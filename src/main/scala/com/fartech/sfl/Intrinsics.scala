package com.fartech.sfl

/**
 * Builtins the compiler open-codes when it knows the argument types.
 *
 * Every one of these also exists as a runtime primitive, so nothing depends on this
 * table for correctness — it only decides whether `abs(n)` becomes two machine
 * instructions or a call that boxes, calls and unboxes. A fast path is taken only
 * when it produces bit-for-bit what the primitive would have produced; where that is
 * awkward (integer `pow`, Java's rounding of negative halves) the work goes to a
 * small C helper rather than to an approximation.
 */
object Intrinsics:

  private val printing = Set("print", "println")

  /** name -> (arity check, result type given the argument types). Unknown means "no". */
  private val table: Map[String, (Array[Ty] => Boolean, Array[Ty] => Ty)] = Map(
    "abs"        -> (one, ts => if Ty.numeric(ts(0)) then ts(0) else Ty.Unknown),
    "min"        -> (two, joinNumeric),
    "max"        -> (two, joinNumeric),
    "exp"        -> (one, allNumeric(Ty.F64)),
    "sin"        -> (one, allNumeric(Ty.F64)),
    "cos"        -> (one, allNumeric(Ty.F64)),
    "floor"      -> (one, allNumeric(Ty.I64)),
    "ceil"       -> (one, allNumeric(Ty.I64)),
    "round"      -> (one, allNumeric(Ty.I64)),
    "trunc"      -> (one, allNumeric(Ty.I64)),
    "sign"       -> (one, allNumeric(Ty.I64)),
    // Two ints have no static answer: the interpreter computes pow in double and
    // saturates, and a negative exponent makes the result a float. Both belong to
    // the primitive, which already does exactly that.
    "pow"        -> (two, ts =>
      if !ts.forall(Ty.numeric) || (ts(0) == Ty.I64 && ts(1) == Ty.I64) then Ty.Unknown
      else Ty.F64),
    "gcd"        -> (two, ts => if ts.forall(_ == Ty.I64) then Ty.I64 else Ty.Unknown),
    "lcm"        -> (two, ts => if ts.forall(_ == Ty.I64) then Ty.I64 else Ty.Unknown),
    "toInt"      -> (one, ts => if Ty.numeric(ts(0)) || ts(0) == Ty.Bool then Ty.I64 else Ty.Unknown),
    "toFloat"    -> (one, ts => if Ty.numeric(ts(0)) || ts(0) == Ty.Bool then Ty.F64 else Ty.Unknown),
    "timeMillis" -> (zero, _ => Ty.I64),
    "timeNanos"  -> (zero, _ => Ty.I64),
    "random"     -> (zero, _ => Ty.F64),
    // The checked ones call a helper that raises exactly as the primitive does;
    // the unchecked ones call the libm symbol the primitive itself calls.
    "sqrt"       -> (one, allNumeric(Ty.F64)),
    "log"        -> (one, allNumeric(Ty.F64)),
    "log2"       -> (one, allNumeric(Ty.F64)),
    "log10"      -> (one, allNumeric(Ty.F64)),
    "cbrt"       -> (one, allNumeric(Ty.F64)),
    "tan"        -> (one, allNumeric(Ty.F64)),
    "asin"       -> (one, allNumeric(Ty.F64)),
    "acos"       -> (one, allNumeric(Ty.F64)),
    "atan"       -> (one, allNumeric(Ty.F64)),
    "sinh"       -> (one, allNumeric(Ty.F64)),
    "cosh"       -> (one, allNumeric(Ty.F64)),
    "tanh"       -> (one, allNumeric(Ty.F64)),
    "atan2"      -> (two, allNumeric(Ty.F64)),
    "hypot"      -> (two, allNumeric(Ty.F64)),
    // Only the float case is open-coded; an int is never NaN or infinite, but the
    // primitive answers that itself and PrimRet already keeps the result unboxed.
    "isNaN"      -> (one, ts => if ts(0) == Ty.F64 then Ty.Bool else Ty.Unknown),
    "isInfinite" -> (one, ts => if ts(0) == Ty.F64 then Ty.Bool else Ty.Unknown),
    // Type predicates: a machine-typed argument answers at compile time, a boxed
    // one with a load of its tag — the same field the primitive tests.
    "isNull"     -> (one, _ => Ty.Bool),
    "isBool"     -> (one, _ => Ty.Bool),
    "isInt"      -> (one, _ => Ty.Bool),
    "isFloat"    -> (one, _ => Ty.Bool),
    "isNumber"   -> (one, _ => Ty.Bool),
    "isString"   -> (one, _ => Ty.Bool),
    "isArray"    -> (one, _ => Ty.Bool),
    "isObject"   -> (one, _ => Ty.Bool),
    "isTuple"    -> (one, _ => Ty.Bool),
    "isFunction" -> (one, _ => Ty.Bool),
    // The JVM's shift semantics — count masked to six bits — spelled out in IR.
    "bitAnd"     -> (two, allI64),
    "bitOr"      -> (two, allI64),
    "bitXor"     -> (two, allI64),
    "bitNot"     -> (one, allI64),
    "shiftLeft"  -> (two, allI64),
    "shiftRight" -> (two, allI64),
    "shiftRightU" -> (two, allI64),
    "clamp"      -> (three, ts =>
      if !ts.forall(Ty.numeric) then Ty.Unknown
      else if ts.forall(_ == Ty.I64) then Ty.I64
      else Ty.F64),
    "parseInt"   -> (one, ts => if Ty.numeric(ts(0)) then Ty.I64 else Ty.Unknown),
    "parseFloat" -> (one, ts => if Ty.numeric(ts(0)) then Ty.F64 else Ty.Unknown),
    "doubleToBits" -> (one, ts => if Ty.numeric(ts(0)) then Ty.I64 else Ty.Unknown),
    "bitsToDouble" -> (one, ts => if ts(0) == Ty.I64 then Ty.F64 else Ty.Unknown)
  )

  private def zero(ts: Array[Ty]): Boolean = ts.isEmpty
  private def one(ts: Array[Ty]): Boolean = ts.length == 1
  private def two(ts: Array[Ty]): Boolean = ts.length == 2
  private def three(ts: Array[Ty]): Boolean = ts.length == 3

  private def allI64(ts: Array[Ty]): Ty =
    if ts.forall(_ == Ty.I64) then Ty.I64 else Ty.Unknown

  private def allNumeric(result: Ty)(ts: Array[Ty]): Ty =
    if ts.forall(Ty.numeric) then result else Ty.Unknown

  /**
   * min/max return one of their arguments unchanged, so the interpreter's
   * `min(1, 2.5)` is the integer 1. A specialised version has one return type, so
   * mixing int and float would print 1.0 instead; requiring both sides to agree
   * sends the mixed case to the primitive, which gets it right.
   */
  private def joinNumeric(ts: Array[Ty]): Ty =
    if ts.forall(Ty.numeric) && ts(0) == ts(1) then ts(0) else Ty.Unknown

  def names: Set[String] = table.keySet ++ printing

  /** The static result type, or None when this call has to go to the primitive. */
  def staticRet(name: String, argTys: Array[Ty]): Option[Ty] =
    if printing.contains(name) then
      if argTys.forall(t => Ty.numeric(t) || t == Ty.Bool) then Some(Ty.Dyn) else None
    else
      table.get(name).flatMap { (arity, result) =>
        if !arity(argTys) then None
        else if argTys.contains(Ty.Unknown) then Some(Ty.Unknown)
        else
          val r = result(argTys)
          if r == Ty.Unknown then None else Some(r)
      }

  /** Emits the fast path, or None to let the caller emit a primitive call. */
  def emitStatic(c: Compiler, name: String, call: Call): Option[(String, Ty)] =
    if printing.contains(name) then
      // Deciding by argument type alone: anything not already a machine number is
      // cheaper to hand to the primitive, which renders it the way the interpreter does.
      val tys = call.args.map(a => c.typeOf(a))
      if call.args.nonEmpty && tys.forall(t => Ty.numeric(t) || t == Ty.Bool) then
        Some(emitPrint(c, name == "println", call))
      else None
    else
      val tys = call.args.map(a => c.typeOf(a))
      staticRet(name, tys).filter(_ != Ty.Unknown).map(_ => emitDirect(c, name, call))

  private def emitDirect(c: Compiler, name: String, call: Call): (String, Ty) =
    val args = call.args.map(a => c.emitOperand(a))
    name match
      case "abs" =>
        val (v, t) = args(0)
        val r = c.freshTmp()
        if t == Ty.F64 then c.emitLine(s"$r = call double @llvm.fabs.f64(double $v)")
        // i1 false: abs(Long.MinValue) is Long.MinValue, as Math.abs gives.
        else c.emitLine(s"$r = call i64 @llvm.abs.i64(i64 $v, i1 false)")
        (r, t)

      case "min" | "max" => emitMinMax(c, name == "min", args)

      case "exp" | "sin" | "cos" =>
        val (v, t) = args(0)
        val d = c.widen(v, t, Ty.F64)
        val r = c.freshTmp()
        c.emitLine(s"$r = call double @llvm.$name.f64(double $d)")
        (r, Ty.F64)

      case "floor" | "ceil" =>
        val (v, t) = args(0)
        if t == Ty.I64 then (v, Ty.I64)
        else
          val f = c.freshTmp()
          c.emitLine(s"$f = call double @llvm.$name.f64(double $v)")
          (c.widen(f, Ty.F64, Ty.I64), Ty.I64)

      case "round" =>
        val (v, t) = args(0)
        if t == Ty.I64 then (v, Ty.I64)
        else
          // Java rounds half upward, which differs from llround for negative halves.
          val r = c.freshTmp()
          c.emitLine(s"$r = call i64 @sfl_round(double $v)")
          (r, Ty.I64)

      case "trunc" | "toInt" =>
        val (v, t) = args(0)
        if t == Ty.I64 then (v, Ty.I64)
        else if t == Ty.Bool then (c.widen(v, Ty.Bool, Ty.I64), Ty.I64)
        else if name == "toInt" then
          // toInt refuses NaN and infinity; trunc converts them the way a cast does.
          val r = c.freshTmp()
          c.emitLine(s"$r = call i64 @sfl_to_int_f64(double $v)")
          (r, Ty.I64)
        else (c.widen(v, Ty.F64, Ty.I64), Ty.I64)

      case "toFloat" =>
        val (v, t) = args(0)
        (c.widen(v, t, Ty.F64), Ty.F64)

      case "sign" =>
        val (v, t) = args(0)
        val r = c.freshTmp()
        if t == Ty.F64 then c.emitLine(s"$r = call i64 @sfl_sign_f64(double $v)")
        else c.emitLine(s"$r = call i64 @sfl_sign_i64(i64 $v)")
        (r, Ty.I64)

      case "pow" =>
        val (av, at) = args(0)
        val (bv, bt) = args(1)
        val r = c.freshTmp()
        c.emitLine(s"$r = call double @llvm.pow.f64(double ${c.widen(av, at, Ty.F64)}, " +
          s"double ${c.widen(bv, bt, Ty.F64)})")
        (r, Ty.F64)

      case "gcd" =>
        val r = c.freshTmp()
        c.emitLine(s"$r = call i64 @sfl_gcd(i64 ${args(0)._1}, i64 ${args(1)._1})")
        (r, Ty.I64)

      case "lcm" =>
        val r = c.freshTmp()
        c.emitLine(s"$r = call i64 @sfl_lcm_i64(i64 ${args(0)._1}, i64 ${args(1)._1})")
        (r, Ty.I64)

      case "timeMillis" =>
        val r = c.freshTmp()
        c.emitLine(s"$r = call i64 @sfl_time_millis()")
        (r, Ty.I64)

      case "timeNanos" =>
        val r = c.freshTmp()
        c.emitLine(s"$r = call i64 @sfl_time_nanos()")
        (r, Ty.I64)

      case "random" =>
        val r = c.freshTmp()
        c.emitLine(s"$r = call double @sfl_random_f64()")
        (r, Ty.F64)

      case "sqrt" | "log" | "log2" | "log10" =>
        val (v, t) = args(0)
        val d = c.widen(v, t, Ty.F64)
        val r = c.freshTmp()
        c.emitLine(s"$r = call double @sfl_${name}_ck(double $d)")
        (r, Ty.F64)

      case "cbrt" | "tan" | "asin" | "acos" | "atan" | "sinh" | "cosh" | "tanh" =>
        val (v, t) = args(0)
        val d = c.widen(v, t, Ty.F64)
        val r = c.freshTmp()
        c.emitLine(s"$r = call double @$name(double $d)")
        (r, Ty.F64)

      case "atan2" | "hypot" =>
        val a = c.widen(args(0)._1, args(0)._2, Ty.F64)
        val b = c.widen(args(1)._1, args(1)._2, Ty.F64)
        val r = c.freshTmp()
        c.emitLine(s"$r = call double @$name(double $a, double $b)")
        (r, Ty.F64)

      case "isNaN" =>
        val (v, _) = args(0)
        val r = c.freshTmp()
        c.emitLine(s"$r = fcmp uno double $v, $v")
        (r, Ty.Bool)

      case "isInfinite" =>
        val (v, _) = args(0)
        val a = c.freshTmp()
        c.emitLine(s"$a = call double @llvm.fabs.f64(double $v)")
        val r = c.freshTmp()
        c.emitLine(s"$r = fcmp oeq double $a, 0x7FF0000000000000")
        (r, Ty.Bool)

      case "bitAnd" | "bitOr" | "bitXor" =>
        val op = name match
          case "bitAnd" => "and"
          case "bitOr"  => "or"
          case _        => "xor"
        val r = c.freshTmp()
        c.emitLine(s"$r = $op i64 ${args(0)._1}, ${args(1)._1}")
        (r, Ty.I64)

      case "bitNot" =>
        val r = c.freshTmp()
        c.emitLine(s"$r = xor i64 ${args(0)._1}, -1")
        (r, Ty.I64)

      case "shiftLeft" | "shiftRight" | "shiftRightU" =>
        val op = name match
          case "shiftLeft"  => "shl"
          case "shiftRight" => "ashr"
          case _            => "lshr"
        val m = c.freshTmp()
        c.emitLine(s"$m = and i64 ${args(1)._1}, 63")
        val r = c.freshTmp()
        c.emitLine(s"$r = $op i64 ${args(0)._1}, $m")
        (r, Ty.I64)

      case "clamp" =>
        if args.forall(_._2 == Ty.I64) then
          val r = c.freshTmp()
          c.emitLine(s"$r = call i64 @sfl_clamp_i64_ck(i64 ${args(0)._1}, " +
            s"i64 ${args(1)._1}, i64 ${args(2)._1})")
          (r, Ty.I64)
        else
          val x = c.widen(args(0)._1, args(0)._2, Ty.F64)
          val lo = c.widen(args(1)._1, args(1)._2, Ty.F64)
          val hi = c.widen(args(2)._1, args(2)._2, Ty.F64)
          val r = c.freshTmp()
          c.emitLine(s"$r = call double @sfl_clamp_f64_ck(double $x, double $lo, double $hi)")
          (r, Ty.F64)

      case "parseInt" =>
        val (v, t) = args(0)
        if t == Ty.I64 then (v, Ty.I64)
        else
          // The primitive narrows a float with Java's saturating cast, NaN included.
          val r = c.freshTmp()
          c.emitLine(s"$r = call i64 @sfl_d2l(double $v)")
          (r, Ty.I64)

      case "parseFloat" =>
        val (v, t) = args(0)
        (c.widen(v, t, Ty.F64), Ty.F64)

      case "doubleToBits" =>
        val d = c.widen(args(0)._1, args(0)._2, Ty.F64)
        val nan = c.freshTmp()
        c.emitLine(s"$nan = fcmp uno double $d, $d")
        val raw = c.freshTmp()
        c.emitLine(s"$raw = bitcast double $d to i64")
        val r = c.freshTmp()
        // Every NaN collapses to the canonical pattern, as Double.doubleToLongBits does.
        c.emitLine(s"$r = select i1 $nan, i64 9221120237041090560, i64 $raw")
        (r, Ty.I64)

      case "bitsToDouble" =>
        val r = c.freshTmp()
        c.emitLine(s"$r = bitcast i64 ${args(0)._1} to double")
        (r, Ty.F64)

      case "isNull" | "isBool" | "isInt" | "isFloat" | "isNumber" | "isString" |
           "isArray" | "isObject" | "isTuple" | "isFunction" =>
        emitPredicate(c, name, args(0))

      case other => c.reject(s"internal error: no fast path for '$other'", call)

  /**
   * A type predicate. A machine-typed argument is answered as a constant; a boxed
   * one by loading its tag — the first field of every cell, which sfl.h pins with
   * a static assertion. The numbers are the SflTag enum, whose values are frozen
   * (new tags are appended), and compiler and runtime ship as one artifact.
   */
  private def emitPredicate(c: Compiler, name: String, arg: (String, Ty)): (String, Ty) =
    val (v, t) = arg
    if t != Ty.Dyn then
      val hit = (name, t) match
        case ("isInt", Ty.I64) | ("isFloat", Ty.F64) | ("isBool", Ty.Bool) => true
        case ("isNumber", Ty.I64) | ("isNumber", Ty.F64)                   => true
        case _                                                             => false
      (if hit then "true" else "false", Ty.Bool)
    else
      val tag = c.freshTmp()
      c.emitLine(s"$tag = load i32, ptr $v")
      val r = c.freshTmp()
      name match
        case "isNull"   => c.emitLine(s"$r = icmp eq i32 $tag, 0")
        case "isBool"   => c.emitLine(s"$r = icmp eq i32 $tag, 1")
        case "isInt"    => c.emitLine(s"$r = icmp eq i32 $tag, 2")
        case "isFloat"  => c.emitLine(s"$r = icmp eq i32 $tag, 3")
        case "isString" => c.emitLine(s"$r = icmp eq i32 $tag, 4")
        case "isArray"  => c.emitLine(s"$r = icmp eq i32 $tag, 5")
        case "isObject" => c.emitLine(s"$r = icmp eq i32 $tag, 6")
        case "isTuple"  => c.emitLine(s"$r = icmp eq i32 $tag, 12")
        case "isNumber" =>
          // INT and FLOAT are adjacent: one subtraction, one unsigned compare.
          val d = c.freshTmp()
          c.emitLine(s"$d = sub i32 $tag, 2")
          c.emitLine(s"$r = icmp ult i32 $d, 2")
        case _ => // isFunction: FUN and NATIVE, likewise adjacent.
          val d = c.freshTmp()
          c.emitLine(s"$d = sub i32 $tag, 7")
          c.emitLine(s"$r = icmp ult i32 $d, 2")
      (r, Ty.Bool)

  private def emitMinMax(c: Compiler, isMin: Boolean, args: Array[(String, Ty)]): (String, Ty) =
    val t = Ty.join(args(0)._2, args(1)._2)
    val a = c.widen(args(0)._1, args(0)._2, t)
    val b = c.widen(args(1)._1, args(1)._2, t)
    val cmp = c.freshTmp()
    if t == Ty.F64 then
      c.emitLine(s"$cmp = fcmp ${if isMin then "olt" else "ogt"} double $a, $b")
    else
      c.emitLine(s"$cmp = icmp ${if isMin then "slt" else "sgt"} i64 $a, $b")
    val r = c.freshTmp()
    c.emitLine(s"$r = select i1 $cmp, ${t.llvm} $a, ${t.llvm} $b")
    (r, t)

  /**
   * Primitives whose result is provably one machine type — every return path of
   * the C implementation constructs that type, or raises and never returns.
   *
   * Where the argument types are unknown the call still goes to the primitive,
   * but the compiler reads the result unboxed and inference stays typed past it:
   * `i < length(arr)` keeps `i` a machine integer even though `arr` is dynamic.
   * A `core` names an unboxed twin with the primitive calling convention — same
   * checks, same messages, no result cell at all; without one the boxed result
   * is read raw, which is still free of a type test because the table is a
   * guarantee, not a guess.
   *
   * Every entry is verified against the primitive's source; the arity set is
   * the arities the guarantee holds for (round(x, digits) can return a float,
   * so only round/1 is listed).
   */
  object PrimRet:
    final case class Fact(ret: Ty, arities: Set[Int], core: String = "")

    private def i64(as: Int*) = Fact(Ty.I64, as.toSet)
    private def f64(as: Int*) = Fact(Ty.F64, as.toSet)
    private def bool(as: Int*) = Fact(Ty.Bool, as.toSet)

    private val facts: Map[String, Fact] = Map(
      "length" -> Fact(Ty.I64, Set(1), "sfl_length_i"),
      "size" -> Fact(Ty.I64, Set(1), "sfl_size_i"),
      "indexOf" -> Fact(Ty.I64, Set(2, 3), "sfl_indexOf_i"),
      "lastIndexOf" -> Fact(Ty.I64, Set(2), "sfl_lastIndexOf_i"),
      "strFind" -> Fact(Ty.I64, Set(2, 3), "sfl_strFind_i"),
      "ord" -> Fact(Ty.I64, Set(1), "sfl_ord_i"),
      "compare" -> i64(2),
      "utf8Length" -> i64(1),
      "gcd" -> i64(2),
      "lcm" -> i64(2),
      "parseInt" -> i64(1, 2),
      "toInt" -> i64(1),
      "floor" -> i64(1),
      "ceil" -> i64(1),
      "trunc" -> i64(1),
      "sign" -> i64(1),
      "round" -> i64(1),
      "bitAnd" -> i64(2),
      "bitOr" -> i64(2),
      "bitXor" -> i64(2),
      "bitNot" -> i64(1),
      "shiftLeft" -> i64(2),
      "shiftRight" -> i64(2),
      "shiftRightU" -> i64(2),
      "doubleToBits" -> i64(1),
      "timeMillis" -> i64(0),
      "timeNanos" -> i64(0),
      "randomInt" -> i64(1, 2),
      "parseFloat" -> f64(1),
      "toFloat" -> f64(1),
      "sqrt" -> f64(1),
      "log" -> f64(1),
      "log2" -> f64(1),
      "log10" -> f64(1),
      "cbrt" -> f64(1),
      "exp" -> f64(1),
      "sin" -> f64(1),
      "cos" -> f64(1),
      "tan" -> f64(1),
      "asin" -> f64(1),
      "acos" -> f64(1),
      "atan" -> f64(1),
      "sinh" -> f64(1),
      "cosh" -> f64(1),
      "tanh" -> f64(1),
      "atan2" -> f64(2),
      "hypot" -> f64(2),
      "random" -> f64(0),
      "bitsToDouble" -> f64(1),
      "PI" -> f64(0),
      "E" -> f64(0),
      "INF" -> f64(0),
      "NAN" -> f64(0),
      "isNull" -> bool(1),
      "isNumber" -> bool(1),
      "isInt" -> bool(1),
      "isFloat" -> bool(1),
      "isString" -> bool(1),
      "isBool" -> bool(1),
      "isArray" -> bool(1),
      "isTuple" -> bool(1),
      "isObject" -> bool(1),
      "isFunction" -> bool(1),
      "isNaN" -> bool(1),
      "isInfinite" -> bool(1),
      "toBool" -> bool(1),
      "isEmpty" -> bool(1),
      "contains" -> bool(2),
      "startsWith" -> bool(2),
      "endsWith" -> bool(2),
      "has" -> bool(2),
      "hasNext" -> bool(1)
    )

    def of(name: String, argc: Int): Option[Fact] =
      facts.get(name).filter(_.arities.contains(argc))

    def retTy(name: String, argc: Int): Ty =
      of(name, argc).map(_.ret).getOrElse(Ty.Dyn)

  /** Mirrors the interpreter: arguments space-separated, `println` adding a newline. */
  private def emitPrint(c: Compiler, newline: Boolean, call: Call): (String, Ty) =
    var i = 0
    while i < call.args.length do
      if i > 0 then c.emitLine("call void @sfl_print_sep()")
      val (v, t) = c.emitOperand(call.args(i))
      val last = i == call.args.length - 1
      val nl = if newline && last then 1 else 0
      t match
        case Ty.I64  => c.emitLine(s"call void @sfl_print_i64(i64 $v, i32 $nl)")
        case Ty.F64  => c.emitLine(s"call void @sfl_print_f64(double $v, i32 $nl)")
        case Ty.Bool =>
          val z = c.freshTmp()
          c.emitLine(s"$z = zext i1 $v to i32")
          c.emitLine(s"call void @sfl_print_bool(i32 $z, i32 $nl)")
        case other => c.emitLine(s"call void @sfl_print_val(ptr ${c.boxed(v, other)}, i32 $nl)")
      i += 1
    ("@sfl_null_obj", Ty.Dyn)
