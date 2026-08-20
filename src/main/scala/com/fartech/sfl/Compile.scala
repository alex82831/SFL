package com.fartech.sfl

import java.io.File
import scala.collection.mutable

/**
 * Types the compiler works in.
 *
 * SFL is dynamically typed, so the compiler infers a machine type where it can and
 * falls back to `Dyn` — a pointer to a garbage-collected runtime value — where it
 * cannot. Nothing is ever rejected for being too dynamic: the lattice's top is a
 * working representation, not an error. That is what lets a numeric loop compile to
 * bare i64 arithmetic while the closure three lines below it still compiles at all.
 */
enum Ty:
  case Unknown, Bool, I64, F64, Dyn

  def llvm: String = this match
    case I64     => "i64"
    case F64     => "double"
    case Bool    => "i1"
    case _       => "ptr"

  def show: String = this match
    case I64     => "int"
    case F64     => "float"
    case Bool    => "bool"
    case Dyn     => "value"
    case Unknown => "unknown"

object Ty:
  /** Widens two types to one that holds both. Anything irreconcilable becomes Dyn. */
  def join(a: Ty, b: Ty): Ty =
    if a == b then a
    else if a == Unknown then b
    else if b == Unknown then a
    else if (a == I64 && b == F64) || (a == F64 && b == I64) then F64
    else Dyn

  def numeric(t: Ty): Boolean = t == I64 || t == F64
  def unboxed(t: Ty): Boolean = t == I64 || t == F64 || t == Bool

/**
 * One compiled instance of a function.
 *
 * A `generic` instance has the runtime's calling convention — `(env, argc, argv)`
 * in and a value out — and is what closures, higher-order calls and tail calls go
 * through. A specialised instance takes unboxed arguments and exists only when
 * inference proved the types at every call site, which is where the speed comes
 * from.
 */
private final class Fn(
    val proto: FnProto,
    val argTys: Array[Ty],
    val index: Int,
    val generic: Boolean
):
  val nSlots: Int = if proto == null then 0 else proto.nSlots
  var ret: Ty = if generic then Ty.Dyn else Ty.Unknown
  val slots: Array[Ty] = Array.fill(nSlots)(if generic then Ty.Dyn else Ty.Unknown)
  var emitted = false

  val symbol: String =
    if proto == null then "sfl_main"
    else s"${if generic then "sfl_g" else "sfl_f"}_${Fn.sanitise(proto.name)}_$index"

  def key: String = if proto == null then "main" else Fn.keyOf(proto, argTys, generic)

private object Fn:
  private def sanitise(s: String): String =
    val out = s.filter(c => c.isLetterOrDigit || c == '_')
    if out.isEmpty then "fn" else out

  def keyOf(proto: FnProto, argTys: Array[Ty], generic: Boolean): String =
    if generic then s"g:${proto.uid}"
    else s"f:${proto.uid}:${argTys.mkString(",")}"

/**
 * Compiles an SFL program to LLVM IR.
 *
 * The compiler runs over the same resolved AST the interpreter runs, so the two can
 * never disagree about what a program means. Every construct of the language is
 * compiled; where a value's type is known the code is unboxed and calls nothing,
 * and where it is not the code calls the runtime, which implements exactly what the
 * interpreter's evaluator does.
 */
final class Compiler(val globals: Globals, val src: SourceRef):

  // Builtins are recognised by identity in the globals table, so a user who defines
  // their own `max` gets their own function rather than the primitive. A shared-source
  // stdlib function's slot holds the lazy sentinel rather than a VNative (see
  // StdlibModules), and is a builtin all the same.
  //
  // Every slot is scanned rather than every name, because one builtin occupies
  // three: the bare name and the two namespaces it is also reachable through, and
  // `std.map` has to compile to the same primitive call `map` does.
  private val builtinIds: Map[Int, String] =
    val out = mutable.HashMap.empty[Int, String]
    var id = 0
    val n = globals.size
    while id < n do
      globals.rawGet(id) match
        case b: VNative if Builtins.lookup(b.name).exists(_ eq b) => out(id) = b.name
        case s: VLazyStdlib                                       => out(id) = s.name
        case _                                                    => ()
      id += 1
    out.toMap

  private def fail(msg: String, n: Node, hint: String = ""): Nothing =
    val e = new CompileError(msg, Pos(n.line, n.col, -1), src)
    if hint.nonEmpty then e.note(hint)
    throw e

  // -------------------------------------------------------------------------
  // Pass 1: discover definitions
  // -------------------------------------------------------------------------

  /** Global id -> the function assigned there by a top-level `def`. */
  private val userFns = mutable.HashMap.empty[Int, FnProto]
  private val mainStmts = mutable.ArrayBuffer.empty[Node]

  /**
   * How many times the program assigns each global, anywhere. A builtin's name is an
   * ordinary global, so `var sum = 0` shadows `sum` exactly as it does under the
   * interpreter — and a name the program writes to is a variable of its own,
   * whatever the library happens to call the same thing.
   */
  private val globalWrites = mutable.HashMap.empty[Int, Int].withDefaultValue(0)

  private def collect(block: Block): Unit = collect(block, topLevel = true)

  /**
   * `topLevel` is true for the statements of the program or module being compiled,
   * and false inside a block the parser inlined for an import. The distinction only
   * matters when building a package archive: an inlined block there is a sibling
   * module, whose functions are provided by the sibling's own object and must not be
   * emitted again. In a program build both cases inline exactly as before — unless
   * the import is a package with a valid prebuilt archive, which is linked instead.
   */
  private def collect(block: Block, topLevel: Boolean): Unit =
    for st <- block.stmts do
      st match
        case mb: ModuleBlock if isProgram && tryLinkPackage(mb) =>
          // Linked from an archive: keep the marker so its inits run at this point.
          mainStmts += mb
        case gs: GlobalSet =>
          gs.value match
            case mk: MakeFun =>
              if topLevel || isProgram then
                userFns(gs.id) = mk.proto
                mainStmts += st
              else
                // A sibling module's function in a package archive build.
                externalSibling(mk.proto) match
                  case Some((pkg, mod)) =>
                    if isPublicGlobalName(gs.id) then externalFn(gs.id) = (pkg, mod, mk.proto.name)
                  case None =>
                    userFns(gs.id) = mk.proto
                    mainStmts += st
            case _ =>
              // A non-function top-level global. It runs where it is defined: in the
              // program, in this module's init, or — for a sibling in an archive
              // build — in the sibling's own object, so it is dropped here.
              if topLevel || isProgram then mainStmts += st
        case b: Block => collect(b, topLevel = false) // an import, inlined by the parser
        case _: Nop   => ()
        case other    => if topLevel || isProgram then mainStmts += other

  /** In a package archive build, the (package, module) a def belongs to when it is a
    * sibling of the module being compiled rather than that module itself. */
  private def externalSibling(proto: FnProto): Option[(String, String)] = pkgBuild match
    case Some((pkg, _, versionDir, moduleSource)) =>
      val p = proto.src.name
      if p != moduleSource && p.startsWith(versionDir) then Some((pkg, moduleRelOf(p, versionDir)))
      else None
    case None => None

  /**
   * A program importing a package with a matching prebuilt archive links it rather
   * than compiling its source: the module inits are called at the import site, the
   * public functions become external symbols, and the archive is handed to the
   * linker. Returns false — so the caller inlines the source exactly as before —
   * when there is no usable archive, the toolchain or sources have moved on, or the
   * package uses a shape this build does not link (a public value, or a dependency
   * on another package).
   */
  private final case class LinkPlan(
      versionDir: String,
      archive: File,
      abi: PkgTool.Binary.Abi,
      reached: List[LinkedModule],
      externals: List[(Int, String, String)]
  )

  private def tryLinkPackage(mb: ModuleBlock): Boolean =
    analyzeLink(mb) match
      case None => false
      case Some(plan) =>
        for (id, mod, name) <- plan.externals do externalFn(id) = (mb.pkg, mod, name)
        for m <- plan.reached do linkedModules.getOrElseUpdate(m.sourcePath, m)
        linkedArchives.getOrElseUpdate(plan.versionDir, plan.archive)
        linkedPackageNames += mb.pkg
        linkedInitsAt(mb) = plan.reached.map(m => PkgTool.Binary.initSymbol(mb.pkg, m.moduleRel))
        // Pull in the primitives and library functions the archive calls, so the
        // program's native table has their code pointers and the linked stdlib inits run.
        for m <- plan.reached; ma <- plan.abi.module(m.moduleRel) do
          for p <- ma.prims; pp <- Prims.byName.get(p) do usedPrims += pp
          for s <- ma.stdlib; sf <- Stdlib.byName.get(s) do usedStdlib += sf
        true

  /**
   * Whether this import can be linked, and everything the commit needs, without
   * touching any state — so a package that turns out to be unlinkable leaves the
   * build exactly as source compilation found it.
   */
  private def analyzeLink(mb: ModuleBlock): Option[LinkPlan] =
    if !binaryLinkingEnabled then None
    else
      val versionDir = mb.versionDir
      val vd = new File(if versionDir.endsWith(File.separator) then versionDir.dropRight(1) else versionDir)
      val target = PkgTool.Binary.targetTriple
      val archive = PkgTool.Binary.archiveFile(vd, target)
      val abiOpt = PkgTool.Binary.readAbi(PkgTool.Binary.abiFile(vd, target))
        .filter(PkgTool.Binary.toolchainMatches)
      if !archive.isFile || abiOpt.isEmpty then None
      else
        val abi = abiOpt.get
        // Walk the inlined module tree: modules reached in dependency order, their
        // public functions, and a check that nothing is a shape we cannot link.
        val reached = mutable.LinkedHashMap.empty[String, LinkedModule]
        val exts = mutable.ArrayBuffer.empty[(Int, String, String)]
        var ok = true
        def scan(b: Block): Unit =
          for st <- b.stmts do
            if ok then st match
              case sub: ModuleBlock =>
                if sub.versionDir != versionDir then ok = false // a dependency on another package
                else scan(sub)
              case gs: GlobalSet =>
                gs.value match
                  case mk: MakeFun =>
                    val src = mk.proto.src.name
                    if !src.startsWith(versionDir) then ok = false
                    else
                      val rel = moduleRelOf(src, versionDir)
                      reached.getOrElseUpdate(src, LinkedModule(mb.pkg, rel, src, mk.proto.src.text))
                      if isPublicGlobalName(gs.id) then exts += ((gs.id, rel, mk.proto.name))
                  case _ =>
                    if isPublicGlobalName(gs.id) then ok = false // a public non-function export
              case b2: Block => scan(b2)
              case _         => ()
        scan(mb)
        // Every reached module must be in the archive at the exact source it was
        // built from, or the objects and the source have drifted apart.
        def digestsAgree = reached.forall { (_, m) =>
          abi.module(m.moduleRel).exists(_.digest == PkgTool.Binary.sourceDigest(m.sourceText))
        }
        if !ok || reached.isEmpty || !digestsAgree then None
        else Some(LinkPlan(versionDir, archive, abi, reached.values.toList, exts.toList))

  private var binaryLinkingEnabled: Boolean = true
  def disableBinaryLinking(): Unit = binaryLinkingEnabled = false

  private def countWrites(n: Node): Unit =
    n match
      case gs: GlobalSet => globalWrites(gs.id) = globalWrites(gs.id) + 1
      case f: ForIn if f.globalId >= 0 =>
        globalWrites(f.globalId) = globalWrites(f.globalId) + 1
      case _ => ()
    children(n).foreach(countWrites)

  private def children(n: Node): Seq[Node] = n match
    case b: Block => b.stmts.toSeq
    case i: If    => i.conds.toSeq ++ i.bodies.toSeq ++ Option(i.orElse)
    case w: While => Seq(w.cond, w.body)
    case f: ForIn => Seq(f.iterable, f.body)
    case s: Select =>
      (s.subject +: s.caseVals.toSeq) ++ s.caseBodies.toSeq ++ Option(s.orElse)
    case c: Call  => c.callee +: c.args.toSeq
    case a: Arith => Seq(a.l, a.r)
    case c: Cmp   => Seq(c.l, c.r)
    case a: And   => Seq(a.l, a.r)
    case o: Or    => Seq(o.l, o.r)
    case x: Not   => Seq(x.e)
    case x: Neg   => Seq(x.e)
    case s: LocalSet  => Seq(s.value)
    case s: GlobalSet => Seq(s.value)
    case s: OuterSet  => Seq(s.value)
    case a: ArrLit    => a.items.toSeq
    case t: TupleLit  => t.items.toSeq
    case o: ObjLit    => o.values.toSeq
    case i: Index     => Seq(i.recv, i.key)
    case i: IndexSet  => Seq(i.recv, i.key, i.value)
    case i: IndexCompound => Seq(i.recv, i.key, i.value)
    case m: MakeFun   => Seq(m.proto.body)
    case r: Return    => Option(r.e).toSeq
    case _            => Nil

  /** True when the program itself owns this global rather than the library. */
  private def isUserGlobal(id: Int): Boolean =
    globalWrites(id) > (if userFns.contains(id) then 1 else 0)

  private def builtinOf(n: Node): String = n match
    case g: GlobalGet if !userFns.contains(g.id) && !isUserGlobal(g.id) =>
      builtinIds.getOrElse(g.id, "")
    case _ => ""

  private def userFnOf(n: Node): FnProto = n match
    case g: GlobalGet if !isUserGlobal(g.id) => userFns.getOrElse(g.id, null)
    case _                                   => null

  /**
   * Whether a function's activation record has to live on the heap: it does as soon
   * as anything inside it builds a closure, because that closure outlives the call
   * and reads these very slots. Functions with no lambdas anywhere inside keep their
   * locals in registers, which is what keeps ordinary code fast.
   */
  private val heapFrameCache = mutable.HashMap.empty[FnProto, Boolean]

  private def heapFrame(proto: FnProto): Boolean =
    heapFrameCache.getOrElseUpdate(proto, containsMakeFun(proto.body))

  private def containsMakeFun(n: Node): Boolean = n match
    case _: MakeFun => true
    case b: Block   => b.stmts.exists(containsMakeFun)
    case i: If      => i.conds.exists(containsMakeFun) || i.bodies.exists(containsMakeFun) ||
                       (i.orElse != null && containsMakeFun(i.orElse))
    case w: While   => containsMakeFun(w.cond) || containsMakeFun(w.body)
    case f: ForIn   => containsMakeFun(f.iterable) || containsMakeFun(f.body)
    case s: Select  => containsMakeFun(s.subject) || s.caseVals.exists(containsMakeFun) ||
                       s.caseBodies.exists(containsMakeFun) ||
                       (s.orElse != null && containsMakeFun(s.orElse))
    case c: Call    => containsMakeFun(c.callee) || c.args.exists(containsMakeFun)
    case a: Arith   => containsMakeFun(a.l) || containsMakeFun(a.r)
    case c: Cmp     => containsMakeFun(c.l) || containsMakeFun(c.r)
    case a: And     => containsMakeFun(a.l) || containsMakeFun(a.r)
    case o: Or      => containsMakeFun(o.l) || containsMakeFun(o.r)
    case x: Not     => containsMakeFun(x.e)
    case x: Neg     => containsMakeFun(x.e)
    case s: LocalSet  => containsMakeFun(s.value)
    case s: GlobalSet => containsMakeFun(s.value)
    case s: OuterSet  => containsMakeFun(s.value)
    case a: ArrLit    => a.items.exists(containsMakeFun)
    case t: TupleLit  => t.items.exists(containsMakeFun)
    case o: ObjLit    => o.values.exists(containsMakeFun)
    case i: Index     => containsMakeFun(i.recv) || containsMakeFun(i.key)
    case i: IndexSet  => containsMakeFun(i.recv) || containsMakeFun(i.key) || containsMakeFun(i.value)
    case i: IndexCompound =>
      containsMakeFun(i.recv) || containsMakeFun(i.key) || containsMakeFun(i.value)
    case r: Return  => r.e != null && containsMakeFun(r.e)
    case _          => false

  // -------------------------------------------------------------------------
  // Storage: runtime-managed slots
  // -------------------------------------------------------------------------

  /**
   * One array holds every value the collector must see from outside the stack:
   * dynamic globals, string literals, the function and builtin values a program
   * refers to. Registering it as a root once, at startup, is all the cooperation
   * the collector needs from generated code.
   */
  private val slotNames = mutable.ArrayBuffer.empty[String]
  private def newSlot(what: String): Int = { slotNames += what; slotNames.size - 1 }

  private val dynGlobalSlot = mutable.HashMap.empty[Int, Int]
  private val strSlot = mutable.LinkedHashMap.empty[String, Int]
  private val natSlot = mutable.LinkedHashMap.empty[String, Int]
  private val funSlot = mutable.LinkedHashMap.empty[FnProto, Int]

  private def slotOfGlobal(id: Int): Int =
    dynGlobalSlot.getOrElseUpdate(id, newSlot(s"global ${globals.nameOf(id)}"))
  private def slotOfString(s: String): Int =
    strSlot.getOrElseUpdate(s, newSlot("string literal"))
  private def slotOfNative(name: String): Int =
    natSlot.getOrElseUpdate(name, newSlot(s"builtin $name"))
  private def slotOfFun(p: FnProto): Int =
    funSlot.getOrElseUpdate(p, newSlot(s"function ${p.name}"))

  private var slotCount = 0

  // Protos that need a static description emitted, for arity errors and rendering.
  private val protoConst = mutable.LinkedHashMap.empty[FnProto, String]
  private def protoRef(p: FnProto): String =
    protoConst.getOrElseUpdate(p, {
      fileIndex(p.src)
      // A library's exported functions export their descriptions too, so a program
      // that uses one as a value can render and check it like any other function.
      // Private ones must not: two modules may share the name, and a shared symbol
      // would give one module's function the other's arity and position.
      pkgBuild match
        case Some((pkg, _, versionDir, moduleSource)) =>
          if !p.name.startsWith("_") && p.name != "lambda" && p.src.name == moduleSource &&
             userFns.valuesIterator.exists(_ eq p) then
            s"@${PkgTool.Binary.protoSymbol(pkg, moduleRelOf(moduleSource, versionDir), p.name)}"
          else s"@proto${protoConst.size}"
        case None =>
          if libraryModule.nonEmpty && !p.name.startsWith("_") && p.name != "lambda" &&
             userFns.valuesIterator.exists(_ eq p) then
            s"@sflstd_proto_${p.name}"
          else s"@proto${protoConst.size}"
    })

  /** Library functions the program refers to as values rather than calling. */
  private val stdFunSlot = mutable.LinkedHashMap.empty[StdFn, Int]
  private def slotOfStdFun(f: StdFn): Int =
    stdFunSlot.getOrElseUpdate(f, newSlot(s"library function ${f.name}"))

  /* Name -> (index, text). The text is linked in so errors can quote their line. */
  private val sourceFiles = mutable.LinkedHashMap.empty[String, (Int, String)]
  private def fileIndex(ref: SourceRef): Int =
    sourceFiles.getOrElseUpdate(ref.name, (sourceFiles.size, ref.text))._1

  // -------------------------------------------------------------------------
  // Pass 2: inference over the specialised instances
  // -------------------------------------------------------------------------

  private val fns = mutable.LinkedHashMap.empty[String, Fn]
  private val globalTys = mutable.HashMap.empty[Int, Ty]
  private var changed = false
  /* Set once inference has settled: the later passes read types, never widen them. */
  private var frozen = false

  private def request(proto: FnProto, argTys: Array[Ty], generic: Boolean): Fn =
    val key = Fn.keyOf(proto, argTys, generic)
    fns.getOrElseUpdate(key, {
      changed = true
      new Fn(proto, argTys, fns.size, generic)
    })

  /** The generic instance of a proto, which every dynamic use goes through. */
  private def genericOf(proto: FnProto): Fn =
    request(proto, Array.fill(proto.params.length)(Ty.Dyn), generic = true)

  private def setSlot(fn: Fn, slot: Int, t: Ty): Unit =
    if !frozen && slot < fn.slots.length then
      val joined = Ty.join(fn.slots(slot), t)
      if joined != fn.slots(slot) then
        fn.slots(slot) = joined
        changed = true

  private def setGlobal(id: Int, t: Ty): Unit =
    if frozen then return
    val prev = globalTys.getOrElse(id, Ty.Unknown)
    val joined = Ty.join(prev, t)
    if joined != prev then
      globalTys(id) = joined
      changed = true

  private def recordReturn(fn: Fn, t: Ty): Unit =
    if !frozen && t != Ty.Unknown then
      val joined = Ty.join(fn.ret, t)
      if joined != fn.ret then
        fn.ret = joined
        changed = true

  /** The type of an expression, recording what it implies about what it touches. */
  private def tyOf(n: Node, fn: Fn): Ty = n match
    case l: Lit =>
      l.v match
        case _: VInt   => Ty.I64
        case _: VFloat => Ty.F64
        case _: VBool  => Ty.Bool
        case _         => Ty.Dyn

    case g: LocalGet  => if g.slot < fn.slots.length then fn.slots(g.slot) else Ty.Dyn
    case _: OuterGet  => Ty.Dyn
    case g: GlobalGet =>
      if externalFn.contains(g.id) then Ty.Dyn
      else if (userFns.contains(g.id) || builtinIds.contains(g.id)) && !isUserGlobal(g.id) then Ty.Dyn
      else globalTys.getOrElse(g.id, Ty.Unknown)

    case s: LocalSet =>
      val t = tyOf(s.value, fn)
      if t != Ty.Unknown then setSlot(fn, s.slot, t)
      t
    case s: GlobalSet =>
      val t = tyOf(s.value, fn)
      if t != Ty.Unknown then setGlobal(s.id, t)
      t
    case s: OuterSet => tyOf(s.value, fn); Ty.Dyn

    case a: Arith =>
      val lt = tyOf(a.l, fn)
      val rt = tyOf(a.r, fn)
      if lt == Ty.Unknown || rt == Ty.Unknown then Ty.Unknown
      else if Ty.numeric(lt) && Ty.numeric(rt) then Ty.join(lt, rt)
      else Ty.Dyn

    case c: Cmp =>
      val lt = tyOf(c.l, fn)
      val rt = tyOf(c.r, fn)
      if lt == Ty.Unknown || rt == Ty.Unknown then Ty.Unknown else Ty.Bool

    case a: And => tyOf(a.l, fn); tyOf(a.r, fn); Ty.Bool
    case o: Or  => tyOf(o.l, fn); tyOf(o.r, fn); Ty.Bool
    case x: Not => tyOf(x.e, fn); Ty.Bool
    case x: Neg =>
      val t = tyOf(x.e, fn)
      if Ty.numeric(t) then t else if t == Ty.Unknown then Ty.Unknown else Ty.Dyn

    case c: Call => tyOfCall(c, fn)

    case b: Block =>
      var t: Ty = Ty.Dyn
      for st <- b.stmts do t = tyOf(st, fn)
      t

    case i: If =>
      var t: Ty = Ty.Unknown
      var k = 0
      while k < i.conds.length do
        tyOf(i.conds(k), fn)
        t = Ty.join(t, tyOf(i.bodies(k), fn))
        k += 1
      if i.orElse != null then Ty.join(t, tyOf(i.orElse, fn)) else Ty.Dyn

    case w: While => tyOf(w.cond, fn); tyOf(w.body, fn); Ty.Dyn
    case f: ForIn => tyOfForIn(f, fn)

    case r: Return =>
      val t = if r.e == null then Ty.Dyn else tyOf(r.e, fn)
      recordReturn(fn, t)
      // Control leaves here, so this expression contributes no type of its own.
      Ty.Unknown

    case _: Break | _: Continue | _: Nop => Ty.Dyn

    case s: Select =>
      tyOf(s.subject, fn)
      var t: Ty = Ty.Unknown
      var k = 0
      while k < s.caseVals.length do
        tyOf(s.caseVals(k), fn)
        t = Ty.join(t, tyOf(s.caseBodies(k), fn))
        k += 1
      if s.orElse != null then Ty.join(t, tyOf(s.orElse, fn)) else Ty.Dyn

    case m: MakeFun =>
      genericOf(m.proto)
      Ty.Dyn
    case a: ArrLit => a.items.foreach(tyOf(_, fn)); Ty.Dyn
    case t: TupleLit => t.items.foreach(tyOf(_, fn)); Ty.Dyn
    case o: ObjLit => o.values.foreach(tyOf(_, fn)); Ty.Dyn
    case i: Index  => tyOf(i.recv, fn); tyOf(i.key, fn); Ty.Dyn
    case i: IndexSet =>
      tyOf(i.recv, fn); tyOf(i.key, fn); tyOf(i.value, fn); Ty.Dyn
    case i: IndexCompound =>
      tyOf(i.recv, fn); tyOf(i.key, fn); tyOf(i.value, fn); Ty.Dyn

  private def tyOfForIn(f: ForIn, fn: Fn): Ty =
    val args = rangeArgs(f.iterable)
    if args != null && f.frameSlots < 0 then
      var allInt = true
      for a <- args do
        val t = tyOf(a, fn)
        if t != Ty.I64 && t != Ty.Unknown then allInt = false
      if allInt then
        if f.globalId >= 0 then setGlobal(f.globalId, Ty.I64) else setSlot(fn, f.slot, Ty.I64)
      else if f.globalId >= 0 then setGlobal(f.globalId, Ty.Dyn) else setSlot(fn, f.slot, Ty.Dyn)
    else
      tyOf(f.iterable, fn)
      if f.frameSlots < 0 then
        if f.globalId >= 0 then setGlobal(f.globalId, Ty.Dyn) else setSlot(fn, f.slot, Ty.Dyn)
    tyOf(f.body, fn)
    Ty.Dyn

  /** The argument list when `n` is a call to the builtin `range`, else null. */
  private def rangeArgs(n: Node): Array[Node] = n match
    case c: Call if builtinOf(c.callee) == "range" => c.args
    case _                                         => null

  private def tyOfCall(c: Call, fn: Fn): Ty =
    val builtin = builtinOf(c.callee)
    val argTys = c.args.map(a => tyOf(a, fn))
    if builtin.nonEmpty then
      Intrinsics.staticRet(builtin, argTys).getOrElse(Ty.Dyn)
    else
      val proto = userFnOf(c.callee)
      if proto == null then
        tyOf(c.callee, fn)
        Ty.Dyn
      else if proto.hasRest || proto.required != proto.params.length ||
              c.args.length != proto.params.length || heapFrame(proto) ||
              argTys.exists(t => !Ty.unboxed(t)) then
        // Anything the specialiser cannot promise a shape for goes through the
        // generic entry, which handles defaults, rest parameters and boxing.
        genericOf(proto)
        Ty.Dyn
      else
        val callee = request(proto, argTys, generic = false)
        callee.ret

  /** Runs inference until nothing changes. */
  private def infer(top: Block): Unit =
    collect(top)
    mainStmts.foreach(countWrites)
    for p <- userFns.values do countWrites(p.body)
    val main = new Fn(null, Array.empty, 0, generic = false)
    fns("main") = main
    var rounds = 0
    changed = true
    while changed && rounds < 128 do
      changed = false
      rounds += 1
      for st <- mainStmts do tyOf(st, main)
      // A snapshot: inferring one body can request further instances.
      for f <- fns.values.toList do
        if f.proto != null then
          var i = 0
          while i < f.argTys.length && i < f.slots.length do
            if !f.generic then f.slots(i) = f.argTys(i)
            i += 1
          val tail = tyOf(f.proto.body, f)
          if !f.generic then recordReturn(f, tail)
    if rounds >= 128 then
      throw new CompileError("type inference did not settle", Pos.none, src)
    for f <- fns.values do
      if f.ret == Ty.Unknown then f.ret = Ty.Dyn
      var i = 0
      while i < f.slots.length do
        if f.slots(i) == Ty.Unknown then f.slots(i) = Ty.Dyn
        i += 1
    for (id, t) <- globalTys.toList do
      if t == Ty.Unknown then globalTys(id) = Ty.Dyn
    frozen = true

  /**
   * Whether a call to this instance can produce a runtime error. Instances that
   * cannot need neither a position update nor a traceback frame, which is what
   * keeps pure arithmetic exactly as fast as it was before the runtime existed.
   */
  private val raises = mutable.HashMap.empty[String, Boolean]

  private def computeRaises(): Unit =
    for f <- fns.values do raises(f.key) = f.generic
    var again = true
    var rounds = 0
    while again && rounds < 64 do
      again = false
      rounds += 1
      for f <- fns.values do
        if !raises(f.key) then
          val body = if f.proto == null then new Block(mainStmts.toArray, 0) else f.proto.body
          if nodeRaises(body, f) then
            raises(f.key) = true
            again = true

  private def nodeRaises(n: Node, fn: Fn): Boolean = n match
    case a: Arith =>
      val lt = tyOf(a.l, fn)
      val rt = tyOf(a.r, fn)
      !(Ty.numeric(lt) && Ty.numeric(rt)) ||
        ((a.op == BinOp.Div || a.op == BinOp.Mod) && Ty.join(lt, rt) == Ty.I64) ||
        nodeRaises(a.l, fn) || nodeRaises(a.r, fn)
    case c: Cmp =>
      val lt = tyOf(c.l, fn)
      val rt = tyOf(c.r, fn)
      !(Ty.numeric(lt) && Ty.numeric(rt) || (lt == Ty.Bool && rt == Ty.Bool)) ||
        nodeRaises(c.l, fn) || nodeRaises(c.r, fn)
    case c: Call =>
      if builtinOf(c.callee).nonEmpty then true
      else
        val proto = userFnOf(c.callee)
        if proto == null then true
        else
          val argTys = c.args.map(a => tyOf(a, fn))
          val key = Fn.keyOf(proto, argTys, generic = false)
          !fns.contains(key) || raises.getOrElse(key, true) || c.args.exists(nodeRaises(_, fn))
    case b: Block => b.stmts.exists(nodeRaises(_, fn))
    case i: If =>
      i.conds.exists(nodeRaises(_, fn)) || i.bodies.exists(nodeRaises(_, fn)) ||
        (i.orElse != null && nodeRaises(i.orElse, fn))
    case w: While  => nodeRaises(w.cond, fn) || nodeRaises(w.body, fn)
    case f: ForIn  =>
      val args = rangeArgs(f.iterable)
      args == null || f.frameSlots >= 0 || args.exists(nodeRaises(_, fn)) ||
        nodeRaises(f.body, fn)
    case s: Select =>
      nodeRaises(s.subject, fn) || s.caseVals.exists(nodeRaises(_, fn)) ||
        s.caseBodies.exists(nodeRaises(_, fn)) || (s.orElse != null && nodeRaises(s.orElse, fn))
    case s: LocalSet  => nodeRaises(s.value, fn)
    case s: GlobalSet => nodeRaises(s.value, fn)
    case g: GlobalGet => globalTys.getOrElse(g.id, Ty.Dyn) == Ty.Dyn
    case a: And => nodeRaises(a.l, fn) || nodeRaises(a.r, fn)
    case o: Or  => nodeRaises(o.l, fn) || nodeRaises(o.r, fn)
    case x: Not => nodeRaises(x.e, fn)
    case x: Neg => !Ty.numeric(tyOf(x.e, fn)) || nodeRaises(x.e, fn)
    case r: Return => r.e != null && nodeRaises(r.e, fn)
    case _: Lit | _: LocalGet | _: Break | _: Continue | _: Nop => false
    case _ => true

  // -------------------------------------------------------------------------
  // Pass 3: emit LLVM IR
  // -------------------------------------------------------------------------

  private val entryBuf = new StringBuilder
  private val out = new StringBuilder
  private var tmpCount = 0
  private var labelCount = 0

  private def tmp(): String = { tmpCount += 1; s"%t$tmpCount" }
  private def label(base: String): String = { labelCount += 1; s"$base$labelCount" }
  private def emit(line: String): Unit = out.append("  ").append(line).append('\n')
  private def emitLabel(l: String): Unit = out.append(l).append(":\n")

  /**
   * Allocas belong in the entry block. One inside a loop would grow the stack on
   * every iteration, because nothing reclaims it until the function returns.
   */
  private def entryAlloca(ty: String): String =
    val name = tmp()
    entryBuf.append("  ").append(name).append(" = alloca ").append(ty).append('\n')
    name

  private var cur: Fn = null
  private var curRaises = false
  private var slotPtr: Array[String] = Array.empty // static instances: one alloca per slot
  private var slotsBase = ""                       // generic instances: the slot array
  private var frameVal = ""                        // the heap frame, when there is one
  private var envVal = "null"
  private var inDefaults = false                   // Local nodes resolve against %env
  private var envSlotsReg = ""                     // lazy sfl_frame_slots(%env), in entry
  private val loopStack = mutable.Stack.empty[(String, String)]

  /**
   * Pins a frame on the stack for as long as the function runs.
   *
   * Generated code reads locals through the frame's slot array, so after the array
   * pointer is in hand nothing refers to the frame object itself — and the collector,
   * which finds roots by scanning the stack, would see no reason to keep it. The
   * store is volatile so no optimisation can decide the same thing.
   */
  private def emitStackCheck(): Unit =
    val fp = tmp(); emit(s"$fp = call ptr @llvm.frameaddress.p0(i32 0)")
    val lim = tmp(); emit(s"$lim = load ptr, ptr @sfl_stack_limit")
    val low = tmp(); emit(s"$low = icmp ult ptr $fp, $lim")
    val bad = label("stack_low")
    val ok = label("stack_ok")
    emit(s"br i1 $low, label %$bad, label %$ok")
    emitLabel(bad)
    emit("call void @sfl_stack_overflow()")
    emit("unreachable")
    emitLabel(ok)

  private def callsAnything(n: Node): Boolean = n match
    case _: Call  => true
    case b: Block => b.stmts.exists(callsAnything)
    case i: If    => i.conds.exists(callsAnything) || i.bodies.exists(callsAnything) ||
                     (i.orElse != null && callsAnything(i.orElse))
    case w: While => callsAnything(w.cond) || callsAnything(w.body)
    case f: ForIn => callsAnything(f.iterable) || callsAnything(f.body)
    case s: Select => callsAnything(s.subject) || s.caseVals.exists(callsAnything) ||
                      s.caseBodies.exists(callsAnything) ||
                      (s.orElse != null && callsAnything(s.orElse))
    case a: Arith => callsAnything(a.l) || callsAnything(a.r)
    case c: Cmp   => callsAnything(c.l) || callsAnything(c.r)
    case a: And   => callsAnything(a.l) || callsAnything(a.r)
    case o: Or    => callsAnything(o.l) || callsAnything(o.r)
    case x: Not   => callsAnything(x.e)
    case x: Neg   => callsAnything(x.e)
    case s: LocalSet  => callsAnything(s.value)
    case s: GlobalSet => callsAnything(s.value)
    // Literals and indexing carry calls too: `def f(n) = [f(n - 1)]` recurses
    // through an array literal, and missing it here would skip the stack check.
    case a: ArrLit    => a.items.exists(callsAnything)
    case t: TupleLit  => t.items.exists(callsAnything)
    case o: ObjLit    => o.values.exists(callsAnything)
    case i: Index     => callsAnything(i.recv) || callsAnything(i.key)
    case i: IndexSet  => callsAnything(i.recv) || callsAnything(i.key) || callsAnything(i.value)
    case i: IndexCompound =>
      callsAnything(i.recv) || callsAnything(i.key) || callsAnything(i.value)
    case r: Return    => r.e != null && callsAnything(r.e)
    case _        => false

  private def pinFrame(frame: String): Unit =
    val keep = entryAlloca("ptr")
    emit(s"store volatile ptr $frame, ptr $keep")

  private def slotsGlobal: String = pkgBuild match
    case Some((pkg, mod, _, _)) => s"@sfl_slots_${PkgTool.Binary.modPrefix(pkg, mod)}"
    case None => if libraryModule.isEmpty then "@sfl_slots" else s"@sfl_slots_$libraryModule"

  private def gslot(i: Int): String = s"getelementptr inbounds (ptr, ptr $slotsGlobal, i64 $i)"

  private def slotAddr(i: Int): String =
    if slotsBase.isEmpty && !inDefaults then slotPtr(i)
    else
      val base = if slotsBase.nonEmpty then slotsBase else envSlots()
      val r = tmp()
      emit(s"$r = getelementptr inbounds ptr, ptr $base, i64 $i")
      r

  /**
   * The defining frame's slot array, for Local nodes inside default expressions.
   *
   * Materialised in the entry block so one register serves every switch arm, and
   * only on first use: a name in a default resolves to a frame slot only when the
   * function was defined under a frame, so `%env` is non-null exactly when this
   * load is emitted — a function whose defaults touch no enclosing locals may
   * carry a null env and must not dereference it.
   */
  private def envSlots(): String =
    if envSlotsReg.isEmpty then
      envSlotsReg = tmp()
      entryBuf.append(s"  $envSlotsReg = call ptr @sfl_frame_slots(ptr $envVal)\n")
    envSlotsReg

  /*
   * Slots of a per-iteration loop frame live past the end of the function's own
   * slot table, and they are always boxed, so anything out of range is Dyn.
   */
  private def slotTy(i: Int): Ty =
    if i >= cur.slots.length || cur.slots(i) == Ty.Unknown then Ty.Dyn else cur.slots(i)
  private def globalTy(id: Int): Ty = globalTys.getOrElse(id, Ty.Dyn)

  // ---- conversions --------------------------------------------------------

  private def box(v: String, t: Ty): String = t match
    case Ty.Dyn => v
    case Ty.I64 => val r = tmp(); emit(s"$r = call ptr @sfl_int(i64 $v)"); r
    case Ty.F64 => val r = tmp(); emit(s"$r = call ptr @sfl_float(double $v)"); r
    case Ty.Bool =>
      val z = tmp(); emit(s"$z = zext i1 $v to i32")
      val r = tmp(); emit(s"$r = call ptr @sfl_bool(i32 $z)")
      r
    case Ty.Unknown => "@sfl_null_obj"

  private def coerce(v: String, from: Ty, to: Ty): String =
    if from == to then v
    else if to == Ty.Dyn then box(v, from)
    else if from == Ty.Dyn && to == Ty.I64 then
      val r = tmp(); emit(s"$r = call i64 @sfl_unbox_int(ptr $v)"); r
    else if from == Ty.Dyn && to == Ty.F64 then
      val r = tmp(); emit(s"$r = call double @sfl_unbox_float(ptr $v)"); r
    else if from == Ty.I64 && to == Ty.F64 then
      val r = tmp(); emit(s"$r = sitofp i64 $v to double"); r
    else if from == Ty.Bool && to == Ty.I64 then
      val r = tmp(); emit(s"$r = zext i1 $v to i64"); r
    else if from == Ty.Dyn && to == Ty.Bool then truthy(v, Ty.Dyn)
    else if from == Ty.Unknown then zeroOf(to)
    else v

  private def truthy(v: String, t: Ty): String = t match
    case Ty.Bool => v
    case Ty.I64  => val r = tmp(); emit(s"$r = icmp ne i64 $v, 0"); r
    case Ty.F64  => val r = tmp(); emit(s"$r = fcmp one double $v, 0.0"); r
    case Ty.Dyn =>
      val c = tmp(); emit(s"$c = call i32 @sfl_truthy(ptr $v)")
      val r = tmp(); emit(s"$r = icmp ne i32 $c, 0")
      r
    case Ty.Unknown => "false"

  private def zeroOf(t: Ty): String = t match
    case Ty.F64  => "0.0"
    case Ty.Bool => "false"
    case Ty.I64  => "0"
    case _       => "@sfl_null_obj"

  // ---- position tracking --------------------------------------------------

  private var curFile = 0

  /**
   * Where the interpreter would say an error raised "here" happened. It pins an
   * unpositioned error to the innermost Call or Index node still being evaluated
   * (Node.locate), and only when none is on the stack does the current statement
   * (in main) or the caller's position (in a function body) show through. The
   * compiled program keeps `sfl_pos` equal to that answer at every point:
   * `callPosStack` mirrors the enclosing call nodes of the expression being
   * emitted, and `stmtPos` is the fallback — a packed constant in main, the
   * register the prologue saved in a function body.
   *
   * The standard library is the one exception: the interpreter implements those
   * functions natively, so nothing inside them has a position or a traceback
   * frame. A library build therefore emits no position stores and no frames at
   * all, which is what makes an error inside `map`'s callback point at the
   * user's `map(...)` call in both engines.
   */
  /**
   * A position `sfl_pos` can be set to. Two shapes so a package archive can carry
   * positions whose file index is not known until a program links it:
   *  - [[PConst]] is a ready i64 operand — a compile-time packed constant, or the
   *    register a prologue loaded (the caller's own position, the fallback in a
   *    function body). It is stored verbatim, and can be re-stored in any later
   *    block because a constant needs nothing and the prologue register dominates.
   *  - [[PLow]] is only the line/column half; the file index comes at run time from
   *    the module's `fbase` global, which the linking program sets. Materialised
   *    fresh at every store, so it too works across blocks.
   */
  private sealed trait AmbientPos
  private case class PConst(op: String) extends AmbientPos
  private case class PLow(low: Long) extends AmbientPos

  private var callPosStack: List[AmbientPos] = Nil
  private var stmtPos: AmbientPos = PConst("0")

  // Positions are emitted for the program and for a package archive (whose modules
  // are ordinary SFL and appear in tracebacks), but not for the standard library,
  // whose functions are native to the interpreter and carry no position of their own.
  private def emitPositions: Boolean = libraryModule.isEmpty

  private def posLow(n: Node, col: Int): Long = (n.line.toLong << 16) | col.toLong

  /** A position for this node, packed for the current build's file model. */
  private def posOf(n: Node, col: Int): AmbientPos = pkgBuild match
    case Some(_) => PLow(posLow(n, col))
    case None    => PConst(((curFile.toLong << 48) | posLow(n, col)).toString)

  /** What `sfl_pos` must return to once the work at hand completes. */
  private def ambientPos: AmbientPos = callPosStack.headOption.getOrElse(stmtPos)

  private def storePos(p: AmbientPos): Unit = p match
    case PConst(op) => emit(s"store i64 $op, ptr @sfl_pos")
    case PLow(low) =>
      val f = tmp(); emit(s"$f = load i64, ptr @${pkgFbaseSymbol}")
      val s = tmp(); emit(s"$s = shl i64 $f, 48")
      val v = tmp(); emit(s"$v = or i64 $s, $low")
      emit(s"store i64 $v, ptr @sfl_pos")

  /** Records where execution is, so an error can quote and underline the source. */
  private def setPos(n: Node, col: Int): Unit =
    if curRaises && emitPositions then storePos(posOf(n, col))

  /**
   * Statement-level position. Only main's statements decorate errors in the
   * interpreter — a statement inside a function body never shows through,
   * because the call that entered the function pins the error first. While a
   * call's arguments are being evaluated the call's own position wins, but the
   * statement still becomes the fallback, as it does in the interpreter.
   */
  private def setStmtPos(n: Node): Unit =
    if curRaises && emitPositions && inMain then
      stmtPos = posOf(n, n.col)
      if callPosStack.isEmpty then storePos(stmtPos)

  /**
   * Enters "evaluating call `c`": from here until the call completes, an error
   * anywhere beneath it — callee, arguments, body — reports the call itself,
   * exactly as Node.locate pins it. A call the parser gave no column to does
   * not pin in the interpreter, so it does not pin here either.
   */
  private def pushCallPos(c: Call): Boolean =
    if curRaises && emitPositions && c.col > 0 then
      val p = posOf(c, c.col)
      callPosStack = p :: callPosStack
      storePos(p)
      true
    else false

  private def popCallPos(pinned: Boolean): Unit =
    if pinned then
      callPosStack = callPosStack.tail
      storePos(ambientPos)

  /** Re-establishes the ambient position after an op that pinned its own. */
  private def restoreAmbient(): Unit =
    if curRaises && emitPositions then storePos(ambientPos)

  private def inMain: Boolean = cur != null && cur.proto == null

  /** True when the function being emitted pushed a traceback frame. */
  private var curFramePushed = false

  /**
   * A tail call compiled as a plain call still reuses its caller's traceback
   * frame, the way the interpreter's retarget does: the caller's frame comes
   * off before the call — the callee pushes its own in the same slot, and with
   * the ambient position restored first it records the original call site, not
   * the tail call's — and comes back once the callee returns, so the epilogue's
   * pop stays balanced. Only the trampoline-parked form gets this for free.
   */
  private def tailFramePop(c: Call): Boolean =
    val doIt = c.tail && curFramePushed && curRaises && emitPositions
    if doIt then emit("call void @sfl_frame_pop()")
    doIt

  private def tailFrameRepush(popped: Boolean): Unit =
    if popped then emit(s"call void @sfl_frame_push(ptr ${cstr(cur.proto.name)})")

  // ---- statements and expressions ------------------------------------------

  /** Emits `n` for its effect; the value is discarded, so loops need no result slot. */
  private def emitVoid(n: Node): Unit = n match
    case mb: ModuleBlock if linkedInitsAt.contains(mb) =>
      // A linked package: run its modules' initialisers here, in dependency order,
      // where the interpreter would run the imported code.
      for init <- linkedInitsAt(mb) do emit(s"call void @$init()")
    case b: Block => b.stmts.foreach(emitStmt)
    case w: While => emitWhile(w, wantValue = false)
    case f: ForIn => emitForIn(f, wantValue = false)
    case i: If    => emitIf(i, wantValue = false)
    case s: Select => emitSelect(s, wantValue = false)
    case other    => emitExpr(other)

  private def emitStmt(n: Node): Unit =
    setStmtPos(n)
    emitVoid(n)

  private def emitExpr(n: Node): (String, Ty) = n match
    case l: Lit =>
      l.v match
        case VInt(v)   => (v.toString, Ty.I64)
        case VFloat(v) => (irDouble(v), Ty.F64)
        case b: VBool  => (if b.b then "true" else "false", Ty.Bool)
        case VStr(s) =>
          val r = tmp(); emit(s"$r = load ptr, ptr ${gslot(slotOfString(s))}")
          (r, Ty.Dyn)
        case VNull => ("@sfl_null_obj", Ty.Dyn)
        case other => fail(s"cannot compile a literal of type ${other.typeName}", n)

    case g: LocalGet =>
      val t = slotTy(g.slot)
      val p = slotAddr(g.slot)
      val r = tmp()
      emit(s"$r = load ${t.llvm}, ptr $p")
      if t == Ty.Dyn then guardAssigned(r, g.name)
      (r, t)

    case g: OuterGet =>
      val base = outerFrame(g.depth)
      val slots = tmp(); emit(s"$slots = call ptr @sfl_frame_slots(ptr $base)")
      val p = tmp(); emit(s"$p = getelementptr inbounds ptr, ptr $slots, i64 ${g.slot}")
      val r = tmp(); emit(s"$r = load ptr, ptr $p")
      guardAssigned(r, g.name)
      (r, Ty.Dyn)

    case g: GlobalGet =>
      if externalFn.contains(g.id) then
        // A linked package function used as a value: a VFun over the archive's entry
        // and proto, built once and kept in a slot like any other function value.
        val r = tmp(); emit(s"$r = load ptr, ptr ${gslot(slotOfExtFun(g.id))}")
        (r, Ty.Dyn)
      else if userFns.contains(g.id) && !isUserGlobal(g.id) then
        val r = tmp(); emit(s"$r = load ptr, ptr ${gslot(slotOfFun(userFns(g.id)))}")
        (r, Ty.Dyn)
      else if builtinIds.contains(g.id) && !isUserGlobal(g.id) then
        val name = builtinIds(g.id)
        val slot =
          if Prims.byName.contains(name) then
            // Handing a builtin out as a value is a use like any other: without this
            // the table would carry its description but a null code pointer.
            usedPrims += Prims.byName(name)
            slotOfNative(name)
          else
            Stdlib.byName.get(name) match
              case Some(f) =>
                usedStdlib += f
                slotOfStdFun(f)
              case None =>
                fail(s"'$name' is not available in compiled programs", g,
                  "it needs the interpreter itself, which a compiled binary does not carry")
        val r = tmp(); emit(s"$r = load ptr, ptr ${gslot(slot)}")
        (r, Ty.Dyn)
      else
        val t = globalTy(g.id)
        val r = tmp()
        if t == Ty.Dyn then
          emit(s"$r = load ptr, ptr ${gslot(slotOfGlobal(g.id))}")
          guardDefined(r, globals.displayOf(g.id))
        else emit(s"$r = load ${t.llvm}, ptr @g_${g.id}")
        (r, t)

    case s: LocalSet =>
      val (v, vt) = emitExpr(s.value)
      val t = slotTy(s.slot)
      val c = coerce(v, vt, t)
      emit(s"store ${t.llvm} $c, ptr ${slotAddr(s.slot)}")
      (c, t)

    case s: OuterSet =>
      val (v, vt) = emitExpr(s.value)
      val c = box(v, vt)
      val base = outerFrame(s.depth)
      val slots = tmp(); emit(s"$slots = call ptr @sfl_frame_slots(ptr $base)")
      val p = tmp(); emit(s"$p = getelementptr inbounds ptr, ptr $slots, i64 ${s.slot}")
      emit(s"store ptr $c, ptr $p")
      (c, Ty.Dyn)

    case s: GlobalSet =>
      val (v, vt) = emitExpr(s.value)
      val t = globalTy(s.id)
      val c = coerce(v, vt, t)
      if t == Ty.Dyn then emit(s"store ptr $c, ptr ${gslot(slotOfGlobal(s.id))}")
      else emit(s"store ${t.llvm} $c, ptr @g_${s.id}")
      (c, t)

    case a: Arith  => emitArith(a)
    case c: Cmp    => emitCmp(c)
    case a: And    => emitShortCircuit(a.l, a.r, wantTrue = true)
    case o: Or     => emitShortCircuit(o.l, o.r, wantTrue = false)

    case x: Not =>
      val (v, t) = emitExpr(x.e)
      val b = truthy(v, t)
      val r = tmp(); emit(s"$r = xor i1 $b, true")
      (r, Ty.Bool)

    case x: Neg =>
      val (v, t) = emitExpr(x.e)
      if t == Ty.F64 then { val r = tmp(); emit(s"$r = fneg double $v"); (r, Ty.F64) }
      else if t == Ty.I64 then { val r = tmp(); emit(s"$r = sub i64 0, $v"); (r, Ty.I64) }
      else
        val r = tmp(); emit(s"$r = call ptr @sfl_neg(ptr ${box(v, t)})")
        (r, Ty.Dyn)

    case b: Block =>
      if b.stmts.isEmpty then ("@sfl_null_obj", Ty.Dyn)
      else
        var i = 0
        while i < b.stmts.length - 1 do { emitStmt(b.stmts(i)); i += 1 }
        val last = b.stmts(b.stmts.length - 1)
        setStmtPos(last)
        emitExpr(last)

    case i: If     => emitIf(i, wantValue = true)
    case w: While  => emitWhile(w, wantValue = true)
    case f: ForIn  => emitForIn(f, wantValue = true)
    case s: Select => emitSelect(s, wantValue = true)

    case r: Return =>
      if cur.proto == null then
        // At the top level `return` ends the program.
        if r.e != null then emitExpr(r.e)
        emit("ret void")
      else
        val retTy = if cur.generic then Ty.Dyn else cur.ret
        if r.e == null then emit(s"store ${retTy.llvm} ${zeroOf(retTy)}, ptr $retSlot")
        else
          val (v, t) = emitExpr(r.e)
          emit(s"store ${retTy.llvm} ${coerce(v, t, retTy)}, ptr $retSlot")
        emit(s"br label %$retLabel")
      emitLabel(label("after_ret"))
      ("", Ty.Unknown)

    case _: Break =>
      if loopStack.isEmpty then fail("'break' outside a loop", n)
      emit(s"br label %${loopStack.top._2}")
      emitLabel(label("after_break"))
      ("", Ty.Unknown)

    case _: Continue =>
      if loopStack.isEmpty then fail("'continue' outside a loop", n)
      emit(s"br label %${loopStack.top._1}")
      emitLabel(label("after_continue"))
      ("", Ty.Unknown)

    case _: Nop => ("@sfl_null_obj", Ty.Dyn)

    case m: MakeFun =>
      val g = genericOf(m.proto)
      val env = if frameVal.nonEmpty then frameVal else envVal
      val r = tmp()
      emit(s"$r = call ptr @sfl_fun_new(ptr @${symbolOf(g)}, ptr $env, ptr ${protoRef(m.proto)})")
      (r, Ty.Dyn)

    case tl: TupleLit =>
      val buf = entryAlloca(s"[${tl.items.length} x ptr]")
      var ti = 0
      while ti < tl.items.length do
        val (v, t) = emitExpr(tl.items(ti))
        val ptr = tmp(); emit(s"$ptr = getelementptr inbounds ptr, ptr $buf, i64 $ti")
        emit(s"store ptr ${box(v, t)}, ptr $ptr")
        ti += 1
      val r = tmp(); emit(s"$r = call ptr @sfl_tuple_of(i64 ${tl.items.length}, ptr $buf)")
      (r, Ty.Dyn)

    case a: ArrLit =>
      if a.items.isEmpty then
        val r = tmp(); emit(s"$r = call ptr @sfl_arr_new(i64 0)")
        (r, Ty.Dyn)
      else
        val buf = entryAlloca(s"[${a.items.length} x ptr]")
        var i = 0
        while i < a.items.length do
          val (v, t) = emitExpr(a.items(i))
          val p = tmp(); emit(s"$p = getelementptr inbounds ptr, ptr $buf, i64 $i")
          emit(s"store ptr ${box(v, t)}, ptr $p")
          i += 1
        val r = tmp(); emit(s"$r = call ptr @sfl_arr_of(i64 ${a.items.length}, ptr $buf)")
        (r, Ty.Dyn)

    case o: ObjLit =>
      val r = tmp(); emit(s"$r = call ptr @sfl_obj_new()")
      var i = 0
      while i < o.keys.length do
        val k = tmp(); emit(s"$k = load ptr, ptr ${gslot(slotOfString(o.keys(i)))}")
        val (v, t) = emitExpr(o.values(i))
        emit(s"call void @sfl_obj_put(ptr $r, ptr $k, ptr ${box(v, t)})")
        i += 1
      (r, Ty.Dyn)

    case ix: Index =>
      val (recv, rt) = emitExpr(ix.recv)
      val (key, kt) = emitExpr(ix.key)
      // The lookup itself pins its error to the index expression (Node.locate on
      // Index wraps only the get), so the position is set for the op alone and
      // the ambient one comes back right after.
      if ix.col > 0 then setPos(ix, ix.col)
      val r = tmp()
      emit(s"$r = call ptr @sfl_index_get(ptr ${box(recv, rt)}, ptr ${box(key, kt)})")
      if ix.col > 0 then restoreAmbient()
      (r, Ty.Dyn)

    case ix: IndexSet =>
      val (recv, rt) = emitExpr(ix.recv)
      val (key, kt) = emitExpr(ix.key)
      val (v, vt) = emitExpr(ix.value)
      // Assignment does not pin in the interpreter; the statement or call shows through.
      val r = tmp()
      emit(s"$r = call ptr @sfl_index_set(ptr ${box(recv, rt)}, ptr ${box(key, kt)}, ptr ${box(v, vt)})")
      (r, Ty.Dyn)

    case ix: IndexCompound =>
      val (recv, rt) = emitExpr(ix.recv)
      val (key, kt) = emitExpr(ix.key)
      val (v, vt) = emitExpr(ix.value)
      val r = tmp()
      emit(s"$r = call ptr @sfl_index_compound(ptr ${box(recv, rt)}, ptr ${box(key, kt)}, " +
        s"i32 ${ix.op}, ptr ${box(v, vt)})")
      (r, Ty.Dyn)

    case c: Call => emitCall(c)

  /** The frame `depth` links out from here. */
  private def outerFrame(depth: Int): String =
    if frameVal.nonEmpty then
      if depth == 0 then frameVal
      else { val r = tmp(); emit(s"$r = call ptr @sfl_frame_up(ptr $frameVal, i64 $depth)"); r }
    else if depth <= 1 then envVal
    else { val r = tmp(); emit(s"$r = call ptr @sfl_frame_up(ptr $envVal, i64 ${depth - 1})"); r }

  /** A local read before anything assigned it is an error, as it is in the interpreter. */
  private def guardAssigned(v: String, name: String): Unit =
    guardNonNull(v, name, "sfl_unassigned", "unassigned")

  private def guardDefined(v: String, name: String): Unit =
    guardNonNull(v, name, "sfl_undefined", "undef")

  /** Nothing has been stored here yet, which the interpreter reports rather than reads. */
  private def guardNonNull(v: String, name: String, raiser: String, tag: String): Unit =
    val bad = label(tag)
    val ok = label(s"${tag}_ok")
    val c = tmp(); emit(s"$c = icmp eq ptr $v, null")
    emit(s"br i1 $c, label %$bad, label %$ok")
    emitLabel(bad)
    val n = tmp(); emit(s"$n = load ptr, ptr ${gslot(slotOfString(name))}")
    emit(s"call void @$raiser(ptr $n)")
    emit("unreachable")
    emitLabel(ok)

  private def irDouble(d: Double): String =
    if d.isNaN || d.isInfinite then
      f"0x${java.lang.Double.doubleToRawLongBits(d)}%016X"
    else
      val s = d.toString
      if s.contains('.') || s.contains('e') || s.contains('E') then s else s + ".0"

  private def emitArith(a: Arith): (String, Ty) =
    val (lv0, lt) = emitExpr(a.l)
    val (rv0, rt) = emitExpr(a.r)
    if Ty.numeric(lt) && Ty.numeric(rt) then
      val t = Ty.join(lt, rt)
      val lv = coerce(lv0, lt, t)
      val rv = coerce(rv0, rt, t)
      val r = tmp()
      if t == Ty.F64 then
        val op = a.op match
          case BinOp.Add => "fadd"
          case BinOp.Sub => "fsub"
          case BinOp.Mul => "fmul"
          case BinOp.Div => "fdiv"
          case _         => "frem"
        emit(s"$r = $op double $lv, $rv")
      else
        a.op match
          case BinOp.Add => emit(s"$r = add i64 $lv, $rv")
          case BinOp.Sub => emit(s"$r = sub i64 $lv, $rv")
          case BinOp.Mul => emit(s"$r = mul i64 $lv, $rv")
          case _ =>
            // SFL reports division by zero; machine code would trap instead. The
            // position is only stored on the failing path, so the check is free.
            guardNonZero(rv, a)
            if a.op == BinOp.Div then emit(s"$r = sdiv i64 $lv, $rv")
            else emit(s"$r = srem i64 $lv, $rv")
      (r, t)
    else
      val r = tmp()
      emit(s"$r = call ptr @sfl_arith(i32 ${a.op}, ptr ${box(lv0, lt)}, ptr ${box(rv0, rt)})")
      (r, Ty.Dyn)

  private def guardNonZero(v: String, n: Node): Unit =
    val isZero = tmp()
    val bad = label("divzero")
    val ok = label("divok")
    emit(s"$isZero = icmp eq i64 $v, 0")
    emit(s"br i1 $isZero, label %$bad, label %$ok")
    emitLabel(bad)
    // Division does not pin in the interpreter: the enclosing call or statement
    // position is already in sfl_pos, and that is what gets reported.
    emit("call void @sfl_div_zero()")
    emit("unreachable")
    emitLabel(ok)

  private def emitCmp(c: Cmp): (String, Ty) =
    val (lv0, lt) = emitExpr(c.l)
    val (rv0, rt) = emitExpr(c.r)
    val unboxable =
      (Ty.numeric(lt) && Ty.numeric(rt)) ||
        (lt == Ty.Bool && rt == Ty.Bool && (c.op == CmpOp.Eq || c.op == CmpOp.Ne))
    if unboxable then
      val t = Ty.join(lt, rt)
      val lv = coerce(lv0, lt, t)
      val rv = coerce(rv0, rt, t)
      val r = tmp()
      if t == Ty.F64 then
        val op = c.op match
          case CmpOp.Eq => "oeq"
          case CmpOp.Ne => "une"
          case CmpOp.Lt => "olt"
          case CmpOp.Le => "ole"
          case CmpOp.Gt => "ogt"
          case _        => "oge"
        emit(s"$r = fcmp $op double $lv, $rv")
      else
        val ty = if t == Ty.Bool then "i1" else "i64"
        val op = c.op match
          case CmpOp.Eq => "eq"
          case CmpOp.Ne => "ne"
          case CmpOp.Lt => "slt"
          case CmpOp.Le => "sle"
          case CmpOp.Gt => "sgt"
          case _        => "sge"
        emit(s"$r = icmp $op $ty $lv, $rv")
      (r, Ty.Bool)
    else
      // The interpreter raises "cannot compare" with no position and lets the
      // enclosing call or statement pin it (Node.locate); sfl_pos already holds
      // exactly that, so nothing is stored here.
      val l = box(lv0, lt)
      val rr = box(rv0, rt)
      if c.op == CmpOp.Eq || c.op == CmpOp.Ne then
        val e = tmp(); emit(s"$e = call i32 @sfl_equal(ptr $l, ptr $rr)")
        val r = tmp()
        emit(s"$r = icmp ${if c.op == CmpOp.Eq then "ne" else "eq"} i32 $e, 0")
        (r, Ty.Bool)
      else
        val e = tmp(); emit(s"$e = call i32 @sfl_compare(ptr $l, ptr $rr)")
        val op = c.op match
          case CmpOp.Lt => "slt"
          case CmpOp.Le => "sle"
          case CmpOp.Gt => "sgt"
          case _        => "sge"
        val r = tmp(); emit(s"$r = icmp $op i32 $e, 0")
        (r, Ty.Bool)

  /** `&&` and `||`: short-circuiting, and always producing a boolean. */
  private def emitShortCircuit(l: Node, r: Node, wantTrue: Boolean): (String, Ty) =
    val res = entryAlloca("i1")
    val rhs = label("sc_rhs")
    val done = label("sc_done")
    val (lv, lt) = emitExpr(l)
    val lb = truthy(lv, lt)
    emit(s"store i1 $lb, ptr $res")
    if wantTrue then emit(s"br i1 $lb, label %$rhs, label %$done")
    else emit(s"br i1 $lb, label %$done, label %$rhs")
    emitLabel(rhs)
    val (rv, rt) = emitExpr(r)
    val rb = truthy(rv, rt)
    emit(s"store i1 $rb, ptr $res")
    emit(s"br label %$done")
    emitLabel(done)
    val loaded = tmp()
    emit(s"$loaded = load i1, ptr $res")
    (loaded, Ty.Bool)

  private def branchTy(n: Node): Ty =
    val t = tyOf(n, cur)
    if t == Ty.Unknown then Ty.Dyn else t

  private def emitIf(i: If, wantValue: Boolean): (String, Ty) =
    val resTy = if wantValue then branchTy(i) else Ty.Dyn
    val slot = if wantValue then entryAlloca(resTy.llvm) else ""
    val done = label("if_done")
    var k = 0
    while k < i.conds.length do
      val thenL = label("if_then")
      val elseL = label("if_else")
      val (cv, ct) = emitExpr(i.conds(k))
      emit(s"br i1 ${truthy(cv, ct)}, label %$thenL, label %$elseL")
      emitLabel(thenL)
      if wantValue then
        val (bv, bt) = emitExpr(i.bodies(k))
        emit(s"store ${resTy.llvm} ${coerce(bv, bt, resTy)}, ptr $slot")
      else emitVoid(i.bodies(k))
      emit(s"br label %$done")
      emitLabel(elseL)
      k += 1
    if i.orElse != null then
      if wantValue then
        val (ev, et) = emitExpr(i.orElse)
        emit(s"store ${resTy.llvm} ${coerce(ev, et, resTy)}, ptr $slot")
      else emitVoid(i.orElse)
    else if wantValue then emit(s"store ${resTy.llvm} ${zeroOf(resTy)}, ptr $slot")
    emit(s"br label %$done")
    emitLabel(done)
    if !wantValue then ("@sfl_null_obj", Ty.Dyn)
    else
      val r = tmp()
      emit(s"$r = load ${resTy.llvm}, ptr $slot")
      (r, resTy)

  private def emitWhile(w: While, wantValue: Boolean): (String, Ty) =
    val slot = if wantValue then entryAlloca("ptr") else ""
    if wantValue then emit(s"store ptr @sfl_null_obj, ptr $slot")
    val head = label("while_head")
    val body = label("while_body")
    val exit = label("while_exit")
    emit(s"br label %$head")
    emitLabel(head)
    val (cv, ct) = emitExpr(w.cond)
    emit(s"br i1 ${truthy(cv, ct)}, label %$body, label %$exit")
    emitLabel(body)
    loopStack.push((head, exit))
    val saved = (frameVal, slotsBase)
    if w.frameSlots >= 0 then enterLoopFrame(w.frameSlots)
    if wantValue then
      val (bv, bt) = emitExpr(w.body)
      emit(s"store ptr ${box(bv, bt)}, ptr $slot")
    else emitVoid(w.body)
    frameVal = saved._1
    slotsBase = saved._2
    loopStack.pop()
    emit(s"br label %$head")
    emitLabel(exit)
    if !wantValue then ("@sfl_null_obj", Ty.Dyn)
    else
      val r = tmp(); emit(s"$r = load ptr, ptr $slot")
      (r, Ty.Dyn)

  /**
   * A loop whose body builds a closure runs each iteration in its own frame, so
   * the closure captures that iteration's variables rather than sharing one
   * binding — the same rule the interpreter follows.
   */
  private def enterLoopFrame(slots: Int): Unit =
    val parent = if frameVal.nonEmpty then frameVal else envVal
    val f = tmp(); emit(s"$f = call ptr @sfl_frame_new(i64 $slots, ptr $parent)")
    pinFrame(f)
    val b = tmp(); emit(s"$b = call ptr @sfl_frame_slots(ptr $f)")
    frameVal = f
    slotsBase = b

  /** Where the loop variable lives, and therefore how it is stored. */
  private def loopVarTy(f: ForIn): Ty =
    if f.frameSlots >= 0 then Ty.Dyn
    else if f.globalId >= 0 then globalTy(f.globalId)
    else slotTy(f.slot)

  private def emitForIn(f: ForIn, wantValue: Boolean): (String, Ty) =
    val args = rangeArgs(f.iterable)
    val counted = args != null && f.frameSlots < 0 && loopVarTy(f) == Ty.I64
    if counted then emitCountedFor(f, args, wantValue) else emitIterFor(f, wantValue)

  private def emitCountedFor(f: ForIn, args: Array[Node], wantValue: Boolean): (String, Ty) =
    val (startV, endV, stepV) = args.length match
      case 1 =>
        val (e, et) = emitExpr(args(0))
        ("0", coerce(e, et, Ty.I64), "1")
      case 2 =>
        val (a, at) = emitExpr(args(0))
        val (b, bt) = emitExpr(args(1))
        (coerce(a, at, Ty.I64), coerce(b, bt, Ty.I64), "1")
      case _ =>
        val (a, at) = emitExpr(args(0))
        val (b, bt) = emitExpr(args(1))
        val (c, ct) = emitExpr(args(2))
        (coerce(a, at, Ty.I64), coerce(b, bt, Ty.I64), coerce(c, ct, Ty.I64))

    val varPtr = if f.globalId >= 0 then s"@g_${f.globalId}" else slotAddr(f.slot)
    emit(s"store i64 $startV, ptr $varPtr")
    val slot = if wantValue then entryAlloca("ptr") else ""
    if wantValue then emit(s"store ptr @sfl_null_obj, ptr $slot")

    val head = label("for_head")
    val body = label("for_body")
    val step = label("for_step")
    val exit = label("for_exit")
    emit(s"br label %$head")
    emitLabel(head)
    // A negative step counts down, so the test direction depends on its sign.
    val curV = tmp(); emit(s"$curV = load i64, ptr $varPtr")
    val stepNeg = tmp(); emit(s"$stepNeg = icmp slt i64 $stepV, 0")
    val ltEnd = tmp(); emit(s"$ltEnd = icmp slt i64 $curV, $endV")
    val gtEnd = tmp(); emit(s"$gtEnd = icmp sgt i64 $curV, $endV")
    val go = tmp(); emit(s"$go = select i1 $stepNeg, i1 $gtEnd, i1 $ltEnd")
    emit(s"br i1 $go, label %$body, label %$exit")
    emitLabel(body)
    loopStack.push((step, exit))
    if wantValue then
      val (bv, bt) = emitExpr(f.body)
      emit(s"store ptr ${box(bv, bt)}, ptr $slot")
    else emitVoid(f.body)
    loopStack.pop()
    emit(s"br label %$step")
    emitLabel(step)
    val prev = tmp(); emit(s"$prev = load i64, ptr $varPtr")
    val next = tmp(); emit(s"$next = add i64 $prev, $stepV")
    emit(s"store i64 $next, ptr $varPtr")
    emit(s"br label %$head")
    emitLabel(exit)
    if !wantValue then ("@sfl_null_obj", Ty.Dyn)
    else { val r = tmp(); emit(s"$r = load ptr, ptr $slot"); (r, Ty.Dyn) }

  private def emitIterFor(f: ForIn, wantValue: Boolean): (String, Ty) =
    val (src, st) = emitExpr(f.iterable)
    val itPtr = entryAlloca("ptr")
    val it = tmp(); emit(s"$it = call ptr @sfl_iter_begin(ptr ${box(src, st)})")
    emit(s"store ptr $it, ptr $itPtr")
    val cell = entryAlloca("ptr")
    val slot = if wantValue then entryAlloca("ptr") else ""
    if wantValue then emit(s"store ptr @sfl_null_obj, ptr $slot")

    val head = label("iter_head")
    val body = label("iter_body")
    val exit = label("iter_exit")
    emit(s"br label %$head")
    emitLabel(head)
    val itv = tmp(); emit(s"$itv = load ptr, ptr $itPtr")
    val more = tmp(); emit(s"$more = call i32 @sfl_iter_next(ptr $itv, ptr $cell)")
    val go = tmp(); emit(s"$go = icmp ne i32 $more, 0")
    emit(s"br i1 $go, label %$body, label %$exit")
    emitLabel(body)
    val v = tmp(); emit(s"$v = load ptr, ptr $cell")

    val saved = (frameVal, slotsBase)
    if f.frameSlots >= 0 then enterLoopFrame(f.frameSlots)
    if f.frameSlots >= 0 then
      // The variable belongs to this iteration's frame, which is why the closures a
      // loop body builds each see their own value.
      emit(s"store ptr $v, ptr ${slotAddr(f.slot)}")
    else if f.globalId >= 0 then
      val t = globalTy(f.globalId)
      if t == Ty.Dyn then emit(s"store ptr $v, ptr ${gslot(slotOfGlobal(f.globalId))}")
      else emit(s"store ${t.llvm} ${coerce(v, Ty.Dyn, t)}, ptr @g_${f.globalId}")
    else
      val t = slotTy(f.slot)
      emit(s"store ${t.llvm} ${coerce(v, Ty.Dyn, t)}, ptr ${slotAddr(f.slot)}")

    loopStack.push((head, exit))
    if wantValue then
      val (bv, bt) = emitExpr(f.body)
      emit(s"store ptr ${box(bv, bt)}, ptr $slot")
    else emitVoid(f.body)
    loopStack.pop()
    frameVal = saved._1
    slotsBase = saved._2
    emit(s"br label %$head")
    emitLabel(exit)
    if !wantValue then ("@sfl_null_obj", Ty.Dyn)
    else { val r = tmp(); emit(s"$r = load ptr, ptr $slot"); (r, Ty.Dyn) }

  private def emitSelect(s: Select, wantValue: Boolean): (String, Ty) =
    val resTy = if wantValue then branchTy(s) else Ty.Dyn
    val slot = if wantValue then entryAlloca(resTy.llvm) else ""
    val (subj, subjTy) = emitExpr(s.subject)
    val done = label("sel_done")
    var k = 0
    while k < s.caseVals.length do
      val hit = label("sel_hit")
      val miss = label("sel_miss")
      val (cv, ct) = emitExpr(s.caseVals(k))
      val cmp = tmp()
      if Ty.numeric(subjTy) && Ty.numeric(ct) then
        val t = Ty.join(subjTy, ct)
        if t == Ty.F64 then
          emit(s"$cmp = fcmp oeq double ${coerce(subj, subjTy, t)}, ${coerce(cv, ct, t)}")
        else emit(s"$cmp = icmp eq i64 ${coerce(subj, subjTy, t)}, ${coerce(cv, ct, t)}")
      else if subjTy == Ty.Bool && ct == Ty.Bool then
        emit(s"$cmp = icmp eq i1 $subj, $cv")
      else
        val e = tmp()
        emit(s"$e = call i32 @sfl_equal(ptr ${box(cv, ct)}, ptr ${box(subj, subjTy)})")
        emit(s"$cmp = icmp ne i32 $e, 0")
      emit(s"br i1 $cmp, label %$hit, label %$miss")
      emitLabel(hit)
      if wantValue then
        val (bv, bt) = emitExpr(s.caseBodies(k))
        emit(s"store ${resTy.llvm} ${coerce(bv, bt, resTy)}, ptr $slot")
      else emitVoid(s.caseBodies(k))
      emit(s"br label %$done")
      emitLabel(miss)
      k += 1
    if s.orElse != null then
      if wantValue then
        val (ev, et) = emitExpr(s.orElse)
        emit(s"store ${resTy.llvm} ${coerce(ev, et, resTy)}, ptr $slot")
      else emitVoid(s.orElse)
    else if wantValue then emit(s"store ${resTy.llvm} ${zeroOf(resTy)}, ptr $slot")
    emit(s"br label %$done")
    emitLabel(done)
    if !wantValue then ("@sfl_null_obj", Ty.Dyn)
    else { val r = tmp(); emit(s"$r = load ${resTy.llvm}, ptr $slot"); (r, resTy) }

  // ---- calls ---------------------------------------------------------------

  /** Evaluates arguments into a stack array, which is what every dynamic call takes. */
  private def emitArgv(args: Array[Node]): String =
    if args.isEmpty then "null"
    else
      val buf = entryAlloca(s"[${args.length} x ptr]")
      var i = 0
      while i < args.length do
        val (v, t) = emitExpr(args(i))
        val p = tmp(); emit(s"$p = getelementptr inbounds ptr, ptr $buf, i64 $i")
        emit(s"store ptr ${box(v, t)}, ptr $p")
        i += 1
      buf

  private def emitCall(c: Call): (String, Ty) =
    val builtin = builtinOf(c.callee)
    val extId = c.callee match
      case g: GlobalGet if externalFn.contains(g.id) => g.id
      case _                                         => -1
    if builtin.nonEmpty then emitBuiltinCall(builtin, c)
    else if extId >= 0 then
      // A public function of a linked package: its generic entry lives in the
      // archive, reached exactly as a standard-library function is.
      val pinned = pushCallPos(c)
      val argv = emitArgv(c.args)
      val r = tmp()
      emit(s"$r = call ptr @sfl_call_code(ptr @${externalCodeSymbol(extId)}, ptr null, " +
        s"i64 ${c.args.length}, ptr $argv)")
      popCallPos(pinned)
      (r, Ty.Dyn)
    else
      val proto = userFnOf(c.callee)
      if proto != null then
        val argTys = c.args.map(a => tyOf(a, cur))
        val spec = fns.get(Fn.keyOf(proto, argTys, generic = false)).filter(_.index >= 0)
        spec match
          case Some(s) if !s.generic => emitStaticCall(s, c)
          case _                     => emitGenericCall(proto, c)
      else
        val pinned = pushCallPos(c)
        val (fnv, ft) = emitExpr(c.callee)
        val fnBoxed = box(fnv, ft)
        val argv = emitArgv(c.args)
        if c.tail && cur.generic then
          val r = tmp()
          emit(s"$r = call ptr @sfl_tail_set(ptr $fnBoxed, i64 ${c.args.length}, ptr $argv)")
          popCallPos(pinned)
          (r, Ty.Dyn)
        else
          if c.tail then popCallPos(pinned)
          val popped = tailFramePop(c)
          val r = tmp()
          emit(s"$r = call ptr @sfl_call(ptr $fnBoxed, i64 ${c.args.length}, ptr $argv)")
          tailFrameRepush(popped)
          if !c.tail then popCallPos(pinned)
          (r, Ty.Dyn)

  private def emitStaticCall(spec: Fn, c: Call): (String, Ty) =
    val pinned = pushCallPos(c)
    val args = c.args.zipWithIndex.map { (a, i) =>
      val (v, t) = emitExpr(a)
      s"${spec.argTys(i).llvm} ${coerce(v, t, spec.argTys(i))}"
    }
    // A tail call reports and records the original call site, so the pin comes
    // off — and the caller's frame with it — before the callee runs.
    if c.tail then popCallPos(pinned)
    val popped = tailFramePop(c)
    val r = tmp()
    emit(s"$r = call ${spec.ret.llvm} @${spec.symbol}(${args.mkString(", ")})")
    tailFrameRepush(popped)
    if !c.tail then popCallPos(pinned)
    (r, spec.ret)

  private def emitGenericCall(proto: FnProto, c: Call): (String, Ty) =
    val g = genericOf(proto)
    val pinned = pushCallPos(c)
    val argv = emitArgv(c.args)
    if c.tail && cur.generic then
      val fnv = tmp(); emit(s"$fnv = load ptr, ptr ${gslot(slotOfFun(proto))}")
      val r = tmp()
      emit(s"$r = call ptr @sfl_tail_set(ptr $fnv, i64 ${c.args.length}, ptr $argv)")
      popCallPos(pinned)
      (r, Ty.Dyn)
    else
      if c.tail then popCallPos(pinned)
      val popped = tailFramePop(c)
      val r = tmp()
      emit(s"$r = call ptr @sfl_call_code(ptr @${symbolOf(g)}, ptr null, i64 ${c.args.length}, ptr $argv)")
      tailFrameRepush(popped)
      if !c.tail then popCallPos(pinned)
      (r, Ty.Dyn)

  private def emitBuiltinCall(name: String, c: Call): (String, Ty) =
    // The pin covers intrinsic expansions too: whatever an intrinsic emits, an
    // error inside it reports the builtin call, as the interpreter's Call does.
    val pinned = pushCallPos(c)
    val res = emitBuiltinCallBody(name, c)
    popCallPos(pinned)
    res

  private def emitBuiltinCallBody(name: String, c: Call): (String, Ty) =
    Intrinsics.emitStatic(this, name, c) match
      case Some(res) => res
      case None =>
        Prims.byName.get(name) match
          case Some(p) if !Prims.available.contains(p.module) =>
            fail(s"'$name' is not available in compiled programs yet", c,
              s"the ${p.module} part of the runtime is not implemented for compilation; " +
                "run the program with the interpreter instead")
          case Some(p) =>
            val argv = emitArgv(c.args)
            val n = c.args.length
            if n < p.min || (p.max >= 0 && n > p.max) then
              emit(s"call void @sfl_arity_fail(ptr ${cstr(name)}, i64 $n)")
              emit("unreachable")
              emitLabel(label("after_arity"))
              ("@sfl_null_obj", Ty.Dyn)
            else
              val r = tmp()
              emit(s"$r = call ptr @${p.symbol}(i64 $n, ptr $argv)")
              usedPrims += p
              (r, Ty.Dyn)
          case None =>
            Stdlib.byName.get(name) match
              case Some(f) =>
                val argv = emitArgv(c.args)
                usedStdlib += f
                val r = tmp()
                emit(s"$r = call ptr @sfl_call_code(ptr @${f.symbol}, ptr null, " +
                  s"i64 ${c.args.length}, ptr $argv)")
                (r, Ty.Dyn)
              case None =>
                fail(s"'$name' is not available in compiled programs", c,
                  "it needs the interpreter itself, which a compiled binary does not carry")

  private val usedPrims = mutable.LinkedHashSet.empty[Prim]
  private val usedStdlib = mutable.LinkedHashSet.empty[StdFn]

  /** Modules the linker must pull in, so unused parts of the library stay out. */
  def linkedStdlibModules: Set[String] = usedStdlib.map(_.module).toSet

  // ---- constants -----------------------------------------------------------

  private val cstrs = mutable.LinkedHashMap.empty[String, String]

  /** A NUL-terminated C string constant, for names the runtime prints. */
  private def cstr(s: String): String =
    cstrs.getOrElseUpdate(s, s"@.c${cstrs.size}")

  /** UTF-8, except an unpaired surrogate keeps its three-byte spelling (WTF-8).
    * A constant must decode back to the exact code units the source held, as the
    * interpreter's String does; the charset encoder would write '?' and lose
    * them. The runtime's utf8_to_utf16 reads this spelling back verbatim. */
  private def wtf8Bytes(s: String): Array[Byte] =
    val out = new java.io.ByteArrayOutputStream(s.length * 3 / 2 + 4)
    var i = 0
    while i < s.length do
      val c = s.charAt(i).toInt
      var cp = c
      if c >= 0xd800 && c < 0xdc00 && i + 1 < s.length
        && s.charAt(i + 1) >= 0xdc00 && s.charAt(i + 1) < 0xe000
      then
        cp = 0x10000 + ((c - 0xd800) << 10) + (s.charAt(i + 1) - 0xdc00)
        i += 1
      if cp < 0x80 then out.write(cp)
      else if cp < 0x800 then
        out.write(0xc0 | (cp >> 6))
        out.write(0x80 | (cp & 0x3f))
      else if cp < 0x10000 then
        out.write(0xe0 | (cp >> 12))
        out.write(0x80 | ((cp >> 6) & 0x3f))
        out.write(0x80 | (cp & 0x3f))
      else
        out.write(0xf0 | (cp >> 18))
        out.write(0x80 | ((cp >> 12) & 0x3f))
        out.write(0x80 | ((cp >> 6) & 0x3f))
        out.write(0x80 | (cp & 0x3f))
      i += 1
    out.toByteArray

  private def escapeIr(s: String): String =
    val sb = new StringBuilder
    for b <- wtf8Bytes(s) do
      val i = b & 0xff
      if i == 0x22 || i == 0x5c || i < 0x20 || i > 0x7e then sb.append("\\%02X".format(i))
      else sb.append(i.toChar)
    sb.append("\\00")
    sb.toString

  private def byteLen(s: String): Int =
    wtf8Bytes(s).length + 1

  // ---- functions -----------------------------------------------------------

  private val bodies = new StringBuilder

  private def beginFunction(f: Fn): Unit =
    out.setLength(0)
    entryBuf.setLength(0)
    tmpCount = 0
    cur = f
    curRaises = raises.getOrElse(f.key, true)
    curFile = fileIndex(if f.proto != null then f.proto.src else src)
    slotPtr = Array.empty
    slotsBase = ""
    frameVal = ""
    envVal = "null"
    inDefaults = false
    envSlotsReg = ""
    callPosStack = Nil
    stmtPos = PConst("0")
    curFramePushed = false

  private var retSlot = ""
  private var retLabel = ""

  private def emitFunction(f: Fn): Unit =
    if f.emitted || f.index < 0 then return
    f.emitted = true
    beginFunction(f)
    val proto = f.proto
    val exportName = exportedSymbol(f)
    val linkage = if exportName.isDefined then "" else "internal "

    if f.generic then
      out.append(s"define ${linkage}ptr @${exportName.getOrElse(f.symbol)}(ptr %env, i64 %argc, ptr %argv) {\n")
      envVal = "%env"
      retLabel = "ret_exit"
      retSlot = entryAlloca("ptr")
      emitLabel("entry")
      emit(s"store ptr @sfl_null_obj, ptr $retSlot")
      if heapFrame(proto) then
        val fr = tmp(); emit(s"$fr = call ptr @sfl_frame_new(i64 ${proto.nSlots}, ptr %env)")
        pinFrame(fr)
        val b = tmp(); emit(s"$b = call ptr @sfl_frame_slots(ptr $fr)")
        frameVal = fr
        slotsBase = b
      else
        val b = entryAlloca(s"[${math.max(proto.nSlots, 1)} x ptr]")
        emit(s"call void @llvm.memset.p0.i64(ptr $b, i8 0, i64 ${math.max(proto.nSlots, 1) * 8}, i1 false)")
        slotsBase = b
      val filled = tmp()
      emit(s"$filled = call i64 @sfl_bind_args_raw(ptr $slotsBase, ptr ${protoRef(proto)}, " +
        s"i64 %argc, ptr %argv)")
      // The caller's position — what an unpinned error anywhere in this body
      // reports — is saved before the defaults run: the interpreter evaluates
      // defaults under the calling node too. Restored on the way out so the
      // caller's context survives the call. Library functions render as natives
      // do under the interpreter: no frame, no positions of their own.
      val savedPos =
        if emitPositions then
          val p = tmp()
          emit(s"$p = load i64, ptr @sfl_pos")
          stmtPos = PConst(p)
          callPosStack = Nil
          p
        else ""
      emitDefaults(proto, filled)
      if callsAnything(proto.body) then emitStackCheck()
      if emitPositions then emit(s"call void @sfl_frame_push(ptr ${cstr(proto.name)})")
      curFramePushed = emitPositions
      val (v, t) = emitExpr(proto.body)
      emit(s"store ptr ${box(v, t)}, ptr $retSlot")
      emit(s"br label %$retLabel")
      emitLabel(retLabel)
      if emitPositions then
        emit("call void @sfl_frame_pop()")
        emit(s"store i64 $savedPos, ptr @sfl_pos")
      val rv = tmp(); emit(s"$rv = load ptr, ptr $retSlot")
      emit(s"ret ptr $rv")
      out.append("}\n\n")
    else
      val params = f.argTys.zipWithIndex.map((t, i) => s"${t.llvm} %arg$i").mkString(", ")
      out.append(s"define internal ${f.ret.llvm} @${f.symbol}($params) {\n")
      retLabel = "ret_exit"
      retSlot = entryAlloca(f.ret.llvm)
      slotPtr = Array.fill(f.nSlots)("")
      var i = 0
      while i < f.nSlots do
        slotPtr(i) = entryAlloca(slotTy(i).llvm)
        i += 1
      emitLabel("entry")
      emit(s"store ${f.ret.llvm} ${zeroOf(f.ret)}, ptr $retSlot")
      i = 0
      while i < f.nSlots do
        if i < f.argTys.length then emit(s"store ${slotTy(i).llvm} %arg$i, ptr ${slotPtr(i)}")
        else emit(s"store ${slotTy(i).llvm} ${zeroOf(slotTy(i))}, ptr ${slotPtr(i)}")
        i += 1
      // A function that cannot fail needs neither a traceback frame nor the caller's
      // position kept for it, which is what keeps pure arithmetic free of runtime.
      // Counting activations is not enough on its own: a compiled frame is small, so
      // a depth limit high enough to be useful is still more stack than there is.
      // Asking how much stack is left is both cheaper and the actual question.
      if proto != null && callsAnything(proto.body) then emitStackCheck()
      val savedPos = if curRaises && emitPositions && proto != null then
        emit(s"call void @sfl_frame_push(ptr ${cstr(proto.name)})")
        val p = tmp()
        emit(s"$p = load i64, ptr @sfl_pos")
        p
      else ""
      stmtPos = PConst(if savedPos.nonEmpty then savedPos else "0")
      callPosStack = Nil
      curFramePushed = savedPos.nonEmpty
      val (v, t) = emitExpr(proto.body)
      emit(s"store ${f.ret.llvm} ${coerce(v, t, f.ret)}, ptr $retSlot")
      emit(s"br label %$retLabel")
      emitLabel(retLabel)
      if savedPos.nonEmpty then
        emit("call void @sfl_frame_pop()")
        emit(s"store i64 $savedPos, ptr @sfl_pos")
      val rv = tmp(); emit(s"$rv = load ${f.ret.llvm}, ptr $retSlot")
      emit(s"ret ${f.ret.llvm} $rv")
      out.append("}\n\n")

    val body = out.toString
    val head = body.indexOf("entry:\n")
    bodies.append(body.substring(0, head + 7)).append(entryBuf).append(body.substring(head + 7))

  /**
   * Parameters the caller left out. Defaults are expressions evaluated in the scope
   * the function was defined in, so they run against `env`, not the new frame.
   */
  private def emitDefaults(proto: FnProto, filled: String): Unit =
    val hasDefaults = (0 until proto.fixed).exists(i => proto.defaults(i) != null)
    if !hasDefaults then return
    val savedFrame = frameVal
    val savedBase = slotsBase
    val done = label("defaults_done")
    val labels = (0 until proto.fixed).map(_ => label("default")).toArray
    val cases = (0 until proto.fixed)
      .map(i => s"i64 $i, label %${labels(i)}")
      .mkString(" ")
    emit(s"switch i64 $filled, label %$done [ $cases ]")
    var i = 0
    while i < proto.fixed do
      emitLabel(labels(i))
      if proto.defaults(i) != null then
        // Evaluate in the defining scope, then store into the callee's slot. The
        // parser resolved the default's names in that scope, so its Local nodes
        // index the captured frame and its Outer nodes count up from there —
        // exactly how the interpreter runs defaults against `fn.env`. Handing
        // emitExpr `%env` as the current frame makes both come out right.
        frameVal = envVal
        slotsBase = ""
        inDefaults = true
        val (v, t) = emitDefaultValue(proto.defaults(i))
        inDefaults = false
        frameVal = savedFrame
        slotsBase = savedBase
        val p = tmp(); emit(s"$p = getelementptr inbounds ptr, ptr $slotsBase, i64 $i")
        emit(s"store ptr ${box(v, t)}, ptr $p")
      emit(s"br label %${if i + 1 < proto.fixed then labels(i + 1) else done}")
      i += 1
    emitLabel(done)

  private def emitDefaultValue(n: Node): (String, Ty) = emitExpr(n)

  /**
   * A standard-library module compiles to exported entry points and an initialiser
   * instead of to a program, so the archive can be built once and any program can
   * link the parts it uses.
   */
  private var libraryModule: String = ""
  def buildAsLibrary(module: String): Unit = libraryModule = module

  /**
   * A package module compiles the way the standard library does — exported entry
   * points and an initialiser rather than a program — but keeps its positions and
   * traceback frames, because a package is ordinary SFL that a program runs the
   * same whether it linked the archive or inlined the source. `pkgBuild` is
   * (package, module path, version directory, this module's source path); the
   * version directory tells emission which sibling functions are provided by the
   * rest of the archive rather than defined here.
   */
  private var pkgBuild: Option[(String, String, String, String)] = None
  def buildAsPackageModule(pkg: String, moduleRel: String, versionDir: String,
                           moduleSource: String): Unit =
    pkgBuild = Some((pkg, moduleRel, versionDir, moduleSource))

  private def pkgFbaseSymbol: String = pkgBuild match
    case Some((pkg, mod, _, _)) => PkgTool.Binary.fbaseSymbol(pkg, mod)
    case None                   => "sfl_pkg_fbase_unused"

  /** True for the top-level program build (not the stdlib, not a package archive). */
  private def isProgram: Boolean = libraryModule.isEmpty && pkgBuild.isEmpty

  /**
   * Functions provided from outside this compilation: sibling modules of the
   * package being archived, or every module of a package a program links. The value
   * is (package, module path, function name), which names the export symbol. Only
   * public functions appear — a program can name no other, and a package's private
   * helpers stay inside the archive.
   */
  private val externalFn = mutable.HashMap.empty[Int, (String, String, String)]

  private def externalCodeSymbol(id: Int): String =
    val (pkg, mod, name) = externalFn(id)
    PkgTool.Binary.codeSymbol(pkg, mod, name)
  private def externalProtoSymbol(id: Int): String =
    val (pkg, mod, name) = externalFn(id)
    PkgTool.Binary.protoSymbol(pkg, mod, name)

  /** Program build: functions referenced as values, so a VFun over the archive
    * symbol is built once and kept in a slot. */
  private val extFunSlot = mutable.LinkedHashMap.empty[Int, Int]
  private def slotOfExtFun(id: Int): Int =
    extFunSlot.getOrElseUpdate(id, newSlot(s"linked function ${externalFn(id)._3}"))

  // ---- program-side linked packages ---------------------------------------

  /** A module reached through a linked package: its source, and the fbase global
    * whose value the program sets to that source's file index. In dependency order. */
  private final case class LinkedModule(pkg: String, moduleRel: String,
                                        sourcePath: String, sourceText: String)
  private val linkedModules = mutable.LinkedHashMap.empty[String, LinkedModule] // by sourcePath
  /** Version directories whose archives the linker must be given, dependents first. */
  private val linkedArchives = mutable.LinkedHashMap.empty[String, File]        // versionDir -> lib.a
  /** Which module inits to call at each linked import site, in dependency order. */
  private val linkedInitsAt = mutable.LinkedHashMap.empty[ModuleBlock, List[String]]
  private val linkedPackageNames = mutable.LinkedHashSet.empty[String]

  /** The archives to link, in the order the linker wants them (a program before the
    * packages it uses; this build only supports packages with no package deps of
    * their own, so insertion order — the order imports were met — suffices). */
  def linkedArchiveFiles: Seq[File] = linkedArchives.values.toSeq

  /** After a package-module compile: what its abi entry records. */
  def moduleExports: List[String] = pkgBuild match
    case Some((_, _, _, moduleSource)) =>
      userFns.values.filter(p => p.src.name == moduleSource && !p.name.startsWith("_"))
        .map(_.name).toList.distinct
    case None => Nil
  def usedPrimNames: List[String] = usedPrims.map(_.name).toList.distinct
  def usedStdlibNames: List[String] = usedStdlib.map(_.name).toList.distinct
  /** Packages this module depends on. This build links only packages with none. */
  def packageDeps: List[String] = Nil

  private def moduleRelOf(canonicalPath: String, versionDir: String): String =
    canonicalPath.stripPrefix(versionDir).stripSuffix(".sfl").replace(File.separatorChar, '/')

  /** Whether a global is part of a module's surface: not one of its private names. */
  private def isPublicGlobalName(id: Int): Boolean =
    val nm = globals.nameOf(id)
    nm != null && !nm.substring(nm.lastIndexOf('#') + 1).startsWith("_")

  private def exportedSymbol(f: Fn): Option[String] =
    if !f.generic || f.proto == null then None
    else if f.proto.name.startsWith("_") then None
    // A top-level anonymous function is held in whatever binding carries it,
    // never referenced by name across objects — and every one of them is
    // called "lambda", so exporting would collide the moment two modules of
    // an archive each have one (stdlib/http.sfl and stdlib/coll.sfl do).
    else if f.proto.name == "lambda" then None
    else if !userFns.valuesIterator.exists(_ eq f.proto) then None
    else pkgBuild match
      case Some((pkg, _, versionDir, moduleSource)) =>
        // Only this module's own public functions are exported here; a sibling's
        // belong to the sibling's object.
        if f.proto.src.name == moduleSource then
          Some(PkgTool.Binary.codeSymbol(pkg, moduleRelOf(moduleSource, versionDir), f.proto.name))
        else None
      case None =>
        if libraryModule.nonEmpty then Some(s"sflstd_${f.proto.name}") else None

  /** The name to call an instance by, which in a library build is the exported one. */
  private def symbolOf(f: Fn): String = exportedSymbol(f).getOrElse(f.symbol)

  // ---- module assembly -----------------------------------------------------

  private def emitStartup(): Unit =
    emit(s"call void @sfl_gc_add_roots(ptr $slotsGlobal, i64 SLOTCOUNT)")
    // Each library module the program reached sets up its own literals and roots.
    for init <- stdlibInits do emit(s"call void @$init()")
    if isProgram then
      emit(s"call void @sfl_set_suggest_names(ptr @sfl_suggest_rows, i64 SUGGESTCOUNT)")
      // A linked package's modules are ordinary SFL: their source is registered so
      // errors quote them, and each module's fbase global is set to the file index
      // it landed at, so the archive's positions resolve to the same lines the
      // interpreter shows. The stores happen before any module init runs (that is
      // in the body), which is what the archive's positions depend on.
      for (_, m) <- linkedModules do fileIndex(SourceRef(m.sourcePath, m.sourceText))
      // Only the program's own sources were quotable before packages; now the
      // linked modules' sources are too, and both are registered here.
      for (name, (_, text)) <- sourceFiles do
        emit(s"call void @sfl_add_source(ptr ${cstr(name)}, ptr ${cstr(text)})")
      for (_, m) <- linkedModules do
        val idx = fileIndex(SourceRef(m.sourcePath, m.sourceText))
        emit(s"store i64 $idx, ptr @${PkgTool.Binary.fbaseSymbol(m.pkg, m.moduleRel)}")
    for (text, slot) <- strSlot do
      val r = tmp()
      emit(s"$r = call ptr @sfl_str_utf8(ptr ${cstr(text)}, i64 ${byteLen(text) - 1})")
      emit(s"store ptr $r, ptr ${gslot(slot)}")
    for (name, slot) <- natSlot do
      val r = tmp()
      emit(s"$r = call ptr @sfl_native_value(ptr ${cstr(name)})")
      emit(s"store ptr $r, ptr ${gslot(slot)}")
    for (f, slot) <- stdFunSlot do
      val r = tmp()
      emit(s"$r = call ptr @sfl_fun_new(ptr @${f.symbol}, ptr null, ptr @sflstd_proto_${f.name})")
      emit(s"store ptr $r, ptr ${gslot(slot)}")
    for (id, slot) <- extFunSlot do
      val r = tmp()
      emit(s"$r = call ptr @sfl_fun_new(ptr @${externalCodeSymbol(id)}, ptr null, " +
        s"ptr @${externalProtoSymbol(id)})")
      emit(s"store ptr $r, ptr ${gslot(slot)}")
    for (proto, slot) <- funSlot do
      val g = genericOf(proto)
      val r = tmp()
      emit(s"$r = call ptr @sfl_fun_new(ptr @${symbolOf(g)}, ptr null, ptr ${protoRef(proto)})")
      emit(s"store ptr $r, ptr ${gslot(slot)}")

  private def emitMain(): Unit =
    beginFunction(fns("main"))
    emitLabel("entry")
    // A library or package module holds only definitions; anything else in it runs
    // when the program initialises the module.
    for st <- mainStmts do emitStmt(st)
    emit("ret void")
    val body = out.toString.substring("entry:\n".length)
    out.setLength(0)
    emitStartup()
    val startup = out.toString
    val name = pkgBuild match
      case Some((pkg, mod, _, _)) => PkgTool.Binary.initSymbol(pkg, mod)
      case None => if libraryModule.isEmpty then "sfl_main" else s"sflstd_init_$libraryModule"
    bodies.append(s"define void @$name() {\nentry:\n")
      .append(entryBuf).append(startup).append(body).append("}\n\n")

  /** Initialisers the program must call, one per linked library module. */
  private def stdlibInits: Seq[String] = usedStdlib.map(f => s"sflstd_init_${f.module}").toSeq.distinct

  /**
   * The "did you mean" candidates, enumerated exactly as the interpreter's
   * `visibleFrom` would at the moment of an error: every global id in order —
   * builtins first, since they are installed before anything is parsed — spelled
   * the way its own file spells it, with private names left out and each spelling
   * emitted once (a builtin occupies three slots and is one candidate). Builtins
   * are defined from startup and carry no slot. A global the program assigns
   * dynamically is checked through its runtime cell, so the name only counts once
   * it holds a value — which is also what keeps an undefined name from suggesting
   * itself. Globals unboxed to machine words and top-level `def`s have no cell to
   * test and count as defined from the start; only an error raised above their
   * definition would notice, and then only if no closer match exists.
   */
  private def suggestRows(): Seq[String] =
    val rows = mutable.ArrayBuffer.empty[String]
    val seen = mutable.HashSet.empty[String]
    var id = 0
    val n = globals.size
    while id < n do
      val name = globals.displayOf(id)
      // One row per spelling: the same builtin under `map`, `std#map` and
      // `array#map` is one candidate, not three.
      if isPublicGlobalName(id) && seen.add(name) then
        if builtinIds.contains(id) then
          rows += s"{ ptr, ptr } { ptr ${cstr(name)}, ptr null }"
        else
          dynGlobalSlot.get(id) match
            case Some(k) =>
              rows += s"{ ptr, ptr } { ptr ${cstr(name)}, ptr ${gslot(k)} }"
            case None =>
              if userFns.contains(id) ||
                 globalTys.get(id).exists(t => t != Ty.Dyn && t != Ty.Unknown) then
                rows += s"{ ptr, ptr } { ptr ${cstr(name)}, ptr null }"
      id += 1
    rows.toSeq

  def compile(top: Block): String =
    infer(top)
    computeRaises()

    // Emitting a body can ask for instances nobody had needed yet, so keep going
    // until the worklist is empty.
    var pending = fns.values.filter(f => f.proto != null && !f.emitted && f.index >= 0).toList
    while pending.nonEmpty do
      pending.foreach(emitFunction)
      pending = fns.values.filter(f => f.proto != null && !f.emitted && f.index >= 0).toList
    emitMain()
    slotCount = math.max(slotNames.size, 1)
    // Built after emitMain, once every dynamic global has its final slot.
    val suggestions = if isProgram then suggestRows() else Nil
    val patched = bodies.toString
      .replace("i64 SLOTCOUNT", s"i64 $slotCount")
      .replace("i64 SUGGESTCOUNT", s"i64 ${suggestions.size}")
    bodies.setLength(0)
    bodies.append(patched)

    val h = new StringBuilder
    h.append("; LLVM IR generated by the SFL compiler\n")
    h.append(s"; source: ${src.name}\n\n")
    h.append(Compiler.declares)
    for p <- usedPrims do h.append(s"declare ptr @${p.symbol}(i64, ptr)\n")
    for f <- usedStdlib do h.append(s"declare ptr @${f.symbol}(ptr, i64, ptr)\n")
    for init <- stdlibInits do h.append(s"declare void @$init()\n")
    for f <- stdFunSlot.keys do h.append(s"@sflstd_proto_${f.name} = external global %SflProto\n")
    // Symbols a linked package (or, in an archive build, a sibling module) provides.
    val extCodeSyms = externalFn.values.map((p, m, n) => PkgTool.Binary.codeSymbol(p, m, n)).toSet
    val extProtoSyms = externalFn.values.map((p, m, n) => PkgTool.Binary.protoSymbol(p, m, n)).toSet
    for s <- extCodeSyms do h.append(s"declare ptr @$s(ptr, i64, ptr)\n")
    for s <- extProtoSyms do h.append(s"@$s = external constant %SflProto\n")
    for (_, m) <- linkedModules do
      h.append(s"declare void @${PkgTool.Binary.initSymbol(m.pkg, m.moduleRel)}()\n")
    h.append('\n')

    // The program owns each linked module's fbase and sets it at startup; an archive
    // module references its own as external, resolved to the program's definition.
    val fbaseDefs = linkedModules.values.map(m => PkgTool.Binary.fbaseSymbol(m.pkg, m.moduleRel)).toSet
    for s <- fbaseDefs do h.append(s"@$s = global i64 0\n")
    pkgBuild.foreach { _ => h.append(s"@$pkgFbaseSymbol = external global i64\n") }
    h.append('\n')

    h.append(s"$slotsGlobal = internal global [$slotCount x ptr] zeroinitializer\n")
    if isProgram then
      h.append(s"@sfl_suggest_rows = internal global [${math.max(suggestions.size, 1)} x { ptr, ptr }] " +
        (if suggestions.isEmpty then "zeroinitializer\n"
         else s"[ ${suggestions.mkString(", ")} ]\n"))
    for (id, ty) <- globalTys.toSeq.sortBy(_._1) if ty != Ty.Dyn do
      h.append(s"@g_$id = internal global ${ty.llvm} ${zeroOf(ty)}")
      h.append(s"    ; ${globals.displayOf(id)}\n")
    h.append('\n')

    // Rendering these is what interns the strings they name, so they are built
    // before the pool of C strings is written out.
    val natives = if isProgram then nativesTable() else ""
    val protos = new StringBuilder
    for (proto, name) <- protoConst do
      val file = fileIndex(proto.src)
      val linkage =
        if name.startsWith("@sflstd_proto_") || name.startsWith("@sflpkg_") then "constant"
        else "private unnamed_addr constant"
      protos.append(s"$name = $linkage %SflProto ")
      protos.append(s"{ ptr ${cstr(proto.name)}, ptr ${cstr(proto.signature)}, i32 $file, ")
      protos.append(s"i32 ${proto.params.length}, i32 ${proto.required}, i32 ${proto.fixed}, ")
      protos.append(s"i32 ${if proto.hasRest then 1 else 0} }\n")

    for (text, name) <- cstrs do
      h.append(s"""$name = private unnamed_addr constant [${byteLen(text)} x i8] c"${escapeIr(text)}"\n""")
    h.append('\n')
    h.append(protos)
    h.append('\n')

    h.append(natives)
    h.append(bodies)
    h.toString

  /**
   * The runtime's view of the builtin library, emitted here rather than in the
   * runtime so that a program links only the primitives it uses. Metadata is
   * present for every builtin — `describe` and `builtins()` must see them all —
   * while the code pointer is filled in only for the ones this program calls.
   */
  private def nativesTable(): String =
    val used = usedPrims.map(_.name).toSet
    val sb = new StringBuilder
    // Doc lines come from the interpreter's registry, which is the one place they
    // are written; arity errors and describe() must show the same text either way.
    def docOf(name: String): String = Builtins.lookup(name).map(_.doc).getOrElse("")
    val primRows = Prims.all.map { p =>
      val fn = if used.contains(p.name) then s"ptr @${p.symbol}" else "ptr null"
      s"%SflNative { ptr ${cstr(p.name)}, ptr ${cstr(p.signature)}, ptr ${cstr(docOf(p.name))}, " +
        s"i32 ${p.min}, i32 ${p.max}, $fn }"
    }
    // Library functions appear here as description only: a program reaches them by
    // name at compile time, but `builtins()` and `describe()` must still see them,
    // because to a program they are builtins like any other. A name a primitive
    // covers already has a row above; its SFL twin exists only for importers.
    val stdRows = Stdlib.all.filterNot(f => Prims.byName.contains(f.name)).map { f =>
      s"%SflNative { ptr ${cstr(f.name)}, ptr ${cstr(f.signature)}, ptr ${cstr(docOf(f.name))}, " +
        s"i32 ${f.min}, i32 ${f.max}, ptr null }"
    }
    val rows = primRows ++ stdRows
    sb.append(s"@sfl_natives = global [${rows.size} x %SflNative] [\n  ")
    sb.append(rows.mkString(",\n  "))
    sb.append("\n]\n")
    sb.append(s"@sfl_native_count = global i32 ${rows.size}\n\n")
    sb.toString

  /** Summary shown after a successful compile. */
  def summary: String =
    val specialised = fns.values.count(f => !f.generic && f.proto != null && f.index >= 0)
    val generic = fns.values.count(f => f.generic && f.emitted)
    val boxedGlobals = globalTys.count(_._2 == Ty.Dyn)
    val typedGlobals = globalTys.size - boxedGlobals
    s"$specialised specialised, $generic generic function(s); " +
      s"$typedGlobals unboxed and $boxedGlobals boxed global(s); " +
      s"${usedPrims.size} primitive(s), ${usedStdlib.size} library function(s)"

  // Accessors used by Intrinsics, which emits into the same buffer.
  private[sfl] def emitLine(line: String): Unit = emit(line)
  private[sfl] def freshTmp(): String = tmp()
  private[sfl] def freshLabel(base: String): String = label(base)
  private[sfl] def emitBlockLabel(l: String): Unit = emitLabel(l)
  private[sfl] def emitOperand(n: Node): (String, Ty) = emitExpr(n)
  private[sfl] def widen(v: String, from: Ty, to: Ty): String = coerce(v, from, to)
  private[sfl] def boxed(v: String, t: Ty): String = box(v, t)
  private[sfl] def reject(msg: String, n: Node, hint: String = ""): Nothing = fail(msg, n, hint)
  private[sfl] def typeOf(n: Node): Ty =
    val t = tyOf(n, cur)
    if t == Ty.Unknown then Ty.Dyn else t

private object Compiler:
  /** Everything the generated module needs from the runtime. */
  private val declares: String =
    """%SflProto = type { ptr, ptr, i32, i32, i32, i32, i32 }
      |%SflNative = type { ptr, ptr, ptr, i32, i32, ptr }
      |
      |@sfl_pos = external global i64
      |@sfl_null_obj = external global i8
      |@sfl_true_obj = external global i8
      |@sfl_false_obj = external global i8
      |
      |declare void @llvm.memset.p0.i64(ptr, i8, i64, i1)
      |declare ptr @sfl_int(i64)
      |declare ptr @sfl_float(double)
      |declare ptr @sfl_bool(i32)
      |declare i64 @sfl_unbox_int(ptr)
      |declare double @sfl_unbox_float(ptr)
      |declare i32 @sfl_truthy(ptr)
      |declare ptr @sfl_arith(i32, ptr, ptr)
      |declare ptr @sfl_neg(ptr)
      |declare i32 @sfl_equal(ptr, ptr)
      |declare i32 @sfl_compare(ptr, ptr)
      |declare ptr @sfl_index_get(ptr, ptr)
      |declare ptr @sfl_index_set(ptr, ptr, ptr)
      |declare ptr @sfl_index_compound(ptr, ptr, i32, ptr)
      |declare ptr @sfl_arr_new(i64)
      |declare ptr @sfl_arr_of(i64, ptr)
      |declare ptr @sfl_tuple_of(i64, ptr)
      |declare ptr @sfl_obj_new()
      |declare void @sfl_obj_put(ptr, ptr, ptr)
      |declare ptr @sfl_str_utf8(ptr, i64)
      |declare ptr @sfl_fun_new(ptr, ptr, ptr)
      |declare ptr @sfl_native_value(ptr)
      |declare ptr @sfl_frame_new(i64, ptr)
      |declare ptr @sfl_frame_slots(ptr)
      |declare ptr @sfl_frame_up(ptr, i64)
      |declare i64 @sfl_bind_args_raw(ptr, ptr, i64, ptr)
      |declare ptr @sfl_call(ptr, i64, ptr)
      |declare ptr @sfl_call_code(ptr, ptr, i64, ptr)
      |declare ptr @sfl_tail_set(ptr, i64, ptr)
      |declare ptr @sfl_iter_begin(ptr)
      |declare i32 @sfl_iter_next(ptr, ptr)
      |declare void @sfl_frame_push(ptr)
      |declare void @sfl_frame_pop()
      |declare void @sfl_gc_add_roots(ptr, i64)
      |declare void @sfl_add_source(ptr, ptr)
      |declare void @sfl_unassigned(ptr)
      |declare void @sfl_undefined(ptr)
      |declare void @sfl_set_suggest_names(ptr, i64)
      |declare void @sfl_stack_overflow()
      |declare ptr @llvm.frameaddress.p0(i32)
      |@sfl_stack_limit = external thread_local global ptr
      |declare void @sfl_arity_fail(ptr, i64)
      |declare void @sfl_div_zero()
      |declare void @sfl_print_i64(i64, i32)
      |declare void @sfl_print_f64(double, i32)
      |declare void @sfl_print_bool(i32, i32)
      |declare void @sfl_print_val(ptr, i32)
      |declare void @sfl_print_sep()
      |declare i64 @sfl_time_millis()
      |declare i64 @sfl_round(double)
      |declare i64 @sfl_sign_i64(i64)
      |declare i64 @sfl_sign_f64(double)
      |declare i64 @sfl_ipow(i64, i64)
      |declare i64 @sfl_gcd(i64, i64)
      |declare double @llvm.sqrt.f64(double)
      |declare double @llvm.fabs.f64(double)
      |declare double @llvm.floor.f64(double)
      |declare double @llvm.ceil.f64(double)
      |declare double @llvm.pow.f64(double, double)
      |declare i64 @llvm.abs.i64(i64, i1)
      |declare double @llvm.log.f64(double)
      |declare double @llvm.exp.f64(double)
      |declare double @llvm.sin.f64(double)
      |declare double @llvm.cos.f64(double)
      |""".stripMargin
