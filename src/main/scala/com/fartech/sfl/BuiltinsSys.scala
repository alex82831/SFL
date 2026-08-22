package com.fartech.sfl

import Builtins.*
import java.io.File
import scala.scalanative.posix.stdlib
import scala.scalanative.posix.sys.stat
import scala.scalanative.posix.sys.statOps.statOps
import scala.scalanative.posix.timeOps.timespecValueOps
// Import narrowly: scalanative.unsafe also exports a `define`, which would shadow ours.
import scala.scalanative.unsafe.{CString, Ptr, Zone, extern, fromCString, stackalloc, toCString}
import scala.scalanative.unsigned.{UInt, UnsignedRichInt}
import scala.scalanative.meta.LinktimeInfo

/**
 * `System.getenv` reads a snapshot taken at process start, so it never sees a later
 * `setenv`. Reading through the C library keeps getEnv and setEnv consistent.
 */
@scala.scalanative.unsafe.extern
private object CEnv:
  def getenv(name: CString): CString = extern

/**
 * macOS has no /proc; the loader is asked for the executable's path instead. The
 * extern is only reachable behind a LinktimeInfo.isMac test, so linking on other
 * platforms never sees the symbol.
 */
@scala.scalanative.unsafe.extern
private object CDyld:
  @scala.scalanative.unsafe.name("_NSGetExecutablePath")
  def nsGetExecutablePath(buf: CString, size: Ptr[UInt]): Int = extern

/** Filesystem, process, environment, clock and network builtins. */
object BuiltinsSys:

  /** Command line arguments after the script name, published to scripts via `args()`. */
  var scriptArgs: Array[String] = Array.empty

  /** Path of the script being run, published via `scriptPath()`. Empty in the REPL. */
  var scriptPath: String = ""

  def register(): Unit =
    files()
    paths()
    system()
    time()
    net()

  private def files(): Unit =
    define("readFile", 1, 1, "fs", "readFile(path)", "Reads a whole file as a UTF-8 string.") { a =>
      VStr(FileUtil.read(argStr(a, 0, "readFile")))
    }

    define("readLines", 1, 1, "fs", "readLines(path)", "Reads a file and splits it into lines.") { a =>
      VArr.of(FileUtil.read(argStr(a, 0, "readLines")).split("\r\n|\n|\r", -1).iterator.map(VStr(_)))
    }

    define("writeFile", 2, 2, "fs", "writeFile(path, content)",
      "Writes content to a file, replacing it if it exists.") { a =>
      FileUtil.write(argStr(a, 0, "writeFile"), a(1).display, append = false)
      VNull
    }

    define("appendFile", 2, 2, "fs", "appendFile(path, content)", "Appends content to a file.") { a =>
      FileUtil.write(argStr(a, 0, "appendFile"), a(1).display, append = true)
      VNull
    }

    define("removeFile", 1, 1, "fs", "removeFile(path)",
      "Deletes a file; returns true when something was removed.") { a =>
      VBool.of(new File(argStr(a, 0, "removeFile")).delete())
    }

    define("listFiles", 1, 1, "fs", "listFiles(dir)",
      "Names of the entries in a directory, sorted; null when the path is not a directory.") { a =>
      val f = new File(argStr(a, 0, "listFiles"))
      val names = f.list()
      if names == null then VNull
      else
        java.util.Arrays.sort(names.asInstanceOf[Array[Object]])
        VArr.of(names.iterator.map(VStr(_)))
    }

    define("exists", 1, 1, "fs", "exists(path)", "True when the path exists.") { a =>
      VBool.of(new File(argStr(a, 0, "exists")).exists())
    }
    define("isDir", 1, 1, "fs", "isDir(path)", "True when the path is a directory.") { a =>
      VBool.of(new File(argStr(a, 0, "isDir")).isDirectory)
    }
    define("isFile", 1, 1, "fs", "isFile(path)", "True when the path is a regular file.") { a =>
      VBool.of(new File(argStr(a, 0, "isFile")).isFile)
    }
    define("fileSize", 1, 1, "fs", "fileSize(path)", "Size of a file in bytes, or -1 when missing.") { a =>
      val f = new File(argStr(a, 0, "fileSize"))
      VInt.of(if f.exists() then f.length() else -1L)
    }
    define("mkdirs", 1, 1, "fs", "mkdirs(path)", "Creates a directory and any missing parents.") { a =>
      val f = new File(argStr(a, 0, "mkdirs"))
      VBool.of(f.isDirectory || f.mkdirs())
    }
    define("fileMtime", 1, 1, "fs", "fileMtime(path)",
      "Last modification time in milliseconds since the epoch, or -1 when missing.") { a =>
      VInt.of(mtimeMillis(argStr(a, 0, "fileMtime")))
    }

    define("copyFile", 2, 2, "fs", "copyFile(from, to)", "Copies a file, replacing the destination.") { a =>
      val from = argStr(a, 0, "copyFile")
      val to = argStr(a, 1, "copyFile")
      try
        java.nio.file.Files.copy(
          java.nio.file.Paths.get(from),
          java.nio.file.Paths.get(to),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
        VBool.True
      catch case e: java.io.IOException => Err.eval(s"copyFile: ${e.getMessage}")
    }

    define("moveFile", 2, 2, "fs", "moveFile(from, to)", "Renames or moves a file.") { a =>
      VBool.of(new File(argStr(a, 0, "moveFile")).renameTo(new File(argStr(a, 1, "moveFile"))))
    }

    define("tempFile", 0, 1, "fs", "tempFile(suffix?)", "Creates an empty temporary file and returns its path.") { a =>
      val suffix = if a.nonEmpty then argStr(a, 0, "tempFile") else ".tmp"
      try VStr(File.createTempFile("sfl-", suffix).getAbsolutePath)
      catch case e: java.io.IOException => Err.eval(s"tempFile: ${e.getMessage}")
    }

  private def paths(): Unit =
    define("absPath", 1, 1, "fs", "absPath(path)", "Absolute, normalised form of a path.") { a =>
      VStr(new File(argStr(a, 0, "absPath")).getCanonicalPath)
    }
    define("baseName", 1, 1, "fs", "baseName(path)", "Final component of a path.") { a =>
      VStr(new File(argStr(a, 0, "baseName")).getName)
    }
    define("dirName", 1, 1, "fs", "dirName(path)", "Everything but the final component of a path.") { a =>
      val p = new File(argStr(a, 0, "dirName")).getParent
      if p == null then VStr(".") else VStr(p)
    }
    define("joinPath", 1, -1, "fs", "joinPath(...)", "Joins path segments with the platform separator.") { a =>
      val parts = a.map(_.display).filter(_.nonEmpty)
      if parts.isEmpty then VStr("")
      else VStr(parts.reduceLeft((acc, p) => new File(acc, p).getPath))
    }

  private def system(): Unit =
    define("execute", 1, 2, "sys", "execute(command, useShell?)",
      "Runs a command and returns {code, out, err}; useShell defaults to true.") { a =>
      val useShell = if a.length > 1 then a(1).truthy else true
      val (code, out, err) = Proc.run(argStr(a, 0, "execute"), useShell)
      VObj.of(Seq("code" -> VInt.of(code.toLong), "out" -> VStr(out), "err" -> VStr(err)))
    }

    define("exit", 0, 1, "sys", "exit(code?)", "Stops the program with the given exit code.") { a =>
      throw new ExitSignal(if a.isEmpty then 0 else argIdx(a, 0, "exit"))
    }

    define("passthrough", 1, 2, "sys", "passthrough(cmd, args?)",
      "Runs a program wired to this process's own stdin/stdout/stderr and returns " +
        "its exit code. Unlike execute() nothing is captured, so the child can be " +
        "interactive and its output appears as it happens.") { a =>
      val cmd = argStr(a, 0, "passthrough")
      val extra: Seq[String] =
        if a.length < 2 then Nil
        else argArr(a, 1, "passthrough").items.toSeq.zipWithIndex.map {
          case (VStr(s), _) => s
          case (other, i) =>
            Err.eval(s"passthrough: argument ${i + 1} of the args array must be a string, got ${other.typeName}")
        }
      try
        val pb = new ProcessBuilder(java.util.Arrays.asList(cmd +: extra *))
        pb.inheritIO()
        VInt.of(pb.start().waitFor().toLong)
      catch case e: java.io.IOException =>
        Err.evalHint(s"passthrough: cannot run '$cmd'", e.getMessage)
    }

    define("getEnv", 1, 2, "sys", "getEnv(name, default?)", "Reads an environment variable.") { a =>
      val v = envVar(argStr(a, 0, "getEnv"))
      if v == null then opt(a, 1) else VStr(v)
    }

    define("setEnv", 2, 2, "sys", "setEnv(name, value)",
      "Sets an environment variable for this process and its children.") { a =>
      val name = argStr(a, 0, "setEnv")
      val value = a(1).display
      Zone.acquire { implicit z =>
        stdlib.setenv(toCString(name), toCString(value), 1)
      }
      VNull
    }

    define("cwd", 0, 0, "sys", "cwd()", "Current working directory.") { _ =>
      VStr(new File(".").getCanonicalPath)
    }

    define("args", 0, 0, "sys", "args()", "Command line arguments passed after the script name.") { _ =>
      VArr.of(scriptArgs.iterator.map(VStr(_)))
    }

    define("scriptPath", 0, 0, "sys", "scriptPath()",
      "Absolute path of the running script, or null in the REPL.") { _ =>
      if scriptPath.isEmpty then VNull else VStr(new File(scriptPath).getAbsolutePath)
    }
    define("exePath", 0, 0, "sys", "exePath()",
      "Absolute path of the running sfl binary, or null where the platform cannot say.") { _ =>
      executablePath match
        case Some(p) => VStr(p)
        case None    => VNull
    }

    define("sleep", 1, 1, "sys", "sleep(ms)", "Pauses for the given number of milliseconds.") { a =>
      val ms = argInt(a, 0, "sleep")
      if ms < 0 then Err.eval("sleep: duration must not be negative")
      Thread.sleep(ms)
      VNull
    }

    define("osName", 0, 0, "sys", "osName()", "Name of the host operating system.") { _ =>
      VStr(System.getProperty("os.name", "unknown"))
    }

    define("uuid", 0, 0, "sys", "uuid()", "A random UUID as a string.") { _ =>
      VStr(java.util.UUID.randomUUID().toString)
    }

  private def time(): Unit =
    define("timeMillis", 0, 0, "time", "timeMillis()", "Milliseconds since the Unix epoch.") { _ =>
      VInt.of(System.currentTimeMillis())
    }

    define("timeNanos", 0, 0, "time", "timeNanos()",
      "A monotonic nanosecond counter, suitable for measuring durations.") { _ =>
      VInt.of(System.nanoTime())
    }

    define("formatTime", 1, 3, "time", "formatTime(millis, format?, utc?)",
      "Formats a timestamp with strftime directives; default '%Y-%m-%d %H:%M:%S'.") { a =>
      val millis = argInt(a, 0, "formatTime")
      val fmt = if a.length > 1 then argStr(a, 1, "formatTime") else "%Y-%m-%d %H:%M:%S"
      val utc = a.length > 2 && a(2).truthy
      VStr(TimeUtil.format(millis, fmt, utc))
    }

    define("timeParts", 1, 2, "time", "timeParts(millis, utc?)",
      "Breaks a timestamp into {year, month, day, hour, minute, second, weekday, yearday, millis}.") { a =>
      TimeUtil.parts(argInt(a, 0, "timeParts"), a.length > 1 && a(1).truthy)
    }

  /**
   * The HTTP builtins are stdlib/http.sfl — one source, shared with the compiler —
   * over the socket, buffer and TLS primitives of BuiltinsIpc. These registry rows
   * carry the listing, signature and doc (the compiled builtins table reads the doc
   * from here); name resolution goes through the sentinel StdlibModules installs,
   * so the delegating impl below only runs if something calls the registry value
   * directly.
   */
  private def net(): Unit =
    def sflFn(name: String, doc: String): Unit =
      val f = Stdlib.byName.getOrElse(name,
        throw new IllegalStateException(s"stdlib/http.sfl does not define '$name'"))
      define(name, f.min, f.max, "net", f.signature, doc) { a =>
        StdlibModules.load(f.module)
        Sfl.globals.valueOf(name) match
          case Some(v) if !v.isInstanceOf[VLazyStdlib] => Builtins.call(v, a.toSeq*)
          case _ =>
            Err.eval(s"internal error: stdlib module '${f.module}' did not define '$name'")
      }

    sflFn("httpGet", "Performs an HTTP GET and returns the response body as a string; " +
      "takes optional headers and a proxy (http, https, socks5(h) or socks4(a), " +
      "with username/password).")
    sflFn("httpPost", "Performs an HTTP POST and returns the response body as a string; " +
      "takes an optional content type, headers and a proxy.")
    sflFn("httpRequest", "Performs any HTTP request and returns {status, body}; takes an " +
      "optional body, content type, headers and a proxy.")
    sflFn("httpGetProxy", "HTTP GET through a proxy such as " +
      "'http://user:pass@127.0.0.1:8080', 'socks5://127.0.0.1:1080' or an object " +
      "{protocol, host, port, username, password}.")

  /**
   * The running binary's path: the loader's answer on macOS, /proc on Linux, and
   * nothing anywhere else yet. Canonicalised so callers can hand it to a subprocess
   * regardless of how sfl itself was invoked. The build tool uses this to run the
   * compiler it is part of, rather than whatever `sfl` is first on PATH.
   */
  private def executablePath: Option[String] =
    val raw =
      if LinktimeInfo.isMac then
        val size = stackalloc[UInt]()
        !size = 4096.toUInt
        val buf = stackalloc[Byte](4096)
        if CDyld.nsGetExecutablePath(buf, size) == 0 then Some(fromCString(buf)) else None
      else if LinktimeInfo.isLinux then Some("/proc/self/exe")
      else None
    raw.flatMap { p =>
      try Some(new File(p).getCanonicalPath)
      catch case _: java.io.IOException => None
    }

  /**
   * Modification time in milliseconds.
   *
   * `File.lastModified` reports whole seconds here, which is coarse enough for a
   * build tool to miss an edit made in the same second as the build that preceded
   * it — and coarser than the compiled runtime, which reads the nanosecond field
   * and so answered differently. stat(2) has what both need.
   */
  private def mtimeMillis(path: String): Long =
    Zone.acquire { implicit z =>
      val st = stackalloc[stat.stat]()
      if stat.stat(toCString(path), st) != 0 then -1L
      else st.st_mtim.tv_sec.toLong * 1000L + st.st_mtim.tv_nsec.toLong / 1000000L
    }

  private def envVar(name: String): String =
    Zone.acquire { implicit z =>
      val p = CEnv.getenv(toCString(name))
      if p == null then null else fromCString(p)
    }

