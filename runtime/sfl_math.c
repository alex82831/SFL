/*
 * Numbers: arithmetic, bit twiddling, randomness, and the type predicates and
 * conversions that travel with them.
 *
 * The interpreter keeps int and float apart and so does everything here: abs,
 * min/max, clamp and pow hand back an int when they were handed ints, while
 * floor/ceil/round/trunc/sign always narrow to one. Two places need Java's
 * arithmetic rather than C's, and both are spelled out below: converting a double
 * to a long saturates instead of trapping, and Math.round breaks ties upwards
 * instead of away from zero.
 *
 * Text is parsed the way java.lang.Long.parseLong and java.lang.Double.parseDouble
 * parse it, not the way strtol and strtod do. The C library accepts forms the
 * interpreter rejects ("inf", "0x1.8" with no binary exponent) and rejects forms it
 * accepts (a trailing 'd' or 'f'), so the accepted grammar is checked here first
 * and strtod is only asked to do the conversion.
 */
#include "sfl.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------------------- */
/* Java arithmetic that C does not provide                                    */
/* ------------------------------------------------------------------------- */

/*
 * The JVM's d2l: NaN becomes zero and anything past the ends of the range clamps
 * to them, where a plain C cast would be undefined.
 */
static int64_t d2l(double d) {
  if (isnan(d)) return 0;
  if (d >= 9223372036854775808.0) return INT64_MAX;
  if (d <= -9223372036854775808.0) return INT64_MIN;
  return (int64_t)d;
}

/*
 * java.lang.Math.round. Halves go towards positive infinity, so round(-0.5) is 0
 * and llround's "away from zero" disagrees on every negative half. This is Java's
 * own bit trick, which also gets 0.49999999999999994 right — the value that
 * floor(x + 0.5) famously rounds to 1.
 */
static int64_t java_round(double a) {
  int64_t bits;
  memcpy(&bits, &a, sizeof bits);
  int64_t biased_exp = (bits & 0x7FF0000000000000LL) >> 52;
  int64_t shift = 1074 - biased_exp; /* SIGNIFICAND_WIDTH - 2 + EXP_BIAS */
  if ((shift & -64LL) == 0) {        /* 0 <= shift < 64 */
    int64_t r = (bits & 0x000FFFFFFFFFFFFFLL) | 0x0010000000000000LL;
    if (bits < 0) r = -r;
    return ((r >> shift) + 1) >> 1;
  }
  return d2l(a);
}

/* Math.abs on a long: Long.MIN_VALUE stays itself, so negate through unsigned. */
static int64_t labs64(int64_t v) { return v < 0 ? (int64_t)(0 - (uint64_t)v) : v; }

/* Math.min/max on doubles: NaN wins, and -0.0 sorts below 0.0. fmin/fmax do
   neither, they return the non-NaN operand and ignore the sign of zero. */
static double jmin(double a, double b) {
  if (isnan(a)) return a;
  if (a == 0.0 && b == 0.0 && signbit(b)) return b;
  return a <= b ? a : b;
}

static double jmax(double a, double b) {
  if (isnan(a)) return a;
  if (a == 0.0 && b == 0.0 && signbit(a)) return b;
  return a >= b ? a : b;
}

/* ------------------------------------------------------------------------- */
/* Numeric text                                                               */
/* ------------------------------------------------------------------------- */

static int is_dec(char c) { return c >= '0' && c <= '9'; }

static int is_hex(char c) {
  return is_dec(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

/*
 * String.trim (everything at or below U+0020 goes) followed by UTF-8. Returns
 * NULL for text that cannot spell a number at all — anything non-ASCII, or an
 * embedded NUL that would silently truncate the C string and make a prefix parse
 * succeed. The caller frees a non-NULL result with sfl_raw_free.
 */
static char *numeric_text(SflVal s) {
  char *out = (char *)sfl_raw_alloc((size_t)s->aux + 1);
  const uint16_t *u = s->u.s.chars; /* read after the allocation, never across one */
  int64_t lo = 0, hi = s->aux;
  while (lo < hi && u[lo] <= ' ') lo++;
  while (hi > lo && u[hi - 1] <= ' ') hi--;
  for (int64_t i = lo; i < hi; i++) {
    if (u[i] == 0 || u[i] > 0x7F) {
      sfl_raw_free(out);
      return NULL;
    }
    out[i - lo] = (char)u[i];
  }
  out[hi - lo] = '\0';
  return out;
}

/* Character.digit over ASCII, which is what Long.parseLong consults per digit. */
static int digit_of(char c, int radix) {
  int v = -1;
  if (c >= '0' && c <= '9') v = c - '0';
  else if (c >= 'a' && c <= 'z') v = c - 'a' + 10;
  else if (c >= 'A' && c <= 'Z') v = c - 'A' + 10;
  return (v >= 0 && v < radix) ? v : -1;
}

/*
 * java.lang.Long.parseLong. It accumulates negatively so that Long.MIN_VALUE has
 * a representable intermediate, and it rejects overflow rather than wrapping,
 * which is the difference that matters against strtol. 0 means "would have thrown
 * NumberFormatException".
 */
static int java_parse_long(const char *s, int radix, int64_t *out) {
  size_t len = strlen(s), i = 0;
  int64_t result = 0, limit = -INT64_MAX;
  int negative = 0;
  if (len == 0) return 0;
  if (s[0] < '0') {
    if (s[0] == '-') {
      negative = 1;
      limit = INT64_MIN;
    } else if (s[0] != '+') return 0;
    if (len == 1) return 0;
    i = 1;
  }
  int64_t multmin = limit / radix;
  while (i < len) {
    int d = digit_of(s[i++], radix);
    if (d < 0 || result < multmin) return 0;
    result *= radix;
    if (result < limit + d) return 0;
    result -= d;
  }
  *out = negative ? result : -result;
  return 1;
}

/*
 * The grammar Double.valueOf documents, with the leading sign already consumed.
 * `end` excludes the optional f/F/d/D suffix. Returns 0 for anything outside it.
 */
static int decimal_form(const char *p, const char *end) {
  int ip = 0, fp = 0;
  while (p < end && is_dec(*p)) { p++; ip++; }
  if (p < end && *p == '.') {
    p++;
    while (p < end && is_dec(*p)) { p++; fp++; }
  }
  if (ip + fp == 0) return 0;
  if (p < end && (*p == 'e' || *p == 'E')) {
    int e = 0;
    p++;
    if (p < end && (*p == '+' || *p == '-')) p++;
    while (p < end && is_dec(*p)) { p++; e++; }
    if (e == 0) return 0;
  }
  return p == end;
}

static int hex_form(const char *p, const char *end) {
  int lead = 0, trail = 0, e = 0;
  p += 2; /* the 0x the caller matched */
  while (p < end && is_hex(*p)) { p++; lead++; }
  if (p < end && *p == '.') {
    p++;
    while (p < end && is_hex(*p)) { p++; trail++; }
  }
  if (lead + trail == 0) return 0;
  /* Java demands the binary exponent that strtod treats as optional. */
  if (p >= end || (*p != 'p' && *p != 'P')) return 0;
  p++;
  if (p < end && (*p == '+' || *p == '-')) p++;
  while (p < end && is_dec(*p)) { p++; e++; }
  return e > 0 && p == end;
}

/*
 * Double.parseDouble. The string is validated against Java's grammar and then
 * handed to strtod with the type suffix removed, since strtod would choke on it.
 * 0 means "would have thrown NumberFormatException".
 */
static int java_parse_double(char *s, double *out) {
  char *p = s;
  if (*p == '+' || *p == '-') p++;
  if (strcmp(p, "NaN") != 0 && strcmp(p, "Infinity") != 0) {
    char *end = p + strlen(p);
    if (end > p && (end[-1] == 'f' || end[-1] == 'F' || end[-1] == 'd' || end[-1] == 'D')) end--;
    int ok = (end - p >= 2 && p[0] == '0' && (p[1] == 'x' || p[1] == 'X'))
               ? hex_form(p, end)
               : decimal_form(p, end);
    if (!ok) return 0;
    *end = '\0'; /* drop the suffix; the sign stays in front of `s` */
  }
  *out = strtod(s, NULL);
  return 1;
}

/*
 * Raising never returns, so the UTF-8 copy of the offending text has to be gone
 * before the message goes out: render it here, release the copy, then raise.
 */
static void raise_quoting(const char *fn, SflVal s, const char *suffix)
    __attribute__((noreturn));
static void raise_quoting(const char *fn, SflVal s, const char *suffix) {
  char *t = sfl_str_dup_utf8(s);
  char msg[1024];
  snprintf(msg, sizeof msg, "'%s'%s", t, suffix);
  sfl_raw_free(t);
  sfl_raise_sig(fn, "%s", msg);
}

/* ------------------------------------------------------------------------- */
/* Conversions                                                                */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_parseInt(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_INT) return v;
  if (v->tag == SFL_FLOAT) return sfl_int(d2l(v->u.d));
  if (v->tag != SFL_STR) sfl_raise_sig("parseInt", "cannot convert %s", sfl_type_name(v));

  /* The radix is only consulted for text, so parseInt(5, "nonsense") is fine. */
  int32_t radix = argc > 1 ? sfl_arg_idx(argv, 1, "parseInt") : 10;
  if (radix < 2 || radix > 36)
    sfl_raise_sig("parseInt", "radix %d is out of range 2..36", radix);

  char *text = numeric_text(v);
  int64_t out = 0;
  int ok = text != NULL && java_parse_long(text, radix, &out);
  sfl_raw_free(text);
  if (!ok) {
    char suffix[64];
    snprintf(suffix, sizeof suffix, " is not an integer in base %d", radix);
    raise_quoting("parseInt", v, suffix);
  }
  return sfl_int(out);
}

SflVal sfl_p_parseFloat(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_INT) return sfl_float((double)v->u.i);
  if (v->tag == SFL_FLOAT) return v;
  if (v->tag != SFL_STR) sfl_raise_sig("parseFloat", "cannot convert %s", sfl_type_name(v));

  char *text = numeric_text(v);
  double out = 0.0;
  int ok = text != NULL && java_parse_double(text, &out);
  sfl_raw_free(text);
  if (!ok) raise_quoting("parseFloat", v, " is not a number");
  return sfl_float(out);
}

/* ------------------------------------------------------------------------- */
/* Basics                                                                     */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_abs(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_INT) return sfl_int(labs64(v->u.i));
  if (v->tag == SFL_FLOAT) return sfl_float(fabs(v->u.d));
  sfl_raise_sig("abs", "expected a number, got %s", sfl_type_name(v));
}

/*
 * min and max share this. One array argument is walked; anything else compares the
 * arguments themselves, and a lone non-array is simply returned — the interpreter
 * never type-checks that case.
 */
static SflVal extreme(int64_t argc, SflVal *argv, const char *fn, int larger) {
  SflVal src = NULL; /* the array being walked, when there is one */
  SflVal best;
  int64_t n;
  if (argc == 1 && argv[0]->tag == SFL_ARR) {
    src = argv[0];
    if (src->aux == 0) sfl_raise_sig(fn, "array is empty");
    n = src->aux;
    best = src->u.a.items[0];
  } else {
    n = argc;
    best = argv[0];
  }
  for (int64_t i = 1; i < n; i++) {
    SflVal v = src ? src->u.a.items[i] : argv[i];
    int32_t c = sfl_compare(v, best);
    if (larger ? c > 0 : c < 0) best = v;
  }
  return best;
}

SflVal sfl_p_max(int64_t argc, SflVal *argv) { return extreme(argc, argv, "max", 1); }
SflVal sfl_p_min(int64_t argc, SflVal *argv) { return extreme(argc, argv, "min", 0); }

SflVal sfl_p_floor(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_INT) return v;
  if (v->tag == SFL_FLOAT) return sfl_int(d2l(floor(v->u.d)));
  sfl_raise_sig("floor", "expected a number, got %s", sfl_type_name(v));
}

SflVal sfl_p_ceil(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_INT) return v;
  if (v->tag == SFL_FLOAT) return sfl_int(d2l(ceil(v->u.d)));
  sfl_raise_sig("ceil", "expected a number, got %s", sfl_type_name(v));
}

SflVal sfl_p_round(int64_t argc, SflVal *argv) {
  /* The digit count is read before the value is looked at, so round(true, "x")
     reports the second argument, exactly as the interpreter does. */
  int32_t digits = argc > 1 ? sfl_arg_idx(argv, 1, "round") : 0;
  SflVal v = argv[0];
  if (v->tag == SFL_INT) return v;
  if (v->tag == SFL_FLOAT) {
    if (digits <= 0) return sfl_int(java_round(v->u.d));
    if (digits > 15) sfl_raise_sig("round", "digits must be at most 15");
    double scale = pow(10.0, (double)digits);
    return sfl_float((double)java_round(v->u.d * scale) / scale);
  }
  sfl_raise_sig("round", "expected a number, got %s", sfl_type_name(v));
}

SflVal sfl_p_trunc(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_INT) return v;
  if (v->tag == SFL_FLOAT) return sfl_int(d2l(v->u.d));
  sfl_raise_sig("trunc", "expected a number, got %s", sfl_type_name(v));
}

SflVal sfl_p_sign(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_INT) return sfl_int(v->u.i > 0 ? 1 : (v->u.i < 0 ? -1 : 0));
  if (v->tag == SFL_FLOAT) {
    /* Math.signum keeps NaN and signed zeros as they are; narrowing to a long
       then flattens all three to 0. */
    double d = v->u.d;
    if (isnan(d)) return sfl_int(0);
    return sfl_int(d > 0 ? 1 : (d < 0 ? -1 : 0));
  }
  sfl_raise_sig("sign", "expected a number, got %s", sfl_type_name(v));
}

SflVal sfl_p_clamp(int64_t argc, SflVal *argv) {
  /* The bounds are checked against each other before the value is even read. */
  double lo = sfl_arg_num(argv, 1, "clamp");
  double hi = sfl_arg_num(argv, 2, "clamp");
  if (lo > hi) {
    char slo[64], shi[64];
    sfl_write_double(slo, sizeof slo, lo);
    sfl_write_double(shi, sizeof shi, hi);
    sfl_raise_sig("clamp", "lower bound %s is greater than upper bound %s", slo, shi);
  }
  double x = sfl_arg_num(argv, 0, "clamp");
  double r = jmax(lo, jmin(hi, x));
  int all_int = argv[0]->tag == SFL_INT && argv[1]->tag == SFL_INT && argv[2]->tag == SFL_INT;
  return all_int ? sfl_int(d2l(r)) : sfl_float(r);
}

SflVal sfl_p_gcd(int64_t argc, SflVal *argv) {
  int64_t x = labs64(sfl_arg_int(argv, 0, "gcd"));
  int64_t y = labs64(sfl_arg_int(argv, 1, "gcd"));
  while (y != 0) {
    int64_t t = x % y;
    x = y;
    y = t;
  }
  return sfl_int(x);
}

SflVal sfl_p_lcm(int64_t argc, SflVal *argv) {
  int64_t x = labs64(sfl_arg_int(argv, 0, "lcm"));
  int64_t y = labs64(sfl_arg_int(argv, 1, "lcm"));
  if (x == 0 || y == 0) return sfl_int(0);
  int64_t g = x, h = y;
  while (h != 0) {
    int64_t t = g % h;
    g = h;
    h = t;
  }
  /*
   * Long.MIN_VALUE stays negative through abs, so the divisor can come out as -1
   * and the quotient then overflows — which the JVM wraps but C leaves undefined,
   * and x86 turns into SIGFPE. The product overflows for large inputs too, and the
   * interpreter lets that wrap as well.
   */
  int64_t q = (x == INT64_MIN && g == -1) ? INT64_MIN : x / g;
  return sfl_int((int64_t)((uint64_t)q * (uint64_t)y));
}

SflVal sfl_p_isNaN(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_FLOAT && isnan(argv[0]->u.d));
}

SflVal sfl_p_isInfinite(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_FLOAT && isinf(argv[0]->u.d));
}

/* ------------------------------------------------------------------------- */
/* Transcendental                                                             */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_pow(int64_t argc, SflVal *argv) {
  double x = sfl_arg_num(argv, 0, "pow");
  double y = sfl_arg_num(argv, 1, "pow");
  double r = pow(x, y);
  /* Two ints and a non-negative exponent is the only case that can stay exact. */
  if (argv[0]->tag == SFL_INT && argv[1]->tag == SFL_INT && argv[1]->u.i >= 0)
    return sfl_num_result(r, 1);
  return sfl_float(r);
}

SflVal sfl_p_atan2(int64_t argc, SflVal *argv) {
  double y = sfl_arg_num(argv, 0, "atan2");
  double x = sfl_arg_num(argv, 1, "atan2");
  return sfl_float(atan2(y, x));
}

SflVal sfl_p_hypot(int64_t argc, SflVal *argv) {
  double x = sfl_arg_num(argv, 0, "hypot");
  double y = sfl_arg_num(argv, 1, "hypot");
  return sfl_float(hypot(x, y));
}

SflVal sfl_p_PI(int64_t argc, SflVal *argv) { return sfl_float(3.141592653589793); }
SflVal sfl_p_E(int64_t argc, SflVal *argv) { return sfl_float(2.718281828459045); }
SflVal sfl_p_INF(int64_t argc, SflVal *argv) { return sfl_float(INFINITY); }
SflVal sfl_p_NAN(int64_t argc, SflVal *argv) { return sfl_float(NAN); }

/* ------------------------------------------------------------------------- */
/* Bits                                                                       */
/* ------------------------------------------------------------------------- */

/*
 * The JVM's shift operators take the count modulo 64 and let the result wrap; C
 * calls both of those undefined, so the count is masked and the left shift runs
 * unsigned.
 */
#define SHIFT_COUNT(n) ((int)((n) & 63))

SflVal sfl_p_bitAnd(int64_t argc, SflVal *argv) {
  return sfl_int(sfl_arg_int(argv, 0, "bitAnd") & sfl_arg_int(argv, 1, "bitAnd"));
}

SflVal sfl_p_bitOr(int64_t argc, SflVal *argv) {
  return sfl_int(sfl_arg_int(argv, 0, "bitOr") | sfl_arg_int(argv, 1, "bitOr"));
}

SflVal sfl_p_bitXor(int64_t argc, SflVal *argv) {
  return sfl_int(sfl_arg_int(argv, 0, "bitXor") ^ sfl_arg_int(argv, 1, "bitXor"));
}

SflVal sfl_p_bitNot(int64_t argc, SflVal *argv) {
  return sfl_int(~sfl_arg_int(argv, 0, "bitNot"));
}

SflVal sfl_p_shiftLeft(int64_t argc, SflVal *argv) {
  int64_t a = sfl_arg_int(argv, 0, "shiftLeft");
  int64_t n = sfl_arg_int(argv, 1, "shiftLeft");
  return sfl_int((int64_t)((uint64_t)a << SHIFT_COUNT(n)));
}

SflVal sfl_p_shiftRight(int64_t argc, SflVal *argv) {
  int64_t a = sfl_arg_int(argv, 0, "shiftRight");
  int64_t n = sfl_arg_int(argv, 1, "shiftRight");
  /* clang shifts a negative signed value arithmetically, which is what >> means. */
  return sfl_int(a >> SHIFT_COUNT(n));
}

SflVal sfl_p_shiftRightU(int64_t argc, SflVal *argv) {
  int64_t a = sfl_arg_int(argv, 0, "shiftRightU");
  int64_t n = sfl_arg_int(argv, 1, "shiftRightU");
  return sfl_int((int64_t)((uint64_t)a >> SHIFT_COUNT(n)));
}

/* Double.doubleToLongBits: the raw pattern, except every NaN collapses to the
   canonical one — so a NaN produced two different ways still compares equal. */
SflVal sfl_p_doubleToBits(int64_t argc, SflVal *argv) {
  double d = sfl_arg_num(argv, 0, "doubleToBits");
  int64_t bits;
  if (d != d) bits = 0x7ff8000000000000LL;
  else memcpy(&bits, &d, sizeof bits);
  return sfl_int(bits);
}

SflVal sfl_p_bitsToDouble(int64_t argc, SflVal *argv) {
  int64_t bits = sfl_arg_int(argv, 0, "bitsToDouble");
  double d;
  memcpy(&d, &bits, sizeof d);
  return sfl_float(d);
}

/* ------------------------------------------------------------------------- */
/* Randomness                                                                 */
/* ------------------------------------------------------------------------- */

/*
 * The interpreter draws from java.util.Random, whose exact stream cannot be
 * reproduced here without reimplementing the JDK's LCG, so this is xoshiro256**
 * seeded through SplitMix64 instead. What the language actually promises is kept:
 * random() is uniform in [0, 1), and randomSeed(n) makes a run repeatable.
 *
 * The state is global because that is what a single shared generator means.
 */
static uint64_t rng_state[4];
static int rng_ready;

static uint64_t rotl64(uint64_t x, int k) { return (x << k) | (x >> (64 - k)); }

static uint64_t splitmix64(uint64_t *x) {
  uint64_t z = (*x += 0x9E3779B97F4A7C15ULL);
  z = (z ^ (z >> 30)) * 0xBF58476D1CE4E5B9ULL;
  z = (z ^ (z >> 27)) * 0x94D049BB133111EBULL;
  return z ^ (z >> 31);
}

static void rng_seed(uint64_t seed) {
  uint64_t s = seed;
  for (int i = 0; i < 4; i++) rng_state[i] = splitmix64(&s);
  rng_ready = 1;
}

static uint64_t rng_next(void) {
  if (!rng_ready) {
    /* Unseeded runs differ from one another, as new java.util.Random() does. */
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    rng_seed((uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec +
             ((uint64_t)(uintptr_t)&ts << 16) + (uint64_t)getpid());
  }
  uint64_t result = rotl64(rng_state[1] * 5, 7) * 9;
  uint64_t t = rng_state[1] << 17;
  rng_state[2] ^= rng_state[0];
  rng_state[3] ^= rng_state[1];
  rng_state[1] ^= rng_state[2];
  rng_state[0] ^= rng_state[3];
  rng_state[2] ^= t;
  rng_state[3] = rotl64(rng_state[3], 45);
  return result;
}

/* 53 random bits over 2^53, the same construction nextDouble() uses. */
static double rng_double(void) { return (double)(rng_next() >> 11) * 0x1.0p-53; }

SflVal sfl_p_random(int64_t argc, SflVal *argv) { return sfl_float(rng_double()); }

SflVal sfl_p_randomInt(int64_t argc, SflVal *argv) {
  int64_t lo, hi;
  if (argc == 1) {
    lo = 0;
    hi = sfl_arg_int(argv, 0, "randomInt");
  } else {
    lo = sfl_arg_int(argv, 0, "randomInt");
    hi = sfl_arg_int(argv, 1, "randomInt");
  }
  if (hi <= lo)
    sfl_raise_sig("randomInt", "the range [%lld, %lld) is empty", (long long)lo, (long long)hi);
  /* The width and the sum both wrap for extreme bounds, as they do on the JVM. */
  int64_t width = (int64_t)((uint64_t)hi - (uint64_t)lo);
  int64_t off = d2l(rng_double() * (double)width);
  return sfl_int((int64_t)((uint64_t)lo + (uint64_t)off));
}

SflVal sfl_p_randomSeed(int64_t argc, SflVal *argv) {
  rng_seed((uint64_t)sfl_arg_int(argv, 0, "randomSeed"));
  return sfl_null;
}

/* ------------------------------------------------------------------------- */
/* Type predicates                                                            */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_typeOf(int64_t argc, SflVal *argv) {
  return sfl_str_utf8(sfl_type_name(argv[0]), -1);
}

SflVal sfl_p_isNull(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_NULL);
}

SflVal sfl_p_isNumber(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_INT || argv[0]->tag == SFL_FLOAT);
}

SflVal sfl_p_isInt(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_INT);
}

SflVal sfl_p_isFloat(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_FLOAT);
}

SflVal sfl_p_isString(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_STR);
}

SflVal sfl_p_isBool(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_BOOL);
}

SflVal sfl_p_isArray(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_ARR);
}

SflVal sfl_p_isTuple(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_TUPLE);
}

SflVal sfl_p_isObject(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_OBJ);
}

SflVal sfl_p_isFunction(int64_t argc, SflVal *argv) {
  return sfl_bool(argv[0]->tag == SFL_FUN || argv[0]->tag == SFL_NATIVE);
}

/* ------------------------------------------------------------------------- */
/* Type conversions                                                           */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_toString(int64_t argc, SflVal *argv) {
  /* A string comes back untouched; everything else goes through repr, which is
     what makes arrays and objects render as JSON with their strings quoted. */
  if (argv[0]->tag == SFL_STR) return argv[0];
  return sfl_repr(argv[0]);
}

SflVal sfl_p_toInt(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  switch (v->tag) {
    case SFL_INT:
      return v;
    case SFL_FLOAT: {
      if (isnan(v->u.d) || isinf(v->u.d)) {
        char shown[64];
        sfl_write_double(shown, sizeof shown, v->u.d);
        sfl_raise_sig("toInt", "cannot convert %s", shown);
      }
      return sfl_int(d2l(v->u.d));
    }
    case SFL_BOOL:
      return sfl_int(v->aux ? 1 : 0);
    case SFL_STR: {
      /* An integer spelling first, then a float spelling, so "1e3" and "2.7"
         convert too — and "Infinity" saturates rather than failing. */
      char *text = numeric_text(v);
      int64_t n = 0;
      double d = 0.0;
      if (text != NULL) {
        if (java_parse_long(text, 10, &n)) {
          sfl_raw_free(text);
          return sfl_int(n);
        }
        if (java_parse_double(text, &d)) {
          sfl_raw_free(text);
          return sfl_int(d2l(d));
        }
      }
      sfl_raw_free(text);
      raise_quoting("toInt", v, " is not a number");
    }
    default:
      sfl_raise_sig("toInt", "cannot convert %s", sfl_type_name(v));
  }
}

SflVal sfl_p_toFloat(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  switch (v->tag) {
    case SFL_INT:
      return sfl_float((double)v->u.i);
    case SFL_FLOAT:
      return v;
    case SFL_BOOL:
      return sfl_float(v->aux ? 1.0 : 0.0);
    case SFL_STR: {
      char *text = numeric_text(v);
      double d = 0.0;
      int ok = text != NULL && java_parse_double(text, &d);
      sfl_raw_free(text);
      if (!ok) raise_quoting("toFloat", v, " is not a number");
      return sfl_float(d);
    }
    default:
      sfl_raise_sig("toFloat", "cannot convert %s", sfl_type_name(v));
  }
}

SflVal sfl_p_toBool(int64_t argc, SflVal *argv) {
  return sfl_bool(sfl_truthy(argv[0]));
}

/* ------------------------------------------------------------------------- */
/* Transcendental functions                                                   */
/* ------------------------------------------------------------------------- */

/*
 * All of these return a float whatever they are given, as the interpreter's
 * `unary` helper does. The three that reject their domain do so with the
 * interpreter's exact wording; the rest let the libm result through, NaN included.
 */
#define SFL_UNARY(name, expr)                                                  \
  SflVal sfl_p_##name(int64_t argc, SflVal *argv) {                            \
    (void)argc;                                                                \
    double x = sfl_arg_num(argv, 0, #name);                                    \
    return sfl_float(expr);                                                    \
  }

SflVal sfl_p_sqrt(int64_t argc, SflVal *argv) {
  (void)argc;
  double x = sfl_arg_num(argv, 0, "sqrt");
  if (x < 0) sfl_raise_sig("sqrt", "argument must not be negative");
  return sfl_float(sqrt(x));
}

SflVal sfl_p_log(int64_t argc, SflVal *argv) {
  (void)argc;
  double x = sfl_arg_num(argv, 0, "log");
  if (x <= 0) sfl_raise_sig("log", "argument must be positive");
  return sfl_float(log(x));
}

SflVal sfl_p_log2(int64_t argc, SflVal *argv) {
  (void)argc;
  double x = sfl_arg_num(argv, 0, "log2");
  if (x <= 0) sfl_raise_sig("log2", "argument must be positive");
  /* Divided rather than log2(), because that is what the interpreter computes. */
  return sfl_float(log(x) / log(2.0));
}

SflVal sfl_p_log10(int64_t argc, SflVal *argv) {
  (void)argc;
  double x = sfl_arg_num(argv, 0, "log10");
  if (x <= 0) sfl_raise_sig("log10", "argument must be positive");
  return sfl_float(log10(x));
}

SFL_UNARY(cbrt, cbrt(x))
SFL_UNARY(exp, exp(x))
SFL_UNARY(sin, sin(x))
SFL_UNARY(cos, cos(x))
SFL_UNARY(tan, tan(x))
SFL_UNARY(asin, asin(x))
SFL_UNARY(acos, acos(x))
SFL_UNARY(atan, atan(x))
SFL_UNARY(sinh, sinh(x))
SFL_UNARY(cosh, cosh(x))
SFL_UNARY(tanh, tanh(x))

#undef SFL_UNARY
