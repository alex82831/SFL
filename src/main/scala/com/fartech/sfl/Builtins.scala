package com.fartech.sfl

import scala.collection.mutable

/**
 * Registry of native functions.
 *
 * Builtins are ordinary values stored in globals, so they can be passed to `map`,
 * shadowed by user definitions, and completed in the REPL like anything else.
 */
object Builtins:
  private val registry = new mutable.LinkedHashMap[String, VNative]

  /**
   * The interpreter for the calling thread. Control-flow state (signals, the call stack,
   * the current source) lives in [[Interp]], so every thread needs its own; globals and
   * the builtin table are shared.
   */
  private val local = new ThreadLocal[Interp]

  def interp: Interp = local.get()
  def interp_=(i: Interp): Unit = local.set(i)

  def rt: Interp =
    val i = local.get()
    if i == null then Err.eval("no interpreter is running on this thread") else i

  /**
   * A handful of primitives exist only so the standard library can be written in
   * SFL. They are spelled with a leading `__` and kept out of anything a person
   * reads — `builtins()`, `help()`, completion — while the compiler still has to see
   * them, because compiled code calls them like any other builtin.
   */
  def isInternal(name: String): Boolean = name.startsWith("__")

  def all: Seq[VNative] = registry.values.filterNot(b => isInternal(b.name)).toSeq
  def names: Seq[String] = registry.keys.toSeq
  def publicNames: Seq[String] = registry.keys.filterNot(isInternal).toSeq
  def lookup(name: String): Option[VNative] = registry.get(name)
  def groups: Seq[String] = registry.values.map(_.group).toSeq.distinct

  def define(
      name: String,
      minArity: Int,
      maxArity: Int,
      group: String,
      signature: String,
      doc: String
  )(impl: Array[Value] => Value): Unit =
    if registry.contains(name) then
      throw new IllegalStateException(s"builtin '$name' is registered twice")
    registry(name) = new VNative(name, minArity, maxArity, group, signature, doc, impl)

  /** Registers every builtin into `g`. Safe to call on a fresh Globals. */
  def install(g: Globals): Unit =
    if registry.isEmpty then
      BuiltinsCore.register()
      BuiltinsText.register()
      BuiltinsMath.register()
      BuiltinsColl.register()
      BuiltinsFn.register()
      BuiltinsSys.register()
      BuiltinsThread.register()
      BuiltinsIpc.register()
      BuiltinsInternal.register()
    for (name, fn) <- registry do
      g.define(name, fn)
      g.setConst(g.id(name), false)
    // Shared-source stdlib functions: the registry row above carries the listing,
    // signature and doc (the compiled table reads the doc from there), while the
    // global slot holds a sentinel the first read replaces with the definition
    // loaded from the very stdlib source compiled programs link.
    for f <- Stdlib.all if StdlibModules.isShared(f.module) do
      g.define(f.name, new VLazyStdlib(f.module, f.name))
      g.setConst(g.id(f.name), false)

  // -------------------------------------------------------------------------
  // Argument accessors. Each reports the builtin and position on a type mismatch,
  // instead of the old code's blanket "invalid argument".
  // -------------------------------------------------------------------------

  private def bad(fn: String, i: Int, want: String, got: Value): Nothing =
    val sig = lookup(fn).map(b => s"the signature is ${b.signature}").getOrElse("")
    Err.evalHint(
      s"$fn: argument ${i + 1} must be $want, got ${got.typeName} ${Err.show(got)}",
      sig
    )

  def argStr(a: Array[Value], i: Int, fn: String): String = a(i) match
    case VStr(s) => s
    case v       => bad(fn, i, "a string", v)

  def argInt(a: Array[Value], i: Int, fn: String): Long = a(i) match
    case VInt(v)                                  => v
    case VFloat(v) if v == math.floor(v) && !java.lang.Double.isInfinite(v) => v.toLong
    case v                                        => bad(fn, i, "an integer", v)

  def argIdx(a: Array[Value], i: Int, fn: String): Int =
    val v = argInt(a, i, fn)
    if v > Int.MaxValue || v < Int.MinValue then Err.eval(s"$fn: index $v is out of range")
    v.toInt

  def argNum(a: Array[Value], i: Int, fn: String): Double = a(i) match
    case VInt(v)   => v.toDouble
    case VFloat(v) => v
    case v         => bad(fn, i, "a number", v)

  def argArr(a: Array[Value], i: Int, fn: String): VArr = a(i) match
    case v: VArr => v
    case v       => bad(fn, i, "an array", v)

  def argObj(a: Array[Value], i: Int, fn: String): VObj = a(i) match
    case v: VObj => v
    case v       => bad(fn, i, "an object", v)

  def argBool(a: Array[Value], i: Int, fn: String): Boolean = a(i) match
    case v: VBool => v.b
    case v        => bad(fn, i, "a boolean", v)

  def argFn(a: Array[Value], i: Int, fn: String): Value = a(i) match
    case v if Values.isCallable(v) => v
    case v                         => bad(fn, i, "a function", v)

  /**
   * A byte array argument: an SFL array whose elements are ints 0..255. This is the
   * value the binary builtins traffic in — sockets, digests, codecs — and the C
   * runtime's sfl_arg_bytes polices it with the same three messages, so a mistake
   * reads identically from either engine. An element is accepted exactly where
   * argInt would accept it, fractionless floats included.
   */
  def argBytes(a: Array[Value], i: Int, fn: String): Array[Byte] = a(i) match
    case arr: VArr =>
      val items = arr.items
      val out = new Array[Byte](items.length)
      var k = 0
      while k < items.length do
        val v = items(k) match
          case VInt(n) => n
          case VFloat(d) if d == math.floor(d) && !java.lang.Double.isInfinite(d) => d.toLong
          case other =>
            Err.eval(s"$fn: argument ${i + 1} has a non-byte element at index $k (${other.typeName})")
        if v < 0 || v > 255 then
          Err.eval(s"$fn: argument ${i + 1} has $v at index $k, expected 0..255")
        out(k) = v.toByte
        k += 1
      out
    case v => bad(fn, i, "a byte array", v)

  /** The SFL value for a run of bytes: an array of ints 0..255. */
  def bytesValue(b: Array[Byte], from: Int, until: Int): VArr =
    val out = new mutable.ArrayBuffer[Value](math.max(until - from, 1))
    var k = from
    while k < until do
      out += VInt.of(b(k) & 0xff)
      k += 1
    new VArr(out)

  def bytesValue(b: Array[Byte]): VArr = bytesValue(b, 0, b.length)

  def opt(a: Array[Value], i: Int): Value = if i < a.length then a(i) else VNull

  /** Calls an SFL function or builtin from inside a builtin. */
  def call(f: Value, args: Value*): Value = Call.invokeValue(f, args.toArray, rt)

  /** Numeric result that stays an int when both inputs were ints. */
  def numResult(d: Double, wasInt: Boolean): Value =
    if wasInt && d == math.floor(d) && !java.lang.Double.isInfinite(d) then VInt.of(d.toLong)
    else VFloat(d)

  def bothInt(a: Value, b: Value): Boolean = a.isInstanceOf[VInt] && b.isInstanceOf[VInt]

  /** Renders the reference table shown by `help()` and the REPL's `:builtins`. */
  def helpText(filter: String, color: Boolean): String =
    val sb = new StringBuilder
    val bold = if color then Ansi.bold else ""
    val cyan = if color then Ansi.cyan else ""
    val dim = if color then Ansi.dim else ""
    val reset = if color then Ansi.reset else ""
    val f = filter.toLowerCase
    val matching = registry.values.filter { b =>
      !isInternal(b.name) &&
      (f.isEmpty || b.name.toLowerCase.contains(f) || b.group.toLowerCase == f ||
        b.doc.toLowerCase.contains(f))
    }.toSeq
    if matching.isEmpty then return s"no builtin matches '$filter'"
    for (group, fns) <- matching.groupBy(_.group).toSeq.sortBy(_._1) do
      sb.append(bold).append(group).append(reset).append('\n')
      val width = fns.map(_.signature.length).max
      for b <- fns.sortBy(_.name) do
        sb.append("  ").append(cyan).append(b.signature.padTo(width, ' ')).append(reset)
        sb.append("  ").append(dim).append(b.doc).append(reset).append('\n')
      sb.append('\n')
    sb.toString.stripLineEnd

/**
 * Standard-library modules the interpreter evaluates from their SFL source — the
 * same source compiled programs link from libsflstd.a — instead of keeping a
 * native twin. One implementation, two engines, agreement by construction.
 *
 * Loading is lazy and happens on the first read of any of the module's names:
 * Builtins.install parks a [[VLazyStdlib]] sentinel in each of their global slots
 * and GlobalGet calls [[force]] when it meets one. The module's source parses with
 * a hidden SourceRef, so an error raised inside it surfaces at the user's call
 * site with no library frames — exactly how compiled programs behave, where
 * library code stores no positions at all.
 */
object StdlibModules:
  /** Modules whose interpreter implementation is the stdlib source itself. */
  private val sharedSource = Set("http")

  private val loaded = scala.collection.mutable.Set.empty[String]

  def isShared(module: String): Boolean = sharedSource.contains(module)

  /** Evaluates `module` into the globals once. Thread-safe; raises on a broken module. */
  def load(module: String): Unit = synchronized {
    if !loaded.contains(module) then
      loaded += module
      val name = s"stdlib/$module.sfl"
      try Builtins.call(Sfl.compileStdlibThunk(Stdlib.sourceOf(module), name))
      catch
        case e: Throwable =>
          loaded -= module
          throw e
  }

  /** GlobalGet's slow path: loads the sentinel's module and re-reads the slot. */
  def force(v: VLazyStdlib, id: Int, rt: Interp): Value =
    load(v.module)
    val defined = rt.globals.rawGet(id)
    if defined == null || defined.isInstanceOf[VLazyStdlib] then
      Err.eval(s"internal error: stdlib module '${v.module}' did not define '${v.name}'")
    defined
