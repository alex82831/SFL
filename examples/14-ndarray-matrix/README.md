# 14 — ndarray and matrices

Rectangular data: `ndarray([dims], fill)` builds nested arrays, `a[i, j]`
sugar reads and writes through the nesting, `shape`/`isRectangular` measure
it. Worked into a matrix multiply (with an identity check) and a little
ASCII heat-diffusion simulation on a 2-D grid.

## Run

```bash
sfl build run
sfl build test
```
