# 12 — Subprocesses

Talking to other programs: `execute` (capture `{code, out, err}`), `processStart` (argv form, no shell) for a coprocess spoken to over pipes
(`processWriteLine` → `processCloseInput` → `processReadLine`/
`processReadAll` → `processWait`), `processAlive`/`processKill` for
long-lived children, and environment variables crossing into children.
Uses only POSIX tools (`sort`, `grep`, `wc`).

## Run

```bash
sfl build run
sfl build test
```
