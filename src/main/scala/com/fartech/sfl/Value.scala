package com.fartech.sfl

import scala.collection.mutable

/**
 * Runtime values.
 *
 * The old implementation carried every value as `Any` wrapped in `ValueExpr`, so each
 * operation had to unwrap an unbounded chain of wrappers and pattern-match on erased
 * generic types. This closed ADT lets the evaluator branch on a small, known set of
 * shapes and keeps the hot paths allocation-light.
 */
sealed abstract class Value:
  def typeName: String

  /** Truthiness used by `if`, `while` and the logical operators. */
  def truthy: Boolean

  /** Human-facing rendering: strings appear bare, as `print` should show them. */
  final def display: String =
    val sb = new StringBuilder
    write(sb, quoted = false)
    sb.toString

  /** Source-like rendering: strings are quoted and escaped. Used by the REPL echo. */
  final def repr: String =
    val sb = new StringBuilder
    write(sb, quoted = true)
    sb.toString

  def write(sb: StringBuilder, quoted: Boolean): Unit

case object VNull extends Value:
  def typeName: String = "null"
  def truthy: Boolean = false
  def write(sb: StringBuilder, quoted: Boolean): Unit = sb.append("null")

final class VBool private (val b: Boolean) extends Value:
  def typeName: String = "bool"
  def truthy: Boolean = b
  def write(sb: StringBuilder, quoted: Boolean): Unit = sb.append(if b then "true" else "false")
  override def equals(o: Any): Boolean = o match
    case that: VBool => that.b == b
    case _           => false
  override def hashCode: Int = if b then 1231 else 1237
  override def toString: String = if b then "true" else "false"

object VBool:
  val True = new VBool(true)
  val False = new VBool(false)
  def of(b: Boolean): VBool = if b then True else False

final case class VInt(v: Long) extends Value:
  def typeName: String = "int"
  def truthy: Boolean = v != 0L
  def write(sb: StringBuilder, quoted: Boolean): Unit = sb.append(v)

object VInt:
  // Loop counters and indices dominate integer allocation, so cache the common range.
  private final val Lo = -128
  private final val Hi = 1024
  private val cache: Array[VInt] =
    Array.tabulate(Hi - Lo + 1)(i => new VInt((i + Lo).toLong))

  def of(v: Long): VInt =
    if v >= Lo && v <= Hi then cache((v - Lo).toInt) else new VInt(v)

  val Zero: VInt = of(0)
  val One: VInt = of(1)

final case class VFloat(v: Double) extends Value:
  def typeName: String = "float"
  def truthy: Boolean = v != 0.0 && !java.lang.Double.isNaN(v)
  def write(sb: StringBuilder, quoted: Boolean): Unit = sb.append(Values.formatDouble(v))

final case class VStr(v: String) extends Value:
  def typeName: String = "string"
  def truthy: Boolean = v.nonEmpty
  def write(sb: StringBuilder, quoted: Boolean): Unit =
    if quoted then Values.quote(v, sb) else sb.append(v)

object VStr:
  val empty: VStr = VStr("")

final class VArr(val items: mutable.ArrayBuffer[Value]) extends Value:
  def typeName: String = "array"
  def truthy: Boolean = items.nonEmpty
  def length: Int = items.length
  def write(sb: StringBuilder, quoted: Boolean): Unit =
    sb.append('[')
    var i = 0
    while i < items.length do
      if i > 0 then sb.append(", ")
      items(i).write(sb, quoted = true)
      i += 1
    sb.append(']')

object VArr:
  def empty: VArr = new VArr(mutable.ArrayBuffer.empty[Value])
  def of(vs: IterableOnce[Value]): VArr = new VArr(mutable.ArrayBuffer.from(vs))
  def sized(n: Int): VArr = new VArr(new mutable.ArrayBuffer[Value](math.max(n, 4)))

/**
 * An immutable, fixed-length sequence — what a function returns when it returns
 * several things. Distinct from an array on purpose: it cannot be mutated or
 * grown, it compares lexicographically, and a tuple pattern matches only a tuple.
 */
final class VTuple(val items: Array[Value]) extends Value:
  def typeName: String = "tuple"
  def truthy: Boolean = true // always at least two elements; the parser has no 0/1-tuples
  def write(sb: StringBuilder, quoted: Boolean): Unit =
    sb.append('(')
    var i = 0
    while i < items.length do
      if i > 0 then sb.append(", ")
      items(i).write(sb, quoted = true)
      i += 1
    sb.append(')')

/** Objects keep insertion order, so iteration and JSON output are deterministic. */
final class VObj(val fields: mutable.LinkedHashMap[String, Value]) extends Value:
  def typeName: String = "object"
  def truthy: Boolean = fields.nonEmpty
  def write(sb: StringBuilder, quoted: Boolean): Unit =
    sb.append('{')
    var first = true
    for (k, v) <- fields do
      if !first then sb.append(", ")
      first = false
      Values.quote(k, sb)
      sb.append(": ")
      v.write(sb, quoted = true)
    sb.append('}')

object VObj:
  def empty: VObj = new VObj(mutable.LinkedHashMap.empty[String, Value])
  def of(pairs: IterableOnce[(String, Value)]): VObj =
    new VObj(mutable.LinkedHashMap.from(pairs))

/** A user-defined function together with the frame it closed over. */
final class VFun(val proto: FnProto, val env: Frame) extends Value:
  def typeName: String = "function"
  def truthy: Boolean = true
  def write(sb: StringBuilder, quoted: Boolean): Unit =
    sb.append("<fn ").append(proto.name).append('/').append(proto.params.length).append('>')

/**
 * A standard-library function whose implementation is the SFL source shared with
 * the compiler, not yet loaded. Builtins.install parks one of these in the global
 * slot of every such name; the first read of any of them evaluates the module into
 * the globals, which replaces every sentinel of that module with its real
 * definition. GlobalGet forces them, so user code can never observe one.
 */
final class VLazyStdlib(val module: String, val name: String) extends Value:
  def typeName: String = "function"
  def truthy: Boolean = true
  def write(sb: StringBuilder, quoted: Boolean): Unit =
    sb.append("<builtin ").append(name).append('>')

/** A builtin implemented in Scala. `maxArity` of -1 means variadic. */
final class VNative(
    val name: String,
    val minArity: Int,
    val maxArity: Int,
    val group: String,
    val signature: String,
    val doc: String,
    val impl: Array[Value] => Value
) extends Value:
  def typeName: String = "function"
  def truthy: Boolean = true
  def write(sb: StringBuilder, quoted: Boolean): Unit =
    sb.append("<builtin ").append(name).append('>')

/** Handle returned by `iter`, consumed by `hasNext` / `iterNext`. */
final class VIter(val it: Iterator[Value], val over: String) extends Value:
  def typeName: String = "iterator"
  def truthy: Boolean = true
  def write(sb: StringBuilder, quoted: Boolean): Unit =
    sb.append("<iterator over ").append(over).append('>')

/**
 * An opaque handle to something the runtime owns: a thread, a channel, a lock, a
 * subprocess, a socket, an open file. `kind` is what `typeOf` reports, so scripts can
 * tell them apart, and `payload` is the Scala object the builtins operate on.
 */
final class VHandle(val kind: String, val payload: Any, val label: String) extends Value:
  def typeName: String = kind
  def truthy: Boolean = true
  def write(sb: StringBuilder, quoted: Boolean): Unit =
    sb.append('<').append(kind)
    if label.nonEmpty then sb.append(' ').append(label)
    sb.append('>')

/**
 * An activation record. Locals live in `slots`, addressed by an index the resolver
 * computed, so variable access never hashes a name at run time. `parent` links to the
 * frame the function was defined in, which is what makes closures work.
 */
final class Frame(val slots: Array[Value], val parent: Frame):
  /** Walks `depth` links outward. Depth is a compile-time constant per reference. */
  def up(depth: Int): Frame =
    var f = this
    var d = depth
    while d > 0 do
      f = f.parent
      d -= 1
    f

object Frame:
  val root: Frame = new Frame(new Array[Value](0), null)

object Values:

  def quote(s: String, sb: StringBuilder): Unit =
    sb.append('"')
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      c match
        case '"'  => sb.append("\\\"")
        case '\\' => sb.append("\\\\")
        case '\n' => sb.append("\\n")
        case '\r' => sb.append("\\r")
        case '\t' => sb.append("\\t")
        case '\b' => sb.append("\\b")
        case '\f' => sb.append("\\f")
        case _ =>
          if c < 0x20 then sb.append("\\u%04x".format(c.toInt))
          else sb.append(c)
      i += 1
    sb.append('"')

  def quote(s: String): String =
    val sb = new StringBuilder(s.length + 2)
    quote(s, sb)
    sb.toString

  /** Prints doubles without Scala's `E` notation for values people actually type. */
  def formatDouble(d: Double): String =
    if java.lang.Double.isNaN(d) then "NaN"
    else if java.lang.Double.isInfinite(d) then if d > 0 then "Infinity" else "-Infinity"
    else
      val s = d.toString
      if s.endsWith(".0") then s else s

  inline def isNum(v: Value): Boolean = v.isInstanceOf[VInt] || v.isInstanceOf[VFloat]

  def toDouble(v: Value): Double = v match
    case VInt(x)   => x.toDouble
    case VFloat(x) => x
    case _         => Err.eval(s"expected a number, got ${v.typeName}")

  def toLong(v: Value): Long = v match
    case VInt(x)   => x
    case VFloat(x) => x.toLong
    case _         => Err.eval(s"expected a number, got ${v.typeName}")

  def toIndex(v: Value, what: String): Int = v match
    case VInt(x) =>
      if x > Int.MaxValue || x < Int.MinValue then Err.eval(s"$what out of range: $x")
      x.toInt
    case VFloat(x) if x == math.floor(x) && !java.lang.Double.isInfinite(x) => x.toInt
    case _ => Err.eval(s"$what must be an integer, got ${v.typeName}")

  /**
   * Structural equality as the language sees it. Ints and floats compare by numeric
   * value so `1 == 1.0` holds; containers compare element-wise.
   */
  def equal(a: Value, b: Value): Boolean =
    if a eq b then true
    else
      a match
        case VInt(x) =>
          b match
            case VInt(y)   => x == y
            case VFloat(y) => x.toDouble == y
            case _         => false
        case VFloat(x) =>
          b match
            case VInt(y)   => x == y.toDouble
            case VFloat(y) => x == y
            case _         => false
        case VStr(x)  => b match { case VStr(y) => x == y; case _ => false }
        case x: VBool => b match { case y: VBool => x.b == y.b; case _ => false }
        case VNull    => b eq VNull
        case x: VArr =>
          b match
            case y: VArr =>
              if x.items.length != y.items.length then false
              else
                var i = 0
                var eq = true
                while eq && i < x.items.length do
                  if !equal(x.items(i), y.items(i)) then eq = false
                  i += 1
                eq
            case _ => false
        case x: VTuple =>
          b match
            case y: VTuple =>
              if x.items.length != y.items.length then false
              else
                var i = 0
                var eq = true
                while eq && i < x.items.length do
                  if !equal(x.items(i), y.items(i)) then eq = false
                  i += 1
                eq
            case _ => false
        case x: VObj =>
          b match
            case y: VObj =>
              if x.fields.size != y.fields.size then false
              else x.fields.forall { case (k, v) => y.fields.get(k).exists(equal(v, _)) }
            case _ => false
        case _ => false

  /** Ordering for `<`, `<=`, `>`, `>=`. Only numbers, strings and bools are ordered. */
  def compare(a: Value, b: Value): Int = a match
    case VInt(x) =>
      b match
        case VInt(y)   => java.lang.Long.compare(x, y)
        case VFloat(y) => java.lang.Double.compare(x.toDouble, y)
        case _         => incomparable(a, b)
    case VFloat(x) =>
      b match
        case VInt(y)   => java.lang.Double.compare(x, y.toDouble)
        case VFloat(y) => java.lang.Double.compare(x, y)
        case _         => incomparable(a, b)
    case VStr(x)  => b match { case VStr(y) => x.compareTo(y); case _ => incomparable(a, b) }
    case x: VBool => b match { case y: VBool => java.lang.Boolean.compare(x.b, y.b); case _ => incomparable(a, b) }
    case x: VTuple =>
      b match
        case y: VTuple =>
          // Lexicographic, length last — so ("b", 1) sorts after ("a", 9), which is
          // what sorting an array of pairs needs.
          val n = math.min(x.items.length, y.items.length)
          var i = 0
          while i < n do
            val c = compare(x.items(i), y.items(i))
            if c != 0 then return c
            i += 1
          Integer.compare(x.items.length, y.items.length)
        case _ => incomparable(a, b)
    case _        => incomparable(a, b)

  private def incomparable(a: Value, b: Value): Nothing =
    Err.eval(s"cannot compare ${a.typeName} with ${b.typeName}")

  def isCallable(v: Value): Boolean = v.isInstanceOf[VFun] || v.isInstanceOf[VNative]

  /** Multi-line rendering used by the REPL and by `jsonStringify(v, true)`. */
  def pretty(v: Value, indent: Int = 2): String =
    val sb = new StringBuilder
    prettyInto(v, sb, indent, 0)
    sb.toString

  private def prettyInto(v: Value, sb: StringBuilder, indent: Int, depth: Int): Unit =
    def pad(n: Int): Unit = { var i = 0; while i < n * indent do { sb.append(' '); i += 1 } }
    v match
      case a: VArr if a.items.nonEmpty =>
        sb.append("[\n")
        var i = 0
        while i < a.items.length do
          pad(depth + 1)
          prettyInto(a.items(i), sb, indent, depth + 1)
          if i < a.items.length - 1 then sb.append(',')
          sb.append('\n')
          i += 1
        pad(depth)
        sb.append(']')
      case o: VObj if o.fields.nonEmpty =>
        sb.append("{\n")
        var i = 0
        val n = o.fields.size
        for (k, fv) <- o.fields do
          pad(depth + 1)
          quote(k, sb)
          sb.append(": ")
          prettyInto(fv, sb, indent, depth + 1)
          if i < n - 1 then sb.append(',')
          sb.append('\n')
          i += 1
        pad(depth)
        sb.append('}')
      case other => other.write(sb, quoted = true)
