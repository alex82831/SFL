/*
 * The string builtins: indexing, slicing, searching, case, splitting and
 * printf-style formatting.
 *
 * Every index, length and range here counts UTF-16 code units, because the
 * interpreter's strings are Java strings; anything else would make length(),
 * s[i] and indexOf() disagree with it the moment a program touches text outside
 * ASCII. BuiltinsText.scala is the specification, down to the wording of each
 * error, so the odd-looking rules below — substring rejecting a range that slice
 * silently clamps, split dropping trailing empties only when no limit was given,
 * %.2f rounding half up — are all deliberate.
 */
#include "sfl.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------------- */
/* A growable UTF-16 buffer                                                   */
/* ------------------------------------------------------------------------- */

/* Raw memory, not a value: the collector never sees it, and nothing but code
   units ever goes in, so it needs no protection across an allocation. */
typedef struct {
  uint16_t *data;
  int64_t len, cap;
} Buf;

static void buf_init(Buf *b) {
  b->cap = 64;
  b->len = 0;
  b->data = (uint16_t *)sfl_raw_alloc((size_t)b->cap * 2);
}

static void buf_free(Buf *b) {
  sfl_raw_free(b->data);
  b->data = NULL;
}

static void buf_room(Buf *b, int64_t extra) {
  if (b->len + extra <= b->cap) return;
  while (b->cap < b->len + extra) b->cap *= 2;
  b->data = (uint16_t *)sfl_raw_realloc(b->data, (size_t)b->cap * 2);
}

static void buf_ch(Buf *b, uint16_t c) {
  buf_room(b, 1);
  b->data[b->len++] = c;
}

static void buf_units(Buf *b, const uint16_t *u, int64_t n) {
  buf_room(b, n);
  if (n > 0) memcpy(b->data + b->len, u, (size_t)n * 2);
  b->len += n;
}

static void buf_ascii(Buf *b, const char *s) {
  int64_t n = (int64_t)strlen(s);
  buf_room(b, n);
  for (int64_t i = 0; i < n; i++) b->data[b->len++] = (uint16_t)(unsigned char)s[i];
}

static void buf_repeat(Buf *b, uint16_t c, int64_t n) {
  buf_room(b, n > 0 ? n : 0);
  for (int64_t i = 0; i < n; i++) b->data[b->len++] = c;
}

static SflVal buf_take(Buf *b) {
  SflVal s = sfl_str_utf16(b->data, b->len);
  sfl_raw_free(b->data);
  b->data = NULL;
  return s;
}

/* s[from, to) as a fresh string. */
static SflVal str_sub(SflVal s, int64_t from, int64_t to) {
  return sfl_str_utf16(s->u.s.chars + from, to - from);
}

/* ------------------------------------------------------------------------- */
/* Case mapping                                                               */
/* ------------------------------------------------------------------------- */

/*
 * The tables are measured from the interpreter's own String implementation, so
 * every code point maps exactly as it does there — the multi-unit expansions
 * (ß to SS, the iota subscripts, the ligatures) and the supplementary planes
 * included. tools/gen-case-tables.py regenerates them.
 */
#include "sfl_case_tables.h"

#define TABLE_LEN(t) ((int64_t)(sizeof(t) / sizeof (t)[0]))

static int is_hi_surrogate(uint16_t c) { return c >= 0xD800 && c < 0xDC00; }
static int is_lo_surrogate(uint16_t c) { return c >= 0xDC00 && c < 0xE000; }

/* c itself when the table has no entry: everything absent maps to itself. */
static uint16_t case1_map(const SflCase1 *t, int64_t n, uint16_t c) {
  int64_t lo = 0, hi = n - 1;
  while (lo <= hi) {
    int64_t mid = (lo + hi) / 2;
    if (t[mid].cp < c) lo = mid + 1;
    else if (t[mid].cp > c) hi = mid - 1;
    else return t[mid].to;
  }
  return c;
}

static const SflCaseN *casen_find(const SflCaseN *t, int64_t n, uint16_t c) {
  int64_t lo = 0, hi = n - 1;
  while (lo <= hi) {
    int64_t mid = (lo + hi) / 2;
    if (t[mid].cp < c) lo = mid + 1;
    else if (t[mid].cp > c) hi = mid - 1;
    else return &t[mid];
  }
  return NULL;
}

static uint32_t supp_map(const SflCaseSupp *t, int64_t n, uint32_t cp) {
  int64_t lo = 0, hi = n - 1;
  while (lo <= hi) {
    int64_t mid = (lo + hi) / 2;
    if (t[mid].cp < cp) lo = mid + 1;
    else if (t[mid].cp > cp) hi = mid - 1;
    else return t[mid].to;
  }
  return cp;
}

static int range_has(const SflCaseRange *t, int64_t n, uint32_t cp) {
  int64_t lo = 0, hi = n - 1;
  while (lo <= hi) {
    int64_t mid = (lo + hi) / 2;
    if (t[mid].hi < cp) lo = mid + 1;
    else if (t[mid].lo > cp) hi = mid - 1;
    else return 1;
  }
  return 0;
}

static int is_cased(uint32_t cp) { return range_has(sfl_cased, TABLE_LEN(sfl_cased), cp); }

/* The marks Unicode skips when deciding whether a sigma is word-final. */
static int is_case_ignorable(uint32_t cp) {
  return range_has(sfl_case_ignorable, TABLE_LEN(sfl_case_ignorable), cp);
}

/* Unicode's Final_Sigma as the interpreter applies it: a cased character
   before the sigma and none after, skipping case-ignorable ones, so a
   word-final capital sigma lowers to ς. Both scans decode surrogate pairs
   (an unpaired surrogate is neither cased nor ignorable), but only the
   backward one accepts a supplementary letter as cased — measured behavior,
   like the tables themselves. */
static int final_sigma(const uint16_t *u, int64_t n, int64_t at) {
  int64_t i = at - 1;
  for (;;) {
    if (i < 0) return 0;
    uint32_t cp = u[i];
    int64_t w = 1;
    if (is_lo_surrogate(u[i]) && i > 0 && is_hi_surrogate(u[i - 1])) {
      cp = 0x10000 + (((uint32_t)(u[i - 1] - 0xD800)) << 10) + (u[i] - 0xDC00);
      w = 2;
    }
    if (!is_case_ignorable(cp)) {
      if (!is_cased(cp)) return 0;
      break;
    }
    i -= w;
  }
  i = at + 1;
  for (;;) {
    if (i >= n) return 1;
    uint32_t cp = u[i];
    int64_t w = 1;
    if (is_hi_surrogate(u[i]) && i + 1 < n && is_lo_surrogate(u[i + 1])) {
      cp = 0x10000 + (((uint32_t)(u[i] - 0xD800)) << 10) + (u[i + 1] - 0xDC00);
      w = 2;
    }
    if (!is_case_ignorable(cp)) return !(cp <= 0xFFFF && is_cased(cp));
    i += w;
  }
}

static void buf_supp(Buf *dst, uint32_t cp) {
  uint32_t x = cp - 0x10000;
  buf_ch(dst, (uint16_t)(0xD800 + (x >> 10)));
  buf_ch(dst, (uint16_t)(0xDC00 + (x & 0x3FF)));
}

static void upcase_units(Buf *dst, const uint16_t *u, int64_t n) {
  for (int64_t i = 0; i < n; i++) {
    uint16_t c = u[i];
    if (c < 0x80) {
      buf_ch(dst, (c >= 'a' && c <= 'z') ? (uint16_t)(c - 32) : c);
      continue;
    }
    if (is_hi_surrogate(c) && i + 1 < n) {
      if (is_lo_surrogate(u[i + 1])) {
        uint32_t cp = 0x10000 + (((uint32_t)(c - 0xD800)) << 10) + (u[i + 1] - 0xDC00);
        buf_supp(dst, supp_map(sfl_upper_supp, TABLE_LEN(sfl_upper_supp), cp));
        i++;
        continue;
      }
      if (is_hi_surrogate(u[i + 1])) {
        /* The interpreter consumes an invalid high+high sequence whole, so a
           valid pair starting at the second unit is never case-mapped. */
        buf_ch(dst, c);
        buf_ch(dst, u[i + 1]);
        i++;
        continue;
      }
    }
    const SflCaseN *e = casen_find(sfl_upper_n, TABLE_LEN(sfl_upper_n), c);
    if (e) {
      for (int k = 0; k < e->n; k++) buf_ch(dst, e->out[k]);
      continue;
    }
    buf_ch(dst, case1_map(sfl_upper_1, TABLE_LEN(sfl_upper_1), c));
  }
}

static void downcase_units(Buf *dst, const uint16_t *u, int64_t n) {
  for (int64_t i = 0; i < n; i++) {
    uint16_t c = u[i];
    if (c < 0x80) {
      buf_ch(dst, (c >= 'A' && c <= 'Z') ? (uint16_t)(c + 32) : c);
      continue;
    }
    if (c == 0x3A3 && final_sigma(u, n, i)) {
      buf_ch(dst, 0x3C2);
      continue;
    }
    if (is_hi_surrogate(c) && i + 1 < n) {
      if (is_lo_surrogate(u[i + 1])) {
        uint32_t cp = 0x10000 + (((uint32_t)(c - 0xD800)) << 10) + (u[i + 1] - 0xDC00);
        buf_supp(dst, supp_map(sfl_lower_supp, TABLE_LEN(sfl_lower_supp), cp));
        i++;
        continue;
      }
      if (is_hi_surrogate(u[i + 1])) {
        buf_ch(dst, c);
        buf_ch(dst, u[i + 1]);
        i++;
        continue;
      }
    }
    const SflCaseN *e = casen_find(sfl_lower_n, TABLE_LEN(sfl_lower_n), c);
    if (e) {
      for (int k = 0; k < e->n; k++) buf_ch(dst, e->out[k]);
      continue;
    }
    buf_ch(dst, case1_map(sfl_lower_1, TABLE_LEN(sfl_lower_1), c));
  }
}

/* ------------------------------------------------------------------------- */
/* Searching                                                                  */
/* ------------------------------------------------------------------------- */

/* java.lang.String.indexOf(String, int), including its treatment of an empty
   needle and of a `from` outside the string. */
static int64_t str_index_of(SflVal hay, SflVal needle, int64_t from) {
  int64_t n = (int64_t)hay->aux, m = (int64_t)needle->aux;
  if (from >= n) return m == 0 ? n : -1;
  if (from < 0) from = 0;
  if (m == 0) return from;
  for (int64_t i = from; i + m <= n; i++)
    if (memcmp(hay->u.s.chars + i, needle->u.s.chars, (size_t)m * 2) == 0) return i;
  return -1;
}

/* java.lang.String.lastIndexOf(String): an empty needle is found at the end. */
static int64_t str_last_index_of(SflVal hay, SflVal needle) {
  int64_t n = (int64_t)hay->aux, m = (int64_t)needle->aux;
  if (m > n) return -1;
  if (m == 0) return n;
  for (int64_t i = n - m; i >= 0; i--)
    if (memcmp(hay->u.s.chars + i, needle->u.s.chars, (size_t)m * 2) == 0) return i;
  return -1;
}

/* ------------------------------------------------------------------------- */
/* length, isEmpty, charAt                                                    */
/* ------------------------------------------------------------------------- */

/* The unboxed core; the compiler calls it directly where the result feeds
   typed code, so a loop bound like `i < length(arr)` costs no allocation. */
int64_t sfl_length_i(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  switch (v->tag) {
    case SFL_STR:
    case SFL_ARR:
    case SFL_OBJ: return (int64_t)v->aux;
    case SFL_NULL: return 0;
    default: sfl_raise_sig("length", "%s has no length", sfl_type_name(v));
  }
}

SflVal sfl_p_length(int64_t argc, SflVal *argv) {
  return sfl_int(sfl_length_i(argc, argv));
}

SflVal sfl_p_isEmpty(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  switch (v->tag) {
    case SFL_STR:
    case SFL_ARR:
    case SFL_OBJ: return sfl_bool(v->aux == 0);
    case SFL_NULL: return sfl_true;
    default: sfl_raise_sig("isEmpty", "%s has no length", sfl_type_name(v));
  }
}

SflVal sfl_p_charAt(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "charAt");
  int64_t len = (int64_t)s->aux;
  int64_t i = sfl_arg_idx(argv, 1, "charAt");
  if (i < 0) i += len;
  if (i < 0 || i >= len)
    sfl_raise_sig("charAt", "index out of bounds (length %lld)", (long long)len);
  return str_sub(s, i, i + 1);
}

/* ------------------------------------------------------------------------- */
/* Slicing                                                                    */
/* ------------------------------------------------------------------------- */

/* subString/substring reject a range they cannot honour; only the negative ends
   are normalised, which is why the message quotes the arguments as given. */
static SflVal substr(int64_t argc, SflVal *argv, const char *fn) {
  SflVal s = sfl_arg_str(argv, 0, fn);
  int64_t len = (int64_t)s->aux;
  int32_t start = sfl_arg_idx(argv, 1, fn);
  int32_t end = argc > 2 ? sfl_arg_idx(argv, 2, fn) : (int32_t)len;
  int64_t b = start < 0 ? len + start : start;
  int64_t e = end < 0 ? len + end : end;
  if (b < 0 || e > len || b > e)
    sfl_raise_sig(fn, "range [%d, %d) is invalid for a string of length %lld", start, end,
              (long long)len);
  return str_sub(s, b, e);
}

SflVal sfl_p_subString(int64_t argc, SflVal *argv) { return substr(argc, argv, "subString"); }
SflVal sfl_p_substring(int64_t argc, SflVal *argv) { return substr(argc, argv, "substring"); }

/* slice, by contrast, clamps: any range at all yields a (possibly empty) result. */
static void slice_range(int64_t argc, SflVal *argv, int64_t len, const char *fn, int64_t *bo,
                        int64_t *eo) {
  int32_t s1 = sfl_arg_idx(argv, 1, fn);
  int64_t raw_b = s1 < 0 ? len + s1 : s1;
  int64_t raw_e = len;
  if (argc > 2) {
    int32_t s2 = sfl_arg_idx(argv, 2, fn);
    raw_e = s2 < 0 ? len + s2 : s2;
  }
  int64_t b = raw_b < 0 ? 0 : (raw_b > len ? len : raw_b);
  int64_t e = raw_e > len ? len : raw_e;
  if (e < b) e = b;
  *bo = b;
  *eo = e;
}

SflVal sfl_p_slice(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  int64_t b, e;
  if (v->tag == SFL_STR) {
    slice_range(argc, argv, (int64_t)v->aux, "slice", &b, &e);
    return str_sub(v, b, e);
  }
  if (v->tag == SFL_ARR) {
    slice_range(argc, argv, (int64_t)v->aux, "slice", &b, &e);
    SflVal out = sfl_arr_new(e - b);
    for (int64_t i = b; i < e; i++) out->u.a.items[i - b] = v->u.a.items[i];
    out->aux = (uint32_t)(e - b);
    return out;
  }
  sfl_raise_sig("slice", "expected a string or array, got %s", sfl_type_name(v));
}

/* ------------------------------------------------------------------------- */
/* Trimming and case                                                          */
/* ------------------------------------------------------------------------- */

/* java.util.regex's \s, which is the six ASCII spaces and nothing else. */
static int regex_space(uint16_t c) {
  return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r';
}

SflVal sfl_p_trim(int64_t argc, SflVal *argv) {
  /* String.trim cuts everything at or below U+0020, not Unicode whitespace. */
  SflVal s = sfl_arg_str(argv, 0, "trim");
  int64_t b = 0, e = (int64_t)s->aux;
  while (b < e && s->u.s.chars[b] <= ' ') b++;
  while (e > b && s->u.s.chars[e - 1] <= ' ') e--;
  return str_sub(s, b, e);
}

SflVal sfl_p_trimStart(int64_t argc, SflVal *argv) {
  /* The interpreter uses replaceAll("^\\s+", ""), a narrower set than trim's. */
  SflVal s = sfl_arg_str(argv, 0, "trimStart");
  int64_t b = 0, n = (int64_t)s->aux;
  while (b < n && regex_space(s->u.s.chars[b])) b++;
  return str_sub(s, b, n);
}

SflVal sfl_p_trimEnd(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "trimEnd");
  int64_t e = (int64_t)s->aux;
  while (e > 0 && regex_space(s->u.s.chars[e - 1])) e--;
  return str_sub(s, 0, e);
}

SflVal sfl_p_toUpper(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "toUpper");
  Buf b;
  buf_init(&b);
  upcase_units(&b, s->u.s.chars, (int64_t)s->aux);
  return buf_take(&b);
}

SflVal sfl_p_toLower(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "toLower");
  Buf b;
  buf_init(&b);
  downcase_units(&b, s->u.s.chars, (int64_t)s->aux);
  return buf_take(&b);
}

/* ------------------------------------------------------------------------- */
/* split, replace, reverse                                                    */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_split(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "split");
  SflVal sep = sfl_arg_str(argv, 1, "split");
  SflVal out = sfl_arr_new(8);

  /* An empty separator is not treated as a pattern at all: the string simply
     falls apart into its code units, with no empty ends. */
  if (sep->aux == 0) {
    for (uint32_t i = 0; i < s->aux; i++) sfl_arr_push(out, str_sub(s, i, i + 1));
    return out;
  }

  int64_t limit = argc > 2 ? sfl_arg_idx(argv, 2, "split") : 0;
  int limited = limit > 0;
  int64_t len = (int64_t)s->aux, sep_len = (int64_t)sep->aux;
  int64_t index = 0, pos = 0, next;

  /* Pattern.split, with the literal search standing in for the quoted regex. */
  while ((next = str_index_of(s, sep, pos)) >= 0) {
    int64_t count = (int64_t)out->aux;
    if (!limited || count < limit - 1) {
      sfl_arr_push(out, str_sub(s, index, next));
      index = next + sep_len;
    } else if (count == limit - 1) {
      sfl_arr_push(out, str_sub(s, index, len));
      index = next + sep_len;
    }
    pos = next + sep_len;
  }
  if (index == 0) { /* the separator never occurred */
    sfl_arr_push(out, s);
    return out;
  }
  if (!limited || (int64_t)out->aux < limit) sfl_arr_push(out, str_sub(s, index, len));
  /* Only an unstated limit drops the empty tail. */
  if (limit == 0)
    while (out->aux > 0 && out->u.a.items[out->aux - 1]->aux == 0) out->aux--;
  return out;
}

SflVal sfl_p_replace(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "replace");
  SflVal target = sfl_arg_str(argv, 1, "replace");
  SflVal repl = sfl_arg_str(argv, 2, "replace");
  Buf b;
  buf_init(&b);

  if (target->aux == 0) {
    /* String.replace with an empty target wraps every character. */
    buf_units(&b, repl->u.s.chars, (int64_t)repl->aux);
    for (uint32_t i = 0; i < s->aux; i++) {
      buf_ch(&b, s->u.s.chars[i]);
      buf_units(&b, repl->u.s.chars, (int64_t)repl->aux);
    }
    return buf_take(&b);
  }

  int64_t pos = 0, len = (int64_t)s->aux, at;
  while ((at = str_index_of(s, target, pos)) >= 0) {
    buf_units(&b, s->u.s.chars + pos, at - pos);
    buf_units(&b, repl->u.s.chars, (int64_t)repl->aux);
    pos = at + (int64_t)target->aux;
  }
  buf_units(&b, s->u.s.chars + pos, len - pos);
  return buf_take(&b);
}

SflVal sfl_p_reverse(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_STR) {
    int64_t n = (int64_t)v->aux;
    Buf b;
    buf_init(&b);
    buf_room(&b, n);
    for (int64_t i = 0; i < n; i++) b.data[i] = v->u.s.chars[n - 1 - i];
    b.len = n;
    /* StringBuilder.reverse puts surrogate pairs back the right way round, so
       reversing text outside the BMP still yields valid characters. */
    for (int64_t i = 0; i + 1 < n; i++) {
      uint16_t lo = b.data[i];
      if (lo >= 0xDC00 && lo < 0xE000) {
        uint16_t hi = b.data[i + 1];
        if (hi >= 0xD800 && hi < 0xDC00) {
          b.data[i] = hi;
          b.data[i + 1] = lo;
          i++;
        }
      }
    }
    return buf_take(&b);
  }
  if (v->tag == SFL_ARR) {
    int64_t n = (int64_t)v->aux;
    SflVal out = sfl_arr_new(n);
    for (int64_t i = 0; i < n; i++) out->u.a.items[i] = v->u.a.items[n - 1 - i];
    out->aux = (uint32_t)n;
    return out;
  }
  sfl_raise_sig("reverse", "expected a string or array, got %s", sfl_type_name(v));
}

/* ------------------------------------------------------------------------- */
/* Searching primitives                                                       */
/* ------------------------------------------------------------------------- */

static int64_t index_of_impl(int64_t argc, SflVal *argv, const char *fn) {
  SflVal s = sfl_arg_str(argv, 0, fn);
  SflVal needle = sfl_arg_str(argv, 1, fn);
  int64_t from = argc > 2 ? sfl_arg_idx(argv, 2, fn) : 0;
  return str_index_of(s, needle, from);
}

int64_t sfl_strFind_i(int64_t argc, SflVal *argv) { return index_of_impl(argc, argv, "strFind"); }

SflVal sfl_p_strFind(int64_t argc, SflVal *argv) { return sfl_int(sfl_strFind_i(argc, argv)); }

int64_t sfl_indexOf_i(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_STR) return index_of_impl(argc, argv, "indexOf");
  if (v->tag == SFL_ARR) {
    int64_t from = 0;
    if (argc > 2) {
      from = sfl_arg_idx(argv, 2, "indexOf");
      if (from < 0) from = 0;
    }
    for (int64_t i = from; i < (int64_t)v->aux; i++)
      if (sfl_equal(v->u.a.items[i], argv[1])) return i;
    return -1;
  }
  sfl_raise_sig("indexOf", "expected a string or array, got %s", sfl_type_name(v));
}

SflVal sfl_p_indexOf(int64_t argc, SflVal *argv) {
  return sfl_int(sfl_indexOf_i(argc, argv));
}

int64_t sfl_lastIndexOf_i(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  if (v->tag == SFL_STR)
    return str_last_index_of(v, sfl_arg_str(argv, 1, "lastIndexOf"));
  if (v->tag == SFL_ARR) {
    for (int64_t i = (int64_t)v->aux - 1; i >= 0; i--)
      if (sfl_equal(v->u.a.items[i], argv[1])) return i;
    return -1;
  }
  sfl_raise_sig("lastIndexOf", "expected a string or array, got %s", sfl_type_name(v));
}

SflVal sfl_p_lastIndexOf(int64_t argc, SflVal *argv) {
  return sfl_int(sfl_lastIndexOf_i(argc, argv));
}

SflVal sfl_p_contains(int64_t argc, SflVal *argv) {
  SflVal v = argv[0];
  switch (v->tag) {
    case SFL_STR:
      return sfl_bool(str_index_of(v, sfl_arg_str(argv, 1, "contains"), 0) >= 0);
    case SFL_ARR:
      for (uint32_t i = 0; i < v->aux; i++)
        if (sfl_equal(v->u.a.items[i], argv[1])) return sfl_true;
      return sfl_false;
    case SFL_OBJ:
      return sfl_bool(sfl_obj_get(v, sfl_arg_str(argv, 1, "contains")) != NULL);
    default:
      sfl_raise_sig("contains", "expected a string, array or object, got %s", sfl_type_name(v));
  }
}

SflVal sfl_p_startsWith(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "startsWith");
  SflVal p = sfl_arg_str(argv, 1, "startsWith");
  if (p->aux > s->aux) return sfl_false;
  return sfl_bool(memcmp(s->u.s.chars, p->u.s.chars, (size_t)p->aux * 2) == 0);
}

SflVal sfl_p_endsWith(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "endsWith");
  SflVal p = sfl_arg_str(argv, 1, "endsWith");
  if (p->aux > s->aux) return sfl_false;
  return sfl_bool(memcmp(s->u.s.chars + (s->aux - p->aux), p->u.s.chars, (size_t)p->aux * 2) == 0);
}

SflVal sfl_p_compare(int64_t argc, SflVal *argv) {
  int32_t c = sfl_compare(argv[0], argv[1]);
  return sfl_int(c < 0 ? -1 : (c > 0 ? 1 : 0));
}

/* ------------------------------------------------------------------------- */
/* Code points                                                                */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_chr(int64_t argc, SflVal *argv) {
  int32_t cp = sfl_arg_idx(argv, 0, "chr");
  if (cp < 0 || cp > 0x10FFFF) sfl_raise_sig("chr", "%d is not a valid code point", cp);
  if (cp < 0x10000) {
    uint16_t u = (uint16_t)cp;
    return sfl_str_utf16(&u, 1);
  }
  uint32_t x = (uint32_t)cp - 0x10000;
  uint16_t pair[2] = {(uint16_t)(0xD800 + (x >> 10)), (uint16_t)(0xDC00 + (x & 0x3FF))};
  return sfl_str_utf16(pair, 2);
}

int64_t sfl_ord_i(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "ord");
  if (s->aux == 0) sfl_raise_sig("ord", "string is empty");
  uint16_t c = s->u.s.chars[0];
  if (c >= 0xD800 && c < 0xDC00 && s->aux > 1) {
    uint16_t d = s->u.s.chars[1];
    if (d >= 0xDC00 && d < 0xE000)
      return 0x10000 + (((int64_t)c - 0xD800) << 10) + (d - 0xDC00);
  }
  return c;
}

SflVal sfl_p_ord(int64_t argc, SflVal *argv) { return sfl_int(sfl_ord_i(argc, argv)); }

/* ------------------------------------------------------------------------- */
/* format                                                                     */
/* ------------------------------------------------------------------------- */

/*
 * java.util.Formatter, over the values the interpreter boxes for it: an int
 * becomes a Long, a float a Double, a string a String, a bool a Boolean, null
 * stays null and anything else arrives as its repr. That boxing is what makes
 * %d reject a float and %f reject an int, and the error text below is the
 * exception class name Scala's catch clause would have printed.
 */

typedef struct {
  int left, plus, space, zero, group, paren, alt, prev;
  int64_t width, precision, index;
  uint16_t conv;
} Spec;

typedef enum { FA_NULL, FA_LONG, FA_DOUBLE, FA_STRING, FA_BOOL } FmtKind;

static void format_error(const char *exception, SflVal fmt) __attribute__((noreturn));
static void format_error(const char *exception, SflVal fmt) {
  char *t = sfl_str_dup_utf8(fmt);
  char msg[1024];
  snprintf(msg, sizeof msg, "format: %s for pattern '%s'", exception, t);
  sfl_raw_free(t);
  sfl_raise_hint(msg, "");
}

static FmtKind fmt_kind(SflVal v) {
  switch (v->tag) {
    case SFL_NULL: return FA_NULL;
    case SFL_INT: return FA_LONG;
    case SFL_FLOAT: return FA_DOUBLE;
    case SFL_STR: return FA_STRING;
    case SFL_BOOL: return FA_BOOL;
    default: return FA_STRING; /* boxed as its repr */
  }
}

/* Parses %[index$][flags][width][.precision]conv, starting just past the '%'. */
static int64_t parse_spec(SflVal fmt, int64_t i, Spec *sp) {
  const uint16_t *f = fmt->u.s.chars;
  int64_t n = (int64_t)fmt->aux;
  memset(sp, 0, sizeof *sp);
  sp->width = -1;
  sp->precision = -1;

  int64_t save = i, v = 0;
  while (i < n && f[i] >= '0' && f[i] <= '9') {
    if (v < 1000000000) v = v * 10 + (f[i] - '0');
    i++;
  }
  if (i > save && i < n && f[i] == '$') {
    sp->index = v;
    i++;
  } else {
    i = save; /* those digits were flags and a width after all */
  }

  /* Flags are parsed before anything else, and a repeated one fails there. */
  while (i < n) {
    int *flag;
    switch (f[i]) {
      case '-': flag = &sp->left; break;
      case '+': flag = &sp->plus; break;
      case ' ': flag = &sp->space; break;
      case '0': flag = &sp->zero; break;
      case ',': flag = &sp->group; break;
      case '(': flag = &sp->paren; break;
      case '#': flag = &sp->alt; break;
      case '<': flag = &sp->prev; break;
      default: flag = NULL; break;
    }
    if (flag == NULL) break;
    if (*flag) format_error("DuplicateFormatFlagsException", fmt);
    *flag = 1;
    i++;
  }

  if (i < n && f[i] >= '0' && f[i] <= '9') {
    int64_t w = 0;
    while (i < n && f[i] >= '0' && f[i] <= '9') {
      if (w < 1000000000) w = w * 10 + (f[i] - '0');
      i++;
    }
    sp->width = w;
  }

  if (i < n && f[i] == '.') {
    i++;
    int64_t p = 0, digits = 0;
    while (i < n && f[i] >= '0' && f[i] <= '9') {
      if (p < 1000000000) p = p * 10 + (f[i] - '0');
      i++;
      digits++;
    }
    if (digits == 0) format_error("UnknownFormatConversionException", fmt);
    sp->precision = p;
  }

  if (i >= n) format_error("UnknownFormatConversionException", fmt);
  sp->conv = f[i];
  return i + 1;
}

/*
 * Formatter's own checks. It builds and checks every directive of a pattern
 * before it prints any of them, so an illegal flag late in a pattern is what
 * gets reported even when an earlier directive has no argument. `validate`
 * below is that first pass; only the checks that need the argument are left to
 * printing time.
 */
static void check_general(const Spec *sp, SflVal fmt, int is_bool) {
  if (is_bool && sp->alt) format_error("FormatFlagsConversionMismatchException", fmt);
  if (sp->width < 0 && sp->left) format_error("MissingFormatWidthException", fmt);
  if (sp->plus || sp->space || sp->zero || sp->group || sp->paren)
    format_error("FormatFlagsConversionMismatchException", fmt);
}

static void check_numeric(const Spec *sp, SflVal fmt) {
  if (sp->width < 0 && (sp->left || sp->zero)) format_error("MissingFormatWidthException", fmt);
  if ((sp->plus && sp->space) || (sp->left && sp->zero))
    format_error("IllegalFormatFlagsException", fmt);
}

static void check_integer(const Spec *sp, SflVal fmt) {
  check_numeric(sp, fmt);
  if (sp->precision >= 0) format_error("IllegalFormatPrecisionException", fmt);
}

/* The uppercase spelling of a conversion asks for an upper-cased result; every
   other capital letter is simply not a conversion. */
static uint16_t fold_conv(uint16_t conv, int *upper) {
  *upper = 1;
  switch (conv) {
    case 'S': return 's';
    case 'B': return 'b';
    case 'X': return 'x';
    case 'E': return 'e';
    case 'G': return 'g';
    case 'C': return 'c';
    case 'A': return 'a';
    case 'H': return 'h';
    default: *upper = 0; return conv;
  }
}

static void check_spec(const Spec *sp, uint16_t conv, SflVal fmt) {
  switch (conv) {
    case '%': /* checkText */
      if (sp->precision >= 0) format_error("IllegalFormatPrecisionException", fmt);
      if (sp->plus || sp->space || sp->zero || sp->group || sp->paren || sp->alt || sp->prev)
        format_error("IllegalFormatFlagsException", fmt);
      if (sp->width < 0 && sp->left) format_error("MissingFormatWidthException", fmt);
      break;
    case 'n':
      if (sp->precision >= 0) format_error("IllegalFormatPrecisionException", fmt);
      if (sp->width >= 0) format_error("IllegalFormatWidthException", fmt);
      if (sp->left || sp->plus || sp->space || sp->zero || sp->group || sp->paren ||
          sp->alt || sp->prev)
        format_error("IllegalFormatFlagsException", fmt);
      break;
    case 's': check_general(sp, fmt, 0); break;
    case 'b': check_general(sp, fmt, 1); break;
    case 'c': /* checkCharacter */
      if (sp->precision >= 0) format_error("IllegalFormatPrecisionException", fmt);
      if (sp->alt || sp->plus || sp->space || sp->zero || sp->group || sp->paren)
        format_error("FormatFlagsConversionMismatchException", fmt);
      if (sp->width < 0 && sp->left) format_error("MissingFormatWidthException", fmt);
      break;
    case 'd':
      check_integer(sp, fmt);
      if (sp->alt) format_error("FormatFlagsConversionMismatchException", fmt);
      break;
    case 'x':
    case 'o':
      check_integer(sp, fmt);
      if (sp->group) format_error("FormatFlagsConversionMismatchException", fmt);
      break;
    case 'f': check_numeric(sp, fmt); break;
    case 'e':
      check_numeric(sp, fmt);
      if (sp->group) format_error("FormatFlagsConversionMismatchException", fmt);
      break;
    case 'g':
      check_numeric(sp, fmt);
      if (sp->alt) format_error("FormatFlagsConversionMismatchException", fmt);
      break;
    default:
      format_error("UnknownFormatConversionException", fmt);
  }
}

static void validate(SflVal fmt) {
  for (int64_t i = 0; i < (int64_t)fmt->aux;) {
    if (fmt->u.s.chars[i] != '%') {
      i++;
      continue;
    }
    Spec sp;
    i = parse_spec(fmt, i + 1, &sp);
    int upper;
    check_spec(&sp, fold_conv(sp.conv, &upper), fmt);
  }
}

/* Formatter's print(String): a precision clips the text, including the "null"
   it substitutes for a missing argument under any conversion. */
static void clip(Buf *p, const Spec *sp) {
  if (sp->precision >= 0 && p->len > sp->precision) p->len = sp->precision;
}

static void justify(Buf *out, const Buf *piece, const Spec *sp) {
  int64_t pad = sp->width > piece->len ? sp->width - piece->len : 0;
  if (!sp->left) buf_repeat(out, ' ', pad);
  buf_units(out, piece->data, piece->len);
  if (sp->left) buf_repeat(out, ' ', pad);
}

/* The magnitude of an int64, safe at INT64_MIN where negation is not. */
static void i64_magnitude(char *out, int64_t v) {
  uint64_t m = v < 0 ? (uint64_t)0 - (uint64_t)v : (uint64_t)v;
  char tmp[24];
  int n = 0;
  do {
    tmp[n++] = (char)('0' + (int)(m % 10));
    m /= 10;
  } while (m);
  for (int i = 0; i < n; i++) out[i] = tmp[n - 1 - i];
  out[n] = '\0';
}

static void u64_radix(char *out, uint64_t v, unsigned radix, int upper) {
  const char *lo = "0123456789abcdef", *hi = "0123456789ABCDEF";
  const char *d = upper ? hi : lo;
  char tmp[72];
  int n = 0;
  do {
    tmp[n++] = d[v % radix];
    v /= radix;
  } while (v);
  for (int i = 0; i < n; i++) out[i] = tmp[n - 1 - i];
  out[n] = '\0';
}

static void buf_grouped(Buf *b, const uint16_t *d, int64_t n) {
  for (int64_t i = 0; i < n; i++) {
    if (i > 0 && (n - i) % 3 == 0) buf_ch(b, ',');
    buf_ch(b, d[i]);
  }
}

/* sign, prefix, the zeros the '0' flag asks for, then the magnitude. */
static void emit_number(Buf *p, int neg, const Buf *mag, const Spec *sp, const char *prefix) {
  const char *lead = neg ? (sp->paren ? "(" : "-") : (sp->plus ? "+" : (sp->space ? " " : ""));
  int64_t trail = (neg && sp->paren) ? 1 : 0;
  int64_t fixed = (int64_t)strlen(lead) + (int64_t)strlen(prefix) + mag->len + trail;
  int64_t zeros = (sp->zero && sp->width > fixed) ? sp->width - fixed : 0;
  buf_ascii(p, lead);
  buf_ascii(p, prefix);
  buf_repeat(p, '0', zeros);
  buf_units(p, mag->data, mag->len);
  if (trail) buf_ch(p, ')');
}

/*
 * The shortest decimal that reads back as `v`: `*nd` digits with the value being
 * 0.digits x 10^(*dec). This is the digit string Java hands its formatter, which
 * is why %.2f of 2.675 gives 2.68 there and 2.67 in C — the rounding below is
 * applied to these digits, not to the exact binary value.
 */
static void shortest_digits(double v, char *digits, int *nd, int *dec) {
  char sci[64];
  int prec = 0;
  for (; prec < 17; prec++) {
    snprintf(sci, sizeof sci, "%.*e", prec, v);
    if (strtod(sci, NULL) == v) break;
  }
  if (prec == 17) snprintf(sci, sizeof sci, "%.*e", 17, v);

  const char *s = sci;
  if (*s == '-') s++;
  int n = 0;
  digits[n++] = *s++;
  if (*s == '.') {
    s++;
    while (*s >= '0' && *s <= '9') digits[n++] = *s++;
  }
  while (*s && *s != 'e' && *s != 'E') s++;
  int e = (int)strtol(s + 1, NULL, 10);
  while (n > 1 && digits[n - 1] == '0') n--;
  *nd = n;
  *dec = e + 1;
}

/* FormattedFloatingDecimal.applyPrecision: keep `keep` significant digits,
   rounding half up, and report the decimal exponent afterwards. */
static int apply_precision(int dec, char *digits, int nd, int keep) {
  if (keep >= nd || keep < 0) return dec;
  if (keep == 0) {
    if (digits[0] >= '5') {
      digits[0] = '1';
      memset(digits + 1, '0', (size_t)nd - 1);
      return dec + 1;
    }
    memset(digits, '0', (size_t)nd);
    return dec;
  }
  if (digits[keep] >= '5') {
    int i = keep;
    char q = digits[--i];
    if (q == '9') {
      while (q == '9' && i > 0) q = digits[--i];
      if (q == '9') { /* every kept digit was a nine: carry into a new place */
        digits[0] = '1';
        memset(digits + 1, '0', (size_t)nd - 1);
        return dec + 1;
      }
    }
    digits[i] = (char)(q + 1);
    memset(digits + i + 1, '0', (size_t)(nd - i - 1));
  } else {
    memset(digits + keep, '0', (size_t)(nd - keep));
  }
  return dec;
}

static void write_fixed(Buf *mag, const char *digits, int nd, int dec, int64_t prec, int group,
                        int alt) {
  Buf ip;
  buf_init(&ip);
  if (dec <= 0) buf_ch(&ip, '0');
  else
    for (int i = 0; i < dec; i++) buf_ch(&ip, (uint16_t)(i < nd ? digits[i] : '0'));
  if (group) buf_grouped(mag, ip.data, ip.len);
  else buf_units(mag, ip.data, ip.len);
  buf_free(&ip);

  if (prec > 0) {
    buf_ch(mag, '.');
    for (int64_t i = 0; i < prec; i++) {
      int64_t k = dec + i;
      buf_ch(mag, (uint16_t)((k >= 0 && k < nd) ? digits[k] : '0'));
    }
  } else if (alt) {
    buf_ch(mag, '.');
  }
}

static void write_sci(Buf *mag, const char *digits, int nd, int dec, int64_t prec, int alt) {
  buf_ch(mag, (uint16_t)digits[0]);
  if (prec > 0) {
    buf_ch(mag, '.');
    for (int64_t i = 1; i <= prec; i++) buf_ch(mag, (uint16_t)(i < nd ? digits[i] : '0'));
  } else if (alt) {
    buf_ch(mag, '.');
  }
  int e = dec - 1;
  buf_ch(mag, 'e');
  buf_ch(mag, e < 0 ? '-' : '+');
  char tmp[16];
  snprintf(tmp, sizeof tmp, "%02d", e < 0 ? -e : e);
  buf_ascii(mag, tmp);
}

/* %f, %e and %g over the magnitude of a finite double. */
static void write_float_mag(Buf *mag, double av, uint16_t conv, const Spec *sp) {
  char digits[32];
  int nd = 1, dec = 1;
  digits[0] = '0';
  int zero = (av == 0.0);
  if (!zero) shortest_digits(av, digits, &nd, &dec);

  if (conv == 'f') {
    int64_t prec = sp->precision < 0 ? 6 : sp->precision;
    if (!zero) dec = apply_precision(dec, digits, nd, (int)(dec + prec));
    write_fixed(mag, digits, nd, dec, prec, sp->group, sp->alt);
  } else if (conv == 'e') {
    int64_t prec = sp->precision < 0 ? 6 : sp->precision;
    if (!zero) dec = apply_precision(dec, digits, nd, (int)(prec + 1));
    write_sci(mag, digits, nd, dec, prec, sp->alt);
  } else { /* 'g': significant digits, then decimal or scientific by magnitude */
    int64_t prec = sp->precision < 0 ? 6 : (sp->precision == 0 ? 1 : sp->precision);
    if (!zero) dec = apply_precision(dec, digits, nd, (int)prec);
    if (dec - 1 < -4 || dec - 1 >= prec) write_sci(mag, digits, nd, dec, prec - 1, sp->alt);
    else write_fixed(mag, digits, nd, dec, prec - dec, sp->group, sp->alt);
  }
}

static SflVal format_values(SflVal fmt, int64_t nargs, SflVal *args) {
  Buf out;
  buf_init(&out);
  int64_t next = 0, last = -1;

  for (int64_t i = 0; i < (int64_t)fmt->aux;) {
    if (fmt->u.s.chars[i] != '%') {
      buf_ch(&out, fmt->u.s.chars[i]);
      i++;
      continue;
    }
    Spec sp;
    i = parse_spec(fmt, i + 1, &sp);

    int upper;
    uint16_t conv = fold_conv(sp.conv, &upper);

    if (conv == 'n') {
      buf_ch(&out, '\n');
      continue;
    }

    Buf p;
    buf_init(&p);

    if (conv == '%') {
      buf_ch(&p, '%');
      justify(&out, &p, &sp);
      buf_free(&p);
      continue;
    }

    int64_t ai = sp.prev ? last : (sp.index > 0 ? sp.index - 1 : next++);
    if (ai < 0 || ai >= nargs) {
      buf_free(&p);
      format_error("MissingFormatArgumentException", fmt);
    }
    last = ai;
    SflVal arg = args[ai];
    FmtKind kind = fmt_kind(arg);

    switch (conv) {
      case 's': {
        /* Only a Formattable may take '#', and no SFL value is one. */
        if (sp.alt) {
          buf_free(&p);
          format_error("FormatFlagsConversionMismatchException", fmt);
        }
        char tmp[64];
        switch (kind) {
          case FA_NULL: buf_ascii(&p, "null"); break;
          case FA_LONG:
            snprintf(tmp, sizeof tmp, "%lld", (long long)arg->u.i);
            buf_ascii(&p, tmp);
            break;
          case FA_DOUBLE:
            sfl_write_double(tmp, sizeof tmp, arg->u.d);
            buf_ascii(&p, tmp);
            break;
          case FA_BOOL: buf_ascii(&p, arg->aux ? "true" : "false"); break;
          default: {
            SflVal s = arg->tag == SFL_STR ? arg : sfl_repr(arg);
            buf_units(&p, s->u.s.chars, (int64_t)s->aux);
            break;
          }
        }
        clip(&p, &sp);
        break;
      }

      case 'b': {
        int truth = kind == FA_NULL ? 0 : (kind == FA_BOOL ? (int)arg->aux : 1);
        buf_ascii(&p, truth ? "true" : "false");
        clip(&p, &sp);
        break;
      }

      case 'd': {
        if (kind == FA_NULL) {
          buf_ascii(&p, "null");
          break;
        }
        if (kind != FA_LONG) {
          buf_free(&p);
          format_error("IllegalFormatConversionException", fmt);
        }
        char ds[24];
        i64_magnitude(ds, arg->u.i);
        Buf mag;
        buf_init(&mag);
        if (sp.group) {
          Buf plain;
          buf_init(&plain);
          buf_ascii(&plain, ds);
          buf_grouped(&mag, plain.data, plain.len);
          buf_free(&plain);
        } else {
          buf_ascii(&mag, ds);
        }
        emit_number(&p, arg->u.i < 0, &mag, &sp, "");
        buf_free(&mag);
        break;
      }

      case 'x':
      case 'o': {
        if (kind == FA_NULL) {
          buf_ascii(&p, "null");
          break;
        }
        if (kind != FA_LONG) {
          buf_free(&p);
          format_error("IllegalFormatConversionException", fmt);
        }
        /* An unsigned rendering has no room for a sign, and Formatter only
           notices once it knows the argument is an integer. */
        if (sp.paren || sp.space || sp.plus) {
          buf_free(&p);
          format_error("FormatFlagsConversionMismatchException", fmt);
        }
        char ds[72];
        u64_radix(ds, (uint64_t)arg->u.i, conv == 'x' ? 16u : 8u, upper);
        Buf mag;
        buf_init(&mag);
        buf_ascii(&mag, ds);
        const char *prefix = "";
        if (sp.alt) prefix = conv == 'x' ? (upper ? "0X" : "0x") : "0";
        emit_number(&p, 0, &mag, &sp, prefix);
        buf_free(&mag);
        break;
      }

      case 'e':
      case 'f':
      case 'g': {
        if (kind == FA_NULL) {
          buf_ascii(&p, "null");
          clip(&p, &sp);
          break;
        }
        if (kind != FA_DOUBLE) {
          buf_free(&p);
          format_error("IllegalFormatConversionException", fmt);
        }
        double v = arg->u.d;
        if (isnan(v)) { /* NaN carries no sign and takes no zero padding */
          buf_ascii(&p, "NaN");
          break;
        }
        int neg = signbit(v) != 0;
        if (isinf(v)) {
          Spec plain = sp;
          plain.zero = 0;
          Buf mag;
          buf_init(&mag);
          buf_ascii(&mag, "Infinity");
          emit_number(&p, neg, &mag, &plain, "");
          buf_free(&mag);
          break;
        }
        Buf mag;
        buf_init(&mag);
        write_float_mag(&mag, fabs(v), conv, &sp);
        emit_number(&p, neg, &mag, &sp, "");
        buf_free(&mag);
        break;
      }

      case 'c':
        /* Formatter wants a Character or an Integer; an SFL int boxes to Long,
           so every non-null argument is a conversion error here. */
        if (kind == FA_NULL) {
          buf_ascii(&p, "null");
          clip(&p, &sp);
          break;
        }
        buf_free(&p);
        format_error("IllegalFormatConversionException", fmt);

      default:
        buf_free(&p);
        format_error("UnknownFormatConversionException", fmt);
    }

    if (upper) {
      Buf u;
      buf_init(&u);
      upcase_units(&u, p.data, p.len);
      justify(&out, &u, &sp);
      buf_free(&u);
    } else {
      justify(&out, &p, &sp);
    }
    buf_free(&p);
  }
  return buf_take(&out);
}

SflVal sfl_p_format(int64_t argc, SflVal *argv) {
  SflVal fmt = sfl_arg_str(argv, 0, "format");
  validate(fmt);
  return format_values(fmt, argc - 1, argv + 1);
}

/* The UTF-8 byte count of the string as it leaves the process — the charset
   encoder's spelling, where an unpaired surrogate is one '?' byte. This is the
   Content-Length of what socketWrite would send, which is what the standard
   library's HTTP client needs and string length in code points cannot say. */
SflVal sfl_p_utf8Length(int64_t argc, SflVal *argv) {
  return sfl_int(sfl_utf8_java_len(sfl_arg_str(argv, 0, "utf8Length")));
}
