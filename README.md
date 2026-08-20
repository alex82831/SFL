# SFL — Scripting for Fun

A small dynamic scripting language written in Scala 3 and compiled to a standalone
native executable with Scala Native. No VM, no warm-up: the binary starts and finishes
a typical script in a few milliseconds.

```sfl
def fizzbuzz(n) {
  for (i in range(1, n + 1)) {
    println(
      if (i % 15 == 0) then "FizzBuzz"
      elsif (i % 3 == 0) then "Fizz"
      elsif (i % 5 == 0) then "Buzz"
      else i
    )
  }
}

fizzbuzz(20)
```

Designed and implemented by Alex Xin. Released under the
[BSD 3-Clause license](LICENSE).

---

## Documentation

- **[使用手册](docs/SFL-使用手册.md)** — the full user manual: syntax, semantics,
  functional programming, concurrency and IPC, ahead-of-time compilation, and every one
  of the 363 builtins.
- **[软件包参考](docs/SFL-软件包参考.md)** — the standard package suite: a
  cross-platform GUI framework, an HTTP server framework, MySQL, PostgreSQL,
  MongoDB and Redis drivers, datetime, csv, toml, markdown, mustache templates,
  jwt, smtp, uuid, cli, log, ansi and dotenv, with how to install and use them
  in both source and prebuilt-binary form.
- **[examples/](examples/)** — 33 self-contained example projects, one per feature
  or package, each openable directly in VSCode or IntelliJ IDEA.
- **[项目与构建指南](docs/SFL-项目与构建指南.md)** — projects end to end: creating
  one, the `build.sfl` reference, incremental builds, custom tasks, testing,
  deployment and distribution, IDE integration.
- **[编译器手册](docs/SFL-编译器手册.md)** — the complete compiler reference: CLI, type
  inference, the value runtime and its collector, the standard library and selective
  linking, interpreter parity, every diagnostic, and internals.
- **[审计报告](docs/审计报告.md)** — audit of the previous release and what changed.
- **[面向对象支持评估](docs/评估-面向对象支持.md)** — why the language does not have
  classes, measured against the code that is actually written in it.
- **[语法糖审查](docs/语法糖审查.md)** — every piece of sugar the language has,
  the candidates that were rejected and why, and the rules behind the new ones.

## Install

macOS and Linux have prebuilt binaries on the [releases page](https://github.com/alex82831/SFL/releases).
Install or update with one line:

```bash
curl -fsSL https://raw.githubusercontent.com/alex82831/SFL/main/install.sh | sh
```

It downloads the binary for your platform, verifies its checksum, and puts `sfl`
on your PATH; run it again, or `sfl --version` against a newer release, to update.
Then `sfl install mongodb` and the rest of the [package suite](docs/SFL-软件包参考.md)
are a command away. To build from source instead, see below.

Releases are produced by pushing a version tag (`git tag v0.6.0 && git push --tags`),
which the [release workflow](.github/workflows/release.yml) turns into built
binaries and a published release — no credentials beyond the repository's own.

## Build

Requires a JDK, sbt 1.12+, and a Clang toolchain. On macOS, installing Xcode or the
Command Line Tools is enough — the build locates the matching clang through `xcrun`.

```bash
sbt nativeLink
cp target/scala-3.8.4/sfl /usr/local/bin/
```

Build knobs, all optional:

| Variable | Values | Default |
| --- | --- | --- |
| `SFL_BUILD_MODE` | `debug`, `releaseFast`, `releaseFull` | `releaseFast` |
| `SFL_GC` | `none`, `boehm`, `immix`, `commix` | `immix` |
| `SFL_LTO` | `none`, `thin`, `full` | `thin` |

## Run

```bash
sfl                       # interactive shell
sfl script.sfl arg1 arg2  # run a script
sfl -e 'println(6 * 7)'   # evaluate an expression
sfl -c script.sfl         # compile to a native executable via LLVM
sfl --help                # every option
```

## Compile

The whole language compiles ahead of time. The compiler infers types over the same AST
the interpreter runs, emits LLVM IR, and hands it to clang along with two archives: a C
runtime, and the part of the builtin library that is written in SFL and compiled by this
same compiler. Only `eval` and `parse` are out of reach, because they would need the
interpreter itself.

```bash
sfl -c hot.sfl -o hot     # native executable
sfl --emit-llvm hot.sfl   # inspect the IR
sfl --build-runtime       # build the archives now rather than on first compile
```

Where inference proves a machine type the code is unboxed and calls nothing; everywhere
else it runs against the runtime. Measured against the interpreter, with both outputs
required to match byte for byte:

| program | interpreted | compiled | speedup |
| --- | --- | --- | --- |
| `fib(32)` | 0.360s | 0.008s | 45× |
| 20M-iteration loop | 0.874s | 0.012s | 73× |
| 10M float iterations | 0.921s | 0.039s | 24× |
| Collatz search to 300k | 2.435s | 0.033s | 74× |
| 200k strings built and counted | 0.134s | 0.057s | 2.4× |
| 1M closure calls | 0.074s | 0.022s | 3.4× |
| 120k objects grouped and summed | 0.107s | 0.048s | 2.2× |

The first four are statically typed; the last three are not, and pay for boxing.

## Projects, editors and tooling

The binary carries its own project tooling — a build tool written in SFL itself,
a language server, and the commands editors integrate against:

```bash
sfl build init            # scaffold a project: build.sfl, src/, tests/
sfl build                 # compile main to build/<name>, skipping when fresh
sfl build run             # run through the interpreter
sfl build test            # run every .sfl under tests/
sfl build describe        # the project model as JSON, for IDEs
sfl check [--json] *.sfl  # parse and report problems without running
sfl syntax                # keywords, operators and builtin metadata as JSON
sfl lsp                   # the Language Server Protocol over stdio
sfl dap                   # the Debug Adapter Protocol: breakpoints, stepping,
                          # stacks and variables for interpreted programs
```

The project file, `build.sfl`, is ordinary SFL: it calls `project({...})` and
optionally `task(name, fn, doc)` — functions the build tool defines before
evaluating it. Package dependencies stay in `sfl.pkg`, where `import` already
reads them. The full story — every field, custom tasks, incremental builds,
deployment and distribution — is in
[docs/SFL-项目与构建指南.md](docs/SFL-项目与构建指南.md).

Packages install with `sfl pkg install`, and `import "name"` finds them. A
package can also ship prebuilt: `sfl pkg build --bin` compiles it for the current
platform into an archive that travels beside the source, so a program that links
it with `sfl -c` skips recompiling the package — while the interpreter still runs
the source, and a stale or absent archive falls back to it, byte-for-byte the
same either way. The [standard package suite](docs/SFL-软件包参考.md) under
`packages/` — a cross-platform GUI framework, the httpd server framework,
database drivers for MySQL, PostgreSQL, MongoDB and Redis, datetime, csv, toml,
markdown, template, jwt, smtp, uuid, cli, log, ansi and dotenv — installs the
same way.

Editor support lives in `editors/`: a VSCode extension and an IntelliJ plugin
(IDEA Community works — the LSP client is LSP4IJ). Both get their keyword and
builtin tables generated from `sfl syntax` by `tools/gen-editor-syntax.sfl`,
and both talk to the same `sfl lsp` for diagnostics, completion, hover and
signature help.

## Test

```bash
sbt test               # JUnit tests over the compiler and evaluator
./tests/run.sh         # language, builtin, command-line and compiler behaviour
./tests/differential.sh # the suites above, compiled, output required to be identical
./bench/run.sh         # interpreter benchmarks
./bench/compile.sh     # interpreter vs compiler
```

The differential run is the strongest check the compiler has: it compiles the language's
own test suites — several hundred assertions each — and requires byte-identical output,
rather than relying on cases written to test the compiler specifically.

## What is in here

| Path | Contents |
| --- | --- |
| `src/main/scala/com/fartech/sfl/` | Lexer, parser, evaluator, builtins, REPL, compiler, LSP server |
| `runtime/` | The C runtime compiled programs link against: values, collector, primitives |
| `stdlib/` | The part of the builtin library written in SFL and precompiled |
| `packages/` | The standard package suite (gui, httpd, mysql, postgres, mongodb, redis, datetime, csv, toml, markdown, template, jwt, smtp, uuid, cli, log, ansi, dotenv), installable with `sfl pkg` |
| `examples/` | 33 standalone example projects, each an IDE-openable `sfl build` project with tests |
| `buildtool/` | The `sfl build` build tool, written in SFL and embedded in the binary |
| `editors/vscode/` | The VSCode extension: TextMate grammar, LSP client, run commands |
| `editors/intellij/` | The IntelliJ plugin (Community-compatible, LSP via LSP4IJ) |
| `src/test/scala/` | JUnit tests |
| `tests/` | Behavioural suites written in SFL, plus a command-line test runner |
| `bench/` | Benchmark scripts and comparison harnesses |
| `docs/` | User manual, audit report, and the builtin description data |
| `tools/` | Manual and reference generators, themselves written in SFL |
| `legacy/v0.0.1/` | The previous implementation, kept for reference |

### Implementation notes

The interpreter is a tree walker over an AST whose nodes carry their own `eval` method.
Names are resolved while parsing: a local becomes an index into a frame's `Array[Value]`
and a global becomes a small integer, so nothing is looked up by name at run time.
`return`, `break` and `continue` are signal flags checked between statements, which lets
them cross any nesting depth. Literal arithmetic is constant-folded during parsing.

Closures capture frames, and a loop whose body builds a function gets a fresh frame per
iteration — the parser detects that case and re-parses the body one scope deeper, so
ordinary loops keep the flat, allocation-free layout. Calls in tail position park their
target on the interpreter instead of recursing, and the enclosing activation loops, which
makes tail recursion unbounded at no cost to normal calls.

Threads are real OS threads with no interpreter lock. Control-flow state lives in a
per-thread interpreter while globals and the builtin table are shared, and the globals
table is chunked so growing it can never lose a write from another thread. Channels are
built on a lock and two conditions rather than a `BlockingQueue`, because closing has to
wake everyone currently blocked.

The REPL drives the terminal through POSIX termios directly — Scala Native has no
line-editing library — which is what provides history, completion, live highlighting and
correct cursor placement for wide characters.

The compiler is gradually typed: inference gives every expression a machine type where it
can and a boxed runtime value where it cannot, so a numeric loop compiles to bare i64
arithmetic while the closure three lines below it compiles to a heap frame and an indirect
call. Nothing is rejected for being too dynamic. Compiled programs link a mark-sweep
collector that traces the object graph precisely and finds roots by scanning the machine
stack, which is what lets generated code stay ordinary code; with threads it stops the
world by signal to reach the other stacks. The program's own source text is linked in, so
a compiled binary quotes and underlines the failing line exactly as the interpreter does.

## Version history

**0.7.0** — The network release: the HTTP builtins no longer need libcurl. `httpGet`
and its siblings are now `stdlib/http.sfl` — one HTTP/1.1 client shared by both
engines — over a socket layer built from C primitives, with TLS reached by `dlopen`
of the system OpenSSL/LibreSSL (`tlsWrap`, certificate and hostname verification,
ALPN); plain HTTP needs no external library at all. New primitives fill the layer
out: byte buffers for byte-counted framing (`bufNew`/`socketReadToBuf`/`bufString`),
raw byte socket I/O, `utf8Length`, a full synchronous **and** asynchronous UDP
surface (`udpSocket`/`udpSend`/`udpReceive`, poll-able), server-side TLS with ALPN
(`tlsAccept`/`tlsProto`), `socketNoDelay` and zero-copy `socketSendFile`. On top of
these ships **`packages/httpd`**, an HTTP server framework: HTTP/1.1 (keep-alive,
chunked both ways, 100-continue, conditional and range static files), HTTP/2 (h2c
and ALPN, multiplexed streams, HPACK, flow control), WebSocket, and server-sent
events, with a synchronous `listen()` and an asynchronous `start()`/`stop()` over
one thread-per-connection model. `sfl` no longer links libcurl or sttp.

**0.5.1** — Modules get real namespaces: a top-level name beginning with `_` belongs to
the file that declares it, and `import "m" as x` puts a module's surface behind `x`
instead of in the global scope, resolved at parse time so it costs nothing at run time.
The standard library now carries 130 private names — `_bad` and `_show` each live in four
files — where it used to hand-write a prefix per module. Also: `jsonStringify` refuses
values with no JSON form instead of emitting text its own parser rejects, and the manual
now says that `copy`/`deepCopy` cannot copy state a closure captured.

**0.6.0** — The sugar release: string interpolation
`"${expr}"` (single quotes stay literal, shell-style; `r"..."` raw strings),
absence-safe access `?.` with local one-hop semantics, null coalescing `??`,
paren-free single-parameter lambdas `x -> e`, and `#!` script execution. Pattern matching over the whole value model (literals, arrays with
`...rest` anywhere, objects, tuples, type patterns, user extractors, guards,
alternatives, `@` binders, `$name` pinning), destructuring `val`/`var`/`for` with
Scala-style filtering loops; tuples as a real immutable kind; multidimensional
array sugar `a[i, j]` with `ndarray`/`shape`; versioned packages (`sfl.pkg`
manifests, `sfl pkg build/install/list/remove`, semver ranges, flat resolution);
async I/O (`poll` readiness, `awaitAny`, an async stdlib module). Patterns desugar
in the parser, so both engines run identical trees — the whole feature set is held
byte-identical by the differential suite.

**0.5.0** — An LLVM ahead-of-time compiler for the whole language (`sfl -c`): strings,
arrays, objects, closures, higher-order functions, errors, iterators, threads and IPC all
compile, and only `eval`/`parse` do not. Statically typed code reaches 23–74× over the
interpreter and dynamic code 2–3×, with output verified byte-identical by compiling the
language's own test suites. Compiled programs link a C runtime with a mark-sweep
collector and 238 primitives, plus 81 builtins written in SFL itself and precompiled into
an archive that is linked selectively;
plus much richer diagnostics — suggestions for misspelled names and keys, signatures on
arity and argument errors, carets on the exact subexpression, tracebacks captured while
the stack still exists, unclosed brackets reporting where they opened, and `attempt()`
returning errors as data. The REPL gains `:doc`, `:threads`, `:trace`, `:bench`,
continuation on trailing operators, and completion that knows about object fields.

**0.4.0** — Threads and IPC: real OS threads (`spawn`, `await`, `parallelMap`) with a
thread-safe interpreter, channels with proper close semantics, mutexes, atomics, latches
and semaphores; and for talking to other processes, subprocess pipes, TCP sockets,
line-oriented file handles, named pipes and signals. 73 new builtins.

**0.3.0** — Complete closures and a functional layer: per-iteration capture in loops,
proper tail calls (unbounded tail recursion), rest parameters, the `|>` pipeline
operator, and 42 new builtins covering combinators (`compose`, `pipe`, `curry`,
`partial`, `memoize`, …), collection transforms (`flatMap`, `groupBy`, `scan`,
`foldRight`, …), copy-on-write updates (`assoc`, `assocIn`, `updateIn`, `getIn`), and
lazy infinite sequences (`naturals`, `iterate`, `cycle`, lazy `map`/`filter`/`take`).

**0.2.0** — Rewritten core: hand-written lexer and parser replacing
scala-parser-combinators, compile-time name resolution, closures, `for`/`break`/
`continue`/`!=`/unary operators, error messages with source positions and tracebacks,
205 builtins (up from 48), a full-featured REPL, and a build that actually links.
Roughly 11× to 2800× faster depending on the workload. See the
[audit report](docs/审计报告.md).

**0.0.1-Alpha** — Initial release (2022).
