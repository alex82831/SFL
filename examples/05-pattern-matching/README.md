# 05 — Pattern matching

`select` with the whole pattern grammar: literals, `_`, bindings, `$pin`,
arrays with `...rest`, tuples, object patterns (and the `{name}` shorthand),
type patterns (`string(s)`, `float(f)`), guards (`case p if g:`), extractor
functions, `@` binders and `|` alternatives. Destructuring `val`/`for` run
the same patterns, and a `for` over a refutable pattern filters as it loops.

Also here: the **tagged record** idiom — a `"kind"` field, constructor
functions, literal dispatch, and a `default:` that raises — which is how SFL
code models a closed set of shapes (the standard library's regex engine is
written this way).

## Run

```bash
sfl build run
sfl build test
```
