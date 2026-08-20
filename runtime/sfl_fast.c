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

/* Java's Math.round: half rounds toward positive infinity, unlike llround. */
int64_t sfl_round(double v) { return (int64_t)floor(v + 0.5); }

int64_t sfl_sign_i64(int64_t v) { return v > 0 ? 1 : (v < 0 ? -1 : 0); }
int64_t sfl_sign_f64(double v) { return v > 0 ? 1 : (v < 0 ? -1 : 0); }

/* Integer power by squaring, matching the interpreter's integer result. */
int64_t sfl_ipow(int64_t base, int64_t exp) {
  if (exp < 0) return (int64_t)pow((double)base, (double)exp);
  int64_t r = 1;
  while (exp > 0) {
    if (exp & 1) r *= base;
    base *= base;
    exp >>= 1;
  }
  return r;
}

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
