/*
 * The SFL runtime: the value model, memory manager and primitive library that
 * compiled SFL programs are linked against.
 *
 * The compiler emits two kinds of code. Where inference proves a machine type it
 * emits unboxed i64/double/i1 arithmetic that never touches this runtime, which is
 * why numeric loops run at C speed. Everywhere else it emits `SflVal` — a pointer
 * to a garbage-collected object — and calls the functions declared here. The two
 * worlds meet at sfl_box_* / sfl_unbox_*.
 *
 * Everything in here exists to make compiled programs behave exactly as the
 * interpreter does, down to the text of error messages and the way floats print.
 * Where a choice was available, the interpreter's behaviour is the specification.
 */
#ifndef SFL_RUNTIME_H
#define SFL_RUNTIME_H

#include <setjmp.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------------- */
/* Values                                                                     */
/* ------------------------------------------------------------------------- */

typedef struct SflObj SflObj;
typedef SflObj *SflVal;

enum SflTag {
  SFL_NULL = 0,
  SFL_BOOL,
  SFL_INT,
  SFL_FLOAT,
  SFL_STR,
  SFL_ARR,
  SFL_OBJ,
  SFL_FUN,     /* a compiled SFL function together with its captured frame */
  SFL_NATIVE,  /* a primitive of this runtime, used as a value              */
  SFL_ITER,
  SFL_FRAME,   /* an activation record that some closure captured           */
  SFL_HANDLE,  /* thread, channel, lock, file, socket, process              */
  /* Appended after SFL_HANDLE so every existing tag keeps its value; code
     compiled against the previous enum still agrees with this runtime. */
  SFL_TUPLE,   /* immutable fixed-length sequence, distinct from an array   */
  SFL_TAGCOUNT
};

/* Static description of a compiled function, emitted once per definition. */
typedef struct SflProto {
  const char *name;
  const char *signature; /* "f(a, b?, ...rest)", for arity errors */
  int32_t file;          /* index into the embedded source table  */
  int32_t nparams;       /* including the rest parameter, if any     */
  int32_t required;      /* parameters with neither default nor rest */
  int32_t fixed;         /* nparams minus the rest parameter         */
  int32_t has_rest;
} SflProto;

/*
 * The generic entry every compiled function carries: it binds arguments into a
 * frame, applies defaults, runs the body and returns. `env` is the frame the
 * function closed over, or NULL for a top-level definition.
 */
typedef SflVal (*SflCode)(SflVal env, int64_t argc, SflVal *argv);

/* A primitive. `min`/`max` mirror VNative; max < 0 means variadic. */
typedef struct SflNative {
  const char *name;
  const char *group; /* the reference group: "math", "string", ... */
  const char *signature;
  const char *doc; /* one-line description, shown by arity errors and describe() */
  int32_t min;
  int32_t max;
  SflVal (*fn)(int64_t argc, SflVal *argv);
} SflNative;

/*
 * One heap cell. Every value is the same size, and anything variable-length
 * hangs off a side buffer this object owns and the collector frees with it.
 * `aux` is the length field: string code units, array length, object entry
 * count, frame slot count.
 */
struct SflObj {
  uint32_t tag;
  uint32_t aux;
  union {
    int64_t i;
    double d;
    struct {
      uint16_t *chars; /* UTF-16, exactly as the interpreter's Java strings */
      int32_t hash;
      int32_t hashed;
    } s;
    struct {
      SflVal *items;
      int32_t cap;
    } a;
    struct {
      /* No capacity field: a tuple can never grow or shrink, which is what
         lets everything that reads one skip the bounds a mutable array needs. */
      SflVal *items;
    } t;
    struct {
      SflVal *keys; /* SFL_STR, in insertion order */
      SflVal *vals;
      int32_t *index; /* open-addressed name -> slot, built once past a threshold */
      int32_t cap;
      int32_t index_cap;
    } o;
    struct {
      SflCode code;
      SflVal env;
      const SflProto *proto;
    } f;
    struct {
      const SflNative *info;
    } n;
    struct {
      SflVal src;     /* the collection being walked, or NULL when lazy   */
      SflVal fn;      /* lazy: called for each element                    */
      SflVal pending; /* lazy: the element hasNext looked ahead at        */
      int64_t pos;    /* collection: index. lazy: 0 none, 1 pending, 2 end */
    } it;
    struct {
      SflVal *slots;
      SflVal parent;
    } fr;
    struct {
      const char *kind;
      void *payload;
      SflVal label;
    } h;
  } u;
};

/* Singletons. Never allocated, never collected, safe to compare by pointer. */
extern SflObj sfl_null_obj;
extern SflObj sfl_true_obj;
extern SflObj sfl_false_obj;

#define sfl_null (&sfl_null_obj)
#define sfl_true (&sfl_true_obj)
#define sfl_false (&sfl_false_obj)

#define sfl_tag(v) ((v)->tag)
#define sfl_is(v, t) ((v)->tag == (uint32_t)(t))

/* ------------------------------------------------------------------------- */
/* Memory                                                                     */
/* ------------------------------------------------------------------------- */

/*
 * A mark-sweep collector. Object graphs are traced precisely — every tag's
 * layout is known — while roots are found conservatively by scanning the machine
 * stack and callee-saved registers, which is what lets it work with ordinary
 * generated code that keeps no side tables.
 *
 * The one rule for runtime code: a value must stay in a local variable across
 * any call that can allocate. Caching `v->u.s.chars` in a pointer and then
 * allocating can leave that pointer dangling, because the collector may free the
 * side buffer of a string nothing refers to any more.
 */
SflVal sfl_alloc(uint32_t tag);
void *sfl_raw_alloc(size_t bytes);           /* side buffer, owned by an object */
void *sfl_raw_realloc(void *p, size_t bytes);
void sfl_raw_free(void *p);

/* Arrays of values the collector must treat as roots: globals, string tables. */
void sfl_gc_add_roots(SflVal *base, int64_t count);
void sfl_gc_collect(void);
void sfl_gc_set_stack_bottom(void *p);
void sfl_gc_set_threshold(int64_t bytes);
void sfl_gc_disable(void);
void sfl_gc_enable(void);
int64_t sfl_gc_live_bytes(void);
int64_t sfl_gc_collections(void);

/*
 * Keeps a value alive across a stretch of runtime code that would otherwise drop
 * it — used where a C local is not enough because the value only exists inside a
 * side buffer being rebuilt.
 */
/*
 * Threads. Each one running SFL code registers its stack so the collector can find
 * its roots, and carries its own error and traceback state.
 */
typedef struct SflThread SflThread;
void sfl_gc_thread_init(void *stack_bottom);
SflThread *sfl_gc_thread_attach(void *stack_bottom);
void sfl_gc_thread_detach(void);
SflThread *sfl_thread_self(void);
void **sfl_thread_state_slot(void);
SflVal *sfl_thread_error_slots(void);

void sfl_gc_protect(SflVal v);
void sfl_gc_unprotect(int64_t count);
int64_t sfl_gc_protect_mark(void);

/* ------------------------------------------------------------------------- */
/* Constructors                                                               */
/* ------------------------------------------------------------------------- */

SflVal sfl_int(int64_t v);
SflVal sfl_float(double v);
SflVal sfl_bool(int v);

SflVal sfl_str_utf8(const char *bytes, int64_t nbytes);
SflVal sfl_str_utf16(const uint16_t *units, int64_t count);
SflVal sfl_str_empty(void);
/* UTF-8 rendering; the caller frees it with sfl_raw_free. */
char *sfl_str_dup_utf8(SflVal s);
int64_t sfl_utf8_len(SflVal s);
void sfl_utf8_write(SflVal s, char *dst);
/* The same, as Java's charset encoder does it: an unpaired surrogate is '?'. */
int64_t sfl_utf8_java_len(SflVal s);
void sfl_utf8_java_write(SflVal s, char *dst);
/* The charset-encoder rendering as a C string, for text that leaves the process
   the way the interpreter's toCString and getBytes send it. */
char *sfl_str_dup_utf8_java(SflVal s);

SflVal sfl_arr_new(int64_t capacity);
SflVal sfl_arr_of(int64_t count, SflVal *items);
void sfl_arr_push(SflVal a, SflVal v);
/* An immutable tuple over a copy of `items`; mirrors `new VTuple(out)`. */
SflVal sfl_tuple_of(int64_t count, SflVal *items);
SflVal sfl_obj_new(void);
/* Insert or overwrite, preserving insertion order like VObj's LinkedHashMap. */
void sfl_obj_put(SflVal o, SflVal key, SflVal v);
SflVal sfl_obj_get(SflVal o, SflVal key);   /* NULL when absent */
int sfl_obj_remove(SflVal o, SflVal key);
int64_t sfl_obj_size(SflVal o);

SflVal sfl_fun_new(SflCode code, SflVal env, const SflProto *proto);
SflVal sfl_native_new(const SflNative *info);
SflVal sfl_handle_new(const char *kind, void *payload, SflVal label);

/* Frames. `parent` is the frame the function was defined in, or NULL. */
SflVal sfl_frame_new(int64_t nslots, SflVal parent);
SflVal sfl_frame_up(SflVal frame, int64_t depth);

/* ------------------------------------------------------------------------- */
/* Boxing: the boundary between compiled machine types and dynamic values      */
/* ------------------------------------------------------------------------- */

#define sfl_box_int(v) sfl_int(v)
#define sfl_box_float(v) sfl_float(v)
#define sfl_box_bool(v) sfl_bool(v)

int64_t sfl_unbox_int(SflVal v);   /* raises unless int (or a float with no fraction) */
double sfl_unbox_float(SflVal v);  /* raises unless numeric */
int sfl_unbox_bool(SflVal v);      /* truthiness, never raises */

int sfl_truthy(SflVal v);
const char *sfl_type_name(SflVal v);

/* ------------------------------------------------------------------------- */
/* Operators                                                                  */
/* ------------------------------------------------------------------------- */

/* op is 0..4 for + - * / %, matching BinOp in the interpreter. */
SflVal sfl_arith(int32_t op, SflVal a, SflVal b);
SflVal sfl_neg(SflVal a);
int sfl_equal(SflVal a, SflVal b);
/* The nesting cap every recursive walk over a value shares; see sfl_value.c. */
#define SFL_NEST_LIMIT 10000
void sfl_check_nesting(int64_t depth);
int32_t sfl_compare(SflVal a, SflVal b); /* raises when incomparable */
/* op is 0..5 for == != < <= > >=, matching CmpOp. */
SflVal sfl_cmp(int32_t op, SflVal a, SflVal b);

SflVal sfl_index_get(SflVal recv, SflVal key);
SflVal sfl_index_set(SflVal recv, SflVal key, SflVal v);
SflVal sfl_index_compound(SflVal recv, SflVal key, int32_t op, SflVal v);

/* ------------------------------------------------------------------------- */
/* Calling                                                                    */
/* ------------------------------------------------------------------------- */

/*
 * Every call from compiled code goes through here, which is also where the
 * tail-call trampoline lives: a callee that ends in a tail call parks its target
 * with sfl_tail_set and returns SFL_TAIL, and this loop picks it up, so tail
 * recursion runs in constant stack exactly as it does in the interpreter.
 */
SflVal sfl_call(SflVal fn, int64_t argc, SflVal *argv);
SflVal sfl_call0(SflVal fn);
SflVal sfl_call1(SflVal fn, SflVal a);
SflVal sfl_call2(SflVal fn, SflVal a, SflVal b);

extern SflObj sfl_tail_marker;
#define SFL_TAIL (&sfl_tail_marker)
SflVal sfl_tail_set(SflVal fn, int64_t argc, SflVal *argv);

/*
 * Argument binding shared by every generic entry: checks arity against the
 * proto, copies positional arguments into the frame's slots and collects any
 * surplus into the rest parameter. Defaults are filled in by the generated code,
 * because they are expressions evaluated in the defining scope.
 * Returns the index of the first parameter the caller must supply a default for.
 */
int64_t sfl_bind_args(SflVal frame, const SflProto *proto, int64_t argc, SflVal *argv);
int64_t sfl_bind_args_raw(SflVal *slots, const SflProto *proto, int64_t argc, SflVal *argv);
SflVal *sfl_frame_slots(SflVal frame);
SflVal sfl_call_code(SflCode code, SflVal env, int64_t argc, SflVal *argv);
SflVal sfl_native_value(const char *name);

/* Raised by generated code when a name is used before it holds anything. */
void sfl_unassigned(SflVal name) __attribute__((noreturn));
void sfl_undefined(SflVal name) __attribute__((noreturn));

/*
 * One "did you mean" candidate. `slot` points at the global's runtime cell when
 * the name only counts once the program has assigned it, and is NULL for names
 * that are defined from startup (builtins, unboxed globals).
 */
typedef struct {
  const char *name;
  SflVal *slot;
} SflSuggestRow;
void sfl_set_suggest_names(const SflSuggestRow *rows, int64_t count);
void sfl_arity_fail(const char *name, int64_t argc) __attribute__((noreturn));

/* ------------------------------------------------------------------------- */
/* Errors                                                                     */
/* ------------------------------------------------------------------------- */

typedef struct SflHandler {
  jmp_buf jb;
  struct SflHandler *prev;
  int64_t frame_depth;   /* traceback depth to unwind to */
  int64_t protect_depth;
  SflVal err;            /* the error object, once one arrives */
} SflHandler;

void sfl_handler_push(SflHandler *h);
void sfl_handler_pop(void);

/* Raises with a formatted message; never returns. */
void sfl_raise(const char *fmt, ...) __attribute__((noreturn, format(printf, 1, 2)));
void sfl_raise_val(SflVal message) __attribute__((noreturn));
void sfl_raise_val_hint(SflVal message, const char *hint) __attribute__((noreturn));
/* Raise carrying the note lines the interpreter would have printed. */
void sfl_raise_hint(const char *msg, const char *hint) __attribute__((noreturn));
void sfl_raise_hint2(const char *msg, const char *hint, const char *hint2)
    __attribute__((noreturn));
/*
 * Raise "fn: ..." carrying fn's signature as help — the note the interpreter's
 * runNative attaches to any builtin complaint that arrives without one.
 */
void sfl_raise_sig(const char *fn, const char *fmt, ...)
    __attribute__((noreturn, format(printf, 2, 3)));

/* Unwinds to this thread's outermost handler, past every try() and attempt().
   The bottom handler must be armed — it is on every spawned thread. */
void sfl_unwind_outermost(void) __attribute__((noreturn));
/* exit() as the interpreter treats it: on a spawned thread it becomes that
   thread's failure and never returns; on the main thread it returns and the
   caller stops the process. */
void sfl_thread_exit(int code);

/*
 * Where execution currently is, as (file << 48) | (line << 16) | col. Generated
 * code stores this once per statement and once before each call, which is what
 * lets a compiled program underline the offending expression exactly as the
 * interpreter does.
 */
extern int64_t sfl_pos;
#define SFL_POS(file, line, col) \
  (((int64_t)(file) << 48) | ((int64_t)(line) << 16) | (int64_t)(col))

/* The program's own text, so errors can quote the line they happened on. */
void sfl_add_source(const char *name, const char *text);

/* Shadow call stack, for tracebacks. */
void sfl_frame_push(const char *fn);
extern _Thread_local char *sfl_stack_limit;
void sfl_thread_stack_init(void *bottom, size_t size);
void sfl_stack_overflow(void) __attribute__((noreturn));
void sfl_frame_pop(void);
int64_t sfl_frame_depth(void);
void sfl_frame_truncate(int64_t depth);
void sfl_frame_retarget(const char *fn);

/*
 * Argument accessors for primitives. Arity is checked before a primitive runs, so
 * these only police types — and they report exactly what the interpreter does:
 * "upper: argument 1 must be a string, got int 3", plus the signature as help.
 */
SflVal sfl_arg_str(SflVal *argv, int i, const char *fn);
int64_t sfl_arg_int(SflVal *argv, int i, const char *fn);
int32_t sfl_arg_idx(SflVal *argv, int i, const char *fn);
double sfl_arg_num(SflVal *argv, int i, const char *fn);
SflVal sfl_arg_arr(SflVal *argv, int i, const char *fn);
SflVal sfl_arg_obj(SflVal *argv, int i, const char *fn);
int sfl_arg_bool(SflVal *argv, int i, const char *fn);
SflVal sfl_arg_fn(SflVal *argv, int i, const char *fn);
/*
 * A byte array argument: an SFL array of ints 0..255, validated with the same
 * three messages the interpreter's argBytes produces, then copied into a raw
 * buffer the caller frees with sfl_raw_free. Validation happens before the
 * allocation, so a raise cannot leak it.
 */
uint8_t *sfl_arg_bytes(SflVal *argv, int i, const char *fn, int64_t *len);
/* The validation half of sfl_arg_bytes alone, for primitives that must check
   every argument in the interpreter's order before allocating anything. */
void sfl_check_bytes(SflVal *argv, int i, const char *fn);
/* The SFL value for a run of bytes: an array of ints 0..255. */
SflVal sfl_bytes_arr(const uint8_t *b, int64_t n);
SflVal sfl_opt(int64_t argc, SflVal *argv, int i);
SflVal sfl_num_result(double d, int was_int);
/* Java's (long) cast: NaN to 0, out of range clamped rather than undefined. */
int64_t sfl_d2l(double d);

/* What try() and attempt() read after catching an error. */
void sfl_report_error(SflVal message, SflVal help, SflVal trace, int64_t pos);
SflVal sfl_err_message(void);
SflVal sfl_err_help(void);
SflVal sfl_err_trace(void);
int32_t sfl_err_line(void);
const char *sfl_err_file(void);

extern int sfl_argc;
extern char **sfl_argv;

/* ------------------------------------------------------------------------- */
/* Rendering                                                                  */
/* ------------------------------------------------------------------------- */

/*
 * `display` is what print shows (strings bare); `repr` is what the REPL and
 * toString show (strings quoted). Floats follow Java's Double.toString, which is
 * what the interpreter prints and what compiled output has to match.
 */
SflVal sfl_display(SflVal v);
SflVal sfl_repr(SflVal v);
SflVal sfl_pretty(SflVal v, int64_t indent);
void sfl_write_double(char *buf, size_t n, double v);

/* ------------------------------------------------------------------------- */
/* Iteration                                                                  */
/* ------------------------------------------------------------------------- */

SflVal sfl_iter_begin(SflVal v);
/* Stores the next element in *out and returns 1, or returns 0 at the end. */
int sfl_iter_next(SflVal it, SflVal *out);
int sfl_iter_has_next(SflVal it);
/*
 * A lazy iterator driven by `next`, which yields [v] for an element and [] when
 * it is done. This is how the standard library keeps map/filter/take lazy over
 * iterators without the runtime knowing what those functions do.
 */
SflVal sfl_iter_from_fn(SflVal next);

/* ------------------------------------------------------------------------- */
/* Program entry                                                              */
/* ------------------------------------------------------------------------- */

/* Defined by generated code; called by the runtime's main(). */
void sfl_main(void);

void sfl_init(int argc, char **argv, void *stack_bottom);
void sfl_shutdown(void);

/* Interned string literals: the compiler emits one call per literal at startup. */
SflVal sfl_intern(const char *utf8, int64_t nbytes);

/* ------------------------------------------------------------------------- */
/* Primitives                                                                 */
/* ------------------------------------------------------------------------- */

/*
 * Every primitive has the same shape, so the compiler can emit a call to any of
 * them from one declaration and can also hand one out as a first-class value.
 * The table is generated from primitives.def, which is also what the Scala side
 * reads to decide what it may compile.
 */
#define SFL_PRIM(name, sym, sig, min, max, module) SflVal sym(int64_t argc, SflVal *argv);
#include "primitives.def"
#undef SFL_PRIM

/* Emitted by the compiler into each program, so unused primitives can be stripped. */
extern const SflNative sfl_natives[];
extern const int sfl_native_count;
const SflNative *sfl_native_find(const char *name);

/* Fast paths the compiler emits when it knows the argument types. */
void sfl_print_i64(int64_t v, int nl);
void sfl_print_f64(double v, int nl);
void sfl_print_bool(int v, int nl);
void sfl_print_val(SflVal v, int nl);
void sfl_print_sep(void);
void sfl_div_zero(void) __attribute__((noreturn));
void sfl_mod_zero(void) __attribute__((noreturn));
int64_t sfl_time_millis(void);
int64_t sfl_round(double v);
/* Digest cores shared by the json module's hex digests and the codec module's
   byte-level digest/HMAC/PBKDF2 primitives. */
void sfl_md5_digest(const uint8_t *msg, uint64_t len, uint8_t out[16]);
void sfl_sha1_digest(const uint8_t *msg, uint64_t len, uint8_t out[20]);
void sfl_sha256_digest(const uint8_t *msg, uint64_t len, uint8_t out[32]);
int64_t sfl_sign_i64(int64_t v);
int64_t sfl_sign_f64(double v);
/* Math.round and toInt's cast, shared by the primitives and the fast paths. */
int64_t sfl_java_round(double a);
int64_t sfl_to_int_f64(double v);
int64_t sfl_gcd(int64_t a, int64_t b);
int64_t sfl_str_length(SflVal s);
int64_t sfl_size_of(SflVal v);

#ifdef __cplusplus
}
#endif

#endif /* SFL_RUNTIME_H */
