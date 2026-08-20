package com.fartech.sfl

import scala.collection.mutable

/**
 * Token kinds. Each keyword and operator gets its own constant so the parser can test
 * a token with one integer comparison instead of a string compare.
 */
object Tok:
  final val EOF = 0
  final val INT = 1
  final val FLOAT = 2
  final val STR = 3
  final val IDENT = 4

  // keywords
  final val VAL = 10
  final val VAR = 11
  final val DEF = 12
  final val IF = 13
  final val ELSIF = 14
  final val ELSE = 15
  final val THEN = 16
  final val WHILE = 17
  final val FOR = 18
  final val IN = 19
  final val SELECT = 20
  final val CASE = 21
  final val DEFAULT = 22
  final val RETURN = 23
  final val IMPORT = 24
  final val BREAK = 25
  final val CONTINUE = 26
  final val TRUE = 27
  final val FALSE = 28
  final val NULL = 29

  // punctuation
  final val LPAREN = 40
  final val RPAREN = 41
  final val LBRACE = 42
  final val RBRACE = 43
  final val LBRACKET = 44
  final val RBRACKET = 45
  final val COMMA = 46
  final val COLON = 47
  final val SEMI = 48
  final val DOT = 49
  final val ARROW = 50

  // operators
  final val ASSIGN = 60
  final val PLUS = 61
  final val MINUS = 62
  final val STAR = 63
  final val SLASH = 64
  final val PERCENT = 65
  final val EQ = 66
  final val NE = 67
  final val LT = 68
  final val LE = 69
  final val GT = 70
  final val GE = 71
  final val ANDAND = 72
  final val OROR = 73
  final val BANG = 74
  final val PLUS_EQ = 75
  final val MINUS_EQ = 76
  final val STAR_EQ = 77
  final val SLASH_EQ = 78
  final val PERCENT_EQ = 79
  final val PIPE_GT = 80
  /** `|` separates pattern alternatives; `@` binds a name to a whole pattern. */
  final val PIPE = 81
  final val AT = 82
  /** `?.` and `??`: absence-safe access and null coalescing. */
  final val QDOT = 83
  final val QQ = 84
  /**
   * An interpolated string is lexed in segments: `"a${x}b${y}c"` becomes
   * STR_OPEN("a"), the tokens of x, STR_MID("b"), the tokens of y, STR_CLOSE("c").
   * A string with no `${` stays one STR token, so the common case costs nothing.
   */
  final val STR_OPEN = 85
  final val STR_MID = 86
  final val STR_CLOSE = 87

  private val names: Map[Int, String] = Map(
    EOF -> "end of input", INT -> "integer", FLOAT -> "float", STR -> "string", IDENT -> "identifier",
    VAL -> "'val'", VAR -> "'var'", DEF -> "'def'", IF -> "'if'", ELSIF -> "'elsif'", ELSE -> "'else'",
    THEN -> "'then'", WHILE -> "'while'", FOR -> "'for'", IN -> "'in'", SELECT -> "'select'",
    CASE -> "'case'", DEFAULT -> "'default'", RETURN -> "'return'", IMPORT -> "'import'",
    BREAK -> "'break'", CONTINUE -> "'continue'", TRUE -> "'true'", FALSE -> "'false'", NULL -> "'null'",
    LPAREN -> "'('", RPAREN -> "')'", LBRACE -> "'{'", RBRACE -> "'}'", LBRACKET -> "'['",
    RBRACKET -> "']'", COMMA -> "','", COLON -> "':'", SEMI -> "';'", DOT -> "'.'", ARROW -> "'->'",
    ASSIGN -> "'='", PLUS -> "'+'", MINUS -> "'-'", STAR -> "'*'", SLASH -> "'/'", PERCENT -> "'%'",
    EQ -> "'=='", NE -> "'!='", LT -> "'<'", LE -> "'<='", GT -> "'>'", GE -> "'>='",
    ANDAND -> "'&&'", OROR -> "'||'", BANG -> "'!'", PLUS_EQ -> "'+='", MINUS_EQ -> "'-='",
    STAR_EQ -> "'*='", SLASH_EQ -> "'/='", PERCENT_EQ -> "'%='", PIPE_GT -> "'|>'",
    PIPE -> "'|'", AT -> "'@'", QDOT -> "'?.'", QQ -> "'??'",
    STR_OPEN -> "interpolated string", STR_MID -> "interpolated string",
    STR_CLOSE -> "end of interpolated string"
  )

  def name(kind: Int): String = names.getOrElse(kind, s"token#$kind")

  /**
   * The concrete spellings of every operator and punctuation token, derived from the
   * same table the error messages use. Editor tooling (`sfl syntax`) reads this, so
   * a new operator added above shows up in the generated highlighters without anyone
   * remembering a second list. Entries with prose names — EOF, literals — drop out
   * because only quoted names are actual spellings.
   */
  def symbolSpellings: Seq[String] =
    names.toSeq
      .filter((kind, n) => kind >= LPAREN && n.length >= 3 && n.head == '\'' && n.last == '\'')
      .sortBy((kind, _) => kind)
      .map((_, n) => n.substring(1, n.length - 1))

  val keywords: Map[String, Int] = Map(
    "val" -> VAL, "var" -> VAR, "def" -> DEF, "if" -> IF, "elsif" -> ELSIF, "elif" -> ELSIF,
    "else" -> ELSE, "then" -> THEN, "while" -> WHILE, "for" -> FOR, "in" -> IN,
    "select" -> SELECT, "case" -> CASE, "default" -> DEFAULT, "return" -> RETURN,
    "import" -> IMPORT, "break" -> BREAK, "continue" -> CONTINUE,
    "true" -> TRUE, "false" -> FALSE, "null" -> NULL, "none" -> NULL
  )

  /** Reserved words that may not be used as identifiers. */
  val reserved: Set[String] = keywords.keySet

final class Token(
    val kind: Int,
    val text: String,
    val ival: Long,
    val dval: Double,
    val line: Int,
    val col: Int,
    val offset: Int,
    /** True when at least one newline separates this token from the previous one. */
    val startsLine: Boolean
):
  def pos: Pos = Pos(line, col, offset)
  override def toString: String = s"${Tok.name(kind)}($text)@$line:$col"

/**
 * Signals that input ended in the middle of a construct, so a REPL can ask for more.
 * `openedAt` points at the token that started it, which is what a batch run needs to
 * report — "unterminated block" is useless without saying which block.
 */
final class IncompleteInput(val reason: String, val openedAt: Pos = Pos.none)
    extends RuntimeException(reason)

/**
 * Hand-written lexer. It replaces the regex-driven `JavaTokenParsers` scanner, which
 * re-matched several regular expressions at every position and offered no usable
 * position information for diagnostics.
 */
final class Lexer(val src: SourceRef):
  private val s: String = src.text
  private val n: Int = s.length
  private var i: Int = 0
  private var line: Int = 1
  private var lineStart: Int = 0
  private var sawNewline: Boolean = false

  private def col: Int = i - lineStart + 1

  private def fail(msg: String, at: Int = -1): Nothing =
    val p = if at >= 0 then at else i
    Err.parse(msg, Pos(line, p - lineStart + 1, p), src)

  def tokenize(): Array[Token] =
    // A script starting with `#!` is an executable; the loader line is not SFL.
    if n >= 2 && s.charAt(0) == '#' && s.charAt(1) == '!' then
      while i < n && s.charAt(i) != '\n' do i += 1
    val out = new mutable.ArrayBuffer[Token](math.max(16, n / 4))
    var t = next()
    while t.kind != Tok.EOF do
      out += t
      t = next()
    out += t
    out.toArray

  /**
   * One entry per `${` we are inside. `braces` counts nested `{` from object
   * literals and blocks inside the interpolation, so the `}` that ends it can be
   * told apart from theirs. `triple` remembers which string form to resume.
   */
  private final class Interp(val triple: Boolean):
    var braces: Int = 0

  private val interps = new mutable.ArrayBuffer[Interp]

  private def newline(): Unit =
    line += 1
    lineStart = i
    sawNewline = true

  private def skipTrivia(): Unit =
    var go = true
    while go do
      go = false
      while i < n && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\r' || s.charAt(i) == '\n') do
        if s.charAt(i) == '\n' then
          i += 1
          newline()
        else i += 1
      if i + 1 < n && s.charAt(i) == '/' && s.charAt(i + 1) == '/' then
        while i < n && s.charAt(i) != '\n' do i += 1
        go = true
      else if i + 1 < n && s.charAt(i) == '/' && s.charAt(i + 1) == '*' then
        i += 2
        var closed = false
        while !closed do
          if i + 1 >= n then
            throw new IncompleteInput("unterminated block comment")
          else if s.charAt(i) == '*' && s.charAt(i + 1) == '/' then
            i += 2
            closed = true
          else
            if s.charAt(i) == '\n' then { i += 1; newline() } else i += 1
        go = true

  private def mk(kind: Int, text: String, startOff: Int, startLine: Int, startCol: Int,
                 ival: Long = 0L, dval: Double = 0.0): Token =
    val t = new Token(kind, text, ival, dval, startLine, startCol, startOff, sawNewline)
    sawNewline = false
    t

  def next(): Token =
    skipTrivia()
    val startOff = i
    val startLine = line
    val startCol = col
    if i >= n then
      if interps.nonEmpty then throw new IncompleteInput("unterminated string interpolation")
      return mk(Tok.EOF, "<eof>", startOff, startLine, startCol)

    val c = s.charAt(i)

    // r"..." / r'...' turn off every kind of processing: no escapes, no
    // interpolation. The prefix reads as Python's, which is where people know it
    // from, and regexes stop needing their backslashes doubled.
    if c == 'r' && i + 1 < n && (s.charAt(i + 1) == '"' || s.charAt(i + 1) == '\'') then
      i += 1
      return rawString(s.charAt(i), startOff, startLine, startCol)

    if isIdentStart(c) then
      i += 1
      while i < n && isIdentPart(s.charAt(i)) do i += 1
      val word = s.substring(startOff, i)
      val kw = Tok.keywords.getOrElse(word, -1)
      return mk(if kw >= 0 then kw else Tok.IDENT, word, startOff, startLine, startCol)

    if c >= '0' && c <= '9' then return number(startOff, startLine, startCol)
    if c == '.' && i + 1 < n && s.charAt(i + 1) >= '0' && s.charAt(i + 1) <= '9' then
      return number(startOff, startLine, startCol)

    if c == '"' || c == '\'' then return string(c, startOff, startLine, startCol)

    // Operators, longest match first.
    inline def two(a: Char, b: Char): Boolean = c == a && i + 1 < n && s.charAt(i + 1) == b

    val kind =
      if two('=', '=') then { i += 2; Tok.EQ }
      else if two('!', '=') then { i += 2; Tok.NE }
      else if two('<', '=') then { i += 2; Tok.LE }
      else if two('>', '=') then { i += 2; Tok.GE }
      else if two('&', '&') then { i += 2; Tok.ANDAND }
      else if two('|', '|') then { i += 2; Tok.OROR }
      else if two('|', '>') then { i += 2; Tok.PIPE_GT }
      else if c == '|' then { i += 1; Tok.PIPE }
      else if c == '@' then { i += 1; Tok.AT }
      else if two('?', '.') then { i += 2; Tok.QDOT }
      else if two('?', '?') then { i += 2; Tok.QQ }
      else if two('-', '>') then { i += 2; Tok.ARROW }
      else if two('+', '=') then { i += 2; Tok.PLUS_EQ }
      else if two('-', '=') then { i += 2; Tok.MINUS_EQ }
      else if two('*', '=') then { i += 2; Tok.STAR_EQ }
      else if two('/', '=') then { i += 2; Tok.SLASH_EQ }
      else if two('%', '=') then { i += 2; Tok.PERCENT_EQ }
      else
        i += 1
        c match
          case '(' => Tok.LPAREN
          case ')' => Tok.RPAREN
          case '{' =>
            if interps.nonEmpty then interps.last.braces += 1
            Tok.LBRACE
          case '}' =>
            if interps.nonEmpty && interps.last.braces == 0 then
              // This `}` closes the `${`: the rest of the string continues here.
              val frame = interps.remove(interps.length - 1)
              return stringBody('"', frame.triple, resuming = true, startOff, startLine, startCol)
            if interps.nonEmpty then interps.last.braces -= 1
            Tok.RBRACE
          case '[' => Tok.LBRACKET
          case ']' => Tok.RBRACKET
          case ',' => Tok.COMMA
          case ':' => Tok.COLON
          case ';' => Tok.SEMI
          case '.' => Tok.DOT
          case '=' => Tok.ASSIGN
          case '+' => Tok.PLUS
          case '-' => Tok.MINUS
          case '*' => Tok.STAR
          case '/' => Tok.SLASH
          case '%' => Tok.PERCENT
          case '<' => Tok.LT
          case '>' => Tok.GT
          case '!' => Tok.BANG
          case '&' => fail("unexpected '&' (did you mean '&&'?)", startOff)
          case '|' => fail("unexpected '|' (did you mean '||' or '|>'?)", startOff)
          case _   => fail(s"unexpected character '$c'", startOff)

    mk(kind, s.substring(startOff, i), startOff, startLine, startCol)

  private def isIdentStart(c: Char): Boolean =
    c == '_' || c == '$' || Character.isLetter(c)

  private def isIdentPart(c: Char): Boolean =
    c == '_' || c == '$' || Character.isLetterOrDigit(c)

  private def number(startOff: Int, startLine: Int, startCol: Int): Token =
    // Radix prefixes: 0x / 0b / 0o. Underscores are permitted as digit separators.
    if s.charAt(i) == '0' && i + 1 < n then
      val p = s.charAt(i + 1)
      val radix = p match
        case 'x' | 'X' => 16
        case 'b' | 'B' => 2
        case 'o' | 'O' => 8
        case _         => 0
      if radix != 0 then
        i += 2
        val digitsStart = i
        while i < n && (Character.digit(s.charAt(i), radix) >= 0 || s.charAt(i) == '_') do i += 1
        val digits = s.substring(digitsStart, i).replace("_", "")
        if digits.isEmpty then fail(s"malformed numeric literal", startOff)
        val v =
          try java.lang.Long.parseLong(digits, radix)
          catch case _: NumberFormatException => fail(s"integer literal out of range", startOff)
        return mk(Tok.INT, s.substring(startOff, i), startOff, startLine, startCol, ival = v)

    var isFloat = false
    while i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '_') do i += 1
    if i < n && s.charAt(i) == '.' && i + 1 < n && Character.isDigit(s.charAt(i + 1)) then
      isFloat = true
      i += 1
      while i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '_') do i += 1
    else if i < n && s.charAt(i) == '.' && !(i + 1 < n && isIdentStart(s.charAt(i + 1))) then
      // Trailing dot, as in `1.` — accept it as a float but not when it starts a field access.
      isFloat = true
      i += 1
    if i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E') then
      val save = i
      i += 1
      if i < n && (s.charAt(i) == '+' || s.charAt(i) == '-') then i += 1
      if i < n && Character.isDigit(s.charAt(i)) then
        isFloat = true
        while i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '_') do i += 1
      else i = save

    val raw = s.substring(startOff, i)
    val clean = raw.replace("_", "")
    if isFloat then
      val d =
        try clean.toDouble
        catch case _: NumberFormatException => fail("malformed float literal", startOff)
      mk(Tok.FLOAT, raw, startOff, startLine, startCol, dval = d)
    else
      // `1e10` has no dot but does not fit `toLong`; the exponent branch above already
      // marked such literals as floats, so anything left here is a plain integer.
      try mk(Tok.INT, raw, startOff, startLine, startCol, ival = clean.toLong)
      catch
        case _: NumberFormatException =>
          try mk(Tok.FLOAT, raw, startOff, startLine, startCol, dval = clean.toDouble)
          catch case _: NumberFormatException => fail("malformed numeric literal", startOff)

  private def string(q: Char, startOff: Int, startLine: Int, startCol: Int): Token =
    val triple = q == '"' && i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"'
    i += (if triple then 3 else 1)
    stringBody(q, triple, resuming = false, startOff, startLine, startCol)

  /**
   * Scans one segment of a string literal.
   *
   * Double-quoted strings interpolate: `${` hands control back to the ordinary
   * token loop and the segment so far becomes a STR_OPEN (or STR_MID when this
   * call resumes after a closing `}`). Single-quoted strings never interpolate —
   * the shell's rule, and the reason a `$` needs no escaping there.
   */
  private def stringBody(q: Char, triple: Boolean, resuming: Boolean,
                         startOff: Int, startLine: Int, startCol: Int): Token =
    val sb = new StringBuilder
    val interpolates = q == '"'
    def segment(kind: Int): Token = mk(kind, sb.toString, startOff, startLine, startCol)
    def openInterp(): Token =
      i += 2
      interps += new Interp(triple)
      segment(if resuming then Tok.STR_MID else Tok.STR_OPEN)
    def closed(): Token = segment(if resuming then Tok.STR_CLOSE else Tok.STR)

    // A single-line string interrupted by a newline (or by the end of the input,
    // which the REPL would extend WITH a newline) can never be completed by reading
    // more, so it is a hard error, not an IncompleteInput: a continuation prompt
    // that cannot possibly succeed would swallow everything typed after it.
    def unterminated(): Nothing =
      Err.parseHint("unterminated string literal",
        Pos(line, startOff - lineStart + 1, startOff), src, 1,
        "a single-line string cannot contain a newline",
        "close the quote on the same line, or use \"\"\"...\"\"\" for multi-line text")

    while true do
      if i >= n then
        if triple then throw new IncompleteInput("unterminated string literal")
        else unterminated()
      val c = s.charAt(i)
      if triple && i + 2 < n && c == '"' && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"' then
        i += 3
        return closed()
      else if !triple && c == q then { i += 1; return closed() }
      else if interpolates && c == '$' && i + 1 < n && s.charAt(i + 1) == '{' then
        return openInterp()
      else if c == '\n' then
        if triple then { sb.append('\n'); i += 1; newline() }
        else unterminated()
      else if c == '\\' then
        // The escape table applies to every cooked string — '...' and both double-
        // quoted forms alike; only the r prefix switches it off. In particular the
        // dollar escape is the one way to write a literal interpolation marker in
        // an interpolating triple-quoted string.
        i += 1
        if i >= n then
          if triple then throw new IncompleteInput("unterminated string literal")
          else unterminated()
        val e = s.charAt(i)
        i += 1
        e match
          case 'n'  => sb.append('\n')
          case 't'  => sb.append('\t')
          case 'r'  => sb.append('\r')
          case 'b'  => sb.append('\b')
          case 'f'  => sb.append('\f')
          case '0'  => sb.append('\u0000')
          case '\\' => sb.append('\\')
          case '"'  => sb.append('"')
          case '\'' => sb.append('\'')
          case '$'  => sb.append('$')
          case 'e'  => sb.append(Ansi.ESC)
          case 'u' =>
            if i + 3 >= n then fail("incomplete \\u escape")
            val hex = s.substring(i, i + 4)
            val cp =
              try Integer.parseInt(hex, 16)
              catch case _: NumberFormatException => fail(s"invalid \\u escape '\\u$hex'")
            sb.append(cp.toChar)
            i += 4
          case '\n' =>
            if triple then
              fail("a backslash cannot escape a line break (write \\\\ for a literal backslash)", i - 2)
            else unterminated()
          case other => fail(s"unknown escape '\\$other'")
      else { sb.append(c); i += 1 }
    throw new IllegalStateException("unreachable")

  /** r"..." — verbatim to the closing quote. The only rule is that there is no rule. */
  private def rawString(q: Char, startOff: Int, startLine: Int, startCol: Int): Token =
    val triple = q == '"' && i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"'
    i += (if triple then 3 else 1)
    val sb = new StringBuilder
    // As in stringBody: a single-line raw string cut off by a newline or by the
    // end of the input can never be completed by more input, so it fails hard
    // instead of asking the REPL for a continuation that cannot succeed.
    def unterminated(): Nothing =
      Err.parseHint("unterminated string literal",
        Pos(line, startOff - lineStart + 1, startOff), src, 1,
        "a single-line raw string cannot contain a newline",
        "close the quote on the same line, or use r\"\"\"...\"\"\" for multi-line text")
    while true do
      if i >= n then
        if triple then throw new IncompleteInput("unterminated string literal")
        else unterminated()
      val c = s.charAt(i)
      if triple then
        if i + 2 < n && c == '"' && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"' then
          i += 3
          return mk(Tok.STR, sb.toString, startOff, startLine, startCol)
        if c == '\n' then { sb.append('\n'); i += 1; newline() }
        else { sb.append(c); i += 1 }
      else
        if c == q then
          i += 1
          return mk(Tok.STR, sb.toString, startOff, startLine, startCol)
        if c == '\n' then unterminated()
        sb.append(c)
        i += 1
    throw new IllegalStateException("unreachable")

object Lexer:
  def tokenize(src: SourceRef): Array[Token] = new Lexer(src).tokenize()

  /**
   * Token kinds that cannot legally end a statement. Seeing one last means the user is
   * mid-expression — a pipeline broken across lines, say — so the REPL should keep
   * reading rather than reporting a syntax error.
   */
  private val cannotEnd: Set[Int] = Set(
    Tok.PIPE_GT, Tok.QDOT, Tok.QQ, Tok.PLUS, Tok.MINUS, Tok.STAR, Tok.SLASH, Tok.PERCENT,
    Tok.ANDAND, Tok.OROR, Tok.EQ, Tok.NE, Tok.LT, Tok.LE, Tok.GT, Tok.GE,
    Tok.ASSIGN, Tok.PLUS_EQ, Tok.MINUS_EQ, Tok.STAR_EQ, Tok.SLASH_EQ, Tok.PERCENT_EQ,
    Tok.COMMA, Tok.COLON, Tok.ARROW, Tok.DOT, Tok.BANG,
    Tok.VAL, Tok.VAR, Tok.DEF, Tok.IF, Tok.ELSIF, Tok.ELSE, Tok.THEN,
    Tok.WHILE, Tok.FOR, Tok.IN, Tok.SELECT, Tok.CASE, Tok.IMPORT
  )

  /**
   * Reports whether `text` ends inside an unclosed construct. The REPL uses this to
   * decide between evaluating a line and prompting for a continuation.
   */
  def isIncomplete(text: String): Boolean =
    try
      val toks = tokenize(SourceRef("<repl>", text))
      var depth = 0
      var i = 0
      while i < toks.length do
        toks(i).kind match
          case Tok.LBRACE | Tok.LPAREN | Tok.LBRACKET  => depth += 1
          case Tok.RBRACE | Tok.RPAREN | Tok.RBRACKET  => depth -= 1
          case _                                       => ()
        i += 1
      if depth > 0 then true
      else
        // A dangling operator means the expression continues on the next line.
        val real = toks.filter(_.kind != Tok.EOF)
        real.nonEmpty && cannotEnd.contains(real.last.kind)
    catch
      case _: IncompleteInput => true
      case _: ParseError      => false
