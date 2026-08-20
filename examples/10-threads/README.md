# 10 — Threads

Real OS threads, no interpreter lock: `spawn`/`await`/`awaitAll`,
`parallelMap` with a worker count, and the shared-state tools — `mutex` +
`withLock`, `atomic` counters. The demo deliberately loses updates on an
unprotected counter to show why the tools exist, and shows a failed thread
surfacing its error at `await`.

## Run

```bash
sfl build run
sfl build test
```
