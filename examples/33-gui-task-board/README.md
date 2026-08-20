# 33 — A GUI task board

A small desktop application on the **gui** package: a shared task board with
persistence, per-window filters and a background job. `run()` serves the app
on loopback and opens a native-feeling window (Chrome/Edge/Chromium app mode
where available, the default browser otherwise); the server keeps the real
widget tree and every piece of state, and the window is a remote canvas —
events up, minimal patches down, over one WebSocket.

## The idea worth taking away

Where a signal is declared decides who shares it:

```sfl
val tasks = signal([])        // src/board.sfl, module level: EVERY window
def boardUi(ctx) {
  val shown = signal("all")   // inside the builder: THIS window only
  ...
}
```

The builder runs once per window. So the task list is common to all of them —
add one in a window and the others redraw, with no broadcast, no
subscription, no message plumbing — while each window keeps its own draft
text and its own All/Open/Done filter. Open the app twice and watch.

Also shown: `computed` for derived values (the visible list, the open
count), keyed `each` so adding or removing patches only what moved, `bind:`
two-way inputs, `ctx.notify` toasts, `ctx.interval` for the clock, and
`ctx.task` — work on a background thread whose result is delivered back onto
the UI loop, so the callback can touch signals safely.

## Run

```bash
sfl build setup     # installs gui (and httpd, which it needs)
sfl build run       # opens a window
sfl build serve     # headless: serve only, then open the printed URL yourself
sfl build test      # drives the whole app over a WebSocket, no browser
```

Tasks live in `board.json` beside the project (`BOARD_FILE` moves it), so
the board survives a restart.

## Two practical notes

**Stopping.** `run()` blocks until the last window closes; `start()`/`stop()`
is the asynchronous pair the test uses. While an app is serving, `exit()`
blocks — the server's threads are still up — so stop the app first. The test
does exactly that: it drives inside `attempt()` and calls `app.stop()` on
every path.

**Testing a GUI headlessly.** `tests/board.sfl` needs no browser: it fetches
the shell page, pulls the session token out of it, speaks WebSocket by hand,
and asserts on the wire protocol — the init tree first, then patch ops. It
searches the op stream rather than demanding an exact sequence, because the
clock ticks once a second and those patches legitimately interleave. Two
windows are opened to prove the shared/session split above.
