package com.fartech.sfl

import Builtins.*
import scala.collection.mutable

/** Arrays, objects, iterators and JSON. */
object BuiltinsColl:

  def register(): Unit =
    arrays()
    higherOrder()
    objects()
    multidim()
    iterators()
    json()

  private def arrays(): Unit =
    define("array", 0, -1, "array", "array(...)", "Builds an array from the arguments.") { a =>
      VArr.of(a.toSeq)
    }

    define("newArray", 1, 2, "array", "newArray(n, fill?)",
      "Array of n elements, each set to fill (default null).") { a =>
      val n = argIdx(a, 0, "newArray")
      if n < 0 then Err.eval("newArray: length must not be negative")
      if n > 50_000_000 then Err.eval(s"newArray: length $n is too large")
      val fill = opt(a, 1)
      val buf = new mutable.ArrayBuffer[Value](math.max(n, 1))
      var i = 0
      while i < n do { buf += fill; i += 1 }
      new VArr(buf)
    }

    define("range", 1, 3, "array", "range(a, b?, step?)",
      "Integers from 0 to a, or from a to b, optionally stepping by step.") { a =>
      val (start, end, step) =
        if a.length == 1 then (0L, argInt(a, 0, "range"), 1L)
        else if a.length == 2 then (argInt(a, 0, "range"), argInt(a, 1, "range"), 1L)
        else (argInt(a, 0, "range"), argInt(a, 1, "range"), argInt(a, 2, "range"))
      if step == 0 then Err.eval("range: step must not be zero")
      val count = if step > 0 then (end - start + step - 1) / step else (end - start + step + 1) / step
      if count > 50_000_000L then Err.eval(s"range: would produce $count elements")
      val buf = new mutable.ArrayBuffer[Value](math.max(count.toInt, 1))
      var v = start
      while (if step > 0 then v < end else v > end) do
        buf += VInt.of(v)
        v += step
      new VArr(buf)
    }

    define("push", 2, -1, "array", "push(arr, ...)", "Appends values in place; returns the array.") { a =>
      val arr = argArr(a, 0, "push")
      var i = 1
      while i < a.length do { arr.items += a(i); i += 1 }
      arr
    }

    define("pop", 1, 1, "array", "pop(arr)", "Removes and returns the last element.") { a =>
      val arr = argArr(a, 0, "pop")
      if arr.items.isEmpty then Err.eval("pop: array is empty")
      arr.items.remove(arr.items.length - 1)
    }

    define("shift", 1, 1, "array", "shift(arr)", "Removes and returns the first element.") { a =>
      val arr = argArr(a, 0, "shift")
      if arr.items.isEmpty then Err.eval("shift: array is empty")
      arr.items.remove(0)
    }

    define("unshift", 2, -1, "array", "unshift(arr, ...)",
      "Inserts values at the front; returns the array.") { a =>
      val arr = argArr(a, 0, "unshift")
      arr.items.prependAll(a.drop(1))
      arr
    }

    define("insert", 3, 3, "array", "insert(arr, i, v)", "Inserts v before index i.") { a =>
      val arr = argArr(a, 0, "insert")
      val i = Index.normalize(argIdx(a, 1, "insert"), arr.items.length)
      if i < 0 || i > arr.items.length then Err.eval(s"insert: index out of bounds (length ${arr.items.length})")
      arr.items.insert(i, a(2))
      arr
    }

    define("removeAt", 2, 2, "array", "removeAt(arr, i)", "Removes and returns the element at index i.") { a =>
      val arr = argArr(a, 0, "removeAt")
      val i = Index.normalize(argIdx(a, 1, "removeAt"), arr.items.length)
      if i < 0 || i >= arr.items.length then Err.eval(s"removeAt: index out of bounds (length ${arr.items.length})")
      arr.items.remove(i)
    }

    define("clear", 1, 1, "array", "clear(v)", "Empties an array or object in place.") { a =>
      a(0) match
        case arr: VArr => arr.items.clear(); arr
        case o: VObj   => o.fields.clear(); o
        case v         => Err.eval(s"clear: expected an array or object, got ${v.typeName}")
    }

    define("concat", 1, -1, "array", "concat(...)", "New array containing every argument's elements.") { a =>
      val buf = new mutable.ArrayBuffer[Value]
      var i = 0
      while i < a.length do
        a(i) match
          case arr: VArr => buf ++= arr.items
          case v         => buf += v
        i += 1
      new VArr(buf)
    }

    define("flatten", 1, 2, "array", "flatten(arr, depth?)",
      "Flattens nested arrays, to the given depth (default 1).") { a =>
      val depth = if a.length > 1 then argIdx(a, 1, "flatten") else 1
      val out = new mutable.ArrayBuffer[Value]
      def go(items: mutable.ArrayBuffer[Value], d: Int): Unit =
        var i = 0
        while i < items.length do
          items(i) match
            case sub: VArr if d > 0 => go(sub.items, d - 1)
            case v                  => out += v
          i += 1
      go(argArr(a, 0, "flatten").items, depth)
      new VArr(out)

    }

    define("unique", 1, 1, "array", "unique(arr)", "Removes duplicates, keeping the first occurrence.") { a =>
      val out = new mutable.ArrayBuffer[Value]
      for v <- argArr(a, 0, "unique").items do
        if !out.exists(Values.equal(_, v)) then out += v
      new VArr(out)
    }

    define("zip", 2, 2, "array", "zip(a, b)", "Pairs elements into an array of two-element arrays.") { a =>
      val x = argArr(a, 0, "zip").items
      val y = argArr(a, 1, "zip").items
      val n = math.min(x.length, y.length)
      val out = new mutable.ArrayBuffer[Value](n)
      var i = 0
      while i < n do
        out += VArr.of(Seq(x(i), y(i)))
        i += 1
      new VArr(out)
    }

    define("first", 1, 1, "array", "first(arr)", "First element, or null when empty.") { a =>
      val arr = argArr(a, 0, "first")
      if arr.items.isEmpty then VNull else arr.items(0)
    }

    define("last", 1, 1, "array", "last(arr)", "Last element, or null when empty.") { a =>
      val arr = argArr(a, 0, "last")
      if arr.items.isEmpty then VNull else arr.items(arr.items.length - 1)
    }

    define("take", 2, 2, "array", "take(v, n)",
      "First n elements; lazy when given an iterator.") { a =>
      val n = math.max(0, argIdx(a, 1, "take"))
      a(0) match
        case it: VIter => new VIter(it.it.take(n), "lazy")
        case arr: VArr => VArr.of(arr.items.take(n))
        case v         => Err.eval(s"take: expected an array or iterator, got ${v.typeName}")
    }

    define("drop", 2, 2, "array", "drop(v, n)",
      "All but the first n elements; lazy when given an iterator.") { a =>
      val n = math.max(0, argIdx(a, 1, "drop"))
      a(0) match
        case it: VIter => new VIter(it.it.drop(n), "lazy")
        case arr: VArr => VArr.of(arr.items.drop(n))
        case v         => Err.eval(s"drop: expected an array or iterator, got ${v.typeName}")
    }

    define("sum", 1, 1, "array", "sum(arr)", "Sum of a numeric array; 0 when empty.") { a =>
      val items = argArr(a, 0, "sum").items
      var allInt = true
      var acc = 0.0
      var iacc = 0L
      var i = 0
      while i < items.length do
        items(i) match
          case VInt(v)   => iacc += v; acc += v.toDouble
          case VFloat(v) => allInt = false; acc += v
          case v         => Err.eval(s"sum: element ${i + 1} is ${v.typeName}, not a number")
        i += 1
      if allInt then VInt.of(iacc) else VFloat(acc)
    }

    define("sort", 1, 2, "array", "sort(arr, compare?)",
      "New sorted array; compare(a, b) should return a negative, zero or positive number.") { a =>
      val items = argArr(a, 0, "sort").items.toArray
      if a.length > 1 then
        val cmp = argFn(a, 1, "sort")
        sortWith(items, (x, y) => Values.toDouble(call(cmp, x, y)) < 0, "sort")
      else sortWith(items, (x, y) => Values.compare(x, y) < 0, "sort")
      VArr.of(items.toSeq)
    }

    define("sortBy", 2, 2, "array", "sortBy(arr, key)",
      "New array sorted by the value key(element) returns.") { a =>
      val items = argArr(a, 0, "sortBy").items.toArray
      val key = argFn(a, 1, "sortBy")
      // Compute each key once rather than on every comparison.
      val decorated = items.map(v => (call(key, v), v))
      sortWith(decorated, (x, y) => Values.compare(x._1, y._1) < 0, "sortBy")
      VArr.of(decorated.iterator.map(_._2).toSeq)
    }

  private def sortWith[T](arr: Array[T], lt: (T, T) => Boolean, fn: String): Unit =
    try scala.util.Sorting.stableSort(arr, lt)
    catch
      case e: IllegalArgumentException =>
        Err.eval(s"$fn: the comparison function is inconsistent (${e.getMessage})")

  private def higherOrder(): Unit =
    define("map", 2, 2, "array", "map(v, f)",
      "Applies f to each element of an array or iterator, or each value of an object.") { a =>
      a(0) match
        case it: VIter =>
          val f = argFn(a, 1, "map")
          new VIter(it.it.map(v => call(f, v)), "lazy")
        case arr: VArr =>
          val f = argFn(a, 1, "map")
          val out = new mutable.ArrayBuffer[Value](arr.items.length)
          var i = 0
          while i < arr.items.length do
            out += call(f, arr.items(i))
            i += 1
          new VArr(out)
        case o: VObj =>
          val f = argFn(a, 1, "map")
          val m = mutable.LinkedHashMap.empty[String, Value]
          for (k, v) <- o.fields do m(k) = call(f, VStr(k), v)
          new VObj(m)
        case v => Err.eval(s"map: expected an array or object, got ${v.typeName}")
    }

    define("filter", 2, 2, "array", "filter(v, pred)",
      "Keeps elements for which pred is truthy. On objects, pred receives (key, value).") { a =>
      a(0) match
        case it: VIter =>
          val f = argFn(a, 1, "filter")
          new VIter(it.it.filter(v => call(f, v).truthy), "lazy")
        case arr: VArr =>
          val f = argFn(a, 1, "filter")
          val out = new mutable.ArrayBuffer[Value]
          var i = 0
          while i < arr.items.length do
            val v = arr.items(i)
            if call(f, v).truthy then out += v
            i += 1
          new VArr(out)
        case o: VObj =>
          val f = argFn(a, 1, "filter")
          val m = mutable.LinkedHashMap.empty[String, Value]
          for (k, v) <- o.fields do if call(f, VStr(k), v).truthy then m(k) = v
          new VObj(m)
        case v => Err.eval(s"filter: expected an array or object, got ${v.typeName}")
    }

    define("reduce", 2, 3, "array", "reduce(arr, f, initial?)",
      "Folds left with f(accumulator, element).") { a =>
      val items = argArr(a, 0, "reduce").items
      val f = argFn(a, 1, "reduce")
      var i = 0
      var acc =
        if a.length > 2 then a(2)
        else if items.isEmpty then Err.eval("reduce: array is empty and no initial value was given")
        else { i = 1; items(0) }
      while i < items.length do
        acc = call(f, acc, items(i))
        i += 1
      acc
    }

    define("forEach", 2, 2, "array", "forEach(v, f)",
      "Calls f for each element, or for each (key, value) of an object.") { a =>
      a(0) match
        case arr: VArr =>
          val f = argFn(a, 1, "forEach")
          var i = 0
          while i < arr.items.length do
            call(f, arr.items(i))
            i += 1
          VNull
        case o: VObj =>
          val f = argFn(a, 1, "forEach")
          for (k, v) <- o.fields.toSeq do call(f, VStr(k), v)
          VNull
        case v => Err.eval(s"forEach: expected an array or object, got ${v.typeName}")
    }

    define("find", 2, 2, "array", "find(arr, pred)",
      "First element satisfying pred, or null.") { a =>
      val items = argArr(a, 0, "find").items
      val f = argFn(a, 1, "find")
      var found: Value = VNull
      var i = 0
      while i < items.length && (found eq VNull) do
        if call(f, items(i)).truthy then found = items(i)
        i += 1
      found
    }

    define("findIndex", 2, 2, "array", "findIndex(arr, pred)",
      "Index of the first element satisfying pred, or -1.") { a =>
      val items = argArr(a, 0, "findIndex").items
      val f = argFn(a, 1, "findIndex")
      var found = -1
      var i = 0
      while i < items.length && found < 0 do
        if call(f, items(i)).truthy then found = i
        i += 1
      VInt.of(found.toLong)
    }

    define("any", 2, 2, "array", "any(arr, pred)", "True when at least one element satisfies pred.") { a =>
      val items = argArr(a, 0, "any").items
      val f = argFn(a, 1, "any")
      var hit = false
      var i = 0
      while i < items.length && !hit do
        if call(f, items(i)).truthy then hit = true
        i += 1
      VBool.of(hit)
    }

    define("every", 2, 2, "array", "every(arr, pred)", "True when every element satisfies pred.") { a =>
      val items = argArr(a, 0, "every").items
      val f = argFn(a, 1, "every")
      var all = true
      var i = 0
      while i < items.length && all do
        if !call(f, items(i)).truthy then all = false
        i += 1
      VBool.of(all)
    }

    define("count", 2, 2, "array", "count(arr, pred)", "Number of elements satisfying pred.") { a =>
      val items = argArr(a, 0, "count").items
      val f = argFn(a, 1, "count")
      var n = 0
      var i = 0
      while i < items.length do
        if call(f, items(i)).truthy then n += 1
        i += 1
      VInt.of(n.toLong)
    }

  private def objects(): Unit =
    define("object", 0, 0, "object", "object()", "Creates an empty object.")(_ => VObj.empty)

    define("keys", 1, 1, "object", "keys(o)", "Array of an object's keys, in insertion order.") { a =>
      VArr.of(argObj(a, 0, "keys").fields.keysIterator.map(VStr(_)).toSeq)
    }

    define("values", 1, 1, "object", "values(o)", "Array of an object's values.") { a =>
      VArr.of(argObj(a, 0, "values").fields.valuesIterator.toSeq)
    }

    define("entries", 1, 1, "object", "entries(o)", "Array of [key, value] pairs.") { a =>
      VArr.of(argObj(a, 0, "entries").fields.iterator.map((k, v) => VArr.of(Seq(VStr(k), v))).toSeq)
    }

    define("fromEntries", 1, 1, "object", "fromEntries(pairs)",
      "Builds an object from an array of [key, value] pairs.") { a =>
      val m = mutable.LinkedHashMap.empty[String, Value]
      for (p, i) <- argArr(a, 0, "fromEntries").items.zipWithIndex do
        p match
          case pair: VArr if pair.items.length == 2 => m(pair.items(0).display) = pair.items(1)
          case _ => Err.eval(s"fromEntries: element ${i + 1} is not a two-element array")
      new VObj(m)
    }

    define("has", 2, 2, "object", "has(o, key)", "True when the object has the key.") { a =>
      VBool.of(argObj(a, 0, "has").fields.contains(argStr(a, 1, "has")))
    }

    define("get", 2, 3, "object", "get(v, key, default?)",
      "Reads a key or index without raising an error when it is missing.") { a =>
      val fallback = opt(a, 2)
      a(0) match
        case o: VObj => o.fields.getOrElse(argStr(a, 1, "get"), fallback)
        case arr: VArr =>
          val i = Index.normalize(argIdx(a, 1, "get"), arr.items.length)
          if i < 0 || i >= arr.items.length then fallback else arr.items(i)
        case VNull => fallback
        case v     => Err.eval(s"get: expected an object or array, got ${v.typeName}")
    }

    define("set", 3, 3, "object", "set(v, key, value)",
      "Writes a key or index in place; returns the container.") { a =>
      Index.set(a(0), a(1), a(2))
      a(0)
    }

    define("remove", 2, 2, "object", "remove(o, key)",
      "Removes a key and returns its value, or null when absent.") { a =>
      argObj(a, 0, "remove").fields.remove(argStr(a, 1, "remove")).getOrElse(VNull)
    }

    define("merge", 1, -1, "object", "merge(...)",
      "New object combining the arguments; later keys win.") { a =>
      val m = mutable.LinkedHashMap.empty[String, Value]
      var i = 0
      while i < a.length do
        for (k, v) <- argObj(a, i, "merge").fields do m(k) = v
        i += 1
      new VObj(m)
    }

    define("size", 1, 1, "object", "size(v)", "Number of entries in an object, array or string.") { a =>
      a(0) match
        case o: VObj   => VInt.of(o.fields.size.toLong)
        case arr: VArr => VInt.of(arr.items.length.toLong)
        case t: VTuple => VInt.of(t.items.length.toLong)
        case VStr(s)   => VInt.of(s.length.toLong)
        case v         => Err.eval(s"size: ${v.typeName} has no size")
    }

    define("copy", 1, 1, "object", "copy(v)", "Shallow copy of an array or object.") { a =>
      a(0) match
        case arr: VArr => VArr.of(arr.items.toSeq)
        case o: VObj   => new VObj(mutable.LinkedHashMap.from(o.fields))
        case v         => v
    }

    define("deepCopy", 1, 1, "object", "deepCopy(v)", "Recursive copy of nested arrays and objects.") { a =>
      def go(v: Value, depth: Int): Value =
        // Kept at its own, much lower cap so the SFL implementation in stdlib/obj.sfl
        // — which compiled programs link and which runs under the call-depth guard —
        // reports the same thing at the same depth.
        if depth > 200 then Err.eval("deepCopy: structure is nested too deeply")
        v match
          case arr: VArr => VArr.of(arr.items.map(go(_, depth + 1)).toSeq)
          case o: VObj   => VObj.of(o.fields.iterator.map((k, x) => (k, go(x, depth + 1))).toSeq)
          // The tuple itself is immutable, but its elements may not be.
          case t: VTuple => new VTuple(t.items.map(go(_, depth + 1)))
          case other     => other
      go(a(0), 0)
    }

  private def multidim(): Unit =
    // These mirror stdlib/nd.sfl line for line: the stdlib file is what compiled
    // programs link, this is what interpreted programs run, and the tuple suite
    // holds the two to identical output.
    define("ndarray", 1, 2, "array", "ndarray(dims, fill?)",
      "A nested rectangular array: ndarray([2, 3], 0) is a 2x3 grid of zeros.") { a =>
      val dims = a(0) match
        case arr: VArr => arr
        case v         => Err.eval(s"ndarray: argument 1 must be an array, got ${v.typeName}")
      if dims.items.isEmpty then Err.eval("ndarray: dims must name at least one dimension")
      for d <- dims.items do
        d match
          case VInt(n) if n >= 0 => ()
          case other =>
            Err.eval(s"ndarray: every dimension must be a non-negative integer, got ${other.repr}")
      val fill = if a.length > 1 then a(1) else VNull
      def build(at: Int): Value =
        val n = dims.items(at).asInstanceOf[VInt].v.toInt
        if at == dims.items.length - 1 then
          VArr.of(Seq.fill(n)(fill))
        else
          VArr.of(Seq.fill(n)(build(at + 1)))
      build(0)
    }

    define("shape", 1, 1, "array", "shape(a)",
      "Dimensions of a nested array, walking first elements: shape([[1,2],[3,4]]) is [2, 2].") { a =>
      val out = mutable.ArrayBuffer.empty[Value]
      var cur = a(0)
      var go = true
      while go do
        cur match
          case arr: VArr =>
            out += VInt.of(arr.items.length.toLong)
            if arr.items.isEmpty then go = false else cur = arr.items(0)
          case _ => go = false
      VArr.of(out.toSeq)
    }

    define("isRectangular", 1, 1, "array", "isRectangular(a)",
      "True when every row of every level has the length shape() reports.") { a =>
      def rect(v: Value, dims: Array[Int], at: Int): Boolean =
        if at >= dims.length then true
        else v match
          case arr: VArr if arr.items.length == dims(at) =>
            at == dims.length - 1 || arr.items.forall(rect(_, dims, at + 1))
          case _ => false
      a(0) match
        case arr: VArr =>
          val dims = mutable.ArrayBuffer.empty[Int]
          var cur: Value = arr
          var go = true
          while go do
            cur match
              case x: VArr =>
                dims += x.items.length
                if x.items.isEmpty then go = false else cur = x.items(0)
              case _ => go = false
          VBool.of(rect(arr, dims.toArray, 0))
        case _ => VBool.False
    }

  private def iterators(): Unit =
    define("iter", 1, 1, "iterator", "iter(v)",
      "Iterator over an array's elements, an object's keys, or a string's characters.") { a =>
      new VIter(ForIn.iterator(a(0)), a(0).typeName)
    }

    define("hasNext", 1, 1, "iterator", "hasNext(it)", "True when the iterator has more elements.") { a =>
      a(0) match
        case it: VIter => VBool.of(it.it.hasNext)
        case v         => Err.eval(s"hasNext: expected an iterator, got ${v.typeName}")
    }

    define("iterNext", 1, 1, "iterator", "iterNext(it)", "Advances the iterator and returns the element.") { a =>
      a(0) match
        case it: VIter =>
          if !it.it.hasNext then Err.eval("iterNext: the iterator is exhausted")
          it.it.next()
        case v => Err.eval(s"iterNext: expected an iterator, got ${v.typeName}")
    }


  private def json(): Unit =
    define("jsonParse", 1, 1, "json", "jsonParse(text)", "Parses JSON text into SFL values.") { a =>
      Json.parse(argStr(a, 0, "jsonParse"))
    }

    define("jsonStringify", 1, 2, "json", "jsonStringify(v, pretty?)",
      "Renders a value as JSON; pass true for indented output.") { a =>
      val pretty = a.length > 1 && a(1).truthy
      VStr(Json.stringify(a(0), pretty))
    }
