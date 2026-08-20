# 28 — MySQL

The **mysql** package (pure-SFL driver, MySQL 5.7+/8 and MariaDB) doing real
work: DDL, `?` parameter binding (strings escape, arrays become `IN` lists,
`mysqlBinary` marks blobs), the faithful type mapping (INT → int, DECIMAL →
string, BLOB → bytes, NULL → null), `transaction()` with automatic
rollback, `queryFull` column metadata, and server errors with errno and
sqlstate.

## Run

```bash
sfl build setup

# a throwaway server, if you need one:
docker run --rm -e MYSQL_ROOT_PASSWORD=secret -e MYSQL_DATABASE=sfl_demo -p 3306:3306 mysql:8

MYSQL_USER=root MYSQL_PASSWORD=secret sfl build run
sfl build test        # pure checks always; live checks when a server answers
```

Configuration: `MYSQL_HOST`/`MYSQL_PORT`/`MYSQL_USER`/`MYSQL_PASSWORD`/
`MYSQL_DB`, or one `MYSQL_URL=mysql://user:pass@host:3306/db`.

Auth notes: `caching_sha2_password` (MySQL 8's default) works over plain
TCP once the server has the account cached; the first-ever login needs
`tls: true` (or `{tls: {caFile: ...}}` for self-signed certs) — or use a
`mysql_native_password` account. The driver refuses to send cleartext
passwords over plain TCP.
