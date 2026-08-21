/*
 * Calling and failing.
 *
 * Every call a compiled program makes on a value it does not know statically goes
 * through sfl_call, which is also where the tail-call trampoline lives: a body
 * ending in a tail call parks its target and returns a marker, and the loop here
 * picks it up, so tail recursion runs in constant stack exactly as it does under
 * the interpreter.
 *
 * Errors are setjmp/longjmp over a handler stack, which is what try() and
 * attempt() push. An error that reaches the bottom is rendered the way the
 * interpreter renders it — message, source excerpt with a caret, help lines,
 * traceback — because the program's own text is linked into the binary.
 */
#include "sfl.h"

#include <math.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/resource.h>
#include <unistd.h>

int64_t sfl_pos;

/* ------------------------------------------------------------------------- */
/* Embedded sources                                                           */
/* ------------------------------------------------------------------------- */

typedef struct { const char *name; const char *text; } Source;

static Source sources[64];
static int source_count;

void sfl_add_source(const char *name, const char *text) {
  if (source_count < (int)(sizeof sources / sizeof sources[0])) {
    sources[source_count].name = name;
    sources[source_count].text = text;
    source_count++;
  }
}

static const char *source_name(int file) {
  return (file >= 0 && file < source_count) ? sources[file].name : "<unknown>";
}

/* The text of one line, without its terminator. Returns length in *len. */
static const char *source_line(int file, int line, int *len) {
  *len = 0;
  if (file < 0 || file >= source_count || line <= 0) return NULL;
  const char *t = sources[file].text;
  int cur = 1;
  while (cur < line && *t) {
    if (*t == '\n') cur++;
    t++;
  }
  if (cur != line) return NULL;
  const char *end = t;
  while (*end && *end != '\n') end++;
  int n = (int)(end - t);
  if (n > 0 && t[n - 1] == '\r') n--;
  *len = n;
  return t;
}

/* ------------------------------------------------------------------------- */
/* Traceback                                                                  */
/* ------------------------------------------------------------------------- */

typedef struct { const char *fn; int32_t file; int32_t line; } TraceFrame;

#define TRACE_MAX 512
_Thread_local TraceFrame trace_stack[TRACE_MAX];
_Thread_local int64_t trace_depth;

/*
 * The interpreter's limit exists because each activation costs native stack.
 * Compiled frames are far smaller, so the ceiling here is only a guard against
 * runaway recursion eating the whole stack; SFL_MAX_DEPTH overrides it.
 */
static int64_t max_depth = 200000;

/*
 * Records an activation. The position comes from sfl_pos, which the caller set
 * just before the call, so a traceback names where each function was called from
 * rather than where it was written — the same thing the interpreter reports.
 */
void sfl_frame_push(const char *fn) {
  if (trace_depth >= max_depth)
    sfl_raise("call stack overflow (depth %lld); check for unbounded recursion",
              (long long)max_depth);
  if (trace_depth < TRACE_MAX) {
    trace_stack[trace_depth].fn = fn;
    trace_stack[trace_depth].file = (int32_t)((sfl_pos >> 48) & 0xFFFF);
    trace_stack[trace_depth].line = (int32_t)((sfl_pos >> 16) & 0xFFFFFFFF);
  }
  trace_depth++;
}

void sfl_frame_pop(void) { trace_depth--; }

/* Raised by the stack check the compiler plants in recursive functions. */
void sfl_stack_overflow(void) {
  sfl_raise("call stack overflow (depth %lld); check for unbounded recursion",
            (long long)trace_depth);
}
int64_t sfl_frame_depth(void) { return trace_depth; }
void sfl_frame_truncate(int64_t depth) { trace_depth = depth; }

void sfl_frame_retarget(const char *fn) {
  if (trace_depth > 0 && trace_depth <= TRACE_MAX) trace_stack[trace_depth - 1].fn = fn;
}

/* ------------------------------------------------------------------------- */
/* Errors                                                                     */
/* ------------------------------------------------------------------------- */

_Thread_local SflHandler *handlers;

/*
 * The error currently travelling. The values themselves live in this thread's
 * record, which the collector already walks; only the position is thread-local,
 * and it holds no pointer.
 */
#define err_roots (sfl_thread_error_slots())
_Thread_local int64_t err_pos;

void sfl_handler_push(SflHandler *h) {
  h->prev = handlers;
  h->frame_depth = trace_depth;
  h->protect_depth = sfl_gc_protect_mark();
  h->err = NULL;
  handlers = h;
}

void sfl_handler_pop(void) {
  if (handlers) handlers = handlers->prev;
}

static SflVal capture_trace(void) {
  SflVal arr = sfl_arr_new(8);
  int64_t upto = trace_depth < TRACE_MAX ? trace_depth : TRACE_MAX;
  for (int64_t i = upto - 1; i >= 0; i--) {
    char line[512];
    snprintf(line, sizeof line, "%s (%s:%d)", trace_stack[i].fn,
             source_name(trace_stack[i].file), trace_stack[i].line);
    sfl_arr_push(arr, sfl_str_utf8(line, -1));
  }
  return arr;
}

/*
 * Renders an error the way the interpreter's Err.render does. Exposed because a
 * thread that failed without ever being awaited has to print the same thing at
 * exit, excerpt and caret included.
 */
void sfl_report_error(SflVal message, SflVal help, SflVal trace, int64_t pos) {
  int file = (int)((pos >> 48) & 0xFFFF);
  int line = (int)((pos >> 16) & 0xFFFFFFFF);
  int col = (int)(pos & 0xFFFF);

  char *msg = sfl_str_dup_utf8_java(message);
  fprintf(stderr, "Runtime error: %s\n", msg);
  sfl_raw_free(msg);

  if (line > 0) {
    fprintf(stderr, "  --> %s:%d", source_name(file), line);
    if (col > 0) fprintf(stderr, ":%d", col);
    fputc('\n', stderr);

    int len = 0;
    const char *text = source_line(file, line, &len);
    if (text && len > 0) {
      char gutter[16];
      snprintf(gutter, sizeof gutter, "%d", line);
      int pad = (int)strlen(gutter);
      fprintf(stderr, "%*s |\n", pad, "");
      fprintf(stderr, "%s | %.*s\n", gutter, len, text);
      fprintf(stderr, "%*s | ", pad, "");
      if (col <= 0) {
        /* No column: underline the whole statement, leading blanks aside. */
        int lead = 0;
        while (lead < len && (text[lead] == ' ' || text[lead] == '\t')) lead++;
        int end = len;
        while (end > lead && (text[end - 1] == ' ' || text[end - 1] == '\t')) end--;
        fprintf(stderr, "%.*s", lead, text);
        int width = end - lead > 0 ? end - lead : 1;
        for (int i = 0; i < width; i++) fputc('^', stderr);
      } else {
        for (int i = 0; i < col - 1 && i < len; i++)
          fputc(text[i] == '\t' ? '\t' : ' ', stderr);
        fputc('^', stderr);
      }
      fputc('\n', stderr);
    }
  }

  if (help) {
    for (uint32_t i = 0; i < help->aux; i++) {
      char *h = sfl_str_dup_utf8_java(help->u.a.items[i]);
      fprintf(stderr, "  help: %s\n", h);
      sfl_raw_free(h);
    }
  }
  if (trace && trace->aux) {
    fprintf(stderr, "  traceback (most recent call first):\n");
    for (uint32_t i = 0; i < trace->aux; i++) {
      char *t = sfl_str_dup_utf8_java(trace->u.a.items[i]);
      fprintf(stderr, "    at %s\n", t);
      sfl_raw_free(t);
    }
  }
}

static void report(void) {
  sfl_report_error(err_roots[0], err_roots[1], err_roots[2], err_pos);
}

static void raise_impl(SflVal message, const char *hint, const char *hint2)
    __attribute__((noreturn));
static void raise_impl(SflVal message, const char *hint, const char *hint2) {
  err_roots[0] = message;
  err_roots[1] = sfl_arr_new(2);
  if (hint && *hint) sfl_arr_push(err_roots[1], sfl_str_utf8(hint, -1));
  if (hint2 && *hint2) sfl_arr_push(err_roots[1], sfl_str_utf8(hint2, -1));
  err_roots[2] = capture_trace();
  err_pos = sfl_pos;

  if (handlers) {
    SflHandler *h = handlers;
    handlers = h->prev;
    trace_depth = h->frame_depth;
    sfl_gc_unprotect(sfl_gc_protect_mark() - h->protect_depth);
    longjmp(h->jb, 1);
  }
  fflush(stdout);
  report();
  exit(1);
}

void sfl_raise(const char *fmt, ...) {
  char buf[1024];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf, sizeof buf, fmt, ap);
  va_end(ap);
  raise_impl(sfl_str_utf8(buf, -1), NULL, NULL);
}

void sfl_raise_hint(const char *msg, const char *hint) {
  raise_impl(sfl_str_utf8(msg, -1), hint, NULL);
}

void sfl_raise_hint2(const char *msg, const char *hint, const char *hint2) {
  raise_impl(sfl_str_utf8(msg, -1), hint, hint2);
}

void sfl_raise_val(SflVal message) {
  raise_impl(sfl_display(message), NULL, NULL);
}

void sfl_raise_val_hint(SflVal message, const char *hint) {
  raise_impl(sfl_display(message), hint, NULL);
}

/*
 * The interpreter's runNative attaches "the signature is ..." to any error a
 * builtin raises about itself ("fn: ...") that carries no other note. Compiled
 * calls reach the primitives directly, so each raise site attaches it here.
 */
void sfl_raise_sig(const char *fn, const char *fmt, ...) {
  char buf[1024];
  int off = snprintf(buf, sizeof buf, "%s: ", fn);
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(buf + off, sizeof buf - (size_t)off, fmt, ap);
  va_end(ap);
  const SflNative *n = sfl_native_find(fn);
  char hint[256];
  hint[0] = '\0';
  if (n) snprintf(hint, sizeof hint, "the signature is %s", n->signature);
  raise_impl(sfl_str_utf8(buf, -1), hint, NULL);
}

/*
 * Unwinds straight to this thread's outermost handler, past every try() and
 * attempt() in between — the way the interpreter's ExitSignal travels, which no
 * user handler may catch. The caller has already recorded what the bottom handler
 * needs; no error state is set here, so the target must not read any.
 */
void sfl_unwind_outermost(void) {
  SflHandler *h = handlers;
  while (h->prev != NULL) h = h->prev;
  handlers = NULL;
  trace_depth = h->frame_depth;
  sfl_gc_unprotect(sfl_gc_protect_mark() - h->protect_depth);
  longjmp(h->jb, 1);
}

/* What try()/attempt() read after catching. */
SflVal sfl_err_message(void) { return err_roots[0] ? err_roots[0] : sfl_str_empty(); }
SflVal sfl_err_help(void) { return err_roots[1] ? err_roots[1] : sfl_arr_new(0); }
SflVal sfl_err_trace(void) { return err_roots[2] ? err_roots[2] : sfl_arr_new(0); }
int32_t sfl_err_line(void) { return (int32_t)((err_pos >> 16) & 0xFFFFFFFF); }
const char *sfl_err_file(void) { return source_name((int)((err_pos >> 48) & 0xFFFF)); }

/* ------------------------------------------------------------------------- */
/* Calling                                                                    */
/* ------------------------------------------------------------------------- */

static SflVal tail_fn;
static SflVal tail_args[64];
static int64_t tail_argc;
static SflVal tail_roots[1];
static int tail_roots_registered;

SflVal sfl_tail_set(SflVal fn, int64_t argc, SflVal *argv) {
  if (!tail_roots_registered) {
    sfl_gc_add_roots(tail_args, 64);
    sfl_gc_add_roots(tail_roots, 1);
    tail_roots_registered = 1;
  }
  if (argc > 64) sfl_raise("a tail call may pass at most 64 arguments, got %lld", (long long)argc);
  tail_fn = fn;
  tail_roots[0] = fn;
  tail_argc = argc;
  for (int64_t i = 0; i < argc; i++) tail_args[i] = argv[i];
  return SFL_TAIL;
}

static void arity_error(const SflProto *p, int64_t got) {
  char expect[64];
  if (p->has_rest) snprintf(expect, sizeof expect, "at least %d", p->required);
  else if (p->required == p->nparams) snprintf(expect, sizeof expect, "%d", p->nparams);
  else snprintf(expect, sizeof expect, "%d to %d", p->required, p->nparams);
  char msg[256], hint[256];
  snprintf(msg, sizeof msg, "%s expects %s argument(s), got %lld", p->name, expect, (long long)got);
  snprintf(hint, sizeof hint, "it is declared as %s", p->signature);
  sfl_raise_hint(msg, hint);
}

/*
 * Binds arguments into a callee's slots and reports how many were supplied, so the
 * generated code knows which defaults it still has to evaluate. Defaults are not
 * filled in here because they are expressions that run in the defining scope.
 */
int64_t sfl_bind_args_raw(SflVal *slots, const SflProto *proto, int64_t argc, SflVal *argv) {
  if (argc < proto->required || (!proto->has_rest && argc > proto->nparams))
    arity_error(proto, argc);

  int64_t positional = argc < proto->fixed ? argc : proto->fixed;
  for (int64_t i = 0; i < positional; i++) slots[i] = argv[i];

  if (proto->has_rest) {
    SflVal rest = sfl_arr_new(argc - proto->fixed > 0 ? argc - proto->fixed : 1);
    for (int64_t i = proto->fixed; i < argc; i++) sfl_arr_push(rest, argv[i]);
    slots[proto->fixed] = rest;
  }
  return positional;
}

int64_t sfl_bind_args(SflVal frame, const SflProto *proto, int64_t argc, SflVal *argv) {
  return sfl_bind_args_raw(frame->u.fr.slots, proto, argc, argv);
}

SflVal *sfl_frame_slots(SflVal frame) { return frame->u.fr.slots; }

/*
 * A builtin used as a value — `map(xs, upper)` — is the same object every time, as
 * it is in the interpreter, so the cache is what makes that true and also what keeps
 * it reachable for the collector.
 */
static SflVal native_cache[512];
static int native_cache_ready;

SflVal sfl_native_value(const char *name) {
  if (!native_cache_ready) {
    sfl_gc_add_roots(native_cache, 512);
    native_cache_ready = 1;
  }
  for (int i = 0; i < sfl_native_count; i++) {
    if (strcmp(sfl_natives[i].name, name) == 0) {
      if (sfl_natives[i].fn == NULL)
        sfl_raise("internal error: builtin '%s' was not linked into this program", name);
      if (i < 512) {
        if (native_cache[i] == NULL) native_cache[i] = sfl_native_new(&sfl_natives[i]);
        return native_cache[i];
      }
      return sfl_native_new(&sfl_natives[i]);
    }
  }
  sfl_raise("undefined variable '%s'", name);
}

static void native_arity(const SflNative *n, int64_t got) {
  if (got >= n->min && (n->max < 0 || got <= n->max)) return;
  char expect[64];
  if (n->max < 0) snprintf(expect, sizeof expect, "at least %d", n->min);
  else if (n->min == n->max) snprintf(expect, sizeof expect, "%d", n->min);
  else snprintf(expect, sizeof expect, "%d to %d", n->min, n->max);
  char msg[256], hint[256];
  snprintf(msg, sizeof msg, "%s expects %s argument(s), got %lld", n->name, expect, (long long)got);
  snprintf(hint, sizeof hint, "the signature is %s", n->signature);
  sfl_raise_hint2(msg, hint, n->doc);
}

static SflVal invoke(SflVal fn, int64_t argc, SflVal *argv) {
  if (fn->tag == SFL_FUN) return fn->u.f.code(fn->u.f.env, argc, argv);
  if (fn->tag == SFL_NATIVE) {
    native_arity(fn->u.n.info, argc);
    /* The table carries a description for every builtin but code only for those the
       program reaches; a missing one is a compiler bug, so say so rather than jump. */
    if (fn->u.n.info->fn == NULL)
      sfl_raise("internal error: builtin '%s' was not linked into this program",
                fn->u.n.info->name);
    return fn->u.n.info->fn(argc, argv);
  }
  {
    SflVal r = sfl_repr(fn);
    char *t = sfl_str_dup_utf8(r);
    char msg[320];
    snprintf(msg, sizeof msg, "this expression is not a function, it is %s %s",
             sfl_type_name(fn), t);
    sfl_raw_free(t);
    const char *extra =
        fn->tag == SFL_ARR ? "to read an element write a[i], not a(i)"
                           : (fn->tag == SFL_OBJ ? "to read a field write o.key or o[key], not o(key)"
                                                 : "");
    sfl_raise_hint(msg, extra);
  }
}

SflVal sfl_call(SflVal fn, int64_t argc, SflVal *argv) {
  SflVal r = invoke(fn, argc, argv);
  while (r == SFL_TAIL) {
    /* Copy out before the next call overwrites the parking area. */
    SflVal next = tail_fn;
    SflVal args[64];
    int64_t n = tail_argc;
    for (int64_t i = 0; i < n; i++) args[i] = tail_args[i];
    r = invoke(next, n, args);
  }
  return r;
}

/* A call to a function the compiler already resolved: same trampoline, no lookup. */
SflVal sfl_call_code(SflCode code, SflVal env, int64_t argc, SflVal *argv) {
  SflVal r = code(env, argc, argv);
  while (r == SFL_TAIL) {
    SflVal next = tail_fn;
    SflVal args[64];
    int64_t n = tail_argc;
    for (int64_t i = 0; i < n; i++) args[i] = tail_args[i];
    r = invoke(next, n, args);
  }
  return r;
}

SflVal sfl_call0(SflVal fn) { return sfl_call(fn, 0, NULL); }
SflVal sfl_call1(SflVal fn, SflVal a) { SflVal v[1] = {a}; return sfl_call(fn, 1, v); }
SflVal sfl_call2(SflVal fn, SflVal a, SflVal b) { SflVal v[2] = {a, b}; return sfl_call(fn, 2, v); }

/* ------------------------------------------------------------------------- */
/* The primitive table                                                        */
/* ------------------------------------------------------------------------- */

/*
 * The table itself is emitted by the compiler into the program, not kept here: a
 * table in the runtime would reference every primitive and so keep every one of
 * them in every binary, however little the program uses.
 */
const SflNative *sfl_native_find(const char *name) {
  for (int i = 0; i < sfl_native_count; i++)
    if (strcmp(sfl_natives[i].name, name) == 0) return &sfl_natives[i];
  return NULL;
}

/* ------------------------------------------------------------------------- */
/* Argument accessors                                                         */
/* ------------------------------------------------------------------------- */

/*
 * Every primitive reaches its arguments through these, so a type mistake reports
 * the builtin, the position and the signature — word for word what the
 * interpreter's Builtins.bad produces.
 */
static void bad_arg(const char *fn, int i, const char *want, SflVal got) __attribute__((noreturn));
static void bad_arg(const char *fn, int i, const char *want, SflVal got) {
  SflVal r = sfl_repr(got);
  char *shown = sfl_str_dup_utf8(r);
  if (strlen(shown) > 48) { shown[45] = '.'; shown[46] = '.'; shown[47] = '.'; shown[48] = '\0'; }
  char msg[512];
  snprintf(msg, sizeof msg, "%s: argument %d must be %s, got %s %s", fn, i + 1, want,
           sfl_type_name(got), shown);
  sfl_raw_free(shown);
  const SflNative *n = sfl_native_find(fn);
  char hint[256];
  hint[0] = '\0';
  if (n) snprintf(hint, sizeof hint, "the signature is %s", n->signature);
  sfl_raise_hint(msg, hint);
}

SflVal sfl_arg_str(SflVal *argv, int i, const char *fn) {
  if (argv[i]->tag == SFL_STR) return argv[i];
  bad_arg(fn, i, "a string", argv[i]);
}

/*
 * Java's (long) cast on a double: NaN becomes 0 and anything out of range clamps to
 * the nearest end, where C's cast would be undefined. Every builtin that keeps an
 * integer result an integer goes through this, so compiled and interpreted output
 * agree even at the extremes.
 */
int64_t sfl_d2l(double d) {
  if (d != d) return 0;
  if (d >= 9223372036854775808.0) return INT64_MAX;
  if (d <= -9223372036854775808.0) return INT64_MIN;
  return (int64_t)d;
}

int64_t sfl_arg_int(SflVal *argv, int i, const char *fn) {
  SflVal v = argv[i];
  if (v->tag == SFL_INT) return v->u.i;
  if (v->tag == SFL_FLOAT && v->u.d == floor(v->u.d) && !isinf(v->u.d))
    return sfl_d2l(v->u.d);
  bad_arg(fn, i, "an integer", v);
}

int32_t sfl_arg_idx(SflVal *argv, int i, const char *fn) {
  int64_t v = sfl_arg_int(argv, i, fn);
  if (v > 2147483647LL || v < -2147483648LL)
    sfl_raise_sig(fn, "index %lld is out of range", (long long)v);
  return (int32_t)v;
}

double sfl_arg_num(SflVal *argv, int i, const char *fn) {
  SflVal v = argv[i];
  if (v->tag == SFL_INT) return (double)v->u.i;
  if (v->tag == SFL_FLOAT) return v->u.d;
  bad_arg(fn, i, "a number", v);
}

SflVal sfl_arg_arr(SflVal *argv, int i, const char *fn) {
  if (argv[i]->tag == SFL_ARR) return argv[i];
  bad_arg(fn, i, "an array", argv[i]);
}

/*
 * A byte array: an SFL array of ints 0..255, copied out for C code to use.
 * An element is accepted exactly where sfl_arg_int would accept it, fractionless
 * floats included, and every complaint matches the interpreter's argBytes word
 * for word. Validation runs before the allocation, so a raise cannot leak it.
 */
void sfl_check_bytes(SflVal *argv, int i, const char *fn) {
  if (argv[i]->tag != SFL_ARR) bad_arg(fn, i, "a byte array", argv[i]);
  SflVal arr = argv[i];
  int64_t n = (int64_t)arr->aux;
  SflVal *items = arr->u.a.items;
  for (int64_t k = 0; k < n; k++) {
    SflVal v = items[k];
    int64_t b;
    if (v->tag == SFL_INT) b = v->u.i;
    else if (v->tag == SFL_FLOAT && v->u.d == floor(v->u.d) && !isinf(v->u.d)) b = sfl_d2l(v->u.d);
    else
      sfl_raise_sig(fn, "argument %d has a non-byte element at index %lld (%s)",
                    i + 1, (long long)k, sfl_type_name(v));
    if (b < 0 || b > 255)
      sfl_raise_sig(fn, "argument %d has %lld at index %lld, expected 0..255",
                    i + 1, (long long)b, (long long)k);
  }
}

uint8_t *sfl_arg_bytes(SflVal *argv, int i, const char *fn, int64_t *len) {
  sfl_check_bytes(argv, i, fn);
  SflVal arr = argv[i];
  int64_t n = (int64_t)arr->aux;
  SflVal *items = arr->u.a.items;
  uint8_t *out = (uint8_t *)sfl_raw_alloc((size_t)(n ? n : 1));
  for (int64_t k = 0; k < n; k++) {
    SflVal v = items[k];
    out[k] = (uint8_t)(v->tag == SFL_INT ? v->u.i : sfl_d2l(v->u.d));
  }
  *len = n;
  return out;
}

SflVal sfl_bytes_arr(const uint8_t *b, int64_t n) {
  SflVal arr = sfl_arr_new(n > 0 ? n : 1);
  for (int64_t k = 0; k < n; k++) sfl_arr_push(arr, sfl_int(b[k]));
  return arr;
}

SflVal sfl_arg_obj(SflVal *argv, int i, const char *fn) {
  if (argv[i]->tag == SFL_OBJ) return argv[i];
  bad_arg(fn, i, "an object", argv[i]);
}

int sfl_arg_bool(SflVal *argv, int i, const char *fn) {
  if (argv[i]->tag == SFL_BOOL) return (int)argv[i]->aux;
  bad_arg(fn, i, "a boolean", argv[i]);
}

SflVal sfl_arg_fn(SflVal *argv, int i, const char *fn) {
  if (argv[i]->tag == SFL_FUN || argv[i]->tag == SFL_NATIVE) return argv[i];
  bad_arg(fn, i, "a function", argv[i]);
}

SflVal sfl_opt(int64_t argc, SflVal *argv, int i) { return i < argc ? argv[i] : sfl_null; }

/* Keeps an integer result an integer when both inputs were, as numResult does. */
SflVal sfl_num_result(double d, int was_int) {
  if (was_int && d == floor(d) && !isinf(d)) return sfl_int(sfl_d2l(d));
  return sfl_float(d);
}

/* ------------------------------------------------------------------------- */
/* Startup                                                                    */
/* ------------------------------------------------------------------------- */

int sfl_argc;
char **sfl_argv;

/*
 * Running out of stack is reported, not crashed on. Compiled frames are small
 * enough that counting calls would cost more than it is worth, so the guard page
 * does the work: the fault lands on an alternate stack and is turned into the same
 * error the interpreter raises.
 */
/* Not SIGSTKSZ: modern glibc makes that a sysconf() call, not a constant, so it
   cannot size a static array. 64 KB is comfortably above it on every platform
   this runs on. */
static char alt_stack[64 * 1024];
static char *stack_low;

/*
 * Where a compiled function must stop recursing. Functions that already count their
 * activations for the traceback need no check; the ones that do not — pure
 * arithmetic, which is exactly where a counter would cost the most — compare their
 * frame address against this instead, three instructions with no memory written.
 * The margin leaves room for the error to be built and for a handler to run.
 *
 * Thread-local, because every thread has its own stack at its own address: the
 * main thread's is sized by RLIMIT_STACK (install_stack_guard), a spawned one by
 * THREAD_STACK_BYTES (sfl_thread_stack_init). One shared boundary would sit in
 * some unrelated part of the address space for every stack but the one that
 * computed it, and fire — or never fire — by accident of layout.
 */
_Thread_local char *sfl_stack_limit;

/* A spawned thread's boundary, from its entry frame and its known stack size. */
void sfl_thread_stack_init(void *bottom, size_t size) {
  sfl_stack_limit = (char *)bottom - size + (256 << 10);
}

static void on_segv(int sig, siginfo_t *info, void *ctx) {
  (void)sig;
  (void)ctx;
  char *addr = (char *)info->si_addr;
  if (addr >= stack_low - (1 << 20) && addr <= stack_low + (1 << 20)) {
    static const char msg[] =
        "Runtime error: call stack overflow; check for unbounded recursion\n";
    ssize_t ignored = write(2, msg, sizeof msg - 1);
    (void)ignored;
    _exit(1);
  }
  static const char msg[] = "Runtime error: segmentation fault\n";
  ssize_t ignored = write(2, msg, sizeof msg - 1);
  (void)ignored;
  _exit(139);
}

static void install_stack_guard(void *bottom) {
  struct rlimit rl;
  size_t size = 8u << 20;
  if (getrlimit(RLIMIT_STACK, &rl) == 0 && rl.rlim_cur != RLIM_INFINITY) size = rl.rlim_cur;
  stack_low = (char *)bottom - size;
  sfl_stack_limit = stack_low + (256 << 10);

  stack_t ss;
  ss.ss_sp = alt_stack;
  ss.ss_size = sizeof alt_stack;
  ss.ss_flags = 0;
  if (sigaltstack(&ss, NULL) != 0) return;

  struct sigaction sa;
  memset(&sa, 0, sizeof sa);
  sa.sa_sigaction = on_segv;
  sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
  sigemptyset(&sa.sa_mask);
  sigaction(SIGSEGV, &sa, NULL);
  sigaction(SIGBUS, &sa, NULL);
}

void sfl_init(int argc, char **argv, void *stack_bottom) {
  sfl_argc = argc;
  sfl_argv = argv;
  sfl_value_init_caches();
  sfl_gc_set_stack_bottom(stack_bottom);
  sfl_gc_thread_init(stack_bottom);
  install_stack_guard(stack_bottom);
  const char *depth = getenv("SFL_MAX_DEPTH");
  if (depth) {
    long long v = atoll(depth);
    if (v > 0) max_depth = v;
  }
}

void sfl_thread_at_exit(void); void sfl_shutdown(void) { fflush(stdout); sfl_thread_at_exit(); }

int main(int argc, char **argv) {
  /* The address of a local in main is the deepest point the collector must scan. */
  void *bottom = (void *)&argv;
  sfl_init(argc, argv, bottom);
  sfl_main();
  sfl_shutdown();
  return 0;
}
