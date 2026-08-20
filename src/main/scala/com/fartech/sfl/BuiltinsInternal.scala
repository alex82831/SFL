package com.fartech.sfl

import Builtins.*

/**
 * Primitives the standard library needs but the language has no syntax for.
 *
 * The library modules under stdlib/ are ordinary SFL, compiled ahead of time for
 * compiled programs and run by the interpreter when they are tested against the
 * native builtins they have to match. That only works if both sides offer the same
 * footing, so these live here as well as in the C runtime. They are named with a
 * leading `__` and are not part of the language people write.
 */
object BuiltinsInternal:

  def register(): Unit =
    define("__iterFn", 1, 1, "iterator", "__iterFn(next)",
      "Builds a lazy iterator from a function returning [value] for each element and [] at the end.") { a =>
      val f = argFn(a, 0, "__iterFn")
      new VIter(new Iterator[Value] {
        private var pending: Value = null
        private var done = false

        private def pull(): Unit =
          if pending == null && !done then
            call(f) match
              case arr: VArr if arr.items.isEmpty => done = true
              case arr: VArr                      => pending = arr.items(0)
              case v => Err.eval(s"__iterFn: expected [value] or [], got ${v.typeName}")

        def hasNext: Boolean = { pull(); !done }
        def next(): Value =
          pull()
          if done then Err.eval("__iterFn: the iterator is exhausted")
          val v = pending
          pending = null
          v
      }, "lazy")
    }

    define("__patFail", 2, 2, "meta", "__patFail(expected, got)",
      "Raised by a val/var destructuring whose pattern did not match.") { a =>
      Err.evalHint(
        s"pattern does not match: expected ${a(0).display}, got ${Err.show(a(1))}",
        "a 'val' pattern must match; use select to try several shapes"
      )
    }

    define("__arity", 1, 1, "function", "__arity(f)",
      "The number of arguments a function declares.") { a =>
      argFn(a, 0, "__arity") match
        case fn: VFun   => VInt.of(if fn.proto.hasRest then fn.proto.required else fn.proto.params.length)
        case n: VNative => VInt.of(if n.maxArity < 0 then n.minArity else n.maxArity)
        case v          => Err.eval(s"__arity: expected a function, got ${v.typeName}")
    }
