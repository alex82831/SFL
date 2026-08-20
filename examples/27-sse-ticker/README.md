# 27 — Server-sent events

A live ticker over SSE: `respondEvents` hands the route a `{send, comment}`
writer and blocks for the life of the subscription — every connection has
its own thread, so blocking is the natural shape. The page (rendered with
the **template** package) subscribes with a plain `EventSource`; `curl -N`
shows the raw `event:`/`id:`/`data:` wire format.

## Run

```bash
sfl build setup
sfl build run                    # http://127.0.0.1:8080
curl -N localhost:8080/events    # the raw stream
sfl build test
```
