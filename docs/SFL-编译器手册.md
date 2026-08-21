# SFL 编译器手册

**SFL Compiler**，随 SFL 0.5.1 发布

SFL 除了解释执行，还可以把程序**提前编译**成独立的原生可执行文件。编译器生成 LLVM IR，
交给 clang 汇编链接，再与一份 SFL 运行时静态链接。产物是一个不依赖 `sfl` 的二进制文件。

编译器接受**完整的语言**。类型能被证明的代码编译成裸的机器运算，比解释执行快
**20 到 75 倍**；证明不了的代码——字符串、数组、对象、闭包——编译成对运行时的调用，
快 **2 到 3 倍**。只有 `eval` 和 `parse` 无法编译，因为它们需要解释器本身。

本手册是编译器的完整参考。语言本身请看[《SFL 使用手册》](SFL-使用手册.md)。

---

## 目录

1. [概述](#1-概述)
2. [快速开始](#2-快速开始)
3. [命令行参考](#3-命令行参考)
4. [语言覆盖范围](#4-语言覆盖范围)
5. [类型推断与拆箱](#5-类型推断与拆箱)
6. [运行时](#6-运行时)
7. [标准库与选择性链接](#7-标准库与选择性链接)
8. [与解释器的一致性](#8-与解释器的一致性)
9. [错误信息参考](#9-错误信息参考)
10. [生成的代码](#10-生成的代码)
11. [性能](#11-性能)
12. [故障排查](#12-故障排查)
13. [实现原理](#13-实现原理)

---

## 1. 概述

### 能证明就拆箱，不能证明就装箱

SFL 是动态类型语言，而机器码需要具体类型。编译器的做法是**渐进的**：

- 推断能证明某个表达式一定是整数、浮点数或布尔值时，它编译成裸的 `i64` / `double` /
  `i1` 运算，不分配、不调用运行时；
- 证明不了时，它编译成 `SflVal`——一个指向垃圾回收对象的指针——并调用运行时，而
  运行时实现的正是解释器求值器所做的事。

两条路径在同一个函数里可以共存：一个局部变量是 `i64`，隔壁那个是装箱值。这就是为什么
数值循环仍然跑在 C 的速度上，而它下面三行的闭包一样能编译。

**冲突不是错误**。一个变量先存整数后存字符串，只是意味着这个变量要装箱，而不是程序
被拒绝。编译器唯一会拒绝的，是 `eval` 与 `parse`。

### 架构

```
                     ┌──────────────────────────────┐
   program.sfl ────► │  词法 → 语法 → 名字解析       │  与解释器完全相同的前端
                     └──────────────┬───────────────┘
                                    │  已解析的 AST
                     ┌──────────────▼───────────────┐
                     │  类型推断（不动点）           │  证明得了 → 机器类型
                     │  单态化                       │  证明不了 → Dyn
                     └──────────────┬───────────────┘
                                    │
                     ┌──────────────▼───────────────┐
                     │  发射 LLVM IR                 │
                     └──────────────┬───────────────┘
                                    │  program.ll
                     ┌──────────────▼───────────────┐
   libsflrt.a  ────► │  clang                        │ ────► 可执行文件
   libsflstd.a ────► │                               │
                     └──────────────────────────────┘
```

前端与解释器**共用同一套代码**，所以两者不可能对语法产生分歧。而语义的一致由差分
测试保证：编译产物的输出必须与解释执行逐字节相同（[§8](#8-与解释器的一致性)）。

---

## 2. 快速开始

```sfl
// wordcount.sfl —— 字符串、数组、对象、闭包、高阶函数，全部可编译
def wordsOf(text) = filter(regexSplit(toLower(text), "[^a-z']+"), (w) -> !isEmpty(w))

val counts = frequencies(wordsOf(readAll()))
val top = take(sortBy(entries(counts), (e) -> -e[1]), 5)

for (pair in top) {
  println(padEnd(pair[0], 16) + pair[1])
}
```

```bash
$ sfl -c wordcount.sfl -o wordcount
compiled wordcount.sfl -> /path/to/wordcount
0 specialised, 3 generic function(s); 0 unboxed and 4 boxed global(s); 6 primitive(s), 12 library function(s)

$ ./wordcount < README.md
the             142
a                87
to               76
```

第一次编译会先构建运行时归档（约 2 秒，见 [§7](#7-标准库与选择性链接)）；之后每次编译
约 0.1 秒。

---

## 3. 命令行参考

### 编译

| 选项 | 含义 |
| --- | --- |
| `-c <文件>`，`--compile <文件>` | 编译脚本为原生可执行文件 |
| `-o <路径>` | 可执行文件的写出位置 |
| `--emit-llvm` | 打印生成的 LLVM IR，不构建 |
| `-O0` … `-O3` | 传给 clang 的优化级别，默认 `-O2` |
| `--keep` | 保留生成的 `.ll` 文件在可执行文件旁边 |
| `-e '<代码>'` | 编译命令行上给出的代码，而不是文件 |

### 归档缓存

| 选项 | 含义 |
| --- | --- |
| `--build-runtime` | 立即构建运行时与标准库归档，并打印缓存目录 |
| `--clean-cache` | 删除缓存的归档，下次编译时重新构建 |

在构建机上先执行一次 `sfl --build-runtime`，可以让后续编译不必承担这笔一次性开销。

### 项目级构建

逐文件的 `-c` 之上是 `sfl build`：按 `build.sfl` 描述的项目做增量编译（本节的
编译选项由它代调，优化级别来自项目文件的 `opt` 字段），并提供 run/test/pkg 等
任务。见[《SFL 项目与构建指南》](SFL-项目与构建指南.md)。

### 编辑器与协议子命令

| 子命令 | 含义 |
| --- | --- |
| `sfl check [--json] <文件…>` | 只解析不执行；`--json` 输出带 file/line/col/span/notes 的诊断，编辑器与 CI 用 |
| `sfl syntax` | 关键字、操作符、内置函数元数据（JSON）；`tools/gen-editor-syntax.sfl` 由此生成两个编辑器插件的词表 |
| `sfl lsp` | 语言服务器协议（stdio）：诊断、补全、悬停、签名提示 |
| `sfl dap [--socket]` | 调试适配器协议（解释器模式）：断点、单步、多线程、变量、求值；`--socket` 供 IntelliJ（LSP4IJ）使用，先打印 `DAP server listening on port <N>` 再在该端口上服务 |

### 输出去向

`-o` 未给出时，可执行文件写到**当前目录**，名字是脚本的基名去掉 `.sfl`：

```bash
$ cd /tmp && sfl -c ~/src/tools/report.sfl
# 产生 /tmp/report
```

编译成功后的说明写到 **stderr**，`--emit-llvm` 的 IR 写到 **stdout**，因此两者都能
直接重定向：

```bash
sfl --emit-llvm program.sfl > program.ll     # 只有 IR
sfl -c program.sfl 2>/dev/null               # 静默编译
```

### 退出码

| 码 | 含义 |
| --- | --- |
| `0` | 编译成功 |
| `1` | 语法错误、编译器拒绝、或链接失败 |
| `2` | 命令行参数有误（例如 `-c` 后面没有文件） |
| `66` | 指定的脚本不存在 |

### 环境变量

| 变量 | 作用 |
| --- | --- |
| `SFL_CACHE` | 归档缓存的位置，默认 `~/.cache/sfl` |
| `SFL_MAX_DEPTH` | 记录 traceback 的最大调用深度 |
| `SFL_HOME` | `import` 搜索的基目录，编译时与解释时一致 |

---

## 4. 语言覆盖范围

### 全部语法

| 构造 | 说明 |
| --- | --- |
| `val` / `var`，全局与局部 | 推断得出机器类型时不装箱 |
| `int` `float` `bool` `string` `null` `array` `object` `function` `iterator` | 全部支持 |
| 所有运算符 `+ - * / % == != < <= > >= && \|\| ! -x` | 语义与解释器完全一致 |
| `if` / `elsif` / `else`，作为语句与作为表达式 | |
| `while`，`for (x in ...)`，`break`，`continue` | `for` 可遍历数组、对象、字符串、迭代器 |
| `select` / `case` / `default` | 任意值比较，不限于数字 |
| `def`，默认参数，`...rest` 参数 | 参数个数错误的报错文本一致 |
| 嵌套 `def`、lambda `(x) -> e` 与 `{ (x) -> ... }` | 完整闭包 |
| 逐轮捕获的循环闭包 | 每次迭代一个独立的活动记录 |
| 尾调用 | 常量栈空间，与解释器同样无界 |
| `return`，包括从深层嵌套返回 | |
| `\|>` 管道运算符 | |
| 数组 / 对象字面量、下标、`o.field`、`a[i] += x` | 任意深度 |
| `import` | 解析期内联，与解释时相同 |
| `error` / `try` / `attempt` / `assert` | 消息、help 行、traceback 全部一致 |
| 线程、通道、互斥量、原子量、闩锁、信号量 | 真正的 OS 线程 |
| 子进程、套接字、文件句柄、命名管道、信号 | |

### 内置函数

| 类别 | 数量 | 实现方式 |
| --- | --- | --- |
| C 原语 | 274 | 运行时（`runtime/*.c`） |
| SFL 标准库 | 91 | SFL 自身，预编译进 `libsflstd.a` |
| 无法编译 | 2 | `eval`、`parse` |

一个内置函数属于哪一类，对程序不可见：`typeOf`、`signature`、`describe`、`builtins()`、
`help()` 对两者的回答一致——**包括那两个编译不了的**：编译产物的内置表里也有它们的
名字、分组、签名与文档，只是没有代码指针；引用它们在编译期就被拒绝，所以运行时永远
够不着那一行。

### 新增语言特性的编译方式

**模式匹配零成本进入编译器**:模式在解析期就展开成普通的判断、下标与赋值节点,
编译器看到的与解释器执行的是同一棵树,不存在"模式匹配的代码生成"这回事。`case`
全为字面量的 select 保持专门的比较链。

**元组**是运行时的一种值(`SFL_TUPLE`),字面量经 `sfl_tuple_of` 构造;相等、比较、
迭代、下标、渲染都在 C 运行时里与解释器逐字对齐,由差分测试(tests/tuples.sfl,
34 条断言)钉住。

**多维下标** `a[i, j]` 在解析期降为 `a[i][j]`,编译器无感知。

**标准库自身也吃这份红利**:0.6.0 把标准库改写到新特性上(热路径的 if 链换成
字面量 `select`、错误消息插值化、单参 lambda 去括号),行为由 236 条对照断言与
差分测试冻结;正则引擎的编译执行由此快了约 25%(select 的比较链对未装箱主体
的代码生成优于逐次 if 的重复取值)。对象模式刻意不进热路径——每个分支多付
isObject+has+get,慢于一次下标加比较。

### 只有两处例外

```sfl
println(eval("1 + 1"))
```

```
Compile error: 'eval' is not available in compiled programs
  --> program.sfl:1:14
  |
1 | println(eval("1 + 1"))
  |              ^
  help: it needs the interpreter itself, which a compiled binary does not carry
```

需要在运行期构造并执行代码时只能用解释器。多数用途可以改写：

| 想做的事 | 编译得了的写法 |
| --- | --- |
| `eval("f(" + x + ")")` | 直接 `f(x)`——SFL 的函数本来就是值 |
| 按名字分派 | 用对象存函数：`val ops = {"add": (a, b) -> a + b}`，再 `ops[name](1, 2)` |
| 读配置里的表达式 | `jsonParse` 读数据，用数据驱动分支 |
| 插件 | 用 `processStart` 起一个 `sfl` 子进程 |

---

## 5. 类型推断与拆箱

### 推断做什么

编译前会对整个程序做一次不动点推断，为每个局部变量、每个全局变量、每个函数的返回值
求一个类型。格是：

```
        Dyn                    装箱的运行时值
      /  |  \
   I64  F64  Bool              机器类型
      \  |  /
      Unknown                  还没有信息
```

`join(I64, F64) = F64`（整数被提升）；其余任何冲突都上升为 `Dyn`。

### 什么时候会拆箱

```sfl
def area(w, h) = w * h          // area(3, 4)   → i64，裸乘法
                                // area(1.5, 2) → double
```

同一个函数被不同类型调用时，编译器为每种组合生成一份**特化**。编译摘要会报告数量：

```
2 specialised, 0 generic function(s); 3 unboxed and 0 boxed global(s); ...
```

### 什么时候会装箱

- 变量在不同地方存过不同类型；
- 值来自数组、对象、字符串，或任何返回动态值的内置函数；
- 函数作为值传递，或被当作闭包调用；
- 函数有 `...rest` 参数或默认参数；
- 函数体内创建了闭包（它的活动记录必须放在堆上）。

### 写得快一些

```sfl
val n = 1000

// 会拆箱：类型自始至终一致
var squares = 0
for (i in range(n)) { squares += i * i }
println(squares)

// 不会：sum 一开始是整数，后来变成字符串
var sum = 0
for (i in range(n)) { sum += i }
sum = "总计：" + sum               // 整个 sum 变成 Dyn
println(sum)
```

改法是换一个变量：

```sfl
val n = 1000
var sum = 0
for (i in range(n)) { sum += i }
val label = "总计：" + sum         // sum 仍然是 i64
println(label)
```

这只影响速度，不影响正确性——两种写法的结果完全相同。

### 哪些内置函数会被内联

编译器为 16 个数值内置函数直接生成机器指令，前提是参数类型已知：
`abs` `min` `max` `exp` `sin` `cos` `floor` `ceil` `round` `trunc` `sign` `pow`
`gcd` `toInt` `toFloat` `timeMillis`。`println` 的参数全是数字或布尔值时也会走直接
打印，不构造字符串对象。

内联只在**逐位产生与原语相同结果**时才做。`min(1, 2.5)` 在解释器里返回整数 `1`，而一份
特化只有一种返回类型，所以混合情形交回原语；`sqrt` 与 `log` 需要检查定义域，也交回原语。

---

## 6. 运行时

编译产物链接的是 `libsflrt.a`——约 6 千行 C，源码在 `runtime/`，可以单独阅读和编译
（`runtime/build.sh`）。它嵌在 `sfl` 二进制里，第一次编译时写入缓存目录并构建。

### 值模型

每个动态值是一个指向堆单元的指针。单元大小统一，变长部分（字符串的码元、数组的元素）
挂在单元拥有的旁路缓冲上，随单元一起释放。

**字符串是 UTF-16 码元序列**，与解释器的 Java 字符串一致。这不是实现细节：它决定了
`length("héllo")` 是 5、`s[1]` 取到哪个字符，以及非 ASCII 文本上的一切下标行为，两边
必须相同。

**对象保持插入顺序**，`keys()`、`for (k in o)` 与 JSON 输出的次序都与解释器一致；条目
数超过阈值后自动建立哈希索引，查找不再是线性的。

### 垃圾回收

标记—清除回收器，**精确遍历对象图**（每个标签的布局都是已知的），**保守寻找根**
（扫描机器栈与被调用者保存的寄存器）。这样生成的代码不必维护任何影子栈：一个跨调用
存活的值，要么被 ABI 压到栈上，要么留在 `setjmp` 能捕获的寄存器里。

- 触发：距上次回收分配的字节数超过阈值，阈值随存活量自适应；
- 多线程：回收前用信号**停止世界**，逐个记录其他线程的栈顶与寄存器，扫描完再放行。
  信号处理器使用 `SA_RESTART`，被打断的系统调用会自动重启；
- 分配在多线程期间加锁，单线程时只多一次可预测的分支。

一个持续分配 40 万个对象与字符串的循环，常驻内存稳定在约 5 MB。

### 错误

`error()`、下标越界、类型错误等全部通过 `setjmp` / `longjmp` 抛到最近的处理器，也就是
`try()` 与 `attempt()` 压入的那个。没有处理器时，程序把错误渲染到 stderr 并以 1 退出。

**程序自己的源文本被链接进了二进制**，所以编译产物能引用出错的那一行并画出插入符号，
与解释器的输出逐字节相同：

```
Runtime error: array index 10 is out of bounds (length 2)
  --> program.sfl:2:5
  |
2 |   xs[10]
  |     ^
  help: valid indices are 0 to 1, or -1 to -2 counting from the end
  traceback (most recent call first):
    at risky (program.sfl:5)
```

未定义的名字同样会给出拼写建议，搜索范围是内置函数与**出错的那个名字所在命名空间**
能看到的名字——与解释器搜索的集合、遍历顺序、编辑距离上限都一致；名字本身永远不会
被建议回去。

### 栈深度

编译出的活动记录比解释器的小得多，所以递归能走得更深。会调用其他函数的编译函数会检查
剩余栈空间（三条指令，不写内存），耗尽时抛出可捕获的 `call stack overflow`，而不是崩溃。

尾调用不占栈：运行时里有一个蹦床，尾位置的调用把目标停在一边返回，由调用循环接手——
与解释器的做法相同。

### 线程

`spawn` 创建真正的 OS 线程，栈 8 MB。每个线程有自己的错误处理器栈与 traceback，并向
回收器注册自己的栈边界。通道、互斥量、信号量、闩锁、原子量都建立在 pthread 上。

---

## 7. 标准库与选择性链接

### 两层实现

- **C 原语**（238 个）——需要真正原语才能做的事：I/O、系统调用、字符串码元操作、
  分配、时间、哈希、JSON；
- **SFL 标准库**（81 个）——可以由前者组合出来的：`map` / `filter` / `reduce` /
  `groupBy` 一类的集合变换，`compose` / `curry` / `memoize` 一类的函数组合子，
  `assoc` / `getIn` 一类的持久化更新，Base64 与 URL 编解码，以及一个用 SFL 写的回溯
  正则引擎。

标准库的源码就在 `stdlib/`，是普通的 SFL：

```sfl
// stdlib/coll.sfl 节选
def filter(v, pred) {
  val kind = __mapKind("filter", v)
  __fn("filter", 2, pred)
  if (kind == "iterator") { ... }
  val out = []
  for (x in v) { if (pred(x)) { push(out, x) } }
  out
}
```

用 SFL 写这一层不只是省了几千行 C。它意味着这些实现能被**与解释器原生版本同一套测试**
检验。0.8.0 之前靠的是"把模块 import 进来遮蔽全局表"；名字按命名空间解析之后，遮蔽
只在写下调用的那个文件里生效，所以检验分成了两条互补的路：

- `tests/stdlib.sfl` 打开七个模块，再用 `eval` 把 `tests/builtins.sfl` 的断言文本
  编进**自己的**命名空间——`eval` 在调用方的命名空间里编译，这正是让那些断言看见
  SFL 实现而不是原生实现的原因（改成 `import` 就会在 builtins.sfl 自己的命名空间里
  解析，静默地把原生实现测两遍）。324 条断言两边必须给出相同的答案。
- `tests/compile/accept/stdlib_impls.sfl` 是编译侧：同一个程序里 `capitalize` 是
  SFL 实现、`std.capitalize` 是原生实现，59 对逐一比对——命名空间让"两个实现同时
  在场"成为可能，比原来的遮蔽把戏更精确，且解释与编译两侧输出必须逐字节一致。

标准库模块是唯一在**根命名空间**里解析的 SFL 源码：它们的公开定义*就是*内置函数，
两个引擎必须在这一点上一致（解释器用 `Parser` 的空命名空间标签，编译器在
`Backend.buildStdlib` 里传同一个）。别的每一份 SFL 源码——你的程序、你的包——
都声明进自己的命名空间，所以没有任何用户代码能落进这张表里。

模块内部以 `_` 开头的名字是私有的（见[使用手册 5.13](SFL-使用手册.md#513-模块导入与命名空间)），
编译器不会把它们导出成 `sflstd_` 符号，它们的函数描述也不导出。这不只是整洁问题：七个
模块里有 130 个私有名字，其中 `_bad`、`_show` 各出现在四个文件里，导出就会在链接时撞在
一起，让一个模块的函数带上另一个模块的名字、参数个数和源码位置。

### 预编译与归档

标准库由 SFL 编译器自己编译，每个模块产生一个目标文件，打包成 `libsflstd.a`：

```
~/.cache/sfl/<版本>-<运行时摘要>-<标准库摘要>/
├── src/            运行时的 C 源码，写出来是为了可读、可调试
├── libsflrt.a      11 个目标文件
├── std/            每个标准库模块一个 .ll 与 .o
├── libsflstd.a     7 个目标文件
└── ready
```

目录名里的摘要来自源码内容，所以改动运行时或标准库会自动换一个新缓存，绝不会链接到
过期的归档；同一台机器上并存的两个 `sfl` 版本也不会互相干扰。

冷缓存构建约 **1.8 秒**，之后编译一个程序约 **0.1 秒**。

### 选择性链接

程序只带上它真正用到的部分，粒度有三层：

1. **符号声明**——编译器只为程序引用到的原语与库函数发射 `declare`；
2. **归档成员**——链接器只从 `.a` 里取出解析得到符号的那些目标文件；
3. **死代码剥离**——`-ffunction-sections` 加上 `-dead_strip`（Linux 上是
   `--gc-sections`）在函数粒度上丢掉够不着的代码。

内置函数表本身由编译器发射进程序，而不是放在运行时里——放在运行时里的表会引用到每一个
原语，那样每个二进制都得带上全部 238 个。表里始终有全部内置函数的**描述**
（`builtins()`、`describe()` 必须看得见它们），但只有程序够得着的才有代码指针。

| 程序 | 二进制大小 |
| --- | --- |
| `println(1)` | 51 KB |
| 集合变换与排序 | 204 KB |
| 上面的 wordcount | 208 KB |

---

## 8. 与解释器的一致性

### 保证

编译产物的 **stdout 必须与解释执行逐字节相同**。这不是期望，是被检验的：

```bash
./tests/differential.sh
```

它把语言自己的测试套件编译后运行，要求输出完全一致：

```
differential: the language's own suites, compiled
  ok    builtins       builtins: 236 passed, 0 failed
  ok    closures       closures: 31 passed, 0 failed
  ok    functional     functional: 117 passed, 0 failed
  ok    imports        imports: 5 passed, 0 failed
  ok    ipc            ipc: 42 passed, 0 failed
```

这比任何一组手写编译器用例都强：这些套件本来就是用来定义语言行为的。另有 23 个专门的
编译器用例在 `tests/compile/`，覆盖闭包、容器、字符串、错误、线程、尾调用、参数默认值，
同样逐字节比对。

浮点数遵循 Java 的 `Double.toString`：最短的往返十进制，`1e-3 ≤ |v| < 1e7` 时按位置
记法，否则用 `E` 记法：

| 值 | 两边都打印为 |
| --- | --- |
| `1.0` | `1.0` |
| `0.1 + 0.2` | `0.30000000000000004` |
| `1e7` | `1.0E7` |
| `1.0 / 0.0` | `Infinity` |
| `-0.0` | `-0.0` |

错误的消息、help 行与 traceback 也逐字节相同（见 [§6](#6-运行时)）。

### 已知的差异

以下几处经过实测，无法或不值得完全对齐。

**随机数序列**。`random()` / `randomInt()` 用的是自带的 xoshiro256\*\* 生成器，解释器
用的是 `scala.util.Random`。同一个 `randomSeed(n)` 在各自内部可复现，但两者的序列不同。

**大小写映射**已不在此列。`toUpper` / `toLower` 的映射表由 `tools/gen-case-tables.py`
直接从解释器逐码点测量生成（`runtime/sfl_case_tables.h`），全部 Unicode 码点——包括
`ß` → `SS` 这类多码元的完整映射、希腊文下标 iota 区、辅助平面以及词尾 sigma 的上下文
规则——均与解释器一致，并由 `tests/builtins.sfl` 里的全码点校验和钉住。若解释器的
Unicode 数据发生变化（例如 Scala Native 升级），重新生成该表并更新校验和即可。

**非 ASCII 数字**。`parseInt`、`toInt`、`jsonParse` 只接受 ASCII 数字；解释器经由
`Character.digit` 也接受阿拉伯-印度数字、全角数字等。

**超越函数的最后一位**。`atan2` 与少数 `pow` 的结果与 Java 相差 1 ulp（Java 自己的规范
也允许 ±1 ulp）。四则运算、比较、取整完全一致。

**正则语法的子集**。标准库里的引擎支持字面量、`.`、字符类与其取反、`\d \w \s \D \W \S`、
锚点 `^ $`、分组与选择 `( | )`、量词 `* + ? {n} {n,} {n,m}` 及其惰性形式、转义元字符。
Java 正则的其余部分（反向引用、前后查找、命名组、`\b`、内联标志）不支持，遇到时会明确
报错指出是哪一个构造，绝不会静默地匹配错。

**递归深度**。编译出的活动记录小得多，因此在解释器已经报栈溢出的深度上，编译产物仍能
继续。两边都会报告失控的递归，只是发生的深度不同。

**`describe` 与 `help` 的元数据**。编译产物携带每个内置函数的名字、签名与参数个数，但
不携带分组和文档串，所以 `describe(f).group` 与 `.doc` 是空的，`help()` 只列签名。

**HTTPS 需要系统 TLS 库**。`httpGet` 一族由标准库的 SFL 源码实现（stdlib/http.sfl，
编译进 libsflstd.a），纯 http 请求零外部依赖。https 经 `tlsWrap` 在运行期 `dlopen`
系统的 OpenSSL/LibreSSL（Homebrew 的 openssl@3 优先，其次 macOS 系统 LibreSSL 与
Linux 的 libssl.so.3）。找不到 TLS 库时调用会报错说明，而不是链接失败。

**子进程句柄不回收**。没有 `processClose`，回收器也不释放句柄的载荷，所以每个子进程句柄
会留下一个结构体与至多两个 8 KiB 缓冲。启动成千上万个子进程的长期程序需要留意。


---

## 9. 错误信息参考

编译期的错误只剩两类。

### 需要解释器的内置函数

```
Compile error: 'eval' is not available in compiled programs
  help: it needs the interpreter itself, which a compiled binary does not carry
```

见 [§4](#4-语言覆盖范围)。

### 构建失败

```
Compile error: the SFL runtime failed to build (sfl_string.c)
  help: <clang 的原始输出>
```

缓存目录里的 C 源码没能编译。这是编译器或工具链的问题，不是你程序的问题：请附上 clang
的输出报告。`sfl --clean-cache` 可以排除缓存损坏的可能。

```
Compile error: clang rejected the generated code
  help: <clang 的原始输出>
  command: <完整的命令行>
```

编译器发出了无效的 IR——这是编译器的缺陷。用 `--keep` 或 `--emit-llvm` 保留 IR 后一并报告。

```
Compile error: cannot find clang, which is needed to assemble the program
  help: install the Xcode command line tools on macOS, or your distribution's clang
```

见 [§12](#12-故障排查)。

### 运行期的错误

编译产物报告的运行期错误与解释器完全一致，包括消息、源码摘录、插入符号、help 行与
traceback。语言手册的错误章节对编译产物同样适用。

---

## 10. 生成的代码

`--emit-llvm` 打印 IR。一个类型已知的函数编译成裸运算：

```sfl
def twice(n) = n * 2
```

```llvm
define internal i64 @sfl_f_twice_2(i64 %arg0) {
entry:
  %t1 = alloca i64
  %t2 = alloca i64
  store i64 0, ptr %t1
  store i64 %arg0, ptr %t2
  %t3 = load i64, ptr %t2
  %t4 = mul i64 %t3, 2
  store i64 %t4, ptr %t1
  br label %ret_exit
ret_exit:
  %t5 = load i64, ptr %t1
  ret i64 %t5
}
```

没有调用、没有分配，`-O2` 之后就是一条移位。

一个闭包则编译成运行时的调用约定：

```llvm
define internal ptr @sfl_g_lambda_5(ptr %env, i64 %argc, ptr %argv) {
entry:
  %t1 = call ptr @sfl_frame_new(i64 2, ptr %env)
  store volatile ptr %t1, ptr %t2        ; 把活动记录钉在栈上，供回收器看见
  %t3 = call ptr @sfl_frame_slots(ptr %t1)
  %t4 = call i64 @sfl_bind_args_raw(ptr %t3, ptr @proto0, i64 %argc, ptr %argv)
  ...
```

模块的结构：

| 段 | 内容 |
| --- | --- |
| 声明 | 程序用到的运行时函数，以及被引用的原语与库函数 |
| `@sfl_slots` | 装箱的全局、字符串字面量、函数值——回收器唯一需要知道的根数组 |
| `@g_<n>` | 拆箱的全局变量 |
| 常量 | C 字符串、函数描述（`%SflProto`）、内置函数表 |
| 函数 | 特化实例与通用入口 |
| `@sfl_main` | 启动代码（注册根、建立字面量、初始化库模块）后接顶层语句 |

`main` 由运行时提供，它记录栈底、装好保护页处理器，然后调用 `sfl_main`。

---

## 11. 性能

`./bench/compile.sh` 每次运行都重新测量，并且**先比对输出再报速度**，所以加速不可能
来自"少做了事"。

```
the first four are statically typed; the last three are not, and pay for boxing

program      interpreted     compiled    speedup  output
fib              0.360s      0.008s      45.0x  2178309
loop             0.874s      0.012s      72.8x  59999997
float            0.921s      0.039s      23.6x  827 562
collatz          2.435s      0.033s      73.8x  230631 442
strings          0.134s      0.057s       2.4x  1578000 1000
closures         0.074s      0.022s       3.4x  1249000000
objects          0.107s      0.048s       2.2x  97 21599820000
```

两端的差距说明了整件事：**推断得出机器类型的代码快 20–75 倍，装箱的代码快 2–3 倍**。
后者没有更快，是因为它做的事情——分配对象、哈希字符串、间接调用——解释器本来也做得
不慢；编译省掉的是遍历语法树的开销，不是分配的开销。

想让热点落到快的一侧，见 [§5](#5-类型推断与拆箱)。

另外，编译产物的**启动**是即时的（没有解析），这对被反复调用的小工具往往比吞吐更重要。

---

## 12. 故障排查

**找不到 clang。** macOS 上安装 Xcode 命令行工具（`xcode-select --install`）；编译器会
通过 `xcrun` 找到与 SDK 匹配的那一个。Linux 上安装发行版的 clang 包。

**第一次编译很慢。** 那是在构建运行时归档。先跑一次 `sfl --build-runtime`。

**修改了 `runtime/` 或 `stdlib/` 却没生效。** 这两个目录的内容在 `sbt nativeLink` 时嵌入
`sfl` 二进制，所以要先重新构建 `sfl`；缓存会因为摘要变化自动重建。

**怀疑缓存坏了。** `sfl --clean-cache`。

**编译产物与解释结果不同。** 这是缺陷，请报告。先用 `./tests/differential.sh` 确认不是
环境问题，再把最小复现和 `--emit-llvm` 的输出一并附上。[§8](#8-与解释器的一致性) 列出了
已知的例外。

---

## 13. 实现原理

### 三趟

**收集**。遍历顶层语句，记下每个 `def` 赋给了哪个全局，其余留作 `sfl_main` 的语句。被
内联进来的 `import` 只是嵌套的语句块，一并展开。

**推断**。从顶层语句与每个被请求的函数实例出发，反复求值类型直到不动点（最多 128 轮）。
一次调用如果参数类型都是机器类型、没有默认参数与 `...rest`、且函数体里没有闭包，就请求
一份**特化**；否则请求**通用入口**。同一趟里还会算出哪些实例**可能抛错**——不可能抛错的
实例既不需要记录 traceback，也不需要更新出错位置，这就是纯算术代码里一条运行时调用都
看不到的原因。

**发射**。为每个实例生成 IR。特化实例的局部变量是 `alloca`，通用入口的活动记录是堆对象
（函数体里有闭包时）或栈上的槽数组（没有时）。工作表一直做到没有新实例为止。

### 闭包

活动记录就是解释器的 `Frame`：一个槽数组加一个指向定义处记录的父指针。`OuterGet(d, s)`
沿父链走 `d` 步再取第 `s` 个槽。函数体里创建闭包的函数，其记录分配在堆上；创建闭包的
循环每轮分配一个子记录，这正是逐轮捕获的由来。

这里有一处必须小心：生成的代码通过记录的**槽数组指针**访问局部变量，拿到指针之后就再
没有东西引用记录对象本身——而回收器是靠扫描栈找根的。所以每个记录会被一条
`store volatile` 钉在栈上。少了这一条，堆压力大的程序会在回收之后读到已被回收的槽。

### 尾调用

尾位置的调用把目标与实参停在运行时的一处，返回一个哨兵值；所有调用点都经过 `sfl_call`，
它看到哨兵就接着调用下一个。一次 `sfl_call` 因此服务整条尾调用链，自递归与互递归都用
常量栈——与解释器 `Call.runBody` 里的蹦床是同一套办法。

### 新增一个内置函数

需要真正原语的，加到运行时：

1. 在 `runtime/primitives.def` 里加一行 `SFL_PRIM(名字, 符号, 签名, 最少, 最多, 模块)`；
2. 在对应的 `runtime/sfl_<模块>.c` 里实现 `SflVal 符号(int64_t argc, SflVal *argv)`；
3. 在解释器里也实现同名内置函数——它才是规范；
4. 写一个用例放进 `tests/compile/accept/`，差分测试会盯着它。

能由已有原语组合出来的，写进 `stdlib/`：写成普通 SFL，加到某个模块里，然后跑
`tests/stdlib.sfl`（324 条断言跑在你的实现上）并在
`tests/compile/accept/stdlib_impls.sfl` 里补一行 `same("你的函数", 你的(…),
std.你的(…))`——两者必须给出相同的答案。编译器会自动发现它（扫描 `def`），并在
程序用到时链接对应的目标文件。

两种情况都需要重新 `sbt nativeLink`，因为运行时与标准库的源码嵌在 `sfl` 里。
