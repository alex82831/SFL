# 09 — Datetime tour

The `datetime` package: everything is an integer of epoch milliseconds, so
arithmetic is arithmetic. ISO 8601 parsing and formatting (`datetime.parse`,
`datetime.iso`), parts in any fixed offset (`datetime.parts`), calendar-aware addition
(`datetime.addMonths` clamps Jan 31 + 1 month to Feb 28/29), differences,
boundaries and calendar predicates — beside the builtin machine-local
`formatTime`/`timeMillis`.

## Run

```bash
sfl build setup     # installs the datetime package into ./sfl_packages/
sfl build run
sfl build test
```
