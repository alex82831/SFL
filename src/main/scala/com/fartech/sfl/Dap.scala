package com.fartech.sfl

import java.io.{BufferedInputStream, File, OutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe.Zone
import scala.scalanative.unsigned.*

/**
 * `sfl dap` — a Debug Adapter Protocol server for the interpreter.
 *
 * Same framing and JSON as [[Lsp]], same single-binary philosophy. The debuggee
 * runs in this process on its own daemon thread; [[Debug.hook]] fires once per
 * statement (and per single-statement loop iteration), which is where breakpoints
 * and stepping live. A paused thread parks inside the hook; the protocol thread
 * never blocks, so the editor stays responsive while the program is stopped.
 *
 * stdout discipline: the protocol owns fd 1, so the first thing the server does
 * is dup it away and point fd 1 at stderr. The debuggee's println goes through
 * Console redirection into `output` events; a subprocess it spawns with
 * passthrough() writes to what is now stderr — visible, and harmless to the
 * protocol stream.
 *
 * Scope of this first version, deliberately: breakpoints, continue/next/step
 * in/out, pause, call stacks for every thread, locals (named through
 * FnProto.debugNames) and globals, bare-identifier and global evaluate. Frame
 * slots inside loop-scoped frames may display positionally — the parser's slot
 * story for those arrives with the language-service rebuild.
 */
object Dap:
  /**
   * The exact line `--socket` mode prints before accepting; the IntelliJ plugin
   * extracts the port from it with the pattern "DAP server listening on port
   * ${port}", so this text is a wire contract, not a log message.
   */
  private def readyLine(port: Int): String = s"DAP server listening on port $port"

  def cli(args: List[String]): Int =
    val socketMode = args match
      case Nil               => false
      case "--socket" :: Nil => true
      case other :: _ =>
        Console.err.println(s"${Ansi.red}sfl dap: unknown argument '$other' (only --socket is accepted)${Ansi.reset}")
        return 2
    Sfl.init()
    if !socketMode then return new DapServer(System.in, System.out, protectStdout = true).serve()

    // Socket transport: an editor whose process plumbing re-decodes stdio text
    // (IntelliJ's does) gets a clean binary channel instead, and stdout becomes
    // an ordinary place for traces. One session per process, like stdio.
    val server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress)
    println(readyLine(server.getLocalPort))
    System.out.flush()
    // This binary is ready in single-digit milliseconds — faster than an IDE
    // reliably attaches its output listeners. Repeating the announcement until
    // someone connects means a listener attached late still sees it; the first
    // line usually remains the only one.
    val announced = new java.util.concurrent.atomic.AtomicBoolean(false)
    val announcer = new Thread(() => {
      var i = 0
      while !announced.get() && i < 40 do
        Thread.sleep(250)
        if !announced.get() then
          println(readyLine(server.getLocalPort))
          System.out.flush()
        i += 1
    })
    announcer.setDaemon(true)
    announcer.start()
    // A DAP client speaks first — lsp4j sends `initialize` the moment it
    // connects. A connection that closes without a byte was a liveness probe
    // (LSP4IJ's tracker checks ports exactly that way); the real client is
    // still coming, so keep accepting until someone talks.
    var session: java.net.Socket = null
    var sessionIn: java.io.PushbackInputStream = null
    try
      while session == null do
        val cand = server.accept()
        val pin = new java.io.PushbackInputStream(cand.getInputStream, 1)
        cand.setSoTimeout(3000)
        val first =
          try pin.read()
          catch case _: java.net.SocketTimeoutException => -1
        if first >= 0 then
          pin.unread(first)
          cand.setSoTimeout(0)
          session = cand
          sessionIn = pin
        else cand.close()
    finally
      announced.set(true)
      server.close()
    try new DapServer(sessionIn, session.getOutputStream, protectStdout = false).serve()
    finally session.close()

private object Mode:
  final val Run = 0
  final val StepIn = 1
  final val StepOver = 2
  final val StepOut = 3
  final val Pause = 4

final class DapServer(
    rawIn: java.io.InputStream,
    rawOut: java.io.OutputStream,
    /** stdio mode only: the protocol owns fd 1, so it is dup'd away up front. */
    protectStdout: Boolean
) extends DebugHook:

  // -------------------------------------------------------------------------
  // Wire plumbing. In stdio mode fd 1 is captured before anything prints to it;
  // in socket mode the channel is private by construction and stdout stays free.
  // -------------------------------------------------------------------------

  private val protoFd: Int =
    if LinktimeInfo.isWindows then -1
    else if !protectStdout then -1
    else
      val saved = unistd.dup(1)
      if saved >= 0 then unistd.dup2(2, 1)
      saved

  private val in = new BufferedInputStream(rawIn)
  private val outLock = new Object
  private var seq = 1

  private def writeBytes(b: Array[Byte]): Unit =
    if protoFd < 0 then
      rawOut.write(b); rawOut.flush()
    else
      Zone {
        val p = scala.scalanative.unsafe.alloc[Byte](b.length)
        var i = 0
        while i < b.length do
          !(p + i) = b(i)
          i += 1
        var off = 0
        while off < b.length do
          val n = unistd.write(protoFd, p + off, (b.length - off).toUSize)
          if n <= 0 then off = b.length
          else off += n.toInt
      }

  private def send(kind: String, fields: Seq[(String, Value)]): Unit =
    outLock.synchronized {
      val msg = VObj.of(Seq("seq" -> VInt.of(seq), "type" -> VStr(kind)) ++ fields)
      seq += 1
      val body = Json.stringify(msg, pretty = false).getBytes(UTF_8)
      writeBytes(s"Content-Length: ${body.length}\r\n\r\n".getBytes(UTF_8))
      writeBytes(body)
    }

  private def event(name: String, body: Value): Unit =
    send("event", Seq("event" -> VStr(name), "body" -> body))

  private def respond(req: VObj, body: Value): Unit =
    send("response", Seq(
      "request_seq" -> req.fields.getOrElse("seq", VInt.Zero),
      "success" -> VBool.True,
      "command" -> req.fields.getOrElse("command", VStr.empty),
      "body" -> body))

  private def reject(req: VObj, message: String): Unit =
    send("response", Seq(
      "request_seq" -> req.fields.getOrElse("seq", VInt.Zero),
      "success" -> VBool.False,
      "command" -> req.fields.getOrElse("command", VStr.empty),
      "message" -> VStr(message)))

  private def readHeaderLine(): String =
    val sb = new StringBuilder
    var c = in.read()
    if c < 0 then return null
    while c >= 0 && c != '\n' do
      if c != '\r' then sb.append(c.toChar)
      c = in.read()
    if c < 0 then null else sb.toString

  private def readMessage(): Option[Value] =
    var contentLength = -1
    var line = readHeaderLine()
    while line != null && line.nonEmpty do
      val colon = line.indexOf(':')
      if colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length") then
        contentLength =
          try line.substring(colon + 1).trim.toInt
          catch case _: NumberFormatException => -1
      line = readHeaderLine()
    if line == null || contentLength < 0 || contentLength > 16 * 1024 * 1024 then return None
    val buf = new Array[Byte](contentLength)
    var off = 0
    while off < contentLength do
      val n = in.read(buf, off, contentLength - off)
      if n < 0 then return None
      off += n
    try Some(Json.parse(new String(buf, UTF_8)))
    catch case _: SflError => Some(VNull)

  // -------------------------------------------------------------------------
  // Session state
  // -------------------------------------------------------------------------

  private final class TState(val id: Int, val rt: Interp, val name: String):
    val lock = new Object
    @volatile var paused = false
    @volatile var mode: Int = Mode.Run
    @volatile var modeDepth = 0
    // Valid while paused; read by the protocol thread, written by the owner.
    var pausedFrame: Frame = null
    var pausedLine = 0
    var pausedSrc: SourceRef = SourceRef.unknown
    /** The visible stack, rebuilt at each stop: (frame, proto, name, line, path). */
    var frames: Vector[(Frame, FnProto, String, Int, String)] = Vector.empty

  private val protocolThread = Thread.currentThread()
  private val states = new ConcurrentHashMap[Thread, TState]
  private val byId = new ConcurrentHashMap[Int, TState]
  private val nextThreadId = new AtomicInteger(1)
  @volatile private var killed = false
  @volatile private var entryPending = false

  /** file (canonical) -> lines. Replaced wholesale on each setBreakpoints. */
  @volatile private var breakpoints: Map[String, Set[Int]] = Map.empty
  private val canonCache = new ConcurrentHashMap[String, String]

  private var linesStartAt1 = true
  private var programPath: String = null
  private var programArgs: Array[String] = Array.empty
  private var launched = false
  private var configured = false
  private var started = false

  private def canon(path: String): String =
    var c = canonCache.get(path)
    if c == null then
      c = try new File(path).getCanonicalPath catch case _: java.io.IOException => path
      canonCache.put(path, c)
    c

  private def toClient(line: Int): Int = if linesStartAt1 then line else line - 1
  private def fromClient(line: Int): Int = if linesStartAt1 then line else line + 1

  // -------------------------------------------------------------------------
  // The hook: every interpreter thread lands here between statements
  // -------------------------------------------------------------------------

  override def onStatement(line: Int, col: Int, frame: Frame, rt: Interp): Unit =
    val th = Thread.currentThread()
    if th eq protocolThread then return
    if killed then throw new ExitSignal(1)
    var ts = states.get(th)
    if ts == null then ts = register(th, rt)

    val atBreak = breakpoints.nonEmpty && {
      val lines = breakpoints.getOrElse(canon(rt.src.name), null)
      lines != null && lines.contains(line)
    }
    val mode = ts.mode
    val reason =
      if atBreak then "breakpoint"
      else
        mode match
          case Mode.StepIn                                       => "step"
          case Mode.StepOver if rt.callDepth <= ts.modeDepth     => "step"
          case Mode.StepOut if rt.callDepth < ts.modeDepth       => "step"
          case Mode.Pause =>
            if entryPending then { entryPending = false; "entry" } else "pause"
          case _ => null
    if reason == null then return

    ts.mode = Mode.Run
    ts.pausedFrame = frame
    ts.pausedLine = line
    ts.pausedSrc = rt.src
    ts.frames = buildFrames(ts)
    event("stopped", VObj.of(Seq(
      "reason" -> VStr(reason),
      "threadId" -> VInt.of(ts.id),
      "allThreadsStopped" -> VBool.False)))
    ts.lock.synchronized {
      ts.paused = true
      while ts.paused && !killed do ts.lock.wait()
    }
    if killed then throw new ExitSignal(1)

  private def register(th: Thread, rt: Interp): TState =
    val id = nextThreadId.getAndIncrement()
    val ts = new TState(id, rt, if id == 1 then "main" else s"thread-$id")
    if id == 1 && entryPending then ts.mode = Mode.Pause
    states.put(th, ts)
    byId.put(id, ts)
    event("thread", VObj.of(Seq("reason" -> VStr("started"), "threadId" -> VInt.of(id))))
    ts

  /**
   * The stack as the editor will see it, newest first. Entry i shows the line the
   * activation is currently at: the paused line for the top, and for everything
   * deeper the call line its callee recorded — the same shift a traceback makes.
   */
  private def buildFrames(ts: TState): Vector[(Frame, FnProto, String, Int, String)] =
    val rt = ts.rt
    val entries = rt.trace // newest first
    val depth = entries.length
    val out = Vector.newBuilder[(Frame, FnProto, String, Int, String)]
    var i = 0
    while i < depth do
      val e = entries(i)
      val arrayIdx = depth - 1 - i
      val frame = if i == 0 then ts.pausedFrame else rt.debugFrame(arrayIdx)
      val proto = rt.debugProto(arrayIdx)
      val line = if i == 0 then ts.pausedLine else entries(i - 1).line
      val path = if i == 0 then ts.pausedSrc.name else entries(i - 1).src.name
      out += ((frame, proto, e.fn, line, path))
      i += 1
    // The program's top level: no proto, so locals stay empty and globals carry it.
    val bottomLine = if depth == 0 then ts.pausedLine else entries(depth - 1).line
    val bottomPath = if depth == 0 then ts.pausedSrc.name else entries(depth - 1).src.name
    val bottomFrame = if depth == 0 then ts.pausedFrame else null
    out += ((bottomFrame, null, "(top level)", bottomLine, bottomPath))
    out.result()

  private def resume(ts: TState, mode: Int): Unit =
    ts.mode = mode
    ts.modeDepth = ts.rt.callDepth
    ts.lock.synchronized {
      ts.paused = false
      ts.lock.notifyAll()
    }

  // -------------------------------------------------------------------------
  // The debuggee
  // -------------------------------------------------------------------------

  /** Line-buffered bridge from the program's Console to `output` events. */
  private final class EventStream(category: String) extends OutputStream:
    private val buf = new java.io.ByteArrayOutputStream(256)
    override def write(b: Int): Unit = buf.synchronized { buf.write(b) }
    override def write(b: Array[Byte], off: Int, len: Int): Unit =
      buf.synchronized { buf.write(b, off, len) }
    override def flush(): Unit =
      val text = buf.synchronized {
        val t = buf.toString("UTF-8")
        buf.reset()
        t
      }
      if text.nonEmpty then
        event("output", VObj.of(Seq("category" -> VStr(category), "output" -> VStr(text))))

  private def maybeStart(): Unit =
    if launched && configured && !started then
      started = true
      BuiltinsSys.scriptArgs = programArgs
      val t = new Thread(() => runProgram())
      t.setDaemon(true) // a disconnect must never leave the process pinned by the debuggee
      t.setName("sfl-debuggee")
      t.start()

  private def runProgram(): Unit =
    val out = new PrintStream(new EventStream("stdout"), true)
    val err = new PrintStream(new EventStream("stderr"), true)
    val code =
      try
        Console.withOut(out) {
          Console.withErr(err) {
            try
              Sfl.runFile(programPath)
              Threads.awaitNonDaemon()
              0
            catch
              case e: ExitSignal => e.code
              case e: SflError =>
                err.println(e.render(false))
                1
              case e: IncompleteInput =>
                err.println(Err.fromIncomplete(e, SourceRef(programPath, "")).render(false))
                1
          }
        }
      finally
        out.flush()
        err.flush()
    event("exited", VObj.of(Seq("exitCode" -> VInt.of(code))))
    event("terminated", VObj.empty)

  // -------------------------------------------------------------------------
  // Requests
  // -------------------------------------------------------------------------

  def serve(): Int =
    Sfl.attachThread() // evaluate() runs on this thread
    Debug.hook = this
    var running = true
    while running do
      readMessage() match
        case None => running = false
        case Some(msg: VObj) if msg.fields.get("type").contains(VStr("request")) =>
          val command = msg.fields.get("command") match
            case Some(VStr(c)) => c
            case _             => ""
          try
            if !handle(command, msg) then running = false
          catch case e: Throwable =>
            reject(msg, s"internal error in $command: ${e.getMessage}")
        case Some(_) => ()
    Debug.hook = null
    0

  /** Handles one request; false means the session is over. */
  private def handle(command: String, req: VObj): Boolean =
    val args = req.fields.getOrElse("arguments", VNull)
    command match
      case "initialize" =>
        args match
          case o: VObj => linesStartAt1 = !o.fields.get("linesStartAt1").contains(VBool.False)
          case _       => ()
        respond(req, VObj.of(Seq(
          "supportsConfigurationDoneRequest" -> VBool.True,
          "supportsTerminateRequest" -> VBool.True,
          "supportsEvaluateForHovers" -> VBool.True)))
        event("initialized", VObj.empty)
        true

      case "launch" =>
        val program = getStr(args, "program")
        if program.isEmpty then { reject(req, "launch needs a 'program'"); return true }
        if !new File(program).isFile then { reject(req, s"cannot open '$program'"); return true }
        programPath = program
        programArgs = args match
          case o: VObj => o.fields.get("args") match
            case Some(a: VArr) => a.items.collect { case VStr(s) => s }.toArray
            case _             => Array.empty
          case _ => Array.empty
        entryPending = getBool(args, "stopOnEntry")
        launched = true
        respond(req, VNull)
        maybeStart()
        true

      case "setBreakpoints" =>
        val path = canon(getStr(args, "source", "path"))
        val wanted = args match
          case o: VObj => o.fields.get("breakpoints") match
            case Some(a: VArr) => a.items.toList.map(b => fromClient(getInt(b, "line")))
            case _             => Nil
          case _ => Nil
        breakpoints =
          if wanted.isEmpty then breakpoints - path
          else breakpoints + (path -> wanted.toSet)
        respond(req, VObj.of(Seq("breakpoints" -> VArr.of(wanted.map(l =>
          VObj.of(Seq("verified" -> VBool.True, "line" -> VInt.of(toClient(l)))))))))
        true

      case "configurationDone" =>
        configured = true
        respond(req, VNull)
        maybeStart()
        true

      case "threads" =>
        val list = byId.values().toArray(Array.empty[TState]).sortBy(_.id).toSeq
        val rows = if list.isEmpty then Seq(VObj.of(Seq("id" -> VInt.of(1), "name" -> VStr("main"))))
          else list.map(ts => VObj.of(Seq("id" -> VInt.of(ts.id), "name" -> VStr(ts.name))))
        respond(req, VObj.of(Seq("threads" -> VArr.of(rows))))
        true

      case "stackTrace" =>
        withPaused(req, args) { ts =>
          val frames = ts.frames.zipWithIndex.map { case ((_, _, name, line, path), i) =>
            VObj.of(Seq(
              "id" -> VInt.of(ts.id * 100 + i),
              "name" -> VStr(name),
              "line" -> VInt.of(toClient(math.max(1, line))),
              "column" -> VInt.of(1),
              "source" -> VObj.of(Seq(
                "name" -> VStr(new File(path).getName),
                "path" -> VStr(canon(path))))))
          }
          respond(req, VObj.of(Seq(
            "stackFrames" -> VArr.of(frames),
            "totalFrames" -> VInt.of(frames.length))))
        }

      case "scopes" =>
        val frameId = getInt(args, "frameId")
        respond(req, VObj.of(Seq("scopes" -> VArr.of(Seq(
          VObj.of(Seq("name" -> VStr("Locals"), "variablesReference" -> VInt.of(frameId * 10 + 1),
            "expensive" -> VBool.False)),
          VObj.of(Seq("name" -> VStr("Globals"), "variablesReference" -> VInt.of(frameId * 10 + 2),
            "expensive" -> VBool.True)))))))
        true

      case "variables" =>
        val ref = getInt(args, "variablesReference")
        val kind = ref % 10
        val frameId = ref / 10
        val ts = byId.get(frameId / 100)
        if ts == null || !ts.paused then respond(req, VObj.of(Seq("variables" -> VArr.empty)))
        else
          val idx = frameId % 100
          val rows =
            if kind == 1 then localRows(ts, idx)
            else globalRows(ts)
          respond(req, VObj.of(Seq("variables" -> VArr.of(rows))))
        true

      case "continue" =>
        withPaused(req, args) { ts =>
          respond(req, VObj.of(Seq("allThreadsContinued" -> VBool.False)))
          resume(ts, Mode.Run)
        }

      case "next" =>
        withPaused(req, args) { ts => respond(req, VNull); resume(ts, Mode.StepOver) }
      case "stepIn" =>
        withPaused(req, args) { ts => respond(req, VNull); resume(ts, Mode.StepIn) }
      case "stepOut" =>
        withPaused(req, args) { ts => respond(req, VNull); resume(ts, Mode.StepOut) }

      case "pause" =>
        val ts = byId.get(getInt(args, "threadId"))
        if ts == null then reject(req, "no such thread")
        else
          ts.mode = Mode.Pause
          respond(req, VNull)
        true

      case "evaluate" =>
        respondEvaluate(req, args)
        true

      case "disconnect" | "terminate" =>
        killed = true
        states.values().forEach { ts =>
          ts.lock.synchronized { ts.paused = false; ts.lock.notifyAll() }
        }
        respond(req, VNull)
        false

      case other =>
        reject(req, s"unsupported request '$other'")
        true

  private def withPaused(req: VObj, args: Value)(f: TState => Unit): Boolean =
    val ts = byId.get(getInt(args, "threadId"))
    if ts == null then reject(req, "no such thread")
    else if !ts.paused then reject(req, "thread is not paused")
    else f(ts)
    true

  // -------------------------------------------------------------------------
  // Variables and evaluate
  // -------------------------------------------------------------------------

  private def render(v: Value): (String, String) =
    if v == null then ("«unassigned»", "unknown")
    else (Err.show(v), v.typeName)

  private def varRow(name: String, v: Value): Value =
    val (value, tpe) = render(v)
    VObj.of(Seq(
      "name" -> VStr(name),
      "value" -> VStr(value),
      "type" -> VStr(tpe),
      "variablesReference" -> VInt.of(0)))

  private def localRows(ts: TState, idx: Int): Seq[Value] =
    if idx < 0 || idx >= ts.frames.length then return Nil
    val (frame, proto, _, _, _) = ts.frames(idx)
    if frame == null then return Nil
    val names = if proto == null then null else proto.debugNames
    val out = mutable.ArrayBuffer.empty[Value]
    var i = 0
    while i < frame.slots.length do
      val name =
        if names != null && i < names.length then names(i)
        else s"slot#$i"
      if !name.startsWith("__pat") && frame.slots(i) != null then
        out += varRow(name, frame.slots(i))
      i += 1
    out.toSeq

  private def globalRows(ts: TState): Seq[Value] =
    val g = ts.rt.globals
    g.definedNames.sorted.flatMap { name =>
      g.valueOf(name) match
        case Some(_: VNative) => None // the builtin table is noise here
        case Some(v)          => Some(varRow(name, v))
        case None             => None
    }

  private def respondEvaluate(req: VObj, args: Value): Unit =
    val expr = getStr(args, "expression").trim
    if expr.isEmpty then { reject(req, "nothing to evaluate"); return }
    // A bare identifier resolves against the chosen frame first, so hovering a
    // local shows the local even though full expressions run globally.
    val frameId = getInt(args, "frameId")
    if expr.forall(c => c == '_' || c == '$' || Character.isLetterOrDigit(c)) && frameId > 0 then
      val ts = byId.get(frameId / 100)
      if ts != null && ts.paused then
        val idx = frameId % 100
        if idx >= 0 && idx < ts.frames.length then
          val (frame, proto, _, _, _) = ts.frames(idx)
          val names = if proto == null then null else proto.debugNames
          if frame != null && names != null then
            var i = 0
            while i < frame.slots.length do
              if i < names.length && names(i) == expr && frame.slots(i) != null then
                val (value, tpe) = render(frame.slots(i))
                respond(req, VObj.of(Seq(
                  "result" -> VStr(value), "type" -> VStr(tpe),
                  "variablesReference" -> VInt.of(0))))
                return
              i += 1
    try
      val v = Sfl.run(expr, "<debug>", Sfl.callerTag)
      val (value, tpe) = render(v)
      respond(req, VObj.of(Seq(
        "result" -> VStr(value), "type" -> VStr(tpe),
        "variablesReference" -> VInt.of(0))))
    catch
      case e: SflError        => reject(req, e.getMessage)
      case e: IncompleteInput => reject(req, e.reason)
      case e: ExitSignal      => reject(req, s"the expression called exit(${e.code})")

  // -------------------------------------------------------------------------
  // JSON accessors, shared shape with Lsp
  // -------------------------------------------------------------------------

  private def get(v: Value, path: String*): Value =
    path.foldLeft(v) { (cur, key) =>
      cur match
        case o: VObj => o.fields.getOrElse(key, VNull)
        case _       => VNull
    }

  private def getStr(v: Value, path: String*): String = get(v, path*) match
    case VStr(s) => s
    case _       => ""

  private def getInt(v: Value, path: String*): Int = get(v, path*) match
    case VInt(n)   => n.toInt
    case VFloat(f) => f.toInt
    case _         => 0

  private def getBool(v: Value, path: String*): Boolean = get(v, path*) match
    case b: VBool => b.b
    case _        => false
