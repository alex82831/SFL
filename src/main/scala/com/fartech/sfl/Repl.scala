package com.fartech.sfl

import java.io.File

/**
 * The interactive shell.
 *
 * Beyond evaluating expressions it offers history, completion, multi-line entry and a
 * set of `:` commands for inspecting and timing code.
 */
object Repl:

  private val commands = Seq(
    ":help"     -> "Show this help.",
    ":quit"     -> "Leave the shell (also Ctrl-D).",
    ":vars"     -> "List the variables you have defined.",
    ":funcs"    -> "List the functions you have defined.",
    ":builtins" -> "Show the builtin reference; takes an optional filter.",
    ":type"     -> "Evaluate an expression and report its type.",
    ":time"     -> "Evaluate an expression and report how long it took.",
    ":ast"      -> "Show the parsed syntax tree for an expression.",
    ":doc"      -> "Show one builtin in full: signature, group and description.",
    ":threads"  -> "List the threads this session has started.",
    ":trace"    -> "Show the last error again, with its traceback.",
    ":bench"    -> "Time an expression over several runs: :bench <n> <expr>.",
    ":load"     -> "Run a script file into this session.",
    ":save"     -> "Write this session's input to a file.",
    ":history"  -> "Show recent input.",
    ":reset"    -> "Forget every user definition.",
    ":clear"    -> "Clear the screen.",
    ":pretty"   -> "Toggle indented printing of arrays and objects: :pretty on|off.",
    ":color"    -> "Toggle coloured output: :color on|off.",
    ":depth"    -> "Show or set the maximum call depth."
  )

  def commandNames: Seq[String] = commands.map(_._1)

  private var pretty = true
  private var running = true

  /** Kept so `:trace` can show the last failure again after the prompt has moved on. */
  private var lastError: SflError = null

  def run(): Int =
    Sfl.init()
    val history = new History(historyFile)
    history.load()
    val interactive = Terminal.isTty && Terminal.enableRaw()
    val editor = new LineEditor(history)

    banner(interactive)

    var exitCode = 0
    val pending = new StringBuilder
    try
      while running do
        val prompt =
          if pending.isEmpty then s"${Ansi.brightGreen}sfl${Ansi.reset}${Ansi.dim}>${Ansi.reset} "
          else s"${Ansi.dim}...${Ansi.reset} "
        editor.read(prompt) match
          case Input.Eof =>
            println(s"${Ansi.dim}bye${Ansi.reset}")
            running = false
          case Input.Interrupted =>
            pending.setLength(0)
          case Input.Line(text) =>
            if pending.isEmpty && text.trim.isEmpty then ()
            else
              if pending.nonEmpty then pending.append('\n')
              pending.append(text)
              val source = pending.toString
              if needsMore(source) then ()
              else
                pending.setLength(0)
                history.add(source)
                // Leave raw mode while user code runs, so Ctrl-C still interrupts and
                // any readLine() inside the script behaves normally.
                if interactive then Terminal.restore()
                try exitCode = handle(source.trim, history)
                finally if interactive && running then Terminal.enableRaw()
    catch
      case e: ExitSignal =>
        exitCode = e.code
    finally
      Terminal.restore()
      history.save()
    exitCode

  private def historyFile: File =
    val home = sys.props.getOrElse("user.home", sys.env.getOrElse("HOME", "."))
    new File(home, ".sfl_history")

  /** True when the input is not yet a complete program. */
  private def needsMore(source: String): Boolean =
    if source.trim.startsWith(":") then false
    else
      try
        Sfl.probe(source, "<repl-probe>")
        false
      catch
        case _: IncompleteInput => true
        case _: ParseError      => Lexer.isIncomplete(source)
        case _: Throwable       => false

  /** Runs one complete entry: either a `:` command or SFL source. */
  private def handle(source: String, history: History): Int =
    if source.startsWith(":") then command(source, history)
    else
      evalAndPrint(source)
      0

  private def evalAndPrint(source: String): Unit =
    try
      val v = Sfl.run(source, "<repl>")
      printResult(v)
    catch
      case e: ExitSignal  => throw e
      case e: SflError    =>
        lastError = e
        println(e.render(Ansi.enabled))
      case e: IncompleteInput =>
        val parseErr = Err.fromIncomplete(e, SourceRef("<repl>", source))
        lastError = parseErr
        println(parseErr.render(Ansi.enabled))
      case e: StackOverflowError =>
        println(s"${Ansi.red}Runtime error: expression nested too deeply${Ansi.reset}")
      case e: Throwable =>
        println(s"${Ansi.red}Internal error: ${e.getClass.getSimpleName}: ${e.getMessage}${Ansi.reset}")

  private def printResult(v: Value): Unit =
    // A null result almost always comes from a statement such as println(...), and
    // echoing "null" after each of those is just noise.
    if v ne VNull then
      val compact = v.repr
      // Only spread a container over several lines when it would not fit anyway.
      val expandable = v.isInstanceOf[VArr] || v.isInstanceOf[VObj]
      val rendered =
        if pretty && expandable && compact.length > math.max(40, Terminal.width - 12) then Values.pretty(v)
        else compact
      println(s"${Ansi.dim}=${Ansi.reset} ${colorValue(v, rendered)} ${Ansi.dim}: ${v.typeName}${Ansi.reset}")

  private def colorValue(v: Value, rendered: String): String =
    if !Ansi.enabled then rendered
    else
      v match
        case _: VStr                   => Ansi.green + rendered + Ansi.reset
        case _: VInt | _: VFloat       => Ansi.yellow + rendered + Ansi.reset
        case _: VBool                  => Ansi.magenta + rendered + Ansi.reset
        case _: VFun | _: VNative      => Ansi.cyan + rendered + Ansi.reset
        case _                         => Highlight.line(rendered)

  // -------------------------------------------------------------------------
  // Meta commands
  // -------------------------------------------------------------------------

  private def command(line: String, history: History): Int =
    val (name, rest) =
      val i = line.indexWhere(_.isWhitespace)
      if i < 0 then (line, "") else (line.substring(0, i), line.substring(i).trim)

    name match
      case ":help" | ":h" | ":?" => showHelp(); 0
      case ":quit" | ":q" | ":exit" =>
        running = false
        println(s"${Ansi.dim}bye${Ansi.reset}")
        0
      case ":clear" =>
        print(Ansi.clearScreen)
        Console.out.flush()
        0
      case ":vars"     => showVars(functions = false); 0
      case ":funcs"    => showVars(functions = true); 0
      case ":builtins" => println(Builtins.helpText(rest, Ansi.enabled)); 0
      case ":history" =>
        val n = history.size
        for i <- math.max(0, n - 30) until n do
          println(f"${Ansi.dim}${i + 1}%4d${Ansi.reset} ${Highlight.line(history(i))}")
        0
      case ":reset" =>
        Sfl.reset()
        lastError = null
        println(s"${Ansi.dim}session cleared${Ansi.reset}")
        0
      case ":doc" =>
        if rest.isEmpty then warn(":doc needs a builtin name")
        else showDoc(rest)
        0
      case ":threads" => showThreads(); 0
      case ":trace" =>
        if lastError == null then println(s"${Ansi.dim}no error yet${Ansi.reset}")
        else println(lastError.render(Ansi.enabled))
        0
      case ":bench" => bench(rest); 0
      case ":type" =>
        if rest.isEmpty then warn(":type needs an expression")
        else
          try
            val v = Sfl.run(rest, "<repl>")
            println(s"${Ansi.cyan}${v.typeName}${Ansi.reset}")
          catch case e: SflError => println(e.render(Ansi.enabled))
        0
      case ":time" =>
        if rest.isEmpty then warn(":time needs an expression")
        else
          try
            val start = System.nanoTime()
            val v = Sfl.run(rest, "<repl>")
            val ms = (System.nanoTime() - start) / 1e6
            printResult(v)
            println(f"${Ansi.dim}elapsed: $ms%.3f ms${Ansi.reset}")
          catch case e: SflError => println(e.render(Ansi.enabled))
        0
      case ":ast" =>
        if rest.isEmpty then warn(":ast needs an expression")
        else
          try println(Dump.render(Sfl.compile(rest, "<repl>")._1))
          catch case e: SflError => println(e.render(Ansi.enabled))
        0
      case ":load" =>
        if rest.isEmpty then warn(":load needs a file name")
        else
          try
            val v = Sfl.runFile(rest)
            println(s"${Ansi.dim}loaded $rest${Ansi.reset}")
            printResult(v)
          catch
            case e: SflError => println(e.render(Ansi.enabled))
            case e: IncompleteInput => println(s"${Ansi.red}Syntax error: ${e.reason}${Ansi.reset}")
        0
      case ":save" =>
        if rest.isEmpty then warn(":save needs a file name")
        else
          FileUtil.write(rest, history.all.mkString("\n") + "\n")
          println(s"${Ansi.dim}wrote ${history.size} line(s) to $rest${Ansi.reset}")
        0
      case ":pretty" =>
        pretty = toggle(rest, pretty)
        println(s"${Ansi.dim}pretty printing ${if pretty then "on" else "off"}${Ansi.reset}")
        0
      case ":color" =>
        Ansi.enabled = toggle(rest, Ansi.enabled)
        println(s"colour ${if Ansi.enabled then "on" else "off"}")
        0
      case ":depth" =>
        if rest.isEmpty then println(s"${Ansi.dim}max call depth: ${Sfl.interp.maxDepth}${Ansi.reset}")
        else
          scala.util.Try(rest.toInt).toOption match
            case Some(n) if n > 0 =>
              Sfl.interp.maxDepth = n
              println(s"${Ansi.dim}max call depth: $n${Ansi.reset}")
            case _ => warn("expected a positive number")
        0
      case other =>
        warn(s"unknown command '$other'; try :help")
        0

  private def toggle(arg: String, current: Boolean): Boolean = arg.toLowerCase match
    case "on" | "true" | "yes"  => true
    case "off" | "false" | "no" => false
    case _                      => !current

  private def warn(msg: String): Unit = println(s"${Ansi.yellow}$msg${Ansi.reset}")

  /** One builtin in full, or the closest name when there is no exact match. */
  private def showDoc(name: String): Unit =
    Builtins.lookup(name) match
      case Some(b) =>
        println(s"${Ansi.cyan}${b.signature}${Ansi.reset}  ${Ansi.dim}[${b.group}]${Ansi.reset}")
        println(s"  ${b.doc}")
        val arity =
          if b.maxArity < 0 then s"${b.minArity} or more arguments"
          else if b.minArity == b.maxArity then s"exactly ${b.minArity} argument(s)"
          else s"${b.minArity} to ${b.maxArity} arguments"
        println(s"  ${Ansi.dim}takes $arity${Ansi.reset}")
      case None =>
        Sfl.globals.valueOf(name) match
          case Some(f: VFun) =>
            println(s"${Ansi.cyan}${f.proto.signature}${Ansi.reset}  ${Ansi.dim}[user-defined]${Ansi.reset}")
            println(s"  ${Ansi.dim}defined at ${f.proto.src.name}:${f.proto.line}${Ansi.reset}")
          case Some(v) =>
            println(s"${Ansi.dim}$name is a ${v.typeName}, not a function${Ansi.reset}")
          case None =>
            val hint = Err.suggest(name, Builtins.publicNames ++ Sfl.globals.definedNames)
            warn(s"no builtin named '$name'" + hint.fold("")(h => s"; did you mean '$h'?"))

  private def showThreads(): Unit =
    val threads = Threads.snapshot
    if threads.isEmpty then println(s"${Ansi.dim}no threads started in this session${Ansi.reset}")
    else
      val width = threads.map(_._1.length).max
      for (name, state, failure) <- threads do
        val colour = state match
          case "failed"                => Ansi.red
          case s if s.startsWith("run") => Ansi.yellow
          case _                       => Ansi.green
        println(s"  ${Ansi.brightBlue}${name.padTo(width, ' ')}${Ansi.reset}  $colour$state${Ansi.reset}" +
          (if failure.isEmpty then "" else s"  ${Ansi.dim}$failure${Ansi.reset}"))

  /** `:bench [n] <expr>` — repeats an expression and reports min, mean and max. */
  private def bench(arg: String): Unit =
    if arg.isEmpty then
      warn(":bench needs an expression, optionally preceded by a repeat count")
      return
    val (runs, source) =
      val head = arg.takeWhile(!_.isWhitespace)
      scala.util.Try(head.toInt).toOption match
        case Some(n) if n > 0 => (n, arg.drop(head.length).trim)
        case _                => (10, arg)
    if source.isEmpty then
      warn(":bench needs an expression after the repeat count")
      return
    try
      val (block, ref) = Sfl.compile(source, "<bench>")
      // One untimed run first, so compilation and any lazy initialisation are excluded.
      Sfl.execute(block, ref)
      var best = Double.MaxValue
      var worst = 0.0
      var total = 0.0
      var i = 0
      while i < runs do
        val t0 = System.nanoTime()
        Sfl.execute(block, ref)
        val ms = (System.nanoTime() - t0) / 1e6
        if ms < best then best = ms
        if ms > worst then worst = ms
        total += ms
        i += 1
      println(f"${Ansi.dim}$runs runs: min $best%.3f ms, mean ${total / runs}%.3f ms, max $worst%.3f ms${Ansi.reset}")
    catch
      case e: SflError =>
        lastError = e
        println(e.render(Ansi.enabled))

  private def showVars(functions: Boolean): Unit =
    val names = Sfl.globals.definedNames.filter(n => Builtins.lookup(n).isEmpty).sorted
    val chosen = names.filter { n =>
      val v = Sfl.globals.valueOf(n)
      v.exists(x => Values.isCallable(x) == functions)
    }
    if chosen.isEmpty then
      println(s"${Ansi.dim}no ${if functions then "functions" else "variables"} defined yet${Ansi.reset}")
    else
      val width = chosen.map(_.length).max
      for n <- chosen do
        val v = Sfl.globals.valueOf(n).get
        val shown =
          if functions then v match
            case f: VFun => f.proto.signature
            case other   => other.repr
          else abbreviate(v.repr, 68)
        println(s"  ${Ansi.brightBlue}${n.padTo(width, ' ')}${Ansi.reset}  ${Ansi.dim}${v.typeName}${Ansi.reset}  $shown")

  private def abbreviate(s: String, max: Int): String =
    if s.length <= max then s else s.substring(0, max - 1) + "…"

  private def showHelp(): Unit =
    val width = commands.map(_._1.length).max
    println(s"${Ansi.bold}Commands${Ansi.reset}")
    for (name, doc) <- commands do
      println(s"  ${Ansi.cyan}${name.padTo(width, ' ')}${Ansi.reset}  ${Ansi.dim}$doc${Ansi.reset}")
    println()
    println(s"${Ansi.bold}Editing${Ansi.reset}")
    val keys = Seq(
      "Tab" -> "Complete a name; twice to list the choices.",
      "Up / Down" -> "Walk history; type a prefix first to filter it.",
      "Ctrl-A / Ctrl-E" -> "Jump to the start or end of the line.",
      "Ctrl-W / Ctrl-U / Ctrl-K" -> "Delete the previous word, the line before the cursor, or after it.",
      "Ctrl-L" -> "Clear the screen.",
      "Ctrl-C" -> "Abandon the current line.",
      "Ctrl-D" -> "Leave the shell."
    )
    val kw = keys.map(_._1.length).max
    for (k, doc) <- keys do
      println(s"  ${Ansi.cyan}${k.padTo(kw, ' ')}${Ansi.reset}  ${Ansi.dim}$doc${Ansi.reset}")
    println()
    println(s"${Ansi.dim}A line continues onto the next when brackets are open or it ends in an operator,${Ansi.reset}")
    println(s"${Ansi.dim}so blocks and |> pipelines can be typed over several lines.${Ansi.reset}")
    println(s"${Ansi.dim}Call help() for the builtin reference, help(\"json\") to filter it, or :doc <name>.${Ansi.reset}")

  private def banner(interactive: Boolean): Unit =
    val title = s"SFL ${Sfl.version}"
    val subtitle = "Scripting for Fun"
    val lines = Seq(
      s"${Ansi.brightRed}${Ansi.bold}$title${Ansi.reset}  ${Ansi.dim}—${Ansi.reset}  ${Ansi.brightBlue}$subtitle${Ansi.reset}",
      s"${Ansi.dim}${Builtins.publicNames.length} builtins loaded. :help for commands, help() for the library.${Ansi.reset}"
    )
    val inner = lines.map(Terminal.displayWidth).max
    val bar = "─" * (inner + 2)
    println(s"${Ansi.dim}╭$bar╮${Ansi.reset}")
    for l <- lines do
      val padding = " " * (inner - Terminal.displayWidth(l))
      println(s"${Ansi.dim}│${Ansi.reset} $l$padding ${Ansi.dim}│${Ansi.reset}")
    println(s"${Ansi.dim}╰$bar╯${Ansi.reset}")
    if !interactive then
      println(s"${Ansi.dim}(no terminal detected: line editing and history are disabled)${Ansi.reset}")
