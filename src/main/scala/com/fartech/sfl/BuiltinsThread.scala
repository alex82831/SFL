package com.fartech.sfl

import Builtins.*
import java.util.concurrent.{CountDownLatch, Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

/** Threads, locks, atomics and channels. */
object BuiltinsThread:

  def register(): Unit =
    threads()
    futures()
    channels()
    locks()

  /** Unwraps a handle of the expected kind, naming the builtin when it is wrong. */
  private def handleOf[T](a: Array[Value], i: Int, kind: String, fn: String): T = a(i) match
    case h: VHandle if h.kind == kind => h.payload.asInstanceOf[T]
    case v => Err.eval(s"$fn: argument ${i + 1} must be a $kind, got ${v.typeName}")

  private def optTimeout(a: Array[Value], i: Int, fn: String): Long =
    if i < a.length && (a(i) ne VNull) then
      val ms = argInt(a, i, fn)
      if ms < 0 then Err.eval(s"$fn: timeout must not be negative")
      ms
    else -1L

  /** awaitAny's trailing timeout. Deliberately not optTimeout: there -1 is an error,
    * here it is the documented "block until something happens", so every negative is
    * normalised to it rather than rejected. */
  private def laxTimeout(a: Array[Value], i: Int, fn: String): Long =
    if i < a.length && (a(i) ne VNull) then
      val ms = argInt(a, i, fn)
      if ms < 0 then -1L else ms
    else -1L

  // -------------------------------------------------------------------------
  // Threads
  // -------------------------------------------------------------------------

  private def threads(): Unit =
    define("spawn", 1, -1, "thread", "spawn(f, ...args)",
      "Runs f(...args) on a new thread; the program waits for it before exiting.") { a =>
      val f = argFn(a, 0, "spawn")
      val args = a.drop(1)
      // The closure is captured here; the thread gets its own interpreter but shares
      // globals, so it can call anything the spawning code could.
      val task = Threads.start("", () => Call.invokeValue(f, args, Builtins.rt), daemon = false)
      new VHandle("thread", task, task.name)
    }

    define("spawnDaemon", 1, -1, "thread", "spawnDaemon(f, ...args)",
      "Like spawn, but the thread does not keep the program alive; use for endless loops.") { a =>
      val f = argFn(a, 0, "spawnDaemon")
      val args = a.drop(1)
      val task = Threads.start("", () => Call.invokeValue(f, args, Builtins.rt), daemon = true)
      new VHandle("thread", task, task.name)
    }

    define("await", 1, 2, "thread", "await(t, timeoutMs?)",
      "Waits for a thread and returns its value, re-raising any error it hit.") { a =>
      Threads.join(handleOf[Threads.Task](a, 0, "thread", "await"), optTimeout(a, 1, "await"))
    }

    define("isAlive", 1, 1, "thread", "isAlive(t)", "True while the thread is still running.") { a =>
      VBool.of(handleOf[Threads.Task](a, 0, "thread", "isAlive").thread.isAlive)
    }

    define("threadName", 1, 1, "thread", "threadName(t)", "The thread's name.") { a =>
      VStr(handleOf[Threads.Task](a, 0, "thread", "threadName").name)
    }

    define("threadFailed", 1, 1, "thread", "threadFailed(t)",
      "True when the thread ended with an error; await() reports the details.") { a =>
      VBool.of(handleOf[Threads.Task](a, 0, "thread", "threadFailed").failure != null)
    }

    define("awaitAll", 1, 2, "thread", "awaitAll(threads, timeoutMs?)",
      "Waits for an array of threads and returns their values in order.") { a =>
      val handles = argArr(a, 0, "awaitAll").items
      val timeout = optTimeout(a, 1, "awaitAll")
      val out = new scala.collection.mutable.ArrayBuffer[Value](handles.length)
      var i = 0
      while i < handles.length do
        handles(i) match
          case h: VHandle if h.kind == "thread" =>
            out += Threads.join(h.payload.asInstanceOf[Threads.Task], timeout)
          case v => Err.eval(s"awaitAll: element ${i + 1} is ${v.typeName}, not a thread")
        i += 1
      new VArr(out)
    }

    define("awaitAny", 1, 2, "thread", "awaitAny(threads, timeoutMs?)",
      "Waits until one of the threads completes and returns its index; -1 on timeout. A failed thread counts as complete.") { a =>
      val handles = argArr(a, 0, "awaitAny").items
      val timeout = laxTimeout(a, 1, "awaitAny")
      // Every element is checked before anything waits, so a bad one is reported
      // even when another element finished long ago. The wording is awaitAll's.
      // Copying the tasks out also snapshots the array: a program that mutates it
      // mid-wait cannot change what this call is waiting on.
      val tasks = new Array[Threads.Task](handles.length)
      var i = 0
      while i < handles.length do
        handles(i) match
          case h: VHandle if h.kind == "thread" => tasks(i) = h.payload.asInstanceOf[Threads.Task]
          case v => Err.eval(s"awaitAny: element ${i + 1} is ${v.typeName}, not a thread")
        i += 1
      // Nothing can ever complete, and "wait forever" must not mean it literally.
      if tasks.isEmpty then VInt.of(-1L)
      else VInt.of(Threads.awaitAny(tasks, timeout).toLong)
    }

    define("currentThreadName", 0, 0, "thread", "currentThreadName()",
      "Name of the thread that is running right now.") { _ =>
      VStr(Thread.currentThread().getName)
    }

    define("cpuCount", 0, 0, "thread", "cpuCount()",
      "Number of processors available, a reasonable default pool size.") { _ =>
      VInt.of(math.max(1, Runtime.getRuntime.availableProcessors()).toLong)
    }

    define("yieldThread", 0, 0, "thread", "yieldThread()",
      "Hints that the scheduler may run another thread now.") { _ =>
      Thread.`yield`()
      VNull
    }

    /**
     * A tiny fork/join: run f over every element in parallel, keeping order. Written as
     * a builtin so scripts get parallelism without hand-rolling thread bookkeeping.
     */
    define("parallelMap", 2, 3, "thread", "parallelMap(arr, f, workers?)",
      "Applies f to each element on several threads, preserving order.") { a =>
      val items = argArr(a, 0, "parallelMap").items
      val f = argFn(a, 1, "parallelMap")
      val requested =
        if a.length > 2 then argIdx(a, 2, "parallelMap")
        else math.max(1, Runtime.getRuntime.availableProcessors())
      if requested <= 0 then Err.eval("parallelMap: worker count must be positive")
      val workers = math.min(requested, math.max(1, items.length))
      val results = new Array[Value](items.length)
      val next = new java.util.concurrent.atomic.AtomicInteger(0)
      val snapshot = items.toArray

      val tasks = (0 until workers).map { _ =>
        Threads.start("", () => {
          var idx = next.getAndIncrement()
          while idx < snapshot.length do
            results(idx) = Call.invokeValue(f, Array(snapshot(idx)), Builtins.rt)
            idx = next.getAndIncrement()
          VNull
        })
      }
      // Join every worker even if one failed, so no thread is left running.
      var firstError: EvalError = null
      for t <- tasks do
        try Threads.join(t, -1L)
        catch case e: EvalError => if firstError == null then firstError = e
      if firstError != null then throw firstError
      VArr.of(results.toSeq)
    }

  // -------------------------------------------------------------------------
  // Futures — native twins of stdlib/async.sfl. A compiled program links the SFL
  // module; an interpreted one resolves these. Each twin goes through the same
  // registered builtins the module calls — spawn, await, awaitAny — so the error
  // texts agree structurally rather than by being copied.
  // -------------------------------------------------------------------------

  private def futures(): Unit =
    def builtin(name: String): Value = lookup(name).get

    define("async", 1, -1, "thread", "async(f, ...args)",
      "Starts f(...args) on a new thread and returns its handle: spawn, under its future-flavoured name.") { a =>
      argFn(a, 0, "async")
      // The SFL module has no spread call, so its argument list is a 16-way jump
      // table; the same ceiling applies here so both engines refuse alike.
      if a.length - 1 > 16 then
        Err.eval("async: cannot call a function with more than 16 arguments")
      call(builtin("spawn"), a.toSeq*)
    }

    define("andThen", 2, 2, "thread", "andThen(t, f)",
      "Returns a thread that waits for t and applies f to its result; t's failure travels down the chain.") { a =>
      handleOf[Threads.Task](a, 0, "thread", "andThen")
      val f = argFn(a, 1, "andThen")
      val t = a(0)
      // The module spawns `() -> f(await(t))`; this is that closure as a native.
      val body = new VNative("anonymous", 0, 0, "function", "anonymous()",
        "A function produced at run time.", _ => call(f, call(builtin("await"), t)))
      call(builtin("spawn"), body)
    }

    define("race", 1, 2, "thread", "race(ts, timeoutMs?)",
      "Waits for the first of the threads: [index, value] of the winner, or null when the timeout expires first.") { a =>
      argArr(a, 0, "race")
      val awaitAnyFn = builtin("awaitAny")
      val idx = (if a.length > 1 then call(awaitAnyFn, a(0), a(1)) else call(awaitAnyFn, a(0))) match
        case VInt(i) => i
        case v       => Err.eval(s"race: awaitAny returned ${v.typeName}") // unreachable
      if idx == -1L then VNull
      else
        val winner = argArr(a, 0, "race").items(idx.toInt)
        VArr.of(Seq(VInt.of(idx), call(builtin("await"), winner)))
    }

    define("withTimeout", 2, 2, "thread", "withTimeout(t, ms)",
      "Waits at most ms for t: [true, value] within the limit, [false, null] past it.") { a =>
      handleOf[Threads.Task](a, 0, "thread", "withTimeout")
      val idx = call(builtin("awaitAny"), VArr.of(Seq(a(0))), a(1)) match
        case VInt(i) => i
        case v       => Err.eval(s"withTimeout: awaitAny returned ${v.typeName}") // unreachable
      if idx == -1L then VArr.of(Seq(VBool.False, VNull))
      else VArr.of(Seq(VBool.True, call(builtin("await"), a(0))))
    }

    define("allSettled", 1, 1, "thread", "allSettled(ts)",
      "Waits for every thread: {\"ok\": true, \"value\": v} or {\"ok\": false, \"error\": message} objects, in order.") { a =>
      val items = argArr(a, 0, "allSettled").items
      val awaitFn = builtin("await")
      val out = new scala.collection.mutable.ArrayBuffer[Value](items.length)
      var i = 0
      while i < items.length do
        // The module wraps its await in try(), whose handler receives the message
        // string; catching here and keeping getMessage is that same contract.
        val entry =
          try VObj.of(Seq("ok" -> VBool.True, "value" -> call(awaitFn, items(i))))
          catch case e: EvalError => VObj.of(Seq("ok" -> VBool.False, "error" -> VStr(e.getMessage)))
        out += entry
        i += 1
      new VArr(out)
    }

  // -------------------------------------------------------------------------
  // Channels
  // -------------------------------------------------------------------------

  private def channels(): Unit =
    define("channel", 0, 1, "channel", "channel(capacity?)",
      "Creates a channel; with a capacity, send blocks while it is full.") { a =>
      val cap = if a.nonEmpty then argIdx(a, 0, "channel") else 0
      if cap < 0 then Err.eval("channel: capacity must not be negative")
      new VHandle("channel", new Chan(cap), if cap > 0 then s"cap=$cap" else "unbounded")
    }

    define("send", 2, 2, "channel", "send(ch, value)",
      "Puts a value on the channel, blocking while a bounded channel is full.") { a =>
      handleOf[Chan](a, 0, "channel", "send").send(a(1))
      VNull
    }

    define("receive", 1, 2, "channel", "receive(ch, timeoutMs?)",
      "Takes the next value, blocking; null when the channel closes or the wait expires.") { a =>
      val v = handleOf[Chan](a, 0, "channel", "receive").receive(optTimeout(a, 1, "receive"))
      if v == null then VNull else v
    }

    define("tryReceive", 1, 1, "channel", "tryReceive(ch)",
      "Takes the next value without waiting; null when the channel is empty.") { a =>
      val v = handleOf[Chan](a, 0, "channel", "tryReceive").tryReceive()
      if v == null then VNull else v
    }

    define("closeChannel", 1, 1, "channel", "closeChannel(ch)",
      "Closes the channel and wakes every waiting thread; queued values remain readable.") { a =>
      handleOf[Chan](a, 0, "channel", "closeChannel").close()
      VNull
    }

    define("channelClosed", 1, 1, "channel", "channelClosed(ch)", "True once the channel is closed.") { a =>
      VBool.of(handleOf[Chan](a, 0, "channel", "channelClosed").isClosed)
    }

    define("channelSize", 1, 1, "channel", "channelSize(ch)", "How many values are queued.") { a =>
      VInt.of(handleOf[Chan](a, 0, "channel", "channelSize").size.toLong)
    }

    define("channelDrain", 1, 1, "channel", "channelDrain(ch)",
      "Removes and returns everything queued right now, without waiting.") { a =>
      new VArr(handleOf[Chan](a, 0, "channel", "channelDrain").drain())
    }

    define("channelToArray", 1, 1, "channel", "channelToArray(ch)",
      "Receives until the channel is closed and drained, returning everything in order.") { a =>
      val ch = handleOf[Chan](a, 0, "channel", "channelToArray")
      val out = new scala.collection.mutable.ArrayBuffer[Value]
      var go = true
      while go do
        val v = ch.receive(-1L)
        if v == null then go = false else out += v
      new VArr(out)
    }

  // -------------------------------------------------------------------------
  // Locks, atomics, latches, semaphores
  // -------------------------------------------------------------------------

  private def locks(): Unit =
    define("mutex", 0, 0, "sync", "mutex()", "Creates a reentrant mutual-exclusion lock.") { _ =>
      new VHandle("mutex", new ReentrantLock(), "")
    }

    define("lock", 1, 1, "sync", "lock(m)",
      "Acquires the lock, blocking. Prefer withLock, which cannot leak it.") { a =>
      handleOf[ReentrantLock](a, 0, "mutex", "lock").lock()
      VNull
    }

    define("unlock", 1, 1, "sync", "unlock(m)", "Releases a lock held by this thread.") { a =>
      val m = handleOf[ReentrantLock](a, 0, "mutex", "unlock")
      if !m.isHeldByCurrentThread then Err.eval("unlock: this thread does not hold the lock")
      m.unlock()
      VNull
    }

    define("tryLock", 1, 2, "sync", "tryLock(m, timeoutMs?)",
      "Acquires the lock if it is free within the timeout; returns whether it succeeded.") { a =>
      val m = handleOf[ReentrantLock](a, 0, "mutex", "tryLock")
      val ms = optTimeout(a, 1, "tryLock")
      VBool.of(if ms < 0 then m.tryLock() else m.tryLock(ms, TimeUnit.MILLISECONDS))
    }

    define("withLock", 2, 2, "sync", "withLock(m, f)",
      "Runs f() holding the lock and releases it even if f raises.") { a =>
      val m = handleOf[ReentrantLock](a, 0, "mutex", "withLock")
      val f = argFn(a, 1, "withLock")
      m.lock()
      try call(f)
      finally m.unlock()
    }

    define("atomic", 0, 1, "sync", "atomic(initial?)",
      "A reference that several threads can update without a lock.") { a =>
      new VHandle("atomic", new AtomicReference[Value](opt(a, 0)), "")
    }

    define("atomicGet", 1, 1, "sync", "atomicGet(a)", "Reads the current value.") { a =>
      handleOf[AtomicReference[Value]](a, 0, "atomic", "atomicGet").get()
    }

    define("atomicSet", 2, 2, "sync", "atomicSet(a, v)", "Replaces the value.") { a =>
      handleOf[AtomicReference[Value]](a, 0, "atomic", "atomicSet").set(a(1))
      VNull
    }

    define("atomicAdd", 2, 2, "sync", "atomicAdd(a, n)",
      "Adds to a numeric value and returns the result, retrying until it wins.") { a =>
      val ref = handleOf[AtomicReference[Value]](a, 0, "atomic", "atomicAdd")
      val delta = a(1)
      var out: Value = VNull
      var done = false
      while !done do
        val current = ref.get()
        val updated = Arith.apply(BinOp.Add, current, delta)
        if ref.compareAndSet(current, updated) then
          out = updated
          done = true
      out
    }

    define("atomicUpdate", 2, 2, "sync", "atomicUpdate(a, f)",
      "Replaces the value with f(old), retrying until it wins. f may run more than once.") { a =>
      val ref = handleOf[AtomicReference[Value]](a, 0, "atomic", "atomicUpdate")
      val f = argFn(a, 1, "atomicUpdate")
      var out: Value = VNull
      var done = false
      while !done do
        val current = ref.get()
        val updated = call(f, current)
        if ref.compareAndSet(current, updated) then
          out = updated
          done = true
      out
    }

    define("atomicCompareSet", 3, 3, "sync", "atomicCompareSet(a, expected, v)",
      "Sets the value only if it still equals expected; returns whether it did.") { a =>
      val ref = handleOf[AtomicReference[Value]](a, 0, "atomic", "atomicCompareSet")
      // Compare by value rather than identity, which is what scripts expect.
      val current = ref.get()
      VBool.of(Values.equal(current, a(1)) && ref.compareAndSet(current, a(2)))
    }

    define("latch", 1, 1, "sync", "latch(n)",
      "A countdown latch that releases waiters once it reaches zero.") { a =>
      val n = argIdx(a, 0, "latch")
      if n < 0 then Err.eval("latch: count must not be negative")
      new VHandle("latch", new CountDownLatch(n), s"n=$n")
    }

    define("countDown", 1, 1, "sync", "countDown(l)", "Decrements the latch.") { a =>
      handleOf[CountDownLatch](a, 0, "latch", "countDown").countDown()
      VNull
    }

    define("awaitLatch", 1, 2, "sync", "awaitLatch(l, timeoutMs?)",
      "Waits for the latch to reach zero; returns whether it did.") { a =>
      val l = handleOf[CountDownLatch](a, 0, "latch", "awaitLatch")
      val ms = optTimeout(a, 1, "awaitLatch")
      if ms < 0 then { l.await(); VBool.True }
      else VBool.of(l.await(ms, TimeUnit.MILLISECONDS))
    }

    define("latchCount", 1, 1, "sync", "latchCount(l)", "How many counts remain.") { a =>
      VInt.of(handleOf[CountDownLatch](a, 0, "latch", "latchCount").getCount)
    }

    define("semaphore", 1, 1, "sync", "semaphore(permits)",
      "A counting semaphore, for limiting how many threads enter a region.") { a =>
      new VHandle("semaphore", new Semaphore(argIdx(a, 0, "semaphore")), "")
    }

    define("acquire", 1, 2, "sync", "acquire(s, timeoutMs?)",
      "Takes a permit, waiting if none is free; returns whether it got one.") { a =>
      val s = handleOf[Semaphore](a, 0, "semaphore", "acquire")
      val ms = optTimeout(a, 1, "acquire")
      if ms < 0 then { s.acquire(); VBool.True }
      else VBool.of(s.tryAcquire(ms, TimeUnit.MILLISECONDS))
    }

    define("release", 1, 1, "sync", "release(s)", "Returns a permit.") { a =>
      handleOf[Semaphore](a, 0, "semaphore", "release").release()
      VNull
    }
