# 29 — PostgreSQL

The **postgres** package (pure-SFL driver, protocol 3.0, SCRAM/MD5 auth)
doing real work: `$1..$n` parameters go to the server out of band (nothing
is ever spliced into SQL), `RETURNING`, array parameters through
`postgres.array` + `ANY($1::int[])`, the faithful type mapping (NUMERIC → string,
BYTEA → bytes), `exec` for multi-statement scripts, `transaction()` with
rollback, error messages with SQLSTATE and position, and LISTEN/NOTIFY
between two connections.

## Run

```bash
sfl build setup

# a throwaway server, if you need one:
docker run --rm -e POSTGRES_PASSWORD=secret -p 5432:5432 postgres:16

PG_PASSWORD=secret sfl build run
sfl build test        # pure checks always; live checks when a server answers
```

Configuration: `PG_HOST`/`PG_PORT`/`PG_USER`/`PG_PASSWORD`/`PG_DB`, or one
`PG_URL=postgres://user:pass@host:5432/db`. TLS: `{tls: true}` or
`{tls: {caFile: "..."}}`; cleartext auth is refused over plain TCP.
