# 13 — Async I/O

"Async" in SFL is not a second colour of function — it is threads plus
readiness: `async`/`await` futures, `awaitAny` and `race` for whichever
finishes first, `withTimeout` for deadlines, and the poll-able UDP surface
(`udpSocket`/`udpSend`/`udpReceive`, `poll` over many handles at once).

## Run

```bash
sfl build run
sfl build test
```
