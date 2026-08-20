# 24 — httpd hello

A first HTTP server with the **httpd** package: `routes([...])` with
`:name` path parameters and `*rest` wildcards, query strings, JSON in and
out (`respondJson`), redirects, and `STATIC` file serving (content types,
conditional and range requests come free). `main` blocks in `listen()`;
the tests run the same app on an ephemeral port via `start()` and drive it
with the builtin HTTP client — no sleeps, no external tools.

## Run

```bash
sfl build setup
sfl build run                 # http://127.0.0.1:8080
curl localhost:8080/hello/ada?times=2
curl -X POST -d '[40,2]' localhost:8080/api/sum
sfl build test
```
