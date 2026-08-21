/*
 * The unboxed fast paths, and what generated code calls when something is wrong.
 *
 * When the compiler has proved a value's type it emits calls to these instead of
 * going through the value model, so `pow(a, b)` on two ints stays integer
 * arithmetic. Each one produces exactly what the boxed route would have produced —
 * that is the only reason they are allowed to exist. Printing lives next to the
 * other I/O, in sfl_io.c.
 */
#include "sfl.h"

#include <ctype.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

void sfl_div_zero(void) { sfl_raise("division by zero"); }
void sfl_mod_zero(void) { sfl_raise("modulo by zero"); }

/*
 * Java's Math.round, and the cast toInt performs. Both go through the same helpers
 * the primitives use, so an unboxed call cannot round or clamp differently from a
 * boxed one.
 */
int64_t sfl_round(double v) { return sfl_java_round(v); }

int64_t sfl_to_int_f64(double v) {
  if (isnan(v) || isinf(v)) {
    char shown[64];
    sfl_write_double(shown, sizeof shown, v);
    sfl_raise_sig("toInt", "cannot convert %s", shown);
  }
  return sfl_d2l(v);
}

int64_t sfl_sign_i64(int64_t v) { return v > 0 ? 1 : (v < 0 ? -1 : 0); }
int64_t sfl_sign_f64(double v) { return v > 0 ? 1 : (v < 0 ? -1 : 0); }

int64_t sfl_gcd(int64_t a, int64_t b) {
  if (a < 0) a = -a;
  if (b < 0) b = -b;
  while (b != 0) {
    int64_t t = a % b;
    a = b;
    b = t;
  }
  return a;
}

/*
 * How many arguments a function declares. A function with a rest parameter reports
 * the count it requires, and a variadic primitive its minimum — the same rule the
 * interpreter's curry() follows.
 */
SflVal sfl_p___arity(int64_t argc, SflVal *argv) {
  SflVal f = sfl_arg_fn(argv, 0, "__arity");
  if (f->tag == SFL_FUN)
    return sfl_int(f->u.f.proto->has_rest ? f->u.f.proto->required : f->u.f.proto->nparams);
  return sfl_int(f->u.n.info->max < 0 ? f->u.n.info->min : f->u.n.info->max);
}

/*
 * Raised by a val/var destructuring whose pattern did not match. The expected
 * side is a description the desugarer built, so it renders bare (display); the
 * value that failed renders as Err.show does — its repr, clipped to 48 UTF-16
 * code units with an ellipsis, so a huge structure cannot flood the message.
 */
SflVal sfl_p___patFail(int64_t argc, SflVal *argv) {
  SflVal ev = sfl_display(argv[0]);
  char *expected = sfl_str_dup_utf8(ev);
  /* `expected` is raw memory now, so the allocations below cannot invalidate it. */
  SflVal gv = sfl_repr(argv[1]);
  char *got;
  if (gv->aux > 48) {
    /* Clip on code units, not UTF-8 bytes, or a non-ASCII repr would cut at a
       different place than the interpreter's 45-character take. */
    SflVal cut = sfl_str_utf16(gv->u.s.chars, 45);
    char *head = sfl_str_dup_utf8(cut);
    size_t n = strlen(head);
    got = (char *)sfl_raw_alloc(n + 4);
    memcpy(got, head, n);
    memcpy(got + n, "...", 4);
    sfl_raw_free(head);
  } else {
    got = sfl_str_dup_utf8(gv);
  }
  char msg[512];
  snprintf(msg, sizeof msg, "pattern does not match: expected %s, got %s", expected, got);
  sfl_raw_free(expected);
  sfl_raw_free(got);
  sfl_raise_hint(msg, "a 'val' pattern must match; use select to try several shapes");
}

/* ------------------------------------------------------------------------- */
/* What generated code calls when something is wrong                          */
/* ------------------------------------------------------------------------- */

void sfl_unassigned(SflVal name) {
  char *n = sfl_str_dup_utf8(name);
  char msg[256];
  snprintf(msg, sizeof msg, "variable '%s' is read before it is assigned", n);
  sfl_raw_free(n);
  sfl_raise_hint(msg, "give it a value on every path that reaches this point");
}

/*
 * Candidates for "did you mean", in the order the interpreter's definedNames
 * yields them: builtins first, in registration order, then the program's own
 * globals in first-mention order. A row with a slot pointer only counts while
 * that global actually holds a value — the interpreter suggests only names
 * that are defined at the moment of the error, and this is also what keeps an
 * undefined name from suggesting itself.
 */
static const SflSuggestRow *suggest_rows;
static int64_t suggest_row_count;

void sfl_set_suggest_names(const SflSuggestRow *rows, int64_t count) {
  suggest_rows = rows;
  suggest_row_count = count;
}

/* Bounded Levenshtein, giving up as soon as the bound is exceeded. */
static int edit_distance(const char *a, const char *b, int limit) {
  int n = (int)strlen(a), m = (int)strlen(b);
  if (n - m > limit || m - n > limit) return limit + 1;
  int prev[256], cur[256];
  if (m > 254) return limit + 1;
  for (int j = 0; j <= m; j++) prev[j] = j;
  for (int i = 1; i <= n; i++) {
    cur[0] = i;
    int row_min = cur[0];
    for (int k = 1; k <= m; k++) {
      int cost = tolower((unsigned char)a[i - 1]) == tolower((unsigned char)b[k - 1]) ? 0 : 1;
      int v = cur[k - 1] + 1;
      if (prev[k] + 1 < v) v = prev[k] + 1;
      if (prev[k - 1] + cost < v) v = prev[k - 1] + cost;
      cur[k] = v;
      if (v < row_min) row_min = v;
    }
    if (row_min > limit) return limit + 1;
    memcpy(prev, cur, sizeof(int) * (size_t)(m + 1));
  }
  return prev[m];
}

/*
 * "did you mean 'x'?", searched over the same candidates, in the same order and
 * with the same distance bound, as the interpreter's Err.suggest: the first
 * strictly-best match wins, so iteration order decides ties. The name itself is
 * never a candidate — another namespace may spell a name exactly as this one
 * does, and proposing that spelling back would say nothing.
 */
static const char *suggest(const char *name) {
  int n = (int)strlen(name);
  if (n == 0) return NULL;
  int limit = n <= 3 ? 1 : 2;
  const char *best = NULL;
  int best_d = limit + 1;
  for (int64_t i = 0; i < suggest_row_count; i++) {
    if (suggest_rows[i].slot != NULL && *suggest_rows[i].slot == NULL) continue;
    const char *c = suggest_rows[i].name;
    if (strcmp(c, name) == 0) continue;
    int len = (int)strlen(c);
    if (len - n > limit || n - len > limit) continue;
    int d = edit_distance(name, c, limit);
    if (d < best_d) { best_d = d; best = c; }
  }
  return best_d <= limit ? best : NULL;
}

void sfl_undefined(SflVal name) {
  char *n = sfl_str_dup_utf8(name);
  char msg[256];
  snprintf(msg, sizeof msg, "undefined variable '%s'", n);
  int n_priv = n[0] == '_';
  const char *near = suggest(n);
  char hint[256];
  if (near) snprintf(hint, sizeof hint, "did you mean '%s'?", near);
  sfl_raw_free(n);
  /* The interpreter attaches the suggestion and one situational note together. */
  sfl_raise_hint2(msg, near ? hint : NULL,
                  n_priv ? "a name beginning with '_' is private to the file that declares it"
                         : "names must be assigned before they are read; a function must be "
                           "defined above the call that runs first");
}

void sfl_arity_fail(const char *name, int64_t argc) {
  const SflNative *n = sfl_native_find(name);
  if (n == NULL) sfl_raise("%s: wrong number of arguments (%lld)", name, (long long)argc);
  char expect[64];
  if (n->max < 0) snprintf(expect, sizeof expect, "at least %d", n->min);
  else if (n->min == n->max) snprintf(expect, sizeof expect, "%d", n->min);
  else snprintf(expect, sizeof expect, "%d to %d", n->min, n->max);
  char msg[256], hint[256];
  snprintf(msg, sizeof msg, "%s expects %s argument(s), got %lld", n->name, expect,
           (long long)argc);
  snprintf(hint, sizeof hint, "the signature is %s", n->signature);
  sfl_raise_hint2(msg, hint, n->doc);
}

/* ------------------------------------------------------------------------- */
/* Unboxed primitive results                                                  */
/*                                                                            */
/* A primitive that provably returns one machine type lets the compiler read  */
/* the result raw and keep inference typed past the call. The table of which  */
/* primitives qualify lives with the compiler (Intrinsics.PrimRet); these are */
/* the readers it emits. A primitive that raises never returns, so the reads  */
/* cannot see a wrong tag.                                                    */
/* ------------------------------------------------------------------------- */

int64_t sfl_int_raw(SflVal v) { return v->u.i; }
double sfl_float_raw(SflVal v) { return v->u.d; }
int32_t sfl_istrue_raw(SflVal v) { return v == sfl_true; }

/* ------------------------------------------------------------------------- */
/* Checked math fast paths                                                    */
/*                                                                            */
/* Each reproduces its primitive exactly — same domain check, same message,   */
/* same libm call — minus the boxing on both sides.                           */
/* ------------------------------------------------------------------------- */

double sfl_sqrt_ck(double x) {
  if (x < 0) sfl_raise_sig("sqrt", "argument must not be negative");
  return sqrt(x);
}

double sfl_log_ck(double x) {
  if (x <= 0) sfl_raise_sig("log", "argument must be positive");
  return log(x);
}

double sfl_log2_ck(double x) {
  if (x <= 0) sfl_raise_sig("log2", "argument must be positive");
  /* Divided rather than log2(), because that is what the primitive computes. */
  return log(x) / log(2.0);
}

double sfl_log10_ck(double x) {
  if (x <= 0) sfl_raise_sig("log10", "argument must be positive");
  return log10(x);
}

/* The primitive's lcm on two machine ints: abs through unsigned so that
   INT64_MIN survives, quotient guarded, product left to wrap as the JVM does. */
int64_t sfl_lcm_i64(int64_t x, int64_t y) {
  if (x < 0) x = (int64_t)(0 - (uint64_t)x);
  if (y < 0) y = (int64_t)(0 - (uint64_t)y);
  if (x == 0 || y == 0) return 0;
  int64_t g = x, h = y;
  while (h != 0) {
    int64_t t = g % h;
    g = h;
    h = t;
  }
  int64_t q = (x == INT64_MIN && g == -1) ? INT64_MIN : x / g;
  return (int64_t)((uint64_t)q * (uint64_t)y);
}

/* clamp's primitive works in doubles whatever it was given — bounds checked
   against each other first, message rendered from the doubles — and narrows
   back to an int only when every argument was one. These two mirror that. */
/* Math.min/max as the primitive spells them: NaN in the first operand wins,
   and -0.0 sorts below 0.0 — fmin/fmax honour neither. */
static double clamp_jmin(double a, double b) {
  if (isnan(a)) return a;
  if (a == 0.0 && b == 0.0 && signbit(b)) return b;
  return a <= b ? a : b;
}

static double clamp_jmax(double a, double b) {
  if (isnan(a)) return a;
  if (a == 0.0 && b == 0.0 && signbit(a)) return b;
  return a >= b ? a : b;
}

static double clamp_core(double x, double lo, double hi) {
  if (lo > hi) {
    char slo[64], shi[64];
    sfl_write_double(slo, sizeof slo, lo);
    sfl_write_double(shi, sizeof shi, hi);
    sfl_raise_sig("clamp", "lower bound %s is greater than upper bound %s", slo, shi);
  }
  return clamp_jmax(lo, clamp_jmin(hi, x));
}

int64_t sfl_clamp_i64_ck(int64_t x, int64_t lo, int64_t hi) {
  return sfl_d2l(clamp_core((double)x, (double)lo, (double)hi));
}

double sfl_clamp_f64_ck(double x, double lo, double hi) {
  return clamp_core(x, lo, hi);
}
