# 22 — JWT authentication

The **jwt** package through a session's whole life: issue on login
(`jwtSign` with `sub`/`role`/`exp` claims), verify on each request
(`jwtVerify` — signature first, then expiry/not-before, with optional
`issuer`/`audience` pinning and `leewaySec` for clock skew), inspect with
`jwtDecode` (which never verifies — don't trust it alone), and watch
tampered and stale tokens bounce. HS256; see example 25 for the same
package guarding HTTP routes.

## Run

```bash
sfl build setup
sfl build run
sfl build test
```
