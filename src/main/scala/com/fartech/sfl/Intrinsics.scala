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
    "pow"        -> (two, ts =>
      if !ts.forall(Ty.numeric) then Ty.Unknown
      else if ts(0) == Ty.I64 && ts(1) == Ty.I64 then Ty.I64 else Ty.F64),
    "gcd"        -> (two, ts => if ts.forall(_ == Ty.I64) then Ty.I64 else Ty.Unknown),
    "toInt"      -> (one, ts => if Ty.numeric(ts(0)) || ts(0) == Ty.Bool then Ty.I64 else Ty.Unknown),
    "toFloat"    -> (one, ts => if Ty.numeric(ts(0)) || ts(0) == Ty.Bool then Ty.F64 else Ty.Unknown),
    "timeMillis" -> (zero, _ => Ty.I64)
  )

  private def zero(ts: Array[Ty]): Boolean = ts.isEmpty
  private def one(ts: Array[Ty]): Boolean = ts.length == 1
  private def two(ts: Array[Ty]): Boolean = ts.length == 2

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
        else c.emitLine(s"$r = call i64 @llvm.abs.i64(i64 $v, i1 true)")
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
          val r = c.freshTmp()
          c.emitLine(s"$r = fptosi double $f to i64")
          (r, Ty.I64)

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
        else
          val r = c.freshTmp()
          c.emitLine(s"$r = fptosi double $v to i64")
          (r, Ty.I64)

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
        if at == Ty.I64 && bt == Ty.I64 then
          val r = c.freshTmp()
          c.emitLine(s"$r = call i64 @sfl_ipow(i64 $av, i64 $bv)")
          (r, Ty.I64)
        else
          val r = c.freshTmp()
          c.emitLine(s"$r = call double @llvm.pow.f64(double ${c.widen(av, at, Ty.F64)}, " +
            s"double ${c.widen(bv, bt, Ty.F64)})")
          (r, Ty.F64)

      case "gcd" =>
        val r = c.freshTmp()
        c.emitLine(s"$r = call i64 @sfl_gcd(i64 ${args(0)._1}, i64 ${args(1)._1})")
        (r, Ty.I64)

      case "timeMillis" =>
        val r = c.freshTmp()
        c.emitLine(s"$r = call i64 @sfl_time_millis()")
        (r, Ty.I64)

      case other => c.reject(s"internal error: no fast path for '$other'", call)

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
