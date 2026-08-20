package com.fartech.sfl

import java.util
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import scala.collection.mutable

/**
 * Runtime support for threads and inter-thread communication.
 *
 * Each SFL thread runs on its own [[Interp]] — control-flow state such as the signal
 * flag and the call stack cannot be shared — while globals and the builtin table are
 * common to all of them.
 */
object Threads:
  private val counter = new AtomicLong(0)

  /** Every thread ever started, so failures that nobody joined can be reported at exit. */
  private val all = new java.util.concurrent.ConcurrentLinkedQueue[Task]

  /** 8 MB, matching the main thread, so the call-depth limit means the same everywhere. */
  private final val StackBytes = 8L * 1024 * 1024

  final class Task(val name: String, val daemon: Boolean):
    @volatile var result: Value = VNull
    /** One-line reason, for the message a joiner sees. */
    @volatile var failure: String = null
    /** Where it happened, e.g. "worker.sfl:12", for the help line. */
    @volatile var failureWhere: String = ""
    /** The thread's own rendered error, shown when nobody awaited it. */
    @volatile var failureDetail: String = null
    @volatile var observed: Boolean = false
    /** The body has returned, one way or another; what awaitAny scans for. */
    @volatile var finished: Boolean = false
    var thread: Thread = null

  /**
   * Completion, announced once per task on one global condition. Global because a
   * waiter on N tasks cannot park on N per-task conditions at once; signalling under
   * the same lock awaitAny scans under closes the missed-wakeup window between its
   * scan and its wait.
   */
  private val completionLock = new ReentrantLock()
  private val completionCond = completionLock.newCondition()

  def start(name: String, body: () => Value, daemon: Boolean = false): Task =
    val task = new Task(if name.isEmpty then s"thread-${counter.incrementAndGet()}" else name, daemon)
    val runnable: Runnable = () =>
      Sfl.attachThread()
      try
        try task.result = body()
        catch
          case e: EvalError =>
            val located = Builtins.interp match
              case null => e
              case rt   => rt.decorate(e)
            task.failure = located.getMessage
            task.failureWhere =
              if located.hasLocation then s"${located.source.name}:${located.pos.line}" else ""
            task.failureDetail = located.render(color = false)
          case e: SflError =>
            task.failure = e.getMessage
            task.failureWhere = if e.hasLocation then s"${e.source.name}:${e.pos.line}" else ""
            task.failureDetail = e.render(color = false)
          case e: ExitSignal =>
            task.failure = s"the thread called exit(${e.code})"
          case e: InterruptedException =>
            task.failure = "interrupted"
          case e: Throwable =>
            task.failure = s"${e.getClass.getSimpleName}: ${e.getMessage}"
      finally
        // On every exit path, after the result and failure stores: a waiter woken
        // by this signal must be able to read what the thread produced.
        completionLock.lock()
        try
          task.finished = true
          completionCond.signalAll()
        finally completionLock.unlock()
    val t = new Thread(null, runnable, task.name, StackBytes)
    t.setDaemon(daemon)
    task.thread = t
    all.add(task)
    t.start()
    task

  /**
   * Waits for a task. Returns its value, or re-raises its failure on this thread.
   * `timeoutMs` of -1 waits indefinitely.
   */
  def join(task: Task, timeoutMs: Long): Value =
    if timeoutMs < 0 then task.thread.join()
    else task.thread.join(timeoutMs)
    if task.thread.isAlive then
      Err.eval(s"join: ${task.name} did not finish within ${timeoutMs}ms")
    task.observed = true
    if task.failure != null then
      // Report it as one line plus a help note, rather than nesting a whole rendered
      // error inside another error's message.
      Err.evalHint(
        s"${task.name} failed: ${task.failure}",
        if task.failureWhere.isEmpty then "" else s"it was raised at ${task.failureWhere}"
      )
    task.result

  /**
   * Index of the first task observed finished, -1 when the timeout expires first.
   * The scan and the wait happen under the same lock every completion signals
   * under, so a task finishing in between cannot be missed. Shaped like
   * [[Chan.receive]]: the deadline ends the waiting, not the attempt — one more
   * scan runs after it expires. A failed task merely counts as finished here;
   * reporting the failure stays [[join]]'s job.
   */
  def awaitAny(tasks: Array[Task], timeoutMs: Long): Int =
    completionLock.lock()
    try
      var remaining = if timeoutMs < 0 then 0L else timeoutMs * 1000000L
      var out = -1
      var expired = false
      var waiting = true
      while waiting do
        var i = 0
        while out < 0 && i < tasks.length do
          if tasks(i).finished then out = i
          i += 1
        if out >= 0 || expired then waiting = false
        else if timeoutMs < 0 then completionCond.await()
        else if remaining <= 0 then expired = true
        else remaining = completionCond.awaitNanos(remaining)
      out
    finally completionLock.unlock()

  /**
   * Prints failures from threads nobody awaited and returns how many there were, so the
   * program can exit non-zero. Without this a crashed worker would vanish silently.
   */
  def reportUnobservedFailures(): Int =
    var n = 0
    val it = all.iterator()
    while it.hasNext do
      val t = it.next()
      if t.failure != null && !t.observed then
        Console.err.println(s"${Ansi.yellow}warning: ${t.name} failed and was never awaited:${Ansi.reset}")
        Console.err.println(if t.failureDetail != null then t.failureDetail else t.failure)
        t.observed = true
        n += 1
    n

  /** Name, state and failure of every thread, for the REPL's `:threads`. */
  def snapshot: Seq[(String, String, String)] =
    val out = scala.collection.mutable.ArrayBuffer.empty[(String, String, String)]
    val it = all.iterator()
    while it.hasNext do
      val t = it.next()
      val state =
        if t.thread.isAlive then if t.daemon then "running (daemon)" else "running"
        else if t.failure != null then "failed"
        else "done"
      out += ((t.name, state, if t.failure == null then "" else t.failure))
    out.toSeq

  /**
   * Waits for every ordinary thread to finish, so a script never exits out from under
   * work it started. Daemon threads are not waited for — that is what they are for.
   * This mirrors how the JVM and Python treat non-daemon threads.
   */
  def awaitNonDaemon(): Unit =
    val it = all.iterator()
    while it.hasNext do
      val t = it.next()
      if !t.daemon && t.thread.isAlive then t.thread.join()

/**
 * A channel: a bounded or unbounded queue with proper close semantics.
 *
 * This is written against a lock and two conditions rather than wrapping a
 * `BlockingQueue`, because closing has to wake everyone currently blocked — something a
 * plain queue cannot express without either a poison value or polling.
 */
final class Chan(val capacity: Int):
  private val lock = new ReentrantLock()
  private val notEmpty = lock.newCondition()
  private val notFull = lock.newCondition()
  private val items = new util.ArrayDeque[Value]()
  private var closed = false

  def isClosed: Boolean =
    lock.lock()
    try closed
    finally lock.unlock()

  def size: Int =
    lock.lock()
    try items.size
    finally lock.unlock()

  /** Blocks while a bounded channel is full. Raises if the channel is closed. */
  def send(v: Value): Unit =
    lock.lock()
    try
      while capacity > 0 && items.size >= capacity && !closed do notFull.await()
      if closed then Err.eval("send: the channel is closed")
      items.addLast(v)
      notEmpty.signal()
    finally lock.unlock()

  /** Non-blocking. Returns null when empty. */
  def tryReceive(): Value =
    lock.lock()
    try
      if items.isEmpty then null
      else
        val v = items.pollFirst()
        notFull.signal()
        v
    finally lock.unlock()

  /**
   * Returns null when the timeout expires, or when the channel is closed and drained.
   * `timeoutMs` of -1 waits indefinitely.
   */
  def receive(timeoutMs: Long): Value =
    lock.lock()
    try
      var remaining = if timeoutMs < 0 then 0L else timeoutMs * 1000000L
      var out: Value = null
      var waiting = true
      while waiting do
        if !items.isEmpty then
          out = items.pollFirst()
          notFull.signal()
          waiting = false
        else if closed then waiting = false
        else if timeoutMs < 0 then notEmpty.await()
        else if remaining <= 0 then waiting = false
        else remaining = notEmpty.awaitNanos(remaining)
      out
    finally lock.unlock()

  /** Wakes everyone blocked on the channel; queued items can still be received. */
  def close(): Unit =
    lock.lock()
    try
      closed = true
      notEmpty.signalAll()
      notFull.signalAll()
    finally lock.unlock()

  /** Drains what is currently queued, for `channelDrain`. */
  def drain(): mutable.ArrayBuffer[Value] =
    lock.lock()
    try
      val out = new mutable.ArrayBuffer[Value](items.size)
      while !items.isEmpty do out += items.pollFirst()
      notFull.signalAll()
      out
    finally lock.unlock()
