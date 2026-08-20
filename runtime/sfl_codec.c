/*
 * Base64 and hex.
 *
 * stdlib/codec.sfl is the tested description of what the interpreter's codecs do,
 * including with input they cannot represent, and importing it still shadows these
 * four names, which is how the test suite keeps pinning it. But its loops go
 * character by character through one-element strings, which on a compiled program
 * is the difference between a millisecond and ninety. A compiled call resolves
 * here instead: the same behavior, ported statement for statement, over bytes.
 *
 * Encoding starts from the UTF-8 bytes Java's charset encoder would produce (an
 * unpaired surrogate becomes '?'), and decoding reproduces the charset decoder's
 * error handling: one U+FFFD per malformed sequence, consuming exactly the bytes
 * codec.sfl's _utf8Malformed says it consumes.
 */
#include "sfl.h"

#include <stdio.h>
#include <string.h>

/* ------------------------------------------------------------------------- */
/* UTF-8, as the interpreter's charset decoder reads it                       */
/* ------------------------------------------------------------------------- */

/* Bytes in the sequence a lead byte starts, or -1 when the byte cannot lead one. */
static int utf8_seq_len(uint8_t b) {
  if (b < 0x80) return 1;
  if (b < 0xc0) return -1;
  if (b < 0xe0) return 2;
  if (b < 0xf0) return 3;
  if (b < 0xf8) return 4;
  return -1;
}

/* The lead byte contributes 7 - len significant bits, each further byte another 6. */
static uint32_t utf8_cp(const uint8_t *b, int64_t i, int len) {
  uint32_t cp = b[i] & (0xffu >> (len + 1));
  for (int k = 1; k < len; k++) cp = (cp << 6) | (b[i + k] & 0x3f);
  return cp;
}

/*
 * 0 when a well-formed sequence starts at i, otherwise how many bytes the decoder
 * consumes in reporting the error — which is what decides where it picks up again.
 * Overlong spellings and code points outside the real range are rejected too.
 */
static int utf8_malformed(const uint8_t *b, int64_t i, int len, int64_t n) {
  int64_t avail = i + len > n ? n - i : len;
  for (int64_t k = 1; k < avail; k++)
    if ((b[i + k] & 0xc0) != 0x80) return (int)k;
  if (avail < len) return (int)avail;
  uint32_t cp = utf8_cp(b, i, len);
  if (len == 2 && cp < 0x80) return 1;
  if (len == 3 && cp < 0x800) return 1;
  if (len == 3 && cp >= 0xd800 && cp <= 0xdfff) return 3;
  if (len == 4 && (cp < 0x10000 || cp > 0x10ffff)) return 1;
  return 0;
}

/* Decodes UTF-8 bytes back into a string, U+FFFD standing in for the malformed. */
static SflVal utf8_java_string(const uint8_t *b, int64_t n) {
  /* Every byte yields at most one code unit: even a 4-byte sequence's two units
     are fewer than its four bytes. */
  uint16_t *out = (uint16_t *)sfl_raw_alloc((size_t)(n ? n : 1) * 2);
  int64_t k = 0;
  for (int64_t i = 0; i < n;) {
    int len = utf8_seq_len(b[i]);
    if (len == 1) {
      out[k++] = b[i];
      i += 1;
    } else if (len < 0) {
      out[k++] = 0xfffd;
      i += 1;
    } else {
      int bad = utf8_malformed(b, i, len, n);
      if (bad == 0) {
        uint32_t cp = utf8_cp(b, i, len);
        if (cp >= 0x10000) {
          cp -= 0x10000;
          out[k++] = (uint16_t)(0xd800 + (cp >> 10));
          out[k++] = (uint16_t)(0xdc00 + (cp & 0x3ff));
        } else {
          out[k++] = (uint16_t)cp;
        }
        i += len;
      } else {
        out[k++] = 0xfffd;
        i += bad;
      }
    }
  }
  SflVal s = sfl_str_utf16(out, k);
  sfl_raw_free(out);
  return s;
}

/* The UTF-8 bytes of the argument, checked and encoded as getBytes(UTF_8) would. */
static uint8_t *java_bytes(SflVal s, int64_t *len) {
  int64_t n = sfl_utf8_java_len(s);
  uint8_t *b = (uint8_t *)sfl_raw_alloc((size_t)n);
  sfl_utf8_java_write(s, (char *)b);
  *len = n;
  return b;
}

/* ------------------------------------------------------------------------- */
/* Base64                                                                     */
/* ------------------------------------------------------------------------- */

static const char b64_chars[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/* -1 for anything outside the alphabet, padding included. */
static int b64_value(uint16_t c) {
  if (c >= 'A' && c <= 'Z') return c - 'A';
  if (c >= 'a' && c <= 'z') return c - 'a' + 26;
  if (c >= '0' && c <= '9') return c - '0' + 52;
  if (c == '+') return 62;
  if (c == '/') return 63;
  return -1;
}

/* The interpreter attaches the signature to any complaint of the form
   "name: what went wrong"; sfl_raise_sig is that rule, compiled. */
static void b64_bad(void) __attribute__((noreturn));
static void b64_bad(void) { sfl_raise_sig("base64Decode", "input is not valid Base64"); }

SflVal sfl_p_base64Encode(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "base64Encode");
  int64_t n;
  uint8_t *b = java_bytes(s, &n);
  int64_t outn = (n + 2) / 3 * 4;
  char *out = (char *)sfl_raw_alloc((size_t)(outn ? outn : 1));
  int64_t k = 0;
  for (int64_t i = 0; i < n; i += 3) {
    uint32_t bits = (uint32_t)b[i] << 16;
    if (i + 1 < n) bits |= (uint32_t)b[i + 1] << 8;
    if (i + 2 < n) bits |= b[i + 2];
    out[k++] = b64_chars[bits >> 18];
    out[k++] = b64_chars[(bits >> 12) & 0x3f];
    out[k++] = i + 1 < n ? b64_chars[(bits >> 6) & 0x3f] : '=';
    out[k++] = i + 2 < n ? b64_chars[bits & 0x3f] : '=';
  }
  SflVal r = sfl_str_utf8(out, outn);
  sfl_raw_free(out);
  sfl_raw_free(b);
  return r;
}

SflVal sfl_p_base64Decode(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "base64Decode");
  const uint16_t *u = s->u.s.chars;
  int64_t n = s->aux;

  /* The interpreter sizes its output buffer from the input length alone and hands
     back the whole buffer, so padding beyond what the data needs surfaces as
     trailing zero bytes — and an input of nothing but padding is rejected. */
  int64_t valid = n;
  if (n >= 1 && u[n - 1] == '=') {
    valid--;
    if (n >= 2 && u[n - 2] == '=') valid--;
  }
  if (n >= 1 && valid == 0) b64_bad();
  int64_t rest = valid % 4;
  int64_t outn = (valid + 3) / 4 * 3 - (rest == 0 ? 0 : 4 - rest);

  /* Everything rejectable is rejected before anything is allocated: a raise
     unwinds past this frame, and a buffer would not survive to be freed. The
     data stops at the first padding character; only more padding may follow it. */
  int64_t stop = 0;
  while (stop < n && u[stop] != '=') {
    if (b64_value(u[stop]) < 0) b64_bad();
    stop++;
  }
  for (int64_t j = stop; j < n; j++)
    if (u[j] != '=') b64_bad();
  /* Six leftover bits are half a byte and cannot have come from any input. */
  if (stop % 4 == 1) b64_bad();

  uint8_t *bytes = (uint8_t *)sfl_raw_alloc((size_t)(outn ? outn : 1));
  int64_t nb = 0;
  uint32_t acc = 0;
  int held = 0;
  for (int64_t i = 0; i < stop; i++) {
    acc = (acc << 6) | (uint32_t)b64_value(u[i]);
    if (++held == 4) {
      bytes[nb++] = (uint8_t)(acc >> 16);
      bytes[nb++] = (uint8_t)(acc >> 8);
      bytes[nb++] = (uint8_t)acc;
      acc = 0;
      held = 0;
    }
  }
  if (held == 2) bytes[nb++] = (uint8_t)(acc >> 4);
  if (held == 3) {
    bytes[nb++] = (uint8_t)(acc >> 10);
    bytes[nb++] = (uint8_t)(acc >> 2);
  }
  while (nb < outn) bytes[nb++] = 0;

  SflVal r = utf8_java_string(bytes, outn);
  sfl_raw_free(bytes);
  return r;
}

/* ------------------------------------------------------------------------- */
/* Hex                                                                        */
/* ------------------------------------------------------------------------- */

/*
 * A hex digit is not only an ASCII one: the interpreter asks Character.digit,
 * which knows every decimal digit in its table and the fullwidth Latin letters
 * as well. These are the code points the digit zero sits on in each script the
 * table covers; nine more digits follow each of them in order.
 */
static const uint16_t hex_digit_zeros[] = {
    0x0660, 0x06f0, 0x07c0, 0x0966, 0x09e6, 0x0a66, 0x0ae6, 0x0b66, 0x0be6,
    0x0c66, 0x0ce6, 0x0d66, 0x0e50, 0x0ed0, 0x0f20, 0x1040, 0x1090, 0x17e0,
    0x1810, 0x1946, 0x19d0, 0x1a80, 0x1a90, 0x1b50, 0x1bb0, 0x1c40, 0x1c50,
    0xa620, 0xa8d0, 0xa900, 0xa9d0, 0xaa50, 0xabf0, 0xff10};

/* -1 for anything that is not a hex digit. */
static int hex_value(uint16_t c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  if (c < 0x0660) return -1;
  if (c >= 0xff21 && c <= 0xff26) return c - 0xff21 + 10;
  if (c >= 0xff41 && c <= 0xff46) return c - 0xff41 + 10;
  for (size_t k = 0; k < sizeof hex_digit_zeros / sizeof hex_digit_zeros[0]; k++)
    if (c >= hex_digit_zeros[k] && c <= hex_digit_zeros[k] + 9)
      return c - hex_digit_zeros[k];
  return -1;
}

/* The interpreter's message carries the whole input, so this cannot go through
   sfl_raise's fixed-size buffer. */
static void bad_hex(SflVal text) __attribute__((noreturn));
static void bad_hex(SflVal text) {
  static const char prefix[] = "invalid hex character in '";
  int64_t pn = (int64_t)sizeof prefix - 1, n = text->aux;
  uint16_t *msg = (uint16_t *)sfl_raw_alloc((size_t)(pn + n + 1) * 2);
  for (int64_t i = 0; i < pn; i++) msg[i] = (uint16_t)prefix[i];
  if (n) memcpy(msg + pn, text->u.s.chars, (size_t)n * 2);
  msg[pn + n] = '\'';
  SflVal m = sfl_str_utf16(msg, pn + n + 1);
  sfl_raw_free(msg);
  sfl_raise_val(m);
}

SflVal sfl_p_hexEncode(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "hexEncode");
  static const char digits[] = "0123456789abcdef";
  int64_t n;
  uint8_t *b = java_bytes(s, &n);
  char *out = (char *)sfl_raw_alloc((size_t)(n ? n * 2 : 1));
  for (int64_t i = 0; i < n; i++) {
    out[i * 2] = digits[b[i] >> 4];
    out[i * 2 + 1] = digits[b[i] & 0x0f];
  }
  SflVal r = sfl_str_utf8(out, n * 2);
  sfl_raw_free(out);
  sfl_raw_free(b);
  return r;
}

SflVal sfl_p_hexDecode(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "hexDecode");
  const uint16_t *u = s->u.s.chars;
  int64_t n = s->aux;
  if (n % 2 != 0) sfl_raise("hex string must have an even number of characters");
  /* Validated before allocating, for the same reason as base64Decode. */
  for (int64_t i = 0; i < n; i++)
    if (hex_value(u[i]) < 0) bad_hex(s);
  uint8_t *bytes = (uint8_t *)sfl_raw_alloc((size_t)(n ? n / 2 : 1));
  for (int64_t i = 0; i < n; i += 2)
    bytes[i / 2] = (uint8_t)((hex_value(u[i]) << 4) | hex_value(u[i + 1]));
  SflVal r = utf8_java_string(bytes, n / 2);
  sfl_raw_free(bytes);
  return r;
}

/* ------------------------------------------------------------------------- */
/* Byte arrays                                                                */
/*                                                                            */
/* The primitives above deal in text; these deal in arrays of ints 0..255,    */
/* which is what wire protocols, digests over binary, and base64 of payloads  */
/* that never were strings traffic in. Each one mirrors the interpreter's     */
/* implementation in BuiltinsText.binary() statement for statement.           */
/* ------------------------------------------------------------------------- */

SflVal sfl_p_utf8Encode(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "utf8Encode");
  int64_t n;
  uint8_t *b = java_bytes(s, &n);
  SflVal r = sfl_bytes_arr(b, n);
  sfl_raw_free(b);
  return r;
}

SflVal sfl_p_utf8Decode(int64_t argc, SflVal *argv) {
  int64_t n;
  uint8_t *b = sfl_arg_bytes(argv, 0, "utf8Decode", &n);
  SflVal r = utf8_java_string(b, n);
  sfl_raw_free(b);
  return r;
}

SflVal sfl_p_base64EncodeBytes(int64_t argc, SflVal *argv) {
  int64_t n;
  uint8_t *b = sfl_arg_bytes(argv, 0, "base64EncodeBytes", &n);
  int64_t outn = (n + 2) / 3 * 4;
  char *out = (char *)sfl_raw_alloc((size_t)(outn ? outn : 1));
  int64_t k = 0;
  for (int64_t i = 0; i < n; i += 3) {
    uint32_t bits = (uint32_t)b[i] << 16;
    if (i + 1 < n) bits |= (uint32_t)b[i + 1] << 8;
    if (i + 2 < n) bits |= b[i + 2];
    out[k++] = b64_chars[bits >> 18];
    out[k++] = b64_chars[(bits >> 12) & 0x3f];
    out[k++] = i + 1 < n ? b64_chars[(bits >> 6) & 0x3f] : '=';
    out[k++] = i + 2 < n ? b64_chars[bits & 0x3f] : '=';
  }
  SflVal r = sfl_str_utf8(out, outn);
  sfl_raw_free(out);
  sfl_raw_free(b);
  return r;
}

/*
 * Strict base64: the standard alphabet, optional but well-formed '=' padding,
 * nothing after it. Deliberately NOT the legacy base64Decode above, whose
 * padding quirks replicate the interpreter's original string codec; this pair
 * is new on both sides and shares one algorithm.
 */
SflVal sfl_p_base64DecodeBytes(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "base64DecodeBytes");
  const uint16_t *u = s->u.s.chars;
  int64_t n = s->aux;
  int64_t stop = 0;
  while (stop < n && u[stop] != '=') {
    if (b64_value(u[stop]) < 0) sfl_raise_sig("base64DecodeBytes", "input is not valid Base64");
    stop++;
  }
  for (int64_t j = stop; j < n; j++)
    if (u[j] != '=') sfl_raise_sig("base64DecodeBytes", "input is not valid Base64");
  int64_t padded = n - stop;
  if (padded > 0 && (n % 4 != 0 || padded > 2 || stop % 4 == 0))
    sfl_raise_sig("base64DecodeBytes", "input is not valid Base64");
  if (stop % 4 == 1) sfl_raise_sig("base64DecodeBytes", "input is not valid Base64");
  int64_t rest = stop % 4;
  int64_t outn = stop / 4 * 3 + (rest == 2 ? 1 : rest == 3 ? 2 : 0);
  uint8_t *bytes = (uint8_t *)sfl_raw_alloc((size_t)(outn ? outn : 1));
  uint32_t acc = 0;
  int held = 0;
  int64_t nb = 0;
  for (int64_t i = 0; i < stop; i++) {
    acc = (acc << 6) | (uint32_t)b64_value(u[i]);
    if (++held == 4) {
      bytes[nb++] = (uint8_t)(acc >> 16);
      bytes[nb++] = (uint8_t)(acc >> 8);
      bytes[nb++] = (uint8_t)acc;
      acc = 0;
      held = 0;
    }
  }
  if (held == 2) bytes[nb++] = (uint8_t)(acc >> 4);
  if (held == 3) {
    bytes[nb++] = (uint8_t)(acc >> 10);
    bytes[nb++] = (uint8_t)(acc >> 2);
  }
  SflVal r = sfl_bytes_arr(bytes, outn);
  sfl_raw_free(bytes);
  return r;
}

SflVal sfl_p_hexEncodeBytes(int64_t argc, SflVal *argv) {
  static const char digits[] = "0123456789abcdef";
  int64_t n;
  uint8_t *b = sfl_arg_bytes(argv, 0, "hexEncodeBytes", &n);
  char *out = (char *)sfl_raw_alloc((size_t)(n ? n * 2 : 1));
  for (int64_t i = 0; i < n; i++) {
    out[i * 2] = digits[b[i] >> 4];
    out[i * 2 + 1] = digits[b[i] & 0x0f];
  }
  SflVal r = sfl_str_utf8(out, n * 2);
  sfl_raw_free(out);
  sfl_raw_free(b);
  return r;
}

/* ASCII hex digits only — not hexDecode's Character.digit table: this pair is
   new on both sides and keeps to the spelling machines actually exchange. */
static int ascii_hex(uint16_t c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

SflVal sfl_p_hexDecodeBytes(int64_t argc, SflVal *argv) {
  SflVal s = sfl_arg_str(argv, 0, "hexDecodeBytes");
  const uint16_t *u = s->u.s.chars;
  int64_t n = s->aux;
  if (n % 2 != 0)
    sfl_raise_sig("hexDecodeBytes", "hex string must have an even number of characters");
  for (int64_t i = 0; i < n; i++)
    if (ascii_hex(u[i]) < 0)
      sfl_raise_sig("hexDecodeBytes", "invalid hex character at index %lld", (long long)i);
  uint8_t *bytes = (uint8_t *)sfl_raw_alloc((size_t)(n ? n / 2 : 1));
  for (int64_t i = 0; i < n; i += 2)
    bytes[i / 2] = (uint8_t)((ascii_hex(u[i]) << 4) | ascii_hex(u[i + 1]));
  SflVal r = sfl_bytes_arr(bytes, n / 2);
  sfl_raw_free(bytes);
  return r;
}

/* ------------------------------------------------------------------------- */
/* Digests, HMAC and PBKDF2 over bytes                                        */
/* ------------------------------------------------------------------------- */

typedef void (*DigestFn)(const uint8_t *, uint64_t, uint8_t *);

typedef struct {
  DigestFn run;
  int dlen;
} Alg;

/* True when the SFL string spells exactly the ASCII literal. */
static int str_is(SflVal s, const char *lit) {
  int64_t n = s->aux;
  for (int64_t i = 0; i < n; i++) {
    if (lit[i] == '\0' || s->u.s.chars[i] != (uint16_t)lit[i]) return 0;
  }
  return lit[n] == '\0';
}

/* The interpreter's digestFor complaint, with the algorithm quoted verbatim. */
static void bad_alg(const char *fn, SflVal alg) __attribute__((noreturn));
static void bad_alg(const char *fn, SflVal alg) {
  static const char tail[] = "' (expected \"md5\", \"sha1\" or \"sha256\")";
  char head[128];
  snprintf(head, sizeof head, "%s: unknown algorithm '", fn);
  int64_t hn = (int64_t)strlen(head), tn = (int64_t)sizeof tail - 1, an = alg->aux;
  uint16_t *msg = (uint16_t *)sfl_raw_alloc((size_t)(hn + an + tn) * 2);
  for (int64_t i = 0; i < hn; i++) msg[i] = (uint16_t)head[i];
  if (an) memcpy(msg + hn, alg->u.s.chars, (size_t)an * 2);
  for (int64_t i = 0; i < tn; i++) msg[hn + an + i] = (uint16_t)tail[i];
  SflVal m = sfl_str_utf16(msg, hn + an + tn);
  sfl_raw_free(msg);
  const SflNative *nat = sfl_native_find(fn);
  char hint[256];
  hint[0] = '\0';
  if (nat) snprintf(hint, sizeof hint, "the signature is %s", nat->signature);
  sfl_raise_val_hint(m, hint);
}

static Alg alg_of(SflVal *argv, const char *fn) {
  SflVal s = sfl_arg_str(argv, 0, fn);
  if (str_is(s, "md5")) return (Alg){sfl_md5_digest, 16};
  if (str_is(s, "sha1")) return (Alg){sfl_sha1_digest, 20};
  if (str_is(s, "sha256")) return (Alg){sfl_sha256_digest, 32};
  bad_alg(fn, s);
}

/* HMAC (RFC 2104); md5, sha1 and sha256 all use a 64-byte block. The scratch
   buffer holds pad || message and is reused across pbkdf2's rounds. */
static void hmac_pads(Alg alg, const uint8_t *key, int64_t klen,
                      uint8_t inner[64], uint8_t outer[64]) {
  uint8_t k0[64];
  memset(k0, 0, sizeof k0);
  if (klen > 64) alg.run(key, (uint64_t)klen, k0);
  else if (klen > 0) memcpy(k0, key, (size_t)klen);
  for (int i = 0; i < 64; i++) {
    inner[i] = (uint8_t)(k0[i] ^ 0x36);
    outer[i] = (uint8_t)(k0[i] ^ 0x5c);
  }
}

static void hmac_run(Alg alg, const uint8_t inner[64], const uint8_t outer[64],
                     const uint8_t *msg, int64_t mlen, uint8_t *scratch, uint8_t *out) {
  memcpy(scratch, inner, 64);
  if (mlen) memcpy(scratch + 64, msg, (size_t)mlen);
  uint8_t h1[32];
  alg.run(scratch, (uint64_t)(64 + mlen), h1);
  memcpy(scratch, outer, 64);
  memcpy(scratch + 64, h1, (size_t)alg.dlen);
  alg.run(scratch, (uint64_t)(64 + alg.dlen), out);
}

SflVal sfl_p_digestBytes(int64_t argc, SflVal *argv) {
  Alg alg = alg_of(argv, "digestBytes");
  int64_t n;
  uint8_t *b = sfl_arg_bytes(argv, 1, "digestBytes", &n);
  uint8_t out[32];
  alg.run(b, (uint64_t)n, out);
  sfl_raw_free(b);
  return sfl_bytes_arr(out, alg.dlen);
}

SflVal sfl_p_hmacBytes(int64_t argc, SflVal *argv) {
  Alg alg = alg_of(argv, "hmacBytes");
  int64_t klen, mlen;
  uint8_t *key = sfl_arg_bytes(argv, 1, "hmacBytes", &klen);
  uint8_t inner[64], outer[64];
  hmac_pads(alg, key, klen, inner, outer);
  sfl_raw_free(key);
  uint8_t *msg = sfl_arg_bytes(argv, 2, "hmacBytes", &mlen);
  uint8_t *scratch = (uint8_t *)sfl_raw_alloc((size_t)(64 + (mlen > 32 ? mlen : 32)));
  uint8_t out[32];
  hmac_run(alg, inner, outer, msg, mlen, scratch, out);
  sfl_raw_free(scratch);
  sfl_raw_free(msg);
  return sfl_bytes_arr(out, alg.dlen);
}

SflVal sfl_p_pbkdf2(int64_t argc, SflVal *argv) {
  /* Checked in the interpreter's order — algorithm, password, salt, iterations,
     length — and only then copied, so a raise can never leak a buffer. */
  Alg alg = alg_of(argv, "pbkdf2");
  sfl_check_bytes(argv, 1, "pbkdf2");
  sfl_check_bytes(argv, 2, "pbkdf2");
  int64_t iterations = sfl_arg_int(argv, 3, "pbkdf2");
  if (iterations < 1) sfl_raise_sig("pbkdf2", "iterations must be at least 1");
  if (iterations > 2147483647LL) sfl_raise_sig("pbkdf2", "iterations is out of range");
  int64_t dklen = alg.dlen;
  if (argc > 4 && argv[4] != sfl_null) dklen = sfl_arg_int(argv, 4, "pbkdf2");
  if (dklen < 1 || dklen > 4096) sfl_raise_sig("pbkdf2", "length must be 1..4096");
  int64_t plen, slen;
  uint8_t *password = sfl_arg_bytes(argv, 1, "pbkdf2", &plen);
  uint8_t inner[64], outer[64];
  hmac_pads(alg, password, plen, inner, outer);
  sfl_raw_free(password);
  uint8_t *salt = sfl_arg_bytes(argv, 2, "pbkdf2", &slen);

  int64_t mmax = slen + 4 > 32 ? slen + 4 : 32;
  uint8_t *scratch = (uint8_t *)sfl_raw_alloc((size_t)(64 + mmax));
  uint8_t *counted = (uint8_t *)sfl_raw_alloc((size_t)(slen + 4));
  uint8_t *out = (uint8_t *)sfl_raw_alloc((size_t)dklen);
  if (slen) memcpy(counted, salt, (size_t)slen);
  sfl_raw_free(salt);

  int64_t written = 0;
  for (int32_t block = 1; written < dklen; block++) {
    counted[slen] = (uint8_t)(block >> 24);
    counted[slen + 1] = (uint8_t)(block >> 16);
    counted[slen + 2] = (uint8_t)(block >> 8);
    counted[slen + 3] = (uint8_t)block;
    uint8_t u[32], t[32];
    hmac_run(alg, inner, outer, counted, slen + 4, scratch, u);
    memcpy(t, u, (size_t)alg.dlen);
    for (int64_t c = 1; c < iterations; c++) {
      hmac_run(alg, inner, outer, u, alg.dlen, scratch, u);
      for (int k = 0; k < alg.dlen; k++) t[k] ^= u[k];
    }
    int64_t take = alg.dlen < dklen - written ? alg.dlen : dklen - written;
    memcpy(out + written, t, (size_t)take);
    written += take;
  }
  SflVal r = sfl_bytes_arr(out, dklen);
  sfl_raw_free(out);
  sfl_raw_free(counted);
  sfl_raw_free(scratch);
  return r;
}

SflVal sfl_p_randomBytes(int64_t argc, SflVal *argv) {
  int64_t n = sfl_arg_int(argv, 0, "randomBytes");
  if (n < 0 || n > 1048576) sfl_raise_sig("randomBytes", "n must be 0..1048576");
  if (n == 0) return sfl_bytes_arr(NULL, 0);
  FILE *f = fopen("/dev/urandom", "rb");
  if (f == NULL) sfl_raise_sig("randomBytes", "cannot open /dev/urandom");
  uint8_t *b = (uint8_t *)sfl_raw_alloc((size_t)n);
  size_t got = 0;
  while (got < (size_t)n) {
    size_t r = fread(b + got, 1, (size_t)n - got, f);
    if (r == 0) {
      fclose(f);
      sfl_raw_free(b);
      sfl_raise_sig("randomBytes", "cannot read /dev/urandom");
    }
    got += r;
  }
  fclose(f);
  SflVal r = sfl_bytes_arr(b, n);
  sfl_raw_free(b);
  return r;
}
