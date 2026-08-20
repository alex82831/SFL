# 04 — Functional pipelines

The functional layer: the `|>` pipeline operator (the piped value becomes the
first argument of the call on the right), combinators (`compose`, `pipe`,
`curry`, `partial`, `flip`, `once`, `tap`), closures that carry private
state, `memoize`, and proper tail calls — `sumTo(1000000)` recurses a million
deep without a stack in sight.

## Run

```bash
sfl build run
sfl build test
```
