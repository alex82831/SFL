# SFL 项目与构建指南

针对 SFL 0.6.0 · 涵盖：项目创建、`build.sfl` 编写参考、构建与测试、部署与分发、IDE 集成。

构建工具内置在 `sfl` 二进制里（`sfl build`），**本体用 SFL 编写**（[buildtool/main.sfl](../buildtool/main.sfl)），
项目文件 `build.sfl` 也是普通 SFL——没有新语法、没有配置语言：构建工具先定义
`project()` 与 `task()` 两个函数，再对项目文件求值，这就是全部机制。

---

## 目录

1. [三分钟上手](#1-三分钟上手)
2. [创建项目](#2-创建项目)
3. [build.sfl 参考](#3-buildsfl-参考)
4. [内置任务](#4-内置任务)
5. [增量构建机制](#5-增量构建机制)
6. [自定义任务](#6-自定义任务)
7. [测试约定](#7-测试约定)
8. [describe：给 IDE 与 CI 的项目描述](#8-describe给-ide-与-ci-的项目描述)
9. [部署与分发](#9-部署与分发)
10. [IDE 集成](#10-ide-集成)
11. [故障排查](#11-故障排查)

---

## 1. 三分钟上手

```bash
mkdir hello && cd hello
sfl build init          # 脚手架：build.sfl + src/main.sfl + tests/smoke.sfl
sfl build               # 编译 src/main.sfl -> build/hello（原生可执行文件）
sfl build run           # 用解释器直接跑（开发时的快路径）
sfl build test          # 跑 tests/ 下的每个 .sfl
./build/hello           # 直接执行编译产物
```

再跑一次 `sfl build` 会打印 `hello is up to date`——增量构建靠文件修改时间，
什么都没改就什么都不做。

## 2. 创建项目

三种方式，产出完全相同的布局：

| 方式 | 操作 |
| --- | --- |
| 命令行 | `sfl build init [名字] [--version x.y.z] [--no-tests]`（省略名字用当前目录名） |
| IntelliJ IDEA | File → New → Project… → 左栏选 **SFL**，可设版本号与是否生成示例测试 |
| VSCode | 命令面板 → **SFL: New Project…**（File → New File… 菜单里也有） |

脚手架布局：

```
hello/
├── build.sfl          # 项目文件（本指南第 3 节）
├── src/
│   └── main.sfl       # 入口脚本
├── tests/
│   └── smoke.sfl      # sfl build test 会跑它
└── .gitignore         # 忽略 build/
```

SFL 脚本即程序，**没有 main 函数**：`src/main.sfl` 的顶层代码就是入口。

## 3. build.sfl 参考

`build.sfl` 是普通 SFL 文件，通常只有一个 `project({...})` 调用，外加可选的
`task(...)`：

```sfl
project({
  "name": "hello",
  "version": "0.1.0",
  "main": "src/main.sfl"
})

task("greet", (args) -> {
  println("hello from a task, args=" + jsonStringify(args))
}, "示例任务")
```

### project(spec)

必须恰好调用一次。`spec` 是一个对象，字段如下：

| 字段 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `name` | **是** | — | 项目名；编译产物叫 `<output>/<name>` |
| `version` | 否 | `"0.0.0"` | 语义化版本串，进入 `describe` 输出 |
| `main` | 否 | `null` | 入口脚本；不填则 `build`/`run` 任务不可用（纯库项目） |
| `sources` | 否 | `[dirName(main)]`，`main` 无目录时 `["."]` | 源码目录数组，递归收集 `*.sfl` 作为增量构建的输入 |
| `tests` | 否 | `"tests"` | 测试目录，`sfl build test` 递归跑其中每个 `.sfl` |
| `output` | 否 | `"build"` | 产物目录 |
| `opt` | 否 | `"O2"` | 编译优化级别（传给 clang 的 `-O0`…`-O3`） |

未知字段会被忽略（向前兼容），字段拼错不会报错——`sfl build describe` 可以
核对最终生效的模型。

### task(name, fn, doc?)

注册一个自定义任务。约束：

- `name` 不能与内置任务同名（build/run/test/clean/pkg/describe/tasks/init），不能重复注册；
- `fn` 接收一个参数：任务名之后的命令行参数数组。`sfl build greet a b` → `fn(["a", "b"])`；
- `fn` 返回整数时作为退出码，返回其他值视为 0；
- `doc` 显示在 `sfl build tasks` 里。

### 与 sfl.pkg 的分工

包依赖**只**声明在 `sfl.pkg`（JSON 清单，`import` 在解析期读它），`build.sfl`
不重复声明——单一事实源。两个文件的角色：

| 文件 | 语言 | 负责 |
| --- | --- | --- |
| `build.sfl` | SFL | 怎么构建：入口、源集、任务 |
| `sfl.pkg` | JSON | 是什么包：名字、版本、依赖范围（`sfl pkg --help`） |

只有要把项目作为**库**发布（`sfl build pkg`）或要 `import` 第三方包时才需要 `sfl.pkg`。

## 4. 内置任务

| 任务 | 行为 | 退出码 |
| --- | --- | --- |
| `sfl build`（= `build`） | 增量编译 `main` → `<output>/<name>`；`--force` 强制重编 | 编译失败非 0 |
| `run [args…]` | 解释器直接运行 `main`，参数透传；子进程直连终端（可交互） | 程序的退出码 |
| `test` | 递归跑 `<tests>/**/*.sfl`，每个文件一个进程 | 有失败为 1 |
| `clean` | 删除 `<output>/` | 0 |
| `pkg` | 委托 `sfl pkg build .`，产出 `<name>-<version>.sflpkg`（需 `sfl.pkg`） | 打包失败非 0 |
| `describe` | 项目模型 JSON 到标准输出（第 8 节） | 0 |
| `tasks` | 列出内置与自定义任务 | 0 |
| `init [名字] [--version v] [--no-tests]` | 脚手架（目录已有 build.sfl 则拒绝） | — |

## 5. 增量构建机制

`build` 任务比较**输入集的最新修改时间**与产物的修改时间（`fileMtime` 内置函数）：

```
输入集 = sources 目录下全部 *.sfl（递归） + main + build.sfl + sfl.pkg（存在时）
产物   = <output>/<name>
跳过条件：产物存在 且 fileMtime(产物) >= max(fileMtime(每个输入))
```

改任何源文件、项目文件或包清单都会触发重编；`--force` 无条件重编。
没有内容哈希、没有分布式缓存——对单一入口的整程序编译（编译器本身以毫秒计）
这已经足够，也永远不会骗你。

## 6. 自定义任务

任务函数是普通 SFL，全部内置函数可用。几个实用模式：

```sfl
// 发布：构建 + 打包，一步到位
task("release", (args) -> {
  var code = passthrough(exePath(), ["build"])
  if (code != 0) { return code }
  passthrough(exePath(), ["build", "pkg"])
}, "构建并打包为 .sflpkg")

// 代码生成：把数据文件烤进源码
task("gen", (args) -> {
  val words = readLines("data/words.txt")
  writeFile("src/generated.sfl", "val WORDS = " + jsonStringify(words) + "\n")
  println("generated " + length(words) + " words")
}, "重新生成 src/generated.sfl")

// 基准：对比解释与编译
task("bench", (args) -> {
  val t0 = timeMillis()
  passthrough(exePath(), ["src/main.sfl"])
  val t1 = timeMillis()
  passthrough("build/" + "hello", [])
  val t2 = timeMillis()
  printf("interpreted %dms, compiled %dms\n", t1 - t0, t2 - t1)
}, "解释 vs 编译耗时对比")
```

要点：

- **`passthrough(cmd, args)`**：子进程直连本进程的 stdin/stdout/stderr，实时输出、
  可交互，返回退出码。构建工具自己跑编译、跑测试用的都是它。
- **`exePath()`**：当前 `sfl` 二进制的绝对路径——调用自身时用它，别假设 PATH 上有 `sfl`。
- **`execute(cmd)`**：与 `passthrough` 相对，捕获输出返回 `{code, out, err}`，适合
  拿结果做判断的场景。
- 任务里 `exit(n)` 会立刻结束整个 `sfl build` 进程，效果等同返回 `n`。

## 7. 测试约定

`sfl build test` 的契约刻意最小：

- `<tests>/` 下每个 `.sfl` 文件是一个测试，**独立进程**运行（互不污染全局）；
- **退出码非 0 即失败**——用 `exit(1)` 报告失败，怎么断言随你；
- 输出实时显示，末尾汇总 `N file(s), all passed` 或失败计数。

配合仓库惯例（`tests/lib/assert.sfl` 风格的 `check`/`report` 函数）或裸写都行：

```sfl
// tests/math.sfl
def check(what, got, want) {
  if (got != want) {
    eprintln("FAIL " + what + ": got " + got + ", want " + want)
    exit(1)
  }
}
check("加法", 1 + 1, 2)
println("math: ok")
```

## 8. describe：给 IDE 与 CI 的项目描述

`sfl build describe` 输出稳定的 JSON——这是 IDE 与脚本了解项目的**唯一正道**，
不要自行解析 `build.sfl`：

```json
{
  "tool": "sfl build 0.1.0",
  "name": "hello",
  "version": "0.1.0",
  "main": "src/main.sfl",
  "sources": ["src"],
  "sourceFiles": ["src/main.sfl"],
  "tests": "tests",
  "output": "build",
  "opt": "O2",
  "deps": {},
  "tasks": ["build", "run", "test", "clean", "pkg", "describe", "tasks", "init", "greet"]
}
```

| 字段 | 说明 |
| --- | --- |
| `sourceFiles` | 输入集展开后的实际文件列表（增量构建看的就是它） |
| `deps` | 来自 `sfl.pkg` 的依赖范围；无清单时为 `{}` |
| `tasks` | 内置任务在前，自定义任务按注册顺序在后 |

配套的静态检查命令（IDE 保存时、CI 提交门禁用）：

```bash
sfl check --json src/*.sfl     # 只解析不执行；诊断带 file/line/col/span
```

## 9. 部署与分发

### 9.1 应用：分发编译产物

`sfl build` 的产物是**自包含原生可执行文件**——SFL 运行时与用到的标准库
静态链接在内，没有虚拟机、没有解释器依赖、毫秒级启动。hello 级程序约 70 KB。

部署 = 复制这一个文件：

```bash
sfl build && scp build/hello server:/usr/local/bin/
```

依赖细则：

- 数学库、线程库链接系统的 `libm`/`libpthread`（所有目标系统都有）；
- **HTTP 内置函数**（`httpGet` 等）是标准库的 SFL 实现，纯 http 零外部依赖；
  **https** 通过 `dlopen` 使用目标机的 OpenSSL/LibreSSL——没有 TLS 库的机器上
  其余功能不受影响，只有 TLS 调用会报错；
- `eval()`/`parse()` 在编译产物里不可用（需要解释器本身），编译时会指出。

平台矩阵（当前）：

| 平台 | 状态 |
| --- | --- |
| macOS（arm64 / x86_64） | ✅ 开发与部署 |
| Linux（x86_64 / arm64） | ✅ 需在目标平台上装 clang 后编译 |
| Windows | ❌ 计划中（见 IDE-插件实施记录 的移植清单） |

**没有交叉编译**：在哪个平台部署，就在哪个平台（或同平台 CI 机）上跑
`sfl build`。编译需要 clang（macOS 装 Xcode 命令行工具即可，首次编译会
自动构建并缓存运行时归档，见 `sfl --build-runtime`）。

### 9.2 库：打包与安装 .sflpkg

库项目通过包管理器分发。`sfl.pkg` 清单：

```json
{
  "name": "mathkit",
  "version": "1.2.0",
  "main": "main",
  "deps": { "textkit": "^0.3.0" }
}
```

打包与安装：

```bash
sfl build pkg                        # 或 sfl pkg build . → mathkit-1.2.0.sflpkg
sfl install mathkit-1.2.0.sflpkg               # 装到 $SFL_HOME/packages/（全局）
sfl install mathkit-1.2.0.sflpkg --local       # 装到 ./sfl_packages/（项目级，遮蔽全局）
sfl install github.com/owner/mathkit           # 直接从 git 仓库克隆并安装
sfl install mathkit                            # registry 短名（见下）
sfl pkg list / remove                # 管理
```

使用方在自己的 `sfl.pkg` 里声明范围，然后直接 `import`：

```sfl
import "mathkit"          // 清单 main 指向的模块
import "mathkit/matrix"   // 包内指定模块
```

版本范围支持 `1.2.3`、`^1.2.3`、`~1.2.3`、`>=1.2.3 <2.0.0`、`*`；一次程序运行
每个包只绑定一个版本，冲突在 import 处报出（带双方的要求与出处）。

**二进制包（`--bin`）：让使用方免编译。** `sfl pkg build --bin` 在打包前先把包
为当前平台编译一次，产物随源码一起进包（`bin/<目标三元组>/lib.a` + `abi.json`，
如 `bin/arm64-macos/`）：

- 使用方 `sfl -c` 编译依赖该包的程序时**直接链接归档**，不再重编包源码；
- 解释执行永远走源码；归档缺失、目标平台不符或 ABI 校验不过时，自动回退到
  源码编译——包里始终带着源码，`--bin` 只是加速，不是门槛；
- 两条路径要求行为逐字节一致（`tests/binpkg` 差分把关）。

多平台分发就在各平台分别跑 `--bin` 构建同版本包（`bin/` 按目标隔离），
或干脆只发源码包，让使用方的首次编译自己付一次。

分发渠道现状：

- **git 仓库直装**：`sfl install <git-url>`——公开仓库零凭证，私有仓库走用户自己的
  SSH 配置；传输就是 `git clone`；
- **registry 短名**：`sfl install <名字>` 查短名索引（默认仓库根的 `registry.json`，
  `SFL_REGISTRY` 可覆盖为你自己的索引）；
- **`.sflpkg` 文件**：tar.gz，仍可通过任何文件渠道手工分发（发布页、制品库、
  git tag 附件）。

中心化 registry 服务器、锁文件与校验和仍在路线图上
（见 [评估-IDE-插件与构建工具.md](评估-IDE-插件与构建工具.md) 第 3.4 节）。

### 9.3 CI 示例

```bash
#!/usr/bin/env bash
set -euo pipefail
sfl check --json $(find src tests -name '*.sfl') > /dev/null   # 门禁：语法
sfl build test                                                  # 门禁：测试
sfl build --force                                               # 干净构建
sfl build pkg                                                   # 库项目：打包
```

## 10. IDE 集成

两个插件功能对齐，语言能力全部来自 `sfl` 二进制（LSP/DAP），装好二进制即可：

| 能力 | VSCode（`editors/vscode/*.vsix`） | IntelliJ（`editors/intellij/*.zip`，Community 可用） |
| --- | --- | --- |
| 新建项目 | 命令面板 SFL: New Project… | New Project → SFL |
| 构建 | ⇧⌘B（`sfl: build` 任务） | Build → Build Project（输出进 Build 工具窗） |
| 运行当前文件 | 标题栏 ▶ / SFL: Run File | 行首 ▶ / 右键 / Current File ▶ |
| **调试当前文件** | F5（零配置） | Current File 🐞 / 行首 ▶ 菜单 |
| 断点/单步/变量/求值 | `sfl dap`（stdio） | `sfl dap --socket`（经 LSP4IJ） |
| 诊断/补全/悬停/签名 | `sfl lsp` | `sfl lsp`（经 LSP4IJ） |
| `.` 成员补全 / `import` 路径补全 | 同上（服务器触发字符 `.` `"` `/`） | 同上 |
| 跳转定义 / 大纲 | 同上 | 同上（Structure 视图） |

二进制查找顺序：插件设置（VSCode `sfl.path` / IDEA Settings→Tools→SFL）→
环境变量 `SFL_PATH` → PATH → 常见安装目录（`/opt/homebrew/bin` 等）。

## 11. 故障排查

| 症状 | 原因与处置 |
| --- | --- |
| `sfl build: no build.sfl in …` | 不在项目根目录，或还没 `sfl build init` |
| `build.sfl never calls project({...})` | 项目文件被求值了但没调 `project()`；检查是否写成了别的名字 |
| 一直 `up to date` 但想重编 | `sfl build --force`，或 `sfl build clean && sfl build` |
| `task 'x' would shadow the built-in task` | 自定义任务与内置任务重名，换个名字 |
| IDE 报找不到 sfl | 按第 10 节的查找顺序设置路径；IDEA 有带 Set Path… 按钮的通知 |
| 编译报 `cannot find clang` | macOS：`xcode-select --install`；Linux：装发行版的 clang |
| 部署机上 https 请求报错 | 目标机没有 OpenSSL/LibreSSL（纯 http 与其余功能不受影响） |
