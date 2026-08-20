package com.fartech.sfl

import com.fartech.sfl.JsonUtils.{jsonArrayString, jsonString}
import sttp.client4.{DefaultSyncBackend, UriContext, basicRequest, quickRequest}

import java.io.{ByteArrayOutputStream, File, PrintWriter}
import java.net.http.HttpClient.{Redirect, Version}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{InetSocketAddress, ProxySelector, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.UUID
import scala.::
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.io.StdIn.readLine
import scala.language.postfixOps
import scala.runtime.LazyVals.Names.cas
import scala.sys.exit
import scala.sys.process.*

object PreDefs {

  private def execute(cmd: String): (Int, String, String) = {
    val stdoutStream = new ByteArrayOutputStream
    val stderrStream = new ByteArrayOutputStream
    val stdoutWriter = new PrintWriter(stdoutStream)
    val stderrWriter = new PrintWriter(stderrStream)
    val exitValue = cmd.!(ProcessLogger(stdoutWriter.println, stderrWriter.println))
    stdoutWriter.close()
    stderrWriter.close()
    (exitValue, stdoutStream.toString, stderrStream.toString)
  }

  private def newFun(name: String, numArgs: Int, imp: List[Option[Any]] => Expr): FunDefExpr =
    FunDefExpr(
      name,
      (numArgs to 1 by -1).foldLeft(List.empty[String]) { (prev, n) => s"_predef_${name}_arg_$n" :: prev },
      Environment.GLOBAL,
      () => {
        val args = (numArgs to 1 by -1).foldLeft(List.empty[Option[Any]]) {
          (prev, n) => Environment.lookupVariable((s"_predef_${name}_arg_$n", Environment.currentScope)) :: prev
        }
        imp(args.filter(arg => arg.isDefined && arg.get != None))
      }
    )

  /*
  * execute(cmd)
  */
  private def _execute_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(cmd: String) =>
        ValueExpr(execute(cmd))
      case _ => throw IllegalArgumentException("invalid argument")
  }

  private val _predef_execute: FunDefExpr = newFun("execute", 1, _execute_imp)

  /*
  * println(str)
  */
  private def _println_imp(args: List[Option[Any]]): Expr = {
    if args.isEmpty then
      println()
    else
      args.head match
        case Some(v) =>
          if v != NoneExpr then println(v)
          else println()
        case _ => println()
    NoneExpr
  }

  private val _predef_println: FunDefExpr = newFun("println", 1, _println_imp)

  /*
  * print(str)
  */
  private def _print_imp(args: List[Option[Any]]): Expr = {
    if args.head.isDefined then
      print(args.head.get)
    else
      print("")
    NoneExpr
  }

  private val _predef_print: FunDefExpr = newFun("print", 1, _print_imp)

  /*
  * length(str)
  */
  private def _length_imp(args: List[Option[Any]]): Expr = {
    if args.head.isDefined then
      args.head.get match
        case _: NumberLiteral => throw IllegalArgumentException("Cannot compute length of a number")
        case s: String => ValueExpr(IntegerLiteral(s.length))
    else
      ValueExpr(IntegerLiteral(0))
  }

  private val _predef_length: FunDefExpr = newFun("length", 1, _length_imp)

  /*
  * readFile(filename)
  */
  private def _readFile_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(s: String) =>
        ValueExpr(FileUtils.readFile(s))
      case _ => throw IllegalArgumentException("missing filename")
  }

  private val _predef_readFile: FunDefExpr = newFun("readFile", 1, _readFile_imp)

  /*
  * writeFile(filename, content)
  */
  private def _writeFile_imp(args: List[Option[Any]]): Expr = {
    args match
      case Some(filename: String) :: Some(content: String) :: Nil =>
        val p = PrintWriter(filename)
        p.write(content)
        p.close()
        NoneExpr
      case _ => throw IllegalArgumentException("missing filename or content")
  }

  private val _predef_writeFile: FunDefExpr = newFun("writeFile", 2, _writeFile_imp)

  /*
  * charAt(str, n)
  */
  private def _charAt_imp(args: List[Option[Any]]): Expr = {
    (args.head, args(1)) match
      case (Some(str: String), Some(n: NumberLiteral)) =>
        ValueExpr(str.charAt(n.toLong.toInt))
      case _ => throw IllegalArgumentException("invalid argument")
  }

  private val _predef_charAt: FunDefExpr = newFun("charAt", 2, _charAt_imp)

  /*
  * httpGetProxy(url, proxy)
  */
  private def _httpGetProxy_imp(args: List[Option[Any]]): Expr = {
    args match
      case Some(url: String) :: Some(proxy: String) :: Nil =>
        try
          val parts = proxy.split(":")
          val (host, port) =
            if parts.length >= 2 then (parts(0), parts(1).toInt)
            else (proxy, 80)
          val client = HttpClient
            .newBuilder()
            .followRedirects(Redirect.NORMAL)
            .version(Version.HTTP_1_1)
            .proxy(ProxySelector.of(new InetSocketAddress(host, port)))
            .build()
          val req = HttpRequest
            .newBuilder(URI.create(url))
            .GET()
            .build()
          val resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
          ValueExpr(resp.body())
        catch
          case e: Throwable => ValueExpr(e.getMessage)
      case _ => throw IllegalArgumentException("invalid argument")
  }

  private val _predef_httpGetProxy: FunDefExpr = newFun("httpGetProxy", 2, _httpGetProxy_imp)

  /*
  * httpGet(url)
  */
  private def _httpGet_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(s: String) =>
        val response = quickRequest.get(uri"$s").send(DefaultSyncBackend())
        ValueExpr(response.body)
      case _ => ValueExpr(args.head.toString)
  }

  private val _predef_httpGet: FunDefExpr = newFun("httpGet", 1, _httpGet_imp)

  /*
  * strFind(str, strToFind)
  */
  private def _strFind_imp(args: List[Option[Any]]): Expr = {
    (args.head, args(1)) match
      case (Some(s1: String), Some(s2: String)) =>
        val idx = s1.indexOf(s2)
        ValueExpr(IntegerLiteral(idx))
      case _ => throw IllegalArgumentException("invalid argument")
  }

  private val _predef_strFind: FunDefExpr = newFun("strFind", 2, _strFind_imp)

  /*
  * toString(arg)
  */
  private def _toString_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(s: String) => ValueExpr(s)
      case Some(n: NumberLiteral) => ValueExpr(n.toString)
      case Some(m: mutable.HashMap[String, Any]) => ValueExpr(jsonString(m))
      case Some(l: ListBuffer[Any]) => ValueExpr(jsonArrayString(l))
      case _ => ValueExpr(args.head.toString)
  }

  private val _predef_toString: FunDefExpr = newFun("toString", 1, _toString_imp)

  /*
  * exit(retCode)
  */
  private def _exit_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(code: NumberLiteral) => exit(code.toInt)
      case _ => exit(0)
  }

  private val _predef_exit: FunDefExpr = newFun("exit", 1, _exit_imp)

  /*
  * readLine()
  */
  private def _readLine_imp(args: List[Option[Any]]): Expr = {
    ValueExpr(readLine())
  }

  private val _predef_readLine: FunDefExpr = newFun("readLine", 0, _readLine_imp)

  /*
  * subString(str, start, end)
  */
  private def _subString_imp(args: List[Option[Any]]): Expr = {
    args match
      case Some(str: String) :: Some(start: NumberLiteral) :: Some(end: NumberLiteral) :: Nil =>
        ValueExpr(str.substring(start.toInt, end.toInt))
      case _ =>
        NoneExpr
  }

  private val _predef_subString: FunDefExpr = newFun("subString", 3, _subString_imp)

  /*
  * listFiles(path)
  */
  private def _listFiles_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(path: String) =>
        val file = new File(path)
        if file.isFile then
          NoneExpr
        else
          ValueExpr(
            ListBuffer.empty ++ file.list()
          )
      case _ =>
        NoneExpr
  }

  private val _predef_listFiles: FunDefExpr = newFun("listFiles", 1, _listFiles_imp)

  /*
  * isDir(path)
  */
  private def _isDir_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(path: String) =>
        val file = File(path)
        ValueExpr(file.isDirectory)
      case _ =>
        NoneExpr
  }

  private val _predef_isDir: FunDefExpr = newFun("isDir", 1, _isDir_imp)

  /*
  * removeFile(path)
  */
  private def _removeFile_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(path: String) =>
        ValueExpr(Files.deleteIfExists(Paths.get(path)))
      case _ =>
        NoneExpr
  }

  private val _predef_removeFile: FunDefExpr = newFun("removeFile", 1, _removeFile_imp)

  /*
  * parse(expr)
  */
  private def _parse_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(line: String) =>
        val holder = ListBuffer[Expr]()
        try {
          SFLParser(line).parseByLine(
            (expr, _) => holder.append(expr)
          )(
            (msg, _) => {
              println(Console.RED + msg + Console.RESET)
            }
          )
        } catch {
          case e: Throwable =>
            println(Console.RED + e.getMessage + Console.RESET)
        }
        if holder.isEmpty then
          NoneExpr
        else
          holder.head
      case _ =>
        NoneExpr
  }

  private val _predef_parse: FunDefExpr = newFun("parse", 1, _parse_imp)

  /*
  * eval(expr)
  */
  private def _eval_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(expr: Expr) =>
        expr.compute.get
      case _ =>
        NoneExpr
  }

  private val _predef_eval: FunDefExpr = newFun("eval", 1, _eval_imp)

  /*
  * timeMillis()
  */
  private def _timeMillis_imp(args: List[Option[Any]]): Expr = {
    ValueExpr(IntegerLiteral(System.currentTimeMillis()))
  }

  private val _predef_timeMillis: FunDefExpr = newFun("timeMillis", 0, _timeMillis_imp)

  /*
  * iter(f)
  */
  private def _iter_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(m: mutable.HashMap[String, Any]) => ValueExpr(IteratorExpr(m))
      case Some(l: ListBuffer[Any]) => ValueExpr(IteratorExpr(l))
      case _ => throw IllegalArgumentException("invalid argument")
  }

  private val _predef_iter: FunDefExpr = newFun("iter", 1, _iter_imp)

  /*
  * hasNext(f)
  */
  private def _iter_has_next_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(iter: IteratorExpr[Any]) => ValueExpr(iter.iterator.hasNext)
      case _ => throw IllegalArgumentException("invalid argument")
  }

  private val _predef_iter_has_next: FunDefExpr = newFun("hasNext", 1, _iter_has_next_imp)

  /*
  * hasNext(f)
  */
  private def _iter_next_imp(args: List[Option[Any]]): Expr = {
    args.head match
      case Some(iter: IteratorExpr[Any]) => ValueExpr(iter.iterator.next())
      case _ => throw IllegalArgumentException("invalid argument")
  }

  private val _predef_iter_next: FunDefExpr = newFun("iterNext", 1, _iter_next_imp)

  /*
  * trim(str)
  */
  private def _trim_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(s: String) => ValueExpr(s.trim)
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_trim: FunDefExpr = newFun("trim", 1, _trim_imp)

  /*
  * toUpper(str)
  */
  private def _toUpper_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(s: String) => ValueExpr(s.toUpperCase)
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_toUpper: FunDefExpr = newFun("toUpper", 1, _toUpper_imp)

  /*
  * toLower(str)
  */
  private def _toLower_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(s: String) => ValueExpr(s.toLowerCase)
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_toLower: FunDefExpr = newFun("toLower", 1, _toLower_imp)

  /*
  * contains(str, part)
  */
  private def _contains_imp(args: List[Option[Any]]): Expr = (args.head, args(1)) match
    case (Some(s: String), Some(p: String)) => ValueExpr(s.contains(p))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_contains: FunDefExpr = newFun("contains", 2, _contains_imp)

  /*
  * startsWith(str, prefix)
  */
  private def _startsWith_imp(args: List[Option[Any]]): Expr = (args.head, args(1)) match
    case (Some(s: String), Some(p: String)) => ValueExpr(s.startsWith(p))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_startsWith: FunDefExpr = newFun("startsWith", 2, _startsWith_imp)

  /*
  * endsWith(str, suffix)
  */
  private def _endsWith_imp(args: List[Option[Any]]): Expr = (args.head, args(1)) match
    case (Some(s: String), Some(p: String)) => ValueExpr(s.endsWith(p))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_endsWith: FunDefExpr = newFun("endsWith", 2, _endsWith_imp)

  /*
  * split(str, sep)
  */
  private def _split_imp(args: List[Option[Any]]): Expr = (args.head, args(1)) match
    case (Some(s: String), Some(sep: String)) => ValueExpr(ListBuffer.empty[Any] ++ s.split(sep).toList)
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_split: FunDefExpr = newFun("split", 2, _split_imp)

  /*
  * replace(str, target, repl)
  */
  private def _replace_imp(args: List[Option[Any]]): Expr = args match
    case Some(s: String) :: Some(t: String) :: Some(r: String) :: Nil => ValueExpr(s.replace(t, r))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_replace: FunDefExpr = newFun("replace", 3, _replace_imp)

  /*
  * parseInt(str)
  */
  private def _parseInt_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(s: String) => ValueExpr(IntegerLiteral(s.trim.toLong))
    case Some(n: NumberLiteral) => ValueExpr(IntegerLiteral(n.toLong))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_parseInt: FunDefExpr = newFun("parseInt", 1, _parseInt_imp)

  /*
  * parseFloat(str)
  */
  private def _parseFloat_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(s: String) => ValueExpr(FloatLiteral(s.trim.toDouble))
    case Some(n: NumberLiteral) => ValueExpr(FloatLiteral(n.toDouble))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_parseFloat: FunDefExpr = newFun("parseFloat", 1, _parseFloat_imp)

  /*
  * abs(num)
  */
  private def _abs_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(IntegerLiteral(v)) => ValueExpr(IntegerLiteral(Math.abs(v)))
    case Some(FloatLiteral(v)) => ValueExpr(FloatLiteral(Math.abs(v)))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_abs: FunDefExpr = newFun("abs", 1, _abs_imp)

  /*
  * max(a, b)
  */
  private def _max_imp(args: List[Option[Any]]): Expr = (args.head, args(1)) match
    case (Some(IntegerLiteral(a)), Some(IntegerLiteral(b))) => ValueExpr(IntegerLiteral(Math.max(a, b)))
    case (Some(FloatLiteral(a)), Some(FloatLiteral(b))) => ValueExpr(FloatLiteral(Math.max(a, b)))
    case (Some(IntegerLiteral(a)), Some(FloatLiteral(b))) => ValueExpr(FloatLiteral(Math.max(a.toDouble, b)))
    case (Some(FloatLiteral(a)), Some(IntegerLiteral(b))) => ValueExpr(FloatLiteral(Math.max(a, b.toDouble)))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_max: FunDefExpr = newFun("max", 2, _max_imp)

  /*
  * min(a, b)
  */
  private def _min_imp(args: List[Option[Any]]): Expr = (args.head, args(1)) match
    case (Some(IntegerLiteral(a)), Some(IntegerLiteral(b))) => ValueExpr(IntegerLiteral(Math.min(a, b)))
    case (Some(FloatLiteral(a)), Some(FloatLiteral(b))) => ValueExpr(FloatLiteral(Math.min(a, b)))
    case (Some(IntegerLiteral(a)), Some(FloatLiteral(b))) => ValueExpr(FloatLiteral(Math.min(a.toDouble, b)))
    case (Some(FloatLiteral(a)), Some(IntegerLiteral(b))) => ValueExpr(FloatLiteral(Math.min(a, b.toDouble)))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_min: FunDefExpr = newFun("min", 2, _min_imp)

  /*
  * floor(num)
  */
  private def _floor_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(IntegerLiteral(v)) => ValueExpr(IntegerLiteral(v))
    case Some(FloatLiteral(v)) => ValueExpr(IntegerLiteral(Math.floor(v).toLong))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_floor: FunDefExpr = newFun("floor", 1, _floor_imp)

  /*
  * ceil(num)
  */
  private def _ceil_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(IntegerLiteral(v)) => ValueExpr(IntegerLiteral(v))
    case Some(FloatLiteral(v)) => ValueExpr(IntegerLiteral(Math.ceil(v).toLong))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_ceil: FunDefExpr = newFun("ceil", 1, _ceil_imp)

  /*
  * round(num)
  */
  private def _round_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(IntegerLiteral(v)) => ValueExpr(IntegerLiteral(v))
    case Some(FloatLiteral(v)) => ValueExpr(IntegerLiteral(Math.round(v).toLong))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_round: FunDefExpr = newFun("round", 1, _round_imp)

  /*
  * random()
  */
  private def _random_imp(args: List[Option[Any]]): Expr = ValueExpr(FloatLiteral(Math.random()))

  private val _predef_random: FunDefExpr = newFun("random", 0, _random_imp)

  /*
  * clamp(x, min, max)
  */
  private def _clamp_imp(args: List[Option[Any]]): Expr = args match
    case Some(IntegerLiteral(x)) :: Some(IntegerLiteral(a)) :: Some(IntegerLiteral(b)) :: Nil =>
      ValueExpr(IntegerLiteral(Math.max(a, Math.min(b, x))))
    case Some(FloatLiteral(x)) :: Some(FloatLiteral(a)) :: Some(FloatLiteral(b)) :: Nil =>
      ValueExpr(FloatLiteral(Math.max(a, Math.min(b, x))))
    case Some(IntegerLiteral(x)) :: Some(FloatLiteral(a)) :: Some(FloatLiteral(b)) :: Nil =>
      ValueExpr(FloatLiteral(Math.max(a, Math.min(b, x.toDouble))))
    case Some(FloatLiteral(x)) :: Some(IntegerLiteral(a)) :: Some(IntegerLiteral(b)) :: Nil =>
      ValueExpr(FloatLiteral(Math.max(a.toDouble, Math.min(b.toDouble, x))))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_clamp: FunDefExpr = newFun("clamp", 3, _clamp_imp)

  /*
  * exists(path)
  */
  private def _exists_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(path: String) => ValueExpr(FileUtils.exists(path))
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_exists: FunDefExpr = newFun("exists", 1, _exists_imp)

  /*
  * isFile(path)
  */
  private def _isFile_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(path: String) => ValueExpr(File(path).isFile)
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_isFile: FunDefExpr = newFun("isFile", 1, _isFile_imp)

  /*
  * mkdirs(path)
  */
  private def _mkdirs_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(path: String) => ValueExpr(File(path).mkdirs())
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_mkdirs: FunDefExpr = newFun("mkdirs", 1, _mkdirs_imp)

  /*
  * cwd()
  */
  private def _cwd_imp(args: List[Option[Any]]): Expr = ValueExpr(System.getProperty("user.dir"))

  private val _predef_cwd: FunDefExpr = newFun("cwd", 0, _cwd_imp)

  /*
  * uuid()
  */
  private def _uuid_imp(args: List[Option[Any]]): Expr = ValueExpr(UUID.randomUUID().toString)

  private val _predef_uuid: FunDefExpr = newFun("uuid", 0, _uuid_imp)

  /*
  * getEnv(name)
  */
  private def _getEnv_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(name: String) =>
      val v = System.getenv(name)
      if v == null then NoneExpr else ValueExpr(v)
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_getEnv: FunDefExpr = newFun("getEnv", 1, _getEnv_imp)

  /*
  * sleep(ms)
  */
  private def _sleep_imp(args: List[Option[Any]]): Expr = args.head match
    case Some(n: NumberLiteral) => Thread.sleep(n.toLong); NoneExpr
    case _ => throw IllegalArgumentException("invalid argument")

  private val _predef_sleep: FunDefExpr = newFun("sleep", 1, _sleep_imp)

  private val preDefFunctions: List[FunDefExpr] =
    List(
      _predef_execute,
      _predef_println,
      _predef_print,
      _predef_length,
      _predef_readFile,
      _predef_charAt,
      _predef_httpGet,
      _predef_httpGetProxy,
      _predef_strFind,
      _predef_toString,
      _predef_exit,
      _predef_readLine,
      _predef_subString,
      _predef_listFiles,
      _predef_isDir,
      _predef_writeFile,
      _predef_timeMillis,
      _predef_removeFile,
      _predef_parse,
      _predef_eval,
      _predef_iter,
      _predef_iter_has_next,
      _predef_iter_next,
      _predef_trim,
      _predef_toUpper,
      _predef_toLower,
      _predef_contains,
      _predef_startsWith,
      _predef_endsWith,
      _predef_split,
      _predef_replace,
      _predef_parseInt,
      _predef_parseFloat,
      _predef_abs,
      _predef_max,
      _predef_min,
      _predef_floor,
      _predef_ceil,
      _predef_round,
      _predef_random,
      _predef_clamp,
      _predef_exists,
      _predef_isFile,
      _predef_mkdirs,
      _predef_cwd,
      _predef_uuid,
      _predef_getEnv,
      _predef_sleep
    )

  private val preDefBody: BodyExpr = BodyExpr(preDefFunctions)

  preDefFunctions.foreach(funDef => {
    Environment.addNewFunction((funDef.funName, funDef.scope), funDef)
  })

  def add(program: Expr): Expr = {
    program match
      case body: BodyExpr => BodyExpr(preDefBody.body ::: body.body)
      case e: Expr => BodyExpr(preDefBody.body ::: List(e))
  }

  def addToEnv(): Unit = preDefFunctions.foreach(_.compute)
}
