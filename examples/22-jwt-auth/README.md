# 22 — JWT authentication

The **jwt** package through a session's whole life: issue on login
(`jwt.sign` with `sub`/`role`/`exp` claims), verify on each request
(`jwt.verify` — signature first, then expiry/not-before, with optional
`issuer`/`audience` pinning and `leewaySec` for clock skew), inspect with
`jwt.decode` (which never verifies — don't trust it alone), and watch
tampered and stale tokens bounce. HS256; see example 25 for the same
package guarding HTTP routes.

## Run

```bash
sfl build setup
sfl build run
sfl build test
```
