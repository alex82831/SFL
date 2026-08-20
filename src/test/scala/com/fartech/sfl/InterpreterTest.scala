package com.fartech.sfl

import org.junit.Assert.*
import org.junit.Test

/**
 * In-process tests of the compiler and evaluator.
 *
 * The broader behavioural suite lives in the `tests` directory as SFL scripts run
 * against the built binary; these cover the Scala-side API and the pieces that are
 * awkward to reach from script code.
 */
class InterpreterTest:

  private var counter = 0

  /** Evaluates a snippet under a fresh name so tests do not collide in the globals. */
  private def eval(src: String): Value =
    counter += 1
    Sfl.run(src, s"<test-$counter>")

  private def evalStr(src: String): String = eval(src).repr

  // JUnit 4 has no `intercept`, and these three exception types cover every case here.

  private def parseErrorFrom(body: => Any): ParseError =
    try
      body
      throw new AssertionError("expected a ParseError, but nothing was thrown")
    catch
      case e: ParseError => e

  private def evalErrorFrom(body: => Any): EvalError =
    try
      body
      throw new AssertionError("expected an EvalError, but nothing was thrown")
    catch
      case e: EvalError => e

  private def incompleteFrom(body: => Any): IncompleteInput =
    try
      body
      throw new AssertionError("expected an IncompleteInput, but nothing was thrown")
    catch
      case e: IncompleteInput => e

  @Test def literalsAndArithmetic(): Unit =
    assertEquals("14", evalStr("2 + 3 * 4"))
    assertEquals("3", evalStr("7 / 2"))
    assertEquals("3.5", evalStr("7.0 / 2"))
    assertEquals("255", evalStr("0xff"))
    assertEquals("\"ab\"", evalStr("\"a\" + \"b\""))

  @Test def comparisonUsesNumericEquality(): Unit =
    assertEquals("true", evalStr("1 == 1.0"))
    assertEquals("false", evalStr("1 == \"1\""))
    assertEquals("true", evalStr("[1, [2]] == [1, [2]]"))

  @Test def logicalOperatorsShortCircuit(): Unit =
    // The right-hand side would raise if it were evaluated.
    assertEquals("false", evalStr("false && (1 / 0 > 0)"))
    assertEquals("true", evalStr("true || (1 / 0 > 0)"))

  @Test def conditionalBranchesKeepTheirOrder(): Unit =
    val src =
      """def pick(n) {
        |  if (n == 1) then "a"
        |  elsif (n == 2) then "b"
        |  elsif (n == 3) then "c"
        |  elsif (n == 4) then "d"
        |  elsif (n == 5) then "e"
        |  elsif (n == 6) then "f"
        |  else "z"
        |}
        |pick(1) + pick(4) + pick(6) + pick(9)""".stripMargin
    assertEquals("\"adfz\"", evalStr(src))

  @Test def returnEscapesNestedBlocks(): Unit =
    val src =
      """def f(n) {
        |  if (n > 0) {
        |    if (n > 10) { return "big" }
        |    return "small"
        |  }
        |  "none"
        |}
        |f(50) + "/" + f(1) + "/" + f(-1)""".stripMargin
    assertEquals("\"big/small/none\"", evalStr(src))

  @Test def callsDoNotClobberOuterBindings(): Unit =
    val src =
      """val name = "outer"
        |def shadow(name) { name = "inner"; name }
        |shadow("arg") + "/" + name""".stripMargin
    assertEquals("\"inner/outer\"", evalStr(src))

  @Test def recursionKeepsPerCallState(): Unit =
    assertEquals("5050", evalStr("def s(n) = if (n <= 0) then 0 else n + s(n - 1)\ns(100)"))

  @Test def closuresCaptureIndependentState(): Unit =
    val src =
      """def box() { var n = 0; { () -> n = n + 1; n } }
        |val a = box()
        |val b = box()
        |a(); a()
        |toString(a()) + "/" + toString(b())""".stripMargin
    assertEquals("\"3/1\"", evalStr(src))

  @Test def objectsPreserveInsertionOrder(): Unit =
    assertEquals(
      "[\"z\", \"a\", \"m\", \"b\", \"y\", \"c\"]",
      evalStr("keys({\"z\":1,\"a\":2,\"m\":3,\"b\":4,\"y\":5,\"c\":6})")
    )

  @Test def constantsAreFolded(): Unit =
    val (block, _) = Sfl.compile("2 * 3 + 4", "<fold>")
    assertEquals(1, block.stmts.length)
    assertTrue(s"expected a folded literal, got ${block.stmts(0)}", block.stmts(0).isInstanceOf[Lit])
    assertEquals("10", block.stmts(0).asInstanceOf[Lit].v.repr)

  @Test def localsResolveToFrameSlots(): Unit =
    val (block, _) = Sfl.compile("def f(a, b) { a + b }", "<slots>")
    val dump = Dump.render(block)
    assertTrue(s"expected slot addressing in:\n$dump", dump.contains("LocalGet a@slot0"))
    assertTrue(s"expected slot addressing in:\n$dump", dump.contains("LocalGet b@slot1"))

  @Test def parseErrorsCarryAPosition(): Unit =
    val e = parseErrorFrom(Sfl.compile("val a = 1\nval b = *", "<pos>"))
    assertEquals(2, e.pos.line)
    assertTrue(e.render(color = false).contains("<pos>:2"))

  @Test def runtimeErrorsCarryAPosition(): Unit =
    val e = evalErrorFrom(eval("val a = 1\nval b = 2\n1 / 0"))
    assertEquals(3, e.pos.line)
    assertTrue(e.getMessage.contains("division by zero"))

  @Test def unknownNamesSuggestAnAlternative(): Unit =
    val e = evalErrorFrom(eval("prinln(1)"))
    assertTrue(e.getMessage.contains("undefined variable 'prinln'"))
    // The suggestion lives in the help notes, which is where the renderer shows it.
    assertTrue(s"no suggestion in ${e.notes}", e.notes.exists(_.contains("println")))
    assertTrue(e.render(color = false).contains("help:"))

  @Test def errorsCarryActionableHelp(): Unit =
    val missingKey = evalErrorFrom(eval("val o = {\"name\": 1}\no[\"nmae\"]"))
    assertTrue(missingKey.notes.exists(_.contains("did you mean 'name'")))
    // The caret should sit on the index, not at the start of the statement.
    assertEquals(2, missingKey.pos.line)
    assertTrue(s"column was ${missingKey.pos.col}", missingKey.pos.col > 1)

    val arity = evalErrorFrom(eval("def two(a, b) = a\ntwo(1)"))
    assertTrue(arity.notes.exists(_.contains("two(a, b)")))

    val badOperand = evalErrorFrom(eval("true + 1"))
    assertTrue(s"values missing from: ${badOperand.getMessage}", badOperand.getMessage.contains("true + 1"))

  @Test def unterminatedInputNamesTheOpeningLine(): Unit =
    val e = incompleteFrom(Sfl.compile("def f() {\n  1", "<open>"))
    assertTrue(e.reason.contains("opened at line 1"))
    assertEquals(1, e.openedAt.line)
    // Converted for batch reporting, it renders with a position.
    val rendered = Err.fromIncomplete(e, SourceRef("<open>", "def f() {\n  1")).render(color = false)
    assertTrue(rendered.contains("<open>:1"))

  @Test def tracebackIsCapturedWhileTheStackExists(): Unit =
    val e = evalErrorFrom(eval(
      "def a3(x) { x[9] }\ndef a2(x) { val v = a3(x); v }\ndef a1() { val v = a2([1]); v }\na1()"))
    val names = e.trace.map(_.fn)
    assertTrue(s"expected three frames, got $names", names.length >= 3)
    assertEquals("a3", names.head)
    assertTrue(names.contains("a2"))
    assertTrue(names.contains("a1"))

  @Test def deepNonTailRecursionIsReportedNotCrashed(): Unit =
    // Non-tail: the pending addition keeps each activation alive.
    val e = evalErrorFrom(eval("def f(n) = 1 + f(n + 1)\nf(0)"))
    assertTrue(e.getMessage.contains("stack overflow"))

  @Test def tailCallsRunInConstantStack(): Unit =
    // Far beyond the call-depth limit, which a non-tail version would trip.
    assertEquals("500000500000",
      evalStr("def go(n, acc) = if (n == 0) then acc else go(n - 1, acc + n)\ngo(1000000, 0)"))

  @Test def mutualTailRecursion(): Unit =
    assertEquals("true",
      evalStr("def ev(n) = if (n == 0) then true else od(n - 1)\n" +
              "def od(n) = if (n == 0) then false else ev(n - 1)\nev(200000)"))

  @Test def closuresCapturePerIteration(): Unit =
    val src =
      """def make() {
        |  val out = []
        |  for (i in range(3)) { push(out, () -> i) }
        |  out
        |}
        |val fs = make()
        |[fs[0](), fs[1](), fs[2]()]""".stripMargin
    assertEquals("[0, 1, 2]", evalStr(src))

  @Test def restParametersCollectSurplusArguments(): Unit =
    assertEquals("[1, [2, 3]]", evalStr("def f(a, ...rest) = [a, rest]\nf(1, 2, 3)"))
    assertEquals("[1, []]", evalStr("def g(a, ...rest) = [a, rest]\ng(1)"))

  @Test def pipelineOperator(): Unit =
    assertEquals("\"AB\"", evalStr("\"ab\" |> toUpper"))
    assertEquals("6", evalStr("[1, 2, 3] |> sum"))
    assertEquals("\"a-b\"", evalStr("[\"a\", \"b\"] |> join(\"-\")"))

  @Test def incompleteInputIsDistinguishable(): Unit =
    incompleteFrom(Sfl.compile("def f() {", "<incomplete>"))
    assertTrue(Lexer.isIncomplete("if (x) {"))
    assertFalse(Lexer.isIncomplete("1 + 1"))

  @Test def lexerHandlesEscapesAndRadixes(): Unit =
    assertEquals("\"a\\tb\"", evalStr("\"a\\tb\""))
    assertEquals("493", evalStr("0o755"))
    assertEquals("1000000", evalStr("1_000_000"))
    assertEquals("1000.0", evalStr("1e3"))

  @Test def jsonRoundTrips(): Unit =
    val v = Json.parse("""{"a":[1,2.5,"x",true,null],"b":{"c":1}}""")
    assertEquals("""{"a": [1, 2.5, "x", true, null], "b": {"c": 1}}""", v.repr)
    assertEquals(v.repr, Json.parse(Json.stringify(v, pretty = true)).repr)

  @Test def valuesCompareAndFormat(): Unit =
    assertTrue(Values.equal(VInt.of(1), VFloat(1.0)))
    assertFalse(Values.equal(VStr("1"), VInt.of(1)))
    assertEquals(0, Values.compare(VStr("a"), VStr("a")))
    assertEquals("\"a\\nb\"", VStr("a\nb").repr)
    assertEquals("a\nb", VStr("a\nb").display)

  @Test def smallIntegersAreCached(): Unit =
    assertSame(VInt.of(7), VInt.of(7))
    assertSame(VBool.of(true), VBool.True)

  @Test def displayWidthCountsWideCharacters(): Unit =
    assertEquals(4, Terminal.displayWidth("中文"))
    assertEquals(3, Terminal.displayWidth("abc"))
    assertEquals(3, Terminal.displayWidth(s"${Ansi.red}abc${Ansi.reset}"))

  @Test def everyBuiltinIsDocumented(): Unit =
    for b <- Builtins.all do
      assertTrue(s"${b.name} has no signature", b.signature.nonEmpty)
      assertTrue(s"${b.name} has no documentation", b.doc.nonEmpty)
      assertTrue(s"${b.name} has no group", b.group.nonEmpty)
      assertTrue(s"${b.name}: signature should start with the name", b.signature.startsWith(b.name))

  @Test def builtinsAreReachableAsValues(): Unit =
    assertEquals("[\"A\", \"B\"]", evalStr("map([\"a\", \"b\"], toUpper)"))

  // ---------------------------------------------------------------- concurrency

  @Test def channelIsFirstInFirstOut(): Unit =
    val ch = new Chan(0)
    ch.send(VInt.of(1))
    ch.send(VInt.of(2))
    assertEquals(2, ch.size)
    assertEquals("1", ch.receive(-1).repr)
    assertEquals("2", ch.receive(-1).repr)
    assertNull(ch.tryReceive())

  @Test def channelReceiveTimesOutToNull(): Unit =
    val ch = new Chan(0)
    val start = System.nanoTime()
    assertNull(ch.receive(30))
    // It really waited rather than returning at once.
    assertTrue(System.nanoTime() - start >= 20_000_000L)

  @Test def closingAChannelWakesReceiversButKeepsQueuedValues(): Unit =
    val ch = new Chan(0)
    ch.send(VStr("queued"))
    ch.close()
    assertTrue(ch.isClosed)
    assertEquals("\"queued\"", ch.receive(-1).repr)
    assertNull(ch.receive(-1))
    val e = evalErrorFrom(ch.send(VInt.of(1)))
    assertTrue(e.getMessage.contains("closed"))

  @Test def closingAChannelUnblocksAWaitingThread(): Unit =
    val ch = new Chan(0)
    @volatile var got: Value = VStr("not set")
    val t = new Thread(() => got = { val v = ch.receive(-1); if v == null then VNull else v })
    t.start()
    Thread.sleep(50)
    assertTrue("the receiver should still be blocked", t.isAlive)
    ch.close()
    t.join(3000)
    assertFalse("closing should have woken the receiver", t.isAlive)
    assertSame(VNull, got)

  @Test def boundedChannelBlocksTheSenderUntilThereIsRoom(): Unit =
    val ch = new Chan(1)
    ch.send(VInt.of(1))
    val t = new Thread(() => ch.send(VInt.of(2)))
    t.start()
    Thread.sleep(50)
    assertTrue("the sender should be blocked on a full channel", t.isAlive)
    assertEquals("1", ch.receive(-1).repr)
    t.join(3000)
    assertFalse(t.isAlive)
    assertEquals("2", ch.receive(-1).repr)

  @Test def globalsSurviveConcurrentDeclarationAndWrites(): Unit =
    // Declaring globals grows the table while other threads write to it; the chunked
    // layout means an in-flight write can never be lost to a reallocation.
    val g = new Globals
    val slot = g.define("shared", VInt.Zero)
    val writer = new Thread(() =>
      var i = 0
      while i < 20000 do
        g.set(slot, VInt.of(i.toLong))
        i += 1
    )
    writer.start()
    var i = 0
    while i < 3000 do
      g.id(s"name$i")
      i += 1
    writer.join()
    assertEquals("19999", g.rawGet(slot).repr)
    assertEquals(3000, g.find("name2999"))

  @Test def eachThreadGetsItsOwnInterpreter(): Unit =
    // Test order is not fixed, so make sure this thread is attached before asking.
    Sfl.attachThread()
    val main = Builtins.rt
    @volatile var other: Interp = null
    val t = new Thread(() => other = Sfl.attachThread())
    t.start()
    t.join(3000)
    assertNotNull(other)
    assertNotSame("threads must not share control-flow state", main, other)
    assertSame("but they do share globals", main.globals, other.globals)

  @Test def threadsRunSflCodeIndependently(): Unit =
    // Deep recursion on several threads at once: shared control-flow state would
    // corrupt the results or the call-depth accounting.
    assertEquals(
      "[500500, 500500, 500500, 500500]",
      evalStr(
        "def s(n, acc) = if (n == 0) then acc else s(n - 1, acc + n)\n" +
        "awaitAll(map(range(4), (i) -> spawn(s, 1000, 0)))"
      )
    )

  @Test def threadErrorsSurfaceOnAwait(): Unit =
    val e = evalErrorFrom(eval("await(spawn(() -> error(\"inside\")))"))
    assertTrue(e.getMessage.contains("inside"))
