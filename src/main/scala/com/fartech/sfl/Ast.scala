package com.fartech.sfl

import scala.collection.mutable

/**
 * Non-local control flow. Rather than throwing (which the old evaluator could not do
 * at all, so `return` only worked at the top level of a body), the interpreter raises
 * a flag that statement sequences check after each step. Costs one predictable branch.
 */
object Sig:
  final val None = 0
  final val Return = 1
  final val Break = 2
  final val Continue = 3

  /**
   * A call in tail position. Instead of recursing, the callee and its already-evaluated
   * arguments are parked on the interpreter and the enclosing `runBody` loop picks them
   * up, so functional code can recurse without bound.
   */
  final val Tail = 4

/**
 * What a debugger needs to see the interpreter run: one callback per statement.
 * The hook also fires once per loop iteration when the body is a single statement,
 * because such a body never re-enters [[Block.eval]] and a breakpoint on it would
 * otherwise be hit once instead of every pass.
 */
trait DebugHook:
  def onStatement(line: Int, col: Int, frame: Frame, rt: Interp): Unit

/**
 * The debugger attachment point. Null in every ordinary run, so the cost of being
 * debuggable is one static-field null test per statement; `sfl dap` installs a hook
 * before the program starts and every interpreter thread sees it.
 */
object Debug:
  @volatile var hook: DebugHook = null

/**
 * Global variables, addressed by a small integer assigned when a name is first seen.
 *
 * Threads share one instance of this, so the value storage is chunked: growing appends
 * a chunk instead of copying into a larger array, which means a write from one thread
 * can never be lost because another thread happened to be declaring a new global.
 * Everything that mutates the name table is serialised; reads and writes of values are
 * lock-free, at the cost of one extra indirection.
 */
final class Globals:
  private final val ChunkBits = 8
  private final val ChunkSize = 1 << ChunkBits
  private final val ChunkMask = ChunkSize - 1

  private val ids = mutable.HashMap.empty[String, Int]
  @volatile private var chunks: Array[Array[Value]] = Array(new Array[Value](ChunkSize))
  private var names = new Array[String](ChunkSize)
  /**
   * What to call a name in a message. Every name but a builtin's is stored under a
   * key that carries the namespace it belongs to, so two namespaces cannot collide —
   * but nobody wants to read that key in an error.
   */
  private var displays = new Array[String](ChunkSize)
  private var consts = new Array[Boolean](ChunkSize)
  @volatile private var n = 0

  // -------------------------------------------------------------------------
  // Namespaces
  //
  // A namespace is a set of global names sharing one key prefix: `tag#name`. The
  // tag is opaque (a canonical directory or file path, or one of the reserved
  // builtin tags) and never reaches the reader; the label is what a person calls
  // it. The registry below outlives every parser, which is what lets the REPL,
  // `eval` and a re-import of an already-parsed module resolve names the same way
  // the first parse did.
  // -------------------------------------------------------------------------

  private val nsLabels = mutable.HashMap.empty[String, String]
  private val nsOpens = mutable.HashMap.empty[String, mutable.LinkedHashSet[String]]
  private val declFiles = mutable.HashMap.empty[String, String]

  /** Records what a namespace is called. The first label for a tag wins. */
  def noteNamespace(tag: String, label: String): Unit = synchronized {
    if tag.nonEmpty && !nsLabels.contains(tag) then nsLabels(tag) = label
  }

  def labelOf(tag: String): String = synchronized(nsLabels.getOrElse(tag, tag))

  /** Records that `tag`'s surface is visible unqualified inside namespace `into`. */
  def noteOpen(into: String, tag: String): Unit = synchronized {
    if into != tag then nsOpens.getOrElseUpdate(into, mutable.LinkedHashSet.empty) += tag
  }

  /** The namespaces opened into `into`, in the order they were opened. */
  def opensOf(into: String): Seq[String] =
    synchronized(nsOpens.get(into).map(_.toSeq).getOrElse(Nil))

  /**
   * Records which file declared a key, and answers with the file that got there
   * first when that was a different one — which is how one namespace's two files
   * declaring the same public name is caught instead of silently overwriting.
   */
  def noteDecl(key: String, file: String): Option[String] = synchronized {
    declFiles.get(key) match
      case Some(first) if first != file => Some(first)
      case Some(_)                      => None
      case None                         => declFiles(key) = file; None
  }

  def size: Int = n
  def nameOf(id: Int): String = names(id)
  def displayOf(id: Int): String = displays(id)
  def isConst(id: Int): Boolean = consts(id)
  def setConst(id: Int, c: Boolean): Unit = consts(id) = c

  /** `null` means "declared by the resolver but never assigned". */
  def rawGet(id: Int): Value = chunks(id >>> ChunkBits)(id & ChunkMask)
  def set(id: Int, v: Value): Unit = chunks(id >>> ChunkBits)(id & ChunkMask) = v
  def isDefined(id: Int): Boolean = rawGet(id) != null
  def find(name: String): Int = synchronized(ids.getOrElse(name, -1))

  def id(name: String): Int = id(name, name)

  def id(name: String, display: String): Int = synchronized {
    val existing = ids.getOrElse(name, -1)
    if existing >= 0 then existing
    else
      if n >>> ChunkBits >= chunks.length then
        // Publish a wider outer array; the existing chunks keep their identity.
        val grown = java.util.Arrays.copyOf(chunks, chunks.length * 2)
        grown(chunks.length) = new Array[Value](ChunkSize)
        chunks = grown
      else if (n & ChunkMask) == 0 && chunks(n >>> ChunkBits) == null then
        chunks(n >>> ChunkBits) = new Array[Value](ChunkSize)
      if n == names.length then
        names = java.util.Arrays.copyOf(names, n * 2)
        displays = java.util.Arrays.copyOf(displays, n * 2)
        consts = java.util.Arrays.copyOf(consts, n * 2)
      names(n) = name
      displays(n) = display
      consts(n) = false
      ids(name) = n
      n += 1
      n - 1
  }

  def define(name: String, v: Value): Int =
    val i = id(name)
    set(i, v)
    i

  /**
   * Names in the root namespace that currently hold a value, for completion and
   * `:vars`. A namespaced name is left out — it is not reachable by this spelling
   * from wherever the caller is, so offering it would only mislead; ask
   * [[membersOf]] for one namespace's own.
   */
  def definedNames: Seq[String] =
    val upto = n
    (0 until upto).filter(i => rawGet(i) != null && names(i).indexOf('#') < 0)
      .map(i => names(i)).toSeq

  /** Public names a namespace declares, for completion and "did you mean". */
  def membersOf(tag: String): Seq[String] =
    val prefix = tag + "#"
    val upto = n
    (0 until upto).map(names).filter(k => k.startsWith(prefix))
      .map(_.substring(prefix.length)).filterNot(_.startsWith("_")).toSeq

  /** The tag a key belongs to, or "" for a name in the root (builtin) namespace. */
  def tagOf(key: String): String =
    val i = key.lastIndexOf('#')
    if i < 0 then "" else key.substring(0, i)

  /**
   * What a misspelling of `id` could have meant: the names its own namespace holds,
   * the names opened into it, and the root namespace behind both. Scoping the
   * suggestion this way is what keeps "did you mean" honest — it never proposes a
   * name the failing code could not have reached.
   */
  def visibleFrom(id: Int): Seq[String] =
    val tag = if id >= 0 && id < n then tagOf(names(id)) else ""
    val reachable = (opensOf(tag) :+ tag).toSet
    val out = mutable.LinkedHashSet.empty[String]
    val upto = n
    var i = 0
    // Id order, which is the order the compiled runtime walks its own table in:
    // builtins first, then everything the program declared as it was parsed. The
    // two engines have to reach the same suggestion, and ties go to whoever comes
    // first, so the order is part of the contract.
    while i < upto do
      val key = names(i)
      val hash = key.lastIndexOf('#')
      val bare = if hash < 0 then key else key.substring(hash + 1)
      if !bare.startsWith("_") && rawGet(i) != null &&
         (hash < 0 || reachable.contains(key.substring(0, hash))) then
        out += bare
      i += 1
    out.toSeq

  def valueOf(name: String): Option[Value] =
    val i = find(name)
    if i >= 0 && rawGet(i) != null then Some(rawGet(i)) else None

/** Interpreter state shared by every node during a run. */
final class Interp(val globals: Globals):
  var signal: Int = Sig.None
  var retVal: Value = VNull

  /** Set together with `Sig.Tail`; consumed by the trampoline in `Call.runBody`. */
  var tailFn: VFun = null
  var tailArgs: Array[Value] = null

  /** Updated once per statement so errors can name a position without try/catch churn. */
  var line: Int = 0
  var col: Int = 0
  var src: SourceRef = SourceRef.unknown

  /**
   * Depth limit for calls that are *not* in tail position; tail calls reuse their
   * activation and are unbounded. Each nested activation costs a few hundred bytes of
   * native stack, and embedders may run on a thread with a 1 MB stack rather than the
   * main thread's 8 MB, so the default is chosen to be safe there. Raise it with
   * SFL_MAX_DEPTH when a deeply recursive algorithm genuinely needs more.
   */
  var maxDepth: Int =
    sys.env.get("SFL_MAX_DEPTH").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(1200)

  private var depth = 0
  // Traceback storage is per interpreter, and there is one interpreter per thread, so
  // this is kept small; deeper frames still count towards the limit but are not shown.
  private val frameFn = new Array[String](512)
  private val frameLine = new Array[Int](512)
  private val frameSrc = new Array[SourceRef](512)
  // True for a frame whose function was defined in hidden (stdlib) source; such
  // frames stay out of tracebacks, as compiled library frames do.
  private val frameHidden = new Array[Boolean](512)
  // Filled only while a debugger is attached: the live frame and proto per depth,
  // which is what turns a traceback into an inspectable stack.
  private val frameFrames = new Array[Frame](512)
  private val frameProtos = new Array[FnProto](512)

  /** Records the frame a call is about to run in; written only when Debug.hook is set. */
  def noteFrame(fr: Frame, proto: FnProto): Unit =
    val d = depth - 1
    if d >= 0 && d < frameFrames.length then
      frameFrames(d) = fr
      frameProtos(d) = proto

  /** The noted frame at `d` (0 = outermost), or null past the recording window. */
  def debugFrame(d: Int): Frame =
    if d >= 0 && d < frameFrames.length then frameFrames(d) else null
  def debugProto(d: Int): FnProto =
    if d >= 0 && d < frameProtos.length then frameProtos(d) else null

  def callDepth: Int = depth

  def push(fn: String, callLine: Int, s: SourceRef, hidden: Boolean = false): Unit =
    if depth >= maxDepth then
      Err.eval(s"call stack overflow (depth $maxDepth); check for unbounded recursion")
    if depth < frameFn.length then
      frameFn(depth) = fn
      frameLine(depth) = callLine
      frameSrc(depth) = s
      frameHidden(depth) = hidden
    depth += 1

  def pop(): Unit = depth -= 1

  /**
   * Renames the innermost frame. A tail call reuses its caller's activation, so without
   * this the traceback would keep naming the function that has already been replaced.
   */
  def retarget(fn: String, hidden: Boolean = false): Unit =
    if depth > 0 && depth <= frameFn.length then
      frameFn(depth - 1) = fn
      frameHidden(depth - 1) = hidden

  def resetStack(): Unit =
    depth = 0
    signal = Sig.None
    retVal = VNull
    tailFn = null
    tailArgs = null

  def trace: List[StackEntry] =
    val upto = math.min(depth, frameFn.length)
    (upto - 1 to 0 by -1).filterNot(frameHidden(_))
      .map(i => StackEntry(frameFn(i), frameLine(i), frameSrc(i))).toList

  /** Attaches position and traceback to an error that bubbled up without them. */
  def decorate(e: EvalError): EvalError =
    val filled =
      if e.pos.line == 0 then new EvalError(e.getMessage, Pos(line, col, -1), src, trace)
      else if e.trace.isEmpty then new EvalError(e.getMessage, e.pos, e.source, trace)
      else e
    if filled ne e then
      filled.notes = e.notes
      filled.span = e.span
    filled

/** Compiled shape of a function: everything fixed at parse time. */
final class FnProto(
    val name: String,
    val params: Array[String],
    val defaults: Array[Node],
    val body: Node,
    val line: Int,
    val src: SourceRef,
    /** When true the final parameter collects any surplus arguments into an array. */
    val hasRest: Boolean = false
):
  /**
   * Identifies this definition uniquely. Two lambdas can share a name and a line —
   * `try(() -> f(), (e) -> g(e))` has two called "lambda" — so anything keyed by a
   * function needs something better than either.
   */
  val uid: Int = FnProto.nextUid()

  /** Filled in once the body has been parsed and all locals allocated. */
  var nSlots: Int = params.length

  /**
   * Slot index -> source name, in allocation order, hidden temporaries included.
   * The debugger reads it to label frame slots; nothing else looks at it. Null for
   * protos built outside the parser (thunks synthesised by eval and the REPL).
   */
  var debugNames: Array[String] = null

  /** Number of parameters that are filled positionally, excluding any rest parameter. */
  val fixed: Int = if hasRest then params.length - 1 else params.length

  val required: Int =
    var r = fixed
    var i = fixed - 1
    while i >= 0 && defaults(i) != null do { r = i; i -= 1 }
    r

  def signature: String =
    val shown = params.zipWithIndex.map { (p, i) =>
      if hasRest && i == fixed then s"...$p"
      else if defaults(i) != null then s"$p?"
      else p
    }
    s"$name(${shown.mkString(", ")})"

  def arityMessage(got: Int): String =
    val expect =
      if hasRest then s"at least $required"
      else if required == params.length then s"${params.length}"
      else s"$required to ${params.length}"
    s"$name expects $expect argument(s), got $got"

object FnProto:
  private var counter = 0
  def nextUid(): Int = synchronized { counter += 1; counter }

/** Base class of every executable node. Evaluation is a virtual call, not a big match. */
sealed abstract class Node:
  def line: Int

  /**
   * Column the node starts at, or 0 when unknown. The parser fills this in for
   * statements and calls, which is what lets a runtime error underline the construct
   * that raised rather than the whole line.
   */
  var col: Int = 0

  def eval(f: Frame, rt: Interp): Value

  /**
   * Pins an error that was raised without a position onto this node. Wrapping a call in
   * try/catch costs nothing while no exception is thrown, so the precise location comes
   * for free on the happy path.
   */
  protected final def locate(e: EvalError, rt: Interp): EvalError =
    // Hidden (standard-library) source never pins: the error bubbles until a node
    // in user source catches it, which is where compiled programs locate it too.
    if e.pos.line != 0 || col <= 0 || rt.src.hidden then e
    else
      val pinned = new EvalError(e.getMessage, Pos(line, col, -1), rt.src, e.trace)
      pinned.notes = e.notes
      pinned.span = e.span
      pinned

// ---------------------------------------------------------------------------
// Literals and references
// ---------------------------------------------------------------------------

final class Lit(val v: Value, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value = v

final class LocalGet(val slot: Int, val name: String, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val v = f.slots(slot)
    if v == null then
      Err.evalHint(
        s"variable '$name' is read before it is assigned",
        "give it a value on every path that reaches this point"
      )
    else v

final class OuterGet(val depth: Int, val slot: Int, val name: String, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val v = f.up(depth).slots(slot)
    if v == null then Err.eval(s"variable '$name' used before it is assigned") else v

final class GlobalGet(val id: Int, val name: String, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val v = rt.globals.rawGet(id)
    if v != null then
      // A shared-source stdlib function loads on first touch, so no sentinel is
      // ever observable: whoever reads the name sees the loaded definition.
      if v.isInstanceOf[VLazyStdlib] then StdlibModules.force(v.asInstanceOf[VLazyStdlib], id, rt)
      else v
    else
      Err.evalHint(
        s"undefined variable '$name'",
        Err.didYouMean(name, rt.globals.visibleFrom(id)),
        if name.startsWith("_") then
          "a name beginning with '_' is private to the file that declares it"
        else
          "names must be assigned before they are read; a function must be defined above the call that runs first"
      )

final class LocalSet(val slot: Int, val value: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val v = value.eval(f, rt)
    if rt.signal != Sig.None then VNull
    else
      f.slots(slot) = v
      v

final class OuterSet(val depth: Int, val slot: Int, val value: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val v = value.eval(f, rt)
    if rt.signal != Sig.None then VNull
    else
      f.up(depth).slots(slot) = v
      v

final class GlobalSet(val id: Int, val value: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val v = value.eval(f, rt)
    if rt.signal != Sig.None then VNull
    else
      rt.globals.set(id, v)
      v

// ---------------------------------------------------------------------------
// Operators
// ---------------------------------------------------------------------------

object BinOp:
  final val Add = 0
  final val Sub = 1
  final val Mul = 2
  final val Div = 3
  final val Mod = 4

  def symbol(op: Int): String = op match
    case Add => "+"
    case Sub => "-"
    case Mul => "*"
    case Div => "/"
    case _   => "%"

final class Arith(val op: Int, val l: Node, val r: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val a = l.eval(f, rt)
    val b = r.eval(f, rt)
    Arith.apply(op, a, b)

object Arith:
  def apply(op: Int, a: Value, b: Value): Value =
    // Int/Int is the overwhelmingly common case, so test it before anything else.
    a match
      case VInt(x) =>
        b match
          case VInt(y) =>
            op match
              case BinOp.Add => VInt.of(x + y)
              case BinOp.Sub => VInt.of(x - y)
              case BinOp.Mul => VInt.of(x * y)
              case BinOp.Div =>
                if y == 0 then Err.eval("division by zero") else VInt.of(x / y)
              case _ =>
                if y == 0 then Err.eval("modulo by zero") else VInt.of(x % y)
          case VFloat(y) => floatOp(op, x.toDouble, y)
          // Straight to the text: `display` would build the same characters
          // through a StringBuilder and copy them once more.
          case VStr(y)   => if op == BinOp.Add then VStr(x.toString + y) else typeErr(op, a, b)
          case _         => typeErr(op, a, b)
      case VFloat(x) =>
        b match
          case VFloat(y) => floatOp(op, x, y)
          case VInt(y)   => floatOp(op, x, y.toDouble)
          case VStr(y)   => if op == BinOp.Add then VStr(Values.formatDouble(x) + y) else typeErr(op, a, b)
          case _         => typeErr(op, a, b)
      case VStr(x) =>
        if op == BinOp.Add then
          b match
            case VStr(y)   => VStr(x + y)
            case VInt(y)   => VStr(x + y)
            case VFloat(y) => VStr(x + Values.formatDouble(y))
            case _         => VStr(x + b.display)
        else if op == BinOp.Mul then
          b match
            case VInt(k) =>
              if k < 0 then Err.eval("cannot repeat a string a negative number of times")
              else if k * x.length > 64 * 1024 * 1024 then Err.eval("string repetition result is too large")
              else VStr(x * k.toInt)
            case _ => typeErr(op, a, b)
        else typeErr(op, a, b)
      case arr: VArr if op == BinOp.Add =>
        b match
          case other: VArr => VArr.of(arr.items ++ other.items)
          case _           => typeErr(op, a, b)
      case obj: VObj if op == BinOp.Add =>
        b match
          case other: VObj =>
            val m = mutable.LinkedHashMap.from(obj.fields)
            for (k, v) <- other.fields do m(k) = v
            new VObj(m)
          case _ => typeErr(op, a, b)
      case _ =>
        // Concatenating anything with a string stays permissive, as it did before.
        if op == BinOp.Add && b.isInstanceOf[VStr] then VStr(a.display + b.asInstanceOf[VStr].v)
        else typeErr(op, a, b)

  private def floatOp(op: Int, x: Double, y: Double): Value = op match
    case BinOp.Add => VFloat(x + y)
    case BinOp.Sub => VFloat(x - y)
    case BinOp.Mul => VFloat(x * y)
    case BinOp.Div => VFloat(x / y)
    case _         => VFloat(x % y)

  private def typeErr(op: Int, a: Value, b: Value): Nothing =
    val sym = BinOp.symbol(op)
    val hint =
      if Values.isNum(a) || Values.isNum(b) then
        "convert the other side with toInt(v), toFloat(v) or toString(v)"
      else "use toString(v) to build text, or toInt(v) / toFloat(v) to do arithmetic"
    Err.evalHint(
      s"operator '$sym' is not defined for ${a.typeName} and ${b.typeName}" +
        s" (${Err.show(a)} $sym ${Err.show(b)})",
      hint
    )

final class Neg(val e: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value = e.eval(f, rt) match
    case VInt(x)   => VInt.of(-x)
    case VFloat(x) => VFloat(-x)
    case v         => Err.eval(s"cannot negate ${v.typeName}")

final class Not(val e: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value = VBool.of(!e.eval(f, rt).truthy)

object CmpOp:
  final val Eq = 0
  final val Ne = 1
  final val Lt = 2
  final val Le = 3
  final val Gt = 4
  final val Ge = 5

final class Cmp(val op: Int, val l: Node, val r: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val a = l.eval(f, rt)
    val b = r.eval(f, rt)
    op match
      case CmpOp.Eq => VBool.of(Values.equal(a, b))
      case CmpOp.Ne => VBool.of(!Values.equal(a, b))
      case _ =>
        // Fast path for the loop-counter comparison that dominates real scripts.
        a match
          case VInt(x) if b.isInstanceOf[VInt] =>
            val y = b.asInstanceOf[VInt].v
            op match
              case CmpOp.Lt => VBool.of(x < y)
              case CmpOp.Le => VBool.of(x <= y)
              case CmpOp.Gt => VBool.of(x > y)
              case _        => VBool.of(x >= y)
          case _ =>
            val c = Values.compare(a, b)
            op match
              case CmpOp.Lt => VBool.of(c < 0)
              case CmpOp.Le => VBool.of(c <= 0)
              case CmpOp.Gt => VBool.of(c > 0)
              case _        => VBool.of(c >= 0)

/** `&&` with short-circuit evaluation, which the previous implementation lacked. */
final class And(val l: Node, val r: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val a = l.eval(f, rt)
    if !a.truthy then VBool.False else VBool.of(r.eval(f, rt).truthy)

final class Or(val l: Node, val r: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val a = l.eval(f, rt)
    if a.truthy then VBool.True else VBool.of(r.eval(f, rt).truthy)

// ---------------------------------------------------------------------------
// Containers
// ---------------------------------------------------------------------------

final class ArrLit(val items: Array[Node], val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val buf = new mutable.ArrayBuffer[Value](items.length)
    var i = 0
    while i < items.length do
      buf += items(i).eval(f, rt)
      i += 1
    new VArr(buf)

final class TupleLit(val items: Array[Node], val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val out = new Array[Value](items.length)
    var i = 0
    while i < items.length do
      out(i) = items(i).eval(f, rt)
      i += 1
    if rt.signal != Sig.None then VNull else new VTuple(out)

final class ObjLit(val keys: Array[String], val values: Array[Node], val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val m = mutable.LinkedHashMap.empty[String, Value]
    var i = 0
    while i < keys.length do
      m(keys(i)) = values(i).eval(f, rt)
      i += 1
    new VObj(m)

final class Index(val recv: Node, val key: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val target = recv.eval(f, rt)
    val k = key.eval(f, rt)
    try Index.get(target, k)
    catch case e: EvalError => throw locate(e, rt)

object Index:
  def get(target: Value, k: Value): Value = target match
    case a: VArr =>
      val i = normalize(Values.toIndex(k, "array index"), a.items.length)
      if i < 0 || i >= a.items.length then outOfBounds("array", k, a.items.length)
      a.items(i)
    case o: VObj =>
      k match
        case VStr(s) =>
          o.fields.getOrElse(s, missingKey(o, s))
        case _ =>
          Err.evalHint(
            s"object keys must be strings, got ${k.typeName}",
            s"the key was ${Err.show(k)}; use an array if you want numeric positions"
          )
    case t: VTuple =>
      val i = normalize(Values.toIndex(k, "tuple index"), t.items.length)
      if i < 0 || i >= t.items.length then outOfBounds("tuple", k, t.items.length)
      t.items(i)
    case VStr(s) =>
      val i = normalize(Values.toIndex(k, "string index"), s.length)
      if i < 0 || i >= s.length then outOfBounds("string", k, s.length)
      VStr.ofChar(s.charAt(i))
    case VNull =>
      Err.evalHint(
        "cannot index into null",
        "check it with isNull(v), or read it with get(v, key, default)"
      )
    case other => Err.eval(s"cannot index into ${other.typeName}")

  def set(target: Value, k: Value, v: Value): Value = target match
    case a: VArr =>
      val i = normalize(Values.toIndex(k, "array index"), a.items.length)
      if i < 0 || i >= a.items.length then outOfBounds("array", k, a.items.length)
      a.items(i) = v
      v
    case o: VObj =>
      k match
        case VStr(s) => o.fields(s) = v; v
        case _       => Err.eval(s"object keys must be strings, got ${k.typeName}")
    case _: VTuple =>
      Err.evalHint(
        "cannot assign into a tuple, it is immutable",
        "use toArray(t) for a mutable copy, or build a new tuple"
      )
    case VNull => Err.eval("cannot assign into null")
    case other => Err.eval(s"cannot assign into ${other.typeName}")

  private def missingKey(o: VObj, key: String): Nothing =
    val keys = o.fields.keys.toSeq
    val listed =
      if keys.isEmpty then "the object is empty"
      else if keys.length <= 8 then s"it has: ${keys.mkString(", ")}"
      else s"it has ${keys.length} keys, starting with: ${keys.take(8).mkString(", ")}"
    Err.evalHint(
      s"object has no key '$key'",
      Err.didYouMean(key, keys),
      listed,
      "use get(o, key, default) to read a key that may be missing"
    )

  private def outOfBounds(what: String, key: Value, len: Int): Nothing =
    val hint =
      if len == 0 then s"the $what is empty"
      else s"valid indices are 0 to ${len - 1}, or -1 to -$len counting from the end"
    Err.evalHint(s"$what index ${key.display} is out of bounds (length $len)", hint)

  /** Negative indices count back from the end, so `a[-1]` is the last element. */
  inline def normalize(i: Int, len: Int): Int = if i < 0 then len + i else i

final class IndexSet(val recv: Node, val key: Node, val value: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val t = recv.eval(f, rt)
    val k = key.eval(f, rt)
    val v = value.eval(f, rt)
    if rt.signal != Sig.None then VNull else Index.set(t, k, v)

/**
 * `a[i] += x`. Evaluating the receiver and key once matters when they have side
 * effects, so this is a node of its own rather than a desugaring to read-then-write.
 */
final class IndexCompound(val recv: Node, val key: Node, val op: Int, val value: Node, val line: Int)
    extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val t = recv.eval(f, rt)
    val k = key.eval(f, rt)
    val cur = Index.get(t, k)
    val v = value.eval(f, rt)
    if rt.signal != Sig.None then VNull else Index.set(t, k, Arith.apply(op, cur, v))

// ---------------------------------------------------------------------------
// Functions
// ---------------------------------------------------------------------------

final class MakeFun(val proto: FnProto, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value = new VFun(proto, f)

/**
 * A call. `tail` is set by the parser for calls in tail position; such a call parks its
 * target on the interpreter rather than recursing, which is what makes unbounded
 * functional recursion possible.
 */
final class Call(val callee: Node, val args: Array[Node], val line: Int) extends Node:
  var tail: Boolean = false

  def eval(f: Frame, rt: Interp): Value =
    try evalCall(f, rt)
    catch case e: EvalError => throw locate(e, rt)

  private def evalCall(f: Frame, rt: Interp): Value =
    val target = callee.eval(f, rt)
    target match
      case fn: VFun =>
        if tail then
          val argv = Call.evalArgs(args, f, rt)
          if rt.signal != Sig.None then VNull
          else
            rt.tailFn = fn
            rt.tailArgs = argv
            rt.signal = Sig.Tail
            VNull
        else Call.invokeUser(fn, args, f, rt, line)
      case nat: VNative => Call.invokeNative(nat, args, f, rt)
      case other =>
        val what = callee match
          case g: GlobalGet => s"'${g.name}'"
          case l: LocalGet  => s"'${l.name}'"
          case o: OuterGet  => s"'${o.name}'"
          case _            => "this expression"
        val extra = other match
          case _: VArr => "to read an element write a[i], not a(i)"
          case _: VObj => "to read a field write o.key or o[key], not o(key)"
          case _       => ""
        Err.evalHint(
          s"$what is not a function, it is ${other.typeName} ${Err.show(other)}",
          extra
        )

object Call:
  private def evalArgs(args: Array[Node], f: Frame, rt: Interp): Array[Value] =
    val argv = new Array[Value](args.length)
    var i = 0
    while i < args.length do
      argv(i) = args(i).eval(f, rt)
      i += 1
      if rt.signal != Sig.None then i = args.length
    argv

  /**
   * Binds `argv` into a fresh slot array: fixed parameters positionally, defaults for
   * the ones left out, and any surplus collected into the rest parameter.
   */
  private def bind(proto: FnProto, argv: Array[Value], env: Frame, rt: Interp): Array[Value] =
    if argv.length < proto.required || (!proto.hasRest && argv.length > proto.params.length) then
      Err.evalHint(proto.arityMessage(argv.length), s"it is declared as ${proto.signature}")

    val slots = new Array[Value](proto.nSlots)
    val positional = math.min(argv.length, proto.fixed)
    System.arraycopy(argv, 0, slots, 0, positional)

    var i = positional
    // Defaults were resolved in the defining scope, so they evaluate against `env`.
    while i < proto.fixed do
      slots(i) = proto.defaults(i).eval(env, rt)
      i += 1

    if proto.hasRest then
      val extra = math.max(0, argv.length - proto.fixed)
      val buf = new mutable.ArrayBuffer[Value](math.max(extra, 1))
      var j = proto.fixed
      while j < argv.length do
        buf += argv(j)
        j += 1
      slots(proto.fixed) = new VArr(buf)
    slots

  def invokeUser(fn: VFun, args: Array[Node], f: Frame, rt: Interp, callLine: Int): Value =
    val proto = fn.proto
    // The common shape — no rest parameter, arity already satisfied — writes argument
    // values straight into the callee's slots, with no intermediate array.
    if !proto.hasRest && args.length <= proto.params.length && args.length >= proto.required then
      val slots = new Array[Value](proto.nSlots)
      var i = 0
      while i < args.length do
        slots(i) = args(i).eval(f, rt)
        i += 1
      if rt.signal != Sig.None then return VNull
      while i < proto.fixed do
        slots(i) = proto.defaults(i).eval(fn.env, rt)
        i += 1
      runBody(fn, slots, rt, callLine)
    else
      val argv = evalArgs(args, f, rt)
      if rt.signal != Sig.None then return VNull
      runBody(fn, bind(proto, argv, fn.env, rt), rt, callLine)

  /** Entry point used by builtins such as `map` that call back into SFL code. */
  def invokeValue(callee: Value, argv: Array[Value], rt: Interp): Value = callee match
    case nat: VNative =>
      checkNativeArity(nat, argv.length)
      runNative(nat, argv)
    case fn: VFun =>
      runBody(fn, bind(fn.proto, argv, fn.env, rt), rt, rt.line)
    case other => Err.eval(s"expected a function, got ${other.typeName}")

  /**
   * Runs a function body, looping rather than recursing when the body ends in a tail
   * call. One activation of `runBody` therefore serves an entire chain of tail calls,
   * so mutual and self recursion in tail position use constant stack.
   */
  private def runBody(fn0: VFun, slots0: Array[Value], rt: Interp, callLine: Int): Value =
    val savedSrc = rt.src
    val savedLine = rt.line
    val savedCol = rt.col
    var fn = fn0
    var slots = slots0
    // A callback invoked from a builtin — or the first call on a fresh thread — has no
    // caller position to report, so fall back to where the function was written.
    val site = if callLine > 0 then callLine else fn.proto.line
    val siteSrc = if savedSrc eq SourceRef.unknown then fn.proto.src else savedSrc
    rt.push(fn.proto.name, site, siteSrc, fn.proto.src.hidden)
    try
      var result: Value = VNull
      var running = true
      while running do
        val proto = fn.proto
        rt.src = proto.src
        val frame = new Frame(slots, fn.env)
        if Debug.hook != null then rt.noteFrame(frame, proto)
        val r = proto.body.eval(frame, rt)
        if rt.signal == Sig.Tail then
          val next = rt.tailFn
          val argv = rt.tailArgs
          rt.signal = Sig.None
          rt.tailFn = null
          rt.tailArgs = null
          // No depth check here: a tail call uses no extra stack, so an endless one is
          // an infinite loop like `while (true)` and is left to run.
          slots = bind(next.proto, argv, next.env, rt)
          fn = next
          rt.retarget(fn.proto.name, fn.proto.src.hidden)
        else if rt.signal == Sig.Return then
          rt.signal = Sig.None
          result = rt.retVal
          rt.retVal = VNull
          running = false
        else
          // `break`/`continue` must not escape a function body.
          if rt.signal != Sig.None then rt.signal = Sig.None
          result = r
          running = false
      result
    catch
      case e: EvalError if e.trace.isEmpty =>
        // Capture the call stack here, while it is still intact: the `finally` below
        // pops this frame, and by the time the driver catches the error there is
        // nothing left to report.
        throw withTrace(e, rt)
    finally
      rt.pop()
      rt.src = savedSrc
      rt.line = savedLine
      // The column comes back with the line: leaving the callee's last statement
      // column behind would pin a later unpositioned error to a nonsense spot.
      rt.col = savedCol

  private def withTrace(e: EvalError, rt: Interp): EvalError =
    val out = new EvalError(e.getMessage, e.pos, e.source, rt.trace)
    out.notes = e.notes
    out.span = e.span
    out

  def checkNativeArity(nat: VNative, count: Int): Unit =
    val okMin = count >= nat.minArity
    val okMax = nat.maxArity < 0 || count <= nat.maxArity
    if !okMin || !okMax then
      val expect =
        if nat.maxArity < 0 then s"at least ${nat.minArity}"
        else if nat.minArity == nat.maxArity then s"${nat.minArity}"
        else s"${nat.minArity} to ${nat.maxArity}"
      Err.evalHint(
        s"${nat.name} expects $expect argument(s), got $count",
        s"the signature is ${nat.signature}",
        nat.doc
      )

  def invokeNative(nat: VNative, args: Array[Node], f: Frame, rt: Interp): Value =
    checkNativeArity(nat, args.length)
    val argv = new Array[Value](args.length)
    var i = 0
    while i < args.length do
      argv(i) = args(i).eval(f, rt)
      i += 1
    if rt.signal != Sig.None then VNull else runNative(nat, argv)

  /**
   * Runs a builtin and makes sure its complaint carries its signature. Builtins raise
   * messages of the form "name: what went wrong"; attaching the signature here covers
   * every one of them without each having to repeat it.
   */
  def runNative(nat: VNative, argv: Array[Value]): Value =
    try nat.impl(argv)
    catch
      case e: EvalError if e.notes.isEmpty && e.getMessage.startsWith(nat.name + ": ") =>
        throw e.note(s"the signature is ${nat.signature}")

// ---------------------------------------------------------------------------
// Statements and control flow
// ---------------------------------------------------------------------------

/**
 * A statement sequence. Its value is that of the last statement, matching the original
 * language. Execution stops as soon as a return/break/continue signal is raised.
 *
 * Not final so that [[ModuleBlock]] can tag an inlined import while keeping this
 * exact evaluation: the interpreter never distinguishes the two.
 */
class Block(val stmts: Array[Node], val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    var result: Value = VNull
    var i = 0
    val n = stmts.length
    while i < n do
      val st = stmts(i)
      rt.line = st.line
      rt.col = st.col
      if Debug.hook != null then Debug.hook.onStatement(st.line, st.col, f, rt)
      result = st.eval(f, rt)
      if rt.signal != Sig.None then return result
      i += 1
    result

/**
 * The body of an imported module, tagged with where it came from.
 *
 * The parser inlines every import as a [[Block]] of the module's statements, and the
 * interpreter runs it as one — a ModuleBlock evaluates identically, so nothing about
 * running a program changes. Its only reader is the ahead-of-time compiler, which uses
 * the tag to decide whether a package module can be linked from a prebuilt archive
 * instead of being compiled again from source. When it cannot (or for a plain file
 * import, where `pkg` is null), the compiler treats it as the ordinary Block it is and
 * inlines the source exactly as before.
 *
 * `sourcePath` is the canonical path the module was read from — the same name its
 * functions' protos carry — and `sourceText` is that file's contents, so a compiled
 * program that links the archive can still register the source and quote it in errors.
 */
final class ModuleBlock(
    stmts: Array[Node],
    line: Int,
    val pkg: String,
    val moduleRel: String,
    val versionDir: String,
    val sourcePath: String,
    val sourceText: String
) extends Block(stmts, line)

final class If(val conds: Array[Node], val bodies: Array[Node], val orElse: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    var i = 0
    // Branches are an ordered array; the previous Map-based representation could
    // reorder or silently drop branches with equal keys.
    while i < conds.length do
      if conds(i).eval(f, rt).truthy then return bodies(i).eval(f, rt)
      if rt.signal != Sig.None then return VNull
      i += 1
    if orElse != null then orElse.eval(f, rt) else VNull

/**
 * `while`. When the body creates a function, `frameSlots` is non-negative and each
 * iteration runs in its own frame, so a closure made on one pass does not observe the
 * next pass's variables. Loops that create no closures keep the flat, allocation-free
 * path.
 */
final class While(val cond: Node, val body: Node, val line: Int, val frameSlots: Int = -1)
    extends Node:
  // A one-statement body never re-enters Block.eval, so the debug hook fires here
  // once per iteration instead; a Block body reports its own statements.
  private val hookBody = !body.isInstanceOf[Block]

  def eval(f: Frame, rt: Interp): Value =
    var result: Value = VNull
    var go = true
    while go do
      if !cond.eval(f, rt).truthy then go = false
      else
        val scope = if frameSlots >= 0 then new Frame(new Array[Value](frameSlots), f) else f
        if hookBody && Debug.hook != null then Debug.hook.onStatement(body.line, body.col, scope, rt)
        result = body.eval(scope, rt)
        if rt.signal != Sig.None then
          if rt.signal == Sig.Break then { rt.signal = Sig.None; go = false }
          else if rt.signal == Sig.Continue then rt.signal = Sig.None
          else go = false // return or tail call: let it propagate
    result

/**
 * `for (x in coll)`. The loop variable lives in a frame slot inside a function and in
 * a global at the top level, so exactly one of `slot` / `globalId` is non-negative.
 *
 * When the body creates a function, `frameSlots` is non-negative: each iteration gets
 * a fresh frame holding the loop variable and the body's locals, which gives closures
 * per-iteration capture instead of a shared binding.
 */
final class ForIn(
    val slot: Int,
    val globalId: Int,
    val iterable: Node,
    val body: Node,
    val line: Int,
    val frameSlots: Int = -1
) extends Node:
  // See While: single-statement bodies get their per-iteration debug hook here.
  private val hookBody = !body.isInstanceOf[Block]

  def eval(f: Frame, rt: Interp): Value =
    val src = iterable.eval(f, rt)
    val it = ForIn.iterator(src)
    var result: Value = VNull
    var go = true
    while go && it.hasNext do
      val v = it.next()
      val scope =
        if frameSlots >= 0 then
          val child = new Frame(new Array[Value](frameSlots), f)
          child.slots(slot) = v
          child
        else
          if globalId >= 0 then rt.globals.set(globalId, v) else f.slots(slot) = v
          f
      if hookBody && Debug.hook != null then Debug.hook.onStatement(body.line, body.col, scope, rt)
      result = body.eval(scope, rt)
      if rt.signal != Sig.None then
        if rt.signal == Sig.Break then { rt.signal = Sig.None; go = false }
        else if rt.signal == Sig.Continue then rt.signal = Sig.None
        else go = false
    result

object ForIn:
  def iterator(v: Value): Iterator[Value] = v match
    case a: VArr => a.items.iterator
    case o: VObj => o.fields.keysIterator.map(VStr(_))
    case VStr(s) => s.iterator.map(VStr.ofChar)
    case t: VTuple => t.items.iterator
    case i: VIter => i.it
    case other   => Err.eval(s"cannot iterate over ${other.typeName}")

final class Select(
    val subject: Node,
    val caseVals: Array[Node],
    val caseBodies: Array[Node],
    val orElse: Node,
    val line: Int
) extends Node:
  // A select whose cases are all string literals — the tag-dispatch idiom — is
  // resolved by hash instead of a comparison chain. Semantics are untouched: a
  // non-string subject can equal no string case, so it goes straight to the
  // default, exactly as the chain would have decided. First case wins on a
  // duplicate, as in the chain. All-int-literal selects get the same treatment,
  // with a float subject bridged the way Values.equal bridges it.
  private val strCases: java.util.HashMap[String, Node] =
    if caseVals.length >= 4 && caseVals.forall {
      case l: Lit => l.v.isInstanceOf[VStr]
      case _      => false
    } then
      val m = new java.util.HashMap[String, Node](caseVals.length * 2)
      var i = 0
      while i < caseVals.length do
        m.putIfAbsent(caseVals(i).asInstanceOf[Lit].v.asInstanceOf[VStr].v, caseBodies(i))
        i += 1
      m
    else null

  private val intCases: mutable.LongMap[Node] =
    if strCases == null && caseVals.length >= 4 && caseVals.forall {
      case l: Lit => l.v.isInstanceOf[VInt]
      case _      => false
    } then
      val m = new mutable.LongMap[Node](caseVals.length * 2)
      var i = 0
      while i < caseVals.length do
        val k = caseVals(i).asInstanceOf[Lit].v.asInstanceOf[VInt].v
        if !m.contains(k) then m(k) = caseBodies(i)
        i += 1
      m
    else null

  def eval(f: Frame, rt: Interp): Value =
    val v = subject.eval(f, rt)
    if strCases != null then
      v match
        case VStr(s) =>
          val body = strCases.get(s)
          if body != null then return body.eval(f, rt)
        case _ => ()
      if orElse != null then orElse.eval(f, rt) else VNull
    else if intCases != null then
      var body: Node = null
      v match
        case VInt(x) => body = intCases.getOrNull(x)
        case VFloat(x) =>
          // An integral float equals the int with the same value; the round-trip
          // test rejects anything a long cannot hold exactly.
          val l = x.toLong
          if l.toDouble == x then body = intCases.getOrNull(l)
        case _ => ()
      if body != null then body.eval(f, rt)
      else if orElse != null then orElse.eval(f, rt)
      else VNull
    else
      var i = 0
      while i < caseVals.length do
        if Values.equal(caseVals(i).eval(f, rt), v) then return caseBodies(i).eval(f, rt)
        i += 1
      if orElse != null then orElse.eval(f, rt) else VNull

final class Return(val e: Node, val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    val v = if e == null then VNull else e.eval(f, rt)
    if rt.signal == Sig.None then
      rt.retVal = v
      rt.signal = Sig.Return
    v

final class Break(val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    rt.signal = Sig.Break
    VNull

final class Continue(val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value =
    rt.signal = Sig.Continue
    VNull

/** Placeholder for statements that produce nothing, such as a stray `;`. */
final class Nop(val line: Int) extends Node:
  def eval(f: Frame, rt: Interp): Value = VNull
