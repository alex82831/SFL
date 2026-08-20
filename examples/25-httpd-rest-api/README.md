# 25 — REST API with JWT auth

Four packages working as one service: **httpd** routes and middleware
(`use([logging, authRequired], routes([...]))`), **jwt** for login tokens
verified on every `/api/notes` route (claims land on `req.user`), **uuid**
v7 ids (time-ordered, so the listing sorts naturally), and **log** for
request lines. Full CRUD with proper status codes: 201/204/401/404/422.

## Run

```bash
sfl build setup
sfl build run     # then follow the curl lines printed in src/main.sfl
sfl build test    # the whole API driven in-process
```
