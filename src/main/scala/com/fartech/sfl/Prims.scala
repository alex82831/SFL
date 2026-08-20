package com.fartech.sfl

/** A primitive of the C runtime: what the compiler may call directly. */
final case class Prim(
    name: String,
    symbol: String,
    signature: String,
    min: Int,
    max: Int,
    module: String
)

/**
 * The primitive table, read from the same `primitives.def` the C runtime includes.
 *
 * Keeping one list means the compiler can never emit a call to a symbol that does
 * not exist, or disagree with the runtime about an arity — a mismatch would only
 * show up as a link error or, worse, as a wrong answer.
 */
object Prims:
  private val Line =
    raw"""SFL_PRIM\("([^"]+)",\s*(\w+),\s*"((?:[^"\\]|\\.)*)",\s*(-?\d+),\s*(-?\d+),\s*(\w+)\)""".r

  val all: Seq[Prim] =
    Line.findAllMatchIn(RuntimeSources.text("primitives.def")).map { m =>
      Prim(m.group(1), m.group(2), m.group(3), m.group(4).toInt, m.group(5).toInt, m.group(6))
    }.toSeq

  val byName: Map[String, Prim] = all.map(p => p.name -> p).toMap

  /** C source files of the runtime, in the order they should be compiled. */
  def sources: Seq[(String, String)] = RuntimeSources.files.filter(_._1.endsWith(".c"))

  def modules: Seq[String] = all.map(_.module).distinct

  /**
   * Modules the runtime actually implements. A primitive whose module has no source
   * file is reported at compile time rather than left to fail at link time.
   */
  val available: Set[String] =
    RuntimeSources.files.map(_._1).collect { case n if n.startsWith("sfl_") && n.endsWith(".c") =>
      n.stripPrefix("sfl_").stripSuffix(".c")
    }.toSet

/** A function of the standard library, which is SFL compiled ahead of time. */
final case class StdFn(name: String, module: String, signature: String, min: Int, max: Int):
  /** Stable across programs, so the archive can be built once and reused. */
  def symbol: String = s"sflstd_$name"

/**
 * The part of the builtin library that is written in SFL itself.
 *
 * Everything here could have been another few thousand lines of C. Written in SFL
 * it is shorter, it is checked by the same test suite as the interpreter's own
 * version, and it exercises the compiler on real code. The modules are compiled
 * once into an archive and linked selectively, so a program that never sorts an
 * array does not carry a sort.
 */
object Stdlib:
  private val DefLine = raw"(?m)^def\s+(\w+)\s*\(([^)]*)\)".r

  /**
   * Reads a definition's parameter list the way FnProto would, so that a library
   * function reports the same signature and the same arity errors as one written
   * in C — `describe` and `signature` cannot tell the two apart.
   */
  private def describeParams(module: String, name: String, params: String): StdFn =
    val parts = params.split(",").map(_.trim).filter(_.nonEmpty)
    val hasRest = parts.lastOption.exists(_.startsWith("..."))
    val fixed = if hasRest then parts.length - 1 else parts.length
    val required = parts.take(fixed).count(p => !p.contains("="))
    val shown = parts.map { p =>
      if p.startsWith("...") then p
      else if p.contains("=") then p.takeWhile(c => c.isLetterOrDigit || c == '_') + "?"
      else p
    }
    StdFn(name, module, s"$name(${shown.mkString(", ")})", required,
      if hasRest then -1 else parts.length)

  /** Module name -> source text. */
  val modules: Seq[(String, String)] =
    StdlibSources.files.map((n, t) => (n.stripSuffix(".sfl"), t))

  /** Private helpers are part of a module's inside, not of the library's surface. */
  val all: Seq[StdFn] =
    modules.flatMap((module, text) =>
      DefLine.findAllMatchIn(text)
        .filterNot(_.group(1).startsWith("_"))
        .map(m => describeParams(module, m.group(1), m.group(2))))

  val byName: Map[String, StdFn] = all.map(f => f.name -> f).toMap

  def sourceOf(module: String): String =
    modules.collectFirst { case (m, t) if m == module => t }
      .getOrElse(throw new NoSuchElementException(s"no standard library module '$module'"))

  /**
   * Modules the given ones depend on. A library function may call another, so the
   * archive has to be walked to a fixed point before deciding what to link.
   */
  def closure(seed: Set[String]): Set[String] =
    var out = seed
    var changed = true
    while changed do
      changed = false
      for m <- out.toSeq do
        val text = sourceOf(m)
        for f <- all if !out.contains(f.module) && callsName(text, f.name) do
          out += f.module
          changed = true
    out

  private def callsName(text: String, name: String): Boolean =
    val i = text.indexOf(name)
    i >= 0 && raw"(?<![A-Za-z0-9_])$name\s*\(".r.findFirstIn(text).isDefined
