# 21 — Markdown static site

A static site generator in one page of SFL, wiring three packages together:
**toml** parses each post's `+++` front matter, **markdown** renders the
body (headings with anchors, fenced code with language classes, tables),
and **template** wraps everything in mustache layouts — `{{{content}}}`
un-escaped for the rendered HTML, a `{{#posts}}` loop for the index.

## Run

```bash
sfl build setup
sfl build run          # writes site/*.html
open site/index.html   # or xdg-open
sfl build clean-site
sfl build test
```

Add a post: drop `posts/2026-09-01-my-title.md` with the same front matter
and run again.
