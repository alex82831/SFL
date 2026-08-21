package com.fartech.sfl

import java.io.File
import scala.collection.mutable

/**
 * Where a name lives once the parser has resolved it.
 *
 * Resolution happens while parsing rather than at run time. The previous
 * implementation looked every variable up in a global `HashMap[(String, Scope), Any]`
 * and walked a parent chain of UUID-named scopes on each access; here a local becomes
 * an array index and a global becomes a small integer.
 */
private sealed trait Target
private final case class TLocal(slot: Int) extends Target
private final case class TOuter(depth: Int, slot: Int) extends Target
private final case class TGlobal(id: Int) extends Target

private final class LocalVar(val name: String, val slot: Int, val const: Boolean, val blockDepth: Int)

/** One function being parsed. Slots are never reused, so closures stay correct. */
private final class FnCtx(val parent: FnCtx):
  val locals = new mutable.ArrayBuffer[LocalVar]
  /**
   * Slot -> name in allocation order. `locals` loses popped blocks, but a slot is
   * never reused, so this survives to become the proto's debugNames — the table
   * that lets a debugger label frame slots.
   */
  val slotNames = new mutable.ArrayBuffer[String]
  var nSlots: Int = 0
  var blockDepth: Int = 0

  def alloc(name: String, const: Boolean): Int =
    val slot = nSlots
    nSlots += 1
    locals += new LocalVar(name, slot, const, blockDepth)
    slotNames += name
    slot

  def find(name: String): LocalVar =
    var i = locals.length - 1
    while i >= 0 do
      if locals(i).name == name then return locals(i)
      i -= 1
    null

  def popBlock(): Unit =
    while locals.nonEmpty && locals.last.blockDepth > blockDepth do locals.remove(locals.length - 1)

/**
 * Optional observer the language server hands to the parser: it records every
 * global read, every global definition, and the import failures the parser
 * tolerated instead of aborting. It is null everywhere else — the REPL, the CLI,
 * the compiler — so nothing changes for them. Names and lines only, no nodes:
 * the server diffs reads against definitions to flag undefined variables, and
 * turns the tolerated import failures into one diagnostic each rather than
 * letting the first of them mask the whole file.
 */
final class ParseIntel:
  final case class GlobalRef(name: String, file: String, line: Int)
  val refs = mutable.ArrayBuffer.empty[GlobalRef]
  val importErrors = mutable.ArrayBuffer.empty[SflError]
  private val defined = mutable.HashMap.empty[String, mutable.HashSet[String]]

  /**
   * The namespace of the file being analysed, and the ones opened into it. A name
   * declared anywhere else — a package the file imported without opening, another
   * project's module — is reachable only as `ns.name`, so an unqualified reference
   * to it is still undefined and still worth a diagnostic.
   */
  var rootTag: String = ""
  /**
   * The file being analysed. A `_`-private name is keyed by its file rather than
   * its namespace — privacy is a property of the file — so the file's own tag has
   * to count as in scope, or every private definition would read as undefined.
   */
  var rootFile: String = ""
  private val visible = mutable.LinkedHashSet.empty[String]

  def openInto(into: String, tag: String): Unit = if into == rootTag then visible += tag

  def declare(name: String, tag: String): Unit =
    defined.getOrElseUpdate(name, mutable.HashSet.empty) += tag
  def ref(name: String, file: String, line: Int): Unit =
    if !name.startsWith("__") && !name.contains('.') && !name.contains('#') then
      refs += GlobalRef(name, file, line)

  private def inScope(tag: String): Boolean =
    tag == rootTag || tag == rootFile || visible.contains(tag)
  def isDefined(name: String): Boolean = defined.get(name).exists(_.exists(inScope))
  def definedNames: Iterable[String] = defined.collect { case (n, ts) if ts.exists(inScope) => n }

/**
 * Recursive-descent parser that emits already-resolved AST nodes.
 *
 * It replaces the `scala-parser-combinators` grammar, which backtracked heavily, ran
 * regular expressions at every position, and mutated global interpreter scope state
 * from inside parser actions.
 */
final class Parser(
    val src: SourceRef,
    val globals: Globals,
    val importer: Importer,
    /**
     * The namespace this file's top-level names belong to, when the caller knows it.
     * `null` asks the importer where the file lives; `""` is the root namespace,
     * which only the standard library is parsed into — its definitions *are* the
     * builtin surface. See [[nsTag]].
     */
    nsOverride: String = null,
    /**
     * alias -> namespace tag, shared with the caller when there is a session to share
     * it with. The REPL parses each entry separately, so an alias bound by one entry
     * has to still be there for the next.
     */
    val namespaces: mutable.Map[String, String] = mutable.HashMap.empty,
    /**
     * Language-server mode: when set, global reads and definitions are recorded
     * and import failures become collected diagnostics instead of aborting the
     * parse. Null in every other caller. See [[ParseIntel]].
     */
    val intel: ParseIntel = null
):
  private val toks: Array[Token] = Lexer.tokenize(src)
  private var p: Int = 0
  private var fn: FnCtx = null
  /** Names declared `val` at top level, so assignment to them can be rejected. */
  private val globalConsts = mutable.HashSet.empty[String]

  // -------------------------------------------------------------------------
  // Namespaces
  //
  // A top-level name belongs to the namespace of the file that declares it, and is
  // stored under a key that carries that namespace: `tag#name`. Nothing a program
  // writes lands in the root table, so a `def map` is the writer's own `map` and
  // the builtin one keeps working everywhere else — including inside the very
  // library the writer is calling.
  //
  // Two kinds of key exist beside the namespace's own. A name beginning with `_`
  // belongs to its file alone, so it is keyed by the file rather than the unit the
  // file is part of; and the builtins sit in the root namespace, whose keys are
  // bare, so an unqualified name that nothing nearer defines still finds them.
  //
  // The key never reaches the reader — errors show the display name.
  // -------------------------------------------------------------------------

  /** The namespace this file's public top-level names belong to; "" is the root. */
  private val nsTag: String =
    if nsOverride != null then nsOverride else importer.namespaceOf(src.name)._1

  /** What a reader calls this namespace. */
  private val nsLabel: String =
    if nsOverride != null then
      if nsOverride.isEmpty then "std" else globals.labelOf(nsOverride)
    else importer.namespaceOf(src.name)._2

  /** True when other files share this namespace, so a name may be declared later. */
  private val sharedNs: Boolean = nsTag.nonEmpty && nsTag != src.name

  /**
   * Whether a second declaration of one public name in this namespace is a mistake
   * worth refusing. It is, between two files of a package or a project. It is not
   * from a synthetic source — a REPL line, `eval`, a debugger watch — where
   * redefining a name is the point.
   */
  private val guardsDuplicates: Boolean = sharedNs && !src.name.startsWith("<")

  /** Keys a `_`-private name: privacy is a property of the file, never of the unit. */
  private val fileTag: String = src.name

  globals.noteNamespace(nsTag, nsLabel)

  /**
   * Namespaces whose surface is visible here unqualified, in the order they were
   * opened. A plain `import "m"` of a file adds one; a package import does not,
   * because a package is a named unit and reaching into it should say so.
   */
  private val opened = mutable.LinkedHashSet.empty[String]
  opened ++= globals.opensOf(nsTag)

  private def isPrivate(name: String): Boolean = name.startsWith("_")

  /**
   * Top-level names this file declares, read off the token stream before parsing.
   *
   * A reference has to know whether a name is this file's own or somebody else's:
   * `_bad` means the private one here, while `__arity` is a builtin this file never
   * declares. Reading it up front is also what lets a private name be referenced
   * before its definition, exactly like any other.
   */
  private lazy val topLevelNames: Set[String] =
    val out = mutable.HashSet.empty[String]
    var depth = 0
    var nest = 0 // parens and brackets, for the bare-assignment rule below
    var i = 0
    while i < toks.length do
      val k = toks(i).kind
      if k == Tok.LBRACE then depth += 1
      else if k == Tok.RBRACE then depth -= 1
      else if k == Tok.LPAREN || k == Tok.LBRACKET then nest += 1
      else if k == Tok.RPAREN || k == Tok.RBRACKET then nest -= 1
      if k == Tok.LBRACE || k == Tok.RBRACE then ()
      else if depth == 0 && (k == Tok.VAL || k == Tok.VAR || k == Tok.DEF) &&
              i + 1 < toks.length && toks(i + 1).kind == Tok.IDENT then
        out += toks(i + 1).text
      else if depth == 0 && nest == 0 && k == Tok.IDENT &&
              i + 1 < toks.length && toks(i + 1).kind == Tok.ASSIGN &&
              (i == 0 || (toks(i - 1).kind != Tok.DOT && toks(i - 1).kind != Tok.RBRACKET)) then
        // `name = value` at the top level declares as surely as `var name = value`
        // does, so the namespace has to know about it before the first reference.
        out += toks(i).text
      else if depth == 0 && (k == Tok.VAL || k == Tok.VAR) && i + 1 < toks.length then
        // A destructuring declaration. Its binder names are the identifiers that are
        // neither called (followed by '(') nor object keys (followed by ':'), so a
        // token walk finds exactly what the pattern parser will later declare.
        var j = i + 1
        var d = 0
        var guard = 0
        while j < toks.length && guard < 10000 &&
              !(d == 0 && toks(j).kind == Tok.ASSIGN) && toks(j).kind != Tok.EOF do
          toks(j).kind match
            case Tok.LPAREN | Tok.LBRACKET | Tok.LBRACE => d += 1
            case Tok.RPAREN | Tok.RBRACKET | Tok.RBRACE => d -= 1
            case Tok.IDENT
                if toks(j).text != "_" && !toks(j).text.startsWith("$") &&
                   j + 1 < toks.length && toks(j + 1).kind != Tok.LPAREN &&
                   toks(j + 1).kind != Tok.COLON =>
              out += toks(j).text
            case _ => ()
          j += 1
          guard += 1
      i += 1
    out.toSet

  /** The key a top-level declaration in this file is stored under. */
  private def declKey(name: String): String =
    if isPrivate(name) then s"$fileTag#$name"
    else if nsTag.isEmpty then name
    else s"$nsTag#$name"

  /**
   * The key an unqualified reference resolves to, tried nearest first:
   *
   *   1. a name this file declares — including a private one, which no import can
   *      ever supply;
   *   2. a name a sibling file of this namespace has already declared;
   *   3. a name opened into this file by a plain `import`, where exactly one of the
   *      opened namespaces has it — two is an ambiguity, and [[resolve]] refuses it
   *      before this ever runs;
   *   4. a builtin;
   *   5. otherwise this namespace, so a package module may call a sibling's function
   *      that has not been parsed yet — and so that a name nothing defines fails
   *      against the namespace it was written in, whose members are what "did you
   *      mean" should be searching.
   */
  private def refKey(name: String): String =
    if topLevelNames.contains(name) then declKey(name)
    else if isPrivate(name) then name // never reachable from outside its file
    else if nsTag.nonEmpty && globals.find(s"$nsTag#$name") >= 0 then s"$nsTag#$name"
    else
      val from = openersOf(name)
      if from.length == 1 then s"${from.head}#$name"
      else if globals.find(name) >= 0 then name
      else if nsTag.nonEmpty then s"$nsTag#$name"
      else name

  /** The opened namespaces that export `name`. */
  private def openersOf(name: String): Seq[String] =
    opened.iterator.filter(tag => globals.find(s"$tag#$name") >= 0).toSeq

  /** Reports a name two opened namespaces both export, instead of picking one. */
  private def checkAmbiguous(name: String, t: Token): Unit =
    val from = openersOf(name)
    if from.length > 1 then
      val labels = from.map(globals.labelOf)
      failWithHint(
        s"'$name' is ambiguous: ${labels.mkString(" and ")} both export it", t,
        s"qualify it — ${labels.map(l => s"$l.$name").mkString(" or ")} — " +
          "or import one of them under an alias"
      )

  /**
   * How a key is spelled in a message. A namespace's own files write their names
   * bare and so does everyone reading an error from inside them; only a reference
   * that had to reach across says so, and those build their own display text (see
   * [[qualifiedGet]]).
   */
  private def globalDisplay(name: String): String = name

  /**
   * Counts function literals built so far. A loop body that raises this count captures
   * variables, and so needs a fresh frame per iteration; see [[loopScope]].
   */
  private var closureCount: Int = 0

  private def makeFun(proto: FnProto, line: Int): MakeFun =
    closureCount += 1
    new MakeFun(proto, line)

  /** Everything the parser must restore to re-parse a stretch of tokens. */
  private final class Mark(
      val pos: Int,
      val slots: Int,
      val locals: Int,
      val blockDepth: Int,
      val consts: Set[String],
      val closures: Int
  )

  private def mark(): Mark =
    new Mark(
      p,
      if fn != null then fn.nSlots else 0,
      if fn != null then fn.locals.length else 0,
      if fn != null then fn.blockDepth else 0,
      globalConsts.toSet,
      closureCount
    )

  private def rewind(m: Mark): Unit =
    p = m.pos
    if fn != null then
      fn.nSlots = m.slots
      if fn.locals.length > m.locals then fn.locals.remove(m.locals, fn.locals.length - m.locals)
      if fn.slotNames.length > m.slots then fn.slotNames.remove(m.slots, fn.slotNames.length - m.slots)
      fn.blockDepth = m.blockDepth
    globalConsts.clear()
    globalConsts ++= m.consts
    closureCount = m.closures

  // -------------------------------------------------------------------------
  // Token helpers
  // -------------------------------------------------------------------------

  private def cur: Token = toks(p)
  private def peek(k: Int): Token = toks(math.min(p + k, toks.length - 1))
  private def at(kind: Int): Boolean = toks(p).kind == kind
  private def advance(): Token = { val t = toks(p); if t.kind != Tok.EOF then p += 1; t }

  private def accept(kind: Int): Boolean =
    if toks(p).kind == kind then { p += 1; true } else false

  private def expect(kind: Int, context: String): Token =
    if toks(p).kind == kind then advance()
    else
      val found = toks(p)
      val hint =
        if kind == Tok.RPAREN && found.kind == Tok.ASSIGN then
          "'=' assigns; did you mean '==' to compare?"
        else if kind == Tok.ASSIGN && found.kind == Tok.EQ then
          "'==' compares; did you mean '=' to assign?"
        else if kind == Tok.COLON && found.kind == Tok.ASSIGN then
          "object entries and 'case' labels use ':', not '='"
        else if kind == Tok.IDENT && Tok.reserved.contains(found.text) then
          s"'${found.text}' is a reserved word, so it cannot be used as a name"
        else ""
      failWithHint(s"expected ${Tok.name(kind)} $context, found ${describe(found)}", found, hint)

  private def describe(t: Token): String =
    if t.kind == Tok.EOF then "end of input"
    else if t.kind == Tok.IDENT then s"identifier '${t.text}'"
    else if t.kind == Tok.STR then "string literal"
    else Tok.name(t.kind)

  private def fail(msg: String): Nothing =
    if cur.kind == Tok.EOF then throw new IncompleteInput(msg)
    Err.parse(msg, cur.pos, src)

  private def failAt(msg: String, t: Token): Nothing = Err.parse(msg, t.pos, src)

  /** Reports at a token, underlining its text and attaching the hints there are. */
  private def failWithHint(msg: String, t: Token, hints: String*): Nothing =
    if t.kind == Tok.EOF then throw new IncompleteInput(msg)
    Err.parseHint(msg, t.pos, src, math.max(1, t.text.length), hints*)

  /** Optional statement separators; newlines already terminate statements implicitly. */
  private def skipSeparators(): Unit = while at(Tok.SEMI) do p += 1

  /**
   * Records where a construct began, so errors can underline exactly that construct.
   * An inner position already recorded is kept: the index in `o[k]` locates a missing
   * key better than the start of the statement that contains it.
   */
  private inline def from[N <: Node](t: Token, n: N): N =
    if n.col <= 0 then n.col = t.col
    n

  // -------------------------------------------------------------------------
  // Scope helpers
  // -------------------------------------------------------------------------

  private def declare(name: String, const: Boolean, t: Token): Target =
    if Tok.reserved.contains(name) then
      failWithHint(s"'$name' is a reserved word and cannot be a variable", t,
        s"pick another name, such as '${name}Value'")
    if fn == null then
      val key = declKey(name)
      // Two files of one namespace declaring the same public name is the overwrite
      // namespaces exist to stop, so it is reported rather than resolved by order.
      if guardsDuplicates && !isPrivate(name) then
        for first <- globals.noteDecl(key, src.name) do
          failWithHint(
            s"'$name' is already defined in '$nsLabel'", t,
            s"${new File(first).getName} declares it too, and both files share one namespace",
            "rename one of them, or make it private by starting the name with '_'"
          )
      val id = globals.id(key, globalDisplay(name))
      if const then globalConsts += key else globalConsts -= key
      globals.setConst(id, const)
      if intel != null then intel.declare(name, nsOf(key))
      TGlobal(id)
    else
      // Redeclaring in the same block is a mistake worth reporting.
      val existing = fn.find(name)
      if existing != null && existing.blockDepth == fn.blockDepth then
        failAt(s"variable '$name' is already declared in this scope", t)
      TLocal(fn.alloc(name, const))

  /**
   * Where a name refers to, given the token that spelled it: locals first, then
   * [[refKey]]'s namespace order. The ambiguity check lives here rather than at the
   * call sites, so every path that reads a name gets it.
   */
  private def resolve(name: String, at: Token): Target =
    if fn == null || !isLocallyBound(name) then checkAmbiguous(name, at)
    var ctx = fn
    var depth = 0
    while ctx != null do
      val lv = ctx.find(name)
      if lv != null then return if depth == 0 then TLocal(lv.slot) else TOuter(depth, lv.slot)
      ctx = ctx.parent
      depth += 1
    TGlobal(globals.id(refKey(name), globalDisplay(name)))

  private def isLocallyBound(name: String): Boolean =
    var ctx = fn
    while ctx != null do
      if ctx.find(name) != null then return true
      ctx = ctx.parent
    false

  /**
   * The namespace an identifier names, or null. Aliases bound by `import` come
   * first, so a program may rebind one; the builtin namespaces — `std` and one per
   * builtin group — are there without an import, because the library they slice up
   * is.
   */
  private def namespaceTag(name: String): String =
    namespaces.get(name) match
      case Some(tag)                   => tag
      case None if Builtins.isNamespace(name) => name
      case None                        => null

  /**
   * True when the identifier at `t` is a namespace being read as `ns.member`. A
   * name the file itself binds always wins: a local, and a top-level definition
   * here, so `val log = {...}` keeps `log.level` meaning that object's field even
   * where a package or builtin group answers to the same name.
   */
  private def isNamespaceRef(t: Token): Boolean =
    namespaceTag(t.text) != null && !isLocallyBound(t.text) &&
      !topLevelNames.contains(t.text) &&
      peek(1).kind == Tok.DOT && peek(2).kind == Tok.IDENT

  /** One name of a namespace reached as `alias.member`. */
  private def qualifiedGet(tag: String, aliasTok: Token, member: Token): Node =
    if isCompoundAssign(cur.kind) || at(Tok.ASSIGN) then
      failWithHint(
        s"cannot assign to '${aliasTok.text}.${member.text}'", member,
        "a namespace's names are read-only from outside it; export a function that changes it"
      )
    if isPrivate(member.text) then
      failWithHint(
        s"'${member.text}' is private to '${aliasTok.text}'", member,
        "names beginning with '_' are visible only inside the file that declares them"
      )
    val id = globals.find(s"$tag#${member.text}")
    if id < 0 then
      failWithHint(
        s"'${aliasTok.text}' has no '${member.text}'", member,
        Err.didYouMean(member.text, globals.membersOf(tag))
      )
    from(member, new GlobalGet(id, s"${aliasTok.text}.${member.text}", member.line))

  private def isConstTarget(name: String): Boolean =
    var ctx = fn
    while ctx != null do
      val lv = ctx.find(name)
      if lv != null then return lv.const
      ctx = ctx.parent
    globalConsts.contains(refKey(name))

  private def isDeclared(name: String): Boolean =
    var ctx = fn
    while ctx != null do
      if ctx.find(name) != null then return true
      ctx = ctx.parent
    globals.find(refKey(name)) >= 0

  private def getNode(t: Target, name: String, line: Int): Node = t match
    case TLocal(slot)        => new LocalGet(slot, name, line)
    case TOuter(depth, slot) => new OuterGet(depth, slot, name, line)
    case TGlobal(id)         =>
      if intel != null then intel.ref(name, src.name, line)
      new GlobalGet(id, name, line)

  /** `name` is the assigned identifier where the call site knows it; a global
    * assignment defines the name, and the intel collector needs to hear that. */
  private def setNode(t: Target, value: Node, line: Int, name: String = ""): Node = t match
    case TLocal(slot)        => new LocalSet(slot, value, line)
    case TOuter(depth, slot) => new OuterSet(depth, slot, value, line)
    case TGlobal(id)         =>
      if intel != null && name.nonEmpty then intel.declare(name, nsOf(declKey(name)))
      new GlobalSet(id, value, line)

  // -------------------------------------------------------------------------
  // Entry points
  // -------------------------------------------------------------------------

  /** Parses a whole compilation unit. */
  def parseProgram(): Block =
    val stmts = new mutable.ArrayBuffer[Node]
    skipSeparators()
    while !at(Tok.EOF) do
      stmts += statement()
      skipSeparators()
    new Block(stmts.toArray, 1)

  // -------------------------------------------------------------------------
  // Statements
  // -------------------------------------------------------------------------

  private def statement(): Node = from(cur, statementBody())

  private def statementBody(): Node =
    val t = cur
    t.kind match
      case Tok.VAL | Tok.VAR => varDecl(t.kind == Tok.VAL)
      case Tok.DEF           => funDecl()
      case Tok.RETURN =>
        advance()
        // `return` with no value must not swallow the next line's expression.
        if at(Tok.SEMI) || at(Tok.RBRACE) || at(Tok.EOF) || cur.startsLine then new Return(null, t.line)
        else new Return(expression(), t.line)
      case Tok.BREAK    => advance(); new Break(t.line)
      case Tok.CONTINUE => advance(); new Continue(t.line)
      case Tok.IMPORT   => importStatement()
      case Tok.WHILE    => whileStatement()
      case Tok.FOR      => forStatement()
      case Tok.SEMI     => advance(); new Nop(t.line)
      case _            => exprStatement()

  private def varDecl(const: Boolean): Node =
    val kw = advance()
    if at(Tok.IDENT) && peek(1).kind == Tok.ASSIGN then
      val nameTok = advance()
      advance()
      val value = expression()
      // Declare after parsing the initializer so `val x = x` refers to the outer `x`.
      val target = declare(nameTok.text, const, nameTok)
      setNode(target, value, kw.line)
    else
      destructuringDecl(const, kw)

  /**
   * `val [a, ...rest] = e` and friends. The pattern must match — a shape the value
   * can fail is what `select` is for — so a miss raises through __patFail with the
   * pattern's own source text in the message.
   */
  private def destructuringDecl(const: Boolean, kw: Token): Node =
    val patStartTok = cur
    val patStart = p
    // The initializer parses first (the `val x = x` rule), so skip the pattern now
    // and come back for the real parse once the value is in hand.
    skipPatternTokens(Tok.ASSIGN, "destructuring")
    val assignTok = cur
    expect(Tok.ASSIGN, "after the pattern")
    val value = expression()
    val afterValue = p

    val (subjF, subjW) = hiddenTemp(kw.line)
    val names = new mutable.ArrayBuffer[String]
    // A dry run collects the names first, purely to reject a pattern that binds
    // nothing before any declaration happens. (The top-level prescan has already
    // fed the binder names to the namespace rules.)
    p = patStart
    parsePattern(subjF, kw.line, inAlt = false, dryRun = true, names)
    if names.isEmpty then
      failAt("this pattern binds nothing; a destructuring 'val' needs at least one name", patStartTok)
    p = patStart
    val realNames = new mutable.ArrayBuffer[String]
    val savedConst = patConst
    patConst = const
    val pat =
      try parsePattern(subjF, kw.line, inAlt = false, dryRun = false, realNames)
      finally patConst = savedConst
    p = afterValue

    val patText = src.text.substring(patStartTok.offset, assignTok.offset).trim
    val failCall = callBuiltin("__patFail", kw.line,
      new Lit(VStr(patText), kw.line), subjF())
    val check = new If(Array(new Not(pat.test, kw.line)), Array(failCall), null, kw.line)
    new Block(Array(subjW(value), check), kw.line)

  private def funDecl(): Node =
    val kw = advance()
    val nameTok = expect(Tok.IDENT, "after 'def'")
    // Declare before parsing the body so the function can call itself.
    val target =
      if fn == null then
        val key = declKey(nameTok.text)
        if guardsDuplicates && !isPrivate(nameTok.text) then
          for first <- globals.noteDecl(key, src.name) do
            failWithHint(
              s"'${nameTok.text}' is already defined in '$nsLabel'", nameTok,
              s"${new File(first).getName} declares it too, and both files share one namespace",
              "rename one of them, or make it private by starting the name with '_'"
            )
        val id = globals.id(key, globalDisplay(nameTok.text))
        globalConsts -= key
        if intel != null then intel.declare(nameTok.text, nsOf(key))
        TGlobal(id)
      else
        val existing = fn.find(nameTok.text)
        if existing != null && existing.blockDepth == fn.blockDepth then TLocal(existing.slot)
        else TLocal(fn.alloc(nameTok.text, const = false))
    val proto = functionRest(nameTok.text, kw.line)
    setNode(target, makeFun(proto, kw.line), kw.line)

  /** Parses parameter lists and body. Extra parameter lists become nested closures. */
  private def functionRest(name: String, line: Int): FnProto =
    val paramLists = new mutable.ArrayBuffer[Params]
    paramLists += paramList()
    while at(Tok.LPAREN) && !cur.startsLine do paramLists += paramList()
    buildFunction(name, paramLists.toList, line)

  private def buildFunction(name: String, lists: List[Params], line: Int): FnProto =
    val params = lists.head
    val ctx = new FnCtx(fn)
    fn = ctx
    var i = 0
    while i < params.length do
      ctx.alloc(params.entries(i)._1, const = false)
      i += 1

    val body: Node =
      if lists.tail.nonEmpty then
        // `def f(a)(b) = ...` becomes `def f(a) = (b) -> ...`, so `f(1)(2)` works
        // through ordinary calls instead of the old fake-currying argument merge.
        val inner = buildFunction(s"$name→", lists.tail, line)
        new Return(makeFun(inner, line), line)
      // A single-expression body accepts any statement, so `def f(x) = total += x`
      // works as readily as a braced body would.
      else if accept(Tok.ASSIGN) then new Return(statement(), line)
      else bracedBody("function body")

    val proto = new FnProto(name, params.names, params.defaults, body, line, src, params.hasRest)
    proto.nSlots = ctx.nSlots
    proto.debugNames = ctx.slotNames.toArray
    fn = ctx.parent
    markTail(proto.body, tail = true)
    proto

  /** A parameter list, plus whether its final entry is a `...rest` collector. */
  private final class Params(val entries: Array[(String, Node)], val hasRest: Boolean):
    def names: Array[String] = entries.map(_._1)
    def defaults: Array[Node] = entries.map(_._2)
    def length: Int = entries.length

  private def paramList(): Params =
    expect(Tok.LPAREN, "to start a parameter list")
    val out = new mutable.ArrayBuffer[(String, Node)]
    var seenDefault = false
    var hasRest = false
    if !at(Tok.RPAREN) then
      var go = true
      while go do
        // `...name` collects every remaining argument into an array.
        val rest = at(Tok.DOT) && peek(1).kind == Tok.DOT && peek(2).kind == Tok.DOT
        if rest then
          if hasRest then failAt("a parameter list can have only one '...' parameter", cur)
          advance(); advance(); advance()
          hasRest = true
        val nameTok = expect(Tok.IDENT, "as a parameter name")
        if out.exists(_._1 == nameTok.text) then
          failAt(s"duplicate parameter '${nameTok.text}'", nameTok)
        val default =
          if rest then
            if at(Tok.ASSIGN) then failAt("a '...' parameter cannot have a default", cur)
            null
          else if accept(Tok.ASSIGN) then
            seenDefault = true
            // Mark that we are inside a default so hiddenTemp avoids frame slots;
            // saved and restored because a default can contain a lambda whose own
            // parameter list can contain another default.
            val savedCtx = defaultCtx
            defaultCtx = fn
            val e = expression()
            defaultCtx = savedCtx
            e
          else
            if seenDefault then
              failAt(s"parameter '${nameTok.text}' must have a default because an earlier one does", nameTok)
            null
        out += ((nameTok.text, default))
        go = accept(Tok.COMMA)
        if go && hasRest then failAt("a '...' parameter must be the last one", cur)
    expect(Tok.RPAREN, "to close a parameter list")
    new Params(out.toArray, hasRest)

  private def importStatement(): Node =
    val kw = advance()
    val fileTok = expect(Tok.STR, "after 'import'")
    // `as` and `open` are contextual rather than reserved, so a program that already
    // uses either as a name keeps working.
    val alias =
      if at(Tok.IDENT) && cur.text == "as" && peek(1).kind == Tok.IDENT then
        advance()
        Some(advance())
      else None
    val openTok = if at(Tok.IDENT) && cur.text == "open" then Some(advance()) else None

    if intel == null then importResolved(kw, fileTok, alias, openTok)
    else
      // Language-server mode: one bad import becomes one diagnostic, and the
      // rest of the file still parses — an editor is mostly looking at files
      // whose imports are broken precisely while the user is typing them.
      try importResolved(kw, fileTok, alias, openTok)
      catch
        case e: SflError =>
          intel.importErrors += e
          new Nop(kw.line)
        case e: IncompleteInput =>
          intel.importErrors += new ParseError(e.reason, fileTok.pos, src)
          new Nop(kw.line)

  /**
   * The importing work itself; raises located errors exactly as before.
   *
   * An import does two separable things. It binds a namespace — `csv`, or whatever
   * `as` renames it to — which is always available and always costs nothing at run
   * time, because `csv.parse` resolves to a global right here. And it may *open* the
   * namespace, putting its surface in this file's scope unqualified.
   *
   * Opening is the default for a plain file, where the module is part of the program
   * being written; it is not the default for a unit with a manifest — a package or
   * another project — whose surface should say where it came from. `open` asks for
   * it either way.
   */
  private def importResolved(
      kw: Token, fileTok: Token, alias: Option[Token], openTok: Option[Token]
  ): Node =
    val path = importer.pathOf(fileTok.text, src, fileTok.pos)
    val (tag, label) = importer.namespaceOf(path)
    globals.noteNamespace(tag, label)

    for a <- alias do
      namespaces.get(a.text) match
        case Some(bound) if bound != tag =>
          failWithHint(
            s"'${a.text}' already names another namespace", a,
            s"it is bound to '${globals.labelOf(bound)}'; pick a different alias")
        case _ => namespaces(a.text) = tag
    if alias.isEmpty && tag != nsTag then
      // The default alias is what the unit calls itself. A second import of the
      // same unit rebinds it to the same tag, so this is idempotent; a clash with
      // a different unit is worth reporting where it happens.
      namespaces.get(label) match
        case Some(bound) if bound != tag =>
          failWithHint(
            s"'$label' already names another namespace", fileTok,
            s"it is bound to '${globals.labelOf(bound)}'",
            s"import this one under an alias: import \"${fileTok.text}\" as …")
        case _ => namespaces(label) = tag

    val opens = openTok.isDefined || (alias.isEmpty && !importer.isUnit(tag))
    if opens && tag != nsTag then
      opened += tag
      globals.noteOpen(nsTag, tag)
      if intel != null then intel.openInto(nsTag, tag)

    importer.begin(path) match
      case None => new Nop(kw.line)
      case Some(text) =>
        // Imported code is parsed into its own namespace and inlined here, so it
        // runs exactly once at the point of import.
        val sub = new Parser(SourceRef(path, text), globals, importer, tag, intel = intel)
        val block =
          try sub.parseProgram()
          finally importer.finish(path)
        importer.recordSurface(tag, sub.exportedNames)
        // A package module is tagged with its origin, so the ahead-of-time compiler
        // may link a prebuilt archive instead of emitting the module's code again.
        // The tag changes nothing for the interpreter — a ModuleBlock evaluates
        // exactly as the Block it extends.
        importer.packageProvenance(path) match
          case Some((pkg, moduleRel, versionDir)) =>
            new ModuleBlock(block.stmts, block.line, pkg, moduleRel, versionDir, path, text)
          case None => block

  /** The public top-level names this file declares. */
  def exportedNames: Set[String] = topLevelNames.filterNot(isPrivate)

  /** The namespace a key belongs to; a bare key is the root namespace. */
  private def nsOf(key: String): String =
    val i = key.lastIndexOf('#')
    if i < 0 then "" else key.substring(0, i)

  private def whileStatement(): Node =
    val kw = advance()
    val cond = conditionExpr()
    val start = mark()
    val flat = loopBody()
    if closureCount == start.closures then new While(cond, flat, kw.line)
    else
      // The body builds closures, so give each iteration its own frame.
      rewind(start)
      val (body, slots) = loopScope(_ => ())
      new While(cond, body, kw.line, slots)

  private def forStatement(): Node =
    val kw = advance()
    val parens = accept(Tok.LPAREN)
    if at(Tok.IDENT) && peek(1).kind == Tok.IN then
      val nameTok = advance()
      advance()
      val iterable = expression()
      if parens then expect(Tok.RPAREN, "to close the 'for' header")

      val start = mark()

      // First pass: the loop variable and the body share the enclosing frame, which is
      // the cheap layout and is correct whenever no closure escapes the iteration.
      val savedDepth = if fn != null then fn.blockDepth else 0
      if fn != null then fn.blockDepth += 1
      val target = declare(nameTok.text, const = false, nameTok)
      val flat = loopBody()
      if fn != null then
        fn.blockDepth = savedDepth
        fn.popBlock()

      if closureCount == start.closures then
        target match
          case TLocal(slot) => new ForIn(slot, -1, iterable, flat, kw.line)
          case TGlobal(id)  => new ForIn(-1, id, iterable, flat, kw.line)
          case TOuter(_, _) => failAt("internal error: loop variable resolved to an outer scope", nameTok)
      else
        // Second pass: the loop variable moves into the per-iteration frame, so every
        // closure made on a pass sees that pass's value.
        rewind(start)
        var slot = -1
        val (body, slots) = loopScope(ctx => slot = ctx.alloc(nameTok.text, const = false))
        new ForIn(slot, -1, iterable, body, kw.line, slots)
    else
      forPatternStatement(kw, parens)

  /**
   * `for ((k, v) in entries(o))` and friends. The element lands in a hidden loop
   * variable, the pattern runs against it at the top of the body, and an element
   * the pattern does not match is SKIPPED — Scala's for-comprehension filtering,
   * which is also the only behaviour that makes `for` useful over mixed data.
   */
  private def forPatternStatement(kw: Token, parens: Boolean): Node =
    val patStartTok = cur
    val patStart = p
    skipPatternTokens(Tok.IN, "loop")
    if !accept(Tok.IN) then failAt("expected 'in' after the loop pattern", cur)
    val iterable = expression()
    if parens then expect(Tok.RPAREN, "to close the 'for' header")

    val hidden = freshPatName()
    val start = mark()
    val afterHeader = p

    /** Parses pattern + body inside the current scope; returns the filtered body. */
    def parseBodyWith(subjF: () => Node): Node =
      p = patStart
      val names = new mutable.ArrayBuffer[String]
      val pat = parsePattern(subjF, kw.line, inAlt = false, dryRun = false, names)
      p = afterHeader
      val filter = new If(Array(new Not(pat.test, kw.line)),
        Array(new Continue(kw.line): Node), null, kw.line)
      val body = loopBody()
      val stmts = body match
        case b: Block => filter +: b.stmts
        case other    => Array(filter, other)
      new Block(stmts.toArray, kw.line)

    // First pass: flat frame, as for a plain loop variable.
    val savedDepth = if fn != null then fn.blockDepth else 0
    if fn != null then fn.blockDepth += 1
    val target = declare(hidden, const = false, patStartTok)
    val flat = parseBodyWith(() => getNode(target, hidden, kw.line))
    if fn != null then
      fn.blockDepth = savedDepth
      fn.popBlock()

    if closureCount == start.closures then
      target match
        case TLocal(slot) => new ForIn(slot, -1, iterable, flat, kw.line)
        case TGlobal(id)  => new ForIn(-1, id, iterable, flat, kw.line)
        case TOuter(_, _) => failAt("internal error: loop variable resolved to an outer scope", patStartTok)
    else
      // Second pass: everything — hidden variable, pattern bindings, body locals —
      // moves into the per-iteration frame, so closures capture per iteration.
      rewind(start)
      var slot = -1
      val ctx = new FnCtx(fn)
      fn = ctx
      slot = ctx.alloc(hidden, const = false)
      val theSlot = slot
      val body = parseBodyWith(() => new LocalGet(theSlot, hidden, kw.line))
      fn = ctx.parent
      new ForIn(slot, -1, iterable, body, kw.line, ctx.nSlots)

  /**
   * Marks the calls that a function body finishes with, so the evaluator can reuse the
   * current activation instead of nesting a new one. A call is in tail position when it
   * produces the body's value directly: the last statement of the body, the branches of
   * an `if` or `select` in that position, and the operand of any `return`.
   *
   * Loop bodies are not tail positions (the loop continues afterwards), but a `return`
   * inside one still is, which the recursion below handles.
   */
  private def markTail(n: Node, tail: Boolean): Unit = n match
    case c: Call => if tail then c.tail = true
    case b: Block =>
      var i = 0
      while i < b.stmts.length do
        markTail(b.stmts(i), tail && i == b.stmts.length - 1)
        i += 1
    case i: If =>
      var k = 0
      while k < i.bodies.length do
        markTail(i.bodies(k), tail)
        k += 1
      if i.orElse != null then markTail(i.orElse, tail)
    case sel: Select =>
      var k = 0
      while k < sel.caseBodies.length do
        markTail(sel.caseBodies(k), tail)
        k += 1
      if sel.orElse != null then markTail(sel.orElse, tail)
    case r: Return => if r.e != null then markTail(r.e, tail = true)
    case w: While  => markTail(w.body, tail = false)
    case fo: ForIn => markTail(fo.body, tail = false)
    // Anything else either is not a call or uses the call's value for something, so it
    // cannot give up its activation. Nested function bodies were marked when built.
    case _ => ()

  /**
   * Re-parses a loop body inside a nested scope, which the evaluator materialises as a
   * fresh frame on each iteration. `prepare` runs before the body is parsed, so a `for`
   * can put its loop variable in that scope.
   */
  private def loopScope(prepare: FnCtx => Unit): (Node, Int) =
    val ctx = new FnCtx(fn)
    fn = ctx
    prepare(ctx)
    val body = loopBody()
    fn = ctx.parent
    (body, ctx.nSlots)

  /** A loop or `if` body: either a braced block or a single statement. */
  private def loopBody(): Node =
    if at(Tok.LBRACE) then bracedBody("loop body") else statement()

  /**
   * A `{`-delimited body. It is usually a block, but `{"k": v}` is an object literal
   * and `{ (a) -> ... }` is a lambda, so the same lookahead applies here as in
   * expression position.
   */
  private def bracedBody(what: String): Node =
    val nxt = peek(1)
    val after = peek(2)
    // `{}` here is an empty body, not an empty object: `for (x in xs) { }` should do
    // nothing rather than build a discarded object on every iteration. In expression
    // position, where an object is what someone would mean, braceExpr still reads it
    // as one.
    val isObject = (nxt.kind == Tok.STR || nxt.kind == Tok.IDENT) && after.kind == Tok.COLON
    val isLambda = nxt.kind == Tok.LPAREN && looksLikeLambdaParams(p + 1)
    if isObject || isLambda then braceExpr() else blockNode(what)

  private def blockNode(what: String): Node =
    val open = expect(Tok.LBRACE, s"to start a $what")
    if fn != null then fn.blockDepth += 1
    val stmts = new mutable.ArrayBuffer[Node]
    skipSeparators()
    while !at(Tok.RBRACE) do
      if at(Tok.EOF) then
        throw new IncompleteInput(s"unterminated $what, opened at line ${open.line}", open.pos)
      stmts += statement()
      skipSeparators()
    expect(Tok.RBRACE, s"to close a $what")
    if fn != null then
      fn.blockDepth -= 1
      fn.popBlock()
    new Block(stmts.toArray, open.line)

  /** `(expr)` or a bare expression, accepting both `if (c)` and `if c` forms. */
  private def conditionExpr(): Node = expression()

  private def exprStatement(): Node =
    val start = cur
    // `name = ...` may declare a brand-new variable, so handle it before the general
    // expression path turns the identifier into a read of an undefined name.
    if start.kind == Tok.IDENT then
      val nxt = peek(1).kind
      if nxt == Tok.ASSIGN then
        advance(); advance()
        val value = expression()
        if isDeclared(start.text) then
          if isConstTarget(start.text) then
            failAt(s"cannot assign to '${start.text}' because it is declared with 'val'", start)
          return setNode(resolve(start.text, start), value, start.line, start.text)
        else
          return setNode(declare(start.text, const = false, start), value, start.line)
      if isCompoundAssign(nxt) then
        advance()
        val opTok = advance()
        val value = expression()
        if !isDeclared(start.text) then
          failAt(s"cannot update '${start.text}' before it is declared", start)
        if isConstTarget(start.text) then
          failAt(s"cannot assign to '${start.text}' because it is declared with 'val'", start)
        val t = resolve(start.text, start)
        val combined = new Arith(compoundOp(opTok.kind), getNode(t, start.text, start.line), value, start.line)
        return setNode(t, combined, start.line)

    val e = expression()
    if at(Tok.ASSIGN) then
      advance()
      val value = expression()
      e match
        case ix: Index => new IndexSet(ix.recv, ix.key, value, start.line)
        case _: Call =>
          failWithHint("left side of '=' is not assignable", start,
            "a call result cannot be assigned to; assign to a variable, a[i] or o.key")
        case _ =>
          failWithHint("left side of '=' is not assignable", start,
            "assign to a variable, an element a[i], or a field o.key")
    else if isCompoundAssign(cur.kind) then
      val opTok = advance()
      val value = expression()
      e match
        case ix: Index => new IndexCompound(ix.recv, ix.key, compoundOp(opTok.kind), value, start.line)
        case _         => failAt(s"left side of '${opTok.text}' is not assignable", start)
    else e

  private def isCompoundAssign(kind: Int): Boolean =
    kind == Tok.PLUS_EQ || kind == Tok.MINUS_EQ || kind == Tok.STAR_EQ ||
      kind == Tok.SLASH_EQ || kind == Tok.PERCENT_EQ

  private def compoundOp(kind: Int): Int = kind match
    case Tok.PLUS_EQ    => BinOp.Add
    case Tok.MINUS_EQ   => BinOp.Sub
    case Tok.STAR_EQ    => BinOp.Mul
    case Tok.SLASH_EQ   => BinOp.Div
    case _              => BinOp.Mod

  // -------------------------------------------------------------------------
  // Expressions, by precedence
  // -------------------------------------------------------------------------

  def expression(): Node = pipeExpr()

  /**
   * `x |> f` is `f(x)` and `x |> f(a)` is `f(x, a)`, so a chain of transformations reads
   * in the order it happens. It binds loosest of all, which makes `a + b |> f` mean
   * `f(a + b)`.
   */
  private def pipeExpr(): Node =
    var left = orExpr()
    while at(Tok.PIPE_GT) do
      val t = advance()
      left = orExpr() match
        case call: Call =>
          // Insert the piped value as the first argument of an existing call.
          val args = new Array[Node](call.args.length + 1)
          args(0) = left
          System.arraycopy(call.args, 0, args, 1, call.args.length)
          new Call(call.callee, args, t.line)
        case target => new Call(target, Array(left), t.line)
    left

  private def orExpr(): Node =
    var left = andExpr()
    while at(Tok.OROR) || at(Tok.QQ) do
      val t = advance()
      if t.kind == Tok.OROR then left = new Or(left, andExpr(), t.line)
      else
        // `a ?? b` keeps a unless it is null; b only evaluates when needed. The
        // subject lands in a hidden slot so it is computed exactly once.
        val (tr, tw) = hiddenTemp(t.line)
        val rhs = andExpr()
        left = new Block(Array(
          tw(left),
          new If(Array(callBuiltin("isNull", t.line, tr())), Array(rhs), tr(), t.line)
        ), t.line)
    left

  private def andExpr(): Node =
    var left = eqExpr()
    while at(Tok.ANDAND) do
      val t = advance()
      left = new And(left, eqExpr(), t.line)
    left

  private def eqExpr(): Node =
    var left = relExpr()
    while at(Tok.EQ) || at(Tok.NE) do
      val t = advance()
      val op = if t.kind == Tok.EQ then CmpOp.Eq else CmpOp.Ne
      left = new Cmp(op, left, relExpr(), t.line)
    left

  private def relExpr(): Node =
    var left = addExpr()
    while at(Tok.LT) || at(Tok.LE) || at(Tok.GT) || at(Tok.GE) do
      val t = advance()
      val op = t.kind match
        case Tok.LT => CmpOp.Lt
        case Tok.LE => CmpOp.Le
        case Tok.GT => CmpOp.Gt
        case _      => CmpOp.Ge
      left = new Cmp(op, left, addExpr(), t.line)
    left

  private def addExpr(): Node =
    var left = mulExpr()
    while at(Tok.PLUS) || at(Tok.MINUS) do
      val t = advance()
      val op = if t.kind == Tok.PLUS then BinOp.Add else BinOp.Sub
      left = fold(op, left, mulExpr(), t.line)
    left

  private def mulExpr(): Node =
    var left = unary()
    while at(Tok.STAR) || at(Tok.SLASH) || at(Tok.PERCENT) do
      val t = advance()
      val op = t.kind match
        case Tok.STAR  => BinOp.Mul
        case Tok.SLASH => BinOp.Div
        case _         => BinOp.Mod
      left = fold(op, left, unary(), t.line)
    left

  /** Folds constant subexpressions so literal arithmetic costs nothing at run time. */
  private def fold(op: Int, l: Node, r: Node, line: Int): Node =
    (l, r) match
      case (a: Lit, b: Lit) if Values.isNum(a.v) && Values.isNum(b.v) =>
        try new Lit(Arith.apply(op, a.v, b.v), line)
        catch case _: EvalError => new Arith(op, l, r, line)
      case _ => new Arith(op, l, r, line)

  private def unary(): Node =
    val t = cur
    if t.kind == Tok.MINUS then
      advance()
      unary() match
        case lit: Lit =>
          lit.v match
            case VInt(x)   => new Lit(VInt.of(-x), t.line)
            case VFloat(x) => new Lit(VFloat(-x), t.line)
            case _         => new Neg(lit, t.line)
        case e => new Neg(e, t.line)
    else if t.kind == Tok.BANG then
      advance()
      new Not(unary(), t.line)
    else if t.kind == Tok.PLUS then
      advance()
      unary()
    else postfix()

  private def postfix(): Node =
    var e = primary()
    var go = true
    while go do
      val t = cur
      // A '(' or '[' at the start of a new line begins a new statement, not a call or
      // index on the previous expression.
      if t.kind == Tok.LPAREN && !t.startsLine then
        advance()
        val args = new mutable.ArrayBuffer[Node]
        if !at(Tok.RPAREN) then
          var more = true
          while more do
            args += expression()
            more = accept(Tok.COMMA)
        expect(Tok.RPAREN, "to close an argument list")
        e = from(t, new Call(e, args.toArray, t.line))
      else if t.kind == Tok.LBRACKET && !t.startsLine then
        advance()
        var k = expression()
        // `a[i, j]` is `a[i][j]`: each extra key indexes one level deeper, which is
        // what multidimensional (nested) arrays need. Assignment rewriting sees the
        // outermost Index and therefore writes through the same chain.
        while accept(Tok.COMMA) do
          e = from(t, new Index(e, k, t.line))
          k = expression()
        expect(Tok.RBRACKET, "to close an index")
        e = from(t, new Index(e, k, t.line))
      else if t.kind == Tok.DOT then
        advance()
        val nameTok = cur
        if nameTok.kind != Tok.IDENT && !Tok.reserved.contains(nameTok.text) then
          failAt("expected a field name after '.'", nameTok)
        advance()
        e = from(t, new Index(e, new Lit(VStr(nameTok.text), nameTok.line), t.line))
      else if t.kind == Tok.QDOT then
        // `o?.k` is one absence-safe hop: null receiver -> null, missing object
        // key -> null, anything else exactly as `.k` (including its errors). The
        // rule is local — chain `?.` on every hop you want forgiven; an unmarked
        // `.` after it stays strict. A call attached to the hop is inside it:
        // `o?.f(x)` is null when o is null, f is missing, or f's value is null.
        advance()
        val nameTok = cur
        if nameTok.kind != Tok.IDENT && !Tok.reserved.contains(nameTok.text) then
          failAt("expected a field name after '?.'", nameTok)
        advance()
        val (tr, tw) = hiddenTemp(t.line)
        val keyLit = () => new Lit(VStr(nameTok.text), nameTok.line)
        val access = new If(
          Array(callBuiltin("isNull", t.line, tr())),
          Array(new Lit(VNull, t.line): Node),
          new If(
            Array(callBuiltin("isObject", t.line, tr())),
            Array(callBuiltin("get", t.line, tr(), keyLit(), new Lit(VNull, t.line))),
            from(t, new Index(tr(), keyLit(), t.line)),
            t.line),
          t.line)
        if at(Tok.LPAREN) && !cur.startsLine then
          val lp = advance()
          val args = new mutable.ArrayBuffer[Node]
          if !at(Tok.RPAREN) then
            var more = true
            while more do
              args += expression()
              more = accept(Tok.COMMA)
          expect(Tok.RPAREN, "to close an argument list")
          val (vr, vw) = hiddenTemp(t.line)
          // The callee is wrapped in a one-statement Block: the interpreter names a
          // bare variable read in its "is not a function" error, and the hidden
          // temp's name must not leak — the generic phrasing also matches what the
          // compiled runtime prints, keeping the two engines byte-identical.
          e = new Block(Array(
            tw(e),
            vw(access),
            new If(Array(callBuiltin("isNull", t.line, vr())),
              Array(new Lit(VNull, t.line): Node),
              from(lp, new Call(new Block(Array(vr(): Node), lp.line), args.toArray, lp.line)), t.line)
          ), t.line)
        else
          e = new Block(Array(tw(e), access), t.line)
      else go = false
    e

  private def primary(): Node =
    val t = cur
    t.kind match
      case Tok.INT   => advance(); new Lit(VInt.of(t.ival), t.line)
      case Tok.FLOAT => advance(); new Lit(VFloat(t.dval), t.line)
      case Tok.STR   => advance(); new Lit(VStr(t.text), t.line)
      case Tok.STR_OPEN =>
        // `"a${x}b"` desugars to ("a" + x) + "b". String `+` renders the right side
        // with display, so interpolation and concatenation cannot disagree — and the
        // leading (possibly empty) literal is what forces string semantics.
        advance()
        var e: Node = new Lit(VStr(t.text), t.line)
        var closed = false
        while !closed do
          e = new Arith(BinOp.Add, e, expression(), t.line)
          cur.kind match
            case Tok.STR_MID =>
              val m = advance()
              if m.text.nonEmpty then e = new Arith(BinOp.Add, e, new Lit(VStr(m.text), m.line), m.line)
            case Tok.STR_CLOSE =>
              val cl = advance()
              if cl.text.nonEmpty then e = new Arith(BinOp.Add, e, new Lit(VStr(cl.text), cl.line), cl.line)
              closed = true
            case _ => fail(s"expected the interpolated string to continue, found ${describe(cur)}")
        e
      case Tok.TRUE  => advance(); new Lit(VBool.True, t.line)
      case Tok.FALSE => advance(); new Lit(VBool.False, t.line)
      case Tok.NULL  => advance(); new Lit(VNull, t.line)
      case Tok.IDENT if peek(1).kind == Tok.ARROW =>
        // `x -> e`: the parentheses around a single parameter are noise.
        val nameTok = advance()
        advance()
        val params = new Params(Array((nameTok.text, null: Node)), false)
        val proto = lambdaBody(params, nameTok.line)
        makeFun(proto, nameTok.line)
      case Tok.IDENT =>
        // `alias.name` is resolved here, at parse time: a namespace's names are
        // ordinary globals stored under a key that carries the namespace, so reaching
        // one costs exactly what reaching any other global costs.
        if isNamespaceRef(t) then
          advance(); advance()
          qualifiedGet(namespaceTag(t.text), t, advance())
        else
          advance()
          getNode(resolve(t.text, t), t.text, t.line)
      case Tok.LPAREN =>
        if looksLikeLambdaParams(p) then lambda()
        else
          advance()
          val e = expression()
          if at(Tok.COMMA) then
            // `(a, b)` builds a tuple. `(a)` stays plain grouping, so there are no
            // one-element tuples and no Python-style trailing-comma surprises.
            val items = new mutable.ArrayBuffer[Node]
            items += e
            while accept(Tok.COMMA) do
              if !at(Tok.RPAREN) then items += expression()
            expect(Tok.RPAREN, "to close a tuple")
            new TupleLit(items.toArray, t.line)
          else
            expect(Tok.RPAREN, "to close a parenthesised expression")
            e
      case Tok.LBRACKET => arrayLiteral()
      case Tok.LBRACE   => braceExpr()
      case Tok.IF       => ifExpr()
      case Tok.SELECT   => selectExpr()
      case Tok.WHILE    => whileStatement()
      case Tok.DEF      => funDecl()
      case Tok.MINUS | Tok.BANG => unary()
      case _ => fail(s"unexpected ${describe(t)} in an expression")

  /** True when the '(' at `from` opens a lambda parameter list rather than a group. */
  private def looksLikeLambdaParams(from: Int): Boolean =
    var i = from
    var depth = 0
    var guard = 0
    while i < toks.length && guard < 10000 do
      toks(i).kind match
        case Tok.LPAREN => depth += 1
        case Tok.RPAREN =>
          depth -= 1
          if depth == 0 then
            return i + 1 < toks.length && toks(i + 1).kind == Tok.ARROW
        case Tok.EOF => return false
        case _       => ()
      i += 1
      guard += 1
    false

  private def lambda(): Node =
    val start = cur
    val params = paramList()
    expect(Tok.ARROW, "in a lambda")
    val proto = lambdaBody(params, start.line)
    makeFun(proto, start.line)

  private def lambdaBody(params: Params, line: Int): FnProto =
    val ctx = new FnCtx(fn)
    fn = ctx
    var i = 0
    while i < params.length do
      ctx.alloc(params.entries(i)._1, const = false)
      i += 1
    val body =
      if at(Tok.LBRACE) then bracedBody("lambda body")
      else new Return(statement(), line)
    val proto = new FnProto("lambda", params.names, params.defaults, body, line, src, params.hasRest)
    proto.nSlots = ctx.nSlots
    proto.debugNames = ctx.slotNames.toArray
    fn = ctx.parent
    markTail(proto.body, tail = true)
    proto

  private def arrayLiteral(): Node =
    val open = advance()
    val items = new mutable.ArrayBuffer[Node]
    if !at(Tok.RBRACKET) then
      var more = true
      while more do
        if at(Tok.RBRACKET) then more = false // tolerate a trailing comma
        else
          items += expression()
          more = accept(Tok.COMMA)
    expect(Tok.RBRACKET, "to close an array literal")
    new ArrLit(items.toArray, open.line)

  /**
   * `{` is overloaded: it can open an object literal, a lambda, or a block. Decide by
   * looking ahead, which the old grammar could only approximate through backtracking.
   */
  private def braceExpr(): Node =
    val nxt = peek(1)
    val after = peek(2)
    if nxt.kind == Tok.RBRACE then
      advance(); advance()
      new ObjLit(Array.empty, Array.empty, cur.line)
    else if nxt.kind == Tok.IDENT && after.kind == Tok.ARROW then
      // `{ x -> ... }` — the block-wrapped lambda with its one parameter unwrapped.
      advance()
      val start = advance()
      advance()
      val params = new Params(Array((start.text, null: Node)), false)
      bracedLambdaBody(params, start.line, start)
    else if nxt.kind == Tok.LPAREN && looksLikeLambdaParams(p + 1) then
      // `{ (a, b) -> ... }`, the block-wrapped lambda form.
      advance()
      val start = cur
      val params = paramList()
      expect(Tok.ARROW, "in a lambda")
      bracedLambdaBody(params, start.line, start)
    else if (nxt.kind == Tok.STR || nxt.kind == Tok.IDENT) && after.kind == Tok.COLON then
      objectLiteral()
    else blockNode("block")

  /** The statements-to-`}` tail shared by both block-wrapped lambda forms. */
  private def bracedLambdaBody(params: Params, line: Int, opened: Token): Node =
    val ctx = new FnCtx(fn)
    fn = ctx
    var i = 0
    while i < params.length do
      ctx.alloc(params.entries(i)._1, const = false)
      i += 1
    val stmts = new mutable.ArrayBuffer[Node]
    skipSeparators()
    while !at(Tok.RBRACE) do
      if at(Tok.EOF) then
        throw new IncompleteInput(s"unterminated lambda, opened at line $line", opened.pos)
      stmts += statement()
      skipSeparators()
    expect(Tok.RBRACE, "to close a lambda")
    val proto = new FnProto(
      "lambda", params.names, params.defaults,
      new Block(stmts.toArray, line), line, src, params.hasRest
    )
    proto.nSlots = ctx.nSlots
    proto.debugNames = ctx.slotNames.toArray
    fn = ctx.parent
    markTail(proto.body, tail = true)
    makeFun(proto, line)

  private def objectLiteral(): Node =
    val open = advance()
    val keys = new mutable.ArrayBuffer[String]
    val vals = new mutable.ArrayBuffer[Node]
    if !at(Tok.RBRACE) then
      var more = true
      while more do
        if at(Tok.RBRACE) then more = false
        else
          val kt = cur
          val key =
            if kt.kind == Tok.STR || kt.kind == Tok.IDENT then { advance(); kt.text }
            else if Tok.reserved.contains(kt.text) then { advance(); kt.text }
            else failAt("expected a key in an object literal", kt)
          expect(Tok.COLON, "after an object key")
          if keys.contains(key) then failAt(s"duplicate key '$key' in object literal", kt)
          keys += key
          vals += expression()
          more = accept(Tok.COMMA)
    expect(Tok.RBRACE, "to close an object literal")
    new ObjLit(keys.toArray, vals.toArray, open.line)

  private def ifExpr(): Node =
    val kw = advance()
    val conds = new mutable.ArrayBuffer[Node]
    val bodies = new mutable.ArrayBuffer[Node]
    conds += conditionExpr()
    bodies += thenBody()
    var orElse: Node = null
    var go = true
    while go do
      if at(Tok.ELSIF) then
        advance()
        conds += conditionExpr()
        bodies += thenBody()
      else if at(Tok.ELSE) && peek(1).kind == Tok.IF then
        advance(); advance()
        conds += conditionExpr()
        bodies += thenBody()
      else if at(Tok.ELSE) then
        advance()
        orElse = thenBody()
        go = false
      else go = false
    new If(conds.toArray, bodies.toArray, orElse, kw.line)

  /** Accepts `then expr`, `then { ... }`, `{ ... }` and a bare statement. */
  private def thenBody(): Node =
    if accept(Tok.THEN) then
      if at(Tok.LBRACE) then bracedBody("block") else statement()
    else if at(Tok.LBRACE) then bracedBody("block")
    else statement()


  // -------------------------------------------------------------------------
  // Patterns
  //
  // A pattern parses directly into ordinary AST: one boolean expression that both
  // TESTS the subject and, via assignments threaded through `&&`'s short-circuit,
  // BINDS its variables. Nothing downstream knows patterns exist — the evaluator
  // and the compiler see nodes they already handle, which is what keeps the two
  // engines byte-identical without either learning a new construct.
  // -------------------------------------------------------------------------

  private var patCounter = 0

  /** Whether pattern bindings being parsed right now are `val`s. */
  private var patConst = false

  private def freshPatName(): String = { patCounter += 1; s"__pat$patCounter" }

  /**
   * A hidden temporary. Inside a function it is a fresh slot; at the top level a
   * global under a `#`-prefixed key no source identifier can spell, bypassing the
   * namespace rules on purpose — temps belong to no file.
   */
  private def hiddenTemp(line: Int): (() => Node, Node => Node) =
    val name = freshPatName()
    // Inside a parameter default the current FnCtx is the DEFINING function, and both
    // engines evaluate the default against that function's environment — but a slot
    // allocated here would be addressed through the callee's frame by the compiler.
    // A global temp reads and writes identically from anywhere, so defaults use one,
    // exactly as top-level code does. (A lambda nested in the default gets its own
    // FnCtx and uses ordinary slots again.)
    if fn != null && (fn ne defaultCtx) then
      val slot = fn.alloc(name, const = false)
      (() => new LocalGet(slot, name, line), v => new LocalSet(slot, v, line))
    else
      val id = globals.id("#" + name, name)
      (() => new GlobalGet(id, name, line), v => new GlobalSet(id, v, line))

  /** The FnCtx whose parameter default is being parsed right now, if any. */
  private var defaultCtx: FnCtx = null

  /**
   * Structural helpers are reached by their global id directly, not through lexical
   * scope: a local variable named `size` must not change what `[a, b]` means. A
   * global redefinition still does — patterns share the language's one builtin
   * table, exactly as extractors do.
   */
  private def rawBuiltin(name: String, line: Int): Node =
    new GlobalGet(globals.id(name), name, line)

  private def callBuiltin(name: String, line: Int, args: Node*): Node =
    new Call(rawBuiltin(name, line), args.toArray, line)

  /** The result of parsing one pattern: a test-and-bind expression plus metadata. */
  private final class PatOut(val test: Node, val lit: Value, val binds: Boolean)

  private def conj(l: Node, r: Node, line: Int): Node =
    if l == null then r else new And(l, r, line)

  /** `set; true` — a binding usable as an `&&` operand. */
  private def bindTrue(target: Target, value: Node, line: Int): Node =
    new Block(Array(setNode(target, value, line), new Lit(VBool.True, line)), line)

  /** Type extractors: `case int(x):` tests the type and matches x against the value. */
  private val typeExtractors = Map(
    "int" -> "isInt", "float" -> "isFloat", "bool" -> "isBool", "string" -> "isString",
    "array" -> "isArray", "object" -> "isObject", "function" -> "isFunction",
    "tuple" -> "isTuple", "number" -> "isNumber"
  )

  /** Skips one pattern's tokens, balancing brackets; stops at `stop` at depth 0. */
  private def skipPatternTokens(stop: Int, what: String): Unit =
    var depth = 0
    var guard = 0
    while guard < 10000 do
      val k = cur.kind
      if k == Tok.EOF then fail(s"the input ends inside a $what pattern")
      if depth == 0 && k == stop then return
      k match
        case Tok.LPAREN | Tok.LBRACKET | Tok.LBRACE => depth += 1
        case Tok.RPAREN | Tok.RBRACKET | Tok.RBRACE => depth -= 1
        case _                                       => ()
      advance()
      guard += 1
    fail(s"unterminated $what pattern")

  /**
   * One pattern, including alternatives. `dryRun` parses without declaring or
   * building — it only collects the names the pattern would bind, which is what a
   * top-level destructuring needs before the real parse so the namespace rules see
   * its names the way they see a plain `val`'s.
   */
  private def parsePattern(subj: () => Node, line: Int, inAlt: Boolean,
                           dryRun: Boolean, names: mutable.ArrayBuffer[String]): PatOut =
    val first = parseSinglePattern(subj, line, inAlt, dryRun, names)
    if !at(Tok.PIPE) then first
    else
      if first.binds then
        failAt("cannot bind variables inside a '|' alternative; use literals, '_' or '$name'", cur)
      var test = first.test
      while accept(Tok.PIPE) do
        val next = parseSinglePattern(subj, line, inAlt = true, dryRun, names)
        if !dryRun then test = new Or(test, next.test, line)
      new PatOut(test, null, binds = false)

  private def parseSinglePattern(subj: () => Node, line: Int, inAlt: Boolean,
                                 dryRun: Boolean, names: mutable.ArrayBuffer[String]): PatOut =
    // `name @ pattern` binds the whole subject as well as the parts.
    if at(Tok.IDENT) && peek(1).kind == Tok.AT && cur.text != "_" then
      val nameTok = advance()
      advance()
      if inAlt then failAt("cannot bind variables inside a '|' alternative", nameTok)
      val inner = parseSinglePattern(subj, line, inAlt, dryRun, names)
      names += nameTok.text
      if dryRun then inner
      else
        val target = declare(nameTok.text, const = patConst, nameTok)
        new PatOut(conj(inner.test, bindTrue(target, subj(), line), line), null, binds = true)
    else parseBasePattern(subj, line, inAlt, dryRun, names)

  private def parseBasePattern(subj: () => Node, line: Int, inAlt: Boolean,
                               dryRun: Boolean, names: mutable.ArrayBuffer[String]): PatOut =
    val t = cur
    def lit(v: Value): PatOut =
      if dryRun then new PatOut(null, v, binds = false)
      else new PatOut(new Cmp(CmpOp.Eq, subj(), new Lit(v, t.line), t.line), v, binds = false)
    t.kind match
      case Tok.INT   => advance(); lit(VInt.of(t.ival))
      case Tok.FLOAT => advance(); lit(VFloat(t.dval))
      case Tok.STR   => advance(); lit(VStr(t.text))
      case Tok.TRUE  => advance(); lit(VBool.True)
      case Tok.FALSE => advance(); lit(VBool.False)
      case Tok.NULL  => advance(); lit(VNull)
      case Tok.MINUS =>
        advance()
        val n = cur
        n.kind match
          case Tok.INT   => advance(); lit(VInt.of(-n.ival))
          case Tok.FLOAT => advance(); lit(VFloat(-n.dval))
          case _         => failAt("expected a number after '-' in a pattern", n)
      case Tok.LBRACKET => arrayPattern(subj, inAlt, dryRun, names)
      case Tok.LPAREN   => tuplePattern(subj, inAlt, dryRun, names)
      case Tok.LBRACE   => objectPattern(subj, inAlt, dryRun, names)
      case Tok.IDENT =>
        if t.text == "_" then
          advance()
          new PatOut(if dryRun then null else new Lit(VBool.True, t.line), null, binds = false)
        else if t.text.startsWith("$") && t.text.length > 1 then
          // `$name` pins: match against the CURRENT value of `name` instead of
          // binding it — the job Scala gives to backquotes.
          advance()
          val name = t.text.substring(1)
          if dryRun then new PatOut(null, null, binds = false)
          else
            val read = getNode(resolve(name, t), name, t.line)
            new PatOut(new Cmp(CmpOp.Eq, subj(), read, t.line), null, binds = false)
        else if peek(1).kind == Tok.LPAREN || dottedExtractorAhead() then
          extractorPattern(subj, inAlt, dryRun, names)
        else
          advance()
          if inAlt then failAt("cannot bind variables inside a '|' alternative", t)
          names += t.text
          if dryRun then new PatOut(null, null, binds = true)
          else
            val target = declare(t.text, const = patConst, t)
            new PatOut(bindTrue(target, subj(), t.line), null, binds = true)
      case _ =>
        // `val while = 1` lands here now that `val` accepts patterns; the reserved
        // word deserves the same explanation it got when only names were legal.
        if Tok.reserved.contains(t.text) then
          failWithHint(s"expected a pattern, found ${describe(t)}", t,
            s"'${t.text}' is a reserved word, so it cannot be used as a name")
        fail(s"expected a pattern, found ${describe(t)}")

  /**
   * Element list shared by array and extractor patterns: subpatterns with at most
   * one `...rest` anywhere. Elements before the rest index from the front; elements
   * after it index from the END, which negative indices express directly.
   */
  private def elementPatterns(subjF: () => Node, closing: Int, what: String, line: Int,
                              inAlt: Boolean, dryRun: Boolean,
                              names: mutable.ArrayBuffer[String]): (Node, Int, Boolean) =
    // A pre-scan finds the rest position, because the elements after it need to
    // know how many siblings follow them before their tests can be built.
    val scanStart = p
    var before = 0
    var after = 0
    var restAt = -1
    var depth = 0
    var guard = 0
    var idx = 0
    while guard < 10000 && !(depth == 0 && cur.kind == closing) do
      if cur.kind == Tok.EOF then fail(s"the input ends inside a $what pattern")
      cur.kind match
        case Tok.LPAREN | Tok.LBRACKET | Tok.LBRACE => depth += 1; advance()
        case Tok.RPAREN | Tok.RBRACKET | Tok.RBRACE => depth -= 1; advance()
        case Tok.COMMA if depth == 0                => idx += 1; advance()
        case Tok.DOT if depth == 0 && peek(1).kind == Tok.DOT && peek(2).kind == Tok.DOT =>
          // Only the full '...' marks a rest element. A lone dot at depth 0 belongs
          // to a dotted extractor callee such as `geo.Circle(r)` and is plain text.
          if restAt >= 0 then failAt(s"a $what pattern can have only one '...'", cur)
          restAt = idx
          advance(); advance(); advance()
        case _ => advance()
      guard += 1
    val total = if p == scanStart then 0 else idx + 1
    if restAt >= 0 then { before = restAt; after = total - restAt - 1 } else before = total
    p = scanStart

    var test: Node = null
    var i = 0
    var seenRest = false
    var restBinds = false
    if !at(closing) then
      var more = true
      while more do
        if at(Tok.DOT) && peek(1).kind == Tok.DOT && peek(2).kind == Tok.DOT then
          advance(); advance(); advance()
          seenRest = true
          if at(Tok.IDENT) && cur.text != "_" then
            val nameTok = advance()
            if inAlt then failAt("cannot bind variables inside a '|' alternative", nameTok)
            names += nameTok.text
            restBinds = true
            if !dryRun then
              val target = declare(nameTok.text, const = patConst, nameTok)
              // rest = drop(take(subject, size - after), before)
              val taken = callBuiltin("take", line, subjF(),
                new Arith(BinOp.Sub, callBuiltin("size", line, subjF()), new Lit(VInt.of(after), line), line))
              val rest = callBuiltin("drop", line, taken, new Lit(VInt.of(before), line))
              test = conj(test, bindTrue(target, rest, line), line)
        else
          val elemIdx = i
          val subjElem: () => Node =
            if !seenRest then () => new Index(subjF(), new Lit(VInt.of(elemIdx), line), line)
            else
              // The k-th element after the rest is the (after-k)-th from the end.
              val fromEnd = after - (elemIdx - before)
              () => new Index(subjF(), new Lit(VInt.of(-fromEnd), line), line)
          val sub = parsePattern(subjElem, line, inAlt, dryRun, names)
          if sub.binds then restBinds = true
          if !dryRun then test = conj(test, sub.test, line)
          i += 1
        more = accept(Tok.COMMA)
    (test, total, seenRest)

  private def arrayPattern(subjF: () => Node, inAlt: Boolean, dryRun: Boolean,
                           names: mutable.ArrayBuffer[String]): PatOut =
    val open = advance()
    val line = open.line
    val (elems, total, hasRest) =
      elementPatterns(subjF, Tok.RBRACKET, "array", line, inAlt, dryRun, names)
    expect(Tok.RBRACKET, "to close an array pattern")
    if dryRun then return new PatOut(null, null, binds = true)
    val fixed = if hasRest then total - 1 else total
    val sizeOk =
      if hasRest then
        new Cmp(CmpOp.Ge, callBuiltin("size", line, subjF()), new Lit(VInt.of(fixed), line), line)
      else
        new Cmp(CmpOp.Eq, callBuiltin("size", line, subjF()), new Lit(VInt.of(total), line), line)
    var test = conj(callBuiltin("isArray", line, subjF()), sizeOk, line)
    if elems != null then test = conj(test, elems, line)
    new PatOut(test, null, binds = true)

  private def tuplePattern(subjF: () => Node, inAlt: Boolean, dryRun: Boolean,
                           names: mutable.ArrayBuffer[String]): PatOut =
    val open = advance()
    val line = open.line
    val subs = new mutable.ArrayBuffer[PatOut]
    val subFs = new mutable.ArrayBuffer[() => Node]
    var i = 0
    var more = true
    while more do
      val elemIdx = i
      val f: () => Node = () => new Index(subjF(), new Lit(VInt.of(elemIdx), line), line)
      subs += parsePattern(f, line, inAlt, dryRun, names)
      subFs += f
      i += 1
      more = accept(Tok.COMMA)
    expect(Tok.RPAREN, "to close a tuple pattern")
    if subs.length < 2 then
      failAt("a tuple pattern needs at least two elements; for grouping just drop the parentheses", open)
    if dryRun then return new PatOut(null, null, binds = subs.exists(_.binds))
    var test = conj(
      callBuiltin("isTuple", line, subjF()),
      new Cmp(CmpOp.Eq, callBuiltin("size", line, subjF()), new Lit(VInt.of(subs.length), line), line),
      line)
    for sub <- subs do test = conj(test, sub.test, line)
    new PatOut(test, null, binds = subs.exists(_.binds))

  private def objectPattern(subjF: () => Node, inAlt: Boolean, dryRun: Boolean,
                            names: mutable.ArrayBuffer[String]): PatOut =
    val open = advance()
    val line = open.line
    var test: Node = if dryRun then null else callBuiltin("isObject", line, subjF())
    var binds = false
    if !at(Tok.RBRACE) then
      var more = true
      while more do
        val keyTok = cur
        if keyTok.kind != Tok.STR && keyTok.kind != Tok.IDENT then
          failAt("expected a key in an object pattern", keyTok)
        advance()
        val key = keyTok.text
        val keySubj: () => Node =
          () => new Index(subjF(), new Lit(VStr(key), line), line)
        if accept(Tok.COLON) then
          val hasKey = if dryRun then null
            else callBuiltin("has", line, subjF(), new Lit(VStr(key), line))
          val sub = parsePattern(keySubj, line, inAlt, dryRun, names)
          if sub.binds then binds = true
          if !dryRun then test = conj(conj(test, hasKey, line), sub.test, line)
        else
          // `{name}` is shorthand for `{name: name}`: require the key, bind the value.
          if inAlt then failAt("cannot bind variables inside a '|' alternative", keyTok)
          names += key
          binds = true
          if !dryRun then
            val target = declare(key, const = patConst, keyTok)
            val hasKey = callBuiltin("has", line, subjF(), new Lit(VStr(key), line))
            test = conj(conj(test, hasKey, line), bindTrue(target, keySubj(), line), line)
        more = accept(Tok.COMMA)
    expect(Tok.RBRACE, "to close an object pattern")
    new PatOut(test, null, binds)

  /** True when the tokens ahead spell `.name….name(` — a dotted extractor callee. */
  private def dottedExtractorAhead(): Boolean =
    var k = 1
    while peek(k).kind == Tok.DOT && isMemberName(peek(k + 1)) do k += 2
    k > 1 && peek(k).kind == Tok.LPAREN

  /** What may follow a '.' in a member chain — the same rule postfix '.' applies. */
  private def isMemberName(t: Token): Boolean =
    t.kind == Tok.IDENT || Tok.reserved.contains(t.text)

  /**
   * `name(patterns)` or `a.b.name(patterns)`. A bare type name tests the subject's
   * type. Anything else is an EXTRACTOR — an ordinary function applied to the
   * subject: with no subpatterns a truthy result matches; otherwise it must return
   * null (no match) or an array whose elements the subpatterns are matched against.
   * Scala's unapply, with "Some/None" spelled "[values]/null". A dotted callee reads
   * exactly as an expression would read it: `alias.name` resolves at parse time when
   * `alias` is a module, and every other hop is a field access.
   */
  private def extractorPattern(subjF: () => Node, inAlt: Boolean, dryRun: Boolean,
                               names: mutable.ArrayBuffer[String]): PatOut =
    val nameTok = advance()
    val line = nameTok.line
    if at(Tok.DOT) then
      var callee: Node = null
      if namespaceTag(nameTok.text) != null && !isLocallyBound(nameTok.text) &&
         !topLevelNames.contains(nameTok.text) && peek(1).kind == Tok.IDENT then
        advance() // '.'
        val member = advance()
        if !dryRun then callee = qualifiedGet(namespaceTag(nameTok.text), nameTok, member)
      else if !dryRun then
        callee = getNode(resolve(nameTok.text, nameTok), nameTok.text, line)
      while at(Tok.DOT) do
        val dotTok = advance()
        val member = cur
        if !isMemberName(member) then failAt("expected a field name after '.'", member)
        advance()
        if !dryRun then
          callee = new Index(callee, new Lit(VStr(member.text), member.line), dotTok.line)
      advance() // '(' — dottedExtractorAhead saw it before this branch was chosen
      extractorApply(callee, subjF, inAlt, dryRun, names, line)
    else
      advance() // '('
      typeExtractors.get(nameTok.text) match
        case Some(pred) =>
          val sub = parsePattern(subjF, line, inAlt, dryRun, names)
          expect(Tok.RPAREN, s"to close the '${nameTok.text}(...)' pattern")
          if dryRun then sub
          else new PatOut(conj(callBuiltin(pred, line, subjF()), sub.test, line), null, sub.binds)
        case None =>
          // The extractor resolves lexically: a local function works, exactly as any
          // other call site would see it.
          val callee = if dryRun then null else getNode(resolve(nameTok.text, nameTok), nameTok.text, line)
          extractorApply(callee, subjF, inAlt, dryRun, names, line)

  /** The call-and-test half every non-type extractor shares, entered after its '('. */
  private def extractorApply(callee: Node, subjF: () => Node, inAlt: Boolean, dryRun: Boolean,
                             names: mutable.ArrayBuffer[String], line: Int): PatOut =
    if at(Tok.RPAREN) then
      advance()
      if dryRun then return new PatOut(null, null, binds = false)
      val (tr, tw) = hiddenTemp(line)
      val store = new Block(Array(tw(new Call(callee, Array(subjF()), line)), new Lit(VBool.True, line)), line)
      // `And(result, true)` is a pure truthiness test on the stored result.
      new PatOut(conj(store, new And(tr(), new Lit(VBool.True, line), line), line), null, binds = false)
    else
      val (tr, tw) = if dryRun then (null, null) else
        val pair = hiddenTemp(line)
        (pair._1, pair._2)
      val (elems, total, hasRest) =
        elementPatterns(if tr == null then subjF else tr, Tok.RPAREN, "extractor", line, inAlt, dryRun, names)
      expect(Tok.RPAREN, "to close an extractor pattern")
      if dryRun then return new PatOut(null, null, binds = true)
      val fixed = if hasRest then total - 1 else total
      val store = new Block(Array(tw(new Call(callee, Array(subjF()), line)), new Lit(VBool.True, line)), line)
      val notNull = new Not(callBuiltin("isNull", line, tr()), line)
      val shape = callBuiltin("isArray", line, tr())
      val sizeOk =
        if hasRest then new Cmp(CmpOp.Ge, callBuiltin("size", line, tr()), new Lit(VInt.of(fixed), line), line)
        else new Cmp(CmpOp.Eq, callBuiltin("size", line, tr()), new Lit(VInt.of(total), line), line)
      var test = conj(conj(conj(store, notNull, line), shape, line), sizeOk, line)
      if elems != null then test = conj(test, elems, line)
      new PatOut(test, null, binds = true)

  private def selectExpr(): Node =
    val kw = advance()
    val subject = conditionExpr()
    expect(Tok.LBRACE, "to start a 'select' body")
    // The subject temp is allocated up front because patterns parse against it; a
    // select whose cases are all plain literals never stores into it and keeps the
    // original Select node, so existing programs keep their exact shape.
    val (subjF, subjW) = hiddenTemp(kw.line)
    val tests = new mutable.ArrayBuffer[Node]
    val bodies = new mutable.ArrayBuffer[Node]
    val lits = new mutable.ArrayBuffer[Value]
    var allLits = true
    var default: Node = null
    val names = new mutable.ArrayBuffer[String]
    skipSeparators()
    while !at(Tok.RBRACE) do
      if at(Tok.EOF) then
        throw new IncompleteInput(s"unterminated 'select', opened at line ${kw.line}", kw.pos)
      if accept(Tok.CASE) then
        val savedDepth = if fn != null then fn.blockDepth else 0
        if fn != null then fn.blockDepth += 1
        val pat = parsePattern(subjF, kw.line, inAlt = false, dryRun = false, names)
        val guard = if accept(Tok.IF) then expression() else null
        expect(Tok.COLON, "after a 'case' pattern")
        val body = caseBody()
        if fn != null then
          fn.blockDepth = savedDepth
          fn.popBlock()
        tests += (if guard == null then pat.test else new And(pat.test, guard, kw.line))
        bodies += body
        if pat.lit != null && guard == null then lits += pat.lit else allLits = false
      else if accept(Tok.DEFAULT) then
        expect(Tok.COLON, "after 'default'")
        if default != null then failAt("'select' has more than one 'default'", cur)
        default = caseBody()
      else fail(s"expected 'case' or 'default', found ${describe(cur)}")
      skipSeparators()
    expect(Tok.RBRACE, "to close a 'select'")
    if allLits then
      new Select(subject, lits.map(v => new Lit(v, kw.line): Node).toArray,
        bodies.toArray, default, kw.line)
    else
      new Block(Array(subjW(subject), new If(tests.toArray, bodies.toArray, default, kw.line)), kw.line)

  private def caseBody(): Node =
    if at(Tok.LBRACE) then bracedBody("case body") else statement()

/**
 * Resolves and reads imported files. Keeps an in-flight set so a cyclic import
 * reports an error instead of recursing forever, and remembers what has been loaded so
 * repeated imports of a shared module are a no-op.
 */
final class Importer(val baseDir: File):
  private val loaded = mutable.HashSet.empty[String]
  private val inFlight = mutable.LinkedHashSet.empty[String]

  private def libDir: String = sys.env.getOrElse("SFL_HOME", ".") + File.separator + "libs"

  // -------------------------------------------------------------------------
  // Namespaces
  //
  // Every file belongs to exactly one namespace, decided by where the file is and
  // not by how it was imported — which is what lets the same module be imported
  // plainly here and behind an alias there without its names moving.
  //
  // A file under an `sfl.pkg` manifest shares that unit's namespace with its
  // siblings: a package's seven modules are one `httpd`, and a project's `src/`
  // files are one project, so neither has to import itself to see its own names.
  // A file with no manifest above it is a namespace of its own.
  //
  // The tag is the manifest directory (or the file), so two units can carry the
  // same name without ever meeting; the label is what a person calls it.
  // -------------------------------------------------------------------------

  private val nsCache = mutable.HashMap.empty[String, (String, String)]
  private val manifestCache = mutable.HashMap.empty[String, Option[(String, String)]]
  private val unitTags = mutable.HashSet.empty[String]

  /**
   * True when a namespace names a unit — a package or a project, something with an
   * `sfl.pkg` that says what it is called. A plain import of one binds its name and
   * stops there; only a bare file, which is just a piece of the program being
   * written, puts its surface in the importer's scope.
   */
  def isUnit(tag: String): Boolean = unitTags.contains(tag)

  /** The (tag, label) of the namespace `path` belongs to. */
  def namespaceOf(path: String): (String, String) =
    nsCache.getOrElseUpdate(path, {
      val f = new File(path)
      // A synthetic source — `<repl>`, `<sfl-build>`, a debugger watch — is a
      // namespace of its own wherever the process happens to be running.
      if !f.isFile then (path, path)
      else
        val dir = if f.getParentFile != null then f.getParentFile else baseDir
        unitAbove(dir) match
          case Some(unit) => unitTags += unit._1; unit
          case None =>
            val base = f.getName
            (path, if base.endsWith(".sfl") then base.dropRight(4) else base)
    })

  /**
   * The nearest `sfl.pkg` at or above `dir`, as the namespace it names.
   *
   * Walked as a loop rather than by recursion: every level shares one cache, and
   * a nested `getOrElseUpdate` on a mutable map is not defined to work.
   */
  private def unitAbove(dir: File): Option[(String, String)] =
    val chain = mutable.ArrayBuffer.empty[String]
    // Canonical first: `new File("tests").getParentFile` is null, and walking up
    // from a relative path would stop before it ever reached the project root.
    var d = if dir == null then null else
      try dir.getCanonicalFile catch case _: java.io.IOException => dir.getAbsoluteFile
    var found: Option[(String, String)] = None
    var depth = 0
    while d != null && found.isEmpty && depth < 64 do
      val key = try d.getCanonicalPath catch case _: java.io.IOException => d.getPath
      manifestCache.get(key) match
        case Some(cached) => found = cached
        case None =>
          val manifest = PkgTool.manifestFile(d)
          if manifest.isFile then
            found =
              try
                val name = PkgTool.readManifest(d).name
                if name.nonEmpty then Some((key, name)) else None
              catch case _: Throwable => None
            if found.isDefined then manifestCache(key) = found
          if found.isEmpty then chain += key
      d = d.getParentFile
      depth += 1
    // Every directory passed on the way up answers the same way this one did.
    for key <- chain do manifestCache(key) = found
    found

  /**
   * The public names a namespace exports, filled as each of its files is parsed.
   * A module is parsed once, so a second import — and `eval`, and the language
   * server asking after the fact — reads the surface from here.
   */
  private val surfaces = mutable.HashMap.empty[String, mutable.LinkedHashSet[String]]

  def recordSurface(tag: String, names: Iterable[String]): Unit =
    val set = surfaces.getOrElseUpdate(tag, mutable.LinkedHashSet.empty)
    for n <- names if !n.startsWith("_") do set += n

  def surfaceOf(tag: String): Set[String] = surfaces.get(tag).map(_.toSet).getOrElse(Set.empty)

  // -------------------------------------------------------------------------
  // Packages
  //
  // When no file resolves the old way, the import's first segment is tried as an
  // installed package name. The result of that search is still just a file, which
  // is what keeps packages orthogonal to everything else: qualified imports and
  // file-private names work in a package module because the parser never learns
  // the file came from one.
  // -------------------------------------------------------------------------

  /**
   * FLAT MODEL: the one version of each package this program run is using. The
   * first import binds it; every later import must be satisfiable by that same
   * version, because two versions of one package would each define the same
   * global names. The state lives here so that recreating the Importer — which
   * happens per program run — starts the next run unbound.
   */
  private final class PkgBinding(
      val version: PkgTool.Version,
      val dir: File,
      val manifest: PkgTool.Manifest,
      val requirer: String,
      val range: Option[PkgTool.Range]
  )
  private val pkgBindings = mutable.HashMap.empty[String, PkgBinding]

  /**
   * The version directories resolved so far, canonical and slash-terminated. A
   * module's imports are governed by the manifest of the package it lives in, and
   * "lives in" is decided by path prefix so that files a package reaches through
   * plain relative imports are covered too.
   */
  private val pkgDirs = mutable.ArrayBuffer.empty[(String, PkgTool.Manifest)]

  /**
   * Canonical module path -> where it came from: (package name, module path
   * relative to the version directory, canonical version directory). The compiler
   * reads this through [[packageProvenance]] to decide whether a module can be
   * linked from a prebuilt archive rather than recompiled. Only modules reached as
   * `import "pkg"` / `import "pkg/mod"` are recorded; a package file reached by a
   * plain relative path is not, and compiles from source as any local file does.
   */
  private val provenance = mutable.HashMap.empty[String, (String, String, String)]

  /** The package a resolved module path belongs to, if the importer resolved it as one. */
  def packageProvenance(path: String): Option[(String, String, String)] = provenance.get(path)

  /**
   * `./sfl.pkg`, when the working directory has one: the manifest that supplies
   * ranges for the top-level program's own imports. Read lazily so a program that
   * never touches packages never parses it, and never fails on it either.
   */
  private lazy val cwdManifest: Option[PkgTool.Manifest] =
    if PkgTool.manifestFile(new File(".")).isFile then Some(PkgTool.readManifest(new File(".")))
    else None

  /** The manifest of the package `fromSource` belongs to, if it belongs to one. */
  private def owningPackage(fromSource: SourceRef): Option[PkgTool.Manifest] =
    val path =
      try new File(fromSource.name).getCanonicalPath
      catch case _: java.io.IOException => fromSource.name
    pkgDirs.collectFirst { case (dir, m) if path.startsWith(dir) => m }

  /**
   * Resolves `name` as `<package>[/module]`. Returns `None` only when the first
   * segment names no installed package at all, so the caller can report a plain
   * missing module; any problem beyond that point is a definite package error and
   * is raised here, anchored at the import.
   */
  private def resolvePackage(name: String, fromSource: SourceRef, pos: Pos): Option[File] =
    val slash = name.indexOf('/')
    val pkgName = if slash < 0 then name else name.substring(0, slash)
    val rest = if slash < 0 then "" else name.substring(slash + 1)
    if !PkgTool.isValidName(pkgName) then return None
    val span = name.length + 2 // underline the quoted import string

    def locate(e: PkgTool.PkgError): Nothing =
      Err.parseHint(e.getMessage, pos, fromSource, span, e.helps*)

    try
      // The first root that has the package wins outright, so a project-local
      // install shadows the global one instead of competing with it on version.
      val root = PkgTool.roots.find(r => PkgTool.installedVersions(r, pkgName).nonEmpty) match
        case Some(r) => r
        case None    => return None
      val installed = PkgTool.installedVersions(root, pkgName)

      // Whose range governs this import: the manifest of the package the importing
      // file lives in, or ./sfl.pkg for the top-level program. A package module
      // importing a package its manifest does not declare is refused outright —
      // an undeclared dependency would float to "highest installed" and change
      // behaviour the day something else is installed.
      val (range, requirer) = owningPackage(fromSource) match
        case Some(owner) =>
          owner.dep(pkgName) match
            case Some(r) => (Some(r), s"package '${owner.name}'")
            case None =>
              Err.parseHint(
                s"package '${owner.name}' does not declare a dependency on '$pkgName'",
                pos, fromSource, span,
                s"declare it in ${owner.name}'s sfl.pkg: \"deps\": {\"$pkgName\": \"^${installed.last}\"}")
        case None =>
          cwdManifest.flatMap(_.dep(pkgName)) match
            case Some(r) => (Some(r), "sfl.pkg")
            case None    => (None, fromSource.name)

      val binding = pkgBindings.get(pkgName) match
        case Some(bound) =>
          // Already bound: a later range only has to accept the bound version.
          range match
            case Some(r) if !r.satisfies(bound.version) =>
              Err.parseHint(
                s"package '$pkgName' is already loaded at version ${bound.version}, " +
                  s"but $requirer needs ${r.text}",
                pos, fromSource, span,
                s"${bound.requirer} selected ${bound.version}" +
                  bound.range.fold(" with no declared range")(br => s" via ${br.text}"),
                "one program run uses a single version of each package; align the two ranges")
            case _ => bound
        case None =>
          val eligible = range match
            case Some(r) => installed.filter(r.satisfies)
            case None    => installed
          if eligible.isEmpty then
            val be = if installed.length == 1 then "is" else "are"
            Err.parseHint(
              s"package '$pkgName' ${PkgTool.listVersions(installed)} $be installed, " +
                s"but $requirer needs ${range.get.text}",
              pos, fromSource, span,
              "install a matching version with 'sfl pkg install'")
          val version = eligible.last // sorted ascending: the highest that satisfies
          val dir = PkgTool.versionDir(root, pkgName, version)
          val manifest = PkgTool.readManifest(dir)
          if manifest.name != pkgName then
            Err.parseHint(
              s"the directory for package '$pkgName' $version holds a manifest for '${manifest.name}'",
              pos, fromSource, span,
              s"${PkgTool.manifestFile(dir).getPath} disagrees with where it is installed; " +
                "reinstall with 'sfl pkg install'")
          val b = new PkgBinding(version, dir, manifest, requirer, range)
          pkgBindings(pkgName) = b
          pkgDirs += ((dir.getCanonicalPath + File.separator, manifest))
          b

      // A bare `import "pkg"` loads the manifest's main module; `pkg/a/b` maps to
      // a/b.sfl under the version directory.
      val module = if rest.isEmpty then binding.manifest.main else rest
      val file = new File(binding.dir, module + ".sfl")
      if file.isFile && file.canRead then
        provenance(file.getCanonicalPath) =
          (pkgName, module, binding.dir.getCanonicalPath + File.separator)
        Some(file)
      else
        Err.parseHint(
          s"package '$pkgName' ${binding.version} has no module '$module'",
          pos, fromSource, span,
          s"looked for $module.sfl under ${binding.dir.getPath}")
    catch case e: PkgTool.PkgError => locate(e)

  def resolve(name: String, fromSource: SourceRef): Option[File] =
    val withExt = if name.endsWith(".sfl") then name else name + ".sfl"
    val fromDir =
      val f = new File(fromSource.name)
      if f.getParentFile != null then f.getParentFile else baseDir
    val candidates = List(
      new File(withExt),
      new File(fromDir, withExt),
      new File(baseDir, withExt),
      new File(libDir, withExt),
      new File(name),
      new File(fromDir, name)
    )
    candidates.find(f => f.isFile && f.canRead)

  /** The canonical path of a module, or a parse error naming where it was looked for. */
  def pathOf(name: String, fromSource: SourceRef, pos: Pos): String =
    // Files first, exactly as before packages existed: a name that resolves to a
    // file keeps meaning that file, so no existing program changes behaviour.
    resolve(name, fromSource) match
      case Some(file) => file.getCanonicalPath
      case None =>
        resolvePackage(name, fromSource, pos) match
          case Some(file) => file.getCanonicalPath
          case None =>
            Err.parseHint(
              s"cannot find module '$name' (looked in the current directory, " +
                s"the importing file's directory, and $libDir)",
              pos, fromSource, name.length + 2,
              s"packages are searched in ./sfl_packages and ${PkgTool.globalRoot.getPath}; " +
                "'sfl pkg install' puts them there"
            )

  /**
   * Returns the module's contents, or `None` when it has already been imported. The
   * caller must call [[finish]] once it has finished parsing the text, so that cycles
   * are detected across the whole nested parse.
   */
  def begin(path: String): Option[String] =
    if inFlight.contains(path) then
      Err.eval(s"circular import (${(inFlight.toList :+ path).mkString(" -> ")})")
    if loaded.contains(path) then None
    else
      loaded += path
      inFlight += path
      Some(FileUtil.read(path))

  def finish(path: String): Unit = inFlight -= path
