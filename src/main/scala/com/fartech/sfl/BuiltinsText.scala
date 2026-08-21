package com.fartech.sfl

import Builtins.*
import java.nio.charset.StandardCharsets
import java.util.regex.{Matcher, Pattern, PatternSyntaxException}
import scala.collection.mutable

/** String manipulation, regular expressions, and text encodings. */
object BuiltinsText:

  def register(): Unit =
    basics()
    search()
    regex()
    encoding()
    binary()

  /** `length`, `contains`, `reverse`, `slice` and friends work on any sequence type. */
  private def basics(): Unit =
    define("length", 1, 1, "string", "length(v)",
      "Length of a string, array or object.") { a =>
      a(0) match
        case VStr(s) => VInt.of(s.length.toLong)
        case v: VArr => VInt.of(v.items.length.toLong)
        case v: VObj => VInt.of(v.fields.size.toLong)
        case VNull   => VInt.Zero
        case v       => Err.eval(s"length: ${v.typeName} has no length")
    }

    define("isEmpty", 1, 1, "string", "isEmpty(v)", "True when a string, array or object has no elements.") { a =>
      a(0) match
        case VStr(s) => VBool.of(s.isEmpty)
        case v: VArr => VBool.of(v.items.isEmpty)
        case v: VObj => VBool.of(v.fields.isEmpty)
        case VNull   => VBool.True
        case v       => Err.eval(s"isEmpty: ${v.typeName} has no length")
    }

    define("charAt", 2, 2, "string", "charAt(s, i)",
      "One-character string at index i; negative indices count from the end.") { a =>
      val s = argStr(a, 0, "charAt")
      val i = Index.normalize(argIdx(a, 1, "charAt"), s.length)
      if i < 0 || i >= s.length then Err.eval(s"charAt: index out of bounds (length ${s.length})")
      VStr.ofChar(s.charAt(i))
    }

    define("subString", 2, 3, "string", "subString(s, start, end?)",
      "Substring from start (inclusive) to end (exclusive); end defaults to the length.") { a =>
      substr(a, "subString")
    }
    define("substring", 2, 3, "string", "substring(s, start, end?)", "Alias of subString.") { a =>
      substr(a, "substring")
    }

    define("slice", 2, 3, "string", "slice(v, start, end?)",
      "Sub-range of a string or array; negative indices count from the end.") { a =>
      a(0) match
        case VStr(s) =>
          val (b, e) = sliceRange(a, s.length, "slice")
          VStr(s.substring(b, e))
        case arr: VArr =>
          val (b, e) = sliceRange(a, arr.items.length, "slice")
          VArr.of(arr.items.slice(b, e))
        case v => Err.eval(s"slice: expected a string or array, got ${v.typeName}")
    }

    define("trim", 1, 1, "string", "trim(s)", "Removes leading and trailing whitespace.") { a =>
      VStr(argStr(a, 0, "trim").trim)
    }
    define("trimStart", 1, 1, "string", "trimStart(s)", "Removes leading whitespace.") { a =>
      VStr(argStr(a, 0, "trimStart").replaceAll("^\\s+", ""))
    }
    define("trimEnd", 1, 1, "string", "trimEnd(s)", "Removes trailing whitespace.") { a =>
      VStr(argStr(a, 0, "trimEnd").replaceAll("\\s+$", ""))
    }
    define("utf8Length", 1, 1, "string", "utf8Length(s)",
      "The UTF-8 byte count of the string as it leaves the process; what a " +
        "Content-Length must say, where length() counts code points.") { a =>
      VInt.of(argStr(a, 0, "utf8Length").getBytes(StandardCharsets.UTF_8).length.toLong)
    }

    define("toUpper", 1, 1, "string", "toUpper(s)", "Upper-cases a string.") { a =>
      VStr(argStr(a, 0, "toUpper").toUpperCase)
    }
    define("toLower", 1, 1, "string", "toLower(s)", "Lower-cases a string.") { a =>
      VStr(argStr(a, 0, "toLower").toLowerCase)
    }
    define("capitalize", 1, 1, "string", "capitalize(s)", "Upper-cases the first character.") { a =>
      val s = argStr(a, 0, "capitalize")
      VStr(if s.isEmpty then s else s"${s.charAt(0).toUpper}${s.substring(1)}")
    }

    define("repeat", 2, 2, "string", "repeat(s, n)", "Concatenates n copies of a string.") { a =>
      val s = argStr(a, 0, "repeat")
      val n = argIdx(a, 1, "repeat")
      if n < 0 then Err.eval("repeat: count must not be negative")
      if s.length.toLong * n > 64L * 1024 * 1024 then Err.eval("repeat: result would be too large")
      VStr(s * n)
    }

    define("padStart", 2, 3, "string", "padStart(s, width, pad?)",
      "Left-pads to the given width using pad (default a space).") { a => pad(a, left = true) }
    define("padEnd", 2, 3, "string", "padEnd(s, width, pad?)",
      "Right-pads to the given width using pad (default a space).") { a => pad(a, left = false) }

    define("split", 2, 3, "string", "split(s, sep, limit?)",
      "Splits on a literal separator; an empty separator splits into characters.") { a =>
      val s = argStr(a, 0, "split")
      val sep = argStr(a, 1, "split")
      if sep.isEmpty then VArr.of(s.map(c => VStr(String.valueOf(c))))
      else
        val limit = if a.length > 2 then argIdx(a, 2, "split") else 0
        val parts = s.split(Pattern.quote(sep), limit)
        VArr.of(parts.iterator.map(VStr(_)))
    }

    define("lines", 1, 1, "string", "lines(s)", "Splits on line breaks.") { a =>
      VArr.of(argStr(a, 0, "lines").split("\r\n|\n|\r", -1).iterator.map(VStr(_)))
    }

    define("join", 1, 2, "string", "join(parts, sep?)",
      "Joins an array into a string using sep (default empty).") { a =>
      val arr = argArr(a, 0, "join")
      val sep = if a.length > 1 then a(1).display else ""
      val sb = new StringBuilder
      var i = 0
      while i < arr.items.length do
        if i > 0 then sb.append(sep)
        arr.items(i).write(sb, quoted = false, 0)
        i += 1
      VStr(sb.toString)
    }

    define("replace", 3, 3, "string", "replace(s, target, replacement)",
      "Replaces every literal occurrence of target.") { a =>
      VStr(argStr(a, 0, "replace").replace(argStr(a, 1, "replace"), argStr(a, 2, "replace")))
    }

    define("format", 1, -1, "string", "format(fmt, ...)",
      "Formats using printf-style directives such as %s, %d and %.2f.") { a =>
      VStr(formatValues(argStr(a, 0, "format"), a.drop(1)))
    }

    define("chr", 1, 1, "string", "chr(code)", "Character for a Unicode code point.") { a =>
      val cp = argIdx(a, 0, "chr")
      if cp < 0 || cp > 0x10ffff then Err.eval(s"chr: $cp is not a valid code point")
      if cp < 128 then VStr.ofChar(cp.toChar) else VStr(new String(Character.toChars(cp)))
    }

    define("ord", 1, 1, "string", "ord(s)", "Unicode code point of the first character.") { a =>
      val s = argStr(a, 0, "ord")
      if s.isEmpty then Err.eval("ord: string is empty")
      VInt.of(s.codePointAt(0).toLong)
    }

  private def substr(a: Array[Value], fn: String): Value =
    val s = argStr(a, 0, fn)
    val start = argIdx(a, 1, fn)
    val end = if a.length > 2 then argIdx(a, 2, fn) else s.length
    val b = Index.normalize(start, s.length)
    val e = Index.normalize(end, s.length)
    if b < 0 || e > s.length || b > e then
      Err.eval(s"$fn: range [$start, $end) is invalid for a string of length ${s.length}")
    VStr(s.substring(b, e))

  private def sliceRange(a: Array[Value], len: Int, fn: String): (Int, Int) =
    val rawB = Index.normalize(argIdx(a, 1, fn), len)
    val rawE = if a.length > 2 then Index.normalize(argIdx(a, 2, fn), len) else len
    val b = math.max(0, math.min(rawB, len))
    val e = math.max(b, math.min(rawE, len))
    (b, e)

  private def pad(a: Array[Value], left: Boolean): Value =
    val fn = if left then "padStart" else "padEnd"
    val s = argStr(a, 0, fn)
    val width = argIdx(a, 1, fn)
    val padStr = if a.length > 2 then argStr(a, 2, fn) else " "
    if padStr.isEmpty then Err.eval(s"$fn: padding string must not be empty")
    if s.length >= width then VStr(s)
    else
      val sb = new StringBuilder
      while sb.length < width - s.length do sb.append(padStr)
      val fill = sb.substring(0, width - s.length)
      VStr(if left then fill + s else s + fill)

  private def search(): Unit =
    define("strFind", 2, 3, "string", "strFind(s, needle, from?)",
      "Index of the first occurrence, or -1.") { a => indexOfImpl(a, "strFind") }

    define("indexOf", 2, 3, "string", "indexOf(v, needle, from?)",
      "Index of the first occurrence in a string or array, or -1.") { a =>
      a(0) match
        case VStr(_) => indexOfImpl(a, "indexOf")
        case arr: VArr =>
          val from = if a.length > 2 then math.max(0, argIdx(a, 2, "indexOf")) else 0
          var i = from
          var found = -1
          while found < 0 && i < arr.items.length do
            if Values.equal(arr.items(i), a(1)) then found = i
            i += 1
          VInt.of(found.toLong)
        case v => Err.eval(s"indexOf: expected a string or array, got ${v.typeName}")
    }

    define("lastIndexOf", 2, 2, "string", "lastIndexOf(v, needle)",
      "Index of the last occurrence in a string or array, or -1.") { a =>
      a(0) match
        case VStr(s) => VInt.of(s.lastIndexOf(argStr(a, 1, "lastIndexOf")).toLong)
        case arr: VArr =>
          var i = arr.items.length - 1
          var found = -1
          while found < 0 && i >= 0 do
            if Values.equal(arr.items(i), a(1)) then found = i
            i -= 1
          VInt.of(found.toLong)
        case v => Err.eval(s"lastIndexOf: expected a string or array, got ${v.typeName}")
    }

    define("contains", 2, 2, "string", "contains(v, needle)",
      "Substring test for strings, membership test for arrays, key test for objects.") { a =>
      a(0) match
        case VStr(s)   => VBool.of(s.contains(argStr(a, 1, "contains")))
        case arr: VArr => VBool.of(arr.items.exists(Values.equal(_, a(1))))
        case o: VObj   => VBool.of(o.fields.contains(argStr(a, 1, "contains")))
        case v         => Err.eval(s"contains: expected a string, array or object, got ${v.typeName}")
    }

    define("startsWith", 2, 2, "string", "startsWith(s, prefix)", "Prefix test.") { a =>
      VBool.of(argStr(a, 0, "startsWith").startsWith(argStr(a, 1, "startsWith")))
    }
    define("endsWith", 2, 2, "string", "endsWith(s, suffix)", "Suffix test.") { a =>
      VBool.of(argStr(a, 0, "endsWith").endsWith(argStr(a, 1, "endsWith")))
    }

    define("reverse", 1, 1, "string", "reverse(v)", "Reverses a string or array (a copy).") { a =>
      a(0) match
        case VStr(s)   => VStr(reverseText(s))
        case arr: VArr => VArr.of(arr.items.reverse)
        case v         => Err.eval(s"reverse: expected a string or array, got ${v.typeName}")
    }

    define("compare", 2, 2, "string", "compare(a, b)",
      "Three-way comparison returning -1, 0 or 1.") { a =>
      VInt.of(math.signum(Values.compare(a(0), a(1))).toLong)
    }

  /**
   * Reverses text the way `StringBuilder.reverse` is specified to: code units go
   * backwards, then every surrogate pair is put back the right way round, so text
   * outside the BMP survives instead of turning into two unpaired halves. Written
   * out rather than delegated because the native StringBuilder skips the fix-up,
   * which is what made the interpreter disagree with the compiled runtime.
   */
  private def reverseText(s: String): String =
    val n = s.length
    val out = new Array[Char](n)
    var i = 0
    while i < n do
      out(i) = s.charAt(n - 1 - i)
      i += 1
    i = 0
    while i + 1 < n do
      val lo = out(i)
      if lo >= '\uDC00' && lo <= '\uDFFF' then
        val hi = out(i + 1)
        if hi >= '\uD800' && hi <= '\uDBFF' then
          out(i) = hi
          out(i + 1) = lo
          i += 1
      i += 1
    new String(out)

  private def indexOfImpl(a: Array[Value], fn: String): Value =
    val s = argStr(a, 0, fn)
    val needle = argStr(a, 1, fn)
    val from = if a.length > 2 then argIdx(a, 2, fn) else 0
    VInt.of(s.indexOf(needle, from).toLong)

  private def compile(pattern: String, fn: String): Pattern =
    try Pattern.compile(pattern)
    catch case e: PatternSyntaxException => Err.eval(s"$fn: invalid regular expression: ${e.getMessage}")

  private def regex(): Unit =
    define("matches", 2, 2, "regex", "matches(s, pattern)",
      "True when the whole string matches the regular expression.") { a =>
      VBool.of(compile(argStr(a, 1, "matches"), "matches").matcher(argStr(a, 0, "matches")).matches())
    }

    define("regexFind", 2, 2, "regex", "regexFind(s, pattern)",
      "First match as an array of [whole, group1, ...], or null when there is none.") { a =>
      val m = compile(argStr(a, 1, "regexFind"), "regexFind").matcher(argStr(a, 0, "regexFind"))
      if !m.find() then VNull else groupsOf(m)
    }

    define("regexFindAll", 2, 2, "regex", "regexFindAll(s, pattern)",
      "Every match, each as an array of [whole, group1, ...].") { a =>
      val m = compile(argStr(a, 1, "regexFindAll"), "regexFindAll").matcher(argStr(a, 0, "regexFindAll"))
      val out = new mutable.ArrayBuffer[Value]
      while m.find() do out += groupsOf(m)
      new VArr(out)
    }

    define("regexReplace", 3, 3, "regex", "regexReplace(s, pattern, replacement)",
      "Replaces every match; $1 style references are available in the replacement.") { a =>
      val m = compile(argStr(a, 1, "regexReplace"), "regexReplace").matcher(argStr(a, 0, "regexReplace"))
      try VStr(m.replaceAll(argStr(a, 2, "regexReplace")))
      catch
        case e: IllegalArgumentException => Err.eval(s"regexReplace: bad replacement: ${e.getMessage}")
        case e: IndexOutOfBoundsException => Err.eval(s"regexReplace: bad group reference: ${e.getMessage}")
    }

    define("regexSplit", 2, 2, "regex", "regexSplit(s, pattern)",
      "Splits on a regular expression.") { a =>
      val parts = compile(argStr(a, 1, "regexSplit"), "regexSplit").split(argStr(a, 0, "regexSplit"), -1)
      VArr.of(parts.iterator.map(VStr(_)))
    }

  private def groupsOf(m: Matcher): VArr =
    val out = new mutable.ArrayBuffer[Value](m.groupCount() + 1)
    var i = 0
    while i <= m.groupCount() do
      val g = m.group(i)
      out += (if g == null then VNull else VStr(g))
      i += 1
    new VArr(out)

  private def encoding(): Unit =
    define("base64Encode", 1, 1, "encoding", "base64Encode(s)", "Base64-encodes a UTF-8 string.") { a =>
      VStr(java.util.Base64.getEncoder.encodeToString(argStr(a, 0, "base64Encode").getBytes(StandardCharsets.UTF_8)))
    }

    define("base64Decode", 1, 1, "encoding", "base64Decode(s)", "Decodes Base64 into a UTF-8 string.") { a =>
      try VStr(new String(java.util.Base64.getDecoder.decode(argStr(a, 0, "base64Decode")), StandardCharsets.UTF_8))
      catch case _: IllegalArgumentException => Err.eval("base64Decode: input is not valid Base64")
    }

    define("hexEncode", 1, 1, "encoding", "hexEncode(s)", "Hex-encodes the UTF-8 bytes of a string.") { a =>
      VStr(Hex.encode(argStr(a, 0, "hexEncode").getBytes(StandardCharsets.UTF_8)))
    }

    define("hexDecode", 1, 1, "encoding", "hexDecode(s)", "Decodes hex into a UTF-8 string.") { a =>
      VStr(new String(Hex.decode(argStr(a, 0, "hexDecode")), StandardCharsets.UTF_8))
    }

    define("md5", 1, 1, "encoding", "md5(s)", "Hex MD5 digest of a string.")(a => digest(a, "MD5", "md5"))
    define("sha1", 1, 1, "encoding", "sha1(s)", "Hex SHA-1 digest of a string.")(a => digest(a, "SHA-1", "sha1"))
    define("sha256", 1, 1, "encoding", "sha256(s)", "Hex SHA-256 digest of a string.")(a => digest(a, "SHA-256", "sha256"))

    define("urlEncode", 1, 1, "encoding", "urlEncode(s)", "Percent-encodes a string for use in a URL.") { a =>
      VStr(java.net.URLEncoder.encode(argStr(a, 0, "urlEncode"), StandardCharsets.UTF_8.name()))
    }
    define("urlDecode", 1, 1, "encoding", "urlDecode(s)", "Decodes a percent-encoded string.") { a =>
      try VStr(java.net.URLDecoder.decode(argStr(a, 0, "urlDecode"), StandardCharsets.UTF_8.name()))
      catch case e: IllegalArgumentException => Err.eval(s"urlDecode: ${e.getMessage}")
    }

  private def digest(a: Array[Value], algorithm: String, fn: String): Value =
    try
      val md = java.security.MessageDigest.getInstance(algorithm)
      VStr(Hex.encode(md.digest(argStr(a, 0, fn).getBytes(StandardCharsets.UTF_8))))
    catch
      case _: java.security.NoSuchAlgorithmException =>
        Err.eval(s"$fn: $algorithm is not available in this build")

  // -------------------------------------------------------------------------
  // Binary data: byte arrays (arrays of ints 0..255)
  //
  // The string builtins above deal in text; anything that needs the exact bytes
  // — wire protocols, digests over binary, base64 of a payload that never was a
  // string — deals in arrays of ints instead, which both engines already share.
  // The C runtime implements every one of these as a primitive in sfl_codec.c,
  // ported statement for statement, so compiled programs behave identically.
  // -------------------------------------------------------------------------

  private val Base64Chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  private def b64Value(c: Char): Int =
    if c >= 'A' && c <= 'Z' then c - 'A'
    else if c >= 'a' && c <= 'z' then c - 'a' + 26
    else if c >= '0' && c <= '9' then c - '0' + 52
    else if c == '+' then 62
    else if c == '/' then 63
    else -1

  /**
   * Strict base64: the standard alphabet, optional but well-formed '=' padding,
   * nothing after it. Both engines run this same algorithm — java.util.Base64 is
   * deliberately not consulted, so its lenient corners cannot leak in.
   */
  private def base64ToBytes(s: String, fn: String): Array[Byte] =
    def bad(): Nothing = Err.eval(s"$fn: input is not valid Base64")
    val n = s.length
    var stop = 0
    while stop < n && s.charAt(stop) != '=' do
      if b64Value(s.charAt(stop)) < 0 then bad()
      stop += 1
    var j = stop
    while j < n do
      if s.charAt(j) != '=' then bad()
      j += 1
    val padded = n - stop
    if padded > 0 && (n % 4 != 0 || padded > 2 || stop % 4 == 0) then bad()
    if stop % 4 == 1 then bad()
    val out = new Array[Byte](stop / 4 * 3 + (stop % 4 match { case 2 => 1; case 3 => 2; case _ => 0 }))
    var acc = 0
    var held = 0
    var nb = 0
    var i = 0
    while i < stop do
      acc = (acc << 6) | b64Value(s.charAt(i))
      held += 1
      if held == 4 then
        out(nb) = (acc >> 16).toByte; out(nb + 1) = (acc >> 8).toByte; out(nb + 2) = acc.toByte
        nb += 3; acc = 0; held = 0
      i += 1
    if held == 2 then out(nb) = (acc >> 4).toByte
    if held == 3 then { out(nb) = (acc >> 10).toByte; out(nb + 1) = (acc >> 2).toByte }
    out

  private def bytesToBase64(b: Array[Byte]): String =
    val sb = new java.lang.StringBuilder((b.length + 2) / 3 * 4)
    var i = 0
    while i < b.length do
      val bits = ((b(i) & 0xff) << 16) |
        (if i + 1 < b.length then (b(i + 1) & 0xff) << 8 else 0) |
        (if i + 2 < b.length then b(i + 2) & 0xff else 0)
      sb.append(Base64Chars.charAt(bits >> 18))
      sb.append(Base64Chars.charAt((bits >> 12) & 0x3f))
      sb.append(if i + 1 < b.length then Base64Chars.charAt((bits >> 6) & 0x3f) else '=')
      sb.append(if i + 2 < b.length then Base64Chars.charAt(bits & 0x3f) else '=')
      i += 3
    sb.toString

  /** Validates and normalises the algorithm name: md5 | sha1 | sha256. */
  private def digestName(alg: String, fn: String): String = alg match
    case "md5"    => "MD5"
    case "sha1"   => "SHA-1"
    case "sha256" => "SHA-256"
    case _ =>
      Err.eval(s"$fn: unknown algorithm '$alg' (expected \"md5\", \"sha1\" or \"sha256\")")

  /** A fresh digest instance. The OpenSSL-backed MessageDigest this build ships
    * cannot be updated again once digest() has run, so every hash gets its own. */
  private def newMd(name: String): java.security.MessageDigest =
    java.security.MessageDigest.getInstance(name)

  /** HMAC (RFC 2104); md5, sha1 and sha256 all use a 64-byte block. The key pads
    * are computed once and reused, because pbkdf2 runs thousands of HMACs over
    * the same key and would otherwise rebuild them every round. */
  private def hmacPads(name: String, key: Array[Byte]): (Array[Byte], Array[Byte]) =
    val block = 64
    val k0 =
      val k = if key.length > block then newMd(name).digest(key) else key
      java.util.Arrays.copyOf(k, block)
    val inner = new Array[Byte](block)
    val outer = new Array[Byte](block)
    var i = 0
    while i < block do
      inner(i) = (k0(i) ^ 0x36).toByte
      outer(i) = (k0(i) ^ 0x5c).toByte
      i += 1
    (inner, outer)

  private def hmacRun(name: String, inner: Array[Byte], outer: Array[Byte],
                      msg: Array[Byte]): Array[Byte] =
    val md1 = newMd(name)
    md1.update(inner)
    md1.update(msg)
    val h1 = md1.digest()
    val md2 = newMd(name)
    md2.update(outer)
    md2.update(h1)
    md2.digest()

  private def binary(): Unit =
    define("utf8Encode", 1, 1, "encoding", "utf8Encode(s)",
      "The UTF-8 bytes of a string, as an array of ints; an unpaired surrogate encodes as '?'.") { a =>
      bytesValue(argStr(a, 0, "utf8Encode").getBytes(StandardCharsets.UTF_8))
    }

    define("utf8Decode", 1, 1, "encoding", "utf8Decode(bytes)",
      "Decodes UTF-8 bytes into a string; malformed sequences become U+FFFD.") { a =>
      VStr(new String(argBytes(a, 0, "utf8Decode"), StandardCharsets.UTF_8))
    }

    define("base64EncodeBytes", 1, 1, "encoding", "base64EncodeBytes(bytes)",
      "Base64-encodes a byte array.") { a =>
      VStr(bytesToBase64(argBytes(a, 0, "base64EncodeBytes")))
    }

    define("base64DecodeBytes", 1, 1, "encoding", "base64DecodeBytes(s)",
      "Decodes Base64 into a byte array.") { a =>
      bytesValue(base64ToBytes(argStr(a, 0, "base64DecodeBytes"), "base64DecodeBytes"))
    }

    define("hexEncodeBytes", 1, 1, "encoding", "hexEncodeBytes(bytes)",
      "Hex-encodes a byte array, lowercase.") { a =>
      VStr(Hex.encode(argBytes(a, 0, "hexEncodeBytes")))
    }

    define("hexDecodeBytes", 1, 1, "encoding", "hexDecodeBytes(s)",
      "Decodes a hex string (ASCII digits only) into a byte array.") { a =>
      val s = argStr(a, 0, "hexDecodeBytes")
      if s.length % 2 != 0 then
        Err.eval("hexDecodeBytes: hex string must have an even number of characters")
      def nib(k: Int): Int =
        val c = s.charAt(k)
        if c >= '0' && c <= '9' then c - '0'
        else if c >= 'a' && c <= 'f' then c - 'a' + 10
        else if c >= 'A' && c <= 'F' then c - 'A' + 10
        else Err.eval(s"hexDecodeBytes: invalid hex character at index $k")
      val out = new Array[Byte](s.length / 2)
      var i = 0
      while i < out.length do
        out(i) = ((nib(i * 2) << 4) | nib(i * 2 + 1)).toByte
        i += 1
      bytesValue(out)
    }

    define("digestBytes", 2, 2, "encoding", "digestBytes(algorithm, bytes)",
      "Digest of a byte array as bytes; algorithm is \"md5\", \"sha1\" or \"sha256\".") { a =>
      val name = digestName(argStr(a, 0, "digestBytes"), "digestBytes")
      bytesValue(newMd(name).digest(argBytes(a, 1, "digestBytes")))
    }

    define("hmacBytes", 3, 3, "encoding", "hmacBytes(algorithm, key, message)",
      "HMAC of a message with a key, both byte arrays, as bytes.") { a =>
      val name = digestName(argStr(a, 0, "hmacBytes"), "hmacBytes")
      val (inner, outer) = hmacPads(name, argBytes(a, 1, "hmacBytes"))
      bytesValue(hmacRun(name, inner, outer, argBytes(a, 2, "hmacBytes")))
    }

    define("pbkdf2", 4, 5, "encoding", "pbkdf2(algorithm, password, salt, iterations, length?)",
      "PBKDF2 key derivation over HMAC; password and salt are byte arrays.") { a =>
      val alg = argStr(a, 0, "pbkdf2")
      val name = digestName(alg, "pbkdf2")
      val hashLen = newMd(name).getDigestLength
      val password = argBytes(a, 1, "pbkdf2")
      val salt = argBytes(a, 2, "pbkdf2")
      val iterations = argInt(a, 3, "pbkdf2")
      if iterations < 1 then Err.eval("pbkdf2: iterations must be at least 1")
      if iterations > Int.MaxValue then Err.eval("pbkdf2: iterations is out of range")
      val dkLen = if a.length > 4 && (a(4) ne VNull) then argInt(a, 4, "pbkdf2") else hashLen.toLong
      if dkLen < 1 || dkLen > 4096 then Err.eval("pbkdf2: length must be 1..4096")
      val (inner, outer) = hmacPads(name, password)
      val out = new Array[Byte](dkLen.toInt)
      var written = 0
      var blockIx = 1
      while written < dkLen do
        val counted = java.util.Arrays.copyOf(salt, salt.length + 4)
        counted(salt.length) = (blockIx >> 24).toByte
        counted(salt.length + 1) = (blockIx >> 16).toByte
        counted(salt.length + 2) = (blockIx >> 8).toByte
        counted(salt.length + 3) = blockIx.toByte
        var u = hmacRun(name, inner, outer, counted)
        val t = u.clone()
        var c = 1L
        while c < iterations do
          u = hmacRun(name, inner, outer, u)
          var k = 0
          while k < t.length do { t(k) = (t(k) ^ u(k)).toByte; k += 1 }
          c += 1
        val take = math.min(t.length, dkLen.toInt - written)
        System.arraycopy(t, 0, out, written, take)
        written += take
        blockIx += 1
      bytesValue(out)
    }

    define("randomBytes", 1, 1, "encoding", "randomBytes(n)",
      "n cryptographically random bytes from the operating system.") { a =>
      val n = argInt(a, 0, "randomBytes")
      if n < 0 || n > 1048576 then Err.eval("randomBytes: n must be 0..1048576")
      val out = new Array[Byte](n.toInt)
      if n > 0 then
        val in =
          try new java.io.FileInputStream("/dev/urandom")
          catch case _: java.io.IOException => Err.eval("randomBytes: cannot open /dev/urandom")
        try
          var got = 0
          while got < out.length do
            val r = in.read(out, got, out.length - got)
            if r < 0 then Err.eval("randomBytes: cannot read /dev/urandom")
            got += r
        finally in.close()
      bytesValue(out)
    }

  /** Shared by `format` and `printf`. */
  def formatValues(fmt: String, args: Array[Value]): String =
    val boxed = args.map[Object] {
      case VInt(v)   => java.lang.Long.valueOf(v)
      case VFloat(v) => java.lang.Double.valueOf(v)
      case VStr(s)   => s
      case b: VBool  => java.lang.Boolean.valueOf(b.b)
      case VNull     => null
      case other     => other.repr
    }
    try String.format(fmt, boxed*)
    catch
      case e: java.util.IllegalFormatException =>
        Err.eval(s"format: ${e.getClass.getSimpleName} for pattern '$fmt'")
