package com.fartech.sfl

import java.io.File
import scala.collection.mutable

/**
 * The editor-facing side of the CLI: `sfl syntax` and `sfl check`.
 *
 * Both exist so external tooling — the VSCode and IntelliJ plugins, the TextMate
 * grammar generator, CI hooks — can ask the binary about the language instead of
 * keeping a private copy of its shape. Anything duplicated out there drifts the
 * day an operator is added; anything read from here cannot.
 */
object IdeTool:

  // -------------------------------------------------------------------------
  // sfl syntax — the language surface as one JSON document
  // -------------------------------------------------------------------------

  /** Bracket pairs are structural rather than lexical, so they are named here once. */
  private val bracketPairs = Seq("(" -> ")", "[" -> "]", "{" -> "}")

  def syntaxCli(args: List[String]): Int =
    if args.nonEmpty then
      Console.err.println(s"${Ansi.red}sfl syntax: takes no arguments${Ansi.reset}")
      return 2
    Sfl.init() // the builtin registry fills on first use, and the dump reads it
    val brackets = bracketPairs.flatMap((a, b) => Seq(a, b)).toSet
    val obj = VObj.of(Seq(
      "version" -> VStr(Sfl.version),
      "keywords" -> strArr(Tok.keywords.keys.toSeq.sorted),
      "operators" -> strArr(Tok.symbolSpellings.filterNot(brackets.contains).distinct),
      "brackets" -> VArr.of(bracketPairs.map((a, b) => strArr(Seq(a, b)))),
      "lineComment" -> VStr("//"),
      "blockComment" -> strArr(Seq("/*", "*/")),
      "stringQuotes" -> strArr(Seq("\"", "'")),
      "tripleQuote" -> VStr("\"\"\""),
      "interpolation" -> strArr(Seq("${", "}")),
      "builtins" -> VArr.of(Builtins.all.map(b => VObj.of(Seq(
        "name" -> VStr(b.name),
        "group" -> VStr(b.group),
        "signature" -> VStr(b.signature),
        "doc" -> VStr(b.doc),
        "minArity" -> VInt.of(b.minArity),
        "maxArity" -> VInt.of(b.maxArity)
      ))))
    ))
    println(Json.stringify(obj, pretty = true))
    0

  // -------------------------------------------------------------------------
  // sfl check — parse, report, never run
  // -------------------------------------------------------------------------

  private val checkUsage =
    s"""${Ansi.bold}sfl check${Ansi.reset} — parse files and report problems without running them
       |
       |${Ansi.bold}Usage${Ansi.reset}
       |  sfl check [--json] <file.sfl> [more files…]
       |
       |Exit code 0 when everything parses, 1 when problems were found. With --json
       |the diagnostics go to stdout as one JSON object; without it they render the
       |way runtime errors do. Imports are resolved and parsed, so a problem inside
       |an imported module is reported at its own file and line.
       |""".stripMargin

  /** One problem to report: a located language error, or a plain file-level failure. */
  private enum Diag:
    case Located(e: SflError)
    case Plain(file: String, message: String)

  def checkCli(args: List[String]): Int =
    var json = false
    val files = List.newBuilder[String]
    @annotation.tailrec
    def parse(rest: List[String]): Option[Int] = rest match
      case Nil                  => None
      case "--json" :: t        => json = true; parse(t)
      case ("-h" | "--help") :: _ => println(checkUsage); Some(0)
      case s :: _ if s.startsWith("-") =>
        Console.err.println(s"${Ansi.red}sfl check: unknown option '$s'${Ansi.reset}")
        Some(2)
      case s :: t               => files += s; parse(t)
    parse(args) match
      case Some(code) => return code
      case None       => ()
    val list = files.result()
    if list.isEmpty then
      Console.err.println(checkUsage)
      return 2

    val diags = list.flatMap(checkOne)
    if json then println(Json.stringify(report(diags), pretty = false))
    else
      for d <- diags do d match
        case Diag.Located(e)       => Console.err.println(e.render(Ansi.enabled))
        case Diag.Plain(file, msg) => Console.err.println(s"${Ansi.red}$file: $msg${Ansi.reset}")
    if diags.isEmpty then 0 else 1

  /**
   * Parses one file with a scope and importer of its own. Nothing here touches
   * [[Sfl.globals]]: an editor asks about files in any order and any state, and two
   * checks must never see each other's names — or worse, each other's package
   * version bindings.
   */
  private def checkOne(path: String): List[Diag] =
    val f = new File(path)
    if !f.isFile then return List(Diag.Plain(path, "cannot open the file"))
    val text =
      try FileUtil.read(f)
      catch case e: java.io.IOException => return List(Diag.Plain(path, e.getMessage))
    val ref = SourceRef(path, text)
    val importer = new Importer(if f.getParentFile != null then f.getParentFile else new File("."))
    try
      new Parser(ref, new Globals, importer, false, mutable.HashMap.empty[String, String]).parseProgram()
      Nil
    catch
      case e: SflError          => List(Diag.Located(e))
      case e: IncompleteInput   => List(Diag.Located(Err.fromIncomplete(e, ref)))
      case e: PkgTool.PkgError  => List(Diag.Plain(path, e.getMessage))

  private def report(diags: List[Diag]): VObj =
    VObj.of(Seq(
      "version" -> VStr(Sfl.version),
      "diagnostics" -> VArr.of(diags.map(diagJson))
    ))

  /**
   * The JSON shape of one diagnostic. `line`/`col` are 1-based as everywhere else in
   * the language; a `span` of 0 means "to the end of the line", matching what the
   * human renderer does with it. The file comes from the error's own source, not the
   * command line, because the problem may live in an imported module.
   */
  private def diagJson(d: Diag): VObj = d match
    case Diag.Located(e) =>
      VObj.of(Seq(
        "file" -> VStr(e.source.name),
        "line" -> VInt.of(e.pos.line),
        "col" -> VInt.of(e.pos.col),
        "span" -> VInt.of(e.span),
        "kind" -> VStr(e.kind),
        "severity" -> VStr("error"),
        "message" -> VStr(e.getMessage),
        "notes" -> strArr(e.notes)
      ))
    case Diag.Plain(file, msg) =>
      VObj.of(Seq(
        "file" -> VStr(file),
        "line" -> VInt.of(0),
        "col" -> VInt.of(0),
        "span" -> VInt.of(0),
        "kind" -> VStr("IO error"),
        "severity" -> VStr("error"),
        "message" -> VStr(msg),
        "notes" -> strArr(Nil)
      ))

  private def strArr(ss: Seq[String]): VArr = VArr.of(ss.map(VStr(_)))
