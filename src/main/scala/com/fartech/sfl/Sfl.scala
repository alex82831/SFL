package com.fartech.sfl

import java.io.File

/**
 * The public entry point: owns the global environment and turns source text into
 * results. A single instance of the globals is shared by scripts, `import`, `eval`
 * and the REPL, so definitions made in one place are visible in the others.
 */
object Sfl:
  val version: String = "0.8.3"

  val globals: Globals = new Globals

  /** The main thread's interpreter. Each additional thread gets its own. */
  val interp: Interp = new Interp(globals)

  var importer: Importer = new Importer(new File("."))

  /**
   * Namespace aliases bound by `import` at the top level. The REPL parses each entry
   * on its own, so this is what makes an alias outlive the line that bound it.
   */
  private val sessionNamespaces: scala.collection.mutable.Map[String, String] =
    scala.collection.mutable.HashMap.empty

  /**
   * The namespace an interactive entry — a REPL line, `sfl -e`, a debugger watch —
   * declares into. One tag for the whole session, so `def f` typed at one prompt is
   * still there at the next, and none of it can reach the builtins.
   */
  val sessionTag: String = "<session>"

  /** Names the interactive session has defined, for `:vars` and completion. */
  def sessionNames: Seq[String] = globals.membersOf(sessionTag)

  /** A name as the session sees it: its own definitions first, then the builtins. */
  def sessionValueOf(name: String): Option[Value] =
    globals.valueOf(s"$sessionTag#$name").orElse(globals.valueOf(name))

  /**
   * The members of a namespace the session can reach by that name — an alias bound
   * by `import`, or one of the builtin namespaces — or None when it names none.
   */
  def namespaceMembers(alias: String): Option[Seq[String]] =
    val tag =
      if sessionNamespaces.contains(alias) then sessionNamespaces(alias)
      else if Builtins.isNamespace(alias) then alias
      else null
    if tag == null then None else Some(globals.membersOf(tag))

  @volatile private var initialised = false

  def init(): Unit =
    synchronized {
      if !initialised then
        Builtins.install(globals)
        initialised = true
    }
    // Thread-local, so this must run once on every thread that evaluates SFL code.
    if Builtins.interp == null then Builtins.interp = interp

  /**
   * Gives the calling thread an interpreter of its own, sharing the globals. Called when
   * a thread starts; the returned instance becomes that thread's [[Builtins.rt]].
   */
  def attachThread(): Interp =
    init()
    val existing = Builtins.interp
    if existing != null && (existing ne interp) then existing
    else
      val fresh = new Interp(globals)
      Builtins.interp = fresh
      fresh

  /**
   * Parses `source` without running it. Serialised: parsing mutates the global table.
   *
   * `ns` names the namespace the text declares into: `null` lets the importer decide
   * from where the file is, which is what a script wants. The REPL and `eval` pass
   * one explicitly — see [[sessionTag]] and [[callerTag]].
   */
  def compile(source: String, name: String, ns: String = null): (Block, SourceRef) =
    init()
    synchronized {
      val ref = SourceRef(name, source)
      val parser = new Parser(ref, globals, importer, ns, sessionNamespaces)
      (parser.parseProgram(), ref)
    }

  /**
   * The namespace of the code currently running, which is what `eval` and `parse`
   * compile into: text handed to `eval` sees exactly what the file calling it sees,
   * and nothing more.
   */
  def callerTag: String =
    val rt = Builtins.interp
    val src = if rt == null then null else rt.src
    if src == null || src.name.isEmpty || src == SourceRef.unknown then sessionTag
    else if src.hidden then "" // standard-library source: the root namespace
    else importer.namespaceOf(src.name)._1

  /**
   * Parses only to find out whether the text is a complete program, which is what the
   * REPL needs before deciding to prompt for more.
   *
   * It gets an importer of its own because the shared one remembers what it has loaded
   * so a module runs once — and a probe that marked a module loaded made the real parse
   * skip it, so `import` in the REPL quietly did nothing.
   */
  def probe(source: String, name: String): Unit =
    init()
    synchronized {
      val ref = SourceRef(name, source)
      // A copy of the aliases, so probing cannot bind one that the entry never ran.
      new Parser(ref, globals, new Importer(importer.baseDir), sessionTag,
        scala.collection.mutable.HashMap.from(sessionNamespaces)).parseProgram()
    }

  /**
   * Wraps source text in a zero-argument function, which is what `parse()` hands back
   * and what `eval()` invokes.
   */
  def compileThunk(source: String, name: String, ns: String = null): VFun =
    // Anything reached through eval()/parse() must fail with a locatable, catchable
    // error. Only the REPL wants the raw "input ran out" signal, to prompt for more.
    val (block, ref) =
      try compile(source, name, ns)
      catch case e: IncompleteInput => throw Err.fromIncomplete(e, SourceRef(name, source))
    val proto = new FnProto(name, Array.empty, Array.empty, block, 1, ref)
    proto.nSlots = 0
    new VFun(proto, Frame.root)

  /**
   * compileThunk for a shared-source standard-library module (see StdlibModules):
   * the ref is hidden, so errors raised inside the module pin at the user's call
   * site and its frames stay out of tracebacks — what compiled programs do, where
   * library code stores no positions at all.
   */
  def compileStdlibThunk(source: String, name: String): VFun =
    init()
    val (block, ref) = synchronized {
      val r = SourceRef(name, source, hidden = true)
      val parser = new Parser(r, globals, importer, "", sessionNamespaces)
      (parser.parseProgram(), r)
    }
    val proto = new FnProto(name, Array.empty, Array.empty, block, 1, ref)
    proto.nSlots = 0
    new VFun(proto, Frame.root)

  /** Compiles and runs, returning the value of the last top-level statement. */
  def run(source: String, name: String, ns: String = null): Value =
    val (block, ref) = compile(source, name, ns)
    execute(block, ref)

  def execute(block: Block, ref: SourceRef): Value =
    init()
    // Run on the calling thread's interpreter, which on the main thread is `interp`.
    val rt = Builtins.rt
    rt.resetStack()
    rt.src = ref
    try block.eval(Frame.root, rt)
    catch case e: EvalError => throw rt.decorate(e)
    finally rt.signal = Sig.None

  def runFile(path: String): Value = runFileInto(path, null)

  /**
   * Runs a file's source, optionally in a namespace of the caller's choosing.
   * A script gets its own — that is what makes a file's top-level names its own —
   * but the REPL's `:load` passes the session's, because a file loaded into a
   * session is meant to define things the session can then call.
   */
  def runFileInto(path: String, ns: String): Value =
    val f = new File(path)
    if !f.isFile then Err.eval(s"cannot run '$path': no such file")
    importer = new Importer(if f.getParentFile != null then f.getParentFile else new File("."))
    run(FileUtil.read(f), f.getPath, ns)

  /**
   * Clears every user definition while keeping the builtins. The Globals object itself
   * stays put because already-compiled code holds indices into it.
   */
  def reset(): Unit =
    init()
    for name <- globals.definedNames do
      if Builtins.lookup(name).isEmpty then globals.set(globals.id(name), null)
    // Whatever the session itself declared, which is where `def f` at the prompt
    // goes now that nothing a program writes lands among the builtins.
    for name <- globals.membersOf(sessionTag) do
      globals.set(globals.id(s"$sessionTag#$name"), null)
    importer = new Importer(new File("."))
    sessionNamespaces.clear()
    interp.resetStack()

/** Renders the AST, for the `--ast` flag and the REPL's `:ast` command. */
object Dump:
  def render(n: Node): String =
    val sb = new StringBuilder
    go(n, sb, 0)
    sb.toString.stripLineEnd

  private def pad(sb: StringBuilder, d: Int): Unit =
    var i = 0
    while i < d do { sb.append("  "); i += 1 }

  private def line(sb: StringBuilder, d: Int, text: String): Unit =
    pad(sb, d)
    sb.append(text)
    sb.append('\n')

  private def go(n: Node, sb: StringBuilder, d: Int): Unit = n match
    case x: Lit       => line(sb, d, s"Lit ${x.v.repr}")
    case x: LocalGet  => line(sb, d, s"LocalGet ${x.name}@slot${x.slot}")
    case x: OuterGet  => line(sb, d, s"OuterGet ${x.name}@${x.depth}/${x.slot}")
    case x: GlobalGet => line(sb, d, s"GlobalGet ${x.name}#${x.id}")
    case x: LocalSet  => line(sb, d, s"LocalSet slot${x.slot}"); go(x.value, sb, d + 1)
    case x: OuterSet  => line(sb, d, s"OuterSet ${x.depth}/${x.slot}"); go(x.value, sb, d + 1)
    case x: GlobalSet =>
      line(sb, d, s"GlobalSet ${Sfl.globals.nameOf(x.id)}#${x.id}")
      go(x.value, sb, d + 1)
    case x: Arith =>
      line(sb, d, s"Arith '${BinOp.symbol(x.op)}'")
      go(x.l, sb, d + 1); go(x.r, sb, d + 1)
    case x: Cmp =>
      line(sb, d, s"Cmp ${cmpName(x.op)}")
      go(x.l, sb, d + 1); go(x.r, sb, d + 1)
    case x: And => line(sb, d, "And"); go(x.l, sb, d + 1); go(x.r, sb, d + 1)
    case x: Or  => line(sb, d, "Or"); go(x.l, sb, d + 1); go(x.r, sb, d + 1)
    case x: Neg => line(sb, d, "Neg"); go(x.e, sb, d + 1)
    case x: Not => line(sb, d, "Not"); go(x.e, sb, d + 1)
    case x: TupleLit =>
      line(sb, d, s"TupleLit(${x.items.length})")
      x.items.foreach(go(_, sb, d + 1))
    case x: ArrLit =>
      line(sb, d, s"ArrLit[${x.items.length}]")
      x.items.foreach(go(_, sb, d + 1))
    case x: ObjLit =>
      line(sb, d, s"ObjLit{${x.keys.mkString(", ")}}")
      x.values.foreach(go(_, sb, d + 1))
    case x: Index => line(sb, d, "Index"); go(x.recv, sb, d + 1); go(x.key, sb, d + 1)
    case x: IndexSet =>
      line(sb, d, "IndexSet")
      go(x.recv, sb, d + 1); go(x.key, sb, d + 1); go(x.value, sb, d + 1)
    case x: IndexCompound =>
      line(sb, d, s"IndexCompound '${BinOp.symbol(x.op)}='")
      go(x.recv, sb, d + 1); go(x.key, sb, d + 1); go(x.value, sb, d + 1)
    case x: MakeFun =>
      line(sb, d, s"MakeFun ${x.proto.signature} slots=${x.proto.nSlots}")
      go(x.proto.body, sb, d + 1)
    case x: Call =>
      line(sb, d, s"Call/${x.args.length}")
      go(x.callee, sb, d + 1)
      x.args.foreach(go(_, sb, d + 1))
    case x: Block =>
      line(sb, d, s"Block[${x.stmts.length}]")
      x.stmts.foreach(go(_, sb, d + 1))
    case x: If =>
      line(sb, d, s"If (${x.conds.length} branch(es))")
      var i = 0
      while i < x.conds.length do
        line(sb, d + 1, "cond")
        go(x.conds(i), sb, d + 2)
        line(sb, d + 1, "then")
        go(x.bodies(i), sb, d + 2)
        i += 1
      if x.orElse != null then
        line(sb, d + 1, "else")
        go(x.orElse, sb, d + 2)
    case x: While => line(sb, d, "While"); go(x.cond, sb, d + 1); go(x.body, sb, d + 1)
    case x: ForIn =>
      line(sb, d, s"ForIn ${if x.globalId >= 0 then s"global#${x.globalId}" else s"slot${x.slot}"}")
      go(x.iterable, sb, d + 1); go(x.body, sb, d + 1)
    case x: Select =>
      line(sb, d, s"Select (${x.caseVals.length} case(s))")
      go(x.subject, sb, d + 1)
      var i = 0
      while i < x.caseVals.length do
        go(x.caseVals(i), sb, d + 2)
        go(x.caseBodies(i), sb, d + 2)
        i += 1
      if x.orElse != null then go(x.orElse, sb, d + 2)
    case x: Return   => line(sb, d, "Return"); if x.e != null then go(x.e, sb, d + 1)
    case _: Break    => line(sb, d, "Break")
    case _: Continue => line(sb, d, "Continue")
    case _: Nop      => line(sb, d, "Nop")

  private def cmpName(op: Int): String = op match
    case CmpOp.Eq => "=="
    case CmpOp.Ne => "!="
    case CmpOp.Lt => "<"
    case CmpOp.Le => "<="
    case CmpOp.Gt => ">"
    case _        => ">="
