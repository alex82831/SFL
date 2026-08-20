# 15 — CLI todo

A todo list as a command-line tool: the **cli** package parses flags,
options and positionals from one spec (and generates `--help` from it), the
**ansi** package colours the listing, and persistence is a JSON file.

The store lives in `src/store.sfl` as pure functions over a path, which is
what makes it testable; the tests also drive the argument grammar through
`cliParseArgs`, the non-exiting version of `cliParse`.

## Run

```bash
sfl build setup      # install cli + ansi into ./sfl_packages/
sfl build run -- add "write the report"
sfl build run -- add "water the plants"
sfl build run -- list
sfl build run -- done 1
sfl build run -- list --all
sfl build run -- --help
sfl build test
```

Compile it and the tool stands alone:

```bash
sfl build && ./build/todo add "ship it" && ./build/todo list
```
