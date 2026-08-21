package com.fartech.sfl

import Builtins.*

/** Numeric functions. Integer inputs keep integer results wherever that is exact. */
object BuiltinsMath:

  def register(): Unit =
    conversions()
    basics()
    transcendental()
    bits()
    randomness()

  private def conversions(): Unit =
    define("parseInt", 1, 2, "math", "parseInt(v, radix?)",
      "Parses an integer from a string or truncates a number; radix defaults to 10.") { a =>
      a(0) match
        case VInt(v)   => VInt.of(v)
        case VFloat(v) => VInt.of(v.toLong)
        case VStr(s) =>
          val radix = if a.length > 1 then argIdx(a, 1, "parseInt") else 10
          if radix < 2 || radix > 36 then Err.eval(s"parseInt: radix $radix is out of range 2..36")
          try VInt.of(java.lang.Long.parseLong(s.trim, radix))
          catch case _: NumberFormatException => Err.eval(s"parseInt: '$s' is not an integer in base $radix")
        case v => Err.eval(s"parseInt: cannot convert ${v.typeName}")
    }

    define("parseFloat", 1, 1, "math", "parseFloat(v)", "Parses a floating point number.") { a =>
      a(0) match
        case VInt(v)   => VFloat(v.toDouble)
        case VFloat(v) => VFloat(v)
        case VStr(s) =>
          try VFloat(s.trim.toDouble)
          catch case _: NumberFormatException => Err.eval(s"parseFloat: '$s' is not a number")
        case v => Err.eval(s"parseFloat: cannot convert ${v.typeName}")
    }

  private def basics(): Unit =
    define("abs", 1, 1, "math", "abs(x)", "Absolute value.") { a =>
      a(0) match
        case VInt(v)   => VInt.of(math.abs(v))
        case VFloat(v) => VFloat(math.abs(v))
        case v         => Err.eval(s"abs: expected a number, got ${v.typeName}")
    }

    define("max", 1, -1, "math", "max(...)",
      "Largest of the arguments, or of a single array argument.") { a => extreme(a, "max", larger = true) }
    define("min", 1, -1, "math", "min(...)",
      "Smallest of the arguments, or of a single array argument.") { a => extreme(a, "min", larger = false) }

    define("floor", 1, 1, "math", "floor(x)", "Largest integer not greater than x.") { a =>
      a(0) match
        case VInt(v)   => VInt.of(v)
        case VFloat(v) => VInt.of(math.floor(v).toLong)
        case v         => Err.eval(s"floor: expected a number, got ${v.typeName}")
    }

    define("ceil", 1, 1, "math", "ceil(x)", "Smallest integer not less than x.") { a =>
      a(0) match
        case VInt(v)   => VInt.of(v)
        case VFloat(v) => VInt.of(math.ceil(v).toLong)
        case v         => Err.eval(s"ceil: expected a number, got ${v.typeName}")
    }

    define("round", 1, 2, "math", "round(x, digits?)",
      "Rounds to the nearest integer, or to the given number of decimal places.") { a =>
      val digits = if a.length > 1 then argIdx(a, 1, "round") else 0
      a(0) match
        case VInt(v) => VInt.of(v)
        case VFloat(v) =>
          if digits <= 0 then VInt.of(javaRound(v))
          else
            if digits > 15 then Err.eval("round: digits must be at most 15")
            val scale = math.pow(10.0, digits.toDouble)
            VFloat(javaRound(v * scale) / scale)
        case v => Err.eval(s"round: expected a number, got ${v.typeName}")
    }

    define("trunc", 1, 1, "math", "trunc(x)", "Discards the fractional part.") { a =>
      a(0) match
        case VInt(v)   => VInt.of(v)
        case VFloat(v) => VInt.of(v.toLong)
        case v         => Err.eval(s"trunc: expected a number, got ${v.typeName}")
    }

    define("sign", 1, 1, "math", "sign(x)", "-1, 0 or 1 according to the sign of x.") { a =>
      a(0) match
        case VInt(v)   => VInt.of(java.lang.Long.signum(v).toLong)
        case VFloat(v) => VInt.of(math.signum(v).toLong)
        case v         => Err.eval(s"sign: expected a number, got ${v.typeName}")
    }

    define("clamp", 3, 3, "math", "clamp(x, lo, hi)", "Constrains x to the range [lo, hi].") { a =>
      val lo = argNum(a, 1, "clamp")
      val hi = argNum(a, 2, "clamp")
      if lo > hi then Err.eval(s"clamp: lower bound $lo is greater than upper bound $hi")
      val x = argNum(a, 0, "clamp")
      val r = math.max(lo, math.min(hi, x))
      if a.forall(_.isInstanceOf[VInt]) then VInt.of(r.toLong) else VFloat(r)
    }

    define("gcd", 2, 2, "math", "gcd(a, b)", "Greatest common divisor of two integers.") { a =>
      var x = math.abs(argInt(a, 0, "gcd"))
      var y = math.abs(argInt(a, 1, "gcd"))
      while y != 0 do
        val t = x % y
        x = y
        y = t
      VInt.of(x)
    }

    define("lcm", 2, 2, "math", "lcm(a, b)", "Least common multiple of two integers.") { a =>
      val x = math.abs(argInt(a, 0, "lcm"))
      val y = math.abs(argInt(a, 1, "lcm"))
      if x == 0 || y == 0 then VInt.Zero
      else
        var g = x
        var h = y
        while h != 0 do
          val t = g % h
          g = h
          h = t
        VInt.of(x / g * y)
    }

    define("isNaN", 1, 1, "math", "isNaN(x)", "True when x is the floating point NaN value.") { a =>
      a(0) match
        case VFloat(v) => VBool.of(java.lang.Double.isNaN(v))
        case _         => VBool.False
    }

    define("isInfinite", 1, 1, "math", "isInfinite(x)", "True when x is positive or negative infinity.") { a =>
      a(0) match
        case VFloat(v) => VBool.of(java.lang.Double.isInfinite(v))
        case _         => VBool.False
    }

  private def extreme(a: Array[Value], fn: String, larger: Boolean): Value =
    val values: Seq[Value] =
      if a.length == 1 then
        a(0) match
          case arr: VArr =>
            if arr.items.isEmpty then Err.eval(s"$fn: array is empty")
            arr.items.toSeq
          case single => Seq(single)
      else a.toSeq
    var best = values.head
    var i = 1
    while i < values.length do
      val c = Values.compare(values(i), best)
      if (larger && c > 0) || (!larger && c < 0) then best = values(i)
      i += 1
    best

  private def transcendental(): Unit =
    def unary(name: String, doc: String)(f: Double => Double): Unit =
      define(name, 1, 1, "math", s"$name(x)", doc) { a => VFloat(f(argNum(a, 0, name))) }

    unary("sqrt", "Square root.") { x =>
      if x < 0 then Err.eval("sqrt: argument must not be negative") else math.sqrt(x)
    }
    unary("cbrt", "Cube root.")(math.cbrt)
    unary("exp", "e raised to the power of x.")(math.exp)
    unary("log", "Natural logarithm.") { x =>
      if x <= 0 then Err.eval("log: argument must be positive") else math.log(x)
    }
    unary("log2", "Base-2 logarithm.") { x =>
      if x <= 0 then Err.eval("log2: argument must be positive") else math.log(x) / math.log(2.0)
    }
    unary("log10", "Base-10 logarithm.") { x =>
      if x <= 0 then Err.eval("log10: argument must be positive") else math.log10(x)
    }
    unary("sin", "Sine of an angle in radians.")(math.sin)
    unary("cos", "Cosine of an angle in radians.")(math.cos)
    unary("tan", "Tangent of an angle in radians.")(math.tan)
    unary("asin", "Arc sine, in radians.")(math.asin)
    unary("acos", "Arc cosine, in radians.")(math.acos)
    unary("atan", "Arc tangent, in radians.")(math.atan)
    unary("sinh", "Hyperbolic sine.")(math.sinh)
    unary("cosh", "Hyperbolic cosine.")(math.cosh)
    unary("tanh", "Hyperbolic tangent.")(math.tanh)

    define("pow", 2, 2, "math", "pow(x, y)", "x raised to the power y.") { a =>
      val r = math.pow(argNum(a, 0, "pow"), argNum(a, 1, "pow"))
      if bothInt(a(0), a(1)) && argInt(a, 1, "pow") >= 0 then numResult(r, wasInt = true) else VFloat(r)
    }

    define("atan2", 2, 2, "math", "atan2(y, x)", "Angle of the point (x, y) in radians.") { a =>
      VFloat(math.atan2(argNum(a, 0, "atan2"), argNum(a, 1, "atan2")))
    }

    define("hypot", 2, 2, "math", "hypot(x, y)", "Length of the hypotenuse, without overflow.") { a =>
      VFloat(math.hypot(argNum(a, 0, "hypot"), argNum(a, 1, "hypot")))
    }

    define("PI", 0, 0, "math", "PI()", "The constant pi.")(_ => VFloat(math.Pi))
    define("E", 0, 0, "math", "E()", "The constant e.")(_ => VFloat(math.E))
    define("INF", 0, 0, "math", "INF()", "Positive infinity.")(_ => VFloat(Double.PositiveInfinity))
    define("NAN", 0, 0, "math", "NAN()", "The floating point NaN value.")(_ => VFloat(Double.NaN))

  private def bits(): Unit =
    define("bitAnd", 2, 2, "bits", "bitAnd(a, b)", "Bitwise AND.") { a =>
      VInt.of(argInt(a, 0, "bitAnd") & argInt(a, 1, "bitAnd"))
    }
    define("bitOr", 2, 2, "bits", "bitOr(a, b)", "Bitwise OR.") { a =>
      VInt.of(argInt(a, 0, "bitOr") | argInt(a, 1, "bitOr"))
    }
    define("bitXor", 2, 2, "bits", "bitXor(a, b)", "Bitwise exclusive OR.") { a =>
      VInt.of(argInt(a, 0, "bitXor") ^ argInt(a, 1, "bitXor"))
    }
    define("bitNot", 1, 1, "bits", "bitNot(a)", "Bitwise complement.") { a =>
      VInt.of(~argInt(a, 0, "bitNot"))
    }
    define("shiftLeft", 2, 2, "bits", "shiftLeft(a, n)", "Left shift.") { a =>
      VInt.of(argInt(a, 0, "shiftLeft") << argInt(a, 1, "shiftLeft"))
    }
    define("shiftRight", 2, 2, "bits", "shiftRight(a, n)", "Arithmetic right shift, keeping the sign.") { a =>
      VInt.of(argInt(a, 0, "shiftRight") >> argInt(a, 1, "shiftRight"))
    }
    define("shiftRightU", 2, 2, "bits", "shiftRightU(a, n)", "Logical right shift, filling with zeros.") { a =>
      VInt.of(argInt(a, 0, "shiftRightU") >>> argInt(a, 1, "shiftRightU"))
    }
    define("doubleToBits", 1, 1, "bits", "doubleToBits(x)",
      "The IEEE-754 bit pattern of a number as an int; every NaN maps to the canonical one.") { a =>
      VInt.of(java.lang.Double.doubleToLongBits(argNum(a, 0, "doubleToBits")))
    }
    define("bitsToDouble", 1, 1, "bits", "bitsToDouble(bits)",
      "The float whose IEEE-754 bit pattern is the given int.") { a =>
      VFloat(java.lang.Double.longBitsToDouble(argInt(a, 0, "bitsToDouble")))
    }

  /**
   * java.lang.Math.round, written out.
   *
   * Halves go towards positive infinity, so round(-0.5) is 0 where "away from zero"
   * would say -1, and the trick below also gets 0.49999999999999994 right — the
   * largest double below a half, which floor(x + 0.5) famously rounds up. The
   * runtime's C copy is the same algorithm, and the two have to agree.
   */
  private def javaRound(a: Double): Long =
    val bits = java.lang.Double.doubleToRawLongBits(a)
    val biasedExp = (bits & 0x7FF0000000000000L) >> 52
    val shift = 1074L - biasedExp // SIGNIFICAND_WIDTH - 2 + EXP_BIAS
    if (shift & -64L) == 0 then
      var r = (bits & 0x000FFFFFFFFFFFFFL) | 0x0010000000000000L
      if bits < 0 then r = -r
      ((r >> shift.toInt) + 1) >> 1
    else a.toLong

  private def randomness(): Unit =
    // A seedable generator makes runs reproducible, which Math.random() cannot offer.
    val rng = new java.util.Random()

    define("random", 0, 0, "math", "random()", "Uniform float in [0, 1).")(_ => VFloat(rng.nextDouble()))

    define("randomInt", 1, 2, "math", "randomInt(lo, hi?)",
      "Integer in [0, lo) with one argument, or in [lo, hi) with two.") { a =>
      val (lo, hi) =
        if a.length == 1 then (0L, argInt(a, 0, "randomInt"))
        else (argInt(a, 0, "randomInt"), argInt(a, 1, "randomInt"))
      if hi <= lo then Err.eval(s"randomInt: the range [$lo, $hi) is empty")
      VInt.of(lo + (rng.nextDouble() * (hi - lo)).toLong)
    }

    define("randomSeed", 1, 1, "math", "randomSeed(n)",
      "Seeds the random generator so a run can be reproduced.") { a =>
      rng.setSeed(argInt(a, 0, "randomSeed"))
      VNull
    }
