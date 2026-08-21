package com.fartech.sfl

import java.io.{BufferedInputStream, File}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

/**
 * `sfl lsp` — a Language Server Protocol server over stdio.
 *
 * The server lives inside the sfl binary for the same reason the compiler does:
 * one process, native start-up, and no second implementation of the language to
 * keep honest. The editor plugins are thin; everything they know, they learn here.
 *
 * The protocol work is deliberately plain: JSON-RPC framed with Content-Length
 * headers, parsed with the same [[Json]] the language exposes, one request at a
 * time. An LSP server earns its keep by answering correctly at every keystroke,
 * not by answering four requests concurrently.
 *
 * What it offers today: parse diagnostics, completion (builtins, keywords, and
 * the file's own definitions), hover and signature help for builtins. The deeper
 * features — go-to-definition, rename, semantic tokens — need the front end to
 * keep ranges and comments, which is tracked as the language-service rebuild.
 */
object Lsp:

  def cli(args: List[String]): Int =
    if args.nonEmpty then
      Console.err.println(s"${Ansi.red}sfl lsp: takes no arguments (the editor owns this process)${Ansi.reset}")
      return 2
    Sfl.init() // completion and hover read the builtin registry
    new Lsp().serve()

final class Lsp:
  private val in = new BufferedInputStream(System.in)
  /** Open documents, newest text wins: uri -> content. */
  private val docs = mutable.HashMap.empty[String, String]
  private var running = true
  private var shutdownSeen = false

  // -------------------------------------------------------------------------
  // The loop
  // -------------------------------------------------------------------------

  def serve(): Int =
    while running do
      readMessage() match
        case None => running = false // stdin closed: the editor is gone
        case Some(msg: VObj) =>
          val id = msg.fields.get("id")
          msg.fields.get("method") match
            case Some(VStr(method)) =>
              id match
                case Some(reqId) => request(method, reqId, param(msg))
                case None        => notification(method, param(msg))
            case _ => () // a response or garbage; this server never sends requests
        case Some(_) => () // unparsable body, already skipped
    // Per the protocol: exiting without a shutdown request is an abnormal end.
    if shutdownSeen then 0 else 1

  private def param(msg: VObj): Value = msg.fields.getOrElse("params", VNull)

  private def request(method: String, id: Value, params: Value): Unit =
    try
      method match
        case "initialize"                 => respond(id, initializeResult())
        case "shutdown"                   => shutdownSeen = true; respond(id, VNull)
        case "textDocument/completion"    => respond(id, completion(params))
        case "textDocument/hover"         => respond(id, hover(params))
        case "textDocument/signatureHelp" => respond(id, signatureHelp(params))
        case "textDocument/documentSymbol" => respond(id, documentSymbols(params))
        case "textDocument/definition"    => respond(id, definition(params))
        case "textDocument/declaration"   => respond(id, definition(params))
        case "textDocument/implementation" => respond(id, definition(params))
        case "textDocument/typeDefinition" => respond(id, definition(params))
        case "textDocument/references"    => respond(id, references(params))
        case other => respondError(id, -32601, s"method '$other' is not supported")
    catch case e: Throwable =>
      // One bad request must not take the server down with it.
      respondError(id, -32603, s"internal error in $method: ${e.getMessage}")

  private def notification(method: String, params: Value): Unit =
    try
      method match
        case "initialized"             => ()
        case "exit"                    => running = false
        case "textDocument/didOpen" =>
          val uri = getStr(params, "textDocument", "uri")
          docs(uri) = getStr(params, "textDocument", "text")
          publish(uri)
        case "textDocument/didChange" =>
          val uri = getStr(params, "textDocument", "uri")
          // Sync is full-document, so the last change in the batch is the document.
          get(params, "contentChanges") match
            case arr: VArr if arr.items.nonEmpty =>
              docs(uri) = getStr(arr.items.last, "text")
              publish(uri)
            case _ => ()
        case "textDocument/didClose" =>
          val uri = getStr(params, "textDocument", "uri")
          docs.remove(uri)
          // Closed files keep stale squiggles in some editors unless cleared.
          notifyClient("textDocument/publishDiagnostics",
            VObj.of(Seq("uri" -> VStr(uri), "diagnostics" -> VArr.empty)))
        case _ => () // $/setTrace, willSave, and anything else we did not ask for
    catch case e: Throwable =>
      Console.err.println(s"sfl lsp: $method failed: ${e.getMessage}")

  // -------------------------------------------------------------------------
  // Wire format
  // -------------------------------------------------------------------------

  /** Reads one framed message; None at end of input, Some(VNull) for a bad body. */
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
    if line == null then return None
    if contentLength < 0 || contentLength > 64 * 1024 * 1024 then return None
    val buf = new Array[Byte](contentLength)
    var off = 0
    while off < contentLength do
      val n = in.read(buf, off, contentLength - off)
      if n < 0 then return None
      off += n
    try Some(Json.parse(new String(buf, UTF_8)))
    catch case _: SflError => Some(VNull)

  /** One header line without its terminator; null when input ends first. */
  private def readHeaderLine(): String =
    val sb = new StringBuilder
    var c = in.read()
    if c < 0 then return null
    while c >= 0 && c != '\n' do
      if c != '\r' then sb.append(c.toChar)
      c = in.read()
    if c < 0 then null else sb.toString

  private def respond(id: Value, result: Value): Unit =
    write(VObj.of(Seq("jsonrpc" -> VStr("2.0"), "id" -> id, "result" -> result)))

  private def respondError(id: Value, code: Int, message: String): Unit =
    write(VObj.of(Seq("jsonrpc" -> VStr("2.0"), "id" -> id,
      "error" -> VObj.of(Seq("code" -> VInt.of(code), "message" -> VStr(message))))))

  private def notifyClient(method: String, params: Value): Unit =
    write(VObj.of(Seq("jsonrpc" -> VStr("2.0"), "method" -> VStr(method), "params" -> params)))

  private def write(v: Value): Unit =
    val body = Json.stringify(v, pretty = false).getBytes(UTF_8)
    val header = s"Content-Length: ${body.length}\r\n\r\n".getBytes(UTF_8)
    System.out.write(header)
    System.out.write(body)
    System.out.flush()

  // -------------------------------------------------------------------------
  // Diagnostics
  // -------------------------------------------------------------------------

  private def publish(uri: String): Unit =
    val text = docs.getOrElse(uri, "")
    val path = uriToPath(uri)
    notifyClient("textDocument/publishDiagnostics",
      VObj.of(Seq("uri" -> VStr(uri), "diagnostics" -> VArr.of(analyze(path, text)))))

  /**
   * Parses the document as `sfl check` would: a scope and importer of its own, so
   * open editors never contaminate each other or the process. The text comes from
   * the editor, not the disk — it is usually unsaved.
   */
  private def analyze(path: String, text: String): List[Value] =
    val ref = SourceRef(path, text)
    val base = new File(path).getParentFile
    val importer = new Importer(if base != null then base else new File("."))
    val intel = new ParseIntel
    val globals = new Globals
    Builtins.install(globals)
    // The namespace this file declares into, so a name that landed in some other
    // one — a package imported but not opened — still reads as out of scope.
    intel.rootTag = importer.namespaceOf(path)._1
    try
      new Parser(ref, globals, importer, null,
        mutable.HashMap.empty[String, String], intel).parseProgram()
      val out = mutable.ArrayBuffer.empty[Value]
      for e <- intel.importErrors do out += lspDiagnostic(e, path, text)
      // Undefined-name checking only when every import resolved: a missing
      // module legitimately leaves its names undefined, and flagging each use
      // would bury the one diagnostic that matters.
      if intel.importErrors.isEmpty && undefinedCheckOn then
        out ++= undefinedDiagnostics(intel, path, text)
      out.toList
    catch
      case e: SflError         => List(lspDiagnostic(e, path, text))
      case e: IncompleteInput  => List(lspDiagnostic(Err.fromIncomplete(e, ref), path, text))
      case e: PkgTool.PkgError => List(plainDiagnostic(e.getMessage))

  /** SFL_LSP_UNDEFINED=off silences the semantic pass; the plugins set it from
    * their settings pages. */
  private val undefinedCheckOn = !sys.env.get("SFL_LSP_UNDEFINED").contains("off")

  /**
   * Reads of globals that nothing defines: not this program (imports included —
   * the collector saw their declarations while they were inlined), not the
   * builtin table. Exactly the reads the interpreter would refuse at run time.
   */
  private def undefinedDiagnostics(intel: ParseIntel, path: String, text: String): Seq[Value] =
    val builtins = Builtins.names.toSet
    val dsl = if isBuildFile(path) then buildDsl.map(_.name).toSet else Set.empty[String]
    val seen = mutable.HashSet.empty[(String, Int)]
    val out = mutable.ArrayBuffer.empty[Value]
    for r <- intel.refs do
      if out.length < 100 && r.file == path
        && !builtins.contains(r.name) && !intel.isDefined(r.name) && !dsl.contains(r.name)
        && !Tok.reserved.contains(r.name) && seen.add((r.name, r.line))
      then
        val line0 = math.max(0, r.line - 1)
        val col = columnOfWord(text, line0, r.name)
        val hint = Err.suggest(r.name, intel.definedNames ++ Builtins.publicNames)
          .map(sug => s"\nhelp: did you mean '$sug'?").getOrElse("")
        out += diagnostic(line0, col, line0, col + r.name.length,
          s"undefined variable '${r.name}'$hint", "Runtime error")
    out.toSeq

  /** First word-boundary occurrence of `word` in the given line, else column 0. */
  private def columnOfWord(text: String, line0: Int, word: String): Int =
    var i = 0
    var l = 0
    while l < line0 && i < text.length do
      if text.charAt(i) == '\n' then l += 1
      i += 1
    var j = i
    while j < text.length && text.charAt(j) != '\n' do j += 1
    val lineText = text.substring(i, j)
    var at = lineText.indexOf(word)
    while at >= 0 do
      val beforeOk = at == 0 || !isIdentChar(lineText.charAt(at - 1))
      val end = at + word.length
      val afterOk = end >= lineText.length || !isIdentChar(lineText.charAt(end))
      if beforeOk && afterOk then return at
      at = lineText.indexOf(word, at + 1)
    0

  private def lspDiagnostic(e: SflError, path: String, text: String): Value =
    val local = e.source.name == path
    val message =
      val base = if local then e.getMessage else s"in '${e.source.name}': ${e.getMessage}"
      base + e.notes.map(n => s"\nhelp: $n").mkString
    if !local || !e.hasLocation then plainDiagnostic(message)
    else
      val line = e.pos.line - 1
      val col = math.max(0, e.pos.col - 1)
      // span 0 means "the whole line", matching the human renderer's caret rule.
      val endCol = if e.span > 0 then col + e.span else math.max(col + 1, lineLength(text, line))
      diagnostic(line, col, line, endCol, message, e.kind)

  private def plainDiagnostic(message: String): Value =
    diagnostic(0, 0, 0, 1, message, "error")

  private def diagnostic(l0: Int, c0: Int, l1: Int, c1: Int, message: String, kind: String): Value =
    VObj.of(Seq(
      "range" -> VObj.of(Seq(
        "start" -> VObj.of(Seq("line" -> VInt.of(l0), "character" -> VInt.of(c0))),
        "end" -> VObj.of(Seq("line" -> VInt.of(l1), "character" -> VInt.of(c1)))
      )),
      "severity" -> VInt.of(1),
      "source" -> VStr("sfl"),
      "code" -> VStr(kind),
      "message" -> VStr(message)
    ))

  private def lineLength(text: String, line0: Int): Int =
    var i = 0
    var l = 0
    while l < line0 && i < text.length do
      if text.charAt(i) == '\n' then l += 1
      i += 1
    var j = i
    while j < text.length && text.charAt(j) != '\n' do j += 1
    j - i

  // -------------------------------------------------------------------------
  // Completion, hover, signature help
  // -------------------------------------------------------------------------

  /** `def name(params)` and `val/var name =` in the open file. The front end does
    * not keep definition sites yet, so the same regex trick the library reference
    * uses (see [[Stdlib]]) serves until the language service lands. */
  private val DefRe = raw"(?m)^\s*def\s+([A-Za-z_$$][A-Za-z0-9_$$]*)\s*\(([^)]*)\)".r
  private val ValRe = raw"(?m)^\s*(?:val|var)\s+([A-Za-z_$$][A-Za-z0-9_$$]*)\s*=".r
  /** The surface of another module: column-0 definitions only, `_`-private excluded. */
  private val ModuleDefRe = raw"(?m)^def\s+([A-Za-z_$$][A-Za-z0-9_$$]*)\s*\(([^)]*)\)".r
  private val ModuleValRe = raw"(?m)^(?:val|var)\s+([A-Za-z_$$][A-Za-z0-9_$$]*)\s*=".r
  // One regex for every import form. RE2 (Scala Native's regex) has no lookahead,
  // so the optional alias and `open` are matched as groups and read afterwards.
  private val ImportRe =
    raw"""import\s+"([^"]+)"(?:\s+as\s+([A-Za-z_$$][A-Za-z0-9_$$]*))?(\s+open\b)?""".r
  private def imports(text: String): Iterator[scala.util.matching.Regex.Match] =
    ImportRe.findAllMatchIn(text)
  /** Imports whose surface lands in this file's scope unqualified. */
  private def plainImports(path: String, text: String): Iterator[scala.util.matching.Regex.Match] =
    imports(text).filter(m =>
      m.group(3) != null || (m.group(2) == null && !isUnitImport(path, m.group(1))))
  private val ObjectKeyRe = raw""""([A-Za-z_$$][A-Za-z0-9_$$]*)"\s*:""".r

  /**
   * Completion is context-first: inside an `import "` string it offers module
   * paths, right after `recv.` it offers that receiver's members, and only in
   * plain code does the flat list of definitions, builtins, keywords and
   * snippets appear. A trigger character that fired anywhere else — the `/` of
   * a division, the quote of an ordinary string, the dot of a float — answers
   * with an empty list rather than noise.
   */
  private def completion(params: Value): Value =
    val uri = getStr(params, "textDocument", "uri")
    val text = docs.getOrElse(uri, "")
    val path = uriToPath(uri)
    val off = offsetOf(text, getInt(params, "position", "line"), getInt(params, "position", "character"))
    importPrefixAt(text, off) match
      case Some(prefix) => VArr.of(importPathItems(path, prefix))
      case None =>
        memberReceiverAt(text, off) match
          case Some(recv) => VArr.of(memberItems(path, text, recv))
          case None =>
            val prev = if off > 0 then text.charAt(off - 1) else ' '
            if prev == '.' || prev == '"' || prev == '/' then VArr.empty
            else globalItems(path, text)

  private def globalItems(path: String, text: String): Value =
    val items = mutable.LinkedHashMap.empty[String, Value]
    for m <- DefRe.findAllMatchIn(text) do
      val name = m.group(1)
      items(name) = completionItem(name, 3, s"def $name(${m.group(2)})", "", "1")
    for m <- ValRe.findAllMatchIn(text) do
      val name = m.group(1)
      if !items.contains(name) then items(name) = completionItem(name, 6, name, "", "1")
    if isBuildFile(path) then
      for m <- buildDsl do
        items(m.name) = completionItem(m.name, m.kind, s"${m.detail} — sfl build DSL", "", "0")
    // An opened import puts its public names in scope, so completion owes them.
    // One transitive level covers a module whose own imports it re-exports.
    for (mod, m) <- plainSurfaces(path, text) if !items.contains(m.name) do
      items(m.name) = completionItem(m.name, m.kind, s"${m.detail} — $mod", "", "1")
    // A namespace is not a name in scope but it is what the reader types next:
    // every alias this file bound, and the builtin namespaces that need none.
    for (alias, spec) <- docAliases(path, text) if !items.contains(alias) do
      items(alias) = completionItem(alias, 9, s"namespace — $spec", "", "1")
    for ns <- Builtins.namespaceNames if !items.contains(ns) do
      items(ns) = completionItem(ns, 9,
        if ns == Builtins.StdNamespace then "namespace — every builtin"
        else s"namespace — the $ns builtins", "", "2")
    for b <- Builtins.all if !items.contains(b.name) do
      items(b.name) = completionItem(b.name, 3, b.signature, b.doc, "2")
    for kw <- Tok.keywords.keys.toSeq.sorted if !items.contains(kw) do
      items(kw) = completionItem(kw, 14, kw, "", "3")
    for (label, insert, detail) <- snippets do
      items("snippet:" + label) = snippetItem(label, insert, detail)
    VArr.of(items.values)

  /** Small structural templates; $0/$1 are LSP snippet placeholders. */
  private val snippets = Seq(
    ("def", "def ${1:name}(${2:args}) {\n\t$0\n}", "def name(args) { … }"),
    ("for", "for (${1:x} in ${2:xs}) {\n\t$0\n}", "for (x in xs) { … }"),
    ("while", "while (${1:cond}) {\n\t$0\n}", "while (cond) { … }"),
    ("select", "select (${1:value}) {\n\tcase ${2:pattern}: $0\n\tdefault: null\n}",
      "select (v) { case …: … }"),
    ("task", "task(\"${1:name}\", (args) -> {\n\t$0\n}, \"${2:doc}\")",
      "task(name, fn, doc) — build.sfl")
  )

  private def snippetItem(label: String, insert: String, detail: String): Value =
    new VObj(mutable.LinkedHashMap[String, Value](
      "label" -> VStr(label),
      "kind" -> VInt.of(15), // snippet
      "detail" -> VStr(detail),
      "sortText" -> VStr("5" + label),
      "insertText" -> VStr(insert),
      "insertTextFormat" -> VInt.of(2)
    ))

  private def completionItem(label: String, kind: Int, detail: String, doc: String, rank: String): Value =
    val fields = mutable.LinkedHashMap[String, Value](
      "label" -> VStr(label),
      "kind" -> VInt.of(kind),
      "detail" -> VStr(detail),
      "sortText" -> VStr(rank + label)
    )
    if doc.nonEmpty then fields("documentation") = VStr(doc)
    new VObj(fields)

  // -------------------------------------------------------------------------
  // Context detection: what is the cursor actually in the middle of?
  // -------------------------------------------------------------------------

  /**
   * `recv` when the cursor sits right after `recv.` or `recv?.` plus any partly
   * typed member name. Numeric receivers — the dot of `1.` — are not members.
   */
  private def memberReceiverAt(text: String, off: Int): Option[String] =
    var i = math.min(off, text.length)
    while i > 0 && isIdentChar(text.charAt(i - 1)) do i -= 1 // the partial word
    if i == 0 || text.charAt(i - 1) != '.' then return None
    var end = i - 1 // at the '.', receiver ends before it
    if end > 0 && text.charAt(end - 1) == '?' then end -= 1
    var s = end
    while s > 0 && isIdentChar(text.charAt(s - 1)) do s -= 1
    if s == end then return None
    val recv = text.substring(s, end)
    if recv.charAt(0).isDigit then None else Some(recv)

  /** The path typed so far when the cursor is inside `import "…`. */
  private def importPrefixAt(text: String, off: Int): Option[String] =
    var ls = math.min(off, text.length)
    while ls > 0 && text.charAt(ls - 1) != '\n' do ls -= 1
    val line = text.substring(ls, math.min(off, text.length))
    raw"""\bimport\s+"([^"]*)$$""".r.findFirstMatchIn(line).map(_.group(1))

  // -------------------------------------------------------------------------
  // Member completion: module aliases first, object keys second
  // -------------------------------------------------------------------------

  /**
   * Every namespace this file can write `ns.` in front of, mapped to the import
   * spec it came from. An `as` alias names one; so does a plain import, under
   * whatever the module calls itself — the package's manifest name, or the file's
   * own name. The builtin namespaces need no import and are handled separately,
   * by [[builtinNamespaceItems]].
   */
  private def docAliases(path: String, text: String): Map[String, String] =
    val out = mutable.LinkedHashMap.empty[String, String]
    for m <- imports(text) do
      val spec = m.group(1)
      val alias = if m.group(2) != null then m.group(2) else defaultAlias(path, spec)
      if alias.nonEmpty then out(alias) = spec
    out.toMap

  /** What a plain import of `spec` binds it as: its unit's name, or the file's. */
  private def defaultAlias(path: String, spec: String): String =
    unitOf(path, spec) match
      case Some((_, name)) => name
      case None =>
        val base = spec.split('/').last
        if base.endsWith(".sfl") then base.dropRight(4) else base

  /**
   * The manifest unit a file belongs to: the nearest directory at or above it that
   * carries an `sfl.pkg`, and the name that manifest declares. Cached, because
   * completion asks after every import on every keystroke.
   */
  private val unitCache = mutable.HashMap.empty[String, Option[(File, String)]]

  private def unitAbove(file: File): Option[(File, String)] =
    // Canonical first: `new File("a.sfl").getParentFile` is null, and the walk
    // would stop before it ever reached the project root.
    val start =
      try file.getCanonicalFile.getParentFile
      catch case _: java.io.IOException => file.getAbsoluteFile.getParentFile
    if start == null then None
    else
      unitCache.getOrElseUpdate(start.getPath, {
        var dir = start
        var found: Option[(File, String)] = None
        var depth = 0
        while dir != null && found.isEmpty && depth < 24 do
          if PkgTool.manifestFile(dir).isFile then
            found = try
              val name = PkgTool.readManifest(dir).name
              if name.nonEmpty then Some((dir, name)) else None
            catch case _: Throwable => None
          dir = dir.getParentFile
          depth += 1
        found
      })

  /** The unit an import spec resolves into, seen from `path`. */
  private def unitOf(path: String, spec: String): Option[(File, String)] =
    resolveModule(path, spec).flatMap(unitAbove)

  private def isUnitImport(path: String, spec: String): Boolean =
    // A sibling module of the file's own unit is not "another unit": it is already
    // in scope, and treating it as one would hide its names from completion.
    unitOf(path, spec).exists((dir, _) => !sameUnit(path, dir))

  private def sameUnit(path: String, dir: File): Boolean =
    unitAbove(new File(path)) match
      case Some((own, _)) => own.getAbsolutePath == dir.getAbsolutePath
      case None           => false

  /**
   * A unit's whole surface, not just the imported module's: a package is one
   * namespace, so `httpd.` offers what any of its files declares.
   */
  private def unitSurface(dir: File): List[Member] =
    val out = mutable.LinkedHashMap.empty[String, Member]
    def walk(d: File, depth: Int): Unit =
      if d == null || depth > 3 then return
      for f <- Option(d.listFiles()).getOrElse(Array.empty[File]).sortBy(_.getName) do
        if f.isDirectory && f.getName != "bin" && !f.getName.startsWith(".") then walk(f, depth + 1)
        else if f.getName.endsWith(".sfl") then
          val src = try FileUtil.read(f) catch case _: Throwable => ""
          for m <- ModuleDefRe.findAllMatchIn(src) if !m.group(1).startsWith("_") do
            out.getOrElseUpdate(m.group(1),
              Member(m.group(1), 3, s"def ${m.group(1)}(${m.group(2)})", lineOfOffset(src, m.start)))
          for m <- ModuleValRe.findAllMatchIn(src) if !m.group(1).startsWith("_") do
            out.getOrElseUpdate(m.group(1),
              Member(m.group(1), 6, s"val ${m.group(1)}", lineOfOffset(src, m.start)))
    walk(dir, 0)
    out.values.toList

  /** The members `recv.` offers, whether `recv` is a builtin namespace or a module. */
  private def namespaceMembers(path: String, text: String, recv: String): List[Member] =
    docAliases(path, text).get(recv) match
      case Some(spec) =>
        unitOf(path, spec) match
          case Some((dir, _)) => unitSurface(dir)
          case None           => moduleSurface(path, text, spec)
      case None => Nil

  /** `std.` and one namespace per builtin group, always available. */
  private def builtinNamespaceItems(recv: String): Seq[Value] =
    if !Builtins.isNamespace(recv) then Nil
    else
      val members =
        if recv == Builtins.StdNamespace then Builtins.all else Builtins.all.filter(_.group == recv)
      members.map(b => completionItem(b.name, 3, s"${b.signature} — $recv", b.doc, "0"))

  private def memberItems(path: String, text: String, recv: String): Seq[Value] =
    val ns = namespaceMembers(path, text, recv)
    if ns.nonEmpty then
      val label = docAliases(path, text).getOrElse(recv, recv)
      ns.map(m => completionItem(m.name, m.kind, s"${m.detail} — $label", "", "0"))
    else
      val builtin = builtinNamespaceItems(recv)
      if builtin.nonEmpty then builtin
      else
        val specific = receiverKeys(text, recv)
        val (keys, note) =
          if specific.nonEmpty then (specific, s"field of $recv")
          else
            // No literal in sight: fall back to every object key this file
            // spells, which is usually the right shape and honestly labelled.
            (ObjectKeyRe.findAllMatchIn(text).map(_.group(1)).toSeq.distinct,
              "object key in this file")
        keys.map(k => completionItem(k, 5, note, "", "0"))

  /** Keys provably attached to `recv` in this file: its literal assignment,
    * `recv.k = …`, `recv["k"] = …` and `set(recv, "k", …)`. */
  private def receiverKeys(text: String, recv: String): Seq[String] =
    val q = java.util.regex.Pattern.quote(recv)
    val out = mutable.LinkedHashSet.empty[String]
    for m <- raw"""(?m)(?:\b(?:val|var)\s+)?$q\s*=\s*\{""".r.findAllMatchIn(text) do
      out ++= braceKeys(text, m.end - 1)
    for m <- raw"""\b$q\.([A-Za-z_$$][A-Za-z0-9_$$]*)\s*=(?:[^=]|$$)""".r.findAllMatchIn(text) do
      out += m.group(1)
    for m <- raw"""\b$q\["([A-Za-z_$$][A-Za-z0-9_$$]*)"\]\s*=(?:[^=]|$$)""".r.findAllMatchIn(text) do
      out += m.group(1)
    for m <- raw"""\bset\(\s*$q\s*,\s*"([A-Za-z_$$][A-Za-z0-9_$$]*)"""".r.findAllMatchIn(text) do
      out += m.group(1)
    out.toSeq

  /** Top-level string keys of the object literal opening at `openIdx`. */
  private def braceKeys(text: String, openIdx: Int): Seq[String] =
    val out = mutable.ArrayBuffer.empty[String]
    var depth = 0
    var i = openIdx
    while i < text.length do
      val c = text.charAt(i)
      if c == '"' || c == '\'' then
        val start = i
        i += 1
        while i < text.length && text.charAt(i) != c do
          if text.charAt(i) == '\\' then i += 1
          i += 1
        if depth == 1 && c == '"' then
          var j = i + 1
          while j < text.length && text.charAt(j) == ' ' do j += 1
          if j < text.length && text.charAt(j) == ':' then
            val key = text.substring(start + 1, math.min(i, text.length))
            if key.nonEmpty && !key.charAt(0).isDigit && key.forall(isIdentChar) then out += key
      else if c == '{' || c == '[' || c == '(' then depth += 1
      else if c == '}' || c == ']' || c == ')' then
        depth -= 1
        if depth <= 0 then return out.toSeq
      i += 1
    out.toSeq

  // -------------------------------------------------------------------------
  // Other modules: resolution via the real importer, surface via regex, cached
  // -------------------------------------------------------------------------

  private final case class Member(name: String, kind: Int, detail: String, line: Int)
  private val moduleCache = mutable.HashMap.empty[String, (Long, List[Member])]

  /** Resolves an import string the way `import` itself would, packages included. */
  private def resolveModule(fromPath: String, name: String): Option[File] =
    val base = new File(fromPath).getParentFile
    val importer = new Importer(if base != null then base else new File("."))
    val ref = SourceRef(fromPath, "")
    importer.resolve(name, ref).orElse {
      try Some(new File(importer.pathOf(name, ref, Pos.none)))
      catch case _: Throwable => None
    }

  private def moduleSurface(fromPath: String, text: String, module: String): List[Member] =
    resolveModule(fromPath, module) match
      case None => Nil
      case Some(file) =>
        val key = file.getAbsolutePath
        val mtime = file.lastModified()
        moduleCache.get(key) match
          case Some((t, members)) if t == mtime => members
          case _ =>
            val src = try FileUtil.read(file) catch case _: Throwable => ""
            val members = mutable.LinkedHashMap.empty[String, Member]
            var line = 0
            for m <- ModuleDefRe.findAllMatchIn(src) if !m.group(1).startsWith("_") do
              members(m.group(1)) =
                Member(m.group(1), 3, s"def ${m.group(1)}(${m.group(2)})", lineOfOffset(src, m.start))
            for m <- ModuleValRe.findAllMatchIn(src) if !m.group(1).startsWith("_") do
              if !members.contains(m.group(1)) then
                members(m.group(1)) = Member(m.group(1), 6, s"val ${m.group(1)}", lineOfOffset(src, m.start))
            val list = members.values.toList
            moduleCache(key) = (mtime, list)
            list

  /** Where a unit declares `name`: the file and the line, searched unit-wide. */
  private def unitDefinitionOf(dir: File, name: String): Option[(File, Int)] =
    def walk(d: File, depth: Int): Option[(File, Int)] =
      if d == null || depth > 3 then None
      else
        val entries = Option(d.listFiles()).getOrElse(Array.empty[File]).sortBy(_.getName)
        entries.iterator.flatMap { f =>
          if f.isDirectory && f.getName != "bin" && !f.getName.startsWith(".") then walk(f, depth + 1)
          else if f.getName.endsWith(".sfl") then
            val src = try FileUtil.read(f) catch case _: Throwable => ""
            ModuleDefRe.findAllMatchIn(src).find(_.group(1) == name)
              .orElse(ModuleValRe.findAllMatchIn(src).find(_.group(1) == name))
              .map(m => (f, lineOfOffset(src, m.start)))
          else None
        }.nextOption()
    walk(dir, 0)

  /** Public members of every opened module, one transitive level deep. */
  private def plainSurfaces(path: String, text: String): Seq[(String, Member)] =
    val out = mutable.ArrayBuffer.empty[(String, Member)]
    val visited = mutable.HashSet.empty[String]
    def walk(fromPath: String, fromText: String, depth: Int): Unit =
      if depth > 2 then return
      for im <- plainImports(fromPath, fromText) do
        val mod = im.group(1)
        resolveModule(fromPath, mod) match
          case Some(file) if visited.add(file.getAbsolutePath) =>
            for member <- moduleSurface(fromPath, fromText, mod) do out += ((mod, member))
            val srcText = try FileUtil.read(file) catch case _: Throwable => ""
            walk(file.getAbsolutePath, srcText, depth + 1)
          case _ => ()
    walk(path, text, 1)
    out.toSeq

  /**
   * The build tool defines project() and task() before it evaluates build.sfl,
   * so inside that one file those names are real. Their surface is read from
   * the build tool source embedded in this binary — the very code `sfl build`
   * runs — so the DSL and this list cannot drift.
   */
  private lazy val buildDsl: List[Member] =
    val src = BuildToolSources.text("main.sfl")
    val out = mutable.LinkedHashMap.empty[String, Member]
    for m <- ModuleDefRe.findAllMatchIn(src) if !m.group(1).startsWith("_") do
      out(m.group(1)) =
        Member(m.group(1), 3, s"def ${m.group(1)}(${m.group(2)})", lineOfOffset(src, m.start))
    for m <- ModuleValRe.findAllMatchIn(src) if !m.group(1).startsWith("_") do
      if !out.contains(m.group(1)) then
        out(m.group(1)) = Member(m.group(1), 6, s"val ${m.group(1)}", lineOfOffset(src, m.start))
    out.values.toList

  private def isBuildFile(path: String): Boolean =
    new File(path).getName == "build.sfl"

  private def lineOfOffset(text: String, off: Int): Int =
    var line = 0
    var i = 0
    val n = math.min(off, text.length)
    while i < n do
      if text.charAt(i) == '\n' then line += 1
      i += 1
    line

  // -------------------------------------------------------------------------
  // Import path completion
  // -------------------------------------------------------------------------

  private def importPathItems(path: String, prefix: String): Seq[Value] =
    val slash = prefix.lastIndexOf('/')
    val dirPart = if slash < 0 then "" else prefix.substring(0, slash)
    val stem = if slash < 0 then prefix else prefix.substring(slash + 1)
    val self = new File(path).getName
    val items = mutable.LinkedHashMap.empty[String, Value]

    def addDirListing(dir: File, origin: String): Unit =
      val kids = dir.listFiles()
      if kids == null then return
      for f <- kids.sortBy(_.getName) do
        val n = f.getName
        if !n.startsWith(".") then
          if f.isFile && n.endsWith(".sfl") && n != self then
            val label = n.stripSuffix(".sfl")
            if label.startsWith(stem) && !items.contains(label) then
              items(label) = completionItem(label, 9, origin, "", "0" + label)
          else if f.isDirectory && n != "build" && n != "sfl_packages" then
            val label = n + "/"
            if n.startsWith(stem) && !items.contains(label) then
              items(label) = completionItem(label, 19, origin, "", "1" + label)

    val base = new File(path).getParentFile
    val libs = new File(sys.env.getOrElse("SFL_HOME", "."), "libs")
    if dirPart.isEmpty then
      if base != null then addDirListing(base, "this directory")
      addDirListing(libs, "SFL_HOME/libs")
      for root <- PkgTool.roots do
        val kids = root.listFiles()
        if kids != null then
          for f <- kids.sortBy(_.getName)
              if f.isDirectory && PkgTool.isValidName(f.getName)
              if PkgTool.installedVersions(root, f.getName).nonEmpty
              if f.getName.startsWith(stem) && !items.contains(f.getName + "/") do
            items(f.getName + "/") = completionItem(f.getName + "/", 9, "installed package", "", "2" + f.getName)
    else
      if base != null then addDirListing(new File(base, dirPart), dirPart)
      addDirListing(new File(libs, dirPart), s"SFL_HOME/libs/$dirPart")
      val pkgName = dirPart.takeWhile(_ != '/')
      val rest = dirPart.drop(pkgName.length).stripPrefix("/")
      for root <- PkgTool.roots do
        val versions = PkgTool.installedVersions(root, pkgName)
        if versions.nonEmpty then
          val vdir = PkgTool.versionDir(root, pkgName, versions.last)
          addDirListing(if rest.isEmpty then vdir else new File(vdir, rest), s"package $pkgName")
    items.values.toSeq

  // -------------------------------------------------------------------------
  // Outline and go-to-definition
  // -------------------------------------------------------------------------

  private def documentSymbols(params: Value): Value =
    val text = docs.getOrElse(getStr(params, "textDocument", "uri"), "")
    val out = mutable.ArrayBuffer.empty[Value]
    def sym(name: String, detail: String, kind: Int, line: Int): Value =
      val range = VObj.of(Seq(
        "start" -> VObj.of(Seq("line" -> VInt.of(line), "character" -> VInt.of(0))),
        "end" -> VObj.of(Seq("line" -> VInt.of(line), "character" -> VInt.of(lineLength(text, line))))))
      VObj.of(Seq(
        "name" -> VStr(name), "detail" -> VStr(detail), "kind" -> VInt.of(kind),
        "range" -> range, "selectionRange" -> range))
    for m <- ModuleDefRe.findAllMatchIn(text) do
      out += sym(m.group(1), s"def ${m.group(1)}(${m.group(2)})", 12, lineOfOffset(text, m.start))
    for m <- ModuleValRe.findAllMatchIn(text) do
      out += sym(m.group(1), "", 13, lineOfOffset(text, m.start))
    VArr.of(out.toSeq)

  private def definition(params: Value): Value =
    val uri = getStr(params, "textDocument", "uri")
    val text = docs.getOrElse(uri, "")
    val path = uriToPath(uri)
    val off = offsetOf(text, getInt(params, "position", "line"), getInt(params, "position", "character"))
    val word = wordAt(text, getInt(params, "position", "line"), getInt(params, "position", "character"))
    if word.isEmpty then return VNull

    def location(targetUri: String, targetText: String, line: Int): Value =
      VObj.of(Seq(
        "uri" -> VStr(targetUri),
        "range" -> VObj.of(Seq(
          "start" -> VObj.of(Seq("line" -> VInt.of(line), "character" -> VInt.of(0))),
          "end" -> VObj.of(Seq("line" -> VInt.of(line),
            "character" -> VInt.of(lineLength(targetText, line))))))))

    def sourceOf(file: File): String =
      try FileUtil.read(file) catch case _: Throwable => ""

    // `alias.word`: the definition lives in the aliased module.
    var s = off
    while s > 0 && isIdentChar(text.charAt(s - 1)) do s -= 1
    if s > 0 && text.charAt(s - 1) == '.' then
      var e = s - 1
      if e > 0 && text.charAt(e - 1) == '?' then e -= 1
      var rs = e
      while rs > 0 && isIdentChar(text.charAt(rs - 1)) do rs -= 1
      val recv = text.substring(rs, e)
      val aliased = docAliases(path, text).get(recv).flatMap { spec =>
        unitOf(path, spec) match
          case Some((dir, _)) =>
            unitDefinitionOf(dir, word).map((file, line) =>
              location(pathToUri(file.getAbsolutePath), sourceOf(file), line))
          case None =>
            for
              member <- moduleSurface(path, text, spec).find(_.name == word)
              file <- resolveModule(path, spec)
            yield location(pathToUri(file.getAbsolutePath), sourceOf(file), member.line)
      }
      // A qualified name answers for itself: if the alias has no such member,
      // the plain searches below would only find an unrelated one.
      return aliased.getOrElse(VNull)

    // This file first, plainly imported modules second.
    DefRe.findAllMatchIn(text).find(_.group(1) == word)
      .orElse(ValRe.findAllMatchIn(text).find(_.group(1) == word))
      .map(m => location(uri, text, lineOfOffset(text, m.start)))
      .orElse(plainImports(path, text).map(_.group(1)).flatMap { module =>
        for
          member <- moduleSurface(path, text, module).find(_.name == word)
          file <- resolveModule(path, module)
        yield location(pathToUri(file.getAbsolutePath), sourceOf(file), member.line)
      }.nextOption())
      .getOrElse(VNull)

  /**
   * Word-boundary occurrences in the open document, declaration included. The
   * language service rebuild will make this scope-aware; until then this is the
   * same honest text-level answer an editor's own "highlight occurrences" gives.
   */
  private def references(params: Value): Value =
    val uri = getStr(params, "textDocument", "uri")
    val text = docs.getOrElse(uri, "")
    val word = wordAt(text, getInt(params, "position", "line"), getInt(params, "position", "character"))
    if word.isEmpty then return VArr.empty
    val out = mutable.ArrayBuffer.empty[Value]
    var line = 0
    var lineStart = 0
    var i = 0
    while i <= text.length do
      if i == text.length || text.charAt(i) == '\n' then
        val lineText = text.substring(lineStart, i)
        var at = lineText.indexOf(word)
        while at >= 0 do
          val beforeOk = at == 0 || !isIdentChar(lineText.charAt(at - 1))
          val end = at + word.length
          val afterOk = end >= lineText.length || !isIdentChar(lineText.charAt(end))
          if beforeOk && afterOk then
            out += VObj.of(Seq(
              "uri" -> VStr(uri),
              "range" -> VObj.of(Seq(
                "start" -> VObj.of(Seq("line" -> VInt.of(line), "character" -> VInt.of(at))),
                "end" -> VObj.of(Seq("line" -> VInt.of(line), "character" -> VInt.of(end)))))))
          at = lineText.indexOf(word, at + 1)
        line += 1
        lineStart = i + 1
      i += 1
    VArr.of(out.toSeq)

  private def pathToUri(path: String): String =
    val sb = new StringBuilder("file://")
    for c <- path do c match
      case ' '           => sb.append("%20")
      case '%'           => sb.append("%25")
      case '#'           => sb.append("%23")
      case '?'           => sb.append("%3F")
      case other         => sb.append(other)
    sb.toString

  private def hover(params: Value): Value =
    val uri = getStr(params, "textDocument", "uri")
    val text = docs.getOrElse(uri, "")
    val path = uriToPath(uri)
    val word = wordAt(text, getInt(params, "position", "line"), getInt(params, "position", "character"))
    if word.isEmpty then return VNull
    val off = offsetOf(text, getInt(params, "position", "line"), getInt(params, "position", "character"))

    // `alias.word` hovers with the member's definition from its module.
    var s = math.min(off, text.length)
    while s > 0 && isIdentChar(text.charAt(s - 1)) do s -= 1
    if s > 0 && text.charAt(s - 1) == '.' then
      var e = s - 1
      if e > 0 && text.charAt(e - 1) == '?' then e -= 1
      var rs = e
      while rs > 0 && isIdentChar(text.charAt(rs - 1)) do rs -= 1
      val recv = text.substring(rs, e)
      namespaceMembers(path, text, recv).find(_.name == word) match
        case Some(member) =>
          val label = docAliases(path, text).getOrElse(recv, recv)
          return markdownHover(s"```sfl\n${member.detail}\n```\n\n*from $label*")
        case None => ()
      if Builtins.isNamespace(recv) then
        Builtins.lookup(word).filter(b => recv == Builtins.StdNamespace || b.group == recv) match
          case Some(b) =>
            return markdownHover(
              s"```sfl\n${b.signature}\n```\n\n${b.doc}\n\n*builtin, reached through `$recv`*")
          case None => ()

    if isBuildFile(path) then
      buildDsl.find(_.name == word) match
        case Some(m) =>
          return markdownHover(
            s"```sfl\n${m.detail}\n```\n\n*sfl build DSL — defined by the build tool " +
              "before build.sfl runs; see the project guide*")
        case None => ()
    Builtins.lookup(word).filterNot(b => Builtins.isInternal(b.name)) match
      case Some(b) =>
        markdownHover(s"```sfl\n${b.signature}\n```\n\n${b.doc}\n\n*builtin, group ${b.group}*")
      case None =>
        // This file's own definitions, then plainly imported modules.
        DefRe.findAllMatchIn(text).find(_.group(1) == word) match
          case Some(m) => markdownHover(s"```sfl\ndef $word(${m.group(2)})\n```")
          case None =>
            plainImports(path, text).map(_.group(1)).toSeq.iterator
              .flatMap(mod => moduleSurface(path, text, mod).find(_.name == word).map((mod, _)))
              .nextOption() match
              case Some((mod, member)) =>
                markdownHover(s"```sfl\n${member.detail}\n```\n\n*from module $mod*")
              case None => VNull

  private def markdownHover(md: String): Value =
    VObj.of(Seq("contents" -> VObj.of(Seq("kind" -> VStr("markdown"), "value" -> VStr(md)))))

  private def signatureHelp(params: Value): Value =
    val uri = getStr(params, "textDocument", "uri")
    val text = docs.getOrElse(uri, "")
    val path = uriToPath(uri)
    val off = offsetOf(text, getInt(params, "position", "line"), getInt(params, "position", "character"))
    callAt(text, off) match
      case None => VNull
      case Some((callee, active)) =>
        findSignature(path, text, callee) match
          case None => VNull
          case Some((label, doc)) =>
            val inner = label.dropWhile(_ != '(').stripPrefix("(").reverse.dropWhile(_ != ')').stripPrefix(")").reverse
            val ps = inner.split(",").map(_.trim).filter(_.nonEmpty)
            VObj.of(Seq(
              "signatures" -> VArr.of(Seq(VObj.of(Seq(
                "label" -> VStr(label),
                "documentation" -> VStr(doc),
                "parameters" -> VArr.of(ps.toSeq.map(p => VObj.of(Seq("label" -> VStr(p)))))
              )))),
              "activeSignature" -> VInt.of(0),
              "activeParameter" -> VInt.of(math.min(active, math.max(0, ps.length - 1)))
            ))

  /**
   * The signature for a call target: builtins, this file's own defs, aliased
   * module members (`g.area`), and plainly imported modules — in that order,
   * which mirrors how the reader resolves the name in their head.
   */
  private def findSignature(path: String, text: String, callee: String): Option[(String, String)] =
    val dot = callee.indexOf('.')
    if dot > 0 then
      val recv = callee.substring(0, dot)
      val fn = callee.substring(dot + 1)
      val label = docAliases(path, text).getOrElse(recv, recv)
      return namespaceMembers(path, text, recv).find(m => m.name == fn && m.kind == 3)
        .map(m => (m.detail.stripPrefix("def "), s"from $label"))
        .orElse {
          if !Builtins.isNamespace(recv) then None
          else Builtins.lookup(fn)
            .filter(b => recv == Builtins.StdNamespace || b.group == recv)
            .map(b => (b.signature, b.doc))
        }
    val fromDsl =
      if isBuildFile(path) then
        buildDsl.find(m => m.name == callee && m.kind == 3)
          .map(m => (m.detail.stripPrefix("def "),
            "sfl build DSL — defined by the build tool before build.sfl runs"))
      else None
    fromDsl.orElse(Builtins.lookup(callee).map(b => (b.signature, b.doc))).orElse {
      DefRe.findAllMatchIn(text).find(_.group(1) == callee)
        .map(m => (s"$callee(${m.group(2)})", "defined in this file"))
    }.orElse {
      plainImports(path, text).map(_.group(1)).toSeq.iterator
        .flatMap(mod => moduleSurface(path, text, mod)
          .find(m => m.name == callee && m.kind == 3).map(m => (m, mod)))
        .nextOption()
        .map((m, mod) => (m.detail.stripPrefix("def "), s"from module $mod"))
    }

  /**
   * The innermost unclosed call at `off`: walks backwards counting brackets and
   * top-level commas. Strings are skipped by their quotes on the way back, which
   * is right for finished text and merely harmless for text mid-edit — signature
   * help degrades to nothing rather than to a wrong answer elsewhere.
   */
  private def callAt(text: String, off: Int): Option[(String, Int)] =
    var i = math.min(off, text.length) - 1
    var depth = 0
    var commas = 0
    while i >= 0 do
      val c = text.charAt(i)
      if c == '"' || c == '\'' then
        i -= 1
        while i >= 0 && text.charAt(i) != c do i -= 1
      else if c == ')' || c == ']' || c == '}' then depth += 1
      else if c == '(' && depth == 0 then
        var j = i - 1
        while j >= 0 && text.charAt(j) == ' ' do j -= 1
        var k = j
        // Dotted callees too: `g.area(` and `g?.area(` both name a module member.
        while k >= 0 && (isIdentChar(text.charAt(k)) || text.charAt(k) == '.' || text.charAt(k) == '?') do
          k -= 1
        val name = text.substring(k + 1, j + 1).replace("?.", ".")
        val valid = name.nonEmpty && !name.charAt(0).isDigit &&
          !name.startsWith(".") && !name.endsWith(".") && name.count(_ == '.') <= 1 &&
          !name.contains("?")
        return if valid then Some((name, commas)) else None
      else if c == '(' || c == '[' || c == '{' then depth -= 1
      else if c == ',' && depth == 0 then commas += 1
      else if c == '\n' && depth == 0 && commas == 0 then
        // A newline outside any bracket usually means we left the call expression.
        // Statements do not span lines here unless brackets are open.
        return None
      i -= 1
    None

  private def isIdentChar(c: Char): Boolean =
    c == '_' || c == '$' || Character.isLetterOrDigit(c)

  private def wordAt(text: String, line0: Int, char0: Int): String =
    val off = offsetOf(text, line0, char0)
    var a = off
    while a > 0 && isIdentChar(text.charAt(a - 1)) do a -= 1
    var b = off
    while b < text.length && isIdentChar(text.charAt(b)) do b += 1
    val w = text.substring(a, b)
    if w.nonEmpty && !w.charAt(0).isDigit then w else ""

  private def offsetOf(text: String, line0: Int, char0: Int): Int =
    var i = 0
    var l = 0
    while l < line0 && i < text.length do
      if text.charAt(i) == '\n' then l += 1
      i += 1
    var j = i
    var c = 0
    while c < char0 && j < text.length && text.charAt(j) != '\n' do
      c += 1
      j += 1
    j

  // -------------------------------------------------------------------------
  // Bits and pieces
  // -------------------------------------------------------------------------

  private def initializeResult(): Value =
    VObj.of(Seq(
      "capabilities" -> VObj.of(Seq(
        "positionEncoding" -> VStr("utf-16"),
        "textDocumentSync" -> VInt.of(1), // full: documents are small and parses are cheap
        // '.' pops member completion, '"' and '/' continue an import path. The
        // server answers with an empty list when the character occurs anywhere
        // else, so division never opens a popup.
        "completionProvider" -> VObj.of(Seq(
          "triggerCharacters" -> VArr.of(Seq(VStr("."), VStr("\""), VStr("/"))))),
        "hoverProvider" -> VBool.True,
        "signatureHelpProvider" -> VObj.of(Seq(
          "triggerCharacters" -> VArr.of(Seq(VStr("("), VStr(","))))),
        "documentSymbolProvider" -> VBool.True,
        "definitionProvider" -> VBool.True,
        // SFL has no types or interfaces, so every flavour of "go to" the
        // editors expose lands on the one definition there is.
        "declarationProvider" -> VBool.True,
        "implementationProvider" -> VBool.True,
        "typeDefinitionProvider" -> VBool.True,
        "referencesProvider" -> VBool.True
      )),
      "serverInfo" -> VObj.of(Seq("name" -> VStr("sfl"), "version" -> VStr(Sfl.version)))
    ))

  /** file:// URI -> filesystem path; anything else is used as the display name. */
  private def uriToPath(uri: String): String =
    if !uri.startsWith("file://") then return uri
    val raw = uri.substring("file://".length)
    val path = percentDecode(if raw.startsWith("/") then raw else "/" + raw.dropWhile(_ != '/'))
    // file:///C:/x arrives as /C:/x on Windows; the leading slash is the URI's, not the path's.
    if path.length >= 3 && path.charAt(0) == '/' && path.charAt(2) == ':' then path.substring(1)
    else path

  private def percentDecode(s: String): String =
    if !s.contains('%') then return s
    val out = new java.io.ByteArrayOutputStream(s.length)
    var i = 0
    while i < s.length do
      val c = s.charAt(i)
      if c == '%' && i + 2 < s.length then
        try
          out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16))
          i += 3
        catch case _: NumberFormatException =>
          out.write(c.toInt); i += 1
      else
        // Anything non-ASCII that arrives undecoded keeps its UTF-8 bytes.
        if c < 0x80 then out.write(c.toInt)
        else out.write(String.valueOf(c).getBytes(UTF_8))
        i += 1
    new String(out.toByteArray, UTF_8)

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
