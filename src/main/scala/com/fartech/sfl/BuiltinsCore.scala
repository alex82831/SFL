package com.fartech.sfl

import Builtins.*
import scala.collection.mutable

/** Console I/O, type predicates, conversions, and reflective helpers. */
object BuiltinsCore:

  def register(): Unit =
    io()
    types()
    meta()

  private def io(): Unit =
    define("print", 0, -1, "io", "print(...)", "Writes its arguments without a trailing newline.") { a =>
      Console.out.print(joined(a))
      VNull
    }

    define("println", 0, -1, "io", "println(...)", "Writes its arguments followed by a newline.") { a =>
      Console.out.println(joined(a))
      VNull
    }

    define("eprintln", 0, -1, "io", "eprintln(...)", "Writes to standard error, with a newline.") { a =>
      Console.err.println(joined(a))
      VNull
    }

    define("printf", 1, -1, "io", "printf(fmt, ...)", "Formats like String.format and prints the result.") { a =>
      Console.out.print(BuiltinsText.formatValues(argStr(a, 0, "printf"), a.drop(1)))
      VNull
    }

    define("flush", 0, 0, "io", "flush()", "Flushes buffered standard output.") { _ =>
      Console.out.flush()
      VNull
    }

    define("readLine", 0, 1, "io", "readLine(prompt?)",
      "Reads one line from standard input; returns null at end of input.") { a =>
      if a.nonEmpty then
        Console.out.print(a(0).display)
        Console.out.flush()
      val line = Stdin.readLine()
      if line == null then VNull else VStr(line)
    }

    define("readAll", 0, 0, "io", "readAll()", "Reads all of standard input as one string.") { _ =>
      VStr(Stdin.readAll())
    }

  private def joined(a: Array[Value]): String =
    if a.isEmpty then ""
    else if a.length == 1 then a(0).display
    else
      val sb = new StringBuilder
      var i = 0
      while i < a.length do
        if i > 0 then sb.append(' ')
        a(i).write(sb, quoted = false, 0)
        i += 1
      sb.toString

  private def types(): Unit =
    define("typeOf", 1, 1, "type", "typeOf(v)",
      "Returns the type name: null, bool, int, float, string, array, object, function or iterator.") { a =>
      VStr(a(0).typeName)
    }

    define("isNull", 1, 1, "type", "isNull(v)", "True when the value is null.")(a => VBool.of(a(0) eq VNull))
    define("isNumber", 1, 1, "type", "isNumber(v)", "True for int or float.")(a => VBool.of(Values.isNum(a(0))))
    define("isInt", 1, 1, "type", "isInt(v)", "True for an integer.")(a => VBool.of(a(0).isInstanceOf[VInt]))
    define("isFloat", 1, 1, "type", "isFloat(v)", "True for a float.")(a => VBool.of(a(0).isInstanceOf[VFloat]))
    define("isString", 1, 1, "type", "isString(v)", "True for a string.")(a => VBool.of(a(0).isInstanceOf[VStr]))
    define("isBool", 1, 1, "type", "isBool(v)", "True for a boolean.")(a => VBool.of(a(0).isInstanceOf[VBool]))
    define("isArray", 1, 1, "type", "isArray(v)", "True for an array.")(a => VBool.of(a(0).isInstanceOf[VArr]))
    define("isTuple", 1, 1, "type", "isTuple(v)", "True for a tuple.")(a => VBool.of(a(0).isInstanceOf[VTuple]))

    define("tupleOf", 1, 1, "type", "tupleOf(arr)",
      "Builds a tuple from an array's elements; the inverse of toArray.") { a =>
      a(0) match
        case arr: VArr =>
          if arr.items.length < 2 then
            Err.eval("tupleOf: a tuple needs at least two elements")
          new VTuple(arr.items.toArray)
        case t: VTuple => t
        case v => Err.eval(s"tupleOf: argument 1 must be an array, got ${v.typeName}")
    }
    define("isObject", 1, 1, "type", "isObject(v)", "True for an object.")(a => VBool.of(a(0).isInstanceOf[VObj]))
    define("isFunction", 1, 1, "type", "isFunction(v)", "True for a function or builtin.") { a =>
      VBool.of(Values.isCallable(a(0)))
    }

    define("toString", 1, 1, "type", "toString(v)",
      "Converts to a string; arrays and objects render as JSON.") { a =>
      a(0) match
        case VStr(s) => VStr(s)
        case v       => VStr(v.repr)
    }

    define("toInt", 1, 1, "type", "toInt(v)", "Converts a number, boolean or numeric string to an integer.") { a =>
      a(0) match
        case VInt(v)   => VInt.of(v)
        case VFloat(v) =>
          if java.lang.Double.isNaN(v) || java.lang.Double.isInfinite(v) then
            Err.eval(s"toInt: cannot convert ${Values.formatDouble(v)}")
          VInt.of(v.toLong)
        case b: VBool => VInt.of(if b.b then 1L else 0L)
        case VStr(s) =>
          try VInt.of(s.trim.toLong)
          catch
            case _: NumberFormatException =>
              try VInt.of(s.trim.toDouble.toLong)
              catch case _: NumberFormatException => Err.eval(s"toInt: '$s' is not a number")
        case v => Err.eval(s"toInt: cannot convert ${v.typeName}")
    }

    define("toFloat", 1, 1, "type", "toFloat(v)", "Converts a number, boolean or numeric string to a float.") { a =>
      a(0) match
        case VInt(v)   => VFloat(v.toDouble)
        case VFloat(v) => VFloat(v)
        case b: VBool  => VFloat(if b.b then 1.0 else 0.0)
        case VStr(s) =>
          try VFloat(s.trim.toDouble)
          catch case _: NumberFormatException => Err.eval(s"toFloat: '$s' is not a number")
        case v => Err.eval(s"toFloat: cannot convert ${v.typeName}")
    }

    define("toBool", 1, 1, "type", "toBool(v)", "Applies the language's truthiness rules.") { a =>
      VBool.of(a(0).truthy)
    }

  private def meta(): Unit =
    define("parse", 1, 1, "meta", "parse(source)",
      "Compiles SFL source text and returns a zero-argument function that runs it.") { a =>
      val code = argStr(a, 0, "parse")
      VStr(code)
      // Compiled into the namespace of whoever called: text handed to parse() reads
      // the names its own file reads, and none that file cannot see.
      Sfl.compileThunk(code, "<parse>", Sfl.callerTag)
    }

    define("eval", 1, 1, "meta", "eval(source)",
      "Evaluates SFL source text, or calls a thunk produced by parse().") { a =>
      a(0) match
        case VStr(code)                => call(Sfl.compileThunk(code, "<eval>", Sfl.callerTag))
        case f if Values.isCallable(f) => call(f)
        case v                         => Err.eval(s"eval: expected source text or a function, got ${v.typeName}")
    }

    define("error", 1, 1, "meta", "error(message)", "Raises a runtime error with the given message.") { a =>
      Err.eval(a(0).display)
    }

    define("assert", 1, 2, "meta", "assert(cond, message?)",
      "Raises an error when the condition is falsy.") { a =>
      if !a(0).truthy then
        val msg = if a.length > 1 then a(1).display else "assertion failed"
        Err.eval(msg)
      VNull
    }

    define("try", 1, 2, "meta", "try(body, handler?)",
      "Runs body(); on error calls handler(message) if given, otherwise returns null.") { a =>
      val body = argFn(a, 0, "try")
      try call(body)
      catch
        case e: EvalError =>
          if a.length > 1 then call(argFn(a, 1, "try"), VStr(e.getMessage)) else VNull
        case e: ParseError =>
          if a.length > 1 then call(argFn(a, 1, "try"), VStr(e.getMessage)) else VNull
    }

    define("attempt", 1, 1, "meta", "attempt(body)",
      "Runs body() and returns {ok: true, value: v} or {ok: false, error: {...}}.") { a =>
      val body = argFn(a, 0, "attempt")
      def failure(e: SflError): Value =
        val info = mutable.LinkedHashMap.empty[String, Value]
        info("message") = VStr(e.getMessage)
        info("kind") = VStr(e.kind)
        info("file") = VStr(e.source.name)
        info("line") = VInt.of(e.pos.line.toLong)
        info("help") = VArr.of(e.notes.map(VStr(_)))
        e match
          case ev: EvalError =>
            info("trace") = VArr.of(ev.trace.map(t => VStr(s"${t.fn} (${t.src.name}:${t.line})")))
          case _ => info("trace") = VArr.empty
        VObj.of(Seq("ok" -> VBool.False, "error" -> new VObj(info)))
      try VObj.of(Seq("ok" -> VBool.True, "value" -> call(body)))
      catch
        case e: EvalError  => failure(e)
        case e: ParseError => failure(e)
    }

    define("help", 0, 1, "meta", "help(filter?)",
      "Prints the builtin reference, optionally filtered by name or group.") { a =>
      val filter = if a.isEmpty then "" else a(0).display
      Console.out.println(Builtins.helpText(filter, Ansi.enabled))
      VNull
    }

    define("builtins", 0, 0, "meta", "builtins()", "Returns an array of every builtin name.") { _ =>
      VArr.of(Builtins.publicNames.map(VStr(_)))
    }

    define("signature", 1, 1, "meta", "signature(f)", "Returns the declared signature of a function.") { a =>
      a(0) match
        case n: VNative => VStr(n.signature)
        case f: VFun    => VStr(f.proto.signature)
        case v          => Err.eval(s"signature: expected a function, got ${v.typeName}")
    }

    define("describe", 1, 1, "meta", "describe(f)",
      "Metadata for a builtin as {name, group, signature, doc, minArity, maxArity}.") { a =>
      val native = a(0) match
        case n: VNative => n
        case VStr(name) =>
          Builtins.lookup(name).getOrElse(Err.eval(s"describe: no builtin named '$name'"))
        case v => Err.eval(s"describe: expected a builtin or its name, got ${v.typeName}")
      VObj.of(Seq(
        "name" -> VStr(native.name),
        "group" -> VStr(native.group),
        "signature" -> VStr(native.signature),
        "doc" -> VStr(native.doc),
        "minArity" -> VInt.of(native.minArity.toLong),
        "maxArity" -> VInt.of(native.maxArity.toLong)
      ))
    }

/**
 * Standard input, read through one buffer.
 *
 * `readLine` and `readAll` are two views of the same stream, so they have to share
 * the place the bytes are held: a reader of its own for each would let the first
 * one buffer ahead and the second find an empty stream — which is what happened,
 * silently, to everything typed after the first line. The C runtime reads both
 * through one FILE\*, and this is the same arrangement.
 */
private object Stdin:
  private val in = new java.io.BufferedInputStream(System.in, 8192)

  /** The next line without its terminator, or null at the end of input. */
  def readLine(): String =
    val buf = new java.io.ByteArrayOutputStream(128)
    var b = in.read()
    if b < 0 then return null
    while b >= 0 && b != '\n' do
      buf.write(b)
      b = in.read()
    val bytes = buf.toByteArray
    // A CRLF terminator: the '\r' belongs to the line ending, not to the line.
    val n = if bytes.length > 0 && bytes(bytes.length - 1) == '\r'.toByte then bytes.length - 1
            else bytes.length
    new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8)

  /** Everything that is left, decoded as UTF-8. */
  def readAll(): String =
    val out = new java.io.ByteArrayOutputStream(8192)
    val chunk = new Array[Byte](8192)
    var n = in.read(chunk)
    while n > 0 do
      out.write(chunk, 0, n)
      n = in.read(chunk)
    new String(out.toByteArray, java.nio.charset.StandardCharsets.UTF_8)
