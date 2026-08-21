/*
 * The value model: constructors, the operators the language defines over values,
 * and how values render.
 *
 * Every decision here is dictated by the interpreter, because compiled output has
 * to be indistinguishable from interpreted output. Strings are UTF-16 code units
 * rather than UTF-8 bytes so that length() and s[i] count what Java counts;
 * objects keep insertion order; ints and floats compare across the divide so
 * 1 == 1.0; and floats print through Java's Double.toString rules.
 */
#include "sfl.h"

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ------------------------------------------------------------------------- */
/* UTF-16 <-> UTF-8                                                           */
/* ------------------------------------------------------------------------- */

/* Number of UTF-16 code units the UTF-8 in [b, b+n) decodes to. */
static int64_t utf16_len_of_utf8(const char *b, int64_t n) {
  int64_t units = 0;
  for (int64_t i = 0; i < n;) {
    unsigned char c = (unsigned char)b[i];
    if (c < 0x80) { i += 1; units += 1; }
    else if ((c & 0xE0) == 0xC0) { i += 2; units += 1; }
    else if ((c & 0xF0) == 0xE0) { i += 3; units += 1; }
    else if ((c & 0xF8) == 0xF0) { i += 4; units += 2; } /* surrogate pair */
    else { i += 1; units += 1; }                         /* stray byte */
  }
  return units;
}

static void utf8_to_utf16(const char *b, int64_t n, uint16_t *out) {
  int64_t k = 0;
  for (int64_t i = 0; i < n;) {
    unsigned char c = (unsigned char)b[i];
    uint32_t cp;
    if (c < 0x80) { cp = c; i += 1; }
    else if ((c & 0xE0) == 0xC0 && i + 1 < n) {
      cp = ((uint32_t)(c & 0x1F) << 6) | ((unsigned char)b[i + 1] & 0x3F);
      i += 2;
    } else if ((c & 0xF0) == 0xE0 && i + 2 < n) {
      cp = ((uint32_t)(c & 0x0F) << 12) | (((unsigned char)b[i + 1] & 0x3F) << 6) |
           ((unsigned char)b[i + 2] & 0x3F);
      i += 3;
    } else if ((c & 0xF8) == 0xF0 && i + 3 < n) {
      cp = ((uint32_t)(c & 0x07) << 18) | (((unsigned char)b[i + 1] & 0x3F) << 12) |
           (((unsigned char)b[i + 2] & 0x3F) << 6) | ((unsigned char)b[i + 3] & 0x3F);
      i += 4;
    } else { cp = 0xFFFD; i += 1; }

    if (cp >= 0x10000) {
      cp -= 0x10000;
      out[k++] = (uint16_t)(0xD800 + (cp >> 10));
      out[k++] = (uint16_t)(0xDC00 + (cp & 0x3FF));
    } else {
      out[k++] = (uint16_t)cp;
    }
  }
}

int64_t sfl_utf8_len(SflVal s) {
  const uint16_t *u = s->u.s.chars;
  int64_t n = s->aux, bytes = 0;
  for (int64_t i = 0; i < n; i++) {
    uint32_t c = u[i];
    if (c >= 0xD800 && c < 0xDC00 && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] < 0xE000) {
      bytes += 4;
      i++;
    } else if (c < 0x80) bytes += 1;
    else if (c < 0x800) bytes += 2;
    else bytes += 3;
  }
  return bytes;
}

/* Writes UTF-8 for `s` into `dst`, which must hold sfl_utf8_len(s) bytes. */
void sfl_utf8_write(SflVal s, char *dst) {
  const uint16_t *u = s->u.s.chars;
  int64_t n = s->aux, k = 0;
  for (int64_t i = 0; i < n; i++) {
    uint32_t cp = u[i];
    if (cp >= 0xD800 && cp < 0xDC00 && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] < 0xE000) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
      i++;
    }
    if (cp < 0x80) dst[k++] = (char)cp;
    else if (cp < 0x800) {
      dst[k++] = (char)(0xC0 | (cp >> 6));
      dst[k++] = (char)(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      dst[k++] = (char)(0xE0 | (cp >> 12));
      dst[k++] = (char)(0x80 | ((cp >> 6) & 0x3F));
      dst[k++] = (char)(0x80 | (cp & 0x3F));
    } else {
      dst[k++] = (char)(0xF0 | (cp >> 18));
      dst[k++] = (char)(0x80 | ((cp >> 12) & 0x3F));
      dst[k++] = (char)(0x80 | ((cp >> 6) & 0x3F));
      dst[k++] = (char)(0x80 | (cp & 0x3F));
    }
  }
}

/*
 * The same conversion as Java's charset encoder, which is what the interpreter's
 * getBytes(UTF_8) goes through: an unpaired surrogate becomes '?', where
 * sfl_utf8_write above keeps its raw three-byte spelling. The codecs and digests
 * must hash and encode what the interpreter would, so they use this pair.
 */
int64_t sfl_utf8_java_len(SflVal s) {
  const uint16_t *u = s->u.s.chars;
  int64_t n = s->aux, bytes = 0;
  for (int64_t i = 0; i < n; i++) {
    uint32_t c = u[i];
    if (c >= 0xD800 && c < 0xDC00 && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] < 0xE000) {
      bytes += 4;
      i++;
    } else if (c >= 0xD800 && c < 0xE000) bytes += 1; /* unpaired: '?' */
    else if (c < 0x80) bytes += 1;
    else if (c < 0x800) bytes += 2;
    else bytes += 3;
  }
  return bytes;
}

/* Writes into `dst`, which must hold sfl_utf8_java_len(s) bytes. */
void sfl_utf8_java_write(SflVal s, char *dst) {
  const uint16_t *u = s->u.s.chars;
  int64_t n = s->aux, k = 0;
  for (int64_t i = 0; i < n; i++) {
    uint32_t cp = u[i];
    if (cp >= 0xD800 && cp < 0xDC00 && i + 1 < n && u[i + 1] >= 0xDC00 && u[i + 1] < 0xE000) {
      cp = 0x10000 + ((cp - 0xD800) << 10) + (u[i + 1] - 0xDC00);
      i++;
    } else if (cp >= 0xD800 && cp < 0xE000) {
      cp = '?';
    }
    if (cp < 0x80) dst[k++] = (char)cp;
    else if (cp < 0x800) {
      dst[k++] = (char)(0xC0 | (cp >> 6));
      dst[k++] = (char)(0x80 | (cp & 0x3F));
    } else if (cp < 0x10000) {
      dst[k++] = (char)(0xE0 | (cp >> 12));
      dst[k++] = (char)(0x80 | ((cp >> 6) & 0x3F));
      dst[k++] = (char)(0x80 | (cp & 0x3F));
    } else {
      dst[k++] = (char)(0xF0 | (cp >> 18));
      dst[k++] = (char)(0x80 | ((cp >> 12) & 0x3F));
      dst[k++] = (char)(0x80 | ((cp >> 6) & 0x3F));
      dst[k++] = (char)(0x80 | (cp & 0x3F));
    }
  }
}

/* Caller frees with sfl_raw_free. */
char *sfl_str_dup_utf8(SflVal s) {
  int64_t n = sfl_utf8_len(s);
  char *out = (char *)sfl_raw_alloc((size_t)n + 1);
  sfl_utf8_write(s, out);
  out[n] = '\0';
  return out;
}

/* Caller frees with sfl_raw_free. For strings handed to the operating system —
   paths, environment entries, argv — where the interpreter's toCString has
   already turned an unpaired surrogate into '?'. Text that returns into the
   program as a value keeps the raw pair above, which round-trips. */
char *sfl_str_dup_utf8_java(SflVal s) {
  int64_t n = sfl_utf8_java_len(s);
  char *out = (char *)sfl_raw_alloc((size_t)n + 1);
  sfl_utf8_java_write(s, out);
  out[n] = '\0';
  return out;
}

/* ------------------------------------------------------------------------- */
/* Constructors                                                               */
/* ------------------------------------------------------------------------- */

/* The interpreter caches this range, so matching it keeps allocation behaviour
   comparable; it costs nothing and makes loop counters free. */
#define INT_CACHE_LO (-128)
#define INT_CACHE_HI 1024
static SflObj int_cache[INT_CACHE_HI - INT_CACHE_LO + 1];
static int int_cache_ready;

SflVal sfl_int(int64_t v) {
  if (v >= INT_CACHE_LO && v <= INT_CACHE_HI) {
    if (!int_cache_ready) {
      for (int64_t i = INT_CACHE_LO; i <= INT_CACHE_HI; i++) {
        int_cache[i - INT_CACHE_LO].tag = SFL_INT;
        int_cache[i - INT_CACHE_LO].u.i = i;
      }
      int_cache_ready = 1;
    }
    return &int_cache[v - INT_CACHE_LO];
  }
  SflVal o = sfl_alloc(SFL_INT);
  o->u.i = v;
  return o;
}

SflVal sfl_float(double v) {
  SflVal o = sfl_alloc(SFL_FLOAT);
  o->u.d = v;
  return o;
}

SflVal sfl_bool(int v) { return v ? sfl_true : sfl_false; }

SflVal sfl_str_utf16(const uint16_t *units, int64_t count) {
  SflVal s = sfl_alloc(SFL_STR);
  s->u.s.chars = (uint16_t *)sfl_raw_alloc((size_t)(count ? count : 1) * 2);
  if (count) memcpy(s->u.s.chars, units, (size_t)count * 2);
  s->aux = (uint32_t)count;
  return s;
}

SflVal sfl_str_utf8(const char *bytes, int64_t nbytes) {
  if (nbytes < 0) nbytes = (int64_t)strlen(bytes);
  int64_t units = utf16_len_of_utf8(bytes, nbytes);
  SflVal s = sfl_alloc(SFL_STR);
  s->u.s.chars = (uint16_t *)sfl_raw_alloc((size_t)(units ? units : 1) * 2);
  utf8_to_utf16(bytes, nbytes, s->u.s.chars);
  s->aux = (uint32_t)units;
  return s;
}

SflVal sfl_str_empty(void) { return sfl_str_utf8("", 0); }

SflVal sfl_arr_new(int64_t capacity) {
  if (capacity < 4) capacity = 4;
  SflVal a = sfl_alloc(SFL_ARR);
  a->u.a.items = (SflVal *)sfl_raw_alloc((size_t)capacity * sizeof(SflVal));
  a->u.a.cap = (int32_t)capacity;
  a->aux = 0;
  return a;
}

SflVal sfl_arr_of(int64_t count, SflVal *items) {
  SflVal a = sfl_arr_new(count);
  for (int64_t i = 0; i < count; i++) a->u.a.items[i] = items[i];
  a->aux = (uint32_t)count;
  return a;
}

void sfl_arr_push(SflVal a, SflVal v) {
  if ((int32_t)a->aux == a->u.a.cap) {
    int32_t cap = a->u.a.cap * 2;
    a->u.a.items = (SflVal *)sfl_raw_realloc(a->u.a.items, (size_t)cap * sizeof(SflVal));
    a->u.a.cap = cap;
  }
  a->u.a.items[a->aux++] = v;
}

SflVal sfl_tuple_of(int64_t count, SflVal *items) {
  SflVal t = sfl_alloc(SFL_TUPLE);
  /* Only raw allocation between the alloc above and the copy below, so no
     collection can run while `t` still has aux 0 and an unfilled buffer. */
  t->u.t.items = (SflVal *)sfl_raw_alloc((size_t)(count ? count : 1) * sizeof(SflVal));
  for (int64_t i = 0; i < count; i++) t->u.t.items[i] = items[i];
  t->aux = (uint32_t)count;
  return t;
}

SflVal sfl_frame_new(int64_t nslots, SflVal parent) {
  SflVal f = sfl_alloc(SFL_FRAME);
  f->u.fr.slots = (SflVal *)sfl_raw_alloc((size_t)(nslots ? nslots : 1) * sizeof(SflVal));
  for (int64_t i = 0; i < nslots; i++) f->u.fr.slots[i] = NULL;
  f->aux = (uint32_t)nslots;
  f->u.fr.parent = parent;
  return f;
}

SflVal sfl_frame_up(SflVal frame, int64_t depth) {
  while (depth-- > 0) frame = frame->u.fr.parent;
  return frame;
}

SflVal sfl_fun_new(SflCode code, SflVal env, const SflProto *proto) {
  SflVal f = sfl_alloc(SFL_FUN);
  f->u.f.code = code;
  f->u.f.env = env;
  f->u.f.proto = proto;
  return f;
}

SflVal sfl_native_new(const SflNative *info) {
  SflVal f = sfl_alloc(SFL_NATIVE);
  f->u.n.info = info;
  return f;
}

SflVal sfl_handle_new(const char *kind, void *payload, SflVal label) {
  SflVal h = sfl_alloc(SFL_HANDLE);
  h->u.h.kind = kind;
  h->u.h.payload = payload;
  h->u.h.label = label;
  return h;
}

/* ------------------------------------------------------------------------- */
/* Objects: insertion-ordered, with a hash index once linear search stops paying */
/* ------------------------------------------------------------------------- */

static int32_t str_hash(SflVal s) {
  if (s->u.s.hashed) return s->u.s.hash;
  int32_t h = 0;
  for (uint32_t i = 0; i < s->aux; i++) h = h * 31 + (int32_t)s->u.s.chars[i];
  s->u.s.hash = h;
  s->u.s.hashed = 1;
  return h;
}

static int str_eq(SflVal a, SflVal b) {
  if (a == b) return 1;
  if (a->aux != b->aux) return 0;
  return memcmp(a->u.s.chars, b->u.s.chars, (size_t)a->aux * 2) == 0;
}

#define OBJ_INDEX_FROM 12

static void obj_reindex(SflVal o) {
  int32_t cap = 16;
  while (cap < (int32_t)o->aux * 2) cap *= 2;
  o->u.o.index = (int32_t *)sfl_raw_realloc(o->u.o.index, (size_t)cap * sizeof(int32_t));
  o->u.o.index_cap = cap;
  for (int32_t i = 0; i < cap; i++) o->u.o.index[i] = -1;
  for (uint32_t i = 0; i < o->aux; i++) {
    uint32_t h = (uint32_t)str_hash(o->u.o.keys[i]) & (uint32_t)(cap - 1);
    while (o->u.o.index[h] >= 0) h = (h + 1) & (uint32_t)(cap - 1);
    o->u.o.index[h] = (int32_t)i;
  }
}

static int32_t obj_find(SflVal o, SflVal key) {
  if (o->u.o.index == NULL) {
    for (uint32_t i = 0; i < o->aux; i++)
      if (str_eq(o->u.o.keys[i], key)) return (int32_t)i;
    return -1;
  }
  uint32_t mask = (uint32_t)(o->u.o.index_cap - 1);
  uint32_t h = (uint32_t)str_hash(key) & mask;
  while (o->u.o.index[h] >= 0) {
    int32_t slot = o->u.o.index[h];
    if (str_eq(o->u.o.keys[slot], key)) return slot;
    h = (h + 1) & mask;
  }
  return -1;
}

SflVal sfl_obj_new(void) {
  SflVal o = sfl_alloc(SFL_OBJ);
  o->u.o.cap = 8;
  o->u.o.keys = (SflVal *)sfl_raw_alloc(8 * sizeof(SflVal));
  o->u.o.vals = (SflVal *)sfl_raw_alloc(8 * sizeof(SflVal));
  return o;
}

void sfl_obj_put(SflVal o, SflVal key, SflVal v) {
  int32_t at = obj_find(o, key);
  if (at >= 0) {
    o->u.o.vals[at] = v;
    return;
  }
  if ((int32_t)o->aux == o->u.o.cap) {
    int32_t cap = o->u.o.cap * 2;
    o->u.o.keys = (SflVal *)sfl_raw_realloc(o->u.o.keys, (size_t)cap * sizeof(SflVal));
    o->u.o.vals = (SflVal *)sfl_raw_realloc(o->u.o.vals, (size_t)cap * sizeof(SflVal));
    o->u.o.cap = cap;
  }
  o->u.o.keys[o->aux] = key;
  o->u.o.vals[o->aux] = v;
  o->aux++;
  if (o->u.o.index != NULL) {
    if ((int32_t)o->aux * 2 > o->u.o.index_cap) obj_reindex(o);
    else {
      uint32_t mask = (uint32_t)(o->u.o.index_cap - 1);
      uint32_t h = (uint32_t)str_hash(key) & mask;
      while (o->u.o.index[h] >= 0) h = (h + 1) & mask;
      o->u.o.index[h] = (int32_t)o->aux - 1;
    }
  } else if (o->aux >= OBJ_INDEX_FROM) {
    obj_reindex(o);
  }
}

SflVal sfl_obj_get(SflVal o, SflVal key) {
  int32_t at = obj_find(o, key);
  return at < 0 ? NULL : o->u.o.vals[at];
}

int sfl_obj_remove(SflVal o, SflVal key) {
  int32_t at = obj_find(o, key);
  if (at < 0) return 0;
  for (uint32_t i = (uint32_t)at; i + 1 < o->aux; i++) {
    o->u.o.keys[i] = o->u.o.keys[i + 1];
    o->u.o.vals[i] = o->u.o.vals[i + 1];
  }
  o->aux--;
  if (o->u.o.index != NULL) obj_reindex(o);
  return 1;
}

int64_t sfl_obj_size(SflVal o) { return o->aux; }

/* ------------------------------------------------------------------------- */
/* Type, truthiness, boxing                                                   */
/* ------------------------------------------------------------------------- */

const char *sfl_type_name(SflVal v) {
  switch (v->tag) {
    case SFL_NULL: return "null";
    case SFL_BOOL: return "bool";
    case SFL_INT: return "int";
    case SFL_FLOAT: return "float";
    case SFL_STR: return "string";
    case SFL_ARR: return "array";
    case SFL_TUPLE: return "tuple";
    case SFL_OBJ: return "object";
    case SFL_FUN:
    case SFL_NATIVE: return "function";
    case SFL_ITER: return "iterator";
    case SFL_HANDLE: return v->u.h.kind;
    default: return "unknown";
  }
}

int sfl_truthy(SflVal v) {
  switch (v->tag) {
    case SFL_NULL: return 0;
    case SFL_BOOL: return (int)v->aux;
    case SFL_INT: return v->u.i != 0;
    case SFL_FLOAT: return v->u.d != 0.0 && !isnan(v->u.d);
    case SFL_STR: return v->aux != 0;
    case SFL_ARR: return v->aux != 0;
    case SFL_OBJ: return v->aux != 0;
    /* A tuple is always truthy — the parser has no 0/1-tuples, so VTuple never
       consults its length — which is why it must not share the container cases. */
    case SFL_TUPLE: return 1;
    default: return 1;
  }
}

int sfl_unbox_bool(SflVal v) { return sfl_truthy(v); }

int64_t sfl_unbox_int(SflVal v) {
  if (v->tag == SFL_INT) return v->u.i;
  if (v->tag == SFL_FLOAT) return (int64_t)v->u.d;
  sfl_raise("expected a number, got %s", sfl_type_name(v));
}

double sfl_unbox_float(SflVal v) {
  if (v->tag == SFL_FLOAT) return v->u.d;
  if (v->tag == SFL_INT) return (double)v->u.i;
  sfl_raise("expected a number, got %s", sfl_type_name(v));
}

/* ------------------------------------------------------------------------- */
/* Rendering                                                                  */
/* ------------------------------------------------------------------------- */

/*
 * Java's Double.toString: the shortest decimal that reads back as the same
 * double, positional when 1e-3 <= |v| < 1e7 and in E notation otherwise, always
 * with at least one digit after the point. The interpreter prints floats this
 * way, so anything else here would make compiled output differ on every float.
 */
void sfl_write_double(char *buf, size_t n, double v) {
  if (isnan(v)) { snprintf(buf, n, "NaN"); return; }
  if (isinf(v)) { snprintf(buf, n, v > 0 ? "Infinity" : "-Infinity"); return; }
  if (v == 0.0) { snprintf(buf, n, signbit(v) ? "-0.0" : "0.0"); return; }

  char sci[64];
  int prec = 0;
  for (; prec <= 16; prec++) {
    snprintf(sci, sizeof sci, "%.*e", prec, v);
    if (strtod(sci, NULL) == v) break;
  }
  if (prec > 16) prec = 16;

  char *epos = strchr(sci, 'e');
  int exp10 = atoi(epos + 1);
  double mag = fabs(v);

  if (mag >= 1e-3 && mag < 1e7) {
    int decimals = prec - exp10;
    if (decimals < 1) decimals = 1;
    if (decimals > 40) decimals = 40;
    snprintf(buf, n, "%.*f", decimals, v);
    char *dot = strchr(buf, '.');
    if (dot) {
      char *end = buf + strlen(buf) - 1;
      while (end > dot + 1 && *end == '0') *end-- = '\0';
    }
  } else {
    *epos = '\0';
    char mant[48];
    snprintf(mant, sizeof mant, "%s", sci);
    if (!strchr(mant, '.')) strncat(mant, ".0", sizeof(mant) - strlen(mant) - 1);
    snprintf(buf, n, "%sE%d", mant, exp10);
  }
}

/* A growable UTF-16 buffer, used to build every rendered string. */
typedef struct {
  uint16_t *data;
  int64_t len, cap;
} Buf;

static void buf_init(Buf *b) {
  b->cap = 64;
  b->len = 0;
  b->data = (uint16_t *)sfl_raw_alloc((size_t)b->cap * 2);
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

static void buf_ascii(Buf *b, const char *s) {
  int64_t n = (int64_t)strlen(s);
  buf_room(b, n);
  for (int64_t i = 0; i < n; i++) b->data[b->len++] = (uint16_t)(unsigned char)s[i];
}

static void buf_str(Buf *b, SflVal s) {
  buf_room(b, s->aux);
  memcpy(b->data + b->len, s->u.s.chars, (size_t)s->aux * 2);
  b->len += s->aux;
}

static SflVal buf_take(Buf *b) {
  SflVal s = sfl_str_utf16(b->data, b->len);
  sfl_raw_free(b->data);
  return s;
}

/* Exactly Values.quote: the six named escapes, \uXXXX below 0x20, else verbatim. */
static void buf_quoted(Buf *b, SflVal s) {
  buf_ch(b, '"');
  for (uint32_t i = 0; i < s->aux; i++) {
    uint16_t c = s->u.s.chars[i];
    switch (c) {
      case '"': buf_ascii(b, "\\\""); break;
      case '\\': buf_ascii(b, "\\\\"); break;
      case '\n': buf_ascii(b, "\\n"); break;
      case '\r': buf_ascii(b, "\\r"); break;
      case '\t': buf_ascii(b, "\\t"); break;
      case '\b': buf_ascii(b, "\\b"); break;
      case '\f': buf_ascii(b, "\\f"); break;
      default:
        if (c < 0x20) {
          char tmp[8];
          snprintf(tmp, sizeof tmp, "\\u%04x", c);
          buf_ascii(b, tmp);
        } else buf_ch(b, c);
    }
  }
  buf_ch(b, '"');
}

/*
 * How many containers a walk over a value may enter. A structure that contains
 * itself has no bottom, and every recursive walk here — rendering, equality,
 * ordering, JSON, deep copying — would otherwise run off the stack and take the
 * whole process with it. The limit sits far above any structure real data has and
 * far below what the stack can hold, so the only thing it ever catches is a cycle.
 * Values.NestLimit in the interpreter holds the same number.
 */
void sfl_check_nesting(int64_t depth) {
  if (depth >= SFL_NEST_LIMIT)
    sfl_raise_hint("structure is nested too deeply",
                   "a value may not nest more than 10000 deep");
}

static void write_value(Buf *b, SflVal v, int quoted, int64_t depth) {
  char tmp[64];
  switch (v->tag) {
    case SFL_NULL: buf_ascii(b, "null"); break;
    case SFL_BOOL: buf_ascii(b, v->aux ? "true" : "false"); break;
    case SFL_INT:
      snprintf(tmp, sizeof tmp, "%lld", (long long)v->u.i);
      buf_ascii(b, tmp);
      break;
    case SFL_FLOAT:
      sfl_write_double(tmp, sizeof tmp, v->u.d);
      buf_ascii(b, tmp);
      break;
    case SFL_STR:
      if (quoted) buf_quoted(b, v); else buf_str(b, v);
      break;
    case SFL_ARR:
      sfl_check_nesting(depth);
      buf_ch(b, '[');
      for (uint32_t i = 0; i < v->aux; i++) {
        if (i) buf_ascii(b, ", ");
        write_value(b, v->u.a.items[i], 1, depth + 1);
      }
      buf_ch(b, ']');
      break;
    case SFL_TUPLE:
      /* VTuple.write: parentheses, elements always quoted — the elements of a
         container render source-like even when the container prints bare. */
      sfl_check_nesting(depth);
      buf_ch(b, '(');
      for (uint32_t i = 0; i < v->aux; i++) {
        if (i) buf_ascii(b, ", ");
        write_value(b, v->u.t.items[i], 1, depth + 1);
      }
      buf_ch(b, ')');
      break;
    case SFL_OBJ:
      sfl_check_nesting(depth);
      buf_ch(b, '{');
      for (uint32_t i = 0; i < v->aux; i++) {
        if (i) buf_ascii(b, ", ");
        buf_quoted(b, v->u.o.keys[i]);
        buf_ascii(b, ": ");
        write_value(b, v->u.o.vals[i], 1, depth + 1);
      }
      buf_ch(b, '}');
      break;
    case SFL_FUN:
      buf_ascii(b, "<fn ");
      buf_ascii(b, v->u.f.proto->name);
      buf_ch(b, '/');
      snprintf(tmp, sizeof tmp, "%d", v->u.f.proto->nparams);
      buf_ascii(b, tmp);
      buf_ch(b, '>');
      break;
    case SFL_NATIVE:
      buf_ascii(b, "<builtin ");
      buf_ascii(b, v->u.n.info->name);
      buf_ch(b, '>');
      break;
    case SFL_ITER:
      buf_ascii(b, "<iterator over ");
      buf_ascii(b, v->u.it.src ? sfl_type_name(v->u.it.src) : "lazy");
      buf_ch(b, '>');
      break;
    case SFL_HANDLE:
      buf_ch(b, '<');
      buf_ascii(b, v->u.h.kind);
      if (v->u.h.label && v->u.h.label->aux) {
        buf_ch(b, ' ');
        buf_str(b, v->u.h.label);
      }
      buf_ch(b, '>');
      break;
    default:
      buf_ascii(b, "<value>");
  }
}

SflVal sfl_display(SflVal v) {
  if (v->tag == SFL_STR) return v;
  Buf b;
  buf_init(&b);
  write_value(&b, v, 0, 0);
  return buf_take(&b);
}

SflVal sfl_repr(SflVal v) {
  Buf b;
  buf_init(&b);
  write_value(&b, v, 1, 0);
  return buf_take(&b);
}

static void pretty_into(Buf *b, SflVal v, int64_t indent, int64_t depth) {
  if (v->tag == SFL_ARR && v->aux) {
    sfl_check_nesting(depth);
    buf_ascii(b, "[\n");
    for (uint32_t i = 0; i < v->aux; i++) {
      for (int64_t k = 0; k < (depth + 1) * indent; k++) buf_ch(b, ' ');
      pretty_into(b, v->u.a.items[i], indent, depth + 1);
      if (i + 1 < v->aux) buf_ch(b, ',');
      buf_ch(b, '\n');
    }
    for (int64_t k = 0; k < depth * indent; k++) buf_ch(b, ' ');
    buf_ch(b, ']');
  } else if (v->tag == SFL_OBJ && v->aux) {
    sfl_check_nesting(depth);
    buf_ascii(b, "{\n");
    for (uint32_t i = 0; i < v->aux; i++) {
      for (int64_t k = 0; k < (depth + 1) * indent; k++) buf_ch(b, ' ');
      buf_quoted(b, v->u.o.keys[i]);
      buf_ascii(b, ": ");
      pretty_into(b, v->u.o.vals[i], indent, depth + 1);
      if (i + 1 < v->aux) buf_ch(b, ',');
      buf_ch(b, '\n');
    }
    for (int64_t k = 0; k < depth * indent; k++) buf_ch(b, ' ');
    buf_ch(b, '}');
  } else {
    write_value(b, v, 1, depth);
  }
}

SflVal sfl_pretty(SflVal v, int64_t indent) {
  Buf b;
  buf_init(&b);
  pretty_into(&b, v, indent, 0);
  return buf_take(&b);
}

/* Err.show: repr, clipped to 48 characters with an ellipsis. */
static SflVal show(SflVal v) {
  SflVal r = sfl_repr(v);
  if (r->aux <= 48) return r;
  SflVal cut = sfl_str_utf16(r->u.s.chars, 45);
  Buf b;
  buf_init(&b);
  buf_str(&b, cut);
  buf_ascii(&b, "...");
  return buf_take(&b);
}

/* ------------------------------------------------------------------------- */
/* Equality and ordering                                                      */
/* ------------------------------------------------------------------------- */

static int equal_at(SflVal a, SflVal b, int64_t depth);

int sfl_equal(SflVal a, SflVal b) { return equal_at(a, b, 0); }

static int equal_at(SflVal a, SflVal b, int64_t depth) {
  /* Identity settles it for everything except a NaN, which IEEE says is equal to
     nothing at all — including the very same NaN, so `n == n` is false there just
     as `NAN() == NAN()` is. */
  if (a == b && !(a->tag == SFL_FLOAT && a->u.d != a->u.d)) return 1;
  switch (a->tag) {
    case SFL_INT:
      if (b->tag == SFL_INT) return a->u.i == b->u.i;
      if (b->tag == SFL_FLOAT) return (double)a->u.i == b->u.d;
      return 0;
    case SFL_FLOAT:
      if (b->tag == SFL_INT) return a->u.d == (double)b->u.i;
      if (b->tag == SFL_FLOAT) return a->u.d == b->u.d;
      return 0;
    case SFL_STR:
      return b->tag == SFL_STR && str_eq(a, b);
    case SFL_BOOL:
      return b->tag == SFL_BOOL && a->aux == b->aux;
    case SFL_NULL:
      return b->tag == SFL_NULL;
    case SFL_ARR: {
      if (b->tag != SFL_ARR || a->aux != b->aux) return 0;
      sfl_check_nesting(depth);
      for (uint32_t i = 0; i < a->aux; i++)
        if (!equal_at(a->u.a.items[i], b->u.a.items[i], depth + 1)) return 0;
      return 1;
    }
    case SFL_TUPLE: {
      /* Element-wise like arrays, but only against another tuple: (1, 2) and
         [1, 2] are different values, which is what makes a tuple pattern safe. */
      if (b->tag != SFL_TUPLE || a->aux != b->aux) return 0;
      sfl_check_nesting(depth);
      for (uint32_t i = 0; i < a->aux; i++)
        if (!equal_at(a->u.t.items[i], b->u.t.items[i], depth + 1)) return 0;
      return 1;
    }
    case SFL_OBJ: {
      /* Order-insensitive, exactly as the interpreter compares objects. */
      if (b->tag != SFL_OBJ || a->aux != b->aux) return 0;
      sfl_check_nesting(depth);
      for (uint32_t i = 0; i < a->aux; i++) {
        SflVal other = sfl_obj_get(b, a->u.o.keys[i]);
        if (other == NULL || !equal_at(a->u.o.vals[i], other, depth + 1)) return 0;
      }
      return 1;
    }
    default:
      return 0;
  }
}

static int32_t dcmp(double x, double y) {
  /* java.lang.Double.compare: -0.0 < 0.0 and NaN sorts above everything. */
  if (x < y) return -1;
  if (x > y) return 1;
  int64_t bx, by;
  memcpy(&bx, &x, 8);
  memcpy(&by, &y, 8);
  if (bx == by) return 0;
  return bx < by ? -1 : 1;
}

int32_t sfl_compare(SflVal a, SflVal b) {
  switch (a->tag) {
    case SFL_INT:
      if (b->tag == SFL_INT) return a->u.i < b->u.i ? -1 : (a->u.i > b->u.i ? 1 : 0);
      if (b->tag == SFL_FLOAT) return dcmp((double)a->u.i, b->u.d);
      break;
    case SFL_FLOAT:
      if (b->tag == SFL_INT) return dcmp(a->u.d, (double)b->u.i);
      if (b->tag == SFL_FLOAT) return dcmp(a->u.d, b->u.d);
      break;
    case SFL_STR: {
      if (b->tag != SFL_STR) break;
      /* String.compareTo: first differing code unit, else the length difference. */
      uint32_t n = a->aux < b->aux ? a->aux : b->aux;
      for (uint32_t i = 0; i < n; i++) {
        int32_t d = (int32_t)a->u.s.chars[i] - (int32_t)b->u.s.chars[i];
        if (d) return d;
      }
      return (int32_t)a->aux - (int32_t)b->aux;
    }
    case SFL_BOOL:
      if (b->tag != SFL_BOOL) break;
      return (int32_t)a->aux - (int32_t)b->aux;
    case SFL_TUPLE: {
      if (b->tag != SFL_TUPLE) break;
      /* Lexicographic, length last — so ("b", 1) sorts after ("a", 9), which is
         what sorting an array of pairs needs. Recursing raises when an element
         pair is itself incomparable, exactly as the interpreter's compare does. */
      sfl_check_nesting(0);
      uint32_t n = a->aux < b->aux ? a->aux : b->aux;
      for (uint32_t i = 0; i < n; i++) {
        int32_t c = sfl_compare(a->u.t.items[i], b->u.t.items[i]);
        if (c) return c;
      }
      return (int32_t)a->aux - (int32_t)b->aux;
    }
    default:
      break;
  }
  sfl_raise("cannot compare %s with %s", sfl_type_name(a), sfl_type_name(b));
}

SflVal sfl_cmp(int32_t op, SflVal a, SflVal b) {
  switch (op) {
    case 0: return sfl_bool(sfl_equal(a, b));
    case 1: return sfl_bool(!sfl_equal(a, b));
    default: {
      int32_t c = sfl_compare(a, b);
      switch (op) {
        case 2: return sfl_bool(c < 0);
        case 3: return sfl_bool(c <= 0);
        case 4: return sfl_bool(c > 0);
        default: return sfl_bool(c >= 0);
      }
    }
  }
}

/* ------------------------------------------------------------------------- */
/* Arithmetic                                                                 */
/* ------------------------------------------------------------------------- */

static const char *op_symbol(int32_t op) {
  switch (op) {
    case 0: return "+";
    case 1: return "-";
    case 2: return "*";
    case 3: return "/";
    default: return "%";
  }
}

static SflVal concat_strings(SflVal a, SflVal b) {
  SflVal x = sfl_display(a);
  SflVal y = sfl_display(b);
  Buf buf;
  buf_init(&buf);
  buf_str(&buf, x);
  buf_str(&buf, y);
  return buf_take(&buf);
}

static void arith_type_error(int32_t op, SflVal a, SflVal b) __attribute__((noreturn));
static void arith_type_error(int32_t op, SflVal a, SflVal b) {
  const char *sym = op_symbol(op);
  int numeric = a->tag == SFL_INT || a->tag == SFL_FLOAT || b->tag == SFL_INT || b->tag == SFL_FLOAT;
  SflVal sa = show(a), sb = show(b);
  char *ta = sfl_str_dup_utf8(sa), *tb = sfl_str_dup_utf8(sb);
  char msg[512];
  snprintf(msg, sizeof msg, "operator '%s' is not defined for %s and %s (%s %s %s)", sym,
           sfl_type_name(a), sfl_type_name(b), ta, sym, tb);
  sfl_raw_free(ta);
  sfl_raw_free(tb);
  sfl_raise_hint(msg, numeric
    ? "convert the other side with toInt(v), toFloat(v) or toString(v)"
    : "use toString(v) to build text, or toInt(v) / toFloat(v) to do arithmetic");
}

static double float_op(int32_t op, double x, double y) {
  switch (op) {
    case 0: return x + y;
    case 1: return x - y;
    case 2: return x * y;
    case 3: return x / y;
    default: return fmod(x, y);
  }
}

SflVal sfl_arith(int32_t op, SflVal a, SflVal b) {
  if (a->tag == SFL_INT) {
    if (b->tag == SFL_INT) {
      int64_t x = a->u.i, y = b->u.i;
      switch (op) {
        case 0: return sfl_int(x + y);
        case 1: return sfl_int(x - y);
        case 2: return sfl_int(x * y);
        case 3:
          if (y == 0) sfl_raise("division by zero");
          if (x == INT64_MIN && y == -1) return sfl_int(INT64_MIN);
          return sfl_int(x / y);
        default:
          if (y == 0) sfl_raise("modulo by zero");
          if (x == INT64_MIN && y == -1) return sfl_int(0);
          return sfl_int(x % y);
      }
    }
    if (b->tag == SFL_FLOAT) return sfl_float(float_op(op, (double)a->u.i, b->u.d));
    if (b->tag == SFL_STR && op == 0) return concat_strings(a, b);
    arith_type_error(op, a, b);
  }
  if (a->tag == SFL_FLOAT) {
    if (b->tag == SFL_FLOAT) return sfl_float(float_op(op, a->u.d, b->u.d));
    if (b->tag == SFL_INT) return sfl_float(float_op(op, a->u.d, (double)b->u.i));
    if (b->tag == SFL_STR && op == 0) return concat_strings(a, b);
    arith_type_error(op, a, b);
  }
  if (a->tag == SFL_STR) {
    if (op == 0) return concat_strings(a, b);
    if (op == 2 && b->tag == SFL_INT) {
      int64_t k = b->u.i;
      if (k < 0) sfl_raise("cannot repeat a string a negative number of times");
      if (k * (int64_t)a->aux > 64 * 1024 * 1024) sfl_raise("string repetition result is too large");
      Buf buf;
      buf_init(&buf);
      for (int64_t i = 0; i < k; i++) buf_str(&buf, a);
      return buf_take(&buf);
    }
    arith_type_error(op, a, b);
  }
  if (a->tag == SFL_ARR && op == 0) {
    if (b->tag != SFL_ARR) arith_type_error(op, a, b);
    SflVal out = sfl_arr_new((int64_t)a->aux + b->aux);
    for (uint32_t i = 0; i < a->aux; i++) out->u.a.items[i] = a->u.a.items[i];
    for (uint32_t i = 0; i < b->aux; i++) out->u.a.items[a->aux + i] = b->u.a.items[i];
    out->aux = a->aux + b->aux;
    return out;
  }
  if (a->tag == SFL_OBJ && op == 0) {
    if (b->tag != SFL_OBJ) arith_type_error(op, a, b);
    SflVal out = sfl_obj_new();
    for (uint32_t i = 0; i < a->aux; i++) sfl_obj_put(out, a->u.o.keys[i], a->u.o.vals[i]);
    for (uint32_t i = 0; i < b->aux; i++) sfl_obj_put(out, b->u.o.keys[i], b->u.o.vals[i]);
    return out;
  }
  /* Concatenating anything with a string stays permissive, as the interpreter is. */
  if (op == 0 && b->tag == SFL_STR) return concat_strings(a, b);
  arith_type_error(op, a, b);
}

SflVal sfl_neg(SflVal a) {
  if (a->tag == SFL_INT) return sfl_int(-a->u.i);
  if (a->tag == SFL_FLOAT) return sfl_float(-a->u.d);
  sfl_raise("cannot negate %s", sfl_type_name(a));
}

/* ------------------------------------------------------------------------- */
/* Indexing                                                                   */
/* ------------------------------------------------------------------------- */

static int64_t to_index(SflVal k, const char *what) {
  if (k->tag == SFL_INT) return k->u.i;
  if (k->tag == SFL_FLOAT && k->u.d == floor(k->u.d) && !isinf(k->u.d)) return (int64_t)k->u.d;
  sfl_raise("%s must be an integer, got %s", what, sfl_type_name(k));
}

static void out_of_bounds(const char *what, SflVal key, int64_t len) __attribute__((noreturn));
static void out_of_bounds(const char *what, SflVal key, int64_t len) {
  SflVal d = sfl_display(key);
  char *t = sfl_str_dup_utf8(d);
  char msg[256], hint[128];
  snprintf(msg, sizeof msg, "%s index %s is out of bounds (length %lld)", what, t, (long long)len);
  sfl_raw_free(t);
  if (len == 0) snprintf(hint, sizeof hint, "the %s is empty", what);
  else snprintf(hint, sizeof hint,
                "valid indices are 0 to %lld, or -1 to -%lld counting from the end",
                (long long)len - 1, (long long)len);
  sfl_raise_hint(msg, hint);
}

static void missing_key(SflVal o, SflVal key) __attribute__((noreturn));
static void missing_key(SflVal o, SflVal key) {
  char *k = sfl_str_dup_utf8(key);
  char msg[256];
  snprintf(msg, sizeof msg, "object has no key '%s'", k);
  sfl_raw_free(k);

  char listed[512];
  if (o->aux == 0) snprintf(listed, sizeof listed, "the object is empty");
  else {
    Buf b;
    buf_init(&b);
    uint32_t show_n = o->aux <= 8 ? o->aux : 8;
    for (uint32_t i = 0; i < show_n; i++) {
      if (i) buf_ascii(&b, ", ");
      buf_str(&b, o->u.o.keys[i]);
    }
    SflVal names = buf_take(&b);
    char *t = sfl_str_dup_utf8(names);
    if (o->aux <= 8) snprintf(listed, sizeof listed, "it has: %s", t);
    else snprintf(listed, sizeof listed, "it has %u keys, starting with: %s", o->aux, t);
    sfl_raw_free(t);
  }
  sfl_raise_hint(msg, listed);
}

SflVal sfl_index_get(SflVal recv, SflVal key) {
  switch (recv->tag) {
    case SFL_ARR: {
      int64_t i = to_index(key, "array index");
      if (i < 0) i += recv->aux;
      if (i < 0 || i >= (int64_t)recv->aux) out_of_bounds("array", key, recv->aux);
      return recv->u.a.items[i];
    }
    case SFL_OBJ: {
      if (key->tag != SFL_STR) {
        SflVal s = show(key);
        char *t = sfl_str_dup_utf8(s);
        char hint[128];
        snprintf(hint, sizeof hint, "the key was %s; use an array if you want numeric positions", t);
        sfl_raw_free(t);
        char msg[128];
        snprintf(msg, sizeof msg, "object keys must be strings, got %s", sfl_type_name(key));
        sfl_raise_hint(msg, hint);
      }
      SflVal v = sfl_obj_get(recv, key);
      if (v == NULL) missing_key(recv, key);
      return v;
    }
    case SFL_TUPLE: {
      int64_t i = to_index(key, "tuple index");
      if (i < 0) i += recv->aux;
      if (i < 0 || i >= (int64_t)recv->aux) out_of_bounds("tuple", key, recv->aux);
      return recv->u.t.items[i];
    }
    case SFL_STR: {
      int64_t i = to_index(key, "string index");
      if (i < 0) i += recv->aux;
      if (i < 0 || i >= (int64_t)recv->aux) out_of_bounds("string", key, recv->aux);
      return sfl_str_utf16(recv->u.s.chars + i, 1);
    }
    case SFL_NULL:
      sfl_raise_hint("cannot index into null",
                     "check it with isNull(v), or read it with get(v, key, default)");
    default:
      sfl_raise("cannot index into %s", sfl_type_name(recv));
  }
}

SflVal sfl_index_set(SflVal recv, SflVal key, SflVal v) {
  switch (recv->tag) {
    case SFL_ARR: {
      int64_t i = to_index(key, "array index");
      if (i < 0) i += recv->aux;
      if (i < 0 || i >= (int64_t)recv->aux) out_of_bounds("array", key, recv->aux);
      recv->u.a.items[i] = v;
      return v;
    }
    case SFL_OBJ:
      if (key->tag != SFL_STR)
        sfl_raise("object keys must be strings, got %s", sfl_type_name(key));
      sfl_obj_put(recv, key, v);
      return v;
    case SFL_TUPLE:
      /* Its own case rather than the generic "cannot assign into tuple": the
         reader has a way out (copy it), and the interpreter names it. */
      sfl_raise_hint("cannot assign into a tuple, it is immutable",
                     "use toArray(t) for a mutable copy, or build a new tuple");
    case SFL_NULL:
      sfl_raise("cannot assign into null");
    default:
      sfl_raise("cannot assign into %s", sfl_type_name(recv));
  }
}

SflVal sfl_index_compound(SflVal recv, SflVal key, int32_t op, SflVal v) {
  SflVal cur = sfl_index_get(recv, key);
  return sfl_index_set(recv, key, sfl_arith(op, cur, v));
}

/* ------------------------------------------------------------------------- */
/* Interned literals                                                          */
/* ------------------------------------------------------------------------- */

/*
 * String literals are created once at startup and kept in a root array, so the
 * collector keeps them and repeated evaluation of a literal allocates nothing.
 */
static SflVal *interned;
static int64_t interned_len, interned_cap;

SflVal sfl_intern(const char *utf8, int64_t nbytes) {
  if (interned_len == interned_cap) {
    int64_t cap = interned_cap ? interned_cap * 2 : 64;
    SflVal *grown = (SflVal *)sfl_raw_alloc((size_t)cap * sizeof(SflVal));
    for (int64_t i = 0; i < interned_len; i++) grown[i] = interned[i];
    /* The old array stays registered as a root; leaving it costs a few words and
       avoids a window where literals are unreachable. */
    interned = grown;
    interned_cap = cap;
    sfl_gc_add_roots(interned, cap);
    for (int64_t i = interned_len; i < cap; i++) interned[i] = NULL;
  }
  SflVal s = sfl_str_utf8(utf8, nbytes);
  interned[interned_len++] = s;
  return s;
}

/* ------------------------------------------------------------------------- */
/* Iteration                                                                  */
/* ------------------------------------------------------------------------- */

SflVal sfl_iter_begin(SflVal v) {
  switch (v->tag) {
    case SFL_ARR:
    case SFL_STR:
    case SFL_OBJ:
    case SFL_TUPLE:
      break;
    case SFL_ITER:
      return v;
    default:
      sfl_raise("cannot iterate over %s", sfl_type_name(v));
  }
  SflVal it = sfl_alloc(SFL_ITER);
  it->u.it.src = v;
  it->u.it.pos = 0;
  return it;
}

SflVal sfl_iter_from_fn(SflVal next) {
  SflVal it = sfl_alloc(SFL_ITER);
  it->u.it.fn = next;
  it->u.it.pos = 0;
  return it;
}

int sfl_iter_next(SflVal it, SflVal *out) {
  SflVal src = it->u.it.src;
  if (src == NULL) {
    /* Lazy: the driving function yields [v], or [] once it is exhausted. */
    if (it->u.it.pos == 2) return 0;
    if (it->u.it.pos == 1) {
      *out = it->u.it.pending;
      it->u.it.pending = NULL;
      it->u.it.pos = 0;
      return 1;
    }
    SflVal r = sfl_call0(it->u.it.fn);
    if (r->tag != SFL_ARR)
      sfl_raise("an iterator function must return [value] or [], got %s", sfl_type_name(r));
    if (r->aux == 0) { it->u.it.pos = 2; return 0; }
    *out = r->u.a.items[0];
    return 1;
  }
  int64_t i = it->u.it.pos;
  switch (src->tag) {
    case SFL_ARR:
      if (i >= (int64_t)src->aux) return 0;
      *out = src->u.a.items[i];
      it->u.it.pos = i + 1;
      return 1;
    case SFL_TUPLE:
      /* Like an array walk; ForIn.iterator yields tuple elements the same way. */
      if (i >= (int64_t)src->aux) return 0;
      *out = src->u.t.items[i];
      it->u.it.pos = i + 1;
      return 1;
    case SFL_OBJ:
      if (i >= (int64_t)src->aux) return 0;
      *out = src->u.o.keys[i];
      it->u.it.pos = i + 1;
      return 1;
    case SFL_STR:
      if (i >= (int64_t)src->aux) return 0;
      *out = sfl_str_utf16(src->u.s.chars + i, 1);
      it->u.it.pos = i + 1;
      return 1;
    default:
      return 0;
  }
}

/* Looks one element ahead, which is what hasNext() needs over a lazy source. */
int sfl_iter_has_next(SflVal it) {
  if (it->u.it.src != NULL) {
    SflVal src = it->u.it.src;
    return it->u.it.pos < (int64_t)src->aux;
  }
  if (it->u.it.pos == 2) return 0;
  if (it->u.it.pos == 1) return 1;
  SflVal v;
  if (!sfl_iter_next(it, &v)) return 0;
  it->u.it.pending = v;
  it->u.it.pos = 1;
  return 1;
}

int64_t sfl_str_length(SflVal s) {
  if (s->tag != SFL_STR) sfl_raise("expected a string, got %s", sfl_type_name(s));
  return s->aux;
}

int64_t sfl_size_of(SflVal v) {
  switch (v->tag) {
    case SFL_STR:
    case SFL_ARR:
    case SFL_TUPLE:
    case SFL_OBJ: return v->aux;
    default: break;
  }
  sfl_raise("size: expected a string, array or object, got %s", sfl_type_name(v));
}
