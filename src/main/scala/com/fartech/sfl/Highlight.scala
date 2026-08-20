package com.fartech.sfl

/**
 * Colourises SFL source for the REPL.
 *
 * This deliberately does not reuse [[Lexer]]: the editor highlights text that is still
 * being typed, so it must cope with unterminated strings and half-written comments
 * instead of raising an error.
 */
object Highlight:

  def line(s: String): String =
    if !Ansi.enabled then s
    else
      val sb = new StringBuilder(s.length * 2)
      var i = 0
      val n = s.length
      while i < n do
        val c = s.charAt(i)
        if c == '/' && i + 1 < n && s.charAt(i + 1) == '/' then
          sb.append(Ansi.gray).append(s.substring(i)).append(Ansi.reset)
          i = n
        else if c == '/' && i + 1 < n && s.charAt(i + 1) == '*' then
          val end = s.indexOf("*/", i + 2)
          val stop = if end < 0 then n else end + 2
          sb.append(Ansi.gray).append(s.substring(i, stop)).append(Ansi.reset)
          i = stop
        else if c == '"' || c == '\'' then
          val stop = stringEnd(s, i, c)
          sb.append(Ansi.green).append(s.substring(i, stop)).append(Ansi.reset)
          i = stop
        else if Character.isDigit(c) && !isIdentPart(prevChar(s, i)) then
          var j = i
          while j < n && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_') do
            j += 1
          sb.append(Ansi.yellow).append(s.substring(i, j)).append(Ansi.reset)
          i = j
        else if isIdentStart(c) then
          var j = i
          while j < n && isIdentPart(s.charAt(j)) do j += 1
          val word = s.substring(i, j)
          val color =
            if Tok.reserved.contains(word) then Ansi.magenta
            else if isCallSite(s, j) && known(word) then Ansi.cyan
            else if known(word) then Ansi.brightBlue
            else ""
          if color.isEmpty then sb.append(word)
          else sb.append(color).append(word).append(Ansi.reset)
          i = j
        else if c == '|' && i + 1 < n && s.charAt(i + 1) == '>' then
          // The pipeline operator reads as one symbol, so colour it as one.
          sb.append(Ansi.brightCyan).append("|>").append(Ansi.reset)
          i += 2
        else if "+-*/%=<>!&|".indexOf(c.toInt) >= 0 then
          sb.append(Ansi.brightMagenta).append(c).append(Ansi.reset)
          i += 1
        else if "()[]{}".indexOf(c.toInt) >= 0 then
          sb.append(Ansi.dim).append(c).append(Ansi.reset)
          i += 1
        else
          sb.append(c)
          i += 1
      sb.toString

  private def prevChar(s: String, i: Int): Char = if i == 0 then ' ' else s.charAt(i - 1)

  private def isCallSite(s: String, from: Int): Boolean =
    var j = from
    while j < s.length && s.charAt(j) == ' ' do j += 1
    j < s.length && s.charAt(j) == '('

  private def known(word: String): Boolean =
    Builtins.lookup(word).isDefined || Sfl.globals.valueOf(word).isDefined

  private def stringEnd(s: String, start: Int, quote: Char): Int =
    if quote == '"' && start + 2 < s.length && s.charAt(start + 1) == '"' && s.charAt(start + 2) == '"' then
      val end = s.indexOf("\"\"\"", start + 3)
      if end < 0 then s.length else end + 3
    else
      var j = start + 1
      var done = false
      while !done && j < s.length do
        val c = s.charAt(j)
        if c == '\\' then j += 2
        else if c == quote then { j += 1; done = true }
        else j += 1
      math.min(j, s.length)

  private def isIdentStart(c: Char): Boolean = c == '_' || c == '$' || Character.isLetter(c)
  private def isIdentPart(c: Char): Boolean = c == '_' || c == '$' || Character.isLetterOrDigit(c)
