# 30 — Redis

The **redis** package in its natural habitats: cache-aside with TTLs
(`get`/`setEx`), a fixed-window rate limiter (`incr` + `expire`), the data
structures (sets, sorted sets, hashes, lists), `pipeline` for one-round-trip
batches, and pub/sub with a subscriber on its own connection and thread,
handing messages back through a channel.

## Run

```bash
sfl build setup
docker run --rm -p 6379:6379 redis:7    # or: redis-server
sfl build run
sfl build test    # live checks when a server answers, quiet skip otherwise
```

`REDIS_HOST` / `REDIS_PORT` point it elsewhere.
