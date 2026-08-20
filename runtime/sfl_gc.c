/*
 * Memory for compiled SFL programs.
 *
 * A mark-sweep collector with conservative roots. Tracing through the object
 * graph is precise — every tag's layout is known — but roots are found by
 * scanning the machine stack and the callee-saved registers for anything that
 * looks like a heap pointer. That is what lets generated code stay ordinary
 * code: it keeps no shadow stack of live values, spills nothing on our behalf,
 * and can hold values in registers, because a value live across a call is either
 * spilled to the stack by the ABI or sitting in a callee-saved register that
 * setjmp captures.
 *
 * Every object is one cell of the same size, which makes the free list, the page
 * layout and the "is this a pointer into the heap" test trivial. Anything of
 * variable length — a string's code units, an array's elements — is a side
 * buffer the cell owns and the sweep frees along with it.
 */
#include "sfl.h"

#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------------- */
/* Pages                                                                      */
/* ------------------------------------------------------------------------- */

#define PAGE_BITS 16
#define PAGE_SIZE (1u << PAGE_BITS)
#define PAGE_MASK (~(uintptr_t)(PAGE_SIZE - 1))

#define CELL_ALIGN 16
#define CELL_SIZE ((sizeof(SflObj) + CELL_ALIGN - 1) & ~(size_t)(CELL_ALIGN - 1))

typedef struct SflPage {
  struct SflPage *next;
  uint32_t ncells;
  uint32_t words;      /* bitmap length, in 64-bit words */
  uint64_t *alloc;     /* cell is a live object          */
  uint64_t *mark;      /* reached in this cycle          */
  char *cells;
} SflPage;

static SflPage *pages;
static uint32_t page_count;

/*
 * Address -> page, so a candidate pointer can be checked in constant time.
 * Open addressing on the page-aligned address; sized to stay under half full.
 */
static SflPage **page_index;
static uint32_t page_index_cap;

static uint32_t page_hash(uintptr_t addr) {
  uint64_t h = (uint64_t)(addr >> PAGE_BITS);
  h *= 0x9E3779B97F4A7C15ull;
  return (uint32_t)(h >> 32);
}

static void page_index_insert(SflPage *p) {
  uintptr_t addr = (uintptr_t)p;
  uint32_t mask = page_index_cap - 1;
  uint32_t i = page_hash(addr) & mask;
  while (page_index[i] != NULL) i = (i + 1) & mask;
  page_index[i] = p;
}

static void page_index_grow(void) {
  uint32_t cap = page_index_cap ? page_index_cap * 2 : 64;
  SflPage **old = page_index;
  uint32_t old_cap = page_index_cap;
  page_index = (SflPage **)calloc(cap, sizeof(SflPage *));
  if (!page_index) { fputs("sfl: out of memory\n", stderr); exit(70); }
  page_index_cap = cap;
  for (uint32_t i = 0; i < old_cap; i++)
    if (old[i]) page_index_insert(old[i]);
  free(old);
}

static SflPage *page_of(uintptr_t addr) {
  if (page_index_cap == 0) return NULL;
  uintptr_t base = addr & PAGE_MASK;
  uint32_t mask = page_index_cap - 1;
  uint32_t i = page_hash(base) & mask;
  while (page_index[i] != NULL) {
    if ((uintptr_t)page_index[i] == base) return page_index[i];
    i = (i + 1) & mask;
  }
  return NULL;
}

/* ------------------------------------------------------------------------- */
/* Free list and accounting                                                   */
/* ------------------------------------------------------------------------- */

/* Free cells are chained through the first word; the tag is always rewritten. */
typedef struct FreeCell { struct FreeCell *next; } FreeCell;

static FreeCell *free_list;
static int64_t cells_total;
static int64_t cells_free;
/* Allocated since the last collection, cells and side buffers together. This is
   what decides when to collect; it is not a ledger of what is live. */
static int64_t bytes_since_gc;
static int64_t gc_threshold = 4 * 1024 * 1024;
static int64_t gc_count;
static int gc_disabled;

SflObj sfl_null_obj = {SFL_NULL, 0, {0}};
SflObj sfl_true_obj = {SFL_BOOL, 1, {0}};
SflObj sfl_false_obj = {SFL_BOOL, 0, {0}};
SflObj sfl_tail_marker = {SFL_NULL, 0, {0}};

static void add_page(void) {
  void *raw = NULL;
  if (posix_memalign(&raw, PAGE_SIZE, PAGE_SIZE) != 0 || raw == NULL) {
    fputs("sfl: out of memory\n", stderr);
    exit(70);
  }
  SflPage *p = (SflPage *)raw;
  memset(p, 0, sizeof(SflPage));

  /* Header, then two bitmaps, then as many cells as the rest of the page holds. */
  size_t avail = PAGE_SIZE - sizeof(SflPage);
  /* Each cell needs CELL_SIZE bytes plus 2 bits of bitmap. */
  uint32_t ncells = (uint32_t)((avail * 8) / (CELL_SIZE * 8 + 2));
  uint32_t words = (ncells + 63) / 64;
  while (sizeof(SflPage) + (size_t)words * 16 + (size_t)ncells * CELL_SIZE > PAGE_SIZE) {
    ncells--;
    words = (ncells + 63) / 64;
  }
  char *base = (char *)p + sizeof(SflPage);
  p->alloc = (uint64_t *)base;
  p->mark = (uint64_t *)(base + (size_t)words * 8);
  p->cells = base + (size_t)words * 16;
  /* Cells must stay CELL_ALIGN-aligned for the "is this a cell start" test. */
  size_t off = (size_t)(p->cells - (char *)p);
  size_t pad = (CELL_ALIGN - (off % CELL_ALIGN)) % CELL_ALIGN;
  p->cells += pad;
  while ((size_t)(p->cells - (char *)p) + (size_t)ncells * CELL_SIZE > PAGE_SIZE) ncells--;
  p->ncells = ncells;
  p->words = words;
  memset(p->alloc, 0, (size_t)words * 16);

  p->next = pages;
  pages = p;
  page_count++;
  if (page_count * 2 >= page_index_cap) page_index_grow();
  page_index_insert(p);

  for (uint32_t i = 0; i < ncells; i++) {
    FreeCell *c = (FreeCell *)(p->cells + (size_t)i * CELL_SIZE);
    c->next = free_list;
    free_list = c;
  }
  cells_total += ncells;
  cells_free += ncells;
}

/* ------------------------------------------------------------------------- */
/* Threads                                                                    */
/* ------------------------------------------------------------------------- */

/*
 * Collecting while other threads run means finding their roots too, and their roots
 * are on their stacks. There is no way to read another thread's stack safely while it
 * is using it, so the world is stopped first: every other thread is signalled, and
 * the handler parks it after recording where its stack currently ends and what its
 * callee-saved registers hold. That is the same technique a conservative collector
 * has to use in any language whose compiler does not emit stack maps.
 *
 * Order matters. A collection always happens inside the allocator, which holds the
 * heap lock, so no other thread can be parked in the middle of an allocation while
 * the collector waits for a lock it will never get.
 */
typedef struct SflThread {
  pthread_t tid;
  void *stack_bottom;
  void *stack_top;   /* valid only while parked */
  jmp_buf regs;
  volatile int parked;
  int exiting;
  struct SflThread *next;
  /* Per-thread interpreter state, kept here so the collector can find it. */
  void *state;
  /*
   * The error this thread is currently carrying: message, help lines, traceback.
   * They live in the thread record rather than in thread-local storage because the
   * collector has to be able to find them, and a root registered into TLS would be
   * left pointing at freed memory the moment the thread exits.
   */
  SflVal err[3];
} SflThread;

static SflThread main_thread;
static SflThread *thread_list = &main_thread;
static pthread_mutex_t threads_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t heap_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile int world_stopped;
static int multithreaded;

static _Thread_local SflThread *self_thread;

#define SFL_STOP_SIGNAL SIGUSR2

SflThread *sfl_thread_self(void) { return self_thread; }

/* Parks this thread until the collector is done with it. */
static void stop_handler(int sig) {
  (void)sig;
  SflThread *t = self_thread;
  if (t == NULL) return;
  jmp_buf here;
  setjmp(here);
  memcpy(t->regs, here, sizeof here);
  t->stack_top = (void *)&here;
  __atomic_store_n(&t->parked, 1, __ATOMIC_SEQ_CST);
  while (__atomic_load_n(&world_stopped, __ATOMIC_SEQ_CST)) sched_yield();
  __atomic_store_n(&t->parked, 0, __ATOMIC_SEQ_CST);
}

void sfl_gc_thread_init(void *stack_bottom) {
  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_handler = stop_handler;
  /* Restarting interrupted syscalls keeps blocking reads and waits transparent. */
  sa.sa_flags = SA_RESTART;
  sigemptyset(&sa.sa_mask);
  sigaction(SFL_STOP_SIGNAL, &sa, NULL);

  main_thread.tid = pthread_self();
  main_thread.stack_bottom = stack_bottom;
  self_thread = &main_thread;
}

/* Registers a thread that is about to start running SFL code. */
SflThread *sfl_gc_thread_attach(void *stack_bottom) {
  SflThread *t = (SflThread *)calloc(1, sizeof(SflThread));
  if (!t) { fputs("sfl: out of memory\n", stderr); exit(70); }
  t->tid = pthread_self();
  t->stack_bottom = stack_bottom;
  self_thread = t;
  pthread_mutex_lock(&threads_lock);
  t->next = thread_list;
  thread_list = t;
  multithreaded = 1;
  pthread_mutex_unlock(&threads_lock);
  return t;
}

void sfl_gc_thread_detach(void) {
  SflThread *t = self_thread;
  if (t == NULL || t == &main_thread) return;
  pthread_mutex_lock(&threads_lock);
  SflThread **link = &thread_list;
  while (*link && *link != t) link = &(*link)->next;
  if (*link) *link = t->next;
  pthread_mutex_unlock(&threads_lock);
  self_thread = NULL;
  free(t);
}

void **sfl_thread_state_slot(void) {
  return self_thread ? &self_thread->state : NULL;
}

/* Where this thread parks the error it is raising, for the collector to see. */
SflVal *sfl_thread_error_slots(void) {
  return self_thread ? self_thread->err : main_thread.err;
}

static void stop_the_world(void) {
  if (!multithreaded) return;
  pthread_mutex_lock(&threads_lock);
  __atomic_store_n(&world_stopped, 1, __ATOMIC_SEQ_CST);
  pthread_t me = pthread_self();
  for (SflThread *t = thread_list; t; t = t->next)
    if (!pthread_equal(t->tid, me)) pthread_kill(t->tid, SFL_STOP_SIGNAL);
  for (SflThread *t = thread_list; t; t = t->next)
    if (!pthread_equal(t->tid, me))
      while (!__atomic_load_n(&t->parked, __ATOMIC_SEQ_CST)) sched_yield();
}

static void start_the_world(void) {
  if (!multithreaded) return;
  __atomic_store_n(&world_stopped, 0, __ATOMIC_SEQ_CST);
  pthread_mutex_unlock(&threads_lock);
}

/* ------------------------------------------------------------------------- */
/* Roots                                                                      */
/* ------------------------------------------------------------------------- */

typedef struct { SflVal *base; int64_t count; } RootArray;

static RootArray *roots;
static int64_t roots_len, roots_cap;

static SflVal *protect_stack;
static int64_t protect_len, protect_cap;

static void *stack_bottom;

void sfl_gc_add_roots(SflVal *base, int64_t count) {
  pthread_mutex_lock(&threads_lock);
  if (roots_len == roots_cap) {
    roots_cap = roots_cap ? roots_cap * 2 : 8;
    roots = (RootArray *)realloc(roots, (size_t)roots_cap * sizeof(RootArray));
    if (!roots) { fputs("sfl: out of memory\n", stderr); exit(70); }
  }
  roots[roots_len].base = base;
  roots[roots_len].count = count;
  roots_len++;
  pthread_mutex_unlock(&threads_lock);
}

void sfl_gc_protect(SflVal v) {
  if (protect_len == protect_cap) {
    protect_cap = protect_cap ? protect_cap * 2 : 32;
    protect_stack = (SflVal *)realloc(protect_stack, (size_t)protect_cap * sizeof(SflVal));
    if (!protect_stack) { fputs("sfl: out of memory\n", stderr); exit(70); }
  }
  protect_stack[protect_len++] = v;
}

int64_t sfl_gc_protect_mark(void) { return protect_len; }

void sfl_gc_unprotect(int64_t count) {
  protect_len -= count;
  if (protect_len < 0) protect_len = 0;
}

void sfl_gc_set_stack_bottom(void *p) { stack_bottom = p; }

/* ------------------------------------------------------------------------- */
/* Marking                                                                    */
/* ------------------------------------------------------------------------- */

static SflVal *mark_stack;
static int64_t mark_len, mark_cap;

static void mark_push(SflVal v) {
  if (mark_len == mark_cap) {
    mark_cap = mark_cap ? mark_cap * 2 : 1024;
    mark_stack = (SflVal *)realloc(mark_stack, (size_t)mark_cap * sizeof(SflVal));
    if (!mark_stack) { fputs("sfl: out of memory\n", stderr); exit(70); }
  }
  mark_stack[mark_len++] = v;
}

/* Marks `v` when it is a live heap object we have not reached yet. */
static void mark_value(SflVal v) {
  if (v == NULL) return;
  uintptr_t addr = (uintptr_t)v;
  if (addr & (CELL_ALIGN - 1)) return;
  SflPage *p = page_of(addr);
  if (p == NULL) return; /* a singleton, or not ours at all */
  size_t off = (size_t)((char *)v - p->cells);
  if ((char *)v < p->cells || off % CELL_SIZE != 0) return;
  uint32_t idx = (uint32_t)(off / CELL_SIZE);
  if (idx >= p->ncells) return;
  uint64_t bit = 1ull << (idx & 63);
  if (!(p->alloc[idx >> 6] & bit)) return; /* stale pointer to a free cell */
  if (p->mark[idx >> 6] & bit) return;
  p->mark[idx >> 6] |= bit;
  mark_push(v);
}

static void mark_children(SflVal v) {
  switch (v->tag) {
    case SFL_ARR: {
      SflVal *items = v->u.a.items;
      for (uint32_t i = 0; i < v->aux; i++) mark_value(items[i]);
      break;
    }
    case SFL_TUPLE: {
      /* Same shape as an array: aux elements in a side buffer. Immutability is a
         language rule, not a layout one, so the collector traces it identically. */
      SflVal *items = v->u.t.items;
      for (uint32_t i = 0; i < v->aux; i++) mark_value(items[i]);
      break;
    }
    case SFL_OBJ: {
      for (uint32_t i = 0; i < v->aux; i++) {
        mark_value(v->u.o.keys[i]);
        mark_value(v->u.o.vals[i]);
      }
      break;
    }
    case SFL_FUN:
      mark_value(v->u.f.env);
      break;
    case SFL_ITER:
      mark_value(v->u.it.src);
      mark_value(v->u.it.fn);
      mark_value(v->u.it.pending);
      break;
    case SFL_FRAME: {
      for (uint32_t i = 0; i < v->aux; i++) mark_value(v->u.fr.slots[i]);
      mark_value(v->u.fr.parent);
      break;
    }
    case SFL_HANDLE:
      mark_value(v->u.h.label);
      break;
    default:
      break;
  }
}

static void drain_marks(void) {
  while (mark_len > 0) mark_children(mark_stack[--mark_len]);
}

/* Treats every aligned word in [lo, hi) as a possible pointer. */
static void scan_conservative(void *lo, void *hi) {
  uintptr_t *p = (uintptr_t *)lo;
  uintptr_t *end = (uintptr_t *)hi;
  while (p < end) {
    mark_value((SflVal)(*p));
    p++;
  }
}

/* ------------------------------------------------------------------------- */
/* Sweeping                                                                   */
/* ------------------------------------------------------------------------- */

/* Releases what a dead object owned. Handles are left alone: their payload is
   owned by the subsystem that made them, exactly as in the interpreter. */
static void finalize(SflVal v) {
  switch (v->tag) {
    case SFL_STR:
      sfl_raw_free(v->u.s.chars);
      break;
    case SFL_ARR:
      sfl_raw_free(v->u.a.items);
      break;
    case SFL_TUPLE:
      sfl_raw_free(v->u.t.items);
      break;
    case SFL_OBJ:
      sfl_raw_free(v->u.o.keys);
      sfl_raw_free(v->u.o.vals);
      sfl_raw_free(v->u.o.index);
      break;
    case SFL_FRAME:
      sfl_raw_free(v->u.fr.slots);
      break;
    default:
      break;
  }
}

static void sweep(void) {
  free_list = NULL;
  cells_free = 0;
  for (SflPage *p = pages; p; p = p->next) {
    for (uint32_t w = 0; w < p->words; w++) {
      uint64_t live = p->alloc[w];
      uint64_t marked = p->mark[w];
      uint64_t dead = live & ~marked;
      while (dead) {
        uint32_t b = (uint32_t)__builtin_ctzll(dead);
        dead &= dead - 1;
        finalize((SflVal)(p->cells + ((size_t)(w * 64 + b)) * CELL_SIZE));
      }
      p->alloc[w] = marked;
      p->mark[w] = 0;
    }
    /* Rebuild the free list from whatever the page no longer holds. */
    for (uint32_t i = 0; i < p->ncells; i++) {
      if (!(p->alloc[i >> 6] & (1ull << (i & 63)))) {
        FreeCell *c = (FreeCell *)(p->cells + (size_t)i * CELL_SIZE);
        c->next = free_list;
        free_list = c;
        cells_free++;
      }
    }
  }
}

/* ------------------------------------------------------------------------- */
/* Collection                                                                 */
/* ------------------------------------------------------------------------- */

static void scan_stack(void *lo, void *hi) {
  if (lo < hi) scan_conservative(lo, hi);
  else scan_conservative(hi, lo);
}

void sfl_gc_collect(void) {
  if (gc_disabled) return;
  if (stack_bottom == NULL) return; /* before sfl_init: nothing is reachable yet */

  /* Force callee-saved registers onto the stack so the scan below sees them. */
  jmp_buf regs;
  setjmp(regs);

  stop_the_world();

  void *mine = self_thread ? self_thread->stack_bottom : stack_bottom;
  scan_stack((void *)&regs, mine);
  scan_conservative(&regs, (char *)&regs + sizeof(regs));
  for (SflThread *t = thread_list; t; t = t->next) {
    if (t == self_thread) continue;
    if (t->stack_top == NULL) continue;
    scan_stack(t->stack_top, t->stack_bottom);
    scan_conservative(t->regs, (char *)t->regs + sizeof(t->regs));
  }
  drain_marks();

  for (int64_t i = 0; i < roots_len; i++) {
    SflVal *base = roots[i].base;
    for (int64_t k = 0; k < roots[i].count; k++) mark_value(base[k]);
  }
  for (int64_t i = 0; i < protect_len; i++) mark_value(protect_stack[i]);
  for (SflThread *t = thread_list; t; t = t->next)
    for (int i = 0; i < 3; i++) mark_value(t->err[i]);
  drain_marks();

  sweep();
  start_the_world();
  gc_count++;

  /* Collect again once the program has allocated about as much as it now holds,
     so the cost stays proportional to how much garbage is actually produced. */
  int64_t live = (cells_total - cells_free) * (int64_t)CELL_SIZE;
  gc_threshold = live > 2 * 1024 * 1024 ? live : 2 * 1024 * 1024;
  bytes_since_gc = 0;
}

int64_t sfl_gc_live_bytes(void) {
  return (cells_total - cells_free) * (int64_t)CELL_SIZE;
}

int64_t sfl_gc_collections(void) { return gc_count; }

void sfl_gc_disable(void) { gc_disabled++; }
void sfl_gc_enable(void) { if (gc_disabled > 0) gc_disabled--; }

/* ------------------------------------------------------------------------- */
/* Allocation                                                                 */
/* ------------------------------------------------------------------------- */

SflVal sfl_alloc(uint32_t tag) {
  /* One predictable branch buys thread safety without taxing single-threaded runs. */
  if (multithreaded) pthread_mutex_lock(&heap_lock);
  if (free_list == NULL || bytes_since_gc > gc_threshold) {
    if (bytes_since_gc > gc_threshold) sfl_gc_collect();
    /* Grow when a collection did not free enough to be worth continuing. */
    while (free_list == NULL || cells_free * 4 < cells_total) {
      add_page();
      if (cells_free * 4 >= cells_total) break;
    }
  }
  FreeCell *c = free_list;
  free_list = c->next;
  cells_free--;
  bytes_since_gc += (int64_t)CELL_SIZE;

  SflVal v = (SflVal)c;
  /* Zero first: a collection triggered while this object is half-built must see
     null children rather than whatever the previous occupant left behind. */
  memset(v, 0, CELL_SIZE);
  v->tag = tag;

  SflPage *p = (SflPage *)((uintptr_t)v & PAGE_MASK);
  uint32_t idx = (uint32_t)(((char *)v - p->cells) / CELL_SIZE);
  p->alloc[idx >> 6] |= 1ull << (idx & 63);
  if (multithreaded) pthread_mutex_unlock(&heap_lock);
  return v;
}

void *sfl_raw_alloc(size_t bytes) {
  void *p = malloc(bytes ? bytes : 1);
  if (!p) { fputs("sfl: out of memory\n", stderr); exit(70); }
  bytes_since_gc += (int64_t)bytes;
  return p;
}

void *sfl_raw_realloc(void *p, size_t bytes) {
  void *q = realloc(p, bytes ? bytes : 1);
  if (!q) { fputs("sfl: out of memory\n", stderr); exit(70); }
  bytes_since_gc += (int64_t)bytes;
  return q;
}

void sfl_raw_free(void *p) { free(p); }

/* Bounds the heap for tests and for `--gc-stats`; 0 means "as much as needed". */
void sfl_gc_set_threshold(int64_t bytes) { if (bytes > 0) gc_threshold = bytes; }
