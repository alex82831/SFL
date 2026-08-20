# 01 — Hello, world

The smallest SFL project, and the everyday output tools: `println`/`print`,
`printf`/`format`, string interpolation `"${expr}"`, literal single-quoted
strings, and command-line arguments via `args()`.

## Run

```bash
sfl build run            # interpret src/main.sfl
sfl build run -- Ada     # pass arguments through
sfl build                # compile to build/hello-world
./build/hello-world Ada  # the native executable, same behaviour
sfl build test           # run tests/
```

## Open in an IDE

Open this directory in VSCode (SFL extension) or IntelliJ IDEA (SFL plugin).
The project file `build.sfl` is discovered automatically; ▶ on `src/main.sfl`
runs it, F5 (VSCode) or 🐞 (IDEA) debugs it with breakpoints.
