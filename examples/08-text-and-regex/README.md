# 08 — Text and regex

The string toolbox (`split`/`join`/`replace`/`padStart`/`trim`/`lines`,
`utf8Length` vs `length`) and the regex builtins (`regexFind` returning
`[whole, group1, ...]`, `regexFindAll`, `regexReplace`, `regexSplit`,
`matches`), composed into a word-frequency report over a text file.

Structure worth copying: the reusable pipeline lives in `src/words.sfl`, so
`src/main.sfl`, the tests **and a custom build task** all import it —
`build.sfl` is ordinary SFL and can import project modules.

## Run

```bash
sfl build run
sfl build test
sfl build wordcount             # the custom task
sfl build wordcount -- other.txt
```
