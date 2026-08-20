package com.fartech.sfl

import Builtins.*
import scala.collection.mutable

/**
 * The functional layer: combinators that build new functions, folds and transforms over
 * collections, copy-on-write updates for nested data, and lazy sequences.
 *
 * Combinators return [[VNative]] values closing over Scala state, so a function produced
 * by `compose` or `curry` is indistinguishable from one written in SFL.
 */
object BuiltinsFn:

  /** Joins rendered arguments into a cache key; no rendering can contain it. */
  private final val Sep = "\u0000"

  def register(): Unit =
    combinators()
    transforms()
    immutable()
    lazySeqs()

  /** Wraps a Scala function as a first-class SFL value. */
  private def derived(name: String, min: Int, max: Int, signature: String)(
      impl: Array[Value] => Value
  ): VNative =
    new VNative(name, min, max, "function", signature, "A function produced at run time.", impl)

  /** How many arguments a callable declares; variadic natives report their minimum. */
  private def arityOf(f: Value): Int = f match
    case fn: VFun    => if fn.proto.hasRest then fn.proto.required else fn.proto.params.length
    case n: VNative  => if n.maxArity < 0 then n.minArity else n.maxArity
    case v           => Err.eval(s"expected a function, got ${v.typeName}")

  private def nameOf(f: Value): String = f match
    case fn: VFun   => fn.proto.name
    case n: VNative => n.name
    case _          => "fn"

  // -------------------------------------------------------------------------
  // Function combinators
  // -------------------------------------------------------------------------

  private def combinators(): Unit =
    define("identity", 1, 1, "function", "identity(x)", "Returns its argument unchanged.") { a =>
      a(0)
    }

    define("constantly", 1, 1, "function", "constantly(v)",
      "Returns a function that ignores its arguments and always yields v.") { a =>
      val v = a(0)
      derived("constantly", 0, -1, "constantly(...)")(_ => v)
    }

    define("compose", 1, -1, "function", "compose(...fns)",
      "Right-to-left composition: compose(f, g)(x) is f(g(x)).") { a =>
      val fns = a.map(v => argFn(Array(v), 0, "compose"))
      derived("composed", 0, -1, "composed(...)") { args =>
        // The rightmost function receives the original arguments.
        var acc = call(fns(fns.length - 1), args*)
        var i = fns.length - 2
        while i >= 0 do
          acc = call(fns(i), acc)
          i -= 1
        acc
      }
    }

    define("pipe", 1, -1, "function", "pipe(...fns)",
      "Left-to-right composition: pipe(f, g)(x) is g(f(x)).") { a =>
      val fns = a.map(v => argFn(Array(v), 0, "pipe"))
      derived("piped", 0, -1, "piped(...)") { args =>
        var acc = call(fns(0), args*)
        var i = 1
        while i < fns.length do
          acc = call(fns(i), acc)
          i += 1
        acc
      }
    }

    define("partial", 1, -1, "function", "partial(f, ...args)",
      "Binds leading arguments, returning a function that takes the rest.") { a =>
      val f = argFn(a, 0, "partial")
      val bound = a.drop(1)
      derived(s"partial:${nameOf(f)}", 0, -1, "partial(...)") { rest =>
        call(f, (bound ++ rest)*)
      }
    }

    define("partialRight", 1, -1, "function", "partialRight(f, ...args)",
      "Binds trailing arguments, returning a function that takes the leading ones.") { a =>
      val f = argFn(a, 0, "partialRight")
      val bound = a.drop(1)
      derived(s"partialRight:${nameOf(f)}", 0, -1, "partialRight(...)") { rest =>
        call(f, (rest ++ bound)*)
      }
    }

    define("flip", 1, 1, "function", "flip(f)",
      "Returns a function with its first two arguments swapped.") { a =>
      val f = argFn(a, 0, "flip")
      derived(s"flip:${nameOf(f)}", 2, -1, "flip(...)") { args =>
        if args.length < 2 then Err.eval("flip: the result needs at least 2 arguments")
        val swapped = args.clone()
        swapped(0) = args(1)
        swapped(1) = args(0)
        call(f, swapped*)
      }
    }

    define("curry", 1, 2, "function", "curry(f, arity?)",
      "Turns f into a chain of one-argument functions; arity defaults to f's own.") { a =>
      val f = argFn(a, 0, "curry")
      val n = if a.length > 1 then argIdx(a, 1, "curry") else arityOf(f)
      if n < 0 then Err.eval("curry: arity must not be negative")
      def step(got: Vector[Value]): Value =
        if got.length >= n then call(f, got*)
        else derived(s"curried:${nameOf(f)}", 1, 1, "curried(x)") { args => step(got :+ args(0)) }
      if n == 0 then call(f) else step(Vector.empty)
    }

    define("uncurry", 1, 1, "function", "uncurry(f)",
      "Turns a chain of one-argument functions into one that takes them all at once.") { a =>
      val f = argFn(a, 0, "uncurry")
      derived(s"uncurried:${nameOf(f)}", 1, -1, "uncurried(...)") { args =>
        var acc = f
        var i = 0
        while i < args.length do
          acc = call(acc, args(i))
          i += 1
        acc
      }
    }

    define("apply", 2, 2, "function", "apply(f, args)",
      "Calls f with an array of arguments.") { a =>
      call(argFn(a, 0, "apply"), argArr(a, 1, "apply").items.toSeq*)
    }

    define("memoize", 1, 1, "function", "memoize(f)",
      "Caches results by argument list, so a pure f is computed once per input.") { a =>
      val f = argFn(a, 0, "memoize")
      val cache = mutable.HashMap.empty[String, Value]
      derived(s"memo:${nameOf(f)}", 0, -1, "memoized(...)") { args =>
        // Values render unambiguously, so repr is a usable cache key; the
        // separator is a character no rendering can contain.
        val key = args.map(_.repr).mkString(Sep)
        cache.getOrElseUpdate(key, call(f, args*))
      }
    }

    define("once", 1, 1, "function", "once(f)",
      "Runs f at most once; later calls return the first result.") { a =>
      val f = argFn(a, 0, "once")
      var done = false
      var result: Value = VNull
      derived(s"once:${nameOf(f)}", 0, -1, "once(...)") { args =>
        if !done then
          result = call(f, args*)
          done = true
        result
      }
    }

    define("negate", 1, 1, "function", "negate(pred)",
      "Returns a predicate that is true exactly when pred is false.") { a =>
      val f = argFn(a, 0, "negate")
      derived(s"not:${nameOf(f)}", 0, -1, "negated(...)") { args =>
        VBool.of(!call(f, args*).truthy)
      }
    }

    define("tap", 2, 2, "function", "tap(v, f)",
      "Calls f(v) for its side effect and returns v, for inspecting a pipeline.") { a =>
      call(argFn(a, 1, "tap"), a(0))
      a(0)
    }

  // -------------------------------------------------------------------------
  // Collection transforms
  // -------------------------------------------------------------------------

  private def transforms(): Unit =
    define("flatMap", 2, 2, "array", "flatMap(v, f)",
      "Maps then concatenates one level; lazy when given an iterator.") { a =>
      val f = argFn(a, 1, "flatMap")
      def expand(v: Value): Iterator[Value] = v match
        case arr: VArr => arr.items.iterator
        case it: VIter => it.it
        case other     => Iterator.single(other)
      a(0) match
        case it: VIter => new VIter(it.it.flatMap(v => expand(call(f, v))), "lazy")
        case arr: VArr =>
          val out = new mutable.ArrayBuffer[Value]
          var i = 0
          while i < arr.items.length do
            out ++= expand(call(f, arr.items(i)))
            i += 1
          new VArr(out)
        case v => Err.eval(s"flatMap: expected an array or iterator, got ${v.typeName}")
    }

    define("groupBy", 2, 2, "array", "groupBy(arr, key)",
      "Groups elements into an object keyed by key(element).") { a =>
      val f = argFn(a, 1, "groupBy")
      val m = mutable.LinkedHashMap.empty[String, Value]
      for v <- argArr(a, 0, "groupBy").items do
        val k = call(f, v).display
        m.get(k) match
          case Some(bucket: VArr) => bucket.items += v
          case _                  => m(k) = VArr.of(Seq(v))
      new VObj(m)
    }

    define("partition", 2, 2, "array", "partition(arr, pred)",
      "Splits into [matching, notMatching].") { a =>
      val f = argFn(a, 1, "partition")
      val yes = new mutable.ArrayBuffer[Value]
      val no = new mutable.ArrayBuffer[Value]
      for v <- argArr(a, 0, "partition").items do
        if call(f, v).truthy then yes += v else no += v
      VArr.of(Seq(new VArr(yes), new VArr(no)))
    }

    define("zipWith", 3, 3, "array", "zipWith(a, b, f)",
      "Combines two arrays element-wise with f, stopping at the shorter one.") { a =>
      val x = argArr(a, 0, "zipWith").items
      val y = argArr(a, 1, "zipWith").items
      val f = argFn(a, 2, "zipWith")
      val n = math.min(x.length, y.length)
      val out = new mutable.ArrayBuffer[Value](n)
      var i = 0
      while i < n do
        out += call(f, x(i), y(i))
        i += 1
      new VArr(out)
    }

    define("scan", 2, 3, "array", "scan(arr, f, initial?)",
      "Like reduce but keeps every intermediate accumulator.") { a =>
      val items = argArr(a, 0, "scan").items
      val f = argFn(a, 1, "scan")
      val out = new mutable.ArrayBuffer[Value]
      var i = 0
      var acc =
        if a.length > 2 then a(2)
        else if items.isEmpty then Err.eval("scan: array is empty and no initial value was given")
        else { i = 1; items(0) }
      out += acc
      while i < items.length do
        acc = call(f, acc, items(i))
        out += acc
        i += 1
      new VArr(out)
    }

    define("foldRight", 3, 3, "array", "foldRight(arr, f, initial)",
      "Folds from the right with f(element, accumulator).") { a =>
      val items = argArr(a, 0, "foldRight").items
      val f = argFn(a, 1, "foldRight")
      var acc = a(2)
      var i = items.length - 1
      while i >= 0 do
        acc = call(f, items(i), acc)
        i -= 1
      acc
    }

    define("takeWhile", 2, 2, "array", "takeWhile(v, pred)",
      "Leading elements satisfying pred; lazy when given an iterator.") { a =>
      val f = argFn(a, 1, "takeWhile")
      a(0) match
        case it: VIter => new VIter(it.it.takeWhile(v => call(f, v).truthy), "lazy")
        case arr: VArr =>
          val out = new mutable.ArrayBuffer[Value]
          var i = 0
          var go = true
          while go && i < arr.items.length do
            if call(f, arr.items(i)).truthy then { out += arr.items(i); i += 1 } else go = false
          new VArr(out)
        case v => Err.eval(s"takeWhile: expected an array or iterator, got ${v.typeName}")
    }

    define("dropWhile", 2, 2, "array", "dropWhile(v, pred)",
      "Drops leading elements satisfying pred; lazy when given an iterator.") { a =>
      val f = argFn(a, 1, "dropWhile")
      a(0) match
        case it: VIter => new VIter(it.it.dropWhile(v => call(f, v).truthy), "lazy")
        case arr: VArr =>
          var i = 0
          var go = true
          while go && i < arr.items.length do
            if call(f, arr.items(i)).truthy then i += 1 else go = false
          VArr.of(arr.items.drop(i))
        case v => Err.eval(s"dropWhile: expected an array or iterator, got ${v.typeName}")
    }

    define("distinctBy", 2, 2, "array", "distinctBy(arr, key)",
      "Removes elements whose key has been seen before.") { a =>
      val f = argFn(a, 1, "distinctBy")
      val seen = mutable.HashSet.empty[String]
      val out = new mutable.ArrayBuffer[Value]
      for v <- argArr(a, 0, "distinctBy").items do
        if seen.add(call(f, v).repr) then out += v
      new VArr(out)
    }

    define("chunk", 2, 2, "array", "chunk(arr, n)",
      "Splits into consecutive groups of n; the last group may be shorter.") { a =>
      val n = argIdx(a, 1, "chunk")
      if n <= 0 then Err.eval("chunk: size must be positive")
      VArr.of(argArr(a, 0, "chunk").items.grouped(n).map(g => VArr.of(g.toSeq)).toSeq)
    }

    define("windows", 2, 2, "array", "windows(arr, n)",
      "Every overlapping run of n consecutive elements.") { a =>
      val n = argIdx(a, 1, "windows")
      if n <= 0 then Err.eval("windows: size must be positive")
      VArr.of(argArr(a, 0, "windows").items.sliding(n).map(g => VArr.of(g.toSeq)).toSeq)
    }

    define("maxBy", 2, 2, "array", "maxBy(arr, key)", "Element with the largest key.") { a =>
      extremeBy(a, "maxBy", larger = true)
    }
    define("minBy", 2, 2, "array", "minBy(arr, key)", "Element with the smallest key.") { a =>
      extremeBy(a, "minBy", larger = false)
    }

    define("sumBy", 2, 2, "array", "sumBy(arr, key)", "Sum of key(element) over the array.") { a =>
      val f = argFn(a, 1, "sumBy")
      val items = argArr(a, 0, "sumBy").items
      var allInt = true
      var acc = 0.0
      var iacc = 0L
      var i = 0
      while i < items.length do
        call(f, items(i)) match
          case VInt(v)   => iacc += v; acc += v.toDouble
          case VFloat(v) => allInt = false; acc += v
          case v         => Err.eval(s"sumBy: key returned ${v.typeName}, not a number")
        i += 1
      if allInt then VInt.of(iacc) else VFloat(acc)
    }

    define("frequencies", 1, 1, "array", "frequencies(arr)",
      "Counts how often each element occurs, keyed by its text form.") { a =>
      val m = mutable.LinkedHashMap.empty[String, Value]
      for v <- argArr(a, 0, "frequencies").items do
        val k = v.display
        m(k) = VInt.of(m.get(k).map(Values.toLong).getOrElse(0L) + 1L)
      new VObj(m)
    }

    define("intersperse", 2, 2, "array", "intersperse(arr, sep)",
      "Inserts sep between consecutive elements.") { a =>
      val items = argArr(a, 0, "intersperse").items
      val out = new mutable.ArrayBuffer[Value]
      var i = 0
      while i < items.length do
        if i > 0 then out += a(1)
        out += items(i)
        i += 1
      new VArr(out)
    }

    define("enumerate", 1, 1, "array", "enumerate(v)",
      "Pairs each element with its index as [index, element]; lazy for iterators.") { a =>
      a(0) match
        case it: VIter =>
          new VIter(it.it.zipWithIndex.map((v, i) => VArr.of(Seq(VInt.of(i.toLong), v))), "lazy")
        case arr: VArr =>
          VArr.of(arr.items.iterator.zipWithIndex.map((v, i) => VArr.of(Seq(VInt.of(i.toLong), v))).toSeq)
        case v => Err.eval(s"enumerate: expected an array or iterator, got ${v.typeName}")
    }

  private def extremeBy(a: Array[Value], fn: String, larger: Boolean): Value =
    val items = argArr(a, 0, fn).items
    if items.isEmpty then Err.eval(s"$fn: array is empty")
    val f = argFn(a, 1, fn)
    var best = items(0)
    var bestKey = call(f, best)
    var i = 1
    while i < items.length do
      val k = call(f, items(i))
      val c = Values.compare(k, bestKey)
      if (larger && c > 0) || (!larger && c < 0) then
        best = items(i)
        bestKey = k
      i += 1
    best

  // -------------------------------------------------------------------------
  // Copy-on-write updates
  // -------------------------------------------------------------------------

  private def immutable(): Unit =
    define("assoc", 3, 3, "object", "assoc(v, key, value)",
      "Copy of an object or array with one key or index replaced.") { a =>
      a(0) match
        case o: VObj =>
          val m = mutable.LinkedHashMap.from(o.fields)
          m(argStr(a, 1, "assoc")) = a(2)
          new VObj(m)
        case arr: VArr =>
          val copy = VArr.of(arr.items.toSeq)
          Index.set(copy, a(1), a(2))
          copy
        case v => Err.eval(s"assoc: expected an object or array, got ${v.typeName}")
    }

    define("dissoc", 2, -1, "object", "dissoc(o, ...keys)",
      "Copy of an object without the given keys.") { a =>
      val m = mutable.LinkedHashMap.from(argObj(a, 0, "dissoc").fields)
      var i = 1
      while i < a.length do
        m.remove(a(i).display)
        i += 1
      new VObj(m)
    }

    define("conj", 2, -1, "array", "conj(arr, ...values)",
      "Copy of an array with values appended.") { a =>
      val out = VArr.of(argArr(a, 0, "conj").items.toSeq)
      var i = 1
      while i < a.length do
        out.items += a(i)
        i += 1
      out
    }

    define("getIn", 2, 3, "object", "getIn(v, path, default?)",
      "Follows an array of keys and indices, yielding the default if the path is absent.") { a =>
      val path = argArr(a, 1, "getIn").items
      val fallback = opt(a, 2)
      var cur = a(0)
      var i = 0
      var ok = true
      while ok && i < path.length do
        cur match
          case o: VObj =>
            o.fields.get(path(i).display) match
              case Some(next) => cur = next
              case None       => ok = false
          case arr: VArr =>
            val idx = Index.normalize(Values.toIndex(path(i), "path index"), arr.items.length)
            if idx >= 0 && idx < arr.items.length then cur = arr.items(idx) else ok = false
          case _ => ok = false
        i += 1
      if ok then cur else fallback
    }

    define("assocIn", 3, 3, "object", "assocIn(v, path, value)",
      "Copy with a nested key replaced; containers along the path are copied too.") { a =>
      assocPath(a(0), argArr(a, 1, "assocIn").items.toSeq, _ => a(2), "assocIn")
    }

    define("updateIn", 3, 3, "object", "updateIn(v, path, f)",
      "Copy with a nested value replaced by f(oldValue).") { a =>
      val f = argFn(a, 2, "updateIn")
      assocPath(a(0), argArr(a, 1, "updateIn").items.toSeq, old => call(f, old), "updateIn")
    }

  /** Rebuilds only the containers along `path`, sharing everything else. */
  private def assocPath(target: Value, path: Seq[Value], update: Value => Value, fn: String): Value =
    if path.isEmpty then update(target)
    else
      val key = path.head
      target match
        case o: VObj =>
          val k = key.display
          val child = o.fields.getOrElse(k, VNull)
          val m = mutable.LinkedHashMap.from(o.fields)
          m(k) = assocPath(child, path.tail, update, fn)
          new VObj(m)
        case arr: VArr =>
          val idx = Index.normalize(Values.toIndex(key, s"$fn path index"), arr.items.length)
          if idx < 0 || idx >= arr.items.length then
            Err.eval(s"$fn: index ${key.display} is out of bounds (length ${arr.items.length})")
          val copy = VArr.of(arr.items.toSeq)
          copy.items(idx) = assocPath(arr.items(idx), path.tail, update, fn)
          copy
        case VNull =>
          // An absent branch grows into an object, so a path can be created wholesale.
          val m = mutable.LinkedHashMap.empty[String, Value]
          m(key.display) = assocPath(VNull, path.tail, update, fn)
          new VObj(m)
        case v => Err.eval(s"$fn: cannot descend into ${v.typeName}")

  // -------------------------------------------------------------------------
  // Lazy sequences
  // -------------------------------------------------------------------------

  private def lazySeqs(): Unit =
    define("naturals", 0, 1, "iterator", "naturals(start?)",
      "An unbounded iterator counting up from start (default 0).") { a =>
      val start = if a.nonEmpty then argInt(a, 0, "naturals") else 0L
      new VIter(Iterator.iterate(start)(_ + 1L).map(VInt.of), "naturals")
    }

    define("iterate", 2, 2, "iterator", "iterate(f, seed)",
      "An unbounded iterator of seed, f(seed), f(f(seed)), …") { a =>
      val f = argFn(a, 0, "iterate")
      new VIter(Iterator.iterate(a(1))(v => call(f, v)), "iterate")
    }

    define("repeatedly", 1, 1, "iterator", "repeatedly(v)",
      "An unbounded iterator yielding the same value forever.") { a =>
      val v = a(0)
      new VIter(Iterator.continually(v), "repeat")
    }

    define("cycle", 1, 1, "iterator", "cycle(arr)",
      "An unbounded iterator repeating an array's elements.") { a =>
      val items = argArr(a, 0, "cycle").items
      if items.isEmpty then Err.eval("cycle: array is empty")
      new VIter(Iterator.continually(items).flatten, "cycle")
    }

    define("toArray", 1, 1, "iterator", "toArray(v)",
      "Realises an iterator into an array. Never pass an unbounded one without take().") { a =>
      a(0) match
        case it: VIter => VArr.of(it.it.toSeq)
        case arr: VArr => arr
        case v         => VArr.of(ForIn.iterator(v).toSeq)
    }
