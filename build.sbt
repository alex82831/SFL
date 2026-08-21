import scala.scalanative.build.*
import scala.sys.process.*
import scala.util.Try

name         := "sfl"
version      := "0.8.2"
organization := "com.fartech"
scalaVersion := "3.8.4"

//
// Build knobs (override from the shell):
//   SFL_BUILD_MODE = debug | releaseFast | releaseFull   (default: releaseFast)
//   SFL_GC         = none | boehm | immix | commix       (default: immix)
//   SFL_LTO        = none | thin | full                  (default: none for debug, thin otherwise)
//
lazy val buildMode: Mode = sys.env.getOrElse("SFL_BUILD_MODE", "releaseFast") match {
  case "debug"       => Mode.debug
  case "releaseFull" => Mode.releaseFull
  case _             => Mode.releaseFast
}

lazy val buildGC: GC = sys.env.getOrElse("SFL_GC", "immix") match {
  case "none"   => GC.none
  case "boehm"  => GC.boehm
  case "commix" => GC.commix
  case _        => GC.immix
}

lazy val buildLTO: LTO = sys.env.get("SFL_LTO").map(_.toLowerCase) match {
  case Some("thin") => LTO.thin
  case Some("full") => LTO.full
  case Some("none") => LTO.none
  case _            => if (buildMode == Mode.debug) LTO.none else LTO.thin
}

lazy val isMac: Boolean = System.getProperty("os.name", "").toLowerCase.contains("mac")

//
// Discover the toolchain instead of hard-coding one developer's paths.
// Every lookup degrades to an empty result when the tool or library is absent,
// so the build still works on Linux and on Macs without Homebrew.
//
def cmd(args: String*): Option[String] =
  Try(args.!!.trim).toOption.filter(_.nonEmpty)

lazy val macSdkPath: Option[String] =
  if (isMac) cmd("xcrun", "--sdk", "macosx", "--show-sdk-path").filter(new File(_).isDirectory) else None

lazy val brewPrefix: Option[String] =
  if (isMac) cmd("brew", "--prefix").filter(new File(_).isDirectory) else None

// scala-native-crypto links libcrypto, which Homebrew keeps out of the default paths.
// (TLS itself is dlopen'd at run time by the runtime and needs nothing at link time.)
lazy val brewLibDirs: Seq[String] =
  brewPrefix.toSeq.flatMap { prefix =>
    Seq(s"$prefix/lib") ++
      Seq("openssl@3")
        .flatMap(f => cmd("brew", "--prefix", f))
        .map(p => s"$p/lib")
  }.filter(new File(_).isDirectory).distinct

lazy val sdkFlags: Seq[String] = macSdkPath.toSeq.flatMap(p => Seq("-isysroot", p))

//
// On macOS, prefer the clang that ships with the active Xcode. Any other clang on
// PATH (Homebrew LLVM, a hand-installed llvm.org build) is likely older than the
// macOS SDK headers it would have to compile, which fails inside libc++.
//
lazy val xcodeClang: Option[File] = if (isMac) cmd("xcrun", "--find", "clang").map(file) else None
lazy val xcodeClangPP: Option[File] = if (isMac) cmd("xcrun", "--find", "clang++").map(file) else None

nativeConfig ~= { c =>
  val base = c
    .withMode(buildMode)
    .withGC(buildGC)
    .withLTO(buildLTO)
    .withOptimize(true)
    .withLinkStubs(false)
    .withIncrementalCompilation(true)
    // spawn() and the channel primitives need real OS threads.
    .withMultithreading(true)
    .withLinkingOptions(c.linkingOptions ++ brewLibDirs.map("-L" + _) ++ sdkFlags)
    .withCompileOptions(c.compileOptions ++ sdkFlags)
  val withCc = xcodeClang.filter(_.exists).fold(base)(f => base.withClang(f.toPath))
  xcodeClangPP.filter(_.exists).fold(withCc)(f => withCc.withClangPP(f.toPath))
}

libraryDependencies ++= Seq(
  // Supplies java.security.MessageDigest, which Scala Native's javalib omits.
  "com.github.lolgab" %%% "scala-native-crypto" % "0.4.0"
)

scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-source:3.8",
  "-Wunused:imports"
)

//
// The C runtime that compiled programs link against lives in runtime/ as ordinary
// C, so it can be read, compiled and debugged on its own. It is embedded into the
// sfl binary here — base64 rather than string literals, so no escaping rule can
// silently corrupt a source file — and written back out to the build cache the
// first time someone compiles a program.
//
Compile / sourceGenerators += Def.task {
  val dir = baseDirectory.value / "runtime"
  val out = (Compile / sourceManaged).value / "RuntimeSources.scala"
  val files = Option(dir.listFiles()).getOrElse(Array.empty[File])
    .filter(f => f.isFile && (f.getName.endsWith(".c") || f.getName.endsWith(".h") ||
                              f.getName.endsWith(".def")))
    .sortBy(_.getName)
  // A string constant is capped at 64 KB in a class file and the runtime sources are
  // bigger, so each file is emitted as a sequence of chunks. They are joined at load
  // rather than with `+`, which the compiler would fold straight back into one
  // oversized constant.
  def encode(f: File): String = {
    val b64 = java.util.Base64.getEncoder.encodeToString(IO.readBytes(f))
    b64.grouped(32000).map(part => "\"" + part + "\"").mkString("Seq(", ", ", ")")
  }
  val entries = files.map(f => "    (\"" + f.getName + "\", " + encode(f) + ")").mkString(",\n")
  // A digest of the sources names the cache directory, so a changed runtime is
  // rebuilt automatically and two versions of sfl never share one cache.
  val digest = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    files.foreach(f => md.update(IO.readBytes(f)))
    md.digest().take(8).map("%02x".format(_)).mkString
  }
  val stdDir = baseDirectory.value / "stdlib"
  val stdFiles = Option(stdDir.listFiles()).getOrElse(Array.empty[File])
    .filter(f => f.isFile && f.getName.endsWith(".sfl")).sortBy(_.getName)
  val stdEntries = stdFiles.map(f => "    (\"" + f.getName + "\", " + encode(f) + ")").mkString(",\n")
  // The build tool travels the same way the stdlib does: SFL sources embedded in
  // the binary, so `sfl build` works wherever the binary goes.
  val btDir = baseDirectory.value / "buildtool"
  val btFiles = Option(btDir.listFiles()).getOrElse(Array.empty[File])
    .filter(f => f.isFile && f.getName.endsWith(".sfl")).sortBy(_.getName)
  val btEntries = btFiles.map(f => "    (\"" + f.getName + "\", " + encode(f) + ")").mkString(",\n")
  val stdDigest = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    stdFiles.foreach(f => md.update(IO.readBytes(f)))
    md.digest().take(8).map("%02x".format(_)).mkString
  }
  // Any change to the compiler itself must invalidate the archive cache too: the
  // stdlib objects in it were produced by that compiler, and a digest that only
  // covers the .sfl sources would silently reuse them.
  val scalaDigest = {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val scalaFiles = ((baseDirectory.value / "src" / "main" / "scala") ** "*.scala").get.sortBy(_.getPath)
    scalaFiles.foreach(f => md.update(IO.readBytes(f)))
    md.digest().take(8).map("%02x".format(_)).mkString
  }
  IO.write(out,
    s"""package com.fartech.sfl
       |
       |/** Generated from runtime/ by the build. Do not edit. */
       |object RuntimeSources:
       |  val digest: String = "$digest"
       |  /** Fingerprint of the compiler's own sources; part of the cache key. */
       |  val buildId: String = "$scalaDigest"
       |
       |  private val encoded: Seq[(String, Seq[String])] = Seq(
       |$entries
       |  )
       |
       |  /** File name -> contents, decoded once. */
       |  lazy val files: Seq[(String, String)] =
       |    encoded.map((n, cs) =>
       |      (n, new String(java.util.Base64.getDecoder.decode(cs.mkString), "UTF-8")))
       |
       |  def text(name: String): String =
       |    files.collectFirst { case (n, t) if n == name => t }
       |      .getOrElse(throw new NoSuchElementException(s"runtime source '$$name' is missing"))
       |
       |/** Generated from stdlib/ by the build. Do not edit. */
       |object StdlibSources:
       |  val digest: String = "$stdDigest"
       |
       |  private val encoded: Seq[(String, Seq[String])] = Seq(
       |$stdEntries
       |  )
       |
       |  lazy val files: Seq[(String, String)] =
       |    encoded.map((n, cs) =>
       |      (n, new String(java.util.Base64.getDecoder.decode(cs.mkString), "UTF-8")))
       |
       |/** Generated from buildtool/ by the build. Do not edit. */
       |object BuildToolSources:
       |  private val encoded: Seq[(String, Seq[String])] = Seq(
       |$btEntries
       |  )
       |
       |  lazy val files: Seq[(String, String)] =
       |    encoded.map((n, cs) =>
       |      (n, new String(java.util.Base64.getDecoder.decode(cs.mkString), "UTF-8")))
       |
       |  def text(name: String): String =
       |    files.collectFirst { case (n, t) if n == name => t }
       |      .getOrElse(throw new NoSuchElementException(s"build tool source '$$name' is missing"))
       |""".stripMargin)
  Seq(out)
}.taskValue

Compile / mainClass := Some("Main")

enablePlugins(ScalaNativePlugin)
enablePlugins(ScalaNativeJUnitPlugin)
