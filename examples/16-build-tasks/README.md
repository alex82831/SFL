# 16 — Build tasks

`build.sfl` is ordinary SFL — `task(name, fn, doc)` registers custom tasks
beside the built-in `build`/`run`/`test`/`clean`, and `sfl build tasks`
lists them all with their docs. This project's tasks show the useful
patterns:

- **`gen`** — code generation: bake `data/words.txt` into
  `src/generated.sfl`; the incremental build picks up the change.
- **`loc`** — a read-only report over the source tree.
- **`check`** — chaining the binary's own commands with `exePath()` +
  `passthrough()` (live output, exit codes propagate).
- **`shout`** — tasks receive their arguments: `sfl build shout hello`.

## Run

```bash
sfl build tasks
sfl build gen && sfl build run
sfl build loc
sfl build check
sfl build shout it works
```
