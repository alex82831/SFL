package com.fartech.sfl

/** A source location. `line` and `col` are 1-based; `offset` indexes into the raw source. */
final case class Pos(line: Int, col: Int, offset: Int)

object Pos:
  val none: Pos = Pos(0, 0, -1)

/**
 * Identifies the text a [[Pos]] refers to, so diagnostics can quote the offending line.
 *
 * `hidden` marks standard-library source the interpreter evaluates: errors raised in
 * it must surface at the user's call site, exactly as compiled programs behave —
 * library code there stores no positions at all — so a hidden ref never pins a
 * position and its frames stay out of tracebacks.
 */
final case class SourceRef(name: String, text: String, hidden: Boolean = false):
  def lineText(line: Int): String =
    if line <= 0 then ""
    else
      var i = 0
      var cur = 1
      val n = text.length
      while cur < line && i < n do
        if text.charAt(i) == '\n' then cur += 1
        i += 1
      if cur != line then ""
      else
        var j = i
        while j < n && text.charAt(j) != '\n' do j += 1
        text.substring(i, j).stripSuffix("\r")

object SourceRef:
  val unknown: SourceRef = SourceRef("<unknown>", "")

/** One entry of a runtime call stack, used to build tracebacks. */
final case class StackEntry(fn: String, line: Int, src: SourceRef)

/**
 * Base class for every error the language reports to the user. Errors carry enough
 * context to render a quoted source line with a caret rather than a bare message.
 */
sealed abstract class SflError(msg: String) extends RuntimeException(msg):
  def pos: Pos
  def source: SourceRef
  def kind: String

  /**
   * Extra lines shown under the excerpt: a suggested spelling, the signature that was
   * expected, the line an unclosed brace was opened on. These are what turn "something
   * is wrong here" into "here is what to change".
   */
  var notes: List[String] = Nil

  /** Adds a help line and returns `this`, so it reads well at a throw site. */
  def note(text: String): this.type =
    if text.nonEmpty then notes = notes :+ text
    this

  /** How many characters the caret should span; 0 means "the whole line". */
  var span: Int = 1

  /** `true` when the message can be rendered with a source excerpt. */
  def hasLocation: Boolean = pos.line > 0

  def render(color: Boolean): String =
    val sb = new StringBuilder
    val (red, dim, bold, reset, cyan) =
      if color then (Ansi.red, Ansi.dim, Ansi.bold, Ansi.reset, Ansi.cyan)
      else ("", "", "", "", "")
    sb.append(red).append(bold).append(kind).append(": ").append(reset).append(red).append(getMessage).append(reset)
    if hasLocation then
      val text = source.lineText(pos.line)
      sb.append('\n').append(dim).append("  --> ").append(source.name)
      sb.append(':').append(pos.line)
      if pos.col > 0 then sb.append(':').append(pos.col)
      sb.append(reset)
      if text.nonEmpty then
        val gutter = pos.line.toString
        val pad = " " * gutter.length
        sb.append('\n').append(dim).append(pad).append(" |").append(reset)
        sb.append('\n').append(dim).append(gutter).append(" | ").append(reset).append(text)
        sb.append('\n').append(dim).append(pad).append(" | ").append(reset)
        sb.append(red).append(marker(text)).append(reset)
    for n <- notes do
      sb.append('\n').append(cyan).append("  help: ").append(reset).append(dim).append(n).append(reset)
    sb.toString

  /**
   * Builds the `^^^` run under the excerpt. Without a column — which is the case for a
   * runtime error raised somewhere inside a statement — the whole statement is
   * underlined rather than pointing arbitrarily at its first character.
   */
  private def marker(text: String): String =
    val sb = new StringBuilder
    if pos.col <= 0 then
      val lead = text.takeWhile(c => c == ' ' || c == '\t')
      sb.append(lead)
      val width = math.max(1, text.trim.length)
      var i = 0
      while i < width do { sb.append('^'); i += 1 }
    else
      // Tabs and wide glyphs would otherwise misplace the caret.
      val upto = text.take(math.max(0, pos.col - 1))
      var i = 0
      while i < upto.length do
        sb.append(if upto.charAt(i) == '\t' then '\t' else ' ')
        i += 1
      var k = 0
      val width = math.max(1, span)
      while k < width do { sb.append('^'); k += 1 }
    sb.toString

final class ParseError(msg: String, val pos: Pos, val source: SourceRef) extends SflError(msg):
  def kind: String = "Syntax error"

/**
 * Raised by the ahead-of-time compiler. Separate from a syntax error because the program
 * is perfectly valid SFL — it just uses something the compiler cannot turn into machine
 * code, and the message needs to say so rather than implying the source is wrong.
 */
final class CompileError(msg: String, val pos: Pos, val source: SourceRef) extends SflError(msg):
  def kind: String = "Compile error"

/** Raised while running a program. Carries the SFL-level call stack, not the Scala one. */
final class EvalError(
    msg: String,
    var pos: Pos,
    var source: SourceRef,
    val trace: List[StackEntry] = Nil
) extends SflError(msg):
  def kind: String = "Runtime error"

  override def render(color: Boolean): String =
    val base = super.render(color)
    if trace.isEmpty then base
    else
      val dim = if color then Ansi.dim else ""
      val reset = if color then Ansi.reset else ""
      val sb = new StringBuilder(base)
      sb.append('\n').append(dim).append("  traceback (most recent call first):").append(reset)
      for e <- trace do
        sb.append('\n').append(dim).append("    at ").append(e.fn)
          .append(" (").append(e.src.name).append(':').append(e.line).append(')').append(reset)
      sb.toString

/** Thrown by the `exit(code)` builtin; the driver turns it into a process exit. */
final class ExitSignal(val code: Int) extends RuntimeException(s"exit($code)")

object Err:
  def parse(msg: String, pos: Pos, src: SourceRef): Nothing = throw new ParseError(msg, pos, src)

  /**
   * Turns "input ran out" into a locatable error. Batch runs need this: an unterminated
   * block is only actionable if the message says which one.
   */
  def fromIncomplete(e: IncompleteInput, src: SourceRef): ParseError =
    val err = new ParseError(e.reason, e.openedAt, src)
    if e.openedAt.line > 0 then err.note("this construct is never closed")
    else err.note("the input ends in the middle of a construct")
    err
  def eval(msg: String): Nothing = throw new EvalError(msg, Pos.none, SourceRef.unknown)
  def eval(msg: String, pos: Pos, src: SourceRef): Nothing = throw new EvalError(msg, pos, src)

  /** Raises a runtime error carrying help lines. Empty hints are dropped. */
  def evalHint(msg: String, hints: String*): Nothing =
    val e = new EvalError(msg, Pos.none, SourceRef.unknown)
    for h <- hints do e.note(h)
    throw e

  def parseHint(msg: String, pos: Pos, src: SourceRef, span: Int, hints: String*): Nothing =
    val e = new ParseError(msg, pos, src)
    e.span = span
    for h <- hints do e.note(h)
    throw e

  /** Formats a value for an error message, keeping it short enough to stay readable. */
  def show(v: Value): String =
    val s = v.repr
    if s.length <= 48 then s else s.take(45) + "..."

  /** "did you mean 'x'?" when something close exists. */
  def didYouMean(name: String, candidates: Iterable[String]): String =
    suggest(name, candidates).fold("")(s => s"did you mean '$s'?")

  /**
   * Suggests a replacement for an unknown identifier. Only names within a small edit
   * distance qualify, so the hint stays quiet when there is no plausible match, and
   * never the name itself — a reference that failed put its own spelling in the
   * namespace it was looking in, and proposing it back would say nothing.
   */
  def suggest(name: String, candidates: Iterable[String]): Option[String] =
    if name.isEmpty then None
    else
      val limit = if name.length <= 3 then 1 else 2
      var best: String = null
      var bestD = Int.MaxValue
      for c <- candidates do
        if c != name && math.abs(c.length - name.length) <= limit then
          val d = editDistance(name.toLowerCase, c.toLowerCase, limit)
          if d < bestD then
            bestD = d
            best = c
      if best != null && bestD <= limit then Some(best) else None

  /** Bounded Levenshtein distance; returns `limit + 1` as soon as the bound is exceeded. */
  private def editDistance(a: String, b: String, limit: Int): Int =
    val n = a.length
    val m = b.length
    if math.abs(n - m) > limit then return limit + 1
    var prev = new Array[Int](m + 1)
    var cur = new Array[Int](m + 1)
    var j = 0
    while j <= m do { prev(j) = j; j += 1 }
    var i = 1
    while i <= n do
      cur(0) = i
      var rowMin = cur(0)
      var k = 1
      while k <= m do
        val cost = if a.charAt(i - 1) == b.charAt(k - 1) then 0 else 1
        val v = math.min(math.min(cur(k - 1) + 1, prev(k) + 1), prev(k - 1) + cost)
        cur(k) = v
        if v < rowMin then rowMin = v
        k += 1
      if rowMin > limit then return limit + 1
      val t = prev; prev = cur; cur = t
      i += 1
    prev(m)

/** ANSI escapes, with a global switch so output stays clean when piped to a file. */
object Ansi:
  var enabled: Boolean = true

  final val ESC: Char = '\u001b'

  private inline def esc(code: String): String = if enabled then "\u001b[" + code else ""

  def reset: String = esc("0m")
  def bold: String = esc("1m")
  def dim: String = esc("2m")
  def italic: String = esc("3m")
  def underline: String = esc("4m")
  def reverse: String = esc("7m")

  def black: String = esc("30m")
  def red: String = esc("31m")
  def green: String = esc("32m")
  def yellow: String = esc("33m")
  def blue: String = esc("34m")
  def magenta: String = esc("35m")
  def cyan: String = esc("36m")
  def white: String = esc("37m")
  def gray: String = esc("90m")

  def brightRed: String = esc("91m")
  def brightGreen: String = esc("92m")
  def brightYellow: String = esc("93m")
  def brightBlue: String = esc("94m")
  def brightMagenta: String = esc("95m")
  def brightCyan: String = esc("96m")

  /** Clears the screen and homes the cursor. */
  def clearScreen: String = esc("H") + esc("2J")

  /** Removes escape sequences, e.g. to measure the printable width of a styled string. */
  def strip(s: String): String =
    if s.indexOf(ESC.toInt) < 0 then s
    else
      val sb = new StringBuilder(s.length)
      var i = 0
      while i < s.length do
        if s.charAt(i) == ESC then
          i += 1
          if i < s.length && s.charAt(i) == '[' then
            i += 1
            while i < s.length && !Character.isLetter(s.charAt(i)) do i += 1
            if i < s.length then i += 1
        else
          sb.append(s.charAt(i))
          i += 1
      sb.toString
