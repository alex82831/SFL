# SFL examples

Thirty-three small, self-contained SFL projects, each demonstrating one part
of the language, the tooling or the standard package suite. Every directory
is a complete project — `build.sfl`, sources, tests, its own README — that
opens directly in VSCode (SFL extension) or IntelliJ IDEA (SFL plugin) and
answers to the same four commands:

```bash
cd examples/01-hello-world
sfl build run      # run through the interpreter
sfl build test     # run the example's tests
sfl build          # compile to a native executable in build/
sfl build tasks    # see the example's custom tasks, if any
```

Examples that import packages carry an `sfl.pkg` and a `setup` task:
`sfl build setup` installs their dependencies into `./sfl_packages/` — from
this repository's `packages/` when run inside a checkout, from the registry
(`sfl install <name>`) anywhere else.

A package is a namespace, so those examples call it by name — `csv.parse(text)`,
`jwt.sign(claims, secret)`, `httpd.routes([...])` — while an example's own
`src/` and `tests/` files share the project's namespace and see each other
without qualifying. The one exception is `gui`, whose widget catalogue reads
better flat: `33-gui-task-board` writes `import "gui" open` and then plain
`column([...])`. The rules are in
[the manual §5.13](../docs/SFL-使用手册.md#513-模块导入与命名空间).

## The language

| example | shows |
| --- | --- |
| [01-hello-world](01-hello-world/) | println/printf, `"${...}"` interpolation, `args()` |
| [02-fizzbuzz](02-fizzbuzz/) | `if` as an expression, `for`/`range`, `while`/`break`/`continue` |
| [03-collections](03-collections/) | arrays/objects/tuples, map/filter/reduce, groupBy, destructuring, `assoc`/`updateIn` |
| [04-functional-pipelines](04-functional-pipelines/) | `\|>`, compose/curry/partial, closures, `memoize`, unbounded tail calls |
| [05-pattern-matching](05-pattern-matching/) | `select` with the whole pattern grammar, extractors, tagged records |
| [06-lazy-sequences](06-lazy-sequences/) | `naturals`/`iterate`/`cycle`, lazy map/filter, take/toArray |
| [07-error-handling](07-error-handling/) | `error`/`try`/`attempt`, errors as data, retries, `?.`/`??` |
| [08-text-and-regex](08-text-and-regex/) | the string toolbox, regex builtins, a word-frequency report |

## Concurrency and processes

| example | shows |
| --- | --- |
| [10-threads](10-threads/) | `spawn`/`await`, `parallelMap`, mutexes, atomics |
| [11-channels](11-channels/) | producer/worker-pool/collector, close semantics, pipelines |
| [12-subprocess](12-subprocess/) | `execute`, coprocesses over pipes, kill/wait |
| [13-async-io](13-async-io/) | `async`/`awaitAny`/`race`, `poll`, the UDP surface, `withTimeout` |

## Data and formats

| example | shows |
| --- | --- |
| [09-datetime-tour](09-datetime-tour/) | the **datetime** package: ISO 8601, calendar arithmetic, offsets |
| [14-ndarray-matrix](14-ndarray-matrix/) | `ndarray`, `a[i, j]` sugar, matrix multiply, a heat grid |
| [19-csv-report](19-csv-report/) | **csv** in and out, groupBy aggregation |
| [20-toml-config](20-toml-config/) | **toml** defaults + **dotenv**/environment overrides |
| [21-markdown-site](21-markdown-site/) | a static site generator: **markdown** + **template** + **toml** front matter |
| [22-jwt-auth](22-jwt-auth/) | the **jwt** package: sign, verify, expiry, audience |

## Tooling and the build

| example | shows |
| --- | --- |
| [15-cli-todo](15-cli-todo/) | a **cli**-parsed, **ansi**-coloured todo tool with JSON persistence |
| [16-build-tasks](16-build-tasks/) | custom `task()`s: codegen, reports, chaining the binary |
| [17-lib-package](17-lib-package/) | a library project: `sfl.pkg`, private names, `sfl build pkg` |
| [18-aot-compile](18-aot-compile/) | Mandelbrot; `sfl build bench` times interpreter vs compiled |

## Network services

| example | shows |
| --- | --- |
| [23-http-client](23-http-client/) | `httpGet`/`httpPost`/`httpRequest` against a local server |
| [24-httpd-hello](24-httpd-hello/) | **httpd** routes, params, JSON, static files |
| [25-httpd-rest-api](25-httpd-rest-api/) | REST CRUD with middleware: **httpd** + **jwt** + **uuid** + **log** |
| [26-websocket-chat](26-websocket-chat/) | a WebSocket chat room, browser client included |
| [27-sse-ticker](27-sse-ticker/) | server-sent events with `respondEvents` |

## Desktop

| example | shows |
| --- | --- |
| [33-gui-task-board](33-gui-task-board/) | a **gui** desktop app: signals shared across windows vs private to one, keyed lists, background tasks — tested headlessly |

## Databases and mail

| example | shows |
| --- | --- |
| [28-mysql-demo](28-mysql-demo/) | the **mysql** driver: binding, types, transactions |
| [29-postgres-demo](29-postgres-demo/) | the **postgres** driver: `$1` params, RETURNING, LISTEN/NOTIFY |
| [30-redis-demo](30-redis-demo/) | **redis**: caching, rate limiting, pipelines, pub/sub |
| [31-mongodb-demo](31-mongodb-demo/) | **mongodb**: CRUD, cursors, aggregation, BSON wrappers |
| [32-smtp-mail](32-smtp-mail/) | **smtp**: MIME building, STARTTLS, AUTH — with a preview mode |

The database and mail examples degrade politely: without a server they
print how to start one (their tests run the pure layers and skip the live
part), and environment variables point them at whatever you have.
