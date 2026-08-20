# 23 — HTTP client

The builtin HTTP client — `httpGet` (one-liner), `httpPost` (body +
content type), `httpRequest` (any method, custom headers, and
`{status, body}` back) — exercised against a local **httpd** server
the same program starts, so everything here runs offline. The identical
calls reach the internet unchanged; `https://` goes through the system's
OpenSSL.

## Run

```bash
sfl build setup
sfl build run
sfl build test
```
