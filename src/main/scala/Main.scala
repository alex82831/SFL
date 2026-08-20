import com.fartech.sfl.*

import java.io.File
import scala.annotation.tailrec

object Main:

  private val usage =
    s"""${Ansi.bold}sfl ${Sfl.version}${Ansi.reset} — Scripting for Fun
       |
       |${Ansi.bold}Usage${Ansi.reset}
       |  sfl                        Start the interactive shell
       |  sfl <script.sfl> [args…]   Run a script, passing the remaining arguments to it
       |  sfl -e '<code>'            Evaluate code given on the command line
       |  sfl -c <script.sfl>        Compile to a native executable via LLVM
       |
       |${Ansi.bold}Options${Ansi.reset}
       |  -repl              Start the shell even when other arguments are present
       |  -source <file>     Run a script (the historical spelling of the positional form)
       |  -e, --eval <code>  Evaluate the given source text
       |  --ast              Print the syntax tree instead of running
       |
       |${Ansi.bold}Compiling${Ansi.reset}
       |  -c, --compile <f>  Compile a script to a native executable
       |  -o <path>          Where to write the executable (default: the script's name)
       |  --emit-llvm        Print the generated LLVM IR instead of building
       |  -O0 … -O3          Optimisation level passed to clang (default -O2)
       |  --keep             Keep the generated .ll next to the executable
       |  --pkg-source       Compile imported packages from source, never their prebuilt archives
       |  --build-runtime    Build the runtime and library archives now, and report where
       |  --clean-cache      Delete the cached archives, so the next compile rebuilds them
       |
       |  The compiler accepts the whole language. Types it can prove become unboxed
       |  machine values; everything else — strings, arrays, objects, closures,
       |  higher-order functions, errors — runs against the SFL runtime, which the
       |  program links statically. Only eval() and parse() are out of reach, because
       |  they would need the interpreter itself.
       |
       |${Ansi.bold}Projects${Ansi.reset}
       |  sfl build [task] [args…]   Build, run or test the project described by ./build.sfl
       |
       |  The build tool is written in SFL and so is the project file: build.sfl
       |  calls project({...}) and task(...). 'sfl build init' scaffolds one, and
       |  'sfl build --help' explains the rest.
       |
       |${Ansi.bold}Packages${Ansi.reset}
       |  sfl pkg build [--bin] [dir]               Pack a package (--bin compiles it too)
       |  sfl pkg install <src> [--local] [--force] Install a .sflpkg or a package directory
       |  sfl pkg list                              List installed packages
       |  sfl pkg remove <name>[@version]           Remove one version, or every version
       |
       |  import "name" loads an installed package's main module, and "name/mod" a
       |  module within it; versions come from the deps ranges in sfl.pkg.
       |
       |${Ansi.bold}Tooling${Ansi.reset}
       |  sfl check [--json] <files…>  Parse files and report problems without running
       |  sfl syntax                   Print keywords, operators and builtin metadata as JSON
       |  sfl lsp                      Serve the Language Server Protocol over stdio
       |  sfl dap                      Serve the Debug Adapter Protocol over stdio
       |
       |  Both exist for editors and CI: `check` is what the IDE plugins run on save,
       |  and `syntax` is what generates their keyword tables, so no list can drift.
       |
       |${Ansi.bold}Other${Ansi.reset}
       |  --time             Report parse and run time on stderr
       |  --no-color         Disable ANSI colours (also honours NO_COLOR)
       |  -v, --version      Print the version
       |  -h, --help         Print this message
       |
       |${Ansi.bold}Environment${Ansi.reset}
       |  SFL_HOME           Base directory whose libs/ folder is searched by import,
       |                     and whose packages/ folder holds installed packages
       |  SFL_MAX_DEPTH      Maximum non-tail call depth before an overflow is reported
       |  SFL_CACHE          Where compiled runtime archives are kept (default ~/.cache/sfl)
       |""".stripMargin

  private final case class Options(
      repl: Boolean = false,
      source: Option[String] = None,
      eval: Option[String] = None,
      ast: Boolean = false,
      time: Boolean = false,
      compile: Boolean = false,
      emitLlvm: Boolean = false,
      output: Option[String] = None,
      optLevel: String = "O2",
      keepArtifacts: Boolean = false,
      buildRuntime: Boolean = false,
      cleanCache: Boolean = false,
      pkgSource: Boolean = false,
      scriptArgs: Array[String] = Array.empty
  )

  def main(args: Array[String]): Unit =
    // Colour is off when output is redirected, so piping to a file stays clean.
    Ansi.enabled = !sys.env.contains("NO_COLOR") && Terminal.isTty

    // `sfl pkg …` and friends are commands, not option sets, so they dispatch before
    // the option parser can mistake their names for a script. A script that really is
    // called `check` still runs as `sfl ./check` or `sfl check.sfl`.
    if args.nonEmpty && args(0) == "pkg" then sys.exit(PkgTool.cli(args.toList.drop(1)))
    if args.nonEmpty && args(0) == "check" then sys.exit(IdeTool.checkCli(args.toList.drop(1)))
    if args.nonEmpty && args(0) == "syntax" then sys.exit(IdeTool.syntaxCli(args.toList.drop(1)))
    if args.nonEmpty && args(0) == "lsp" then sys.exit(Lsp.cli(args.toList.drop(1)))
    if args.nonEmpty && args(0) == "dap" then sys.exit(Dap.cli(args.toList.drop(1)))
    if args.nonEmpty && args(0) == "build" then
      BuiltinsSys.scriptArgs = args.drop(1)
      sys.exit(runBuildTool())

    val opts =
      try parseArgs(args.toList, Options())
      catch
        case e: IllegalArgumentException =>
          Console.err.println(s"${Ansi.red}${e.getMessage}${Ansi.reset}")
          Console.err.println("Try 'sfl --help'.")
          sys.exit(2)

    BuiltinsSys.scriptArgs = opts.scriptArgs

    val code =
      if opts.cleanCache || opts.buildRuntime then manageCache(opts)
      else if opts.compile || opts.emitLlvm then compileOnce(opts)
      else if opts.repl || (opts.source.isEmpty && opts.eval.isEmpty) then Repl.run()
      else runOnce(opts)

    // Let ordinary threads finish rather than cutting them off mid-work, then surface
    // any that failed without ever being awaited. exit() skips this by throwing past it.
    Threads.awaitNonDaemon()
    val silentFailures = Threads.reportUnobservedFailures()
    sys.exit(if code == 0 && silentFailures > 0 then 1 else code)

  /**
   * `sfl build`: the build tool is itself SFL, embedded at build time the way the
   * stdlib is, so one binary carries its own build system. The project file it
   * reads — build.sfl — is ordinary SFL too; the tool defines project() and
   * task() before evaluating it, which is the whole DSL.
   */
  private def runBuildTool(): Int =
    try
      Sfl.run(BuildToolSources.text("main.sfl"), "<sfl-build>")
      0
    catch
      case e: ExitSignal => e.code
      case e: SflError =>
        Console.err.println(e.render(Ansi.enabled))
        1

  private def runOnce(opts: Options): Int =
    val (text, name) = opts.eval match
      case Some(code) => (code, "<eval>")
      case None =>
        val path = opts.source.get
        val f = new File(path)
        if !f.isFile then
          Console.err.println(s"${Ansi.red}sfl: cannot open '$path'${Ansi.reset}")
          return 66
        Sfl.importer = new Importer(if f.getParentFile != null then f.getParentFile else new File("."))
        BuiltinsSys.scriptPath = f.getPath
        (FileUtil.read(f), f.getPath)

    try
      val parseStart = System.nanoTime()
      val (block, ref) = Sfl.compile(text, name)
      val parsed = System.nanoTime()
      if opts.ast then
        println(Dump.render(block))
        return 0
      Sfl.execute(block, ref)
      if opts.time then
        val done = System.nanoTime()
        Console.err.println(
          f"${Ansi.dim}parse ${(parsed - parseStart) / 1e6}%.2f ms, run ${(done - parsed) / 1e6}%.2f ms${Ansi.reset}"
        )
      0
    catch
      case e: ExitSignal => e.code
      case e: SflError =>
        Console.err.println(e.render(Ansi.enabled))
        1
      case e: IncompleteInput =>
        Console.err.println(Err.fromIncomplete(e, SourceRef(name, text)).render(Ansi.enabled))
        1
      case _: StackOverflowError =>
        Console.err.println(s"${Ansi.red}Runtime error: expression nested too deeply${Ansi.reset}")
        1

  /**
   * Builds or discards the archives compiled programs link against. Doing this
   * explicitly is useful on a build machine, where the first compile should not be
   * the one that pays for the runtime.
   */
  private def manageCache(opts: Options): Int =
    try
      if opts.cleanCache then
        Backend.clearCache()
        Console.err.println(s"${Ansi.dim}removed the compiler cache${Ansi.reset}")
      if opts.buildRuntime then
        Backend.ensureCache(verbose = true) match
          case Some(r) =>
            Console.err.println(
              f"built ${r.runtimeObjects} runtime objects and ${r.libraryModules} " +
                f"library modules in ${r.seconds}%.1fs")
          case None =>
            Console.err.println("already built")
        println(Backend.cacheDir.getPath)
      0
    catch
      case e: SflError =>
        Console.err.println(e.render(Ansi.enabled))
        1

  /** Ahead-of-time compilation: parse with the same front end, then emit and link. */
  private def compileOnce(opts: Options): Int =
    val (text, name) = opts.eval match
      case Some(code) => (code, "<eval>")
      case None =>
        opts.source match
          case None =>
            Console.err.println(s"${Ansi.red}sfl: -c needs a script to compile${Ansi.reset}")
            return 2
          case Some(path) =>
            val f = new File(path)
            if !f.isFile then
              Console.err.println(s"${Ansi.red}sfl: cannot open '$path'${Ansi.reset}")
              return 66
            Sfl.importer = new Importer(if f.getParentFile != null then f.getParentFile else new File("."))
            (FileUtil.read(f), f.getPath)

    try
      val (block, ref) = Sfl.compile(text, name)
      val compiler = new Compiler(Sfl.globals, ref)
      if opts.pkgSource then compiler.disableBinaryLinking()
      val ir = compiler.compile(block)
      if opts.emitLlvm then
        println(ir)
        0
      else
        val target = opts.output.getOrElse {
          val base = new File(name).getName
          if base.endsWith(".sfl") then base.dropRight(4) else base + ".out"
        }
        val result = Backend.link(ir, target, opts.optLevel, opts.keepArtifacts,
          compiler.linkedArchiveFiles)
        Console.err.println(s"${Ansi.dim}compiled ${new File(name).getName} -> ${result.exePath}${Ansi.reset}")
        Console.err.println(s"${Ansi.dim}${compiler.summary}${Ansi.reset}")
        if opts.keepArtifacts then
          Console.err.println(s"${Ansi.dim}kept ${result.irPath}${Ansi.reset}")
        0
    catch
      case e: SflError =>
        Console.err.println(e.render(Ansi.enabled))
        1
      case e: IncompleteInput =>
        Console.err.println(Err.fromIncomplete(e, SourceRef(name, text)).render(Ansi.enabled))
        1

  @tailrec
  private def parseArgs(args: List[String], acc: Options): Options = args match
    case Nil => acc
    case "-h" :: _ | "--help" :: _ =>
      println(usage)
      sys.exit(0)
    case "-v" :: _ | "--version" :: _ =>
      println(s"sfl ${Sfl.version}")
      sys.exit(0)
    case "--build-runtime" :: rest =>
      parseArgs(rest, acc.copy(buildRuntime = true))
    case "--clean-cache" :: rest =>
      parseArgs(rest, acc.copy(cleanCache = true))
    case "--no-color" :: rest =>
      Ansi.enabled = false
      parseArgs(rest, acc)
    case ("--ast" | "-tasty") :: rest =>
      parseArgs(rest, acc.copy(ast = true))
    case "--time" :: rest =>
      parseArgs(rest, acc.copy(time = true))
    case ("-repl" | "--repl") :: rest =>
      parseArgs(rest, acc.copy(repl = true))
    case "-source" :: value :: rest =>
      parseArgs(rest, acc.copy(source = Some(value), scriptArgs = rest.toArray))
    case "-source" :: Nil =>
      throw new IllegalArgumentException("sfl: -source needs a file name")
    case ("-c" | "--compile") :: value :: rest =>
      parseArgs(rest, acc.copy(compile = true, source = Some(value)))
    case ("-c" | "--compile") :: Nil =>
      throw new IllegalArgumentException("sfl: -c needs a file name")
    case "--emit-llvm" :: rest =>
      parseArgs(rest, acc.copy(emitLlvm = true))
    case "-o" :: value :: rest =>
      parseArgs(rest, acc.copy(output = Some(value)))
    case "-o" :: Nil =>
      throw new IllegalArgumentException("sfl: -o needs a path")
    case "--keep" :: rest =>
      parseArgs(rest, acc.copy(keepArtifacts = true))
    case "--pkg-source" :: rest =>
      parseArgs(rest, acc.copy(pkgSource = true))
    case level :: rest if level.length == 3 && level.startsWith("-O") && level(2).isDigit =>
      parseArgs(rest, acc.copy(optLevel = level.drop(1)))
    case ("-e" | "--eval") :: value :: rest =>
      parseArgs(rest, acc.copy(eval = Some(value)))
    case ("-e" | "--eval") :: Nil =>
      throw new IllegalArgumentException("sfl: -e needs some code to evaluate")
    case arg :: rest if arg.startsWith("-") && arg.length > 1 =>
      throw new IllegalArgumentException(s"sfl: unknown option '$arg'")
    case script :: rest =>
      // The first bare word is the script; everything after it belongs to the script.
      acc.copy(source = Some(script), scriptArgs = rest.toArray)
