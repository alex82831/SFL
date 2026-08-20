package com.fartech.sfl

import java.io.File

/**
 * Turns generated LLVM IR into an executable.
 *
 * Rather than binding to the LLVM C API this hands the IR to clang — the same clang
 * the project already needs to build itself, so a working SFL install can always
 * compile. Alongside it go two archives: the C runtime, and the part of the builtin
 * library written in SFL and compiled by this very compiler. Both are built once
 * into a cache keyed by a digest of their sources, so only the first compile on a
 * machine pays for them, and the linker takes only the objects a program reaches.
 */
object Backend:

  private def run(cmd: Seq[String]): (Int, String) =
    val out = File.createTempFile("sfl-cc-out", ".txt")
    try
      val pb = new ProcessBuilder(java.util.Arrays.asList(cmd*))
      pb.redirectErrorStream(true)
      pb.redirectOutput(out)
      val code = pb.start().waitFor()
      (code, FileUtil.read(out.getPath))
    catch case e: java.io.IOException => (127, e.getMessage)
    finally out.delete()

  private def which(name: String): Option[String] =
    val (code, out) = run(Seq("/usr/bin/which", name))
    if code == 0 && out.trim.nonEmpty then Some(out.trim.linesIterator.next()) else None

  private lazy val isMac: Boolean =
    System.getProperty("os.name", "").toLowerCase.contains("mac")

  /** Prefers the clang that matches the platform SDK, as the sbt build does. */
  private def findClang(): Option[String] =
    if isMac then
      val (code, out) = run(Seq("/usr/bin/xcrun", "--find", "clang"))
      if code == 0 && out.trim.nonEmpty then Some(out.trim) else which("clang")
    else which("clang")

  private lazy val sdkFlags: Seq[String] =
    if !isMac then Nil
    else
      val (code, out) = run(Seq("/usr/bin/xcrun", "--show-sdk-path"))
      if code == 0 && out.trim.nonEmpty then Seq("-isysroot", out.trim) else Nil

  /** macOS and GNU ld spell "drop what nothing reaches" differently. */
  private lazy val stripFlags: Seq[String] =
    if isMac then Seq("-Wl,-dead_strip") else Seq("-Wl,--gc-sections")

  private def clangOrFail(): String =
    findClang().getOrElse(
      throw new CompileError(
        "cannot find clang, which is needed to assemble the program",
        Pos.none, SourceRef.unknown
      ).note("install the Xcode command line tools on macOS, or your distribution's clang")
    )

  // -------------------------------------------------------------------------
  // The cache
  // -------------------------------------------------------------------------

  /**
   * Keyed by a digest of the runtime and library sources, so editing either one
   * builds a fresh cache instead of silently linking a stale archive, and two
   * versions of sfl on one machine never collide.
   */
  lazy val cacheDir: File =
    val base =
      sys.env.get("SFL_CACHE").map(new File(_)).getOrElse {
        val home = System.getProperty("user.home", ".")
        new File(new File(new File(home), ".cache"), "sfl")
      }
    new File(base,
      s"${Sfl.version}-${RuntimeSources.digest}-${StdlibSources.digest}-${RuntimeSources.buildId}")

  private def stamp: File = new File(cacheDir, "ready")

  private def cacheIsBuilt: Boolean = stamp.isFile

  /** Removes every cached build, so the next compile starts from the sources. */
  def clearCache(): Unit =
    def rm(f: File): Unit =
      if f.isDirectory then
        val kids = f.listFiles()
        if kids != null then kids.foreach(rm)
      f.delete()
    val siblings = cacheDir.getParentFile.listFiles()
    if siblings != null then siblings.foreach(rm)

  final case class BuildReport(runtimeObjects: Int, libraryModules: Int, seconds: Double)

  /**
   * Builds both archives if they are not already in the cache. Called before the
   * first link; safe to call repeatedly.
   */
  def ensureCache(verbose: Boolean = false): Option[BuildReport] =
    if cacheIsBuilt then None
    else
      val started = System.nanoTime()
      val clang = clangOrFail()
      // Build into a private staging directory and rename it into place at the end.
      // A rename is atomic, so a concurrent `sfl -c` either sees the complete old
      // cache or the complete new one — never an archive that is half written,
      // which links without error and produces a binary that crashes at startup.
      val staging = new File(cacheDir.getParentFile,
        s"${cacheDir.getName}.tmp-${java.util.UUID.randomUUID().toString.take(8)}")
      def rmTree(f: File): Unit =
        if f.isDirectory then Option(f.listFiles()).foreach(_.foreach(rmTree))
        f.delete()
      rmTree(staging)
      val srcDir = new File(staging, "src")
      srcDir.mkdirs()
      for (name, text) <- RuntimeSources.files do
        FileUtil.write(new File(srcDir, name).getPath, text)

      val objs = for (name, _) <- RuntimeSources.files if name.endsWith(".c") yield
        val obj = new File(srcDir, name.stripSuffix(".c") + ".o")
        if verbose then Console.err.println(s"  compiling runtime/$name")
        val cmd = Seq(clang, "-std=c11", "-O2", "-fPIC",
          "-ffunction-sections", "-fdata-sections", "-Wall",
          "-Wno-unused-parameter", "-Wno-unused-function") ++ sdkFlags ++
          Seq("-c", new File(srcDir, name).getPath, "-o", obj.getPath)
        val (code, out) = run(cmd)
        if code != 0 then
          throw new CompileError(s"the SFL runtime failed to build ($name)", Pos.none, SourceRef.unknown)
            .note(out.trim.linesIterator.take(10).mkString("; "))
        obj

      val rtLib = new File(staging, "libsflrt.a")
      archive(rtLib, objs.toSeq)

      val stdObjs = buildStdlib(staging, clang, verbose)
      val stdLib = new File(staging, "libsflstd.a")
      archive(stdLib, stdObjs)

      FileUtil.write(new File(staging, "ready").getPath, s"${Sfl.version}\n")
      // Someone else may have completed the same build while this one ran; their
      // cache is as good as ours, so keep whichever landed first.
      if cacheIsBuilt then
        rmTree(staging)
      else if !staging.renameTo(cacheDir) then
        if !cacheIsBuilt then
          rmTree(staging)
          throw new CompileError("could not move the built runtime cache into place",
            Pos.none, SourceRef.unknown)
            .note(s"tried to rename ${staging.getPath} to ${cacheDir.getPath}")
        else rmTree(staging)
      Some(BuildReport(objs.size, stdObjs.size, (System.nanoTime() - started) / 1e9))

  private def archive(lib: File, objs: Seq[File]): Unit =
    lib.delete()
    val ar = which("ar").getOrElse("/usr/bin/ar")
    val (code, out) = run(Seq(ar, "rcs", lib.getPath) ++ objs.map(_.getPath))
    if code != 0 then
      throw new CompileError(s"could not build ${lib.getName}", Pos.none, SourceRef.unknown)
        .note(out.trim)

  /**
   * Compiles the SFL-written part of the library with the SFL compiler itself. Each
   * module becomes one object file, which is the granularity the linker drops at.
   */
  private def buildStdlib(staging: File, clang: String, verbose: Boolean): Seq[File] =
    val dir = new File(staging, "std")
    dir.mkdirs()
    for (module, source) <- Stdlib.modules yield
      if verbose then Console.err.println(s"  compiling stdlib/$module.sfl")
      val name = s"stdlib/$module.sfl"
      val (block, ref) =
        try Sfl.compile(source, name)
        catch
          case e: IncompleteInput => throw Err.fromIncomplete(e, SourceRef(name, source))
      val compiler = new Compiler(Sfl.globals, ref)
      compiler.buildAsLibrary(module)
      val ir = compiler.compile(block)
      val irFile = new File(dir, s"$module.ll")
      FileUtil.write(irFile.getPath, ir)
      val obj = new File(dir, s"$module.o")
      val cmd = Seq(clang, "-O2", "-fPIC", "-ffunction-sections", "-fdata-sections",
        "-Wno-override-module") ++ sdkFlags ++
        Seq("-c", "-x", "ir", irFile.getPath, "-o", obj.getPath)
      val (code, out) = run(cmd)
      if code != 0 then
        throw new CompileError(s"the standard library module '$module' failed to build",
          Pos.none, SourceRef.unknown)
          .note(out.trim.linesIterator.take(10).mkString("; "))
      obj

  // -------------------------------------------------------------------------
  // Building a package archive
  // -------------------------------------------------------------------------

  /**
   * Compiles every module of a package into `bin/<target>/lib.a`, with an
   * `abi.json` beside it describing what each object holds. Each module is compiled
   * on its own — its siblings resolve as external symbols the archive references,
   * exactly as a program that links it will — and one object per module keeps the
   * linker's drop granularity. Returns the manifest that was written.
   */
  def buildPackage(dir: File, verbose: Boolean = false): PkgTool.Binary.Abi =
    val clang = clangOrFail()
    val manifest = PkgTool.readManifest(dir)
    val pkg = manifest.name
    val target = PkgTool.Binary.targetTriple
    val versionDir = dir.getCanonicalPath + File.separator

    // Every .sfl under the package, but not what lives in the bin/ output tree or a
    // nested package root.
    val modules = collectModules(dir, dir)
    if modules.isEmpty then
      throw new CompileError(s"package '$pkg' has no .sfl modules to build", Pos.none, SourceRef.unknown)

    val staging = new File(dir, s"bin/.staging-${java.util.UUID.randomUUID().toString.take(8)}")
    staging.mkdirs()
    try
      val objs = scala.collection.mutable.ArrayBuffer.empty[File]
      val abiModules = scala.collection.mutable.ArrayBuffer.empty[PkgTool.Binary.ModuleAbi]
      for f <- modules do
        val moduleSource = f.getCanonicalPath
        val moduleRel = moduleSource.stripPrefix(versionDir).stripSuffix(".sfl")
          .replace(File.separatorChar, '/')
        if verbose then Console.err.println(s"  compiling $pkg/$moduleRel")
        val source = FileUtil.read(moduleSource)
        // A fresh importer per module, so each module's imports inline into its own
        // tree and every module sees its siblings to reference them.
        Sfl.importer = new Importer(f.getParentFile)
        val (block, ref) =
          try Sfl.compile(source, moduleSource)
          catch case e: IncompleteInput => throw Err.fromIncomplete(e, SourceRef(moduleSource, source))
        val compiler = new Compiler(Sfl.globals, ref)
        compiler.buildAsPackageModule(pkg, moduleRel, versionDir, moduleSource)
        val irText = compiler.compile(block)
        val irFile = new File(staging, moduleRel.replace('/', '_') + ".ll")
        FileUtil.write(irFile.getPath, irText)
        val obj = new File(staging, moduleRel.replace('/', '_') + ".o")
        val (code, out) = run(Seq(clang, "-O2", "-fPIC", "-ffunction-sections", "-fdata-sections",
          "-Wno-override-module") ++ sdkFlags ++ Seq("-c", "-x", "ir", irFile.getPath, "-o", obj.getPath))
        if code != 0 then
          throw new CompileError(s"package module '$pkg/$moduleRel' failed to build",
            Pos.none, SourceRef.unknown).note(out.trim.linesIterator.take(10).mkString("; "))
        objs += obj
        abiModules += PkgTool.Binary.ModuleAbi(moduleRel, PkgTool.Binary.sourceDigest(source),
          compiler.moduleExports, compiler.usedPrimNames, compiler.usedStdlibNames, compiler.packageDeps)

      val outDir = PkgTool.Binary.binDir(dir, target)
      outDir.mkdirs()
      archive(PkgTool.Binary.archiveFile(dir, target), objs.toSeq)
      val abi = PkgTool.Binary.Abi(pkg, manifest.version.toString, PkgTool.Binary.currentSfl,
        target, PkgTool.Binary.currentRuntimeDigest, PkgTool.Binary.currentStdlibDigest,
        PkgTool.Binary.currentBuildId, abiModules.toList)
      PkgTool.Binary.writeAbi(PkgTool.Binary.abiFile(dir, target), abi)
      abi
    finally
      def rm(f: File): Unit =
        if f.isDirectory then Option(f.listFiles()).foreach(_.foreach(rm))
        f.delete()
      rm(staging)

  /** Every `.sfl` under `dir`, skipping the bin/ output tree and any nested package. */
  private def collectModules(root: File, dir: File): Seq[File] =
    val kids = dir.listFiles()
    if kids == null then Nil
    else kids.toSeq.flatMap { f =>
      if f.isDirectory then
        val nested = (dir ne root) && new File(f, "sfl.pkg").isFile
        if f.getName == "bin" || f.getName == "sfl_packages" then Nil
        else if nested then Nil // a nested package has its own build
        else collectModules(root, f)
      else if f.getName.endsWith(".sfl") then Seq(f)
      else Nil
    }

  // -------------------------------------------------------------------------
  // Linking a program
  // -------------------------------------------------------------------------

  final case class Result(exePath: String, irPath: String, clangCommand: String)

  /**
   * Writes the IR and links it against the cached archives. `keepArtifacts` leaves
   * the `.ll` beside the executable, which is what makes the output inspectable.
   */
  def link(ir: String, output: String, optLevel: String, keepArtifacts: Boolean,
           extraArchives: Seq[File] = Nil): Result =
    val clang = clangOrFail()
    ensureCache()
    val base = new File(output).getAbsoluteFile
    val dir = if base.getParentFile != null then base.getParentFile else new File(".")
    val stem = base.getName

    val irFile = new File(dir, s"$stem.ll")
    FileUtil.write(irFile.getPath, ir)

    // The program references its linked packages, which reference the library, which
    // references the runtime, so the archives go in that order; a static archive is
    // only searched once.
    val cmd = Seq(clang, s"-$optLevel", "-Wno-override-module") ++ sdkFlags ++
      // `-x ir` would otherwise claim the archives too, and clang would try to
      // parse `!<arch>` as LLVM assembly.
      Seq("-x", "ir", irFile.getPath, "-x", "none") ++
      extraArchives.map(_.getPath) ++
      Seq(new File(cacheDir, "libsflstd.a").getPath,
        new File(cacheDir, "libsflrt.a").getPath) ++
      // -ldl for the TLS shim, which reaches libssl through dlopen; on macOS and
      // recent glibc it is part of libc, and passing it is harmless there.
      stripFlags ++ Seq("-lm", "-lpthread") ++
      (if isMac then Nil else Seq("-ldl")) ++ Seq("-o", base.getPath)

    val (code, out) = run(cmd)
    if !keepArtifacts then irFile.delete()
    if code != 0 then
      // A clang failure here means the compiler emitted bad IR, which is a compiler
      // bug; surfacing clang's own message is the fastest route to diagnosing it.
      val e = new CompileError("clang rejected the generated code", Pos.none, SourceRef.unknown)
      e.note(out.trim.linesIterator.take(8).mkString("; "))
      e.note(s"command: ${cmd.mkString(" ")}")
      throw e
    Result(base.getPath, if keepArtifacts then irFile.getPath else "", cmd.mkString(" "))
