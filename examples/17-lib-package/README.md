# 17 — A library package

A **library** project: no `main`, modules at the project root (that is what
ships), `sfl.pkg` naming the package and its entry module, `_`-private
helpers that stay out of consumers' namespaces, and a `main.sfl` that
re-exports the surface by importing the other modules.

## Test, package, install, use

```bash
sfl build test                      # the library's own tests
sfl build pkg                       # -> mathkit-0.2.0.sflpkg
sfl install mathkit-0.2.0.sflpkg    # install globally…
cd /tmp && mkdir try && cd try
sfl -e 'import "mathkit"
println(mean([1.0, 2.0, 4.0]))'
sfl -e 'import "mathkit/vec" as v
println(v.vecDot([1, 2], [3, 4]))'
```

A consumer project declares a range in its own `sfl.pkg` —
`{ "deps": { "mathkit": "^0.2.0" } }` — and imports as above; one version
per run, conflicts reported at the import.
