# 26 — WebSocket chat

A chat room on the **httpd** package's WebSocket support: the `WS` route's
`open`/`message`/`close` callbacks, a shared member table (one thread per
connection, so a mutex guards it), and broadcast that survives a dying
socket. `public/index.html` is a complete browser client.

The test is the fun part: it speaks RFC 6455 by hand — handshake, masked
client frames — over plain sockets, and drives **two** clients through a
join/chat/leave conversation.

## Run

```bash
sfl build setup
sfl build run          # http://127.0.0.1:8080 — open two tabs
sfl build test
```
