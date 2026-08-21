package com.fartech.sfl

import java.io.{File, FileOutputStream, InputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import scala.collection.mutable
import scala.scalanative.posix.time as ptime
import scala.scalanative.posix.timeOps.tmOps
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

object FileUtil:
  def read(f: File): String = read(f.getPath)

  def read(path: String): String =
    val file = new File(path)
    if !file.exists() then Err.eval(s"file not found: $path")
    if file.isDirectory then Err.eval(s"'$path' is a directory, not a file")
    try new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
    catch case e: java.io.IOException => Err.eval(s"cannot read '$path': ${e.getMessage}")

  def write(path: String, content: String, append: Boolean = false): Unit =
    try
      val out = new FileOutputStream(path, append)
      val w = new OutputStreamWriter(out, StandardCharsets.UTF_8)
      try w.write(content)
      finally w.close()
    catch case e: java.io.IOException => Err.eval(s"cannot write '$path': ${e.getMessage}")

  def exists(path: String): Boolean = new File(path).exists()

  def readStream(in: InputStream): String =
    val buf = new Array[Byte](8192)
    val out = new java.io.ByteArrayOutputStream
    var n = in.read(buf)
    while n > 0 do
      out.write(buf, 0, n)
      n = in.read(buf)
    new String(out.toByteArray, StandardCharsets.UTF_8)

/** JSON reading and writing over [[Value]]. */
object Json:

  def stringify(v: Value, pretty: Boolean): String =
    reject(v, new StringBuilder, 0)
    if pretty then Values.pretty(v) else v.repr

  /**
   * Refuses a value JSON cannot represent.
   *
   * Rendering a function produced `<fn lambda/0>`, which is not JSON and which this
   * module's own parser rejects — so the output looked like a result and was not one.
   * The path is reported because the offending value is usually nested.
   */
  private def reject(v: Value, path: StringBuilder, depth: Int): Unit = v match
    case a: VArr =>
      Values.checkNesting(depth)
      var i = 0
      while i < a.items.length do
        val mark = path.length
        path.append('[').append(i).append(']')
        reject(a.items(i), path, depth + 1)
        path.setLength(mark)
        i += 1
    case o: VObj =>
      Values.checkNesting(depth)
      for (k, field) <- o.fields do
        val mark = path.length
        path.append('.').append(k)
        reject(field, path, depth + 1)
        path.setLength(mark)
    case VFloat(d) if java.lang.Double.isNaN(d) || java.lang.Double.isInfinite(d) =>
      Err.evalHint(
        s"jsonStringify: cannot convert ${Values.formatDouble(d)} to JSON",
        if path.isEmpty then "JSON has no spelling for NaN or infinity"
        else s"the number at $path is ${Values.formatDouble(d)}, which JSON cannot spell"
      )
    case _: VFun | _: VNative | _: VIter | _: VHandle | _: VTuple =>
      Err.evalHint(
        s"jsonStringify: cannot convert ${v.typeName} to JSON",
        if v.isInstanceOf[VTuple] then
          if path.isEmpty then "a tuple has no JSON form; convert it with toArray(t)"
          else s"the tuple at $path has no JSON form; convert it with toArray(t)"
        else if path.isEmpty then "this value has no JSON form; replace it with data"
        else s"the value at $path has no JSON form; remove it or replace it with data"
      )
    case _ => ()

  def parse(text: String): Value =
    val p = new JsonParser(text)
    val v = p.parseValue()
    p.skipWs()
    if !p.atEnd then Err.eval(s"trailing content in JSON at offset ${p.offset}")
    v

private final class JsonParser(s: String):
  private var i = 0
  def offset: Int = i
  def atEnd: Boolean = i >= s.length

  def skipWs(): Unit =
    while i < s.length && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r') do
      i += 1

  private def fail(msg: String): Nothing = Err.eval(s"invalid JSON at offset $i: $msg")

  def parseValue(): Value =
    skipWs()
    if i >= s.length then fail("unexpected end of input")
    s.charAt(i) match
      case '{' => obj()
      case '[' => arr()
      case '"' => VStr(str())
      case 't' => lit("true", VBool.True)
      case 'f' => lit("false", VBool.False)
      case 'n' => lit("null", VNull)
      case c if c == '-' || (c >= '0' && c <= '9') => num()
      case c   => fail(s"unexpected character '$c'")

  private def lit(word: String, v: Value): Value =
    if s.regionMatches(i, word, 0, word.length) then { i += word.length; v }
    else fail(s"expected '$word'")

  private def obj(): Value =
    i += 1
    val m = mutable.LinkedHashMap.empty[String, Value]
    skipWs()
    if i < s.length && s.charAt(i) == '}' then { i += 1; return new VObj(m) }
    var go = true
    while go do
      skipWs()
      if i >= s.length || s.charAt(i) != '"' then fail("expected a string key")
      val k = str()
      skipWs()
      if i >= s.length || s.charAt(i) != ':' then fail("expected ':'")
      i += 1
      m(k) = parseValue()
      skipWs()
      if i < s.length && s.charAt(i) == ',' then i += 1
      else go = false
    if i >= s.length || s.charAt(i) != '}' then fail("expected '}'")
    i += 1
    new VObj(m)

  private def arr(): Value =
    i += 1
    val buf = new mutable.ArrayBuffer[Value]
    skipWs()
    if i < s.length && s.charAt(i) == ']' then { i += 1; return new VArr(buf) }
    var go = true
    while go do
      buf += parseValue()
      skipWs()
      if i < s.length && s.charAt(i) == ',' then i += 1
      else go = false
    if i >= s.length || s.charAt(i) != ']' then fail("expected ']'")
    i += 1
    new VArr(buf)

  private def str(): String =
    i += 1
    val sb = new StringBuilder
    var go = true
    while go do
      if i >= s.length then fail("unterminated string")
      val c = s.charAt(i)
      if c == '"' then { i += 1; go = false }
      else if c == '\\' then
        i += 1
        if i >= s.length then fail("unterminated escape")
        val e = s.charAt(i)
        i += 1
        e match
          case '"'  => sb.append('"')
          case '\\' => sb.append('\\')
          case '/'  => sb.append('/')
          case 'b'  => sb.append('\b')
          case 'f'  => sb.append('\f')
          case 'n'  => sb.append('\n')
          case 'r'  => sb.append('\r')
          case 't'  => sb.append('\t')
          case 'u' =>
            if i + 4 > s.length then fail("incomplete \\u escape")
            val hex = s.substring(i, i + 4)
            try sb.append(Integer.parseInt(hex, 16).toChar)
            catch case _: NumberFormatException => fail(s"bad \\u escape '$hex'")
            i += 4
          case other => fail(s"bad escape '\\$other'")
      else { sb.append(c); i += 1 }
    sb.toString

  private def num(): Value =
    val start = i
    if i < s.length && s.charAt(i) == '-' then i += 1
    while i < s.length && Character.isDigit(s.charAt(i)) do i += 1
    var isFloat = false
    if i < s.length && s.charAt(i) == '.' then
      isFloat = true
      i += 1
      while i < s.length && Character.isDigit(s.charAt(i)) do i += 1
    if i < s.length && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
      isFloat = true
      i += 1
      if i < s.length && (s.charAt(i) == '+' || s.charAt(i) == '-') then i += 1
      while i < s.length && Character.isDigit(s.charAt(i)) do i += 1
    val text = s.substring(start, i)
    if text.isEmpty || text == "-" then fail("malformed number")
    if isFloat then VFloat(text.toDouble)
    else
      try VInt.of(text.toLong)
      catch case _: NumberFormatException => VFloat(text.toDouble)

object Hex:
  private val digits = "0123456789abcdef".toCharArray

  def encode(bytes: Array[Byte]): String =
    val sb = new StringBuilder(bytes.length * 2)
    var i = 0
    while i < bytes.length do
      val b = bytes(i) & 0xff
      sb.append(digits(b >>> 4)).append(digits(b & 0x0f))
      i += 1
    sb.toString

  def decode(s: String): Array[Byte] =
    if s.length % 2 != 0 then Err.eval("hex string must have an even number of characters")
    val out = new Array[Byte](s.length / 2)
    var i = 0
    while i < out.length do
      val hi = Character.digit(s.charAt(i * 2), 16)
      val lo = Character.digit(s.charAt(i * 2 + 1), 16)
      if hi < 0 || lo < 0 then Err.eval(s"invalid hex character in '$s'")
      out(i) = ((hi << 4) | lo).toByte
      i += 1
    out

/**
 * Date formatting. Scala Native's javalib has no `java.time` or `java.util.Calendar`,
 * so this goes to the C library directly.
 */
object TimeUtil:
  def format(millis: Long, fmt: String, utc: Boolean): String =
    Zone.acquire { implicit z =>
      val tPtr = alloc[ptime.time_t]()
      !tPtr = (millis / 1000L).toSize
      val tmPtr = alloc[ptime.tm]()
      val res = if utc then ptime.gmtime_r(tPtr, tmPtr) else ptime.localtime_r(tPtr, tmPtr)
      if res == null then Err.eval("cannot convert timestamp to a calendar time")
      val cap = 512
      val buf = alloc[CChar](cap)
      val written = ptime.strftime(buf, cap.toUSize, toCString(fmt), tmPtr)
      if written.toInt == 0 then "" else fromCString(buf)
    }

  /** Broken-down local time as an object, for scripts that want the parts. */
  def parts(millis: Long, utc: Boolean): VObj =
    Zone.acquire { implicit z =>
      val tPtr = alloc[ptime.time_t]()
      !tPtr = (millis / 1000L).toSize
      val tmPtr = alloc[ptime.tm]()
      val res = if utc then ptime.gmtime_r(tPtr, tmPtr) else ptime.localtime_r(tPtr, tmPtr)
      if res == null then Err.eval("cannot convert timestamp to a calendar time")
      val m = mutable.LinkedHashMap.empty[String, Value]
      m("year") = VInt.of(tmPtr.tm_year + 1900L)
      m("month") = VInt.of(tmPtr.tm_mon + 1L)
      m("day") = VInt.of(tmPtr.tm_mday.toLong)
      m("hour") = VInt.of(tmPtr.tm_hour.toLong)
      m("minute") = VInt.of(tmPtr.tm_min.toLong)
      m("second") = VInt.of(tmPtr.tm_sec.toLong)
      m("weekday") = VInt.of(tmPtr.tm_wday.toLong)
      m("yearday") = VInt.of(tmPtr.tm_yday + 1L)
      m("millis") = VInt.of(millis)
      new VObj(m)
    }

/**
 * Runs external commands.
 *
 * Output goes to temporary files rather than pipes: reading two pipes sequentially from
 * a single thread deadlocks as soon as the child fills the one that is not being read.
 */
object Proc:
  def run(command: String, shell: Boolean): (Int, String, String) =
    val outFile = File.createTempFile("sfl-out", ".tmp")
    val errFile = File.createTempFile("sfl-err", ".tmp")
    try
      val isWindows = System.getProperty("os.name", "").toLowerCase.contains("win")
      val argv =
        if !shell then splitCommand(command)
        else if isWindows then java.util.Arrays.asList("cmd.exe", "/c", command)
        else java.util.Arrays.asList("/bin/sh", "-c", command)
      val pb = new ProcessBuilder(argv)
      pb.redirectOutput(outFile)
      pb.redirectError(errFile)
      val proc = pb.start()
      val code = proc.waitFor()
      (code, FileUtil.read(outFile.getPath), FileUtil.read(errFile.getPath))
    catch
      case e: java.io.IOException => Err.eval(s"cannot run command: ${e.getMessage}")
    finally
      outFile.delete()
      errFile.delete()

  /** Minimal argv splitter honouring single and double quotes. */
  def splitCommand(cmd: String): java.util.List[String] =
    val out = new java.util.ArrayList[String]()
    val sb = new StringBuilder
    var quote = ' '
    var i = 0
    var started = false
    while i < cmd.length do
      val c = cmd.charAt(i)
      if quote != ' ' then
        if c == quote then quote = ' ' else sb.append(c)
      else if c == '"' || c == '\'' then { quote = c; started = true }
      else if c == ' ' || c == '\t' then
        if started then { out.add(sb.toString); sb.setLength(0); started = false }
      else { sb.append(c); started = true }
      i += 1
    if started then out.add(sb.toString)
    out
