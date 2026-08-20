+++
title = "One tree, two engines"
date = "2026-08-15"
tags = ["compiler"]
+++

The interpreter walks the same AST the compiler types — which is why
`sfl -c` output is held **byte-identical** to interpreted output.

| engine | starts in | fib(32) |
| --- | ---: | ---: |
| interpreter | ~3 ms | 360 ms |
| compiled | ~1 ms | 8 ms |
