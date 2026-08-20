package com.fartech.sfl

import java.io.File
import scala.collection.mutable

/** Result of asking the editor for a line. */
enum Input:
  case Line(text: String)
  case Interrupted
  case Eof

/** Command history, persisted between sessions. */
final class History(val file: File, val limit: Int = 1000):
  private val entries = mutable.ArrayBuffer.empty[String]

  def all: Seq[String] = entries.toSeq
  def size: Int = entries.length
  def apply(i: Int): String = entries(i)

  def add(line: String): Unit =
    val t = line.trim
    if t.nonEmpty && (entries.isEmpty || entries.last != t) then
      entries += t
      if entries.length > limit then entries.remove(0, entries.length - limit)

  def load(): Unit =
    if file.isFile then
      try
        for l <- FileUtil.read(file).split('\n') if l.trim.nonEmpty do entries += l
        if entries.length > limit then entries.remove(0, entries.length - limit)
      catch case _: EvalError => () // an unreadable history file is not worth reporting

  def save(): Unit =
    try FileUtil.write(file.getPath, entries.mkString("\n") + "\n")
    catch case _: EvalError => ()

/**
 * An interactive line editor: cursor movement, history, tab completion and live
 * syntax highlighting, drawn with ANSI escapes over a raw-mode terminal.
 */
final class LineEditor(history: History):
  private val buf = mutable.ArrayBuffer.empty[Int] // code points
  private var cursor = 0
  private var lastCursorRow = 0
  private var historyIndex = -1
  private var stash = ""

  private def out = Console.out

  /**
   * Reads one line. Falls back to blocking `readLine` when raw mode is unavailable,
   * so piped input and non-interactive shells still work.
   */
  def read(prompt: String): Input =
    if !Terminal.inRawMode then
      out.print(Ansi.strip(prompt))
      out.flush()
      val l = scala.io.StdIn.readLine()
      return if l == null then Input.Eof else Input.Line(l)

    buf.clear()
    cursor = 0
    lastCursorRow = 0
    historyIndex = -1
    stash = ""
    render(prompt)

    var result: Input = null
    while result == null do
      Terminal.readKey() match
        case Key.Enter =>
          out.print("\n")
          out.flush()
          result = Input.Line(text)

        case Key.Ch(cp) =>
          buf.insert(cursor, cp)
          cursor += 1
          render(prompt)

        case Key.Backspace =>
          if cursor > 0 then
            cursor -= 1
            buf.remove(cursor)
            render(prompt)

        case Key.Delete =>
          if cursor < buf.length then
            buf.remove(cursor)
            render(prompt)

        case Key.Left  => if cursor > 0 then { cursor -= 1; render(prompt) }
        case Key.Right => if cursor < buf.length then { cursor += 1; render(prompt) }
        case Key.Home  => cursor = 0; render(prompt)
        case Key.End   => cursor = buf.length; render(prompt)
        case Key.CtrlA => cursor = 0; render(prompt)
        case Key.CtrlE => cursor = buf.length; render(prompt)

        case Key.WordLeft =>
          cursor = wordStart(cursor)
          render(prompt)
        case Key.WordRight =>
          cursor = wordEnd(cursor)
          render(prompt)

        case Key.CtrlK =>
          if cursor < buf.length then
            buf.remove(cursor, buf.length - cursor)
            render(prompt)

        case Key.CtrlU =>
          if cursor > 0 then
            buf.remove(0, cursor)
            cursor = 0
            render(prompt)

        case Key.CtrlW =>
          val start = wordStart(cursor)
          if start < cursor then
            buf.remove(start, cursor - start)
            cursor = start
            render(prompt)

        case Key.CtrlL =>
          out.print(Ansi.clearScreen)
          lastCursorRow = 0
          render(prompt)

        case Key.CtrlC =>
          out.print(Ansi.dim + "^C" + Ansi.reset + "\n")
          out.flush()
          result = Input.Interrupted

        case Key.CtrlD =>
          if buf.isEmpty then
            out.print("\n")
            out.flush()
            result = Input.Eof
          else if cursor < buf.length then
            buf.remove(cursor)
            render(prompt)

        case Key.Up   => recall(prompt, older = true)
        case Key.Down => recall(prompt, older = false)

        case Key.Tab => complete(prompt)

        case Key.Eof =>
          result = Input.Eof

        case Key.Escape | Key.Unknown => ()

    result

  private def text: String =
    val sb = new StringBuilder
    var i = 0
    while i < buf.length do
      sb.appendAll(Character.toChars(buf(i)))
      i += 1
    sb.toString

  private def setText(s: String): Unit =
    buf.clear()
    var i = 0
    while i < s.length do
      val cp = s.codePointAt(i)
      buf += cp
      i += Character.charCount(cp)
    cursor = buf.length

  // -------------------------------------------------------------------------
  // Rendering
  // -------------------------------------------------------------------------

  private def render(prompt: String): Unit =
    val w = math.max(Terminal.width, 20)
    val promptWidth = Terminal.displayWidth(prompt)

    // Move back to the start of the previous render and clear everything below.
    val sb = new StringBuilder
    if lastCursorRow > 0 then sb.append(s"\u001b[${lastCursorRow}A")
    sb.append('\r').append("\u001b[J")

    sb.append(prompt).append(Highlight.line(text))

    val total = promptWidth + widthOf(0, buf.length)
    // A cursor sitting exactly at the right margin is ambiguous; force the wrap.
    if total > 0 && total % w == 0 then sb.append('\n')
    val endRow = total / w

    val target = promptWidth + widthOf(0, cursor)
    val targetRow = target / w
    val targetCol = target % w

    if targetRow < endRow then sb.append(s"\u001b[${endRow - targetRow}A")
    else if targetRow > endRow then sb.append(s"\u001b[${targetRow - endRow}B")
    sb.append('\r')
    if targetCol > 0 then sb.append(s"\u001b[${targetCol}C")

    lastCursorRow = targetRow
    out.print(sb.toString)
    out.flush()

  private def widthOf(from: Int, to: Int): Int =
    var w = 0
    var i = from
    while i < to do
      w += Terminal.charWidth(buf(i))
      i += 1
    w

  // -------------------------------------------------------------------------
  // History
  // -------------------------------------------------------------------------

  /** Up/Down walk history; with text already typed they walk matching entries only. */
  private def recall(prompt: String, older: Boolean): Unit =
    if history.size == 0 then return
    if historyIndex < 0 then stash = text
    val prefix = stash
    var i = if historyIndex < 0 then history.size else historyIndex
    var found = -1
    if older then
      i -= 1
      while found < 0 && i >= 0 do
        if prefix.isEmpty || history(i).startsWith(prefix) then found = i
        i -= 1
      if found < 0 then return
    else
      i += 1
      while found < 0 && i < history.size do
        if prefix.isEmpty || history(i).startsWith(prefix) then found = i
        i += 1
      if found < 0 then
        historyIndex = -1
        setText(stash)
        render(prompt)
        return
    historyIndex = found
    setText(history(found))
    render(prompt)

  // -------------------------------------------------------------------------
  // Completion
  // -------------------------------------------------------------------------

  private def wordStart(from: Int): Int =
    var i = from
    while i > 0 && !isWordChar(buf(i - 1)) do i -= 1
    while i > 0 && isWordChar(buf(i - 1)) do i -= 1
    i

  private def wordEnd(from: Int): Int =
    var i = from
    while i < buf.length && !isWordChar(buf(i)) do i += 1
    while i < buf.length && isWordChar(buf(i)) do i += 1
    i

  private def isWordChar(cp: Int): Boolean =
    cp == '_' || cp == '$' || Character.isLetterOrDigit(cp)

  private def complete(prompt: String): Unit =
    var start = cursor
    while start > 0 && isWordChar(buf(start - 1)) do start -= 1
    // Right after a dot there is no partial word yet, but field names still apply.
    val afterDot = start > 0 && buf(start - 1) == '.'.toInt
    val sb = new StringBuilder
    var i = start
    while i < cursor do
      sb.appendAll(Character.toChars(buf(i)))
      i += 1
    val partial = sb.toString
    if partial.isEmpty && !afterDot then
      // No word to complete: insert two spaces, which is what indentation wants.
      buf.insert(cursor, ' '.toInt)
      buf.insert(cursor + 1, ' '.toInt)
      cursor += 2
      render(prompt)
      return

    val candidates = LineEditor.candidates(partial, textBefore(start))
    if candidates.isEmpty then return
    val insert =
      if candidates.length == 1 then candidates.head
      else commonPrefix(candidates)

    if insert.length > partial.length then
      buf.remove(start, cursor - start)
      var k = start
      var j = 0
      while j < insert.length do
        val cp = insert.codePointAt(j)
        buf.insert(k, cp)
        k += 1
        j += Character.charCount(cp)
      cursor = k
      render(prompt)
    else if candidates.length > 1 then
      out.print("\n")
      out.print(LineEditor.columns(candidates, math.max(Terminal.width, 20)))
      lastCursorRow = 0
      render(prompt)

  /** The text to the left of `upto`, used to decide what kind of name fits here. */
  private def textBefore(upto: Int): String =
    val sb = new StringBuilder
    var i = 0
    while i < upto do
      sb.appendAll(Character.toChars(buf(i)))
      i += 1
    sb.toString

  private def commonPrefix(xs: Seq[String]): String =
    var prefix = xs.head
    for x <- xs.tail do
      var i = 0
      while i < prefix.length && i < x.length && prefix.charAt(i) == x.charAt(i) do i += 1
      prefix = prefix.substring(0, i)
    prefix

object LineEditor:
  /**
   * What could go here. The text to the left decides: after `obj.` only that object's
   * keys make sense, after `:doc` only builtin names do, and otherwise anything nameable.
   *
   * Field completion reads the object out of the globals rather than evaluating the
   * receiver, so pressing Tab can never run the user's code.
   */
  def candidates(partial: String, before: String = ""): Seq[String] =
    val trimmed = before.trim
    if trimmed.endsWith(".") then
      val receiver = trimmed.dropRight(1).reverse.takeWhile(c => c.isLetterOrDigit || c == '_' || c == '$').reverse
      Sfl.globals.valueOf(receiver) match
        case Some(o: VObj) => o.fields.keys.filter(_.startsWith(partial)).toSeq.sorted
        case _             => Seq.empty
    else if trimmed.startsWith(":doc") || trimmed.startsWith(":builtins") then
      Builtins.publicNames.filter(_.startsWith(partial)).sorted
    else
      val names =
        Tok.reserved.toSeq ++ Builtins.publicNames ++ Sfl.globals.definedNames ++ Repl.commandNames
      names.filter(_.startsWith(partial)).distinct.sorted

  /** Lays names out in aligned columns, the way a shell lists completions. */
  def columns(items: Seq[String], width: Int): String =
    val colWidth = items.map(_.length).max + 2
    val perRow = math.max(1, width / colWidth)
    val sb = new StringBuilder
    var i = 0
    while i < items.length do
      sb.append(Ansi.cyan).append(items(i).padTo(colWidth, ' ')).append(Ansi.reset)
      i += 1
      if i % perRow == 0 then sb.append('\n')
    if items.length % perRow != 0 then sb.append('\n')
    sb.toString
