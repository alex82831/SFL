# 06 — Lazy sequences

Infinite sequences that cost nothing until asked: `naturals()`, `iterate`
(unfold from a seed), `cycle`, with lazy `map`/`filter`/`takeWhile`/
`dropWhile`, realised by `take`/`toArray`. Includes lazy fibonacci, a primes
stream, the Collatz walk, and a counter proving `map` only runs for the
elements actually taken.

## Run

```bash
sfl build run
sfl build test
```
