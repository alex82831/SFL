package com.fartech.sfl

import java.io.File

/**
 * Versioned packages: the `sfl.pkg` manifest, the semver subset, the two installed
 * roots, and the `sfl pkg` command-line verbs.
 *
 * A package is nothing more than a directory of `.sfl` modules with a manifest, and
 * an installed package is that directory copied under `<root>/<name>/<version>/`.
 * The importer (see [[Importer]]) does the actual resolution; this file owns the
 * pieces both sides share, so the CLI and the language can never disagree about
 * what a version or a range means.
 */
object PkgTool:

  /**
   * A package problem before it has a source location. The importer catches these
   * and re-throws them anchored at the `import` that triggered the work; the CLI
   * prints them directly. Help lines ride along so both surfaces keep the house
   * message-plus-help shape.
   */
  final class PkgError(msg: String, val helps: List[String]) extends RuntimeException(msg)

  private def bad(msg: String, helps: String*): Nothing = throw new PkgError(msg, helps.toList)

  // -------------------------------------------------------------------------
  // Names, versions and ranges
  // -------------------------------------------------------------------------

  /** `[A-Za-z_][A-Za-z0-9_-]*` — checked by hand, which is cheaper than a regex. */
  def isValidName(s: String): Boolean =
    if s.isEmpty then return false
    val c0 = s.charAt(0)
    if !((c0 >= 'A' && c0 <= 'Z') || (c0 >= 'a' && c0 <= 'z') || c0 == '_') then return false
    var i = 1
    while i < s.length do
      val c = s.charAt(i)
      if !((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ||
            c == '_' || c == '-') then return false
      i += 1
    true

  final case class Version(major: Int, minor: Int, patch: Int) extends Ordered[Version]:
    def compare(that: Version): Int =
      if major != that.major then Integer.compare(major, that.major)
      else if minor != that.minor then Integer.compare(minor, that.minor)
      else Integer.compare(patch, that.patch)
    override def toString: String = s"$major.$minor.$patch"

  /** Strict `x.y.z`: three dot-separated runs of digits and nothing else. */
  private def parseVersion(s: String): Option[Version] =
    val parts = s.split('.')
    if parts.length != 3 || parts.exists(p => p.isEmpty || !p.forall(_.isDigit)) then None
    else
      try Some(Version(parts(0).toInt, parts(1).toInt, parts(2).toInt))
      catch case _: NumberFormatException => None

  private def versionOrBad(s: String, what: String): Version =
    parseVersion(s).getOrElse(
      bad(s"$what '$s' is not a version of the form x.y.z",
        "versions are three integers, such as 1.2.0"))

  /**
   * One requirement on a version. The original text is kept because every error
   * that mentions a range must show it exactly as the manifest wrote it.
   */
  final class Range(val text: String, check: Version => Boolean):
    def satisfies(v: Version): Boolean = check(v)

  /**
   * Exactly the documented subset: `1.2.3`, `^1.2.3`, `~1.2.3`, `*`, `>=1.2.3`,
   * and the conjunction `>=1.2.3 <2.0.0`. Anything else is refused rather than
   * guessed at, so a typo cannot quietly select the wrong version.
   */
  private def parseRange(text: String): Range =
    def unsupported(): Nothing =
      bad(s"unsupported version range '$text'",
        "supported forms: \"1.2.3\", \"^1.2.3\", \"~1.2.3\", \"*\", \">=1.2.3\", \">=1.2.3 <2.0.0\"")
    val s = text.trim
    if s == "*" then new Range(s, _ => true)
    else if s.startsWith("^") then
      val lo = parseVersion(s.substring(1)).getOrElse(unsupported())
      // Caret keeps the leftmost non-zero component: below 1.0.0 the minor is the
      // compatibility line, so ^0.2.3 admits 0.2.x only.
      val hi = if lo.major > 0 then Version(lo.major + 1, 0, 0) else Version(0, lo.minor + 1, 0)
      new Range(s, v => v >= lo && v < hi)
    else if s.startsWith("~") then
      val lo = parseVersion(s.substring(1)).getOrElse(unsupported())
      val hi = Version(lo.major, lo.minor + 1, 0)
      new Range(s, v => v >= lo && v < hi)
    else if s.startsWith(">=") then
      s.split("\\s+") match
        case Array(a) =>
          val lo = parseVersion(a.substring(2)).getOrElse(unsupported())
          new Range(s, v => v >= lo)
        case Array(a, b) if b.startsWith("<") && !b.startsWith("<=") =>
          val lo = parseVersion(a.substring(2)).getOrElse(unsupported())
          val hi = parseVersion(b.substring(1)).getOrElse(unsupported())
          new Range(s, v => v >= lo && v < hi)
        case _ => unsupported()
    else
      parseVersion(s) match
        case Some(exact) => new Range(s, v => v == exact)
        case None        => unsupported()

  // -------------------------------------------------------------------------
  // The manifest
  // -------------------------------------------------------------------------

  final case class Manifest(
      name: String,
      version: Version,
      main: String,
      deps: List[(String, Range)],
      dir: File
  ):
    def dep(pkg: String): Option[Range] = deps.collectFirst { case (n, r) if n == pkg => r }

  def manifestFile(dir: File): File = new File(dir, "sfl.pkg")

  /** Reads and validates `<dir>/sfl.pkg`. Every complaint names the file, because a
    * manifest error usually surfaces far from the manifest — at an import site or
    * mid-install — and the reader has to know which file to fix. */
  def readManifest(dir: File): Manifest =
    val mf = manifestFile(dir)
    if !mf.isFile then
      bad(s"no sfl.pkg manifest in '${dir.getPath}'",
        "a package directory must contain an sfl.pkg naming the package and its version")
    val where = mf.getPath
    val json =
      try Json.parse(FileUtil.read(mf))
      catch case e: SflError => bad(s"$where: ${e.getMessage}")
    val obj = json match
      case o: VObj => o
      case other   => bad(s"$where: the manifest must be a JSON object, not ${other.typeName}")

    def strField(key: String): Option[String] = obj.fields.get(key) match
      case None             => None
      case Some(VStr(text)) => Some(text)
      case Some(other)      => bad(s"$where: '$key' must be a string, not ${other.typeName}")

    val name = strField("name").getOrElse(bad(s"$where: the manifest has no 'name'"))
    if !isValidName(name) then
      bad(s"$where: '$name' is not a valid package name",
        "a name starts with a letter or '_' and continues with letters, digits, '_' or '-'")
    val version = versionOrBad(
      strField("version").getOrElse(bad(s"$where: the manifest has no 'version'")), s"$where: version")
    // "main" is the module a bare `import "name"` loads; leaving it out means main.sfl.
    val main = strField("main").getOrElse("main")

    val deps = obj.fields.get("deps") match
      case None => Nil
      case Some(d: VObj) =>
        d.fields.toList.map { (dep, rv) =>
          if !isValidName(dep) then bad(s"$where: '$dep' is not a valid package name in 'deps'")
          rv match
            case VStr(rangeText) =>
              val range =
                try parseRange(rangeText)
                catch case e: PkgError => bad(s"$where: dependency '$dep': ${e.getMessage}", e.helps*)
              dep -> range
            case other => bad(s"$where: the range for '$dep' must be a string, not ${other.typeName}")
        }
      case Some(other) => bad(s"$where: 'deps' must be an object, not ${other.typeName}")

    Manifest(name, version, main, deps, dir)

  // -------------------------------------------------------------------------
  // Installed roots
  // -------------------------------------------------------------------------

  /** The project-local root, deliberately relative: it belongs to the directory the
    * program is run from, the way sfl.pkg itself does. */
  private def localRoot: File = new File("sfl_packages")

  /** The user-global root, under the same SFL_HOME that already hosts libs/. */
  def globalRoot: File = new File(sys.env.getOrElse("SFL_HOME", "."), "packages")

  /** Both roots, the project one first — the order every search and listing uses. */
  def roots: List[File] = List(localRoot, globalRoot)

  /**
   * The roots to search on behalf of a file that belongs to `projectRoot`, that
   * project's own first. [[roots]] is relative to the working directory, which
   * is the right question for a command line — `sfl app.sfl` looks where you
   * are standing — but the wrong one for a tool reading a file out of a
   * checkout that holds many projects, each with its own packages. Such a tool
   * passes the project the file belongs to and gets that project's answer.
   */
  def rootsFor(projectRoot: Option[File]): List[File] =
    val own = projectRoot.toList.flatMap(r => List(new File(r, "sfl_packages"), new File(r, "packages")))
    (own ++ roots).distinctBy(_.getPath)

  /** The versions of `name` installed under `root`, lowest first. A directory whose
    * name is not a strict version is ignored rather than reported: the roots are
    * ordinary directories and may hold whatever else the user keeps there. */
  def installedVersions(root: File, name: String): List[Version] =
    val kids = new File(root, name).listFiles()
    if kids == null then Nil
    else kids.toList.flatMap(f => if f.isDirectory then parseVersion(f.getName) else None).sorted

  def versionDir(root: File, name: String, v: Version): File =
    new File(new File(root, name), v.toString)

  /** Joins versions for prose: "1.0.0", "1.0.0 and 1.2.0", "1.0.0, 1.1.0 and 1.2.0". */
  def listVersions(vs: List[Version]): String = vs match
    case Nil      => "no versions"
    case v :: Nil => v.toString
    case _        => vs.init.mkString(", ") + " and " + vs.last

  // -------------------------------------------------------------------------
  // The command line: sfl pkg <verb>
  // -------------------------------------------------------------------------

  private val usage =
    s"""${Ansi.bold}sfl pkg${Ansi.reset} — manage installed packages
       |
       |${Ansi.bold}Usage${Ansi.reset}
       |  sfl pkg build [--bin] [dir]               Pack a package into <name>-<version>.sflpkg
       |                                            (--bin also compiles it for this platform)
       |  sfl pkg install <src> [--local] [--force] Install a package
       |  sfl pkg list                              List installed packages, the project root first
       |  sfl pkg remove <name>[@version]           Remove one version, or every version
       |
       |${Ansi.bold}Install sources${Ansi.reset} (sfl install <src> is a shorthand for sfl pkg install)
       |  a directory              ./mathkit                 a local package directory
       |  a .sflpkg file           mathkit-1.2.0.sflpkg       a packed archive
       |  a git URL                github.com/owner/repo      clones and installs it
       |                           github.com/owner/repo/pkg/mathkit@v1.2.0   a subdir at a tag
       |                           git@github.com:owner/repo  private, over your SSH key
       |  a registry name          mathkit  /  mathkit@1.2.0  looked up in the registry
       |
       |Packages install into $$SFL_HOME/packages/<name>/<version>/, or into
       |./sfl_packages/... with --local. The local root shadows the global one.
       |Git sources need `git`; registry names resolve through $$SFL_REGISTRY.
       |""".stripMargin

  /** Runs one `sfl pkg` invocation; the return value is the process exit code
    * (0 ok, 1 the operation failed, 2 the command line was malformed). */
  def cli(args: List[String]): Int =
    try
      args match
        case Nil                        => Console.err.println(usage); 2
        case ("-h" | "--help") :: _     => println(usage); 0
        case "build" :: rest            => build(rest)
        case "install" :: rest          => install(rest)
        case "list" :: rest             => list(rest)
        case "remove" :: rest           => remove(rest)
        case other :: _                 => usageError(s"unknown pkg command '$other'")
    catch
      case e: PkgError =>
        Console.err.println(s"${Ansi.red}sfl pkg: ${e.getMessage}${Ansi.reset}")
        for h <- e.helps do
          Console.err.println(s"${Ansi.cyan}  help: ${Ansi.reset}${Ansi.dim}$h${Ansi.reset}")
        1

  private def usageError(msg: String): Int =
    Console.err.println(s"${Ansi.red}sfl pkg: $msg${Ansi.reset}")
    Console.err.println("Try 'sfl pkg --help'.")
    2

  /** The Backend.run pattern: output to a temp file, never a pipe that can fill. */
  private def run(cmd: Seq[String]): (Int, String) =
    val out = File.createTempFile("sfl-pkg-out", ".txt")
    try
      val pb = new ProcessBuilder(java.util.Arrays.asList(cmd*))
      pb.redirectErrorStream(true)
      pb.redirectOutput(out)
      val code = pb.start().waitFor()
      (code, FileUtil.read(out.getPath))
    catch case e: java.io.IOException => (127, e.getMessage)
    finally out.delete()

  private def rmTree(f: File): Unit =
    if f.isDirectory then
      val kids = f.listFiles()
      if kids != null then kids.foreach(rmTree)
    f.delete()

  /** A fresh empty directory. File.createTempFile is the one primitive javalib
    * guarantees, so the file becomes the directory. */
  private def makeTempDir(): File =
    val f = File.createTempFile("sfl-pkg", ".d")
    f.delete()
    if !f.mkdirs() then bad(s"cannot create a temporary directory at ${f.getPath}")
    f

  private def build(args: List[String]): Int =
    var bin = false
    var dirs = List.empty[String]
    val it = args.iterator
    while it.hasNext do it.next() match
      case "--bin"                => bin = true
      case s if s.startsWith("-") => return usageError(s"unknown option '$s' for pkg build")
      case s                      => dirs = s :: dirs
    val dir = dirs.reverse match
      case Nil      => new File(".")
      case d :: Nil => new File(d)
      case _        => return usageError("pkg build takes at most one directory")
    if !dir.isDirectory then bad(s"'${dir.getPath}' is not a directory")
    val m = readManifest(dir)
    // With --bin, compile the modules for this platform first, so the archive under
    // bin/<target>/ travels inside the .sflpkg alongside the source.
    if bin then
      try
        val abi = Backend.buildPackage(dir, verbose = true)
        Console.err.println(s"${Ansi.dim}built ${abi.modules.length} module(s) for ${abi.target}${Ansi.reset}")
      catch case e: SflError =>
        Console.err.println(e.render(Ansi.enabled))
        return 1
    val out = new File(s"${m.name}-${m.version}.sflpkg").getAbsoluteFile
    // Pack through a temp file: when the package dir is the working directory, tar
    // would otherwise race with its own growing output. Stray .sflpkg files inside
    // the package are excluded for the same reason a .tar never contains itself.
    val tmp = File.createTempFile("sfl-pkg-build", ".tgz")
    try
      val (code, log) = run(Seq("tar", "--exclude", "*.sflpkg", "-czf", tmp.getPath,
        "-C", dir.getPath, "."))
      if code != 0 then
        bad(s"tar failed while packing '${dir.getPath}'", log.trim.linesIterator.take(4).toList*)
      out.delete()
      if !tmp.renameTo(out) then
        // Rename fails across filesystems; falling back to a byte copy always works.
        val (c2, l2) = run(Seq("cp", tmp.getPath, out.getPath))
        if c2 != 0 then bad(s"cannot write ${out.getPath}", l2.trim)
      Console.err.println(s"${Ansi.dim}packed ${m.name} ${m.version}${Ansi.reset}")
      println(out.getName)
      0
    finally tmp.delete()

  private def install(args: List[String]): Int =
    var local = false
    var force = false
    var srcs = List.empty[String]
    val it = args.iterator
    while it.hasNext do it.next() match
      case "--local"                 => local = true
      case "--force"                 => force = true
      case s if s.startsWith("-")    => return usageError(s"unknown option '$s' for pkg install")
      case s                         => srcs = s :: srcs
    val src = srcs.reverse match
      case one :: Nil => one
      case _          => return usageError("pkg install needs exactly one .sflpkg file or package directory")

    // A source is one of: a local package directory, a local .sflpkg archive, or a
    // remote — a git URL or a registry short name that is fetched into a temp tree
    // and then installed exactly like a local directory. `cleanup`, when set, is the
    // temp tree to remove afterwards.
    val srcFile = new File(src)
    val (stage, cleanup) =
      if srcFile.isDirectory then (srcFile, None)
      else if srcFile.isFile then
        val tmp = makeTempDir()
        val (code, log) = run(Seq("tar", "-xzf", srcFile.getPath, "-C", tmp.getPath))
        if code != 0 then
          rmTree(tmp)
          bad(s"cannot unpack '$src'", log.trim.linesIterator.take(4).toList*)
        (tmp, Some(tmp))
      else
        Remote.fetch(src) match
          case Some((dir, root)) => (dir, Some(root))
          case None =>
            bad(s"cannot open '$src'",
              "give a package directory, a .sflpkg file, a git URL " +
                "(github.com/owner/repo[/subdir][@ref]), or a registry name")

    try
      val m = readManifest(stage)
      val root = if local then localRoot else globalRoot
      val dest = versionDir(root, m.name, m.version)
      if dest.exists then
        if !force then
          bad(s"package '${m.name}' ${m.version} is already installed at ${dest.getPath}",
            "pass --force to replace it")
        rmTree(dest)
      if !dest.mkdirs() then bad(s"cannot create ${dest.getPath}")
      // `stage/.` copies the directory's contents, dotfiles included, not the
      // directory itself — the manifest travels with the modules.
      val (code, log) = run(Seq("cp", "-R", stage.getPath + File.separator + ".", dest.getPath))
      if code != 0 then
        rmTree(dest)
        bad(s"cannot copy the package into ${dest.getPath}", log.trim)
      println(s"installed ${m.name} ${m.version} -> ${dest.getPath}")
      0
    finally cleanup.foreach(rmTree)

  private def list(args: List[String]): Int =
    if args.nonEmpty then return usageError("pkg list takes no arguments")
    var any = false
    for root <- roots do
      val kids = root.listFiles()
      val names =
        if kids == null then Array.empty[String]
        else kids.filter(f => f.isDirectory && isValidName(f.getName)).map(_.getName).sorted
      val lines =
        for name <- names.toList; v <- installedVersions(root, name) yield s"  $name $v"
      if lines.nonEmpty then
        println(root.getPath)
        lines.foreach(println)
        any = true
    if !any then println("no packages installed")
    0

  private def remove(args: List[String]): Int =
    val spec = args match
      case one :: Nil => one
      case _          => return usageError("pkg remove needs one <name> or <name>@<version>")
    val at = spec.indexOf('@')
    val (name, ver) =
      if at < 0 then (spec, None)
      else (spec.substring(0, at), Some(versionOrBad(spec.substring(at + 1), "version")))
    if !isValidName(name) then return usageError(s"'$name' is not a valid package name")

    var removed = List.empty[String]
    for root <- roots do
      val target = ver match
        case Some(v) => versionDir(root, name, v)
        case None    => new File(root, name)
      if target.exists then
        rmTree(target)
        // A version dir leaves its now-empty name dir behind; sweep it so `list`
        // and resolution never see a package with no versions.
        if ver.isDefined then
          val parent = new File(root, name)
          val left = parent.listFiles()
          if left != null && left.isEmpty then parent.delete()
        removed = target.getPath :: removed
    if removed.isEmpty then
      bad(s"package '$name'${ver.fold("")(v => s" $v")} is not installed")
    removed.reverse.foreach(p => println(s"removed $p"))
    0

  // -------------------------------------------------------------------------
  // Binary packages
  //
  // A package can ship prebuilt object code beside its source, under
  // `bin/<target>/`: an archive `lib.a` of the package's modules compiled by
  // this same compiler, and an `abi.json` describing what it holds. The source
  // always ships too and stays the source of truth — the interpreter runs it,
  // errors quote it, and a mismatched or absent archive falls back to it — so a
  // binary package is an ahead-of-time build cached in the package, never a way
  // to hide the code. This object owns the names and the manifest both the
  // packer (Backend) and the linker (Compiler) must agree on.
  // -------------------------------------------------------------------------

  object Binary:

    /** `arch-os`, e.g. "arm64-macos": the granularity an archive is valid for. */
    def targetTriple: String =
      val a = System.getProperty("os.arch", "").toLowerCase
      val arch =
        if a.contains("aarch64") || a.contains("arm64") then "arm64"
        else if a.contains("x86_64") || a.contains("amd64") then "x86_64"
        else if a.isEmpty then "unknown" else a
      val o = System.getProperty("os.name", "").toLowerCase
      val os =
        if o.contains("mac") || o.contains("darwin") then "macos"
        else if o.contains("linux") then "linux"
        else if o.contains("windows") then "windows"
        else if o.isEmpty then "unknown" else o.split("\\s+").head
      s"$arch-$os"

    private def sha256(s: String): String =
      val md = java.security.MessageDigest.getInstance("SHA-256")
      md.digest(s.getBytes("UTF-8")).map("%02x".format(_)).mkString

    /** The digest of a module's source, which the archive is keyed to. */
    def sourceDigest(text: String): String = sha256(text)

    /** Keeps a symbol to the C identifier charset; the hash disambiguates names
      * that only differ in characters that flatten to '_'. */
    private def san(s: String): String =
      s.map(c => if c.isLetterOrDigit || c == '_' then c else '_').mkString

    private def h6(s: String): String = sha256(s).take(6)

    /** The per-module symbol prefix, stable across builds and free of the
      * version (the flat model uses one version of a package per program). */
    def modPrefix(pkg: String, moduleRel: String): String =
      s"sflpkg_${san(pkg)}_${san(moduleRel)}_${h6(pkg + "/" + moduleRel)}"

    def codeSymbol(pkg: String, moduleRel: String, name: String): String =
      s"${modPrefix(pkg, moduleRel)}_f_${san(name)}"
    def protoSymbol(pkg: String, moduleRel: String, name: String): String =
      s"${modPrefix(pkg, moduleRel)}_p_${san(name)}"
    def initSymbol(pkg: String, moduleRel: String): String =
      s"${modPrefix(pkg, moduleRel)}_init"
    def fbaseSymbol(pkg: String, moduleRel: String): String =
      s"${modPrefix(pkg, moduleRel)}_fbase"

    def binDir(versionDir: File, target: String): File =
      new File(new File(versionDir, "bin"), target)
    def archiveFile(versionDir: File, target: String): File =
      new File(binDir(versionDir, target), "lib.a")
    def abiFile(versionDir: File, target: String): File =
      new File(binDir(versionDir, target), "abi.json")

    /** One module's contribution to an archive. `deps` names the packages this
      * module imports, so the linker can walk the binary dependency graph. */
    final case class ModuleAbi(
        moduleRel: String,
        digest: String,
        exports: List[String],
        prims: List[String],
        stdlib: List[String],
        deps: List[String]
    )

    /** The archive manifest. The toolchain fields fix exactly the compiler and
      * runtime that produced the objects; a link is refused unless all match, so
      * a stale `.o` can never reach the linker. */
    final case class Abi(
        name: String,
        version: String,
        sfl: String,
        target: String,
        runtimeDigest: String,
        stdlibDigest: String,
        buildId: String,
        modules: List[ModuleAbi]
    ):
      def module(rel: String): Option[ModuleAbi] = modules.find(_.moduleRel == rel)

    /** The toolchain fingerprint the current binary would stamp into an archive. */
    def currentSfl: String = Sfl.version
    def currentRuntimeDigest: String = RuntimeSources.digest
    def currentStdlibDigest: String = StdlibSources.digest
    def currentBuildId: String = RuntimeSources.buildId

    private def arr(items: Seq[String]): VArr = VArr.of(items.map(VStr(_)))

    def writeAbi(f: File, abi: Abi): Unit =
      val mods = abi.modules.map { m =>
        VObj.of(List(
          "module" -> VStr(m.moduleRel),
          "digest" -> VStr(m.digest),
          "exports" -> arr(m.exports),
          "prims" -> arr(m.prims),
          "stdlib" -> arr(m.stdlib),
          "deps" -> arr(m.deps)
        )): Value
      }
      val obj = VObj.of(List(
        "name" -> VStr(abi.name),
        "version" -> VStr(abi.version),
        "sfl" -> VStr(abi.sfl),
        "target" -> VStr(abi.target),
        "runtimeDigest" -> VStr(abi.runtimeDigest),
        "stdlibDigest" -> VStr(abi.stdlibDigest),
        "buildId" -> VStr(abi.buildId),
        "modules" -> new VArr(scala.collection.mutable.ArrayBuffer.from(mods))
      ))
      FileUtil.write(f.getPath, Json.stringify(obj, pretty = true) + "\n")

    def readAbi(f: File): Option[Abi] =
      if !f.isFile then return None
      try
        Json.parse(FileUtil.read(f.getPath)) match
          case o: VObj =>
            def str(k: String): String = o.fields.get(k) match
              case Some(VStr(s)) => s
              case _             => throw new NoSuchElementException(k)
            val mods = o.fields.get("modules") match
              case Some(a: VArr) => a.items.toList.flatMap {
                case mo: VObj =>
                  def ms(k: String): String = mo.fields.get(k) match
                    case Some(VStr(s)) => s; case _ => ""
                  def ml(k: String): List[String] = mo.fields.get(k) match
                    case Some(a: VArr) => a.items.toList.collect { case VStr(s) => s }
                    case _             => Nil
                  Some(ModuleAbi(ms("module"), ms("digest"), ml("exports"),
                    ml("prims"), ml("stdlib"), ml("deps")))
                case _ => None
              }
              case _ => Nil
            Some(Abi(str("name"), str("version"), str("sfl"), str("target"),
              str("runtimeDigest"), str("stdlibDigest"), str("buildId"), mods))
          case _ => None
      catch case _: Throwable => None

    /** True when this archive was built by exactly this toolchain for this target. */
    def toolchainMatches(abi: Abi): Boolean =
      abi.sfl == currentSfl && abi.target == targetTriple &&
        abi.runtimeDigest == currentRuntimeDigest &&
        abi.stdlibDigest == currentStdlibDigest && abi.buildId == currentBuildId

  // -------------------------------------------------------------------------
  // Remote sources: install from a git repository or a registry name
  //
  // `install` hands anything that is not a local directory or .sflpkg here. A git
  // URL is cloned; a bare name is looked up in a registry (a small JSON index,
  // fetched over HTTPS) that maps it to a git URL. Either way the result is a
  // directory `install` treats exactly like a local one — which is what keeps
  // remote sources orthogonal to the rest of the tool.
  //
  // The transport is `git clone`, deliberately: it works for public repositories
  // with no credentials and for private ones through the user's own git auth, so
  // this tool never handles a token; and it sidesteps the anonymous API rate limit.
  // Everything lands in a temp tree first and is validated before a byte reaches
  // the package roots, and a subdirectory is refused if it tries to escape the
  // clone — installing a package runs its code, so the path to it is checked.
  // -------------------------------------------------------------------------

  object Remote:

    /** Where `sfl install <name>` resolves short names; override for another index. */
    def registryUrl: String =
      sys.env.getOrElse("SFL_REGISTRY",
        "https://raw.githubusercontent.com/alex82831/SFL/main/registry.json")

    /** A resolved git source: what to clone, an optional subdirectory within it that
      * holds the package, and an optional tag/branch/commit to check out. */
    final case class GitSource(url: String, subdir: Option[String], ref: Option[String])

    /**
     * Fetches `src` into a temp tree. Returns (the package directory to install,
     * the temp root to delete afterwards), or None when `src` is not a git URL and
     * not a valid registry name — so `install` can report a plain "cannot open".
     */
    def fetch(src: String): Option[(File, File)] =
      parseGitSource(src) match
        case Some(gs) => Some(cloneStage(gs, src))
        case None =>
          val (name, ref) = splitRef(src)
          if isValidName(name) then Some(cloneStage(resolveShortName(name, ref), src))
          else None

    // ---- git source syntax --------------------------------------------------

    /**
     * Splits a trailing `@ref` off a source. The `git@host:` of an scp-style URL is
     * not a ref: that `@` has a ':' after it and nothing path-like before it.
     */
    private def splitRef(spec: String): (String, Option[String]) =
      val at = spec.lastIndexOf('@')
      if at <= 0 then (spec, None)
      else
        val before = spec.substring(0, at)
        val after = spec.substring(at + 1)
        if !before.contains('/') && !before.contains(':') && after.contains(':') then (spec, None)
        else (before, Some(after))

    private def isScpLike(s: String): Boolean =
      s.matches("^[A-Za-z0-9._-]+@[A-Za-z0-9._-]+:.*")
    private def isOwnerRepo(s: String): Boolean =
      s.matches("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._.-]*(/.*)?$")

    /** github.com paths carry the package's subdirectory after `owner/repo`. */
    private def githubFrom(path: String, ref: Option[String]): Option[GitSource] =
      val segs = path.split('/').filter(_.nonEmpty)
      if segs.length < 2 then None
      else
        val url = s"https://github.com/${segs(0)}/${segs(1).stripSuffix(".git")}.git"
        val sub = if segs.length > 2 then Some(sanitizeSubdir(segs.drop(2).mkString("/"))) else None
        Some(GitSource(url, sub, ref))

    /**
     * Recognises a git source. `git+<url>` forces git on any URL (file:// included,
     * which the tests use); github.com is special-cased so a subdirectory can ride
     * in the path; other schemes and scp-style URLs pass through whole; and a bare
     * `owner/repo` is github shorthand. Anything else is None — a registry name, or
     * a mistake `install` will report.
     */
    private def parseGitSource(spec0: String): Option[GitSource] =
      // A `#subdir` fragment (the last suffix) points at a package within the repo
      // for any URL form — the github path already carries one, but an SSH or a
      // plain git URL has nowhere else to put it.
      val hash = spec0.lastIndexOf('#')
      val (spec, frag) =
        if hash > 0 then (spec0.substring(0, hash), Some(sanitizeSubdir(spec0.substring(hash + 1))))
        else (spec0, None)
      val (base, ref) = splitRef(spec)
      val parsed =
        if base.startsWith("git+") then Some(GitSource(base.substring(4), None, ref))
        else if base.startsWith("https://github.com/") then githubFrom(base.stripPrefix("https://github.com/"), ref)
        else if base.startsWith("http://github.com/") then githubFrom(base.stripPrefix("http://github.com/"), ref)
        else if base.startsWith("github.com/") then githubFrom(base.stripPrefix("github.com/"), ref)
        else if base.startsWith("https://") || base.startsWith("http://") ||
                base.startsWith("ssh://") || base.startsWith("git://") then Some(GitSource(base, None, ref))
        else if isScpLike(base) then Some(GitSource(base, None, ref))
        else if isOwnerRepo(base) then githubFrom(base, ref)
        else None
      parsed.map(gs => if frag.isDefined then gs.copy(subdir = frag) else gs)

    /** Refuses a subdirectory that is absolute or climbs out of the clone. */
    private def sanitizeSubdir(s: String): String =
      if s.startsWith("/") || s.split('/').exists(_ == "..") then
        bad(s"unsafe package subdirectory '$s'",
          "a subdirectory within a repository must not be absolute or contain '..'")
      s

    // ---- fetching -----------------------------------------------------------

    /** A ref that names a version, so it can be checked against the manifest. */
    private val VersionTag = "^v?(\\d+\\.\\d+\\.\\d+)$".r

    private def requireGit(): Unit =
      val (code, _) = run(Seq("git", "--version"))
      if code != 0 then
        bad("git is needed to install a package from a repository",
          "install git (on macOS: xcode-select --install), " +
            "or install from a local .sflpkg file or a directory instead")

    private def cloneStage(gs: GitSource, label: String): (File, File) =
      requireGit()
      val tmp = makeTempDir()
      try
        cloneInto(gs, tmp)
        val stage = gs.subdir match
          case Some(sub) => new File(tmp, sub)
          case None      => tmp
        if !stage.isDirectory then
          bad(s"'$label': the repository has no directory '${gs.subdir.getOrElse(".")}'",
            s"cloned ${gs.url}${gs.ref.fold("")(r => s" at $r")}")
        // A version-tag ref must agree with the manifest. The flat resolver decides
        // semver ranges from the installed version directory's name, so a package
        // fetched at @v1.2.0 whose sfl.pkg says otherwise would take part in every
        // later decision under a version it never claimed.
        gs.ref match
          case Some(VersionTag(v)) =>
            val m = readManifest(stage)
            if m.version.toString != v then
              bad(s"'$label': the ref asks for $v but the package's sfl.pkg says ${m.version}",
                "install the ref whose sfl.pkg matches, or drop the version from the ref")
          case _ => ()
        (stage, tmp)
      catch
        case e: Throwable => rmTree(tmp); throw e

    private def cloneHint(url: String): String =
      if url.startsWith("https://github.com/") then
        "if the repository is private, use its SSH URL (git@github.com:owner/repo) so git uses your key"
      else "check the URL and that you can reach it"

    private def cloneInto(gs: GitSource, tmp: File): Unit =
      val shallow = Seq("git", "clone", "--depth", "1", "--quiet")
      val (code, log) = gs.ref match
        case Some(r) => run(shallow ++ Seq("--branch", r, gs.url, tmp.getPath))
        case None    => run(shallow ++ Seq(gs.url, tmp.getPath))
      if code != 0 then
        gs.ref match
          case Some(r) =>
            // --branch takes only a name; a bare commit sha needs a full clone.
            Option(tmp.listFiles()).getOrElse(Array.empty[File]).foreach(rmTree)
            val (c2, l2) = run(Seq("git", "clone", "--quiet", gs.url, tmp.getPath))
            if c2 != 0 then
              bad(s"cannot clone ${gs.url}", (cloneHint(gs.url) +: l2.trim.linesIterator.take(3).toList)*)
            val (c3, l3) = run(Seq("git", "-C", tmp.getPath, "checkout", "--quiet", r))
            if c3 != 0 then
              bad(s"cannot check out '$r' from ${gs.url}", l3.trim.linesIterator.take(3).toList*)
          case None =>
            bad(s"cannot clone ${gs.url}", (cloneHint(gs.url) +: log.trim.linesIterator.take(3).toList)*)

    // ---- registry -----------------------------------------------------------

    private def resolveShortName(name: String, ref: Option[String]): GitSource =
      val reg = fetchRegistry()
      val pkgs = reg.fields.get("packages") match
        case Some(o: VObj) => o
        case _ => bad(s"the registry at $registryUrl has no 'packages' object")
      pkgs.fields.get(name) match
        case Some(o: VObj) =>
          val git = o.fields.get("git") match
            case Some(VStr(g)) => g
            case _ => bad(s"the registry entry for '$name' has no 'git' source")
          val regSub = o.fields.get("subdir").collect { case VStr(s) => sanitizeSubdir(s) }
          val regRef = o.fields.get("ref").collect { case VStr(s) => s }
          parseGitSource(git) match
            case Some(gs) => GitSource(gs.url, regSub.orElse(gs.subdir), ref.orElse(regRef).orElse(gs.ref))
            case None     => bad(s"the registry entry for '$name' has an unusable git source '$git'")
        case _ =>
          bad(s"package '$name' is not in the registry",
            s"the registry is $registryUrl (override with SFL_REGISTRY)",
            "or give a git URL directly, e.g. 'sfl install github.com/owner/repo'")

    private def fetchRegistry(): VObj =
      val (code, body) = run(Seq("curl", "-fsSL", "--max-time", "20", registryUrl))
      if code != 0 then
        bad(s"cannot fetch the package registry",
          (s"tried $registryUrl (override with SFL_REGISTRY)" +:
            body.trim.linesIterator.take(2).toList)*)
      try
        Json.parse(body) match
          case o: VObj => o
          case _       => bad(s"the registry at $registryUrl is not a JSON object")
      catch case e: SflError => bad(s"the registry at $registryUrl is not valid JSON: ${e.getMessage}")
