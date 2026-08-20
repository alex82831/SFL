/*
 * Threads, synchronisation and channels.
 *
 * The interpreter builds all of this on the JDK: a Thread per spawn, a
 * ReentrantLock with its conditions per channel, AtomicReference, CountDownLatch,
 * Semaphore. None of those exist here, so each is rebuilt from a pthread mutex and
 * condition variables — but the observable behaviour, down to the wording of every
 * error and the exact moment a blocked thread wakes, is taken from Concurrent.scala
 * and BuiltinsThread.scala rather than from what POSIX happens to make easy.
 *
 * Two things dominate the design.
 *
 * The collector. It stops the world by signalling every registered thread and
 * parking it wherever it stands, then scans the parked stacks conservatively. A
 * thread that runs SFL code without registering would have its stack ignored and
 * its values freed underneath it, so every thread this module starts attaches
 * before it touches a value and detaches on every exit path — including the one
 * where the SFL body raised. Nothing else in here is allowed to be subtle about
 * that.
 *
 * Reachability of what is not on a stack. A handle's payload is an opaque `void *`
 * the collector never looks inside, so a value parked in a channel's queue, in an
 * atomic, or in a not-yet-started thread's argument list is reachable from no stack
 * and no root — it would be swept while a program plainly still holds it. Every
 * such value therefore lives in an SFL_ARR reached from one root array this module
 * registers once (see "Keeping values reachable" below), and the payload structs
 * only ever hold a pointer to a slot inside that graph.
 */

/* POSIX.2008 for clock_gettime and pthread_condattr; Darwin's full set besides. */
#define _POSIX_C_SOURCE 200809L
#define _DARWIN_C_SOURCE 1

#include "sfl.h"

#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------------------- */
/* Small shared helpers                                                       */
/* ------------------------------------------------------------------------- */

static char *dup_cstr(const char *s) {
  size_t n = strlen(s) + 1;
  char *out = (char *)sfl_raw_alloc(n);
  memcpy(out, s, n);
  return out;
}

/* Copies a value's text into a fixed buffer, for a message about to be raised.
   Raising longjmps, so nothing that needs freeing may be alive at that point. */
static void copy_utf8(SflVal s, char *dst, size_t n) {
  char *t = sfl_str_dup_utf8(s);
  snprintf(dst, n, "%s", t);
  sfl_raw_free(t);
}

/*
 * Appends to an array without ever leaving a slot the collector might read before
 * it is written: the element lands first and the length grows after. sfl_arr_push
 * bumps the length in the same expression that stores, which is fine when only one
 * thread can be looking, and this module is exactly the place where that stops
 * being true — another thread can stop the world between the two.
 */
static void arr_append(SflVal a, SflVal v) {
  if ((int32_t)a->aux == a->u.a.cap) {
    /*
     * Grown by hand rather than through realloc. realloc is free to release the
     * old buffer and return a new one, and the store that puts the new pointer
     * back into the object cannot happen until it returns — so for the width of
     * one instruction `items` names memory the allocator has already taken back.
     * A stop signal landing there parks this thread in exactly that state, and
     * the collector then traces `aux` elements through a dangling pointer.
     *
     * Filling a fresh buffer first, publishing it, and only then freeing the old
     * one means `items` points at `aux` readable elements at every instant.
     */
    int32_t cap = a->u.a.cap * 2;
    SflVal *grown = (SflVal *)sfl_raw_alloc((size_t)cap * sizeof(SflVal));
    SflVal *old = a->u.a.items;
    memcpy(grown, old, (size_t)a->aux * sizeof(SflVal));
    __atomic_store_n(&a->u.a.items, grown, __ATOMIC_RELEASE);
    a->u.a.cap = cap;
    sfl_raw_free(old);
  }
  a->u.a.items[a->aux] = v;
  __atomic_store_n(&a->aux, a->aux + 1u, __ATOMIC_RELEASE);
}

/* An array of `n` slots that all read as null, used wherever a fixed-shape record
   has to be a value the collector can trace. */
static SflVal arr_fixed(int64_t n) {
  SflVal a = sfl_arr_new(n > 0 ? n : 1);
  for (int64_t i = 0; i < n; i++) a->u.a.items[i] = sfl_null;
  a->aux = (uint32_t)n;
  return a;
}

/* Unwraps a handle of the expected kind, naming the builtin when it is wrong. The
   message is the interpreter's handleOf, word for word. */
static void *handle_of(SflVal *argv, int i, const char *kind, const char *fn) {
  SflVal v = argv[i];
  if (v->tag == SFL_HANDLE && strcmp(v->u.h.kind, kind) == 0) return v->u.h.payload;
  sfl_raise_sig(fn, "argument %d must be a %s, got %s", i + 1, kind, sfl_type_name(v));
}

/* An optional trailing timeout in milliseconds; -1 means "wait indefinitely".
   An explicit null reads as absent, exactly as optTimeout treats it. */
static int64_t opt_timeout(int64_t argc, SflVal *argv, int i, const char *fn) {
  if (i < (int)argc && argv[i]->tag != SFL_NULL) {
    int64_t ms = sfl_arg_int(argv, i, fn);
    if (ms < 0) sfl_raise_sig(fn, "timeout must not be negative");
    return ms;
  }
  return -1;
}

/* awaitAny's trailing timeout. Deliberately not opt_timeout: there -1 is an error,
   here it is the documented "block until something happens", so every negative is
   normalised to it rather than rejected. */
static int64_t lax_timeout(int64_t argc, SflVal *argv, int i, const char *fn) {
  if (i < (int)argc && argv[i]->tag != SFL_NULL) {
    int64_t ms = sfl_arg_int(argv, i, fn);
    return ms < 0 ? -1 : ms;
  }
  return -1;
}

/* ------------------------------------------------------------------------- */
/* Waiting                                                                    */
/* ------------------------------------------------------------------------- */

/*
 * Every wait in this file is a loop over a predicate with a fixed deadline, never a
 * single call trusted to mean what it returned. Two things make that mandatory:
 * condition variables are allowed to wake spuriously, and the collector's stop
 * signal lands on whatever thread it likes — SA_RESTART covers system calls, but a
 * condition wait interrupted by a handler may still come back early.
 */
static void deadline_in(struct timespec *ts, int64_t ms) {
  clock_gettime(CLOCK_REALTIME, ts);
  ts->tv_sec += (time_t)(ms / 1000);
  ts->tv_nsec += (long)(ms % 1000) * 1000000L;
  if (ts->tv_nsec >= 1000000000L) {
    ts->tv_sec += 1;
    ts->tv_nsec -= 1000000000L;
  }
}

/* Waits once. Returns ETIMEDOUT when the deadline passed, 0 for anything else —
   including an interruption, which the caller's predicate loop absorbs. */
static int wait_once(pthread_cond_t *cv, pthread_mutex_t *m, const struct timespec *dl) {
  int rc = dl ? pthread_cond_timedwait(cv, m, dl) : pthread_cond_wait(cv, m);
  return rc == ETIMEDOUT ? ETIMEDOUT : 0;
}

/* ------------------------------------------------------------------------- */
/* Keeping values reachable                                                   */
/* ------------------------------------------------------------------------- */

/*
 * One root array, registered once, is the whole mechanism.
 *
 *   keep_root[0] -> registry array -> chunk arrays -> the slots
 *
 * A slot is one element of a chunk, and a chunk is an SFL_ARR of a fixed size that
 * is never pushed to again, so the address of an element inside its side buffer
 * never moves and can be handed to a payload struct as a stable `SflVal *`. Chunks
 * are only ever added to the registry, never removed, so a slot stays a valid,
 * traced location for the life of the program — which is what a channel or an
 * atomic needs, since the collector never finalises a handle and so never tells
 * anyone that its payload has died.
 *
 * The alternative — one sfl_gc_add_roots call per channel — would grow the
 * collector's root table from several threads at once while a collection may be
 * reading it, and sfl_gc_add_roots is not written to survive that. Here that call
 * happens exactly once, and provably while the program is still single-threaded:
 * a second thread can only exist because spawn created it, and spawn takes a slot
 * before it creates anything.
 */
#define KEEP_CHUNK 256

static SflVal keep_root[1]; /* [0] is the registry: an array of chunk arrays */
static SflVal keep_chunk;   /* the chunk being filled; also an element of the registry */
static int32_t keep_used = KEEP_CHUNK; /* forces a fresh chunk on the first request */
static int keep_ready;
static pthread_mutex_t keep_lock = PTHREAD_MUTEX_INITIALIZER;

/* Reserves a slot the collector will trace forever. Allocating here can collect,
   which is safe: the collector wants the heap lock and the thread list, never this
   one, and every value in flight is either in a local or already in the registry. */
static SflVal *keep_slot(void) {
  pthread_mutex_lock(&keep_lock);
  if (!keep_ready) {
    keep_root[0] = sfl_arr_new(8);
    sfl_gc_add_roots(keep_root, 1);
    keep_ready = 1;
  }
  if (keep_used == KEEP_CHUNK) {
    SflVal chunk = arr_fixed(KEEP_CHUNK); /* a local until the registry holds it */
    arr_append(keep_root[0], chunk);
    keep_chunk = chunk;
    keep_used = 0;
  }
  SflVal *slot = &keep_chunk->u.a.items[keep_used++];
  *slot = sfl_null;
  pthread_mutex_unlock(&keep_lock);
  return slot;
}

/* ------------------------------------------------------------------------- */
/* Rendering an error a thread hit                                            */
/* ------------------------------------------------------------------------- */

typedef struct { char *p; size_t len, cap; } TextBuf;

static void tb_init(TextBuf *b) {
  b->cap = 256;
  b->len = 0;
  b->p = (char *)sfl_raw_alloc(b->cap);
  b->p[0] = '\0';
}

static void tb_add(TextBuf *b, const char *s) {
  size_t n = strlen(s);
  if (b->len + n + 1 > b->cap) {
    while (b->len + n + 1 > b->cap) b->cap *= 2;
    b->p = (char *)sfl_raw_realloc(b->p, b->cap);
  }
  memcpy(b->p + b->len, s, n + 1);
  b->len += n;
}

/* render_failure's text exists only to reach stderr, so it takes the charset
   encoder's spelling; the message kept for a joiner stays raw and round-trips. */
static void tb_add_val(TextBuf *b, SflVal s) {
  char *t = sfl_str_dup_utf8_java(s);
  tb_add(b, t);
  sfl_raw_free(t);
}

/*
 * The text a thread's failure is shown as when nobody awaited it. The interpreter
 * prints EvalError.render(color = false) here; this reproduces every line of it
 * that a linked runtime can still see. The source excerpt is the one part it
 * cannot: the program's text lives in a table private to sfl_call.c, and only its
 * own reporter can quote it.
 */
static char *render_failure(void) {
  TextBuf b;
  tb_init(&b);
  tb_add(&b, "Runtime error: ");
  tb_add_val(&b, sfl_err_message());

  int32_t line = sfl_err_line();
  if (line > 0) {
    char loc[512];
    snprintf(loc, sizeof loc, "\n  --> %s:%d", sfl_err_file(), (int)line);
    tb_add(&b, loc);
  }
  SflVal help = sfl_err_help();
  for (uint32_t i = 0; i < help->aux; i++) {
    tb_add(&b, "\n  help: ");
    tb_add_val(&b, help->u.a.items[i]);
  }
  SflVal trace = sfl_err_trace();
  if (trace->aux) {
    tb_add(&b, "\n  traceback (most recent call first):");
    for (uint32_t i = 0; i < trace->aux; i++) {
      tb_add(&b, "\n    at ");
      tb_add_val(&b, trace->u.a.items[i]);
    }
  }
  return b.p;
}

/*
 * Re-raises the error that just arrived, after the caller has run the cleanup it
 * owed — withLock releasing its mutex, say. raise_impl already popped the handler
 * `h` and left the error in this thread's slots, so stepping to the next handler by
 * hand is a true rethrow: the message, the help lines and the traceback all survive
 * unchanged. Only when nothing above us catches does it fall back to raising afresh,
 * which is the one path where the runtime has to render it and so must be entered
 * through sfl_raise.
 */
static void rethrow(SflHandler *h) __attribute__((noreturn));
static void rethrow(SflHandler *h) {
  SflHandler *outer = h->prev;
  if (outer != NULL) {
    sfl_handler_pop(); /* the head is `outer`; leave it as outer->prev */
    sfl_frame_truncate(outer->frame_depth);
    sfl_gc_unprotect(sfl_gc_protect_mark() - outer->protect_depth);
    longjmp(outer->jb, 1);
  }
  {
    char msg[1024], hint[512];
    SflVal help = sfl_err_help();
    copy_utf8(sfl_err_message(), msg, sizeof msg);
    hint[0] = '\0';
    if (help->aux) copy_utf8(help->u.a.items[0], hint, sizeof hint);
    sfl_raise_hint(msg, hint);
  }
}

/* ------------------------------------------------------------------------- */
/* Threads                                                                    */
/* ------------------------------------------------------------------------- */

/* 8 MB, matching the main thread, so the call-depth limit means the same
   everywhere — the same reason Concurrent.scala sizes its threads by hand. */
#define THREAD_STACK_BYTES (8u * 1024u * 1024u)

typedef struct Task Task;

/* A worker whose body is C rather than SFL, used by parallelMap. */
typedef SflVal (*CBody)(Task *t);

struct Task {
  struct Task *next; /* the list of every task ever started; never unlinked */
  char *name;        /* "thread-N" */
  int daemon;

  pthread_mutex_t lock;
  pthread_cond_t cv;
  int started;  /* the thread has registered with the collector */
  int finished; /* the body has returned, one way or another */
  int observed; /* somebody awaited it and saw whatever it did */

  /* Set once, before `finished`. NULL failure means it produced a value. */
  char *failure; /* the one-line reason a joiner is shown */
  char *where;   /* "file:line", or "" */
  char *detail;  /* the rendered error, for a failure nobody awaited */

  /*
   * A slot holding this task's own three-element box: [0] the function, [1] its
   * arguments, [2] the result. The box is the only thing keeping the arguments
   * alive between spawn returning and the new thread reaching them, and the only
   * thing keeping the result alive between the thread exiting — at which point its
   * stack stops being scanned — and a joiner asking for it.
   */
  SflVal *slot;

  CBody cbody;
  void *cdata;
};

/* Every task ever started, oldest first and never unlinked, so a failure nobody
   joined can still be reported at exit and a walker can drop the lock between
   steps without the node under it going away. */
static Task *tasks;
static Task *tasks_tail;
static pthread_mutex_t tasks_lock = PTHREAD_MUTEX_INITIALIZER;
static int64_t thread_counter;

/* Who this thread is, for currentThreadName() and for the exit drain. */
static _Thread_local Task *self_task;

/*
 * Completion, announced once per task on one global condition variable. Global
 * because a waiter on N tasks cannot park on N per-task condition variables at
 * once; broadcasting under the same lock awaitAny scans under is what closes the
 * missed-wakeup window between its scan and its wait.
 */
static pthread_mutex_t done_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t done_cv = PTHREAD_COND_INITIALIZER;

static void task_announce_done(void) {
  pthread_mutex_lock(&done_lock);
  pthread_cond_broadcast(&done_cv);
  pthread_mutex_unlock(&done_lock);
}

static SflVal task_box(Task *t) { return *t->slot; }

static Task *task_new(int daemon) {
  Task *t = (Task *)calloc(1, sizeof(Task));
  if (t == NULL) { fputs("sfl: out of memory\n", stderr); exit(70); }
  int64_t n = __atomic_add_fetch(&thread_counter, 1, __ATOMIC_SEQ_CST);
  char name[64];
  snprintf(name, sizeof name, "thread-%lld", (long long)n);
  t->name = dup_cstr(name);
  t->daemon = daemon;
  pthread_mutex_init(&t->lock, NULL);
  pthread_cond_init(&t->cv, NULL);
  t->where = dup_cstr("");
  t->slot = keep_slot();
  /* Every task gets its own box, including the ones whose body is C: task_run
     stores the result into it unconditionally, so it must never be shared and must
     never still be the null singleton when that store happens. */
  *t->slot = arr_fixed(3);
  return t;
}

static void task_publish(Task *t) {
  pthread_mutex_lock(&tasks_lock);
  t->next = NULL;
  if (tasks_tail) tasks_tail->next = t;
  else tasks = t;
  tasks_tail = t;
  pthread_mutex_unlock(&tasks_lock);
}

/* Records what the body raised, in the four shapes Threads.start keeps. */
static void task_record_failure(Task *t) {
  char *msg = sfl_str_dup_utf8(sfl_err_message());
  int32_t line = sfl_err_line();
  char where[512];
  where[0] = '\0';
  if (line > 0) snprintf(where, sizeof where, "%s:%d", sfl_err_file(), (int)line);
  char *detail = render_failure();

  sfl_raw_free(t->where);
  t->where = dup_cstr(where);
  t->detail = detail;
  /* Written last: `failure` is what every reader tests, so nothing may observe it
     before the lines that explain it are in place. */
  __atomic_store_n(&t->failure, msg, __ATOMIC_RELEASE);
}

/*
 * exit() on a spawned thread. The interpreter's ExitSignal skips every try and
 * attempt on its way out and is kept as the one line "the thread called exit(N)" —
 * no location, no rendered detail — so the failure is stored here directly and the
 * unwind jumps straight to task_run's handler rather than travelling as a raise.
 * On the main thread there is no task to fail; the caller stops the process.
 */
void sfl_thread_exit(int code) {
  Task *t = self_task;
  if (t == NULL) return;
  char msg[64];
  snprintf(msg, sizeof msg, "the thread called exit(%d)", code);
  __atomic_store_n(&t->failure, dup_cstr(msg), __ATOMIC_RELEASE);
  sfl_unwind_outermost();
}

/*
 * Lets go of the function and the arguments once the body has run, whichever way
 * it ended. Nothing reads them again — a joiner only ever wants slot [2] — and the
 * slot they sit in is never reclaimed, so leaving them there would retain every
 * argument of every thread a program ever spawned for as long as it runs.
 */
static void task_drop_input(Task *t) {
  SflVal box = task_box(t);
  box->u.a.items[0] = sfl_null;
  box->u.a.items[1] = sfl_null;
}

/* Runs the body with a handler armed, so an error becomes a recorded failure
   rather than tearing down the whole process the way an uncaught raise would. */
static void task_run(Task *arg) {
  Task *volatile t = arg; /* survives the longjmp without the standard's caveats */
  SflHandler h;
  sfl_handler_push(&h);
  if (setjmp(h.jb) == 0) {
    SflVal r;
    if (t->cbody != NULL) {
      r = t->cbody(t);
    } else {
      SflVal box = task_box(t);
      SflVal fn = box->u.a.items[0];
      SflVal args = box->u.a.items[1];
      r = sfl_call(fn, (int64_t)args->aux, args->u.a.items);
    }
    sfl_handler_pop();
    /* Into the traced slot before the stack that holds it stops being scanned. */
    SflVal out = task_box(t);
    out->u.a.items[2] = r;
    task_drop_input(t);
    return;
  }
  /* Already set when the body left through sfl_thread_exit, whose one-line
     failure must not be replaced by a rendered error. */
  if (t->failure == NULL) task_record_failure(t);
  task_drop_input(t);
}

/*
 * The entry point of every thread this module starts.
 *
 * The attach comes first and the detach last, with nothing that touches a value
 * outside them: until the collector knows about this stack it will not scan it, and
 * once it stops knowing it must not. The result is stored while still attached, so
 * it is reachable from the traced slot before this stack disappears.
 */
static void *thread_entry(void *arg) {
  Task *t = (Task *)arg;
  void *bottom = (void *)&t;
  sfl_gc_thread_attach(bottom);
  self_task = t;

  /* Tell the spawner we are registered. It waits for this, which is what keeps the
     collector's allocator from being raced: sfl_alloc only takes the heap lock once
     some thread has attached, so no thread may allocate lock-free after that point.
     Handing the flag over through this mutex also publishes the attach itself. */
  pthread_mutex_lock(&t->lock);
  t->started = 1;
  pthread_cond_broadcast(&t->cv);
  pthread_mutex_unlock(&t->lock);

  task_run(t);

  sfl_gc_thread_detach();

  pthread_mutex_lock(&t->lock);
  /* A release store: awaitAny reads this flag under done_lock, not t->lock, so the
     result and failure written by task_run have to be published with it. */
  __atomic_store_n(&t->finished, 1, __ATOMIC_RELEASE);
  pthread_cond_broadcast(&t->cv);
  pthread_mutex_unlock(&t->lock);
  task_announce_done();
  return NULL;
}

static void thread_at_exit_install(void);

/* Starts `t`, whose slot is already filled in. Never returns with the task both
   published and dead in the water: a thread that could not be created is marked
   finished before the error travels, or the exit drain would wait for it forever. */
static void task_start(Task *t) {
  thread_at_exit_install();
  task_publish(t);

  pthread_attr_t attr;
  pthread_attr_init(&attr);
  pthread_attr_setstacksize(&attr, THREAD_STACK_BYTES);
  pthread_t tid;
  int rc = pthread_create(&tid, &attr, thread_entry, t);
  pthread_attr_destroy(&attr);
  if (rc != 0) {
    pthread_mutex_lock(&t->lock);
    __atomic_store_n(&t->finished, 1, __ATOMIC_RELEASE);
    t->observed = 1;
    pthread_cond_broadcast(&t->cv);
    pthread_mutex_unlock(&t->lock);
    /* Announced before the raise — a raise leaves by longjmp and would never
       come back to wake an awaitAny already waiting on this task. */
    task_announce_done();
    sfl_raise("cannot start %s: %s", t->name, strerror(rc));
  }
  /* Nothing joins these; they release themselves when the body returns. */
  pthread_detach(tid);

  pthread_mutex_lock(&t->lock);
  while (!t->started) wait_once(&t->cv, &t->lock, NULL);
  pthread_mutex_unlock(&t->lock);
}

/* Waits for a task, then re-raises its failure on this thread or returns its
   value. `timeout_ms` of -1 waits indefinitely. */
static SflVal task_join(Task *t, int64_t timeout_ms) {
  struct timespec dl;
  int timed = timeout_ms >= 0;
  if (timed) deadline_in(&dl, timeout_ms);

  pthread_mutex_lock(&t->lock);
  while (!t->finished) {
    if (!timed) {
      wait_once(&t->cv, &t->lock, NULL);
    } else if (wait_once(&t->cv, &t->lock, &dl) == ETIMEDOUT) {
      break;
    }
  }
  int done = t->finished;
  if (done) t->observed = 1;
  pthread_mutex_unlock(&t->lock);

  if (!done)
    /* The interpreter's Threads.join says "join: ...", which is not the name of
       the builtin that ran (await), so it never gains a signature note. */
    sfl_raise("join: %s did not finish within %lldms", t->name, (long long)timeout_ms);

  if (t->failure != NULL) {
    /* One line plus a help note, rather than a whole rendered error nested inside
       another error's message — the shape Threads.join reports. */
    char msg[1024], hint[512];
    snprintf(msg, sizeof msg, "%s failed: %s", t->name, t->failure);
    hint[0] = '\0';
    if (t->where[0] != '\0') snprintf(hint, sizeof hint, "it was raised at %s", t->where);
    sfl_raise_hint(msg, hint);
  }
  return task_box(t)->u.a.items[2];
}

/* Joins without letting the failure escape, for callers that must join everything
   before reporting anything. Returns 1 and fills the buffers when it failed. */
static int task_join_quiet(Task *t, char *msg, size_t msgn, char *hint, size_t hintn) {
  SflHandler h;
  sfl_handler_push(&h);
  if (setjmp(h.jb) == 0) {
    task_join(t, -1);
    sfl_handler_pop();
    return 0;
  }
  copy_utf8(sfl_err_message(), msg, msgn);
  {
    SflVal help = sfl_err_help();
    hint[0] = '\0';
    if (help->aux) copy_utf8(help->u.a.items[0], hint, hintn);
  }
  return 1;
}

/* ------------------------------------------------------------------------- */
/* spawn                                                                      */
/* ------------------------------------------------------------------------- */

static SflVal spawn_impl(int64_t argc, SflVal *argv, int daemon, const char *fn) {
  SflVal f = sfl_arg_fn(argv, 0, fn);
  SflVal args = sfl_arr_new(argc > 1 ? argc - 1 : 1);
  for (int64_t i = 1; i < argc; i++) arr_append(args, argv[i]);

  /* `f` and `args` are locals, so they survive task_new allocating the box; the
     moment they are inside it they are reachable without this frame. */
  Task *t = task_new(daemon);
  SflVal box = task_box(t);
  box->u.a.items[0] = f;
  box->u.a.items[1] = args;

  SflVal label = sfl_str_utf8(t->name, -1);
  SflVal h = sfl_handle_new("thread", t, label);
  task_start(t);
  return h;
}

SflVal sfl_p_spawn(int64_t argc, SflVal *argv) {
  return spawn_impl(argc, argv, 0, "spawn");
}

SflVal sfl_p_spawnDaemon(int64_t argc, SflVal *argv) {
  return spawn_impl(argc, argv, 1, "spawnDaemon");
}

SflVal sfl_p_await(int64_t argc, SflVal *argv) {
  Task *t = (Task *)handle_of(argv, 0, "thread", "await");
  return task_join(t, opt_timeout(argc, argv, 1, "await"));
}

SflVal sfl_p_awaitAll(int64_t argc, SflVal *argv) {
  SflVal arr = sfl_arg_arr(argv, 0, "awaitAll");
  int64_t timeout = opt_timeout(argc, argv, 1, "awaitAll");
  SflVal out = sfl_arr_new(arr->aux ? arr->aux : 1);
  for (uint32_t i = 0; i < arr->aux; i++) {
    SflVal v = arr->u.a.items[i];
    if (v->tag != SFL_HANDLE || strcmp(v->u.h.kind, "thread") != 0)
      sfl_raise_sig("awaitAll", "element %u is %s, not a thread", i + 1, sfl_type_name(v));
    SflVal r = task_join((Task *)v->u.h.payload, timeout);
    arr_append(out, r);
  }
  return out;
}

SflVal sfl_p_awaitAny(int64_t argc, SflVal *argv) {
  SflVal arr = sfl_arg_arr(argv, 0, "awaitAny");
  int64_t timeout = lax_timeout(argc, argv, 1, "awaitAny");
  uint32_t n = arr->aux;

  /* Every element is checked before anything waits, so a bad one is reported even
     when another element finished long ago. The wording is awaitAll's. */
  for (uint32_t i = 0; i < n; i++) {
    SflVal v = arr->u.a.items[i];
    if (v->tag != SFL_HANDLE || strcmp(v->u.h.kind, "thread") != 0)
      sfl_raise_sig("awaitAny", "element %u is %s, not a thread", i + 1, sfl_type_name(v));
  }
  /* Nothing can ever complete, and "wait forever" must not mean it literally. */
  if (n == 0) return sfl_int(-1);

  /* The tasks are captured up front: a Task is malloc'd and never freed, so the
     pointers stay good however long the wait runs, and a program that mutates the
     array mid-wait cannot change what this call is waiting on — the interpreter
     snapshots its array the same way. */
  Task **ts = (Task **)sfl_raw_alloc((size_t)n * sizeof(Task *));
  for (uint32_t i = 0; i < n; i++) ts[i] = (Task *)arr->u.a.items[i]->u.h.payload;

  struct timespec dl;
  int timed = timeout >= 0;
  if (timed) deadline_in(&dl, timeout);

  /*
   * Scan-then-wait under the one lock every completion broadcasts under, so a task
   * finishing between the scan and the wait still wakes it. Nothing in the loop
   * allocates or raises, which is what makes holding done_lock here safe: the
   * collector wants the heap lock and the thread list, never this one. The
   * deadline ends the waiting, not the attempt — one more scan runs after it.
   */
  int64_t found = -1;
  int expired = 0;
  pthread_mutex_lock(&done_lock);
  for (;;) {
    for (uint32_t i = 0; i < n && found < 0; i++)
      if (__atomic_load_n(&ts[i]->finished, __ATOMIC_ACQUIRE)) found = (int64_t)i;
    if (found >= 0 || expired) break;
    if (!timed) {
      wait_once(&done_cv, &done_lock, NULL);
    } else if (wait_once(&done_cv, &done_lock, &dl) == ETIMEDOUT) {
      expired = 1;
    }
  }
  pthread_mutex_unlock(&done_lock);
  sfl_raw_free(ts);

  /* Deliberately not observed and not re-raised: a failed thread merely counts as
     complete here, and a later await reports the failure exactly as it does today. */
  return sfl_int(found);
}

SflVal sfl_p_isAlive(int64_t argc, SflVal *argv) {
  Task *t = (Task *)handle_of(argv, 0, "thread", "isAlive");
  pthread_mutex_lock(&t->lock);
  int alive = !t->finished;
  pthread_mutex_unlock(&t->lock);
  return sfl_bool(alive);
}

SflVal sfl_p_threadName(int64_t argc, SflVal *argv) {
  Task *t = (Task *)handle_of(argv, 0, "thread", "threadName");
  return sfl_str_utf8(t->name, -1);
}

SflVal sfl_p_threadFailed(int64_t argc, SflVal *argv) {
  Task *t = (Task *)handle_of(argv, 0, "thread", "threadFailed");
  return sfl_bool(__atomic_load_n(&t->failure, __ATOMIC_ACQUIRE) != NULL);
}

SflVal sfl_p_currentThreadName(int64_t argc, SflVal *argv) {
  /* The main thread is "main", as it is on the JVM. */
  return sfl_str_utf8(self_task ? self_task->name : "main", -1);
}

SflVal sfl_p_cpuCount(int64_t argc, SflVal *argv) {
  long n = sysconf(_SC_NPROCESSORS_ONLN);
  return sfl_int(n > 1 ? (int64_t)n : 1);
}

SflVal sfl_p_yieldThread(int64_t argc, SflVal *argv) {
  sched_yield();
  return sfl_null;
}

/* ------------------------------------------------------------------------- */
/* parallelMap                                                                */
/* ------------------------------------------------------------------------- */

/*
 * A tiny fork/join: every worker takes the next index nobody has claimed, so the
 * work spreads without a queue and the results still land in order.
 *
 * `box` holds the function, the results and the snapshot, and lives in a local of
 * the calling frame that is still read after the joins — which is what keeps all
 * three traced while the workers use them. The workers reach it through a plain
 * pointer in this struct, which the collector never looks at and never has to.
 */
typedef struct {
  SflVal box; /* [0] fn, [1] results, [2] snapshot */
  volatile int64_t next;
  int64_t count;
} PMap;

static SflVal pmap_worker(Task *t) {
  PMap *p = (PMap *)t->cdata;
  SflVal fn = p->box->u.a.items[0];
  SflVal results = p->box->u.a.items[1];
  SflVal snap = p->box->u.a.items[2];
  for (;;) {
    int64_t idx = __atomic_fetch_add(&p->next, 1, __ATOMIC_SEQ_CST);
    if (idx >= p->count) break;
    SflVal v = sfl_call1(fn, snap->u.a.items[idx]);
    results->u.a.items[idx] = v;
  }
  return sfl_null;
}

SflVal sfl_p_parallelMap(int64_t argc, SflVal *argv) {
  SflVal items = sfl_arg_arr(argv, 0, "parallelMap");
  SflVal f = sfl_arg_fn(argv, 1, "parallelMap");
  int32_t requested;
  if (argc > 2) {
    requested = sfl_arg_idx(argv, 2, "parallelMap");
  } else {
    long n = sysconf(_SC_NPROCESSORS_ONLN);
    requested = n > 1 ? (int32_t)n : 1;
  }
  if (requested <= 0) sfl_raise_sig("parallelMap", "worker count must be positive");

  int64_t count = (int64_t)items->aux;
  int64_t most = count > 1 ? count : 1;
  int64_t workers = requested < most ? requested : most;

  /* The snapshot is taken once, so a worker never reads an array the calling
     program is still changing; the results start out null and are filled in place,
     which is what makes the output keep the input's order. */
  SflVal box = arr_fixed(3);
  box->u.a.items[0] = f;
  box->u.a.items[1] = arr_fixed(count);
  box->u.a.items[2] = sfl_arr_of(count, items->u.a.items);

  PMap *p = (PMap *)calloc(1, sizeof(PMap));
  Task **ts = (Task **)calloc((size_t)workers, sizeof(Task *));
  if (p == NULL || ts == NULL) { fputs("sfl: out of memory\n", stderr); exit(70); }
  p->box = box;
  p->count = count;

  /*
   * Starting can fail part way through, and an error leaves by longjmp — which
   * would carry the unwind straight past this frame while the workers already
   * running still hold `p->box`. That box is reachable from nothing else: `p` is
   * malloc'd, so the collector never looks at it, and the only traced reference
   * is the local below. Letting the error out here would hand those threads a
   * box the next collection is free to sweep.
   *
   * So the error is caught, every task that was made is joined with this frame —
   * and the box in it — still alive, and only then is it allowed to travel on.
   */
  volatile int64_t made = 0;
  int start_failed = 0;
  SflHandler sh;
  sfl_handler_push(&sh);
  if (setjmp(sh.jb) == 0) {
    while (made < workers) {
      Task *t = task_new(0);
      t->cbody = pmap_worker;
      t->cdata = p;
      ts[made] = t;
      /* Counted before it is started: a task whose thread could not be created
         is marked finished by task_start, so joining it is safe and keeps the
         count honest no matter where the raise came from. */
      made++;
      task_start(t);
    }
    sfl_handler_pop();
  } else {
    start_failed = 1;
  }

  /* Join every worker even if one failed, so no thread is left running. */
  char msg[1024], hint[512];
  int failed = 0;
  for (int64_t i = 0; i < made; i++) {
    char m[1024], hn[512];
    if (task_join_quiet(ts[i], m, sizeof m, hn, sizeof hn) && !failed) {
      failed = 1;
      memcpy(msg, m, sizeof msg);
      memcpy(hint, hn, sizeof hint);
    }
  }
  /* Every worker has finished by now, so nothing can still be reading either of
     these — and freeing before the raise below is the only chance to, since a raise
     leaves by longjmp. */
  free(ts);
  free(p);
  /* The start failure is the cause; rethrowing it keeps its traceback intact,
     and it outranks anything a worker went on to report. */
  if (start_failed) rethrow(&sh);
  if (failed) sfl_raise_hint(msg, hint);

  /* Rebuilt rather than returned directly, so the array handed back is this call's
     own and the box stays the only thing the workers ever shared. */
  SflVal results = box->u.a.items[1];
  return sfl_arr_of(count, results->u.a.items);
}

/* ------------------------------------------------------------------------- */
/* Exit                                                                       */
/* ------------------------------------------------------------------------- */

/*
 * A script never exits out from under work it started, and a worker that crashed is
 * never allowed to vanish silently. Ordinary threads are waited for; daemon threads
 * are not, which is the whole point of them.
 *
 * This runs from sfl_shutdown on the ordinary path. It is also registered with
 * atexit, so that exit() — which leaves through exit(3) and never reaches
 * sfl_shutdown — still waits and still reports. Only the sfl_shutdown call knows the
 * program meant to succeed, so only it turns an unobserved failure into a non-zero
 * status.
 */
static int drain_done;
static int at_exit_installed;

static int drain_threads(int wait_for_them) {
  if (__atomic_exchange_n(&drain_done, 1, __ATOMIC_SEQ_CST)) return 0;

  /* Walked without the list lock held across a wait: a live thread may still be
     spawning, and it needs that lock. Nodes are never unlinked, so a pointer read
     under the lock stays good after it is dropped. */
  pthread_mutex_lock(&tasks_lock);
  Task *t = wait_for_them ? tasks : NULL;
  pthread_mutex_unlock(&tasks_lock);
  while (t != NULL) {
    /* Never wait for the thread doing the waiting. */
    if (!t->daemon && t != self_task) {
      pthread_mutex_lock(&t->lock);
      while (!t->finished) wait_once(&t->cv, &t->lock, NULL);
      pthread_mutex_unlock(&t->lock);
    }
    pthread_mutex_lock(&tasks_lock);
    t = t->next;
    pthread_mutex_unlock(&tasks_lock);
  }

  int n = 0;
  pthread_mutex_lock(&tasks_lock);
  Task *cur = tasks;
  pthread_mutex_unlock(&tasks_lock);
  while (cur != NULL) {
    pthread_mutex_lock(&cur->lock);
    int report = cur->failure != NULL && !cur->observed;
    if (report) cur->observed = 1;
    pthread_mutex_unlock(&cur->lock);
    if (report) {
      fflush(stdout);
      fprintf(stderr, "warning: %s failed and was never awaited:\n", cur->name);
      fprintf(stderr, "%s\n", cur->detail ? cur->detail : cur->failure);
      n++;
    }
    pthread_mutex_lock(&tasks_lock);
    cur = cur->next;
    pthread_mutex_unlock(&tasks_lock);
  }
  return n;
}

/*
 * Reached only when the program left through exit(3) — the exit() builtin, or a
 * failure deep in the runtime — rather than by running to its end. Waiting for the
 * other threads is right when the program's own thread is the one leaving, and
 * wrong when a spawned thread is: it would be waiting for work that may be waiting
 * on it, and the interpreter would not have ended the process at all in that case.
 */
static void at_exit_drain(void) { (void)drain_threads(self_task == NULL); }

static void thread_at_exit_install(void) {
  if (__atomic_exchange_n(&at_exit_installed, 1, __ATOMIC_SEQ_CST)) return;
  atexit(at_exit_drain);
}

/* Called from sfl_shutdown, which is only reached when the program ran to its end. */
void sfl_thread_at_exit(void) {
  if (drain_threads(1) > 0) {
    fflush(NULL);
    exit(1);
  }
}

/* ------------------------------------------------------------------------- */
/* Channels                                                                   */
/* ------------------------------------------------------------------------- */

/*
 * A bounded or unbounded queue with proper close semantics, written against one
 * mutex and two conditions rather than around a ready-made queue, because closing
 * has to wake everyone currently blocked — something no plain queue can express
 * without either a poison value or polling.
 *
 * The queued values live in a ring that is an SFL_ARR held in a traced slot, and
 * every slot a value leaves is written back to null, so a received value stops being
 * reachable through the channel the moment it is handed over.
 */
typedef struct {
  pthread_mutex_t lock;
  pthread_cond_t not_empty;
  pthread_cond_t not_full;
  SflVal *slot;     /* the ring: an SFL_ARR whose length is its capacity */
  int32_t head;     /* index of the oldest value */
  int32_t count;    /* how many are queued */
  int32_t ring;     /* the ring's length */
  int32_t capacity; /* what the program asked for; 0 means unbounded */
  int closed;
} Chan;

/* Doubles the ring, oldest value first so the order survives. Allocating here can
   collect while this channel's lock is held, which is safe: the collector needs the
   heap lock and the thread list, and this thread holds neither. */
static void chan_grow(Chan *c) {
  int32_t ncap = c->ring * 2;
  SflVal next = arr_fixed(ncap);
  SflVal old = *c->slot;
  for (int32_t i = 0; i < c->count; i++)
    next->u.a.items[i] = old->u.a.items[(c->head + i) % c->ring];
  *c->slot = next;
  c->head = 0;
  c->ring = ncap;
}

static void chan_push(Chan *c, SflVal v) {
  if (c->count == c->ring) chan_grow(c);
  SflVal ring = *c->slot;
  ring->u.a.items[(c->head + c->count) % c->ring] = v;
  c->count++;
}

static SflVal chan_poll(Chan *c) {
  SflVal ring = *c->slot;
  SflVal v = ring->u.a.items[c->head];
  ring->u.a.items[c->head] = sfl_null; /* stop holding what we just handed out */
  c->head = (c->head + 1) % c->ring;
  c->count--;
  return v;
}

SflVal sfl_p_channel(int64_t argc, SflVal *argv) {
  int32_t cap = argc > 0 ? sfl_arg_idx(argv, 0, "channel") : 0;
  if (cap < 0) sfl_raise_sig("channel", "capacity must not be negative");

  Chan *c = (Chan *)calloc(1, sizeof(Chan));
  if (c == NULL) { fputs("sfl: out of memory\n", stderr); exit(70); }
  pthread_mutex_init(&c->lock, NULL);
  pthread_cond_init(&c->not_empty, NULL);
  pthread_cond_init(&c->not_full, NULL);
  c->capacity = cap;
  c->ring = cap > 0 && cap < 1024 ? cap : 16;
  c->slot = keep_slot();
  *c->slot = arr_fixed(c->ring);

  char label[64];
  if (cap > 0) snprintf(label, sizeof label, "cap=%d", (int)cap);
  else snprintf(label, sizeof label, "unbounded");
  return sfl_handle_new("channel", c, sfl_str_utf8(label, -1));
}

SflVal sfl_p_send(int64_t argc, SflVal *argv) {
  Chan *c = (Chan *)handle_of(argv, 0, "channel", "send");
  SflVal v = argv[1];
  pthread_mutex_lock(&c->lock);
  while (c->capacity > 0 && c->count >= c->capacity && !c->closed)
    wait_once(&c->not_full, &c->lock, NULL);
  if (c->closed) {
    /* Out of the lock before raising: a raise leaves by longjmp and would never
       come back to release it. */
    pthread_mutex_unlock(&c->lock);
    sfl_raise_sig("send", "the channel is closed");
  }
  chan_push(c, v);
  pthread_cond_signal(&c->not_empty);
  pthread_mutex_unlock(&c->lock);
  return sfl_null;
}

/* NULL when the timeout expired, or when the channel is closed and drained. */
static SflVal chan_receive(Chan *c, int64_t timeout_ms) {
  struct timespec dl;
  int timed = timeout_ms >= 0;
  if (timed) deadline_in(&dl, timeout_ms);

  pthread_mutex_lock(&c->lock);
  SflVal out = NULL;
  /* The deadline ends the waiting, not the attempt: a send that lands in the
     instant the wait gives up has still put a value in the queue, and returning
     empty-handed while holding the lock over it would lose it. So the predicate
     is tested once more before the loop is left. */
  int expired = 0;
  for (;;) {
    if (c->count > 0) {
      out = chan_poll(c);
      pthread_cond_signal(&c->not_full);
      break;
    }
    if (c->closed || expired) break;
    if (!timed) {
      wait_once(&c->not_empty, &c->lock, NULL);
    } else if (wait_once(&c->not_empty, &c->lock, &dl) == ETIMEDOUT) {
      expired = 1;
    }
  }
  pthread_mutex_unlock(&c->lock);
  return out;
}

SflVal sfl_p_receive(int64_t argc, SflVal *argv) {
  Chan *c = (Chan *)handle_of(argv, 0, "channel", "receive");
  SflVal v = chan_receive(c, opt_timeout(argc, argv, 1, "receive"));
  return v ? v : sfl_null;
}

SflVal sfl_p_tryReceive(int64_t argc, SflVal *argv) {
  Chan *c = (Chan *)handle_of(argv, 0, "channel", "tryReceive");
  pthread_mutex_lock(&c->lock);
  SflVal v = NULL;
  if (c->count > 0) {
    v = chan_poll(c);
    pthread_cond_signal(&c->not_full);
  }
  pthread_mutex_unlock(&c->lock);
  return v ? v : sfl_null;
}

SflVal sfl_p_closeChannel(int64_t argc, SflVal *argv) {
  Chan *c = (Chan *)handle_of(argv, 0, "channel", "closeChannel");
  pthread_mutex_lock(&c->lock);
  c->closed = 1;
  /* Everyone, not one of each: a close has to release every blocked thread, and
     the ones waiting to send will never be woken by a receive again. */
  pthread_cond_broadcast(&c->not_empty);
  pthread_cond_broadcast(&c->not_full);
  pthread_mutex_unlock(&c->lock);
  return sfl_null;
}

SflVal sfl_p_channelClosed(int64_t argc, SflVal *argv) {
  Chan *c = (Chan *)handle_of(argv, 0, "channel", "channelClosed");
  pthread_mutex_lock(&c->lock);
  int closed = c->closed;
  pthread_mutex_unlock(&c->lock);
  return sfl_bool(closed);
}

SflVal sfl_p_channelSize(int64_t argc, SflVal *argv) {
  Chan *c = (Chan *)handle_of(argv, 0, "channel", "channelSize");
  pthread_mutex_lock(&c->lock);
  int32_t n = c->count;
  pthread_mutex_unlock(&c->lock);
  return sfl_int(n);
}

SflVal sfl_p_channelDrain(int64_t argc, SflVal *argv) {
  Chan *c = (Chan *)handle_of(argv, 0, "channel", "channelDrain");
  /* Built before the lock is taken: growing it only reallocates a side buffer,
     which never collects, so nothing inside the lock can start a collection. */
  SflVal out = sfl_arr_new(16);
  pthread_mutex_lock(&c->lock);
  while (c->count > 0) arr_append(out, chan_poll(c));
  pthread_cond_broadcast(&c->not_full);
  pthread_mutex_unlock(&c->lock);
  return out;
}

SflVal sfl_p_channelToArray(int64_t argc, SflVal *argv) {
  Chan *c = (Chan *)handle_of(argv, 0, "channel", "channelToArray");
  SflVal out = sfl_arr_new(16);
  for (;;) {
    SflVal v = chan_receive(c, -1);
    if (v == NULL) break; /* closed and drained */
    arr_append(out, v);
  }
  return out;
}

/* ------------------------------------------------------------------------- */
/* Mutexes                                                                    */
/* ------------------------------------------------------------------------- */

/*
 * A reentrant lock, built from a plain mutex and a condition rather than from
 * PTHREAD_MUTEX_RECURSIVE, because the behaviour that has to match is
 * ReentrantLock's: unlock() must be able to say "this thread does not hold the
 * lock", and tryLock(ms) must be able to give up after a while — and neither
 * pthread_mutex_timedlock nor a reliable owner query exists everywhere this has to
 * build.
 */
typedef struct {
  pthread_mutex_t m; /* guards the fields below, never held across a call */
  pthread_cond_t cv;
  pthread_t owner;
  int held;
  int64_t depth;
} Mutex;

static int mutex_mine(Mutex *m) { return m->held && pthread_equal(m->owner, pthread_self()); }

static void mutex_take(Mutex *m) {
  if (m->held) {
    m->depth++;
  } else {
    m->held = 1;
    m->owner = pthread_self();
    m->depth = 1;
  }
}

SflVal sfl_p_mutex(int64_t argc, SflVal *argv) {
  Mutex *m = (Mutex *)calloc(1, sizeof(Mutex));
  if (m == NULL) { fputs("sfl: out of memory\n", stderr); exit(70); }
  pthread_mutex_init(&m->m, NULL);
  pthread_cond_init(&m->cv, NULL);
  return sfl_handle_new("mutex", m, sfl_str_empty());
}

static void mutex_lock(Mutex *m) {
  pthread_mutex_lock(&m->m);
  while (m->held && !pthread_equal(m->owner, pthread_self()))
    wait_once(&m->cv, &m->m, NULL);
  mutex_take(m);
  pthread_mutex_unlock(&m->m);
}

/* Returns 0 when this thread does not hold the lock, which is the one case the
   caller has to turn into an error. */
static int mutex_unlock(Mutex *m) {
  pthread_mutex_lock(&m->m);
  if (!mutex_mine(m)) {
    pthread_mutex_unlock(&m->m);
    return 0;
  }
  if (--m->depth == 0) {
    m->held = 0;
    pthread_cond_signal(&m->cv);
  }
  pthread_mutex_unlock(&m->m);
  return 1;
}

SflVal sfl_p_lock(int64_t argc, SflVal *argv) {
  mutex_lock((Mutex *)handle_of(argv, 0, "mutex", "lock"));
  return sfl_null;
}

SflVal sfl_p_unlock(int64_t argc, SflVal *argv) {
  Mutex *m = (Mutex *)handle_of(argv, 0, "mutex", "unlock");
  if (!mutex_unlock(m)) sfl_raise_sig("unlock", "this thread does not hold the lock");
  return sfl_null;
}

SflVal sfl_p_tryLock(int64_t argc, SflVal *argv) {
  Mutex *m = (Mutex *)handle_of(argv, 0, "mutex", "tryLock");
  int64_t ms = opt_timeout(argc, argv, 1, "tryLock");
  struct timespec dl;
  int timed = ms >= 0;
  if (timed) deadline_in(&dl, ms);

  pthread_mutex_lock(&m->m);
  int ok = 0;
  int expired = 0; /* the deadline ends the waiting; the lock is tried once more */
  for (;;) {
    if (!m->held || pthread_equal(m->owner, pthread_self())) {
      mutex_take(m);
      ok = 1;
      break;
    }
    if (!timed) break; /* tryLock() without a timeout never waits */
    if (expired) break;
    if (wait_once(&m->cv, &m->m, &dl) == ETIMEDOUT) expired = 1;
  }
  pthread_mutex_unlock(&m->m);
  return sfl_bool(ok);
}

SflVal sfl_p_withLock(int64_t argc, SflVal *argv) {
  Mutex *m = (Mutex *)handle_of(argv, 0, "mutex", "withLock");
  SflVal f = sfl_arg_fn(argv, 1, "withLock");
  mutex_lock(m);
  /*
   * The lock is released even when f raises, which is the whole reason to prefer
   * withLock over lock/unlock. Catching is the only way to run anything on the way
   * out — an error leaves by longjmp — so the error is caught here, the lock is
   * dropped, and the error carries on to whoever was going to handle it.
   */
  SflHandler h;
  sfl_handler_push(&h);
  if (setjmp(h.jb) == 0) {
    SflVal r = sfl_call0(f);
    sfl_handler_pop();
    mutex_unlock(m);
    return r;
  }
  mutex_unlock(m);
  rethrow(&h);
}

/* ------------------------------------------------------------------------- */
/* Semaphores and latches                                                     */
/* ------------------------------------------------------------------------- */

typedef struct {
  pthread_mutex_t m;
  pthread_cond_t cv;
  int64_t permits;
} Sem;

SflVal sfl_p_semaphore(int64_t argc, SflVal *argv) {
  /* A negative count is allowed, as it is for java.util.concurrent.Semaphore: it
     means that many releases have to happen before anyone gets through. */
  int32_t permits = sfl_arg_idx(argv, 0, "semaphore");
  Sem *s = (Sem *)calloc(1, sizeof(Sem));
  if (s == NULL) { fputs("sfl: out of memory\n", stderr); exit(70); }
  pthread_mutex_init(&s->m, NULL);
  pthread_cond_init(&s->cv, NULL);
  s->permits = permits;
  return sfl_handle_new("semaphore", s, sfl_str_empty());
}

SflVal sfl_p_acquire(int64_t argc, SflVal *argv) {
  Sem *s = (Sem *)handle_of(argv, 0, "semaphore", "acquire");
  int64_t ms = opt_timeout(argc, argv, 1, "acquire");
  struct timespec dl;
  int timed = ms >= 0;
  if (timed) deadline_in(&dl, ms);

  pthread_mutex_lock(&s->m);
  int got = 0;
  /* Checked once more after the deadline, for the same reason acquire(ms) on a
     java.util.concurrent.Semaphore succeeds when a release beat the timeout by a
     hair: a permit that is there to be taken must not be left stranded because
     the waiter happened to give up in the same instant. */
  int expired = 0;
  for (;;) {
    if (s->permits > 0) {
      s->permits--;
      got = 1;
      break;
    }
    if (expired) break;
    if (!timed) {
      wait_once(&s->cv, &s->m, NULL);
    } else if (wait_once(&s->cv, &s->m, &dl) == ETIMEDOUT) {
      expired = 1;
    }
  }
  pthread_mutex_unlock(&s->m);
  return sfl_bool(got);
}

SflVal sfl_p_release(int64_t argc, SflVal *argv) {
  Sem *s = (Sem *)handle_of(argv, 0, "semaphore", "release");
  pthread_mutex_lock(&s->m);
  s->permits++;
  pthread_cond_signal(&s->cv);
  pthread_mutex_unlock(&s->m);
  return sfl_null;
}

typedef struct {
  pthread_mutex_t m;
  pthread_cond_t cv;
  int64_t count;
} Latch;

SflVal sfl_p_latch(int64_t argc, SflVal *argv) {
  int32_t n = sfl_arg_idx(argv, 0, "latch");
  if (n < 0) sfl_raise_sig("latch", "count must not be negative");
  Latch *l = (Latch *)calloc(1, sizeof(Latch));
  if (l == NULL) { fputs("sfl: out of memory\n", stderr); exit(70); }
  pthread_mutex_init(&l->m, NULL);
  pthread_cond_init(&l->cv, NULL);
  l->count = n;
  char label[32];
  snprintf(label, sizeof label, "n=%d", (int)n);
  return sfl_handle_new("latch", l, sfl_str_utf8(label, -1));
}

SflVal sfl_p_countDown(int64_t argc, SflVal *argv) {
  Latch *l = (Latch *)handle_of(argv, 0, "latch", "countDown");
  pthread_mutex_lock(&l->m);
  if (l->count > 0 && --l->count == 0) pthread_cond_broadcast(&l->cv);
  pthread_mutex_unlock(&l->m);
  return sfl_null;
}

SflVal sfl_p_awaitLatch(int64_t argc, SflVal *argv) {
  Latch *l = (Latch *)handle_of(argv, 0, "latch", "awaitLatch");
  int64_t ms = opt_timeout(argc, argv, 1, "awaitLatch");
  struct timespec dl;
  int timed = ms >= 0;
  if (timed) deadline_in(&dl, ms);

  pthread_mutex_lock(&l->m);
  while (l->count > 0) {
    if (!timed) {
      wait_once(&l->cv, &l->m, NULL);
    } else if (wait_once(&l->cv, &l->m, &dl) == ETIMEDOUT) {
      break;
    }
  }
  int reached = l->count == 0;
  pthread_mutex_unlock(&l->m);
  return sfl_bool(reached);
}

SflVal sfl_p_latchCount(int64_t argc, SflVal *argv) {
  Latch *l = (Latch *)handle_of(argv, 0, "latch", "latchCount");
  pthread_mutex_lock(&l->m);
  int64_t n = l->count;
  pthread_mutex_unlock(&l->m);
  return sfl_int(n);
}

/* ------------------------------------------------------------------------- */
/* Atomics                                                                    */
/* ------------------------------------------------------------------------- */

/*
 * The value itself lives in a traced slot, so a compare-and-swap on that slot is
 * both the atomic operation and the update of a location the collector reads. The
 * collector only reads it with the world stopped, so it can never see a half-written
 * pointer, and the old value stays in place until the swap wins, so a collection in
 * the middle of a retry loop still finds everything.
 */
typedef struct { SflVal *slot; } Atom;

SflVal sfl_p_atomic(int64_t argc, SflVal *argv) {
  SflVal initial = sfl_opt(argc, argv, 0);
  Atom *a = (Atom *)calloc(1, sizeof(Atom));
  if (a == NULL) { fputs("sfl: out of memory\n", stderr); exit(70); }
  a->slot = keep_slot();
  *a->slot = initial;
  return sfl_handle_new("atomic", a, sfl_str_empty());
}

SflVal sfl_p_atomicGet(int64_t argc, SflVal *argv) {
  Atom *a = (Atom *)handle_of(argv, 0, "atomic", "atomicGet");
  return __atomic_load_n(a->slot, __ATOMIC_SEQ_CST);
}

SflVal sfl_p_atomicSet(int64_t argc, SflVal *argv) {
  Atom *a = (Atom *)handle_of(argv, 0, "atomic", "atomicSet");
  __atomic_store_n(a->slot, argv[1], __ATOMIC_SEQ_CST);
  return sfl_null;
}

SflVal sfl_p_atomicAdd(int64_t argc, SflVal *argv) {
  Atom *a = (Atom *)handle_of(argv, 0, "atomic", "atomicAdd");
  SflVal delta = argv[1];
  for (;;) {
    SflVal cur = __atomic_load_n(a->slot, __ATOMIC_SEQ_CST);
    SflVal next = sfl_arith(0, cur, delta); /* 0 is +, matching BinOp.Add */
    if (__atomic_compare_exchange_n(a->slot, &cur, next, 0, __ATOMIC_SEQ_CST,
                                    __ATOMIC_SEQ_CST))
      return next;
  }
}

SflVal sfl_p_atomicUpdate(int64_t argc, SflVal *argv) {
  Atom *a = (Atom *)handle_of(argv, 0, "atomic", "atomicUpdate");
  SflVal f = sfl_arg_fn(argv, 1, "atomicUpdate");
  for (;;) {
    SflVal cur = __atomic_load_n(a->slot, __ATOMIC_SEQ_CST);
    /* f runs outside any lock and may run more than once, exactly as documented:
       another thread winning the swap means starting over with its value. */
    SflVal next = sfl_call1(f, cur);
    if (__atomic_compare_exchange_n(a->slot, &cur, next, 0, __ATOMIC_SEQ_CST,
                                    __ATOMIC_SEQ_CST))
      return next;
  }
}

SflVal sfl_p_atomicCompareSet(int64_t argc, SflVal *argv) {
  Atom *a = (Atom *)handle_of(argv, 0, "atomic", "atomicCompareSet");
  /* Compared by value rather than identity, which is what scripts expect; the swap
     itself is still by identity, so it fails if anyone else got in first. */
  SflVal cur = __atomic_load_n(a->slot, __ATOMIC_SEQ_CST);
  if (!sfl_equal(cur, argv[1])) return sfl_false;
  return sfl_bool(__atomic_compare_exchange_n(a->slot, &cur, argv[2], 0,
                                              __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST));
}
