/*
 * JSON, and the digests that live beside it.
 *
 * The parser is a transcription of the interpreter's JsonParser, offsets and all.
 * Its messages name the offset a script would have seen under the interpreter, and
 * that offset counts UTF-16 code units, because that is what indexes the
 * interpreter's strings; anything else would report a different position the
 * moment the document contains a non-ASCII character.
 *
 * The digests are written out here rather than pulled from a library so a compiled
 * binary needs nothing but libc. They hash the UTF-8 encoding of the string, which
 * is exactly what MessageDigest is handed on the interpreter's side.
 */
#include "sfl.h"

#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------------- */
/* The JSON reader                                                            */
/* ------------------------------------------------------------------------- */

typedef struct {
  SflVal src;        /* the text; its code units are read back through this, never
                        cached, so an allocation mid-parse cannot leave a dangler */
  int64_t i;
  int64_t n;
  uint16_t *scratch; /* where a string literal is decoded, sized once per parse */
} Jp;

static uint16_t jp_at(Jp *p, int64_t i) { return p->src->u.s.chars[i]; }

/* One UTF-16 code unit per entry, so a message quoting a character reads exactly
   as the interpreter's does — an unpaired surrogate included, which survives the
   round trip through the runtime's WTF-8-tolerant conversions. */
static void units_utf8(Jp *p, int64_t at, int64_t count, char *out) {
  int64_t k = 0;
  for (int64_t j = 0; j < count; j++) {
    uint32_t c = jp_at(p, at + j);
    if (c < 0x80) out[k++] = (char)c;
    else if (c < 0x800) {
      out[k++] = (char)(0xC0 | (c >> 6));
      out[k++] = (char)(0x80 | (c & 0x3F));
    } else {
      out[k++] = (char)(0xE0 | (c >> 12));
      out[k++] = (char)(0x80 | ((c >> 6) & 0x3F));
      out[k++] = (char)(0x80 | (c & 0x3F));
    }
  }
  out[k] = '\0';
}

static void jp_fail(Jp *p, const char *fmt, ...)
    __attribute__((noreturn, format(printf, 2, 3)));

static void jp_fail(Jp *p, const char *fmt, ...) {
  char detail[256];
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(detail, sizeof detail, fmt, ap);
  va_end(ap);
  /* Raising longjmps out, so let go of the scratch buffer while we still can. */
  sfl_raw_free(p->scratch);
  p->scratch = NULL;
  sfl_raise("invalid JSON at offset %lld: %s", (long long)p->i, detail);
}

static void jp_ws(Jp *p) {
  while (p->i < p->n) {
    uint16_t c = jp_at(p, p->i);
    if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
    p->i++;
  }
}

static SflVal jp_value(Jp *p);

static SflVal jp_lit(Jp *p, const char *word, SflVal v) {
  int64_t len = (int64_t)strlen(word);
  if (p->i + len <= p->n) {
    int64_t k = 0;
    while (k < len && jp_at(p, p->i + k) == (uint16_t)(unsigned char)word[k]) k++;
    if (k == len) {
      p->i += len;
      return v;
    }
  }
  jp_fail(p, "expected '%s'", word);
}

/* Integer.parseInt(hex, 16) over four code units, sign and all — the interpreter
   reads the escape with exactly that, so "\u+041" is an 'A' there and here. Four
   digits cannot overflow, and the caller truncates to a char as toChar does. */
static int parse_hex4(Jp *p, int64_t at, int32_t *out) {
  int64_t k = 0;
  int neg = 0;
  uint16_t first = jp_at(p, at);
  if (first == '+' || first == '-') {
    neg = first == '-';
    k = 1;
  }
  int32_t acc = 0;
  for (; k < 4; k++) {
    uint16_t c = jp_at(p, at + k);
    int d;
    if (c >= '0' && c <= '9') d = c - '0';
    else if (c >= 'a' && c <= 'f') d = c - 'a' + 10;
    else if (c >= 'A' && c <= 'F') d = c - 'A' + 10;
    else return 0;
    acc = acc * 16 + d;
  }
  *out = neg ? -acc : acc;
  return 1;
}

static SflVal jp_str(Jp *p) {
  if (p->scratch == NULL)
    p->scratch = (uint16_t *)sfl_raw_alloc((size_t)(p->n ? p->n : 1) * 2);
  uint16_t *out = p->scratch; /* raw memory, so no collection can move or free it */
  int64_t k = 0;

  p->i++;
  for (;;) {
    if (p->i >= p->n) jp_fail(p, "unterminated string");
    uint16_t c = jp_at(p, p->i);
    if (c == '"') {
      p->i++;
      break;
    }
    if (c != '\\') {
      out[k++] = c;
      p->i++;
      continue;
    }
    p->i++;
    if (p->i >= p->n) jp_fail(p, "unterminated escape");
    uint16_t e = jp_at(p, p->i);
    p->i++;
    switch (e) {
      case '"': out[k++] = '"'; break;
      case '\\': out[k++] = '\\'; break;
      case '/': out[k++] = '/'; break;
      case 'b': out[k++] = '\b'; break;
      case 'f': out[k++] = '\f'; break;
      case 'n': out[k++] = '\n'; break;
      case 'r': out[k++] = '\r'; break;
      case 't': out[k++] = '\t'; break;
      case 'u': {
        if (p->i + 4 > p->n) jp_fail(p, "incomplete \\u escape");
        int32_t cp;
        if (!parse_hex4(p, p->i, &cp)) {
          char shown[16];
          units_utf8(p, p->i, 4, shown);
          jp_fail(p, "bad \\u escape '%s'", shown);
        }
        /* One code unit per escape: a surrogate pair arrives as two escapes and
           lands in the string as the pair the interpreter would have built. */
        out[k++] = (uint16_t)cp;
        p->i += 4;
        break;
      }
      default: {
        char shown[16];
        units_utf8(p, p->i - 1, 1, shown);
        jp_fail(p, "bad escape '\\%s'", shown);
      }
    }
  }
  return sfl_str_utf16(out, k);
}

/* Long.parseLong. Failing means the literal does not fit, which is the
   interpreter's cue to keep the number as a float rather than to reject it. */
static int parse_i64(const char *t, int64_t *out) {
  int neg = t[0] == '-';
  size_t k = neg ? 1 : 0;
  if (t[k] == '\0') return 0;
  int64_t limit = neg ? INT64_MIN : -INT64_MAX;
  int64_t multmin = limit / 10;
  int64_t acc = 0;
  for (; t[k]; k++) {
    int d = t[k] - '0';
    if (d < 0 || d > 9) return 0;
    if (acc < multmin) return 0;
    acc *= 10;
    if (acc < limit + d) return 0;
    acc -= d;
  }
  *out = neg ? acc : -acc;
  return 1;
}

/* Double.parseDouble, to the extent the token grammar can reach it: the whole
   text must be consumed, so "1e" is rejected rather than read as 1. */
static int parse_f64(const char *t, double *out) {
  char *end;
  *out = strtod(t, &end);
  return end != t && *end == '\0';
}

static int jp_digit(uint16_t c) { return c >= '0' && c <= '9'; }

static SflVal jp_num(Jp *p) {
  int64_t start = p->i;
  if (p->i < p->n && jp_at(p, p->i) == '-') p->i++;
  while (p->i < p->n && jp_digit(jp_at(p, p->i))) p->i++;

  int is_float = 0;
  if (p->i < p->n && jp_at(p, p->i) == '.') {
    is_float = 1;
    p->i++;
    while (p->i < p->n && jp_digit(jp_at(p, p->i))) p->i++;
  }
  if (p->i < p->n && (jp_at(p, p->i) == 'e' || jp_at(p, p->i) == 'E')) {
    is_float = 1;
    p->i++;
    if (p->i < p->n && (jp_at(p, p->i) == '+' || jp_at(p, p->i) == '-')) p->i++;
    while (p->i < p->n && jp_digit(jp_at(p, p->i))) p->i++;
  }

  int64_t len = p->i - start;
  if (len == 0 || (len == 1 && jp_at(p, start) == '-')) jp_fail(p, "malformed number");

  /* Every unit of the token is ASCII by construction, so narrowing is exact. */
  char small[64];
  char *text = len < (int64_t)sizeof small ? small : (char *)sfl_raw_alloc((size_t)len + 1);
  for (int64_t k = 0; k < len; k++) text[k] = (char)jp_at(p, start + k);
  text[len] = '\0';

  SflVal r = NULL;
  int64_t iv = 0;
  double d = 0;
  int ok = 1;
  if (!is_float && parse_i64(text, &iv)) r = sfl_int(iv);
  else if ((ok = parse_f64(text, &d)) != 0) r = sfl_float(d);
  if (text != small) sfl_raw_free(text);
  if (!ok) jp_fail(p, "malformed number");
  return r;
}

static SflVal jp_arr(Jp *p) {
  p->i++;
  SflVal a = sfl_arr_new(4); /* a local, so the collector sees it and its elements */
  jp_ws(p);
  if (p->i < p->n && jp_at(p, p->i) == ']') {
    p->i++;
    return a;
  }
  int go = 1;
  while (go) {
    SflVal v = jp_value(p);
    sfl_arr_push(a, v);
    jp_ws(p);
    if (p->i < p->n && jp_at(p, p->i) == ',') p->i++;
    else go = 0;
  }
  if (p->i >= p->n || jp_at(p, p->i) != ']') jp_fail(p, "expected ']'");
  p->i++;
  return a;
}

static SflVal jp_obj(Jp *p) {
  p->i++;
  SflVal o = sfl_obj_new();
  jp_ws(p);
  if (p->i < p->n && jp_at(p, p->i) == '}') {
    p->i++;
    return o;
  }
  int go = 1;
  while (go) {
    jp_ws(p);
    if (p->i >= p->n || jp_at(p, p->i) != '"') jp_fail(p, "expected a string key");
    /* The key has to outlive parsing the value, which allocates. */
    SflVal k = jp_str(p);
    jp_ws(p);
    if (p->i >= p->n || jp_at(p, p->i) != ':') jp_fail(p, "expected ':'");
    p->i++;
    SflVal v = jp_value(p);
    sfl_obj_put(o, k, v); /* a repeated key overwrites in place, keeping its position */
    jp_ws(p);
    if (p->i < p->n && jp_at(p, p->i) == ',') p->i++;
    else go = 0;
  }
  if (p->i >= p->n || jp_at(p, p->i) != '}') jp_fail(p, "expected '}'");
  p->i++;
  return o;
}

static SflVal jp_value(Jp *p) {
  jp_ws(p);
  if (p->i >= p->n) jp_fail(p, "unexpected end of input");
  uint16_t c = jp_at(p, p->i);
  switch (c) {
    case '{': return jp_obj(p);
    case '[': return jp_arr(p);
    case '"': return jp_str(p);
    case 't': return jp_lit(p, "true", sfl_true);
    case 'f': return jp_lit(p, "false", sfl_false);
    case 'n': return jp_lit(p, "null", sfl_null);
    default: {
      if (c == '-' || jp_digit(c)) return jp_num(p);
      char shown[16];
      units_utf8(p, p->i, 1, shown);
      jp_fail(p, "unexpected character '%s'", shown);
    }
  }
}

SflVal sfl_p_jsonParse(int64_t argc, SflVal *argv) {
  SflVal text = sfl_arg_str(argv, 0, "jsonParse");
  Jp p;
  p.src = text;
  p.i = 0;
  p.n = text->aux;
  p.scratch = NULL;

  SflVal v = jp_value(&p);
  jp_ws(&p);
  if (p.i < p.n) {
    sfl_raw_free(p.scratch);
    sfl_raise("trailing content in JSON at offset %lld", (long long)p.i);
  }
  sfl_raw_free(p.scratch);
  return v;
}

/* ------------------------------------------------------------------------- */
/* The JSON writer                                                            */
/* ------------------------------------------------------------------------- */

/*
 * repr and pretty already emit JSON for everything JSON can carry, so stringify is
 * a choice between them. Values with no JSON form — functions, iterators, handles —
 * render as their repr placeholder rather than raising, which is what the
 * interpreter does and what keeps jsonStringify usable for debug output.
 */
/*
 * Refuses a value JSON cannot represent.
 *
 * Rendering a function produced `<fn lambda/0>`, which is not JSON and which this
 * module's own parser rejects — so the output looked like a result and was not one.
 * The path is reported because the offending value is usually nested.
 */
static void json_reject(SflVal v, char *path, size_t cap, size_t at) {
  switch (v->tag) {
    case SFL_ARR:
      for (uint32_t i = 0; i < v->aux; i++) {
        int n = snprintf(path + at, cap - at, "[%u]", i);
        json_reject(v->u.a.items[i], path, cap, n > 0 ? at + (size_t)n : at);
        path[at] = '\0';
      }
      return;
    case SFL_OBJ:
      for (uint32_t i = 0; i < v->aux; i++) {
        char *key = sfl_str_dup_utf8(v->u.o.keys[i]);
        int n = snprintf(path + at, cap - at, ".%s", key);
        sfl_raw_free(key);
        json_reject(v->u.o.vals[i], path, cap, n > 0 ? at + (size_t)n : at);
        path[at] = '\0';
      }
      return;
    case SFL_TUPLE: {
      /* Rejected like a function — a tuple deliberately has no JSON form, or a
         round trip would silently hand back an array — but the hint can offer a
         fix (toArray) that the function case cannot. */
      char msg[160], hint[320];
      snprintf(msg, sizeof msg, "jsonStringify: cannot convert %s to JSON",
               sfl_type_name(v));
      if (at == 0)
        snprintf(hint, sizeof hint, "a tuple has no JSON form; convert it with toArray(t)");
      else
        snprintf(hint, sizeof hint,
                 "the tuple at %s has no JSON form; convert it with toArray(t)", path);
      sfl_raise_hint(msg, hint);
    }
    case SFL_FUN:
    case SFL_NATIVE:
    case SFL_ITER:
    case SFL_HANDLE: {
      char msg[160], hint[320];
      snprintf(msg, sizeof msg, "jsonStringify: cannot convert %s to JSON",
               sfl_type_name(v));
      if (at == 0) snprintf(hint, sizeof hint, "this value has no JSON form; replace it with data");
      else snprintf(hint, sizeof hint,
                    "the value at %s has no JSON form; remove it or replace it with data", path);
      sfl_raise_hint(msg, hint);
    }
    default:
      return;
  }
}

SflVal sfl_p_jsonStringify(int64_t argc, SflVal *argv) {
  char path[512];
  path[0] = '\0';
  json_reject(argv[0], path, sizeof path, 0);
  if (sfl_truthy(sfl_opt(argc, argv, 1))) return sfl_pretty(argv[0], 2);
  return sfl_repr(argv[0]);
}

/* ------------------------------------------------------------------------- */
/* Digests                                                                    */
/* ------------------------------------------------------------------------- */

static uint32_t rotl32(uint32_t v, int n) { return (v << n) | (v >> (32 - n)); }
static uint32_t rotr32(uint32_t v, int n) { return (v >> n) | (v << (32 - n)); }

/*
 * The trailing 0x80, the zero fill and the 64-bit length, for whichever of the one
 * or two closing blocks the message needs. MD5 writes that length little-endian,
 * the SHA family big-endian; nothing else about the padding differs.
 */
static size_t pad_tail(const uint8_t *rest, size_t rem, uint64_t bits, int little,
                       uint8_t tail[128]) {
  memcpy(tail, rest, rem);
  tail[rem++] = 0x80;
  size_t total = rem <= 56 ? 64 : 128;
  memset(tail + rem, 0, total - rem);
  for (int k = 0; k < 8; k++) {
    uint8_t byte = (uint8_t)(bits >> (8 * k));
    tail[total - (little ? 8 - k : k + 1)] = byte;
  }
  return total;
}

static const uint32_t MD5_K[64] = {
    0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a,
    0xa8304613, 0xfd469501, 0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be,
    0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821, 0xf61e2562, 0xc040b340,
    0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
    0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8,
    0x676f02d9, 0x8d2a4c8a, 0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c,
    0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70, 0x289b7ec6, 0xeaa127fa,
    0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
    0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92,
    0xffeff47d, 0x85845dd1, 0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1,
    0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391};

static const uint8_t MD5_S[64] = {7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7,
                                  12, 17, 22, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9,
                                  14, 20, 5, 9, 14, 20, 4, 11, 16, 23, 4, 11, 16,
                                  23, 4, 11, 16, 23, 4, 11, 16, 23, 6, 10, 15, 21,
                                  6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21};

static void md5_block(uint32_t st[4], const uint8_t *b) {
  uint32_t m[16];
  for (int i = 0; i < 16; i++)
    m[i] = (uint32_t)b[i * 4] | ((uint32_t)b[i * 4 + 1] << 8) |
           ((uint32_t)b[i * 4 + 2] << 16) | ((uint32_t)b[i * 4 + 3] << 24);

  uint32_t a = st[0], bb = st[1], c = st[2], d = st[3];
  for (int i = 0; i < 64; i++) {
    uint32_t f;
    int g;
    if (i < 16) { f = (bb & c) | (~bb & d); g = i; }
    else if (i < 32) { f = (d & bb) | (~d & c); g = (5 * i + 1) & 15; }
    else if (i < 48) { f = bb ^ c ^ d; g = (3 * i + 5) & 15; }
    else { f = c ^ (bb | ~d); g = (7 * i) & 15; }
    f += a + MD5_K[i] + m[g];
    a = d;
    d = c;
    c = bb;
    bb += rotl32(f, MD5_S[i]);
  }
  st[0] += a;
  st[1] += bb;
  st[2] += c;
  st[3] += d;
}

void sfl_md5_digest(const uint8_t *msg, uint64_t len, uint8_t out[16]) {
  uint32_t st[4] = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
  uint64_t i = 0;
  for (; i + 64 <= len; i += 64) md5_block(st, msg + i);
  uint8_t tail[128];
  size_t total = pad_tail(msg + i, (size_t)(len - i), len * 8, 1, tail);
  for (size_t k = 0; k < total; k += 64) md5_block(st, tail + k);
  for (int k = 0; k < 4; k++)
    for (int j = 0; j < 4; j++) out[k * 4 + j] = (uint8_t)(st[k] >> (8 * j));
}

static void sha1_block(uint32_t st[5], const uint8_t *b) {
  uint32_t w[80];
  for (int i = 0; i < 16; i++)
    w[i] = ((uint32_t)b[i * 4] << 24) | ((uint32_t)b[i * 4 + 1] << 16) |
           ((uint32_t)b[i * 4 + 2] << 8) | (uint32_t)b[i * 4 + 3];
  for (int i = 16; i < 80; i++)
    w[i] = rotl32(w[i - 3] ^ w[i - 8] ^ w[i - 14] ^ w[i - 16], 1);

  uint32_t a = st[0], bb = st[1], c = st[2], d = st[3], e = st[4];
  for (int i = 0; i < 80; i++) {
    uint32_t f, k;
    if (i < 20) { f = (bb & c) | (~bb & d); k = 0x5a827999; }
    else if (i < 40) { f = bb ^ c ^ d; k = 0x6ed9eba1; }
    else if (i < 60) { f = (bb & c) | (bb & d) | (c & d); k = 0x8f1bbcdc; }
    else { f = bb ^ c ^ d; k = 0xca62c1d6; }
    uint32_t t = rotl32(a, 5) + f + e + k + w[i];
    e = d;
    d = c;
    c = rotl32(bb, 30);
    bb = a;
    a = t;
  }
  st[0] += a;
  st[1] += bb;
  st[2] += c;
  st[3] += d;
  st[4] += e;
}

void sfl_sha1_digest(const uint8_t *msg, uint64_t len, uint8_t out[20]) {
  uint32_t st[5] = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0};
  uint64_t i = 0;
  for (; i + 64 <= len; i += 64) sha1_block(st, msg + i);
  uint8_t tail[128];
  size_t total = pad_tail(msg + i, (size_t)(len - i), len * 8, 0, tail);
  for (size_t k = 0; k < total; k += 64) sha1_block(st, tail + k);
  for (int k = 0; k < 5; k++)
    for (int j = 0; j < 4; j++) out[k * 4 + j] = (uint8_t)(st[k] >> (24 - 8 * j));
}

static const uint32_t SHA256_K[64] = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
    0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
    0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
    0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2};

static void sha256_block(uint32_t st[8], const uint8_t *b) {
  uint32_t w[64];
  for (int i = 0; i < 16; i++)
    w[i] = ((uint32_t)b[i * 4] << 24) | ((uint32_t)b[i * 4 + 1] << 16) |
           ((uint32_t)b[i * 4 + 2] << 8) | (uint32_t)b[i * 4 + 3];
  for (int i = 16; i < 64; i++) {
    uint32_t s0 = rotr32(w[i - 15], 7) ^ rotr32(w[i - 15], 18) ^ (w[i - 15] >> 3);
    uint32_t s1 = rotr32(w[i - 2], 17) ^ rotr32(w[i - 2], 19) ^ (w[i - 2] >> 10);
    w[i] = w[i - 16] + s0 + w[i - 7] + s1;
  }

  uint32_t a = st[0], bb = st[1], c = st[2], d = st[3];
  uint32_t e = st[4], f = st[5], g = st[6], h = st[7];
  for (int i = 0; i < 64; i++) {
    uint32_t s1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
    uint32_t ch = (e & f) ^ (~e & g);
    uint32_t t1 = h + s1 + ch + SHA256_K[i] + w[i];
    uint32_t s0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
    uint32_t maj = (a & bb) ^ (a & c) ^ (bb & c);
    uint32_t t2 = s0 + maj;
    h = g;
    g = f;
    f = e;
    e = d + t1;
    d = c;
    c = bb;
    bb = a;
    a = t1 + t2;
  }
  st[0] += a;
  st[1] += bb;
  st[2] += c;
  st[3] += d;
  st[4] += e;
  st[5] += f;
  st[6] += g;
  st[7] += h;
}

void sfl_sha256_digest(const uint8_t *msg, uint64_t len, uint8_t out[32]) {
  uint32_t st[8] = {0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
                    0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19};
  uint64_t i = 0;
  for (; i + 64 <= len; i += 64) sha256_block(st, msg + i);
  uint8_t tail[128];
  size_t total = pad_tail(msg + i, (size_t)(len - i), len * 8, 0, tail);
  for (size_t k = 0; k < total; k += 64) sha256_block(st, tail + k);
  for (int k = 0; k < 8; k++)
    for (int j = 0; j < 4; j++) out[k * 4 + j] = (uint8_t)(st[k] >> (24 - 8 * j));
}

/* The bytes MessageDigest would have been given: the string's UTF-8 encoding,
   with the charset encoder's '?' where an unpaired surrogate stood. */
static uint8_t *utf8_bytes(SflVal s, int64_t *len) {
  int64_t n = sfl_utf8_java_len(s);
  uint8_t *b = (uint8_t *)sfl_raw_alloc((size_t)n);
  sfl_utf8_java_write(s, (char *)b);
  *len = n;
  return b;
}

static SflVal hex_string(const uint8_t *bytes, int n) {
  static const char digits[] = "0123456789abcdef";
  char out[65];
  for (int i = 0; i < n; i++) {
    out[i * 2] = digits[bytes[i] >> 4];
    out[i * 2 + 1] = digits[bytes[i] & 0x0f];
  }
  return sfl_str_utf8(out, n * 2);
}

SflVal sfl_p_md5(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "md5");
  int64_t n;
  uint8_t *b = utf8_bytes(s, &n);
  uint8_t out[16];
  sfl_md5_digest(b, (uint64_t)n, out);
  sfl_raw_free(b);
  return hex_string(out, 16);
}

SflVal sfl_p_sha1(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "sha1");
  int64_t n;
  uint8_t *b = utf8_bytes(s, &n);
  uint8_t out[20];
  sfl_sha1_digest(b, (uint64_t)n, out);
  sfl_raw_free(b);
  return hex_string(out, 20);
}

SflVal sfl_p_sha256(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "sha256");
  int64_t n;
  uint8_t *b = utf8_bytes(s, &n);
  uint8_t out[32];
  sfl_sha256_digest(b, (uint64_t)n, out);
  sfl_raw_free(b);
  return hex_string(out, 32);
}
