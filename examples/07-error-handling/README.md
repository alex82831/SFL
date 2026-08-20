# 07 — Error handling

Raising with `error()`, catching with `try(body, handler)`, and — the shape
most SFL code prefers — `attempt(body)`, which returns the outcome as data
(`{value}` or `{error: {message}}`) so failures can be filtered, collected
and routed like any other value. Plus `assert`, a retry-with-backoff helper,
null-safety (`?.`, `??`) as the small-scale complement, and the
cleanup-then-rethrow pattern.

## Run

```bash
sfl build run
sfl build test
```
