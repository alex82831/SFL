# 18 — Ahead-of-time compilation

A Mandelbrot renderer whose inner loop is pure float arithmetic — the code
shape the AOT compiler proves types for and unboxes completely. The same
file runs interpreted (`sfl build run`) and compiles to a self-contained
native binary (`sfl build` → `build/mandelbrot`), with byte-identical
output.

```bash
sfl build run          # interpreted, draws the set
sfl build              # compile via LLVM + clang
./build/mandelbrot     # native, same picture
sfl build bench        # interpreted vs compiled, timed
sfl build test
```

Expect the compiled render to be tens of times faster — this is the
statically-typed best case from the README's table. (Importing this module
in the test runs the checksum pass under the interpreter, so `sfl build
test` takes a few seconds.)
