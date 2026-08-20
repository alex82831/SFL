# 20 — TOML config with dotenv overrides

Layered configuration: `app.toml` carries the checked-in defaults (tables,
inline tables, arrays of tables), `.env` and the real environment override
individual keys through a naming convention (`APP_SERVER_PORT` →
`server.port`), with values cast to the type they replace. The effective
config prints back out through `tomlStringify`.

## Run

```bash
sfl build setup
sfl build run
APP_SERVER_PORT=6000 sfl build run     # overridden
cp .env.example .env && sfl build run  # or via a .env file
sfl build test
```
