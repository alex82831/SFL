# SFL 使用手册

**SFL — Scripting for Fun**，版本 0.8.3

SFL 是一门小巧的动态脚本语言，用 Scala 3 实现，通过 Scala Native 编译成独立的原生可执行文件。
它没有虚拟机、没有启动预热，二进制启动到执行完毕通常只要几毫秒，适合写命令行小工具、
文本处理脚本和自动化任务。

> 本手册中的内置函数参考由 `tools/gen-builtin-reference.sfl` 从解释器自身生成，
> 因此永远与实现保持一致。重新生成：`./tools/build-manual.sh`。

---

## 目录

1. [快速开始](#1-快速开始)
2. [构建与安装](#2-构建与安装)
3. [命令行用法](#3-命令行用法)
4. [交互式 REPL](#4-交互式-repl)
5. [语言语法](#5-语言语法)
   - [5.1 注释](#51-注释)
   - [5.2 数据类型与字面量](#52-数据类型与字面量)
   - [5.3 变量](#53-变量)
   - [5.4 运算符](#54-运算符)
   - [5.5 真值规则](#55-真值规则)
   - [5.6 条件](#56-条件)
   - [5.7 分支选择 select](#57-分支选择-select)
   - [5.8 循环](#58-循环)
   - [5.9 函数](#59-函数)
   - [5.10 闭包](#510-闭包)
   - [5.11 递归与尾调用](#511-递归与尾调用)
   - [5.12 数组与对象](#512-数组与对象)
   - [5.13 模块导入与命名空间](#513-模块导入与命名空间)
   - [5.14 错误处理](#514-错误处理)
   - [5.15 元编程](#515-元编程)
6. [函数式编程](#6-函数式编程)
   - [6.1 管道运算符](#61-管道运算符)
   - [6.2 函数组合子](#62-函数组合子)
   - [6.3 集合变换](#63-集合变换)
   - [6.4 不可变更新](#64-不可变更新)
   - [6.5 惰性序列](#65-惰性序列)
7. [并发与进程间通信](#7-并发与进程间通信)
   - [7.1 线程](#71-线程)
   - [7.2 通道](#72-通道)
   - [7.3 同步原语](#73-同步原语)
   - [7.4 共享数据的规则](#74-共享数据的规则)
   - [7.5 异步 I/O](#75-异步-io)
   - [7.6 子进程与管道](#76-子进程与管道)
   - [7.7 套接字](#77-套接字)
   - [7.8 文件流与具名管道](#78-文件流与具名管道)
   - [7.9 进程与信号](#79-进程与信号)
8. [编译为原生代码](#8-编译为原生代码)
   - [8.1 用法](#81-用法)
   - [8.2 可以编译的子集](#82-可以编译的子集)
   - [8.3 性能](#83-性能)
   - [8.4 工作原理](#84-工作原理)
9. [内置函数参考](#9-内置函数参考)
10. [完整示例](#10-完整示例)
11. [环境变量](#11-环境变量)
12. [已知限制](#12-已知限制)

---

## 1. 快速开始

新建 `hello.sfl`：

```sfl
def greet(name) = "你好，" + name + "！"

println(greet("世界"))

for (i in range(1, 4)) {
  println(i + " 的平方是 " + i * i)
}
```

运行：

```bash
sfl hello.sfl
```

输出：

```
你好，世界！
1 的平方是 1
2 的平方是 4
3 的平方是 9
```

---

## 2. 构建与安装

### 环境要求

- JDK 17 或更高（仅构建时需要）
- sbt 1.12+
- Clang / LLVM 工具链
  - **macOS**：安装 Xcode 或 Command Line Tools 即可。构建脚本会用 `xcrun` 找到随
    Xcode 分发的 clang，因此 PATH 上即使有其它版本的 clang 也不受影响。
  - **Linux**：安装发行版的 `clang`、`lld` 与 `libgc`（Boehm GC，可选）。
- 无需 libcurl：`httpGet` 等网络函数由标准库自带的 HTTP 客户端实现（stdlib/http.sfl，
  跑在 socket 原语之上）。https 在运行期通过 `dlopen` 使用系统的 OpenSSL/LibreSSL
  （macOS 系统自带 LibreSSL，Homebrew 的 openssl@3 优先；Linux 发行版自带 libssl）；
  纯 http 请求不需要任何外部库。

### 构建

```bash
sbt nativeLink
```

产物位于 `target/scala-3.8.4/sfl`。把它复制到 `PATH` 上任意目录即可：

```bash
cp target/scala-3.8.4/sfl /usr/local/bin/
```

### 构建选项

通过环境变量调节，无需改动 `build.sbt`：

| 变量 | 取值 | 默认 | 说明 |
| --- | --- | --- | --- |
| `SFL_BUILD_MODE` | `debug` / `releaseFast` / `releaseFull` | `releaseFast` | 优化级别。`releaseFull` 最快但编译最慢。 |
| `SFL_GC` | `none` / `boehm` / `immix` / `commix` | `immix` | 垃圾回收器。 |
| `SFL_LTO` | `none` / `thin` / `full` | 非 debug 下为 `thin` | 链接时优化。 |

例如做一次最高优化的构建：

```bash
SFL_BUILD_MODE=releaseFull SFL_LTO=full sbt nativeLink
```

### 运行测试

```bash
sbt test          # 解释器内核的 JUnit 测试
./tests/run.sh    # 语言行为、内置函数与命令行行为的完整测试
./bench/run.sh    # 性能基准
```

---

## 3. 命令行用法

```
sfl                        启动交互式 REPL
sfl <script.sfl> [参数…]   运行脚本，其余参数经由 args() 传给脚本
sfl -e '<代码>'            直接执行一段代码
```

除选项外还有一组**子命令**，各管一摊：

| 子命令 | 作用 | 详见 |
| --- | --- | --- |
| `sfl build [任务]` | 项目构建工具：脚手架、增量编译、测试、打包；项目文件 build.sfl 用 SFL 编写 | [项目与构建指南](SFL-项目与构建指南.md) |
| `sfl pkg …` | 包管理：打包（`--bin` 附带本平台编译归档，编译方免重编）/ 安装 / 列出 / 删除 | `sfl pkg --help` |
| `sfl install <名字\|git-url>` | 从 GitHub git 仓库或 registry 名安装包 | 见 §5.13 |
| `sfl check [--json] <文件…>` | 只解析不执行，报告诊断；给编辑器与 CI 用 | — |
| `sfl syntax` | 输出关键字、操作符与内置函数元数据（JSON）；编辑器词表由它生成 | — |
| `sfl lsp` | 在标准输入输出上提供语言服务器协议（诊断、补全、悬停、签名） | — |
| `sfl dap [--socket]` | 调试适配器协议：断点、单步、调用栈、变量（解释器模式） | — |

子命令名优先于脚本名：真有叫 `build` 的脚本要写 `sfl ./build` 或 `sfl build.sfl`。

脚本首行可以写 `#!/usr/bin/env sfl`,配合 `chmod +x` 后直接执行——加载器行
不参与解析。

| 选项 | 说明 |
| --- | --- |
| `-repl` | 强制启动 REPL |
| `-source <文件>` | 运行脚本（旧版本的写法，仍然支持） |
| `-e`, `--eval <代码>` | 执行命令行上给出的源码 |
| `--ast` | 只打印语法树，不执行 |
| `--time` | 在标准错误上报告解析与执行耗时 |
| `--no-color` | 关闭彩色输出（同时也遵循 `NO_COLOR` 环境变量） |
| `-v`, `--version` | 打印版本 |
| `-h`, `--help` | 打印帮助 |

退出码：正常结束为 `0`；`exit(n)` 返回 `n`；运行时或语法错误为 `1`；命令行参数错误为 `2`；
脚本文件打不开为 `66`。

错误信息输出到标准错误，脚本自身的输出保持干净，可以安全地重定向：

```bash
sfl report.sfl > report.txt      # 只拿到脚本输出
sfl report.sfl 2> errors.log     # 只拿到错误
```

### 传参给脚本

```bash
sfl greet.sfl 张三 李四
```

```sfl
for (name in args()) {
  println("你好，" + name)
}
```

---

## 4. 交互式 REPL

直接运行 `sfl` 进入。REPL 提供完整的行编辑能力：

| 按键 | 作用 |
| --- | --- |
| `Tab` | 补全名字；再按一次列出所有候选 |
| `↑` / `↓` | 翻阅历史；先输入前缀则只翻阅匹配该前缀的历史 |
| `←` / `→` | 移动光标 |
| `Ctrl-A` / `Ctrl-E`（或 `Home` / `End`） | 跳到行首 / 行尾 |
| `Alt-←` / `Alt-→` | 按词移动 |
| `Ctrl-W` | 删除光标前的一个词 |
| `Ctrl-U` / `Ctrl-K` | 删除光标之前 / 之后的内容 |
| `Ctrl-L` | 清屏 |
| `Ctrl-C` | 放弃当前行 |
| `Ctrl-D` | 退出 |

输入时会实时着色：关键字、字符串、数字、已知函数、`|>` 各有颜色。历史保存在
`~/.sfl_history`，跨会话可用。命令行编辑正确处理中文等宽字符，光标位置不会错位。

**续行**：括号未配平、或一行以运算符结尾时，会自动进入续行模式（提示符变成 `...`）。
因此函数、循环，以及跨行书写的管道都可以直接敲进去：

```
sfl> range(1, 11) |>
...   filter((n) -> n % 2 == 0) |>
...   map((n) -> n * n) |>
...   sum
= 220 : int
```

**补全**会看上下文：
- 一般情况下补全关键字、内置函数与你定义过的名字
- 在 `对象.` 后面补全该对象的键（只查全局变量，不会执行你的代码）
- 在 `:doc` 与 `:builtins` 后面只补全内置函数名

```
sfl> val cfg = {"host": "localhost", "port": 8080}
sfl> cfg.h<Tab>          →  cfg.host
sfl> :doc para<Tab>      →  :doc parallelMap
```

新功能在 REPL 里的配合：

```
sfl> val ch = channel()
= <channel unbounded> : channel
sfl> spawn(() -> { send(ch, "hi"); closeChannel(ch) })
= <thread thread-1> : thread
sfl> channelToArray(ch)
= ["hi"] : array
sfl> :threads
  thread-1  done

sfl> :bench 50 sum(range(10000))
50 runs: min 0.079 ms, mean 0.083 ms, max 0.102 ms
```

### REPL 命令

| 命令 | 作用 |
| --- | --- |
| `:help` | 显示命令与快捷键 |
| `:quit` | 退出 |
| `:vars` | 列出你定义过的变量 |
| `:funcs` | 列出你定义过的函数 |
| `:builtins [关键字]` | 显示内置函数参考，可按名字或分组过滤 |
| `:type <表达式>` | 求值并报告类型 |
| `:time <表达式>` | 求值并报告耗时 |
| `:ast <表达式>` | 显示语法树 |
| `:doc <名字>` | 显示单个内置函数的签名、分组与说明；也可查用户定义的函数 |
| `:threads` | 列出本次会话启动过的线程及其状态 |
| `:trace` | 重新显示上一个错误，含调用栈 |
| `:bench [次数] <表达式>` | 重复执行并报告最小/平均/最大耗时 |
| `:load <文件>` | 把脚本载入当前会话 |
| `:save <文件>` | 把本次会话的输入存成文件 |
| `:history` | 显示最近的输入 |
| `:reset` | 清除所有用户定义 |
| `:clear` | 清屏 |
| `:pretty on\|off` | 是否对数组和对象做缩进打印 |
| `:color on\|off` | 是否彩色输出 |
| `:depth [n]` | 查看或设置最大调用深度 |

示例：

```
sfl> :time sum(range(1000000))
= 499999500000 : int
elapsed: 22.418 ms

sfl> :type {"a": 1}
object

sfl> :builtins sha
encoding
  sha1(s)    字符串的 SHA-1 摘要，十六进制表示。
  sha256(s)  字符串的 SHA-256 摘要，十六进制表示。
```

在 REPL 之外，脚本里也可以调用 `help()` 或 `help("json")` 打印同样的参考。

---

## 5. 语言语法

### 5.1 注释

```sfl
// 单行注释

/* 块注释
   可以跨行 */
```

### 5.2 数据类型与字面量

SFL 有 9 种可以写成字面量或由普通运算得到的类型：`null`、`bool`、`int`、`float`、
`string`、`array`、`object`、`function`，以及由 `iter()` 产生的 `iterator`。

除此之外，运行时资源各有自己的类型名——`typeOf` 对它们返回 `thread`、`channel`、
`mutex`、`atomic`、`latch`、`semaphore`、`process`、`connection`、`server`、`file`，
共 10 种。它们由对应的内置函数创建，不能写成字面量，也不能序列化成 JSON。所以
`typeOf(v)` 一共可能返回 19 个名字。

```sfl
// 空值：null 与 none 等价
val nothing = null

// 布尔
val yes = true
val no  = false

// 整数：64 位有符号，支持多种进制与下划线分隔
val dec  = 1_000_000
val hex  = 0xFF        // 255
val oct  = 0o755       // 493
val bin  = 0b1010      // 10

// 浮点：64 位双精度
val pi   = 3.14159
val big  = 1.5e10

// 字符串：双引号会插值，单引号不会（shell 的规矩），三引号跨行
val a = "你好，${name}！共 ${size(xs)} 项"   // ${表达式} 求值后拼入
val b = '单引号里 ${name} 原样保留'
val c = """跨行
也插值：${1 + 1}"""
val d = r"正则不用翻倍反斜杠：[a-z]\d+"      // r 前缀：无转义、无插值
val e = r"""跨行的原始文本"""

字符串转义：`\n` `\t` `\r` `\b` `\f` `\0` `\\` `\"` `\'` `\$` `\e`（ESC），
以及 `\uXXXX`。插值只认 `${表达式}`：单独的 `$` 是字面量（`"$5.00"` 安全），
`\$` 显式转义。转义表对 `'`、`"`、`"""` 一体适用——三引号串里同样用 `\$` 写出字面量 `${`。单行字符串（含 `r"…"`、`r'…'`）不能包含换行，跨行文本用 `"""` 或 `r"""`。r 前缀的字符串没有任何处理——转义表对它不生效。

```sfl
println("制表符:\t结束")
println("中文")     // 中文
```

数组与对象用 JSON 风格书写：

```sfl
val nums   = [1, 2, 3]
val mixed  = [1, "two", true, null, [4]]
val person = {"name": "张三", "age": 30}
val brief  = {name: "李四", age: 25}     // 键也可以不加引号
```

#### 元组

`(a, b)` 构造**元组**——定长、不可变的序列，专为"一个函数要返回几个值"而生。
它与数组是两种类型：不能 push、不能改元素，`(1, 2) == [1, 2]` 为假。

```sfl
def divmod(a, b) = (a / b, a % b)
val (q, r) = divmod(17, 5)        // 解构，见 5.3
println(q, r)                     // 3 2

val t = (1, "two", 3.0)
println(t[0], t[-1], size(t))     // 1 3.0 3
for (x in (10, 20)) { println(x) }
```

元组按字典序比较，前缀相同时短的在前，所以 `sort` 一个"键值对元组"的数组正好
按键排序。`(x)` 仍是普通括号——没有一元组。`toArray(iter(t))` 转成数组，
`tupleOf(arr)` 转回来。元组没有 JSON 形式：`jsonStringify` 会报错并提示先转数组。

### 5.3 变量

```sfl
val fixed = 1        // 不可重新赋值
var count = 0        // 可以重新赋值
count = 1
count += 1           // 也支持 -=  *=  /=  %=

implicit = "直接赋值即声明"
```

对 `val` 声明的变量再赋值是编译期错误：

```sfl
val x = 1
x = 2
// Syntax error: cannot assign to 'x' because it is declared with 'val'
```

变量的作用域是词法作用域。函数参数与局部变量互不干扰，同名的外层变量不会被覆盖：

```sfl
val name = "外层"
def f(name) {
  name = "内层"
  name
}
println(f("参数"))   // 内层
println(name)        // 外层
```

#### 解构声明

`val`/`var` 的左边可以是任何模式（见 5.7）。模式必须匹配，不匹配就报错——
"值可能是几种形状之一"的场景该用 `select`：

```sfl
val [a, b] = [1, 2]
val (q, r) = divmod(17, 5)
val {"host": host, "port": port} = config
val {name, age} = person                  // 简写：取同名键
val [head, ...tail] = list
var [x, y] = [0, 0]                       // var 解构出的绑定可以再赋值
```

`for` 的循环变量同样可以是模式，而且带过滤语义：不匹配的元素**跳过**——

```sfl
for ((k, v) in [(1, "a"), (2, "b")]) { println(k, v) }

def even(v) = if (isInt(v) && v % 2 == 0) then [v] else null
for (even(e) in [1, 2, 3, 4]) { println(e) }   // 只打印 2 和 4
```

### 5.4 运算符

按优先级从低到高：

| 优先级 | 运算符 | 结合性 | 说明 |
| --- | --- | --- | --- |
| 1 | `\|>` | 左 | 管道，见 [6.1](#61-管道运算符) |
| 2 | `\|\|` `??` | 左 | 逻辑或（短路）；空合并 `??` 与之同层 |
| 3 | `&&` | 左 | 逻辑与，短路求值 |
| 4 | `==` `!=` | 左 | 相等比较 |
| 5 | `<` `<=` `>` `>=` | 左 | 大小比较 |
| 6 | `+` `-` | 左 | 加减 |
| 7 | `*` `/` `%` | 左 | 乘、除、取余 |
| 8 | `-` `!` | 右 | 取负、逻辑非 |
| 9 | `f(…)` `a[…]` `o.k` `o?.k` | 左 | 调用、下标、取字段 |

要点：

- **`&&` 与 `\|\|` 短路求值**：右侧只在必要时才求值。
- **`??` 与 `\|\|` 同层、左结合**：`a \|\| b ?? c` 即 `(a \|\| b) ?? c`；`??` 的右侧只吸收到
  `&&` 层，`a ?? b && c` 即 `a ?? (b && c)`。
- **整数除法**：`int / int` 结果仍是整数（`7 / 2` 得 `3`）。需要小数请让一侧是浮点：
  `7.0 / 2` 得 `3.5`。整数除以 0 会报错，浮点除以 0 得到无穷。
- **`+` 兼作拼接**：任一侧是字符串就做字符串拼接；两个数组相加得到拼接后的新数组；
  两个对象相加得到合并后的新对象。
- **`*` 兼作重复**：`"ab" * 3` 得 `"ababab"`。
- **`==` 按值比较**：`1 == 1.0` 为真；数组和对象按元素递归比较；不同类型（如 `1` 与 `"1"`）
  一律不相等。
- **比较运算**只用于数字、字符串和布尔值之间；比较不同类型会报错。

```sfl
println(2 + 3 * 4)         // 14
println((2 + 3) * 4)       // 20
println(7 / 2)             // 3
println(7.0 / 2)           // 3.5
println("值=" + 42)        // 值=42
println([1, 2] + [3])      // [1, 2, 3]
println({"a": 1} + {"b": 2})   // {"a": 1, "b": 2}
println(!true)             // false
```

#### 空安全:`?.` 与 `??`

`o?.k` 是**一跳**宽恕:接收者为 null → null,对象缺键 → null,其余与 `.k`
完全一样(包括报错)。规则是局部的——想宽恕哪一跳,就在哪一跳写 `?.`;
后面的普通 `.` 仍然严格。紧跟的调用属于本跳:`api?.ping()` 在键缺失或值为
null 时得 null。

`a ?? b` 在 a 为 null 时取 b(惰性)。注意 SFL 的 `||` 返回布尔而非操作数,
所以默认值只能用 `??` 拼——这比 JS 少一个陷阱。

```sfl
val host = conf?.server?.host ?? "localhost"
val label = "${user?.name ?? "匿名"}"
```

### 5.5 真值规则

`if`、`while`、`&&`、`\|\|`、`!` 以及 `filter` 之类的谓词都按下面的规则判定真假：

| 值 | 真假 |
| --- | --- |
| `false`、`null` | 假 |
| `0`、`0.0`、`NaN` | 假 |
| `""` | 假 |
| `[]`、`{}` | 假 |
| 其它一切 | 真 |

### 5.6 条件

`if` 是表达式，有返回值。条件外的括号可写可不写，`then` 也可省略。

```sfl
// 表达式形式
val label = if (n > 0) then "正" else "负"

// 块形式
if (n > 100) {
  println("大")
} elsif (n > 10) {
  println("中")
} else {
  println("小")
}

// else if 与 elsif 等价，elif 也可以
if (a) { … } else if (b) { … }
```

分支按书写顺序依次判断，第一个成立的分支生效，且分支数量不受限制。

### 5.7 分支选择与模式匹配 select

`select` 逐个尝试 `case`，命中即取该分支的值；都不命中时走 `default`，没有
`default` 则得到 `null`。它是表达式。最简单的形态按值相等比较：

```sfl
def dayName(n) {
  select (n) {
    case 1: "周一"
    case 2: "周二"
    case 3: "周三"
    default: "其它"
  }
}
```

`case` 后面其实是**模式**。模式在匹配的同时把值拆开、给各部分命名，绑定的名字
只在该分支内可见：

```sfl
def describe(v) = select (v) {
  case 0:                       "零"
  case n if n < 0:              "负数 " + n              // 守卫：模式后接 if
  case [x]:                     "单元素数组：" + x
  case [first, ...rest]:        "首元素 " + first + "，还有 " + size(rest) + " 个"
  case (a, b):                  "二元组，和为 " + (a + b)
  case {"kind": k, "size": s}:  k + "，大小 " + s
  case {name}:                  "有 name 键：" + name    // 简写，等价 {"name": name}
  case string(s):               "字符串 " + s            // 类型模式
  case _:                       "别的东西"               // 通配符
}
```

全部模式一览：

| 模式 | 匹配 | 绑定 |
| --- | --- | --- |
| `1`、`"s"`、`true`、`null`、`-2` | 按 `==` 比较字面量 | — |
| `_` | 任何值 | — |
| `x` | 任何值 | `x` = 整个值 |
| `$x` | 与**变量 x 当前的值**相等 | — |
| `[p1, p2]` | 长度相同的数组，逐元素匹配 | 各子模式 |
| `[p, ...rest]` | 至少 1 个元素的数组 | `rest` = 其余元素;`...` 可出现在任意一处 |
| `(p1, p2)` | 同长度的元组 | 各子模式 |
| `{"k": p}` | 含键 `k` 的对象（多余的键不管） | 子模式 |
| `{name}` | 含键 `name` 的对象 | `name` = 该键的值 |
| `int(p)` 等 | 类型判定后匹配 p | 子模式 |
| `f(p1, p2)`、`a.b.f(p1, p2)` | 提取器（见下） | 各子模式 |
| `p1 \| p2` | 任一分支（分支里不能绑定名字） | — |
| `x @ p` | p 匹配时整体绑定给 x | `x` 与 p 的绑定 |
| `case p if g:` | p 匹配且守卫 g 为真 | p 的绑定在 g 里可用 |

类型模式有九个：`int` `float` `bool` `string` `array` `object` `function`
`tuple` `number`（int 或 float）。

**提取器**是普通函数：`case f(a, b):` 会调用 `f(主体)`，返回 `null` 表示不匹配，
返回数组则逐元素交给子模式。无参数形式 `case f():` 只看返回值的真假。因此任何
谓词、任何解析函数都能直接当模式用：

```sfl
def halves(v) = if (isInt(v) && v % 2 == 0) then [v / 2, v / 2] else null

select (10) {
  case halves(a, b): a + b     // 10 -> halves 返回 [5, 5]，a=5 b=5
  case _: "奇数"
}
```

提取器的名字可以带点号：`case geo.point(x, y):` 调用的就是表达式里的 `geo.point`
——模块别名（见 5.13）照旧在解析期解析，对象字段照旧在运行期取值。这样一组提取器
可以像普通函数一样放进模块或对象里，不必先绑一个平名：

```sfl
// lib/geometry.sfl
def point(p) = if (isObject(p) && has(p, "x") && has(p, "y")) then [p.x, p.y] else null
```

```sfl
import "lib/geometry" as geo

def where(v) = select (v) {
  case geo.point(x, y): "点 (" + x + ", " + y + ")"
  case _: "不是点"
}
```

对照 Scala：`case x: Int` 写作 `case int(x)`；`case Some(v)` 的自定义 unapply 写作
提取器函数（`Some/None` 说成 `[值]/null`）；反引号 `` `x` `` 写作 `$x`；其余
（字面量、`_`、守卫、`@`、`|`、嵌套）逐一对应。SFL 不做编译期穷尽性检查——没有
分支命中就是 `null`，这沿袭 select 一贯的语义。

模式在解析期展开成普通的判断与赋值节点，解释器与编译器因此天然一致；`case`
全为字面量的 select 保持原来的实现与性能。

#### 惯用法：标签记录

SFL 的类型是数据的形状，不是名字——要名字，把名字放进数据里。需要"一组形状
不同、按种类分派"的数据时，惯用法是**标签记录**：对象的第一个键放一个 `"kind"`，
构造集中在构造函数里，分派交给 `select`。标准库的正则引擎
（`stdlib/regex.sfl`，11 种节点）就是这样写的。

```sfl
// 1. 构造只走构造函数——字段齐不齐、默认值是什么，只有这一处要对
def circle(r)  = {"kind": "circle", "r": r}
def rect(w, h) = {"kind": "rect", "w": w, "h": h}

// 2. 分派用 select (x.kind) 的字面量分支——这是最快的写法
def area(s) = select (s.kind) {
  case "circle": 3.14159 * s.r * s.r
  case "rect":   s.w * s.h
  default: error("area: 不是形状: " + jsonStringify(s))   // 3. 兜底必须报错
}
```

三条规则各堵一个坑：

- **构造集中**。字面量散落各处时，漏一个字段不会在构造点报错，而是在远处的
  使用点才炸（`object has no key 'h'`）。构造函数让字段个数一目了然，也给默认值
  一个家。
- **字面量分派**。`case "circle":` 这样的全字面量 select 编译成专门的比较链，
  实测比对象模式（`case {"kind": "circle", "r": r}:`）快约 1.2 倍，比提取器快约
  1.9 倍；解释执行下差距更大（约 2.4 倍与近 4 倍）。热路径认准这一种写法。
  复现：`bench/dispatch.sfl`。
- **`default:` 必须报错**。kind 字符串打错（`"circel"`）既不是语法错误也不是
  运行时错误——没有兜底时 select 静默返回 `null`，答案错了都查不到源头。让
  default 一律 `error(...)`，打错的 kind 第一次跑到就现形。

标签放在普通字段里，还换来一个别处买不到的性质：**JSON 往返无损**。
`jsonParse(jsonStringify(c))` 之后 `"kind"` 还在，跨进程、落盘、读回来照常分派。

要把一组类型连同匹配逻辑打包给别人用，就写成提取器放进模块——
`case geo.point(x, y):` 既是类型判定也是解构（写法见上）。

### 5.8 循环

```sfl
// while
var i = 0
while (i < 10) {
  i += 1
}

// for-in：可遍历数组、对象（得到键）、字符串（得到字符）和迭代器
for (x in [1, 2, 3]) { println(x) }
for (k in {"a": 1, "b": 2}) { println(k) }
for (c in "abc") { println(c) }
for (i in range(10)) { println(i) }

// break 与 continue
for (x in range(100)) {
  if (x > 10) { break }
  if (x % 2 == 0) { continue }
  println(x)
}
```

`break` 和 `continue` 只影响最内层的循环。

### 5.9 函数

```sfl
// 块形式：最后一个表达式的值就是返回值
def add(a, b) {
  a + b
}

// 单表达式形式
def square(n) = n * n

// 默认参数
def greet(name, greeting = "你好") = greeting + "，" + name

greet("张三")              // 你好，张三
greet("张三", "早上好")    // 早上好，张三

// 显式 return，可以出现在任意嵌套层级
def classify(n) {
  if (n < 0) {
    return "负数"
  }
  if (n == 0) {
    return "零"
  }
  "正数"
}

// 匿名函数（lambda）
val double = (x) -> x * 2
val sum    = (a, b) -> a + b
val block  = { (x) ->
  val t = x * 2
  t + 1
}

// 可变参数：以 ... 开头的最后一个参数收集剩余实参为数组
def tally(label, ...nums) = label + " = " + sum(nums)
tally("合计", 1, 2, 3)                // 合计 = 6
tally("空")                           // 空 = 0

def sumAll(...nums) = reduce(nums, (a, b) -> a + b, 0)
println(sumAll(1, 2, 3, 4))           // 10

// 函数是值，可以传递
println(map([1, 2, 3], double))       // [2, 4, 6]
println(map(["a", "b"], toUpper))     // ["A", "B"]

// 多参数列表 = 柯里化
def add3(a)(b)(c) = a + b + c
println(add3(1)(2)(3))                // 6
val add1 = add3(1)
println(add1(2)(3))                   // 6
```

参数个数不匹配会报错并指出期望的个数。递归和相互递归都可以正常使用：

```sfl
def isEven(n) = if (n == 0) then true else isOdd(n - 1)
def isOdd(n)  = if (n == 0) then false else isEven(n - 1)
```

> 相互递归要求两个函数都已经定义完毕后再调用。顶层语句是按顺序执行的，
> 所以调用点必须在两个 `def` 之后。

#### 单参数的箭头可以不带括号

```sfl
map(xs, x -> x * 2)
filter(xs, { x -> x % 2 == 0 })     // 花括号形式同理
val add = a -> b -> a + b           // 柯里化读起来也顺
```

多参数、零参数、带默认值或 `...rest` 的仍需括号——一个名字直接跟箭头,才是糖。

### 5.10 闭包

函数会捕获它定义时所在的作用域，因此可以用来封装状态：

```sfl
def counter() {
  var n = 0
  { () ->
    n = n + 1
    n
  }
}

val c1 = counter()
val c2 = counter()
println(c1())   // 1
println(c1())   // 2
println(c2())   // 1  —— 每次调用 counter() 得到独立的状态
```

同一次调用里创建的多个闭包共享同一份状态：

```sfl
def makePair() {
  var value = 0
  [(v) -> value = v, () -> value]
}
val pair = makePair()
pair[0](42)
println(pair[1]())    // 42
```

捕获可以跨越任意多层：

```sfl
def outer(a) = (b) -> (c) -> a + b + c
println(outer(1)(2)(3))    // 6
```

**循环中的闭包按轮次捕获。** 每一轮迭代都有自己的一份绑定，因此在循环里创建的闭包
看到的是那一轮的值，而不是循环结束后的最终值：

```sfl
val fs = []
for (i in range(3)) {
  push(fs, () -> i)
}
println(map(fs, (f) -> f()))    // [0, 1, 2]

val gs = []
var k = 0
while (k < 3) {
  val squared = k * k
  push(gs, () -> squared)
  k += 1
}
println(map(gs, (f) -> f()))    // [0, 1, 4]
```

这一点与 JavaScript 的 `let`、Rust 等语言一致，而不同于 Python 与 JavaScript 的 `var`。
只有在循环体确实创建了函数时才会为每轮分配新的作用域，普通循环不受任何影响。

循环体内对外层变量的赋值仍然作用于外层，只有本轮新声明的变量才是每轮独立的：

```sfl
def mixed() {
  var total = 0            // 外层变量，被所有轮次共享
  val fs = []
  for (i in range(4)) {
    total += i             // 累加到同一个 total
    push(fs, () -> i)      // 捕获的是本轮的 i
  }
  [total, map(fs, (f) -> f())]
}
println(mixed())           // [6, [0, 1, 2, 3]]
```

### 5.11 递归与尾调用

普通递归的嵌套深度有上限（默认 1200 层，可用 `SFL_MAX_DEPTH` 调整），超出会报告一条
运行时错误而不是让进程崩溃：

```sfl
def depth(n) = if (n == 0) then 0 else 1 + depth(n - 1)   // 非尾递归
depth(100000)
// Runtime error: call stack overflow (depth 1200); check for unbounded recursion
```

**处在尾位置的调用不占用调用栈**，因此可以无限深度地递归。所谓尾位置，是指该调用的
结果直接就是函数的返回值：函数体的最后一条语句、其中 `if` 与 `select` 各分支的取值，
以及任何 `return` 的操作数。

```sfl
// 尾递归：accumulator 写法，深度不受限制
def sumTo(n, acc) = if (n == 0) then acc else sumTo(n - 1, acc + n)
println(sumTo(1000000, 0))          // 500000500000

// 相互尾递归同样可行
def isEven(n) = if (n == 0) then true else isOdd(n - 1)
def isOdd(n)  = if (n == 0) then false else isEven(n - 1)
println(isEven(500000))             // true

// 块、select 与显式 return 中的尾位置
def loop(n, acc) {
  if (n == 0) {
    return acc
  }
  loop(n - 1, acc + 1)              // 块的最后一条语句，是尾位置
}
```

对比一下两种写法：`1 + f(...)` 中的调用不是尾位置，因为返回后还要做一次加法，所以
每一层都必须保留在栈上；而 `f(..., acc + 1)` 把累加提前算好，返回值即是结果。

> 由于尾调用不消耗栈，`def f() = f()` 是一个无限循环而非栈溢出，与 `while (true)` 等价。

### 5.12 数组与对象

```sfl
val a = [10, 20, 30]

println(a[0])      // 10
println(a[-1])     // 30，负数下标从末尾倒数
a[1] = 99
a[0] += 5

val o = {"name": "张三", "tags": ["x", "y"]}

println(o["name"])       // 张三
println(o.name)          // 同上，点号写法
println(o.tags[0])       // x
o["age"] = 30
o.age += 1

// 任意深度的读写
val deep = {"a": {"b": {"c": [1, 2, 3]}}}
println(deep.a.b.c[2])   // 3
deep.a.b.c[0] = 100
```

**多维数组**是嵌套数组加两样便利：`a[i, j]` 是 `a[i][j]` 的简写（读、写、复合
赋值都适用），`ndarray([2, 3], 0)` 构造一个 2×3、填 0 的矩形嵌套。`shape(a)`
报告各层长度，`isRectangular(a)` 检查每行是否等长：

```sfl
val g = ndarray([2, 3], 0)
g[0, 1] = 5
g[1, 2] += 7
println(g[0, 1], shape(g))     // 5 [2, 3]
```

下标越界、对象缺键都会报错并指出具体位置。想要“取不到就用默认值”，用 `get()`：

```sfl
println(get(o, "缺失的键", "默认值"))
```

对象保持键的插入顺序，遍历和序列化的结果是确定的。

常用集合操作：

```sfl
val nums = range(1, 11)                        // [1..10]
println(sum(nums))                             // 55
println(filter(nums, (n) -> n % 2 == 0))       // [2, 4, 6, 8, 10]
println(map(nums, (n) -> n * n))
println(reduce(nums, (acc, n) -> acc + n, 0))  // 55
println(sortBy(["ccc", "a", "bb"], length))    // ["a", "bb", "ccc"]
println(join(map(nums, toString), ", "))
```

### 5.13 模块导入与命名空间

**每个文件的顶层名字都属于它自己的命名空间。** 你写的 `def map` 就是你的 `map`——
它不会替掉内置的那一个，也不会跟着你去改别人的代码。这是 SFL 处理重名的全部办法，
下面的规则都是从这一条推出来的。

```sfl
// lib/stats.sfl
def summarise(xs) = map(xs, (x) -> x * 2)   // 这里的 map 永远是内置的那个
```

```sfl
// main.sfl
import "lib/stats"
def map(a, b) = "我的 map"                   // 只在本文件里生效

println(map([1], (x) -> x))                 // 我的 map
println(summarise([1, 2, 3]))               // [2, 4, 6]——库看到的还是内置 map
println(std.map([1, 2], (x) -> x * 3))      // [3, 6]——随时能绕回内置的
```

在没有命名空间之前，上面这段的 `summarise` 会拿到你的 `map`，而且没有任何提示。

#### 名字的查找顺序

一个不带点的名字，按由近及远的顺序解析：

1. 局部变量与参数（包括闭包捕获的外层变量）；
2. **本文件的顶层声明**——包括以 `_` 开头的私有名字；
3. **同一命名空间里兄弟文件的声明**（见下面的"一个单元就是一个命名空间"）；
4. **被 `import` 打开（open）的命名空间**里的名字；
5. **内置函数**。

如果第 4 步有两个被打开的命名空间都导出这个名字，SFL 不会挑一个，而是在引用处报错，
让你写清楚要哪一个：

```
Syntax error: 'label' is ambiguous: one and two both export it
  help: qualify it — one.label or two.label — or import one of them under an alias
```

#### 查找模块文件

```sfl
import "utils"          // 会找 utils.sfl
import "lib/strings"    // 相对路径
```

查找顺序：

1. 当前工作目录
2. 引入该模块的那个脚本所在的目录
3. `$SFL_HOME/libs/`

被导入的文件在导入处执行一次，它的顶层定义随即可用。同一个模块被多次导入只会执行一次；
出现循环导入会明确报错，而不是无限递归。

#### 一个 import 做两件事

`import "m"` 会**绑定一个命名空间**，还可能**打开**它：

| 写法 | 绑定命名空间 | 打开（名字直接可见） |
| --- | --- | --- |
| `import "lib/geometry"` | `geometry` | 是——普通文件是你自己程序的一部分 |
| `import "csv"` | `csv` | 否——包是别人写的一个有名字的单元 |
| `import "csv" as c` | `c` | 否 |
| `import "gui" open` | `gui` | 是——显式要求 |

绑定总会发生，所以 `geometry.dist(p)` 和 `csv.parse(text)` 一样都能写；
区别只在于要不要连带把名字铺进当前文件。

```sfl
import "lib/geometry"          // 普通文件：两种写法都行
println(dist({"x": 3, "y": 4}))
println(geometry.dist({"x": 3, "y": 4}))

import "csv"                   // 包：只有带命名空间的写法
println(csv.parse("a,b\n1,2"))
println(parse("a,b"))          // 报错：undefined variable 'parse'
```

包默认不打开，是因为包是装来的：`csv` 有 `parse`，`toml` 也有 `parse`，
把它们一起铺进同一个文件只会撞车。真想要扁平的写法——比如 `gui` 那种一整套
控件构造器的 DSL——就写 `open`，这时是你自己选的：

```sfl
import "gui" open
val app = gui.app({title: "Demo", root: (ctx) -> column([text("hi")])})
```

`x.name` 在**解析期**就解析成了对某个全局的直接引用，所以它和写一个普通名字一样快——
没有对象查找，运行时不做任何额外的事。它也是普通的值：

```sfl
val f = geometry.dist
println(map(points, geometry.dist))
```

#### 私有名字

**以 `_` 开头的顶层名字只属于声明它的那个文件。** 别的文件看不到它——通过命名空间
也看不到——两个模块可以各有一个同名的 `_helper` 而互不干扰。

```sfl
// lib/geometry.sfl
def _square(n) = n * n            // 只在本文件可见
def dist(p) = sqrt(_square(p.x) + _square(p.y))
```

```sfl
import "lib/geometry"
println(dist({"x": 3, "y": 4}))   // 5.0
println(_square(3))               // 报错：undefined variable '_square'
println(geometry._square(3))      // 报错：'_square' is private to 'geometry'
```

规则只作用于**顶层**：函数内部的 `val _tmp = 1` 就是一个普通局部变量。

SFL 自己的标准库就是这样写的——`stdlib/` 下的模块共有 130 个私有名字，其中
`_bad`、`_show` 这样的名字同时出现在四个文件里。

#### 一个单元就是一个命名空间

命名空间是按**单元**划分的，不是按文件：

- 目录里有 `sfl.pkg` 的，那份清单的 `name` 就是这个单元的名字，
  它下面所有 `.sfl` 文件**共用**一个命名空间；
- 没有清单的文件，自己就是一个命名空间，名字取文件名。

所以一个包的七个模块合起来是一个 `httpd`，`httpd.GET` 和 `httpd.mimeType`
不管定义在哪个文件里都能拿到；一个项目的 `src/a.sfl` 和 `src/b.sfl` 也是一家人，
互相调用不需要先 `import`（`import` 仍然负责让那个文件**执行**一次）。

同一个命名空间里的两个文件声明同一个公开名字会直接报错，而不是后者覆盖前者：

```
Syntax error: 'shared' is already defined in 'dup'
  help: other.sfl declares it too, and both files share one namespace
  help: rename one of them, or make it private by starting the name with '_'
```

#### 内置函数的命名空间

所有内置函数除了本来的名字，还住在两个命名空间里：

- **`std`**——全部 367 个，`std.map`、`std.println`、`std.connect`；
- **按类别分组**——`math.abs`、`string.toUpper`、`array.concat`、`io.println`、
  `fs.readFile`、`json.jsonParse`、`thread.spawn`……组名就是手册第 9 章的分组名。

它们是同一个函数的不同写法，不是副本：`std.length == string.length` 为真，
两者都能当值传递。这条通道的用处是：**本文件遮住了某个内置函数之后，还能拿回它。**

```sfl
def connect(a, b) = "我的 connect"      // 本文件的
val sock = std.connect(host, port, 5000)  // 内置的
```

数据库驱动包就是这么写的：`mysql.connect` 是包的公开 API，包内部要开 TCP 连接时
写 `std.connect`。

命名空间不是关键字。本文件声明过、或者局部绑定过的名字总是赢：

```sfl
val math = {abs: (x) -> "字段"}
println(math.abs(-1))                     // 字段
```

#### 几条规则

- 模块的私有名字（`_` 开头）不能通过命名空间访问；
- 不能从外部给命名空间里的名字赋值——要改就由模块自己导出一个函数来改；
- 同一个模块既 `import "m"` 又 `import "m" as x` 是允许的：名字放在哪里不取决于
  怎么导入它，两个写法指向同一份定义；
- 别名重名会报错（`'csv' already names another namespace`），换个别名即可。

#### eval 与命名空间

`eval` 和 `parse` 在**调用方的命名空间**里编译，所以交给它们的文本看到的东西，
和调用它的那个文件看到的完全一样——不多也不少：

```sfl
def helper() = 41
println(eval("helper() + 1"))    // 42
```


#### 包与版本

模块可以打成**包**分发。一个包就是一个目录:里面放模块文件,根上放一份
`sfl.pkg` 清单(JSON):

```json
{ "name": "mathx", "version": "1.2.0", "main": "main",
  "deps": { "geo": "^1.0.0" } }
```

`import` 找不到同名文件时,会把第一段当包名解析:

```sfl
import "mathx"            // 该包的 main 模块
import "mathx/stats"      // 包内指定模块
import "mathx/geo" as g   // 与 as 别名照常组合
import "mathx" open       // 要扁平写法时显式打开
```

**一个包就是一个命名空间**,名字取清单里的 `name`。包内所有模块共用它,所以
`import "mathx/stats"` 之后 `mathx.mean(...)` 照样能拿到 `main.sfl` 里的东西;
包内的模块彼此引用也不必先 `import`(`import` 只负责让那个文件执行一次)。

包默认**只绑定命名空间、不打开**:两个包各有一个 `parse` 是常态,而它们该互不
干扰。标准包套件的公开 API 因此都不再带手写前缀——`csv.parse`、`datetime.iso`、
`jwt.sign`、`mysql.connect`——前缀的活儿交给命名空间了。

查找根有两个,项目本地优先:`./sfl_packages/<名>/<版本>/`,然后
`$SFL_HOME/packages/<名>/<版本>/`。选哪个版本由**需求方的清单**决定:包内模块
引用别的包时用它自己 `sfl.pkg` 里声明的范围(引用未声明的包会报错并给出
`"deps": {...}` 的写法);顶层程序用项目根的 `sfl.pkg`;都没有就取已安装的最高
版本。版本范围支持 `1.2.3`(精确)、`^1.2.3`、`~1.2.3`、`*`、`>=1.2.3` 以及
`>=a <b` 连写。

**一次运行里每个包只有一个版本**(扁平模型)。先解析的引用把版本定下来;之后
若有引用的范围容不下这个版本,报错并指出两个需求方与各自的范围——这类冲突宁可
当场炸,不可静默混两个版本。

包的构建与安装走命令行:

```bash
sfl pkg build            # 当前目录 → mathx-1.2.0.sflpkg
sfl pkg install mathx-1.2.0.sflpkg           # 装到 $SFL_HOME/packages
sfl pkg install ./mathx --local              # 装到 ./sfl_packages(压过全局)
sfl pkg list
sfl pkg remove mathx@1.2.0
```

包对语言不引入任何新语义:解析结果就是一个文件,`_` 私有名、`as` 别名、命名空间、
编译器(import 在解析期内联)全部照常工作——编译产物与解释器逐字节一致由差分测试
盯着。

#### 从 git 仓库或 registry 安装

`sfl install`(即 `sfl pkg install`)的来源除了本地目录和 `.sflpkg`,还可以是一个
**git 仓库**或一个 **registry 短名**:

```bash
sfl install github.com/owner/repo                     # 克隆并安装
sfl install github.com/owner/repo/pkg/mathx@v1.2.0    # 仓库子目录 + 标签
sfl install git@github.com:owner/repo#pkg/mathx       # 私有仓库,走你的 SSH key
sfl install mathx                                     # registry 短名
sfl install mathx@1.2.0                               # 指定版本
```

- **传输一律走 `git clone`**:public 仓库零凭证,private 仓库用你**自己配好的 git
  认证**(SSH key 或凭证助手)——SFL 自己从不经手 token,也避开匿名 API 的限速。需要
  本机装了 `git`。
- **子目录**:github 形式把 `owner/repo` 之后的路径当子目录;任意形式都可用 `#子目录`
  片段(如私有仓库 `git@host:owner/repo#packages/mathx`)。子目录里含 `..` 或绝对路径
  会被拒(防路径穿越)。
- **版本**:`@<tag|branch|commit>` 指定 ref。ref 形如 `v1.2.0` 时会与仓库里 `sfl.pkg`
  的 version **交叉校验**,不符即拒——否则包会以它并不声明的版本参与后续的 semver 决策。
  分支/哈希则以 `sfl.pkg` 为准,安装那行回显真实版本。
- **短名**:`sfl install <名>` 到 registry 索引里查名字→git 源。默认索引是
  `SFL_REGISTRY`(缺省指向官方仓库的 `registry.json`),可用该环境变量换成你自己的。
- 下载物先落到临时目录、清单校验通过后才进包根;**不拉 submodule**(不支持);从
  git 装即运行别人的源码,只装可信来源。

装完就是 `$SFL_HOME/packages/` 下一个普通目录,扁平模型、版本冲突报错、`import`
全部照常。

#### 预编译的二进制包

一个包可以在源码旁边**预编译一份二进制归档**,给 `sfl -c` 提速:

```bash
sfl pkg build --bin       # 为当前平台编译,产物进 bin/<平台>/lib.a + abi.json,再打包
```

`--bin` 把包的每个模块用本编译器各编成一个对象文件(兄弟模块之间以符号相互引用,
和链接它的程序看到的一样),归到 `bin/<target>/lib.a`,旁边写一份 `abi.json` 记下
是哪套工具链、哪份源码编出来的。这份 `bin/` 随 `.sflpkg` 一起分发。

要点在于**源码永远随行,而且是事实源**:

- **解释器**始终跑源码——它不看 `bin/`,所以"调用预编译的包"在解释器里就是正常
  解释执行,诊断照常引用源码行;
- **`sfl -c`** 在归档可用且匹配时链接它、跳过重新编译该包;`abi.json` 的工具链指纹
  (版本 / 运行时 / 标准库 / 编译器)或任一模块的源码摘要对不上,就**自动回退**到
  从源码编译。所以二进制形态是构建加速加固定的目标码,而**不是**藏源码的手段;缺了
  它、旧了它,程序照样能编能跑。

三种路径——解释、链接归档、`--pkg-source` 强制从源码编译——输出逐字节一致,由
`tests/binpkg` 盯着。当前形态链接的包本身不能再依赖别的包(有这种依赖就整体回退到
源码),公开名必须都是函数(公开的顶层 `val` 值会让该包回退)。

```bash
sfl -c app.sfl                 # 用得上的已装二进制包会被自动链接
sfl -c --pkg-source app.sfl    # 强制所有包从源码编译
```

### 5.14 错误处理

运行时错误会中断脚本，并打印出错位置、源码行、调用栈，以及可操作的 `help` 提示：

```
Runtime error: object has no key 'emial'
  --> report.sfl:12:21
   |
12 |   println("hi " + user["emial"])
   |                       ^
  help: did you mean 'email'?
  help: it has: name, email
  help: use get(o, key, default) to read a key that may be missing
  traceback (most recent call first):
    at greet (report.sfl:12)
    at main (report.sfl:30)
```

`help` 行会尽量给出下一步该怎么改：拼错的键或变量名给出最接近的候选、参数个数不符时
给出函数签名、下标越界时给出合法区间、类型不匹配时给出该用哪个转换函数。未闭合的括号
会指出它是在**哪一行打开**的，而不是只说“文件到头了”。

尾调用会复用调用者的栈帧，所以调用栈里不会出现被替换掉的那一层——这正是尾调用的语义。

想捕获错误，用 `try(body, handler)`：`body` 是一个无参函数，出错时调用 `handler(错误消息)`。

```sfl
val result = try(
  () -> jsonParse(readFile("config.json")),
  (err) -> {
    eprintln("读取配置失败: " + err)
    {}
  }
)

// 不关心错误内容时可以省略 handler，出错则返回 null
val maybe = try(() -> parseInt(userInput))
if (isNull(maybe)) {
  println("这不是一个数字")
}
```

`try` 只把错误消息交给处理函数。需要完整信息时用 `attempt`，它把结果变成数据：

```sfl
val r = attempt(() -> jsonParse(text))
if (r.ok) {
  use(r.value)
} else {
  eprintln(r.error.message)          // 错误消息
  eprintln(r.error.file + ":" + r.error.line)
  for (h in r.error.help) { eprintln("  提示: " + h) }
  for (f in r.error.trace) { eprintln("  at " + f) }
}
```

错误成为普通值之后，就可以像别的数据一样组合：

```sfl
val outcomes = map(inputs, (s) -> attempt(() -> toInt(s)))
val parts = partition(outcomes, (r) -> r.ok)
val values = map(parts[0], (r) -> r.value)
val problems = map(parts[1], (r) -> r.error.message)
```

主动抛错用 `error()` 和 `assert()`：

```sfl
def withdraw(balance, amount) {
  assert(amount > 0, "金额必须为正")
  if (amount > balance) {
    error("余额不足")
  }
  balance - amount
}
```

### 5.15 元编程

```sfl
// 执行一段源码
println(eval("1 + 2"))          // 3

// 先编译成无参函数，再多次执行
val code = parse("x * 2")
x = 21
println(eval(code))             // 42

// 查询函数信息
println(signature(sortBy))              // sortBy(arr, key)
println(describe("md5").doc)            // 字符串的 MD5 摘要，十六进制表示。
println(length(builtins()))             // 内置函数总数
```

---

## 6. 函数式编程

SFL 把函数当作普通的值：可以传参、返回、存进数组和对象。在此之上，标准库提供了一套
组合子、集合变换、不可变更新与惰性序列，配合[尾调用](#511-递归与尾调用)可以完全用
函数式风格写程序。

### 6.1 管道运算符

`x |> f` 就是 `f(x)`；如果右边写成调用形式，管道左边的值会作为**第一个**参数插入：

```sfl
"  hello  " |> trim |> toUpper        // "HELLO"
[3, 1, 2] |> join("-")                // "3-1-2"，即 join([3,1,2], "-")
```

它让一串变换按发生顺序阅读，而不是从里往外读：

```sfl
// 传统写法：从内往外
sum(map(filter(range(1, 11), (n) -> n % 2 == 0), (n) -> n * n))

// 管道写法：从左往右
range(1, 11)
  |> filter((n) -> n % 2 == 0)
  |> map((n) -> n * n)
  |> sum
```

`|>` 的优先级最低，因此 `a + b |> f` 是 `f(a + b)`。

### 6.2 函数组合子

```sfl
val inc = (n) -> n + 1
val dbl = (n) -> n * 2

compose(inc, dbl)(5)        // 11，从右往左：inc(dbl(5))
pipe(inc, dbl)(5)           // 12，从左往右：dbl(inc(5))

partial((a, b, c) -> a + b + c, 1, 2)(3)   // 6，绑定前几个参数
partialRight((a, b) -> a - b, 4)(10)       // 6，绑定后几个参数
flip((a, b) -> a - b)(4, 10)               // 6，交换前两个参数

curry((a, b, c) -> a + b + c)(1)(2)(3)     // 6
uncurry((a) -> (b) -> a + b)(3, 4)         // 7
apply(max, [4, 9, 1])                      // 9，用数组作参数列表

identity(7)                 // 7
constantly("x")(1, 2)       // "x"
negate((n) -> n > 3)(5)     // false
```

`memoize` 给纯函数加缓存，`once` 让初始化只跑一次，`tap` 在管道中间观察数据：

```sfl
val fib = memoize((n) -> if (n < 2) then n else fib(n - 1) + fib(n - 2))
println(fib(60))            // 立即得到结果

val init = once(() -> { println("只会打印一次"); "ready" })
init(); init()

[1, 2, 3] |> tap((v) -> eprintln("中间结果: " + toString(v))) |> sum
```

用组合子可以写出无参数名的（point-free）风格：

```sfl
val evens   = (ns) -> filter(ns, (n) -> n % 2 == 0)
val squares = (ns) -> map(ns, (n) -> n * n)
val total   = pipe(evens, squares, sum)

println(total(range(7)))    // 56
```

### 6.3 集合变换

除了 `map` / `filter` / `reduce` / `forEach`，还有：

```sfl
flatMap([1, 2], (n) -> [n, n * 10])          // [1, 10, 2, 20]
groupBy([1, 2, 3, 4], (n) -> n % 2)          // {"1": [1, 3], "0": [2, 4]}
partition(range(6), (n) -> n < 3)            // [[0, 1, 2], [3, 4, 5]]
zipWith([1, 2], [10, 20], (a, b) -> a + b)   // [11, 22]
scan([1, 2, 3], (a, b) -> a + b, 0)          // [0, 1, 3, 6]，保留中间结果
foldRight(["a", "b"], (x, acc) -> x + acc, "!")   // "ab!"
takeWhile([1, 2, 9], (n) -> n < 5)           // [1, 2]
dropWhile([1, 2, 9], (n) -> n < 5)           // [9]
distinctBy(["aa", "ab", "b"], (s) -> charAt(s, 0))   // ["aa", "b"]
chunk(range(5), 2)                           // [[0, 1], [2, 3], [4]]
windows([1, 2, 3, 4], 3)                     // [[1, 2, 3], [2, 3, 4]]
maxBy(["a", "ccc"], length)                  // "ccc"
sumBy([{"n": 1}, {"n": 2}], (o) -> o.n)      // 3
frequencies(["a", "b", "a"])                 // {"a": 2, "b": 1}
intersperse([1, 2, 3], 0)                    // [1, 0, 2, 0, 3]
enumerate(["x", "y"])                        // [[0, "x"], [1, "y"]]
```

### 6.4 不可变更新

`assoc` 系列返回修改后的副本，原值不动；路径上的容器逐层复制，其余部分与原值共享，
因此对大结构做小改动的代价很低。

```sfl
val state = {"user": {"name": "张三", "roles": ["dev"]}, "count": 1}

assoc(state, "count", 2)                        // 副本，state.count 仍是 1
dissoc(state, "count")                          // 去掉一个键的副本
conj([1, 2], 3)                                 // [1, 2, 3]，原数组不变

assocIn(state, ["user", "name"], "李四")         // 深层替换
updateIn(state, ["count"], (n) -> n + 1)        // 用函数计算新值
getIn(state, ["user", "roles", 0])              // "dev"
getIn(state, ["user", "missing"], "默认")        // 路径不存在时返回默认值

println(state.user.name)                        // 张三，原对象始终未被修改
```

与之对应，`push` / `set` / `remove` / `clear` 是就地修改的版本。

### 6.5 惰性序列

`naturals`、`iterate`、`repeatedly`、`cycle` 产生**无限**的惰性序列；`map`、`filter`、
`take`、`drop`、`takeWhile`、`dropWhile`、`flatMap`、`enumerate` 作用在迭代器上时保持
惰性；`toArray` 才真正求值。

```sfl
// 前 5 个奇平方数
naturals()
  |> map((n) -> n * n)
  |> filter((n) -> n % 2 == 1)
  |> take(5)
  |> toArray                    // [1, 9, 25, 49, 81]

iterate((n) -> n * 2, 1) |> take(8) |> toArray    // [1, 2, 4, 8, 16, 32, 64, 128]
cycle(["a", "b"])        |> take(5) |> toArray    // ["a", "b", "a", "b", "a"]
naturals(1) |> takeWhile((n) -> n < 6) |> toArray // [1, 2, 3, 4, 5]
```

惰性是真实的——只有被取走的元素才会被计算：

```sfl
var 计算次数 = 0
naturals() |> map((n) -> { 计算次数 += 1; n }) |> take(3) |> toArray
println(计算次数)               // 3，而不是无穷
```

> 对无限序列调用 `toArray` 而不先用 `take` 截断，程序会一直运行下去。

---

## 7. 并发与进程间通信

SFL 的线程是**真正的操作系统线程**，没有全局解释器锁，因此计算密集的工作可以跑满多个
核心。每个线程有自己的调用栈与控制流状态，全局变量与函数定义则是所有线程共享的。

### 7.1 线程

```sfl
val t = spawn(() -> {
  sleep(50)
  "done"
})

println("主线程继续往下走")
println(await(t))          // done
```

`spawn(f, ...args)` 用给定实参在新线程上执行 f，返回一个线程句柄。`await(t)` 等待它结束
并返回其返回值；如果那个线程里出了错，错误会在调用 `await` 的线程重新抛出：

```sfl
val bad = spawn(() -> error("worker failed"))
try(() -> await(bad), (e) -> println("捕获到: " + e))
```

`awaitAll(数组)` 等待一组线程，**按原顺序**返回结果，与它们谁先结束无关：

```sfl
val ts = map(range(4), (i) -> spawn(heavyWork, i))
val results = awaitAll(ts)
```

需要按元素并行时，`parallelMap` 更直接——它自己分配线程、保持顺序：

```sfl
val hashes = parallelMap(files, (f) -> sha256(readFile(f)))
val squares = parallelMap(range(1000), (n) -> n * n, 4)   // 指定 4 个线程
```

**退出语义**：`spawn` 出来的线程会被等待，程序不会在它们干活时退出；
如果某个线程出错却没人 `await`，退出时会把错误打到标准错误并让退出码变成 1。
后台常驻的循环请用 `spawnDaemon`，它不会阻止程序退出。`exit(n)` 则是立刻结束，
不等任何线程。

```sfl
spawn(() -> { sleep(300); println("这一行会打印") })
spawnDaemon(() -> { while (true) { sleep(1000) } })       // 不会拖住程序
println("主流程结束")
```

其它：`isAlive(t)`、`threadName(t)`、`threadFailed(t)`、`currentThreadName()`、
`cpuCount()`、`yieldThread()`；`await(t, 毫秒)` 超时未结束会报错。

### 7.2 通道

通道是线程之间传递数据的首选方式，它自带同步，不需要额外加锁。

```sfl
val ch = channel()                 // 无界
val bounded = channel(16)          // 有界：满时 send 阻塞，形成背压

send(ch, value)                    // 投递
receive(ch)                        // 阻塞取值
receive(ch, 100)                   // 最多等 100 毫秒，超时返回 null
tryReceive(ch)                     // 不等待，空则返回 null
closeChannel(ch)                   // 关闭，唤醒所有等待者
```

关闭后仍可取走已经入队的值；取空之后 `receive` 返回 `null`，这正好可以作为循环的终止
条件。`channelToArray(ch)` 把「一直收到关闭为止」这件事写成一行：

```sfl
val out = channel()
val producer = spawn(() -> {
  for (i in range(5)) { send(out, i * i) }
  closeChannel(out)                // 不关就会一直等下去
})

println(channelToArray(out))       // [0, 1, 4, 9, 16]
await(producer)
```

典型的工作队列：多个消费者共享一个任务通道，结果汇总到另一个通道。

```sfl
val jobs = channel()
val results = channel()

val workers = map(range(4), (w) -> spawn(() -> {
  var job = receive(jobs)
  while (job != null) {
    send(results, expensive(job))
    job = receive(jobs)
  }
}))

for (job in allJobs) { send(jobs, job) }
closeChannel(jobs)                 // 让消费者的循环收到 null 而退出
awaitAll(workers)
closeChannel(results)

println(channelToArray(results))
```

### 7.3 同步原语

**互斥锁**。`withLock` 会在函数出错时也把锁释放，应优先使用；`lock` / `unlock` /
`tryLock` 留给更复杂的场合。

```sfl
var total = 0
val m = mutex()

awaitAll(map(range(8), (i) -> spawn(() -> {
  for (j in range(1000)) {
    withLock(m, () -> total = total + 1)
  }
})))
println(total)                     // 8000，一次更新都没丢
```

**原子量**。计数器这类简单场景不必上锁：

```sfl
val counter = atomic(0)
atomicAdd(counter, 1)                       // 返回新值
atomicUpdate(counter, (v) -> v * 2)         // 冲突时自动重试，函数可能被调用多次
atomicCompareSet(counter, 10, 0)            // 值仍是 10 时才写 0
println(atomicGet(counter))
```

**倒数闩**用于「等所有人就绪」，**信号量**用于限制并发数：

```sfl
val ready = latch(3)
for (i in range(3)) { spawn(() -> { setup(); countDown(ready) }) }
awaitLatch(ready, 5000)            // 返回是否在超时内等到

val limit = semaphore(2)           // 最多两个线程同时进入
spawn(() -> {
  acquire(limit)
  try(() -> download(url))
  release(limit)
})
```

### 7.4 共享数据的规则

线程之间传递的是**引用**，不是副本。这几条规则可以避免绝大多数问题：

1. **优先用通道传数据**，而不是共享一个可变的数组或对象。
2. 数组和对象**不是线程安全的**。多个线程同时 `push` 同一个数组会破坏它。确实要共享
   时，用 `withLock` 把每一次访问都保护起来。
3. 只读共享是安全的。要把**数据**交给线程又不担心被改，传 `deepCopy(v)`——
   但请先读下一条，它决定了这一条什么时候不成立。
4. 全局变量被所有线程共享；对同一个全局变量的并发写要用锁或原子量。
5. 闭包捕获的是它定义时的那个作用域。把闭包交给多个线程时，它们共享同一份被捕获的
   变量，规则同上。
6. **`copy` 与 `deepCopy` 拷不动闭包捕获的状态。** 它们复制数组的元素和对象的字段；
   而一个函数值捕获的变量不是字段，复制过去的仍然是同一个函数，指向同一份状态。
   因此"把方法放进对象里"的写法不能靠 `deepCopy` 隔离：

   ```sfl
   def makeCounter(n0) {
     val self = {"n": n0}
     self.inc = () -> { self.n += 1; self.n }   // 闭包捕获的是 self 本身
     self
   }
   val d = deepCopy(makeCounter(0))
   d.inc()          // 改的是原件，不是 d
   ```

   要让每个线程有独立状态，就为每个线程**各调用一次工厂函数**，而不是拷贝一个实例。
   这不是 `deepCopy` 的缺陷——任何动态语言都改写不了闭包已经捕获的环境。

```sfl
// 不好：两个线程同时改一个数组
val shared = []
awaitAll(map(range(2), (i) -> spawn(() -> { for (j in range(1000)) push(shared, j) })))

// 好：各自算各自的，最后合并
val parts = awaitAll(map(range(2), (i) -> spawn(() -> map(range(1000), (j) -> j))))
val merged = flatMap(parts, identity)

// 也好：用锁保护每一次访问
val guarded = []
val m = mutex()
awaitAll(map(range(2), (i) -> spawn(() ->
  { for (j in range(1000)) withLock(m, () -> push(guarded, j)) })))
```

### 7.5 异步 I/O

SFL 的异步模型建立在线程之上,不引入回调或事件循环:**期物就是线程句柄,
就绪就是 poll**。

`poll(handles, timeoutMs?)` 是就绪多路复用:传入连接、服务器、进程、文件句柄
的数组,返回其中**现在**可读(服务器则是可接受连接)的子集,保持传入顺序。
超时参数缺省或为负表示阻塞等待,0 是探测,超时返回空数组。一个线程就能照看
几十个连接:

```sfl
val server = serverSocket(0)
val conns = []
while (true) {
  for (ready in poll([server] + conns)) {
    if (ready == server) then push(conns, accept(server))
    else {
      val line = socketReadLine(ready)
      if (isNull(line)) then remove(conns, indexOf(conns, ready))
      else socketWriteLine(ready, toUpper(line))
    }
  }
}
```

`awaitAny(threads, timeoutMs?)` 等最先完成的线程,返回其下标(超时 -1);
失败的线程也算完成,错误留待 `await` 它时报告。在这两个原语之上,标准库的
async 模块给出期物组合子:

```sfl
val t = async(httpGet, url)            // 新线程上跑,返回线程句柄
val u = andThen(t, (r) -> r.body)      // 完成后接续
println(await(withTimeout(u, 2000)))   // [true, 值] 或 [false, null]

val [idx, v] = race([async(f), async(g)], 1000)   // 最先完成者
for (r in allSettled(ts)) {            // 全部等完,失败不打断
  if (r.ok) then println(r.value) else eprintln(r.error)
}
```

`async(f, ...args)` 就是 `spawn` 的期物风格名字;`then` 是保留字(`if … then`),
接续因此叫 `andThen`。所有这些在编译产物里与解释器逐字节一致——就绪判断的
底层两边都是 `poll(2)`。

### 7.6 子进程与管道

`execute(cmd)` 适合「跑完拿结果」；需要边写边读、与子进程持续交互时用 `processStart`，
它在子进程的三个标准流上建立管道。

```sfl
val p = processStart(["tr", "a-z", "A-Z"])
processWriteLine(p, "hello")
processCloseInput(p)               // 让子进程读到结尾
println(processReadLine(p))        // HELLO
println(processWait(p))            // 0
```

命令可以是字符串（默认经 shell 执行）或 argv 数组（不经 shell，参数不会被再次切分）：

```sfl
processStart("ls -l | wc -l")      // 走 shell，管道符有效
processStart(["echo", "one two"])  // 不走 shell，"one two" 是一个参数
```

与长期运行的子进程一问一答：

```sfl
val calc = processStart(["bc"])
for (expr in ["1 + 1", "6 * 7"]) {
  processWriteLine(calc, expr)
  println(expr + " = " + processReadLine(calc))
}
processCloseInput(calc)
processWait(calc)
```

其余：`processReadErrLine(p)` 读错误流、`processReadAll(p)` 读到流结束、
`processAlive(p)`、`processWait(p, 毫秒)` 超时返回 `null`、`processKill(p, 强制?)`。

> `processReadLine` 会阻塞到有一行为止。如果子进程既不输出也不退出，就会一直等；
> 需要同时照看多个子进程时，把每个交给一个线程，结果汇总到通道。

### 7.7 套接字

TCP 套接字既能在本机进程之间通信，也能跨机器通信。端口传 `0` 让系统分配一个空闲端口，
再用 `serverPort` 查询，测试时特别方便。

```sfl
val srv = serverSocket(0)
val port = serverPort(srv)

val server = spawn(() -> {
  val conn = accept(srv, 5000)     // 超时返回 null
  val line = socketReadLine(conn, 5000)
  socketWriteLine(conn, "echo:" + line)
  closeSocket(conn)
})

val c = connect("127.0.0.1", port)
socketWriteLine(c, "ping")
println(socketReadLine(c, 5000))   // echo:ping
closeSocket(c)
await(server)
closeServer(srv)
```

一个可以并发服务的小服务器：

```sfl
val srv = serverSocket(8080)
while (true) {
  val conn = accept(srv)
  spawn(() -> {
    val req = socketReadLine(conn, 10000)
    socketWriteLine(conn, handle(req))
    closeSocket(conn)
  })
}
```

### 7.8 文件流与具名管道

按行读写文件，不必一次读入全部内容：

```sfl
val w = openFile("out.txt", "w")   // "r" 读（默认）、"w" 覆盖写、"a" 追加
fileWriteLine(w, "第一行")
fileClose(w)

val r = openFile("out.txt")
var line = fileReadLine(r)
while (line != null) {
  println(line)
  line = fileReadLine(r)
}
fileClose(r)
```

具名管道（FIFO）是本机进程之间最轻量的通道。注意打开读端会**阻塞**到有写入者出现，
所以两端要放在不同的线程或不同的进程里：

```sfl
mkfifo("/tmp/sfl.fifo")

// 进程 A
val r = openFile("/tmp/sfl.fifo")
println(fileReadLine(r))

// 进程 B
val w = openFile("/tmp/sfl.fifo", "w")
fileWriteLine(w, "跨进程的一行")
fileClose(w)
```

### 7.9 进程与信号

```sfl
println(pid())                     // 自己的进程号
signalProcess(somePid, SIGTERM())  // 发信号
```

信号常量：`SIGINT()`（2）、`SIGKILL()`（9）、`SIGTERM()`（15）、`SIGHUP()`（1）、
`SIGUSR1()`、`SIGUSR2()`。

---

## 8. 编译为原生代码

除了解释执行，SFL 还可以把程序**编译**成独立的原生可执行文件。编译器生成 LLVM IR，
再交给 clang 汇编链接——也就是构建 SFL 自身所用的那个 clang，因此只要能装 SFL 就能编译。

> 本章是概览。完整参考——每个命令行选项、类型推断规则、运行时与垃圾回收器、
> 标准库与选择性链接、与解释器的一致性保证、全部错误信息、生成代码与实现原理——见
> [《SFL 编译器手册》](SFL-编译器手册.md)。多文件项目的增量构建、测试、打包与
> 部署分发，见[《SFL 项目与构建指南》](SFL-项目与构建指南.md)（`sfl build`）。

编译器接受**完整的语言**：字符串、数组、对象、闭包、高阶函数、异常、迭代器、线程、
通道、子进程、套接字，全部可以编译。只有 `eval` 与 `parse` 不行，因为它们需要解释器
本身，而编译产物并不携带解释器。

### 8.1 用法

```bash
sfl -c program.sfl              # 编译成 ./program
sfl -c program.sfl -o build/app # 指定输出路径
sfl --emit-llvm program.sfl     # 只打印生成的 LLVM IR
sfl -c program.sfl -O3 --keep   # 指定优化级别，并保留 .ll
sfl --build-runtime             # 预先构建运行时归档（首次编译会自动做）
```

编译成功后会在标准错误上报告生成了什么：

```
compiled program.sfl -> /path/to/program
2 specialised, 3 generic function(s); 1 unboxed and 4 boxed global(s); 6 primitive(s), 12 library function(s)
```

### 8.2 能证明就拆箱，不能证明就装箱

机器码需要具体类型，而 SFL 是动态类型的。编译器的做法是**渐进的**：推断能证明某个
表达式一定是整数、浮点数或布尔值时，就编译成裸的机器运算，不分配也不调用运行时；
证明不了时，编译成指向垃圾回收对象的指针，并调用运行时——而运行时做的正是解释器
求值器所做的事。

两条路径在同一个函数里可以共存。**类型冲突不是错误**：一个变量先存整数后存字符串，
只意味着它要装箱。

同一个函数被不同类型调用时会**分别生成一份特化**：

```sfl
def twice(x) = x * 2
println(twice(21))     // 一份 int -> int 的特化
println(twice(1.5))    // 一份 float -> float 的特化
```

### 8.3 内置函数的两层实现

367 个内置函数里，272 个是运行时中的 C 原语，95 个用 SFL 自己写成——集合变换、
函数组合子、持久化更新、编解码、正则引擎、HTTP 客户端——预编译进一个归档，程序用到
哪个就链接哪个。哪一层实现对程序不可见：两种写法都答应 `map` 与 `std.map`。

归档缓存在 `~/.cache/sfl` 下，按源码摘要命名，首次构建约 2 秒，之后每次编译约 0.1 秒。

### 8.4 性能

同一份程序，解释执行与编译执行的对比（Apple Silicon，`-O2`，三次运行取均值；
两者输出逐字节比对一致才计入）：

| 程序 | 解释 | 编译 | 提升 |
| --- | --- | --- | --- |
| `fib(32)` 递归 | 0.360s | 0.008s | **45×** |
| 两千万次循环取模 | 0.874s | 0.012s | **73×** |
| 一千万次浮点迭代 | 0.921s | 0.039s | **24×** |
| Collatz 搜索到 30 万 | 2.435s | 0.033s | **74×** |
| 二十万次字符串构造与统计 | 0.134s | 0.057s | **2.4×** |
| 一百万次闭包调用 | 0.074s | 0.022s | **3.4×** |
| 十二万个对象分组求和 | 0.107s | 0.048s | **2.2×** |

前四个的类型能被推断出来，后三个不能。**推断得出机器类型的代码快 20–75 倍，装箱的
代码快 2–3 倍**——后者省掉的是遍历语法树的开销，而不是分配的开销。

复现：`./bench/compile.sh`

### 8.5 一致性

编译产物的输出必须与解释执行**逐字节相同**，包括浮点数的写法、错误消息、help 行、
源码摘录与 traceback。这由差分测试保证：语言自己的测试套件会被编译后运行，输出必须
完全一致。

```bash
./tests/differential.sh     # 把语言自己的套件编译后比对
./tests/compile/run.sh      # 专门的编译器用例
```

少数无法完全对齐的地方（随机数序列、非 ASCII 大小写映射的覆盖范围、正则语法的子集等）
在[编译器手册](SFL-编译器手册.md#8-与解释器的一致性)里逐条列出。

## 9. 内置函数参考

共 367 个内置函数。在 REPL 里用 `:builtins <关键字>`、在脚本里用 `help("<关键字>")`
可以随时查阅同样的内容。

参数名后带 `?` 表示可省略，`...` 表示可接受任意多个参数。

下面的每个**分组名同时是一个命名空间**：`math.abs`、`string.toUpper`、
`io.println` 与不带前缀的写法是同一个函数，而 `std` 一个命名空间装下全部
（`std.abs`、`std.println`）。这两条通道在本文件用自己的定义遮住某个内置函数
之后仍然有效，详见 [5.13](#513-模块导入与命名空间)。

#### 输入输出 (io)

| 函数 | 说明 |
| --- | --- |
| `eprintln(...)` | 输出到标准错误并换行。 |
| `flush()` | 刷新标准输出缓冲区。 |
| `print(...)` | 输出参数，不换行；多个参数以空格分隔。 |
| `printf(fmt, ...)` | 按格式串输出，支持 %s、%d、%.2f 等指示符。 |
| `println(...)` | 输出参数并换行；无参数时输出一个空行。 |
| `readAll()` | 把标准输入全部读入为一个字符串。 |
| `readLine(prompt?)` | 从标准输入读入一行；可传入提示串；读到结尾返回 null。 |

#### 字符串 (string)

| 函数 | 说明 |
| --- | --- |
| `capitalize(s)` | 把首字符转成大写。 |
| `charAt(s, i)` | 取指定下标处的单字符字符串；负数下标从末尾倒数。 |
| `chr(code)` | 由 Unicode 码位生成字符。 |
| `compare(a, b)` | 三路比较，返回 -1、0 或 1。 |
| `contains(v, needle)` | 字符串是否含子串、数组是否含元素、对象是否含键。 |
| `endsWith(s, suffix)` | 是否以指定后缀结尾。 |
| `format(fmt, ...)` | 按格式串生成字符串，用法同 printf。 |
| `indexOf(v, needle, from?)` | 在字符串中找子串、在数组中找元素，返回首个下标，找不到返回 -1。 |
| `isEmpty(v)` | 字符串、数组或对象是否为空。 |
| `join(parts, sep?)` | 把数组连接成字符串，sep 默认为空串。 |
| `lastIndexOf(v, needle)` | 返回最后一次出现的下标，找不到返回 -1。 |
| `length(v)` | 字符串的字符数、数组的元素个数或对象的键个数。 |
| `lines(s)` | 按换行符切分成数组，兼容 LF、CRLF 和 CR。 |
| `ord(s)` | 取首字符的 Unicode 码位。 |
| `padEnd(s, width, pad?)` | 在右侧补齐到指定宽度，默认用空格。 |
| `padStart(s, width, pad?)` | 在左侧补齐到指定宽度，默认用空格。 |
| `repeat(s, n)` | 把字符串重复 n 次。 |
| `replace(s, target, replacement)` | 替换全部字面匹配的子串。 |
| `reverse(v)` | 反转字符串或数组，返回新值。 |
| `slice(v, start, end?)` | 截取字符串或数组的子区间；负数下标从末尾倒数，越界会被裁剪。 |
| `split(s, sep, limit?)` | 按字面分隔符切分；分隔符为空串时按字符切分；limit 限制份数。 |
| `startsWith(s, prefix)` | 是否以指定前缀开头。 |
| `strFind(s, needle, from?)` | 查找子串首次出现的下标，找不到返回 -1；可指定起始位置。 |
| `subString(s, start, end?)` | 截取 [start, end) 的子串；省略 end 时截到末尾。 |
| `substring(s, start, end?)` | subString 的别名。 |
| `toLower(s)` | 转成小写。 |
| `toUpper(s)` | 转成大写。 |
| `trim(s)` | 去掉首尾空白。 |
| `trimEnd(s)` | 去掉末尾的空白。 |
| `trimStart(s)` | 去掉开头的空白。 |
| `utf8Length(s)` | 字符串离开进程时的 UTF-8 字节数——Content-Length 需要的计数;length() 数的是码点。 |

#### 正则表达式 (regex)

| 函数 | 说明 |
| --- | --- |
| `matches(s, pattern)` | 整个字符串是否匹配正则表达式。 |
| `regexFind(s, pattern)` | 返回首个匹配的 [完整匹配, 分组1, …]，没有匹配则返回 null。 |
| `regexFindAll(s, pattern)` | 返回全部匹配，每个匹配是 [完整匹配, 分组1, …]。 |
| `regexReplace(s, pattern, replacement)` | 替换全部正则匹配，替换串中可用 $1 引用分组。 |
| `regexSplit(s, pattern)` | 按正则表达式切分字符串。 |

#### 编码与摘要 (encoding)

| 函数 | 说明 |
| --- | --- |
| `base64Decode(s)` | 解码 Base64 得到 UTF-8 字符串。 |
| `base64DecodeBytes(s)` | 把 Base64 字符串解码为字节数组。 |
| `base64Encode(s)` | 把字符串按 UTF-8 编码后做 Base64 编码。 |
| `base64EncodeBytes(bytes)` | 把字节数组编码为 Base64 字符串。 |
| `digestBytes(algorithm, bytes)` | 对字节数组求摘要，返回字节数组；算法为 "md5"、"sha1" 或 "sha256"。 |
| `hexDecode(s)` | 把十六进制还原成 UTF-8 字符串。 |
| `hexDecodeBytes(s)` | 把十六进制字符串（仅 ASCII 数字与字母）解码为字节数组。 |
| `hexEncode(s)` | 把字符串的 UTF-8 字节转成十六进制。 |
| `hexEncodeBytes(bytes)` | 把字节数组编码为小写十六进制字符串。 |
| `hmacBytes(algorithm, key, message)` | 以字节数组形式的密钥对消息求 HMAC，返回字节数组。 |
| `md5(s)` | 字符串的 MD5 摘要，十六进制表示。 |
| `pbkdf2(algorithm, password, salt, iterations, length?)` | PBKDF2 密钥派生（基于 HMAC）；口令与盐都是字节数组，length 缺省为摘要长度。 |
| `randomBytes(n)` | 从操作系统取 n 个加密安全的随机字节。 |
| `sha1(s)` | 字符串的 SHA-1 摘要，十六进制表示。 |
| `sha256(s)` | 字符串的 SHA-256 摘要，十六进制表示。 |
| `urlDecode(s)` | URL 百分号解码。 |
| `urlEncode(s)` | URL 百分号编码。 |
| `utf8Decode(bytes)` | 把 UTF-8 字节数组解码为字符串；畸形序列变为 U+FFFD。 |
| `utf8Encode(s)` | 把字符串编码为 UTF-8 字节数组（0..255 的整数数组）；未配对代理项编码为 '?'。 |

#### 数学 (math)

| 函数 | 说明 |
| --- | --- |
| `E()` | 自然常数 e。 |
| `INF()` | 正无穷。 |
| `NAN()` | 浮点 NaN。 |
| `PI()` | 圆周率 π。 |
| `abs(x)` | 绝对值。 |
| `acos(x)` | 反余弦，返回弧度。 |
| `asin(x)` | 反正弦，返回弧度。 |
| `atan(x)` | 反正切，返回弧度。 |
| `atan2(y, x)` | 点 (x, y) 的极角，返回弧度。 |
| `cbrt(x)` | 立方根。 |
| `ceil(x)` | 向上取整。 |
| `clamp(x, lo, hi)` | 把数值限制在 [lo, hi] 范围内。 |
| `cos(x)` | 余弦，参数为弧度。 |
| `cosh(x)` | 双曲余弦。 |
| `exp(x)` | 自然指数 e^x。 |
| `floor(x)` | 向下取整。 |
| `gcd(a, b)` | 两个整数的最大公约数。 |
| `hypot(x, y)` | 直角三角形斜边长，计算过程不会溢出。 |
| `isInfinite(x)` | 是否为正负无穷。 |
| `isNaN(x)` | 是否为浮点 NaN。 |
| `lcm(a, b)` | 两个整数的最小公倍数。 |
| `log(x)` | 自然对数。 |
| `log10(x)` | 以 10 为底的对数。 |
| `log2(x)` | 以 2 为底的对数。 |
| `max(...)` | 取多个参数中的最大值；只传一个数组时取数组中的最大值。 |
| `min(...)` | 取多个参数中的最小值；只传一个数组时取数组中的最小值。 |
| `parseFloat(v)` | 解析成浮点数。 |
| `parseInt(v, radix?)` | 把字符串按指定进制解析成整数，或把浮点数截断成整数；进制默认 10。 |
| `pow(x, y)` | 求 x 的 y 次幂。 |
| `random()` | 返回 [0, 1) 区间内的随机浮点数。 |
| `randomInt(lo, hi?)` | 单参数时返回 [0, lo) 内的整数，双参数时返回 [lo, hi) 内的整数。 |
| `randomSeed(n)` | 设置随机数种子，使运行结果可复现。 |
| `round(x, digits?)` | 四舍五入；可指定保留的小数位数。 |
| `sign(x)` | 符号函数，返回 -1、0 或 1。 |
| `sin(x)` | 正弦，参数为弧度。 |
| `sinh(x)` | 双曲正弦。 |
| `sqrt(x)` | 平方根，参数为负数时报错。 |
| `tan(x)` | 正切，参数为弧度。 |
| `tanh(x)` | 双曲正切。 |
| `trunc(x)` | 舍去小数部分。 |

#### 位运算 (bits)

| 函数 | 说明 |
| --- | --- |
| `bitAnd(a, b)` | 按位与。 |
| `bitNot(a)` | 按位取反。 |
| `bitOr(a, b)` | 按位或。 |
| `bitXor(a, b)` | 按位异或。 |
| `bitsToDouble(bits)` | 把 IEEE-754 位模式（整数）还原为浮点数。 |
| `doubleToBits(x)` | 数值的 IEEE-754 位模式（整数）；所有 NaN 都归一到规范 NaN。 |
| `shiftLeft(a, n)` | 左移。 |
| `shiftRight(a, n)` | 算术右移，保留符号位。 |
| `shiftRightU(a, n)` | 逻辑右移，高位补零。 |

#### 类型判断与转换 (type)

| 函数 | 说明 |
| --- | --- |
| `isArray(v)` | 是否为数组。 |
| `isBool(v)` | 是否为布尔值。 |
| `isFloat(v)` | 是否为浮点数。 |
| `isFunction(v)` | 是否为函数或内置函数。 |
| `isInt(v)` | 是否为整数。 |
| `isNull(v)` | 是否为 null。 |
| `isNumber(v)` | 是否为整数或浮点数。 |
| `isObject(v)` | 是否为对象。 |
| `isString(v)` | 是否为字符串。 |
| `isTuple(v)` | 值为元组时返回 true。 |
| `toBool(v)` | 按语言的真值规则转成布尔值。 |
| `toFloat(v)` | 把数字、布尔值或数字字符串转成浮点数。 |
| `toInt(v)` | 把数字、布尔值或数字字符串转成整数。 |
| `toString(v)` | 转成字符串；数组和对象输出 JSON 形式。 |
| `tupleOf(arr)` | 把数组的元素做成一个元组；toArray 的逆操作。元组至少要有两个元素。 |
| `typeOf(v)` | 返回类型名：null、bool、int、float、string、array、object、function 或 iterator。 |

#### 函数组合子 (function)

| 函数 | 说明 |
| --- | --- |
| `apply(f, args)` | 用一个数组作为参数列表调用 f。 |
| `compose(...fns)` | 从右往左组合函数：compose(f, g)(x) 等于 f(g(x))。 |
| `constantly(v)` | 返回一个忽略所有参数、始终返回 v 的函数。 |
| `curry(f, arity?)` | 把 f 柯里化成一串单参数函数；arity 默认取 f 自身的参数个数。 |
| `flip(f)` | 返回一个前两个参数互换的函数。 |
| `identity(x)` | 原样返回参数，常用于 flatMap、sortBy 等需要一个函数占位的地方。 |
| `memoize(f)` | 按参数缓存结果，纯函数对同一输入只会计算一次。 |
| `negate(pred)` | 返回取反后的谓词。 |
| `once(f)` | 最多执行一次，之后的调用直接返回首次的结果。 |
| `partial(f, ...args)` | 绑定前几个参数，返回接收其余参数的函数。 |
| `partialRight(f, ...args)` | 绑定后几个参数，返回接收前面参数的函数。 |
| `pipe(...fns)` | 从左往右组合函数：pipe(f, g)(x) 等于 g(f(x))。 |
| `tap(v, f)` | 调用 f(v) 产生副作用后原样返回 v，便于在管道中间观察数据。 |
| `uncurry(f)` | 把一串单参数函数还原成一次接收全部参数的函数。 |

#### 数组与高阶函数 (array)

| 函数 | 说明 |
| --- | --- |
| `any(arr, pred)` | 是否至少有一个元素满足谓词。 |
| `array(...)` | 用参数构造数组。 |
| `chunk(arr, n)` | 按每 n 个一组切分，最后一组可能不满。 |
| `clear(v)` | 清空数组或对象（原地修改）。 |
| `concat(...)` | 把若干数组和值拼成一个新数组。 |
| `conj(arr, ...values)` | 返回在末尾追加了若干元素的数组副本。 |
| `count(arr, pred)` | 统计满足谓词的元素个数。 |
| `distinctBy(arr, key)` | 按 key(元素) 去重，保留首次出现的元素。 |
| `drop(v, n)` | 去掉前 n 个元素。 |
| `dropWhile(v, pred)` | 跳过满足谓词的前缀；传入迭代器时保持惰性。 |
| `enumerate(v)` | 把每个元素配上下标，得到 [下标, 元素]；传入迭代器时保持惰性。 |
| `every(arr, pred)` | 是否所有元素都满足谓词。 |
| `filter(v, pred)` | 保留使谓词为真的元素；对象的谓词接收 (键, 值)。 |
| `find(arr, pred)` | 返回首个满足谓词的元素，没有则返回 null。 |
| `findIndex(arr, pred)` | 返回首个满足谓词的元素下标，没有则返回 -1。 |
| `first(arr)` | 首个元素，空数组返回 null。 |
| `flatMap(v, f)` | 先映射再展开一层；传入迭代器时保持惰性。 |
| `flatten(arr, depth?)` | 按指定深度展平嵌套数组，深度默认为 1。 |
| `foldRight(arr, f, initial)` | 从右往左折叠，函数签名为 f(元素, 累计值)。 |
| `forEach(v, f)` | 遍历数组元素或对象的 (键, 值)，只为副作用。 |
| `frequencies(arr)` | 统计每个元素出现的次数，以其文本形式为键。 |
| `groupBy(arr, key)` | 按 key(元素) 的返回值分组，得到一个对象。 |
| `insert(arr, i, v)` | 在下标 i 之前插入元素。 |
| `intersperse(arr, sep)` | 在相邻元素之间插入分隔值。 |
| `isRectangular(a)` | 嵌套数组每层每行长度一致时返回 true；shape() 报告的形状因此可信。 |
| `last(arr)` | 末尾元素，空数组返回 null。 |
| `map(v, f)` | 对数组每个元素或对象每个值应用函数，返回新集合。 |
| `maxBy(arr, key)` | 返回 key(元素) 最大的那个元素。 |
| `minBy(arr, key)` | 返回 key(元素) 最小的那个元素。 |
| `ndarray(dims, fill?)` | 构造矩形嵌套数组：ndarray([2, 3], 0) 是 2×3 的全 0 网格；填充值默认 null。 |
| `newArray(n, fill?)` | 构造长度为 n 的数组，每个元素为 fill（默认 null）。 |
| `partition(arr, pred)` | 按谓词拆成 [满足的, 不满足的] 两个数组。 |
| `pop(arr)` | 弹出并返回末尾元素。 |
| `push(arr, ...)` | 在数组末尾追加元素（原地修改），返回该数组。 |
| `range(a, b?, step?)` | 生成整数数组：range(n) 为 0..n-1，range(a,b) 为 a..b-1，可指定步长。 |
| `reduce(arr, f, initial?)` | 从左折叠，函数签名为 f(累计值, 元素)；可指定初值。 |
| `removeAt(arr, i)` | 删除并返回下标 i 处的元素。 |
| `scan(arr, f, initial?)` | 与 reduce 相同，但保留每一步的中间结果。 |
| `shape(a)` | 嵌套数组各层的长度，沿首元素下探：shape([[1,2],[3,4]]) 得 [2, 2]。 |
| `shift(arr)` | 弹出并返回首个元素。 |
| `sort(arr, compare?)` | 排序并返回新数组；可传比较函数，返回负数、零或正数。 |
| `sortBy(arr, key)` | 按 key(元素) 的返回值排序；每个元素的 key 只计算一次。 |
| `sum(arr)` | 数值数组求和，空数组返回 0。 |
| `sumBy(arr, key)` | 对 key(元素) 求和。 |
| `take(v, n)` | 取前 n 个元素。 |
| `takeWhile(v, pred)` | 取满足谓词的前缀；传入迭代器时保持惰性。 |
| `unique(arr)` | 去重，保留首次出现的元素。 |
| `unshift(arr, ...)` | 在数组开头插入元素（原地修改），返回该数组。 |
| `windows(arr, n)` | 取所有长度为 n 的连续滑动窗口。 |
| `zip(a, b)` | 把两个数组按位置配对成 [a, b] 的数组。 |
| `zipWith(a, b, f)` | 用 f 逐位合并两个数组，长度以较短者为准。 |

#### 对象 (object)

| 函数 | 说明 |
| --- | --- |
| `assoc(v, key, value)` | 返回替换了某个键或下标的对象/数组副本，原值不变。 |
| `assocIn(v, path, value)` | 返回替换了嵌套路径处取值的副本，路径上的容器会被逐层复制，其余部分共享。 |
| `copy(v)` | 数组或对象的浅拷贝。 |
| `deepCopy(v)` | 递归拷贝嵌套的数组和对象。不拷贝函数捕获的状态：复制过去的仍是同一个函数，指向同一份状态。 |
| `dissoc(o, ...keys)` | 返回去掉若干键的对象副本。 |
| `entries(o)` | 对象的 [键, 值] 数组。 |
| `fromEntries(pairs)` | 由 [键, 值] 数组构造对象。 |
| `get(v, key, default?)` | 读取对象的键或数组的下标，缺失时返回默认值而不报错。 |
| `getIn(v, path, default?)` | 按键与下标组成的路径取值，路径不存在时返回默认值。 |
| `has(o, key)` | 对象是否含指定键。 |
| `keys(o)` | 对象的键数组，保持插入顺序。 |
| `merge(...)` | 合并多个对象为新对象，后者覆盖前者。 |
| `object()` | 构造一个空对象。 |
| `remove(o, key)` | 删除键并返回其值，键不存在时返回 null。 |
| `set(v, key, value)` | 写入对象的键或数组的下标（原地修改），返回该容器。 |
| `size(v)` | 对象、数组或字符串的元素个数。 |
| `updateIn(v, path, f)` | 与 assocIn 相同，但新值由 f(旧值) 计算得到。 |
| `values(o)` | 对象的值数组。 |

#### 迭代器 (iterator)

| 函数 | 说明 |
| --- | --- |
| `cycle(arr)` | 无限循环一个数组的元素。 |
| `hasNext(it)` | 迭代器是否还有下一个元素。 |
| `iter(v)` | 为数组的元素、对象的键或字符串的字符创建迭代器。 |
| `iterNext(it)` | 取出下一个元素并前进；迭代器耗尽时报错。 |
| `iterate(f, seed)` | 生成 seed、f(seed)、f(f(seed))… 的无限惰性迭代器。 |
| `naturals(start?)` | 从 start（默认 0）开始无限递增的惰性迭代器。 |
| `repeatedly(v)` | 无限重复同一个值的惰性迭代器。 |
| `toArray(v)` | 把迭代器求值成数组。无限迭代器必须先经 take() 截断。 |

#### JSON (json)

| 函数 | 说明 |
| --- | --- |
| `jsonParse(text)` | 把 JSON 文本解析成 SFL 的值。 |
| `jsonStringify(v, pretty?)` | 把值序列化成 JSON；第二个参数为真时输出缩进格式。 |

#### 文件系统 (fs)

| 函数 | 说明 |
| --- | --- |
| `absPath(path)` | 路径的绝对规范形式。 |
| `appendFile(path, content)` | 把内容追加到文件末尾。 |
| `baseName(path)` | 路径的最后一段。 |
| `copyFile(from, to)` | 复制文件，覆盖目标。 |
| `dirName(path)` | 路径去掉最后一段后的部分。 |
| `exists(path)` | 路径是否存在。 |
| `fileMtime(path)` | 文件最后修改时间（毫秒时间戳）；文件不存在返回 -1。构建工具据此做增量编译。 |
| `fileSize(path)` | 文件字节数，文件不存在时返回 -1。 |
| `isDir(path)` | 路径是否是目录。 |
| `isFile(path)` | 路径是否是普通文件。 |
| `joinPath(...)` | 用系统分隔符拼接路径片段。 |
| `listFiles(dir)` | 列出目录下的条目名并排序；路径不是目录时返回 null。 |
| `mkdirs(path)` | 创建目录，必要时一并创建上级目录。 |
| `moveFile(from, to)` | 重命名或移动文件。 |
| `readFile(path)` | 按 UTF-8 读入整个文件。 |
| `readLines(path)` | 读入文件并按行切分成数组。 |
| `removeFile(path)` | 删除文件，删掉了返回 true。 |
| `tempFile(suffix?)` | 创建一个空的临时文件并返回其路径。 |
| `writeFile(path, content)` | 写入文件，已存在则覆盖。 |

#### 系统与进程 (sys)

| 函数 | 说明 |
| --- | --- |
| `args()` | 脚本名之后的命令行参数。 |
| `cwd()` | 当前工作目录。 |
| `exePath()` | 当前运行的 sfl 二进制的绝对路径；平台无法提供时返回 null。构建工具用它调用自身。 |
| `execute(command, useShell?)` | 执行命令并返回 {code, out, err}；默认经由 shell 执行。 |
| `exit(code?)` | 以指定退出码结束程序。 |
| `getEnv(name, default?)` | 读取环境变量，不存在时返回默认值。 |
| `osName()` | 操作系统名称。 |
| `passthrough(cmd, args?)` | 运行外部程序并让其直接使用本进程的标准输入/输出/错误，返回退出码。与 execute() 不同，输出不被捕获而是实时显示，子进程可以交互。 |
| `scriptPath()` | 正在运行的脚本的绝对路径；在 REPL 中返回 null。 |
| `setEnv(name, value)` | 设置环境变量，对本进程及其子进程生效。 |
| `sleep(ms)` | 暂停指定的毫秒数。 |
| `uuid()` | 生成一个随机 UUID 字符串。 |

#### 时间 (time)

| 函数 | 说明 |
| --- | --- |
| `formatTime(millis, format?, utc?)` | 按 strftime 指示符格式化时间戳，默认格式 '%Y-%m-%d %H:%M:%S'。 |
| `timeMillis()` | Unix 纪元以来的毫秒数。 |
| `timeNanos()` | 单调递增的纳秒计数，适合测量耗时。 |
| `timeParts(millis, utc?)` | 把时间戳拆成 {year, month, day, hour, minute, second, weekday, yearday, millis}。 |

#### 网络 (net)

| 函数 | 说明 |
| --- | --- |
| `httpGet(url, headers?)` | 发起 HTTP GET，返回响应体字符串；可传入请求头对象。 |
| `httpGetProxy(url, proxy)` | 经由形如 '127.0.0.1:8080' 的代理发起 HTTP GET。 |
| `httpPost(url, body, contentType?, headers?)` | 发起 HTTP POST，返回响应体字符串。 |
| `httpRequest(method, url, body?, contentType?, headers?)` | 发起任意方法的 HTTP 请求，返回 {status, body}。 |

#### 线程 (thread)

| 函数 | 说明 |
| --- | --- |
| `allSettled(ts)` | 等待所有线程,按顺序返回 {"ok": true, "value": v} 或 {"ok": false, "error": 消息} 的数组;不会因单个失败而中断。 |
| `andThen(t, f)` | 返回一个新线程:等待 t 完成后把结果交给 f。链式后续计算。 |
| `async(f, ...args)` | 在新线程上调用 f(...args),返回线程句柄;spawn 的期物风格名字,配合 andThen/race/withTimeout 使用。 |
| `await(t, timeoutMs?)` | 等待线程结束并返回它的返回值；线程内出错则在当前线程重新抛出。 |
| `awaitAll(threads, timeoutMs?)` | 等待一组线程，按原顺序返回它们的返回值。 |
| `awaitAny(threads, timeoutMs?)` | 等待线程数组中最先完成的一个,返回其下标;带超时(毫秒)时超时返回 -1。失败的线程也算完成,其错误留待 await 该线程时报告。 |
| `cpuCount()` | 可用的处理器个数，可作为线程数的默认值。 |
| `currentThreadName()` | 当前线程的名字。 |
| `isAlive(t)` | 线程是否仍在运行。 |
| `parallelMap(arr, f, workers?)` | 把 f 并行地作用到数组每个元素上，结果保持原顺序；可指定线程数。 |
| `race(ts, timeoutMs?)` | 等待线程数组中最先完成者并取其结果,返回 [下标, 值];带超时(毫秒)时超时返回 null。 |
| `spawn(f, ...args)` | 在新线程上执行 f(...args)，返回线程句柄；程序退出前会等待它结束。 |
| `spawnDaemon(f, ...args)` | 与 spawn 相同，但该线程不会阻止程序退出，适合无限循环的后台任务。 |
| `threadFailed(t)` | 线程是否以错误结束；具体信息由 await() 抛出。 |
| `threadName(t)` | 线程的名字。 |
| `withTimeout(t, ms)` | 在限时内等待线程 t:按时完成返回 [true, 值],超时返回 [false, null]。 |
| `yieldThread()` | 提示调度器可以切换到其它线程。 |

#### 通道 (channel)

| 函数 | 说明 |
| --- | --- |
| `channel(capacity?)` | 创建一个通道；给出容量则为有界通道，满时 send 会阻塞。 |
| `channelClosed(ch)` | 通道是否已关闭。 |
| `channelDrain(ch)` | 不等待地取走当前排队的全部元素。 |
| `channelSize(ch)` | 通道中当前排队的元素个数。 |
| `channelToArray(ch)` | 持续接收直到通道关闭并取空，按顺序返回全部元素。 |
| `closeChannel(ch)` | 关闭通道并唤醒所有等待者；已入队的值仍可继续取出。 |
| `receive(ch, timeoutMs?)` | 从通道取值并阻塞等待；通道关闭且取空、或等待超时时返回 null。 |
| `send(ch, value)` | 向通道投递一个值；有界通道满时阻塞，通道已关闭则报错。 |
| `tryReceive(ch)` | 非阻塞地取值，通道为空时返回 null。 |

#### 同步原语 (sync)

| 函数 | 说明 |
| --- | --- |
| `acquire(s, timeoutMs?)` | 获取一个许可，必要时等待，返回是否拿到。 |
| `atomic(initial?)` | 创建一个可被多线程无锁更新的引用。 |
| `atomicAdd(a, n)` | 对数值做原子加法并返回新值，冲突时自动重试。 |
| `atomicCompareSet(a, expected, v)` | 当前值仍等于 expected 时才写入新值，返回是否写入成功。 |
| `atomicGet(a)` | 读取当前值。 |
| `atomicSet(a, v)` | 覆盖当前值。 |
| `atomicUpdate(a, f)` | 用 f(旧值) 的结果替换当前值，冲突时自动重试，因此 f 可能被调用多次。 |
| `awaitLatch(l, timeoutMs?)` | 等待闩归零，返回是否在超时内等到。 |
| `countDown(l)` | 把闩的计数减一。 |
| `latch(n)` | 创建一个倒数闩，计数归零时放行所有等待者。 |
| `latchCount(l)` | 闩剩余的计数。 |
| `lock(m)` | 获取锁，必要时阻塞。推荐用 withLock，它不会漏掉解锁。 |
| `mutex()` | 创建一把可重入的互斥锁。 |
| `release(s)` | 归还一个许可。 |
| `semaphore(permits)` | 创建一个计数信号量，用于限制同时进入某段代码的线程数。 |
| `tryLock(m, timeoutMs?)` | 在超时内尝试获取锁，返回是否成功。 |
| `unlock(m)` | 释放本线程持有的锁。 |
| `withLock(m, f)` | 持有锁执行 f()，无论 f 是否出错都会释放锁。 |

#### 进程间通信 (ipc)

| 函数 | 说明 |
| --- | --- |
| `SIGHUP()` | 挂断，常被用来表示“重新加载配置”（1）。 |
| `SIGINT()` | 中断信号，即 Ctrl-C 发送的那个（2）。 |
| `SIGKILL()` | 强制杀死，不可捕获（9）。 |
| `SIGTERM()` | 请求终止（15）。 |
| `SIGUSR1()` | 第一个用户自定义信号。 |
| `SIGUSR2()` | 第二个用户自定义信号。 |
| `accept(srv, timeoutMs?)` | 等待一个客户端连接；超时返回 null。 |
| `bufClear(b)` | 清空缓冲区并释放其存储。 |
| `bufNew()` | 新建空字节缓冲区,用于按字节计数的读取;见 socketReadToBuf。 |
| `bufSize(b)` | 缓冲区当前的字节数。 |
| `bufString(b)` | 把整个缓冲区一次性按 UTF-8 解码为字符串。 |
| `closeServer(srv)` | 停止监听。 |
| `closeSocket(c)` | 关闭一个连接。 |
| `closeUdp(u)` | 关闭 UDP 套接字。 |
| `connect(host, port, timeoutMs?)` | 连接到 host:port，返回连接句柄。 |
| `fileClose(f)` | 关闭打开的文件。 |
| `fileFlush(f)` | 刷新缓冲的写入。 |
| `fileReadLine(f)` | 读取一行，文件结束返回 null；用在具名管道上会阻塞到有写入者。 |
| `fileWrite(f, text)` | 向打开的文件写入文本。 |
| `fileWriteLine(f, text)` | 写入一行并刷新，具名管道的读取端可以立刻看到。 |
| `mkfifo(path, mode?)` | 创建具名管道（FIFO），用于与其它进程通信；模式默认 0o600。 |
| `openFile(path, mode?)` | 以流的方式打开文件，模式为 'r'（默认）、'w' 或 'a'。 |
| `pid()` | 当前进程的进程号。 |
| `poll(handles, timeoutMs?)` | 就绪多路复用:传入连接/服务器/进程/文件句柄的数组,返回其中现在可读(或可接受连接)的子集,保持原顺序。超时毫秒数默认 -1 阻塞等待,0 为探测;超时返回空数组。 |
| `processAlive(p)` | 子进程是否仍在运行。 |
| `processCloseInput(p)` | 关闭子进程的标准输入，让它读到结尾。 |
| `processKill(p, force?)` | 终止子进程；第二个参数为真时强制杀死。 |
| `processReadAll(p)` | 读取子进程剩余的全部输出，阻塞到流结束。 |
| `processReadErrLine(p)` | 读取子进程错误流的一行，流结束时返回 null。 |
| `processReadLine(p)` | 读取子进程输出的一行，流结束时返回 null；会阻塞。 |
| `processStart(command, useShell?)` | 启动子进程并在其三个标准流上建立管道；命令可以是字符串或 argv 数组。 |
| `processWait(p, timeoutMs?)` | 等待子进程结束并返回退出码；超时未结束返回 null。 |
| `processWrite(p, text)` | 向子进程的标准输入写入并刷新。 |
| `processWriteLine(p, text)` | 向子进程的标准输入写入一行并刷新。 |
| `serverPort(srv)` | 服务端实际绑定的端口。 |
| `serverSocket(port, backlog?)` | 在 TCP 端口上监听；端口传 0 表示由系统分配，可用 serverPort() 查询。 |
| `signalProcess(pid, signal)` | 向指定进程发送信号，信号值见 SIGTERM() 等。 |
| `socketNoDelay(c, on?)` | 打开(默认)或关闭 TCP_NODELAY:小包立即发送,不等 Nagle 合并。 |
| `socketPeer(c)` | 对端地址，形如 'host:port'。 |
| `socketReadBytes(c, maxBytes, timeoutMs?)` | 从连接读取至多 maxBytes 个原始字节；流结束返回 []，超时返回 null。 |
| `socketReadLine(c, timeoutMs?)` | 读取一行；连接结束或超时返回 null。 |
| `socketReadToBuf(c, b, maxBytes, timeoutMs?)` | 从连接读至多 maxBytes 字节追加进缓冲区:返回读到的字节数,流结束返回 0,超时返回 null。 |
| `socketSendFile(c, path, offset?, length?)` | 从 offset 起把文件的 length 字节直接送下连接(含 TLS),字节不经过值层;返回发送的字节数。 |
| `socketWrite(c, text)` | 向连接写入并刷新。 |
| `socketWriteBuf(c, b)` | 把缓冲区的原始字节原样写入连接,不经解码。 |
| `socketWriteBytes(c, bytes)` | 把字节数组（0..255 的整数）原样写入连接。 |
| `socketWriteLine(c, text)` | 向连接写入一行并刷新。 |
| `tlsAccept(c, certFile, keyFile, alpn?, timeoutMs?)` | 把已 accept 的连接原地升级为服务端 TLS,使用 PEM 证书与私钥;alpn 按优先级列出本服务器支持的协议,协商结果用 tlsProto 读取。 |
| `tlsProto(c)` | TLS 握手最终协商出的 ALPN 协议,未协商则为 null。 |
| `tlsWrap(c, host, caFile?, timeoutMs?, alpn?)` | 把连接原地升级为 TLS:握手并对 host 做证书与主机名校验;caFile 可覆盖信任库,alpn 提供协议列表(协商结果见 tlsProto)。 |
| `udpPort(u)` | UDP 套接字实际绑定的端口。 |
| `udpReceive(u, timeoutMs?)` | 等待一个数据报:返回 {data, host, port},超时返回 null。 |
| `udpSend(u, host, port, text)` | 向 host:port 发送一个数据报。 |
| `udpSocket(port?)` | 打开一个 UDP 套接字并绑定端口;不传端口(或传 0)由系统分配。 |

#### 元编程与错误处理 (meta)

| 函数 | 说明 |
| --- | --- |
| `assert(cond, message?)` | 条件为假时抛出错误。 |
| `attempt(body)` | 执行 body()，成功返回 {ok: true, value: 值}，出错返回 {ok: false, error: {message, kind, file, line, help, trace}}。 |
| `builtins()` | 返回全部内置函数名的数组。 |
| `describe(f)` | 返回内置函数的元信息 {name, group, signature, doc, minArity, maxArity}。 |
| `error(message)` | 抛出一个带指定消息的运行时错误。 |
| `eval(source)` | 执行 SFL 源码字符串，或调用 parse() 生成的函数。 |
| `help(filter?)` | 打印内置函数参考，可传入关键字或分组名过滤。 |
| `parse(source)` | 把 SFL 源码编译成一个无参函数，调用它即执行。 |
| `signature(f)` | 返回函数的声明签名。 |
| `try(body, handler?)` | 执行 body()；出错时调用 handler(错误消息)，未提供 handler 则返回 null。 |


---

## 10. 完整示例

### 统计一个目录下的代码行数

```sfl
def countLines(path) {
  val text = try(() -> readFile(path), (e) -> "")
  length(lines(text))
}

def walk(dir, suffix) {
  val found = []
  for (name in listFiles(dir)) {
    val full = joinPath(dir, name)
    if (isDir(full)) {
      for (sub in walk(full, suffix)) {
        push(found, sub)
      }
    } elsif (endsWith(name, suffix)) {
      push(found, full)
    }
  }
  found
}

val target = if (length(args()) > 0) then args()[0] else "."
val files = walk(target, ".sfl")
var total = 0

for (f in sortBy(files, (p) -> p)) {
  val n = countLines(f)
  total += n
  printf("%6d  %s\n", n, f)
}
printf("%6d  合计（%d 个文件）\n", total, length(files))
```

### 处理 JSON

```sfl
val raw = """
{
  "users": [
    {"name": "张三", "age": 30, "tags": ["admin"]},
    {"name": "李四", "age": 25, "tags": []},
    {"name": "王五", "age": 35, "tags": ["admin", "dev"]}
  ]
}
"""

val data = jsonParse(raw)
val admins = filter(data.users, (u) -> contains(u.tags, "admin"))

println("管理员：" + join(map(admins, (u) -> u.name), "、"))
println("平均年龄：" + round(sum(map(data.users, (u) -> u.age)) / toFloat(length(data.users)), 1))

writeFile("admins.json", jsonStringify(admins, true))
```

### 调用外部命令

```sfl
val r = execute("git rev-parse --short HEAD")
if (r.code == 0) {
  println("当前提交：" + trim(r.out))
} else {
  eprintln("git 执行失败：" + trim(r.err))
  exit(1)
}
```

### 抓取网页并提取标题

```sfl
val html = httpGet("https://example.com")
val m = regexFind(html, "<title>(.*?)</title>")
println(if (isNull(m)) then "没有标题" else m[1])
```

### 简易计时

```sfl
def timeIt(label, body) {
  val t0 = timeNanos()
  val result = body()
  printf("%s 耗时 %.3f ms\n", label, (timeNanos() - t0) / 1000000.0)
  result
}

timeIt("求和", () -> sum(range(1000000)))
```

---

## 11. 环境变量

| 变量 | 作用 |
| --- | --- |
| `SFL_HOME` | `import` 会额外搜索 `$SFL_HOME/libs/`；`sfl pkg` 也把包装在这里 |
| `SFL_MAX_DEPTH` | 非尾调用的最大嵌套深度，超过则报“stack overflow”而不是崩溃，默认 1200；尾调用不受限制 |
| `HOME` | REPL 的历史文件写在 `$HOME/.sfl_history` |
| `NO_COLOR` | 设置后关闭彩色输出 |
| `COLUMNS` | 无法通过终端查询宽度时，用它作为终端列数 |

工具链自己的几个变量在各自的手册里：编译器的归档缓存 `SFL_CACHE`（见
[《编译器手册》](SFL-编译器手册.md)）、包索引 `SFL_REGISTRY`（见
[《项目与构建指南》](SFL-项目与构建指南.md)）、GUI 的 `SFL_GUI_BROWSER` 与
`SFL_GUI_NO_WINDOW`（见[《软件包参考》](SFL-软件包参考.md)）。

---

## 12. 已知限制

- **整数除法**：`int / int` 结果为整数，这与 C、Java、Go 一致，但与 Python 3 不同。
- **非尾递归有深度上限**：默认 1200 层，用 `SFL_MAX_DEPTH` 可以调高；尾位置的调用不
  受限制，见 [5.11](#511-递归与尾调用)。
- **没有 `try`/`catch` 语法**：错误处理通过 `try(body, handler)` 内置函数完成。
- **数组和对象不是线程安全的**：跨线程共享可变容器必须自行加锁，见
  [7.4](#74-共享数据的规则)。
- **`copy` / `deepCopy` 拷不动闭包捕获的状态**：它们复制字段，而闭包捕获的变量不是字段。
  要独立的状态，就各调用一次工厂函数，见 [7.4](#74-共享数据的规则) 第 6 条。
- **`jsonStringify` 只接受有 JSON 形式的值**：遇到函数、迭代器或运行时句柄会报错并指出
  是哪个字段，而不是输出读不回来的文本。
- **`processReadLine` 等 IPC 操作都是阻塞的**：需要同时等待多路输入时，用线程加通道，
  语言本身没有 select/poll 这样的多路复用原语。
- **信号只能发不能收**：可以用 `signalProcess` 发送信号，但脚本无法注册信号处理函数。
- **正则**：由 Scala Native 的 `java.util.regex` 实现（基于 RE2），不支持反向引用和
  环视断言。
- **网络**：HTTP 客户端就是标准库源码（stdlib/http.sfl）。它沿用 curl 时代的行为契约：
  单次调用 60 秒总预算、最多跟随 32 次重定向、每个请求 `Connection: close`；代理来自
  `httpGetProxy` 的参数或 curl 家的环境变量（`all_proxy`/`ALL_PROXY`、`https_proxy`、
  仅小写的 `http_proxy`，`no_proxy` 豁免）。不再声明 `Accept-Encoding`，因此合规服务器
  会返回未压缩的响应体；HTTPS 校验证书链与主机名，信任库来自系统（亦尊重
  `SSL_CERT_FILE`/`SSL_CERT_DIR` 环境变量）。套接字支持 TCP（`tlsWrap` 可原地升级 TLS）
  与 UDP（`udpSocket` 一族），没有 Unix 域套接字。
- **惰性序列不能自动截断**：对无限序列直接 `toArray` 会一直运行，需要自行用 `take`
  或 `takeWhile` 划定范围。
- **`eval` 与 `parse` 无法编译**：它们需要解释器本身，而编译产物并不携带解释器。
  语言的其余部分——包括数组、对象、闭包、线程与 IPC——都可以编译，详见
  [8. 编译为原生代码](#8-编译为原生代码)。编译产物与解释执行还有几处已知的细微差别
  （随机数序列、非 ASCII 大小写映射、正则语法子集），在
  [编译器手册](SFL-编译器手册.md#8-与解释器的一致性)里逐条列出。
- **多线程有固定开销**：为支持线程，运行时启用了安全点等机制，全局变量表也多了一层
  间址，单线程脚本因此比不支持线程的构建慢约 5%（实测 fib 13.2→13.8 ms、
  循环 39.9→41.9 ms）。多核并行带来的收益通常远大于此。
