# 19 — CSV report

The **csv** package end to end: `csv.parseObjects` (RFC 4180, quoted commas
and all — cells arrive as strings, converting them is your decision),
reshaping with `groupBy`/`sortBy`, and `csv.stringifyObjects` with a fixed
column order to write `report.csv`. The **datetime** package reads the date
span.

## Run

```bash
sfl build setup
sfl build run       # prints the table, writes report.csv
sfl build test
```
