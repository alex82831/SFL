# 11 — Channels

Producer/worker-pool/collector over channels: bounded buffers
(`channel(cap)` makes `send` block when full), `closeChannel` waking every
waiter, `receive` returning `null` once closed **and** drained (the natural
worker loop exit), `tryReceive`/`channelDrain` for polling, and
`channelToArray` to gather a stream. Ends with a little pipeline where each
stage is a thread joined to the next by a channel.

## Run

```bash
sfl build run
sfl build test
```
