# SFL 的 IDE 插件与构建工具

**可行性评估** · 针对 SFL 0.6.0 · 2026-08-19

本文回答一个问题：要做出可用的 IntelliJ IDEA 插件和 VSCode 插件（语法高亮、项目管理与构建、源码级调试、包管理），
除了规划中的"类 sbt 工具"之外，**还缺什么**。

所有关于现状的结论都在本仓库上核对过，随文给出文件与行号，可复现。凡是工作量数字，都标明是估算。

---

## 结论

| 结论 | 说明 |
| --- | --- |
| **类 sbt 的构建工具不是瓶颈，先做它是错的顺序** | 它对高亮、调试、IDE 的"看懂代码"一点帮助都没有 |
| **真正缺的是一层语言服务（language service）** | 四项功能里有三项半都压在它上面 |
| **前端形状不对，不是"缺功能"而是"缺形态"** | 解析器直接吐可执行 AST，无区间、无注释、遇错即抛、解析期读盘并内联导入 |
| **源码级调试是最大的单项缺口** | 解释器没有任何断点机制；编译产物**零调试信息**，且运行帧不保存变量名 |
| **包管理已完成约一半** | 清单、语义化版本、范围、双根、冲突诊断都有；缺的是网络、锁文件、校验、解析器 |
| **语法高亮是四项里最便宜的** | 现成词法器位置信息完整，改造成本小 |
| 总量估算 | **9–14 人月**（估算），其中约一半花在语言服务层 |

一句话：**先把"能被工具查询的编译器前端"做出来，插件和构建工具都是它的下游；顺序反了会写两遍。**

---

## 一、已有的资产

这些不用重做，直接复用。

| 资产 | 位置 | 对插件的价值 |
| --- | --- | --- |
| 手写词法器，位置信息完整 | `Lexer.scala:118` `Token(kind, text, ival, dval, line, col, offset, startsLine)` | 高亮的地基。**同时有 line/col 和 offset**，这是 LSP 与 IntelliJ 都要的 |
| 诊断基础设施 | `Errors.scala:4` `Pos(line, col, offset)`、`SflError.notes`/`span`、`render()` | 消息、帮助行、下划线宽度都已建模，只差一个 JSON 出口 |
| "你是不是想写…"拼写建议 | `Errors.scala`、`Globals.membersOf` | 补全和快速修复可以直接复用 |
| 内置函数元数据 | `Builtins.scala:42` `define(name, minArity, maxArity, group, signature, doc)` | 321 个内置函数自带签名和文档 → hover、补全、签名提示**几乎白送** |
| 中文文档库 | `docs/builtins-zh.json` | 中文 hover 现成 |
| 包清单与版本模型 | `PkgTool.scala` 全文 | `sfl.pkg`、语义化版本子集、`^`/`~`/`>=`/区间、双根、扁平绑定冲突诊断 |
| 类型推断 | `Compile.scala:14` `enum Ty { Unknown, Bool, I64, F64, Dyn }` | hover 可显示 `int`/`float`/`bool`/`value` |
| 运行时调用栈 | `Ast.scala:141` `frameFn`/`frameLine`/`frameSrc` | 调试器的 stack trace 已有原型 |
| 逐语句位置钩子 | `Ast.scala:823` `Block.eval` 里 `rt.line = st.line` | **断点检查插在这一行**，钩子点天然存在 |
| 极强的系统调用面 | `execute` `processStart` `httpGet/httpPost/httpRequest` `serverSocket` 全套文件操作 | 构建工具和包管理器**可以用 SFL 自己写** |
| 增量式差分测试 | `tests/differential.sh` | 任何前端改造都有回归网 |

关于最后一条要强调：SFL 的 sys/IPC 内置函数已经覆盖进程、HTTP、socket、文件系统。
**"构建文件用 SFL 写"这个设计不缺语言能力**，甚至构建工具本体都可以用 SFL 写而不是 Scala。

---

## 二、真正的缺口：没有语言服务层

四项功能里，除了"包管理"，其余三项都要求同一件事：
**一个能被反复询问"这个位置是什么"的编译器前端。**

SFL 今天的前端是为"跑得快"设计的，不是为"被询问"设计的。五个结构性问题：

### 1. 没有无损语法树，AST 节点只有起点、没有区间

```scala
final class Lit(val v: Value, val line: Int) extends Node        // Ast.scala:264
final class LocalGet(val slot: Int, val name: String, val line: Int) extends Node
final class GlobalGet(val id: Int, val name: String, val line: Int) extends Node
```

30 个 AST 节点类里，全部只带 `line`；只有 2 处带 `col`（`Ast.scala:243`，且是给报错用的 `var`）。
**没有任何节点知道自己在哪里结束。** 注释和空白在词法阶段就被丢掉（`Lexer.scala:195-209`，跳过后不产生 token）。

而且这棵树是**已解析、已降级**的执行 IR：变量在解析时就变成了槽位号和全局 id，
定义点的位置**没有被记录下来**。

后果——以下功能全部无法实现，不是"难"，是"没有输入"：

| 功能 | 缺什么 |
| --- | --- |
| 跳转到定义 | 定义点位置未保存 |
| 查找引用 / 重命名 | 引用点区间未保存 |
| 结构视图 / 大纲 | 无法枚举顶层定义及其区间 |
| 代码折叠 | 无块区间 |
| 扩展选区 | 无嵌套区间 |
| 格式化 | 注释已丢失，格式化会删掉用户注释 |
| 语义高亮 | 无法把区间标成"局部变量"还是"内置函数" |
| IntelliJ PSI | **平台硬性要求 PSI 覆盖文件每一个字符**，包括空白和注释 |

最后一条是硬门槛：IntelliJ 的解析器契约要求树的叶子节点拼起来等于原文件，一个字符都不能少。

### 2. 遇到第一个错误就抛异常，没有错误恢复

```scala
private def fail(msg: String): Nothing =                          // Parser.scala:214
  if cur.kind == Tok.EOF then throw new IncompleteInput(msg)
  Err.parse(msg, cur.pos, src)
```

命令行编译时这是对的——报一个最好的错比报十个噪音强。
但 IDE 在**每次击键**时重新解析，而正在编辑的文件**大部分时间语法是坏的**。

没有错误恢复意味着：光标停在一个半写的表达式上时，整个文件退化成一条红波浪线，
后面所有代码失去高亮、失去补全、失去大纲。这是"能用"和"不能用"的分界线。

### 3. 解析有副作用，且依赖全局单例

```scala
val path = importer.pathOf(fileTok.text, src, fileTok.pos)        // Parser.scala:517
importer.begin(path) match
  case Some(text) =>
    // 导入的代码被解析进当前全局作用域并就地内联
    new Parser(SourceRef(path, text), globals, importer, alias.isDefined).parseProgram()
```

`import` 在**解析期**就做完了这些事：读磁盘、在两个包根里搜索、选定包版本并全局绑定
（`Parser.scala:1735` `pkgBindings`）、把被导入模块的源码解析并**内联**进同一棵树、写进共享的 `Globals`。

于是"分析一个文件"实际等于"构建一个完整程序"。而 IDE 需要的是：
并发地、反复地、只读地分析任意单个文件，不碰磁盘上的包安装状态，不污染任何共享表。

`Sfl.globals` 是单例，`Importer` 的包版本绑定是进程级的——两个编辑器标签页会互相干扰。

### 4. 没有符号模型

不存在"定义"这个对象。没有：

- 函数/变量的定义点位置
- 用户代码的文档注释（语言里没有文档注释约定，也没有任何东西去提取它）
- 模块的导出面描述（`Globals.membersOf` 靠 `"tag#name"` 字符串前缀反查，位置早已丢失）
- 作用域树（局部变量的作用域范围只在解析器的临时栈里，解析完就没了）

`Globals` 是 `name → id → value`，纯运行时结构。
跳转、hover、大纲、工作区符号搜索这四件事共用一个符号模型，而它不存在。

### 5. 没有增量性

一切都是全程序、从头来过。脚本 5 毫秒跑完时这完全合理；
但 IDE 一秒问三十次，而且项目会有几百个文件。

---

## 三、四项功能逐项评估

### 3.1 语法高亮 — 最便宜的一项

| | 现状 |
| --- | --- |
| 已有 | `Highlight.scala`（REPL 用，ANSI，逐行启发式，**故意不用词法器**，注释里写明了原因）；`Lexer.scala` 位置信息完整 |
| 缺 | TextMate 语法（`.tmLanguage.json`，VSCode 需要）；IntelliJ 词法器（JFlex 或手写 `Lexer` 实现）；tree-sitter 语法（可选） |

三个具体障碍：

1. **词法器遇错即抛**（`Lexer.scala:203` 未闭合块注释抛 `IncompleteInput`）。IDE 词法器必须容错，坏输入要产出 `BAD_CHARACTER` token 继续往下走。
2. **词法器不可重入**。IntelliJ 的 `Lexer` 契约是 `start(buffer, startOffset, endOffset, initialState)`——必须能从文件中间某个状态重启。当前实现是一次性 `tokenize()` 全文。
3. **注释与空白不产出 token**。IntelliJ 需要它们，VSCode 的语义高亮也需要区间。

另外插值字符串 `"a${x}b"` 已经在词法层拆成 `STR_OPEN`/`STR_MID`/`STR_CLOSE`（`Lexer.scala:85-87`），
这是好消息——TextMate 语法里做嵌套高亮有据可依，但要手写。

**还缺一件容易被忽略的东西**：一份**机器可读的语法规格**。
否则 TextMate 语法、IntelliJ 词法器、真词法器三份实现会各自漂移，
加一个关键字要改三处，而且没有任何测试能发现漏改。
建议：关键字表、操作符表、token 种类从 `Lexer.scala:107` 的表导出成 JSON，三处都从它生成或对它做断言。

> 估算：容错+可重入词法器 1 周；TextMate 语法 3 天；IntelliJ 词法器 3 天；语义高亮依赖第四节的语言服务。

### 3.2 项目管理与构建 — 规划中的类 sbt 工具，但它只覆盖一半

先说清楚：**构建工具解决的是"项目"，不解决"IDE 看懂代码"。**

现状是 SFL **没有项目概念**。一个程序 = 一个入口文件 + 解析期内联进来的导入。没有：

| 缺什么 | 为什么 IDE 需要它 |
| --- | --- |
| 构建文件 | 项目根的标识；IDE 靠它认出"这是一个 SFL 项目" |
| 源码根 / 多模块 | 项目视图的树形结构；判断一个文件属不属于本项目 |
| 显式的依赖图与目标 | 增量构建；"构建"按钮该做什么 |
| 内容寻址的构建缓存 | 改一行不要重编全部 |
| 任务模型（compile/test/run/package） | IDE 的 Run Configuration 挂在任务上 |
| **`--describe --json` 或 BSP 端点** | **这是关键**：插件必须能问构建工具"源码根在哪、依赖是什么、输出在哪"，而不是自己再实现一遍解析逻辑 |

最后一条最容易漏。Scala 生态用 BSP 解决它（本仓库根目录就有 `.bsp/`）。
SFL 不必上 BSP 那么重，但**必须有一个稳定的、机器可读的项目描述端点**，否则插件会把构建工具的逻辑抄一遍，然后两边漂移。

关于"构建文件用 SFL 写"——这个设计**没有语言层障碍**，但有一个实现约束：

构建脚本要调用 `project()`、`task()`、`dependsOn()` 这类 API，这些函数必须由宿主注入。
而 SFL **没有扩展机制**：`Builtins.define` 是 Scala 内部 API，加内置函数要重新编译 `sfl`；
`dlopen` 只为 libcurl 硬编码了一处（`runtime/sfl_ipc.c:1355`），不是通用 FFI。

两条路：
- **（推荐）构建工具内置进 `sfl` 二进制**，作为 `sfl build` 子命令，注入自己那组内置函数。零新机制，与 `sfl pkg` 一致。
- 给 SFL 加通用原生扩展机制。代价大得多，且会把 GC 和值表示暴露给第三方。

> 估算：项目模型 + 任务图 + 增量缓存 + JSON 描述端点，4–6 周。

### 3.3 源码级调试 — 最大的缺口

这是两个独立的调试器，因为有两个执行模式。

#### 解释器调试

| | 状况 |
| --- | --- |
| 钩子点 | **有**。`Ast.scala:823` `Block.eval` 每条语句更新 `rt.line`/`rt.col` |
| 断点机制 | **完全没有**。没有检查、没有暂停、没有事件循环 |
| 调用栈 | 有原型（`Ast.scala:141` `frameFn`/`frameLine`/`frameSrc`，上限 512 帧） |
| **变量查看** | **不可能，缺数据**。见下 |

变量查看这条要展开说，它是个真问题：

```scala
final class Frame(val slots: Array[Value], val parent: Frame)     // Value.scala:187
```

运行时帧就是一个 `Array[Value]`，**没有名字**。
`FnProto` 保存了 `params: Array[String]`（`Ast.scala:186`），但**块内局部变量的名字没有保存**——
它们只存在于解析器的 `LocalVar` 列表里（`Parser.scala:19`），解析完就丢了。

所以今天即使加上断点，"Variables" 面板也只能显示 `slot[3] = 42`。
**必须新增一张调试符号表**：每个 `FnProto` 记录 槽位 → 名字 + 作用域区间。
（好消息：这张表只在 `-g` 模式下生成，不影响正常运行的内存占用。）

其余缺口：

- **单步语义**与尾调用蹦床冲突。尾调用复用调用者的活动记录（`Ast.scala:712`），
  "step out" 在尾递归里没有对应的物理帧。需要明确定义单步在尾调用下的行为，否则调试体验会很怪。
- **表达式求值 / 监视窗口**：要针对活动帧的作用域解析一个表达式。
  当前解析器只能针对**自己的**编译期作用域栈做解析，无法"在某个帧的上下文里解析"。
- **多线程**：`spawn()` 用真 OS 线程，每线程一个 `Interp`（`Builtins.scala:16` `ThreadLocal`）。
  调试器要能枚举线程、全停/单停、在正确的线程上恢复。
- **没有 DAP 实现**。VSCode 需要 Debug Adapter Protocol；IntelliJ 也能吃 DAP，省一套实现。

#### 编译产物调试

**编译器不发射任何调试信息。** 核对过：`Compile.scala` 和 `Backend.scala` 里
没有 `!dbg`、没有 `DILocation`、没有 `DISubprogram`，clang 命令行（`Backend.scala:212`）没有 `-g`。

即使补上 DWARF 行表，还有三层困难：

1. **值的展示**。SFL 值是自定义运行时的带标签指针（`runtime/sfl_value.c`）。
   LLDB 会显示原始指针。需要 LLDB 的 Python 类型摘要插件，或让调试器回调进运行时的格式化函数。
2. **优化把映射打碎**。默认 `-O2`，内联和寄存器分配之后行映射会跳。需要一条 `-O0 -g` 的调试构建路径。
3. **特化让变量消失**。推断出机器类型的变量被拆箱、可能完全存活在寄存器里，甚至不作为独立实体存在（`Compile.scala:48`）。

**建议：把编译产物调试放到第二阶段，第一阶段只做解释器调试。**
理由是收益比悬殊——开发时用解释器跑（本来就是几毫秒），编译是发布行为。

> 估算：解释器调试器 + DAP，4–6 周；编译产物 DWARF 调试，4–8 周且风险高。

### 3.4 包管理 — 已完成约一半

`PkgTool.scala` 383 行，质量不错，模型是对的。已有：清单格式、语义化版本、
范围子集（`1.2.3` `^` `~` `*` `>=` 区间）、项目级/全局双根、本地根遮蔽全局根、
"扁平模型"单版本绑定 + 冲突时的详细诊断（`Parser.scala:1755`）。

缺的部分：

| 缺什么 | 严重度 | 说明 |
| --- | --- | --- |
| **网络** | 高 | `install` 只接受**本地** `.sflpkg` 文件或目录。没有 registry，没有 `sfl pkg add name@^1.2.0` 这种"去拿" |
| **锁文件** | 高 | 现在的解析是"选装了的、满足范围的最高版本"（`Parser.scala:1766`）——**不可复现**，别人装了个新版本你的构建就变了 |
| **完整性校验** | 高 | 无 checksum、无签名 |
| **真正的解析器** | 中 | 现在是"先到先绑定，冲突就报错"（一种策略），不是求解传递依赖集的算法。依赖一多会频繁撞墙 |
| **前置解析** | 中 | 解析发生在 `import` 执行到的那一刻。没有"先把整个依赖图解出来、一次性报冲突" |
| `publish` | 中 | 只有 `build`（打 tarball） |
| dev 依赖 / 可选依赖 | 低 | |
| 下载缓存与安装根分离 | 低 | |

注意 `PkgTool` 依赖外部 `tar` 和 `cp`（`PkgTool.scala:280,312,331`）——见 3.5 的 Windows 问题。

> 估算：registry 客户端 + 锁文件 + 校验 + 解析器，3–4 周（不含运营一个 registry 服务）。

---

## 四、其它必须补的零件

这些单个都不大，但少了任何一个插件都会显得半成品。

| 零件 | 为什么必须 | 依赖 |
| --- | --- | --- |
| **格式化器** | 现代语言插件的标配；IntelliJ 把"重新格式化"、自动缩进、输入时补全括号全挂在它上面 | 无损语法树（必须保住注释） |
| **JSON 诊断输出** | 现在错误只有 ANSI 散文（`Errors.scala:59`）。插件不能去 parse 人类可读文本 | 小 |
| **多错误报告** | 解析器只报一个错就停。IDE 要看到文件里全部问题 | 错误恢复 |
| **文档注释约定** | 内置函数有 `doc` 字段，**用户代码什么都没有**。hover 一个自己写的函数会一片空白 | 无损语法树（注释） |
| **测试事件协议** | IDE 的测试树、绿勾红叉需要机器可读事件流。现在 `check()`/`report()` 打散文 | 小 |
| **语言配置数据** | 括号配对、注释符号、缩进规则、单词模式、折叠标记 | 小，但两个编辑器各要一份 |
| **稳定的 CLI/协议契约 + 版本号** | 否则编译器一改插件就坏 | 设计工作 |
| **Windows 支持** | `Backend.scala:29` 写死 `/usr/bin/which`，`:38` 用 `xcrun`，`:50` 用 `-Wl,--gc-sections`；`PkgTool` shell out 到 `tar`/`cp` | **今天 SFL 在 Windows 上跑不了**，而 VSCode/IntelliJ 用户里 Windows 占比不小 |
| **分发方案** | IntelliJ 插件是 JVM，VSCode 插件是 Node，两者都要找到或自带**原生** `sfl` 二进制，按平台×架构分发（macOS arm64/x64、Linux x64/arm64、Windows） | 需要 CI 矩阵 |
| **REPL / 控制台集成** | 两个 IDE 都期望 Run Configuration 能挂一个交互控制台 | 小 |

Windows 这条值得单独想清楚：如果决定不支持 Windows，那要在插件市场页面写明，
否则会收到大量差评。如果要支持，`Backend`、`PkgTool`、以及 `runtime/` 里的 pthread/fifo/socket 代码都要过一遍。

---

## 五、建议的架构

```
                    ┌───────────────────────────────────────┐
                    │  sfl 二进制（唯一的真理来源）           │
                    │                                       │
   IntelliJ 插件 ───┤  sfl lsp    ← LSP over stdio          │
                    │  sfl dap    ← DAP over stdio          │
   VSCode 插件  ────┤  sfl build --describe --json          │
                    │  sfl pkg    （已有，需扩展）            │
                    │                                       │
                    │  ┌─────────────────────────────────┐  │
                    │  │ 语言服务层（新，第零阶段）        │  │
                    │  │  无损 CST + 区间 + trivia        │  │
                    │  │  错误恢复                        │  │
                    │  │  符号 / 作用域 / 定义点模型       │  │
                    │  │  无副作用的分析模式               │  │
                    │  │  增量缓存                        │  │
                    │  └────────────┬────────────────────┘  │
                    │               │ 降级（lowering）       │
                    │  ┌────────────▼────────────────────┐  │
                    │  │ 现有执行 AST（不动）              │  │
                    │  │  解释器 · LLVM 后端               │  │
                    │  └─────────────────────────────────┘  │
                    └───────────────────────────────────────┘
```

三个要点：

1. **一份语法，一个前端。** CST 是解析器唯一的产物，执行 AST 由 CST **降级**得到。
   诱惑是"给工具单独写一个宽松解析器"——短期便宜，但两份语法必然漂移，这是这类项目最经典的失败方式。
   代价是要改 `Parser.scala`（1857 行），但差分测试（`tests/differential.sh`）能兜住。

2. **语言服务编进 `sfl` 二进制**，不做成 JVM 进程。理由是 SFL 的立身之本就是原生启动快，
   一个几毫秒启动的 LSP 服务器是竞争优势；而且 Scala Native 已经开了多线程（`build.sbt:nativeConfig` `.withMultithreading(true)`）。

3. **两个插件都做薄。** 逻辑全在服务器侧，插件只做协议接线。

### IntelliJ 有一个需要先确认的坑

IntelliJ 平台官方的 LSP 客户端 API（`com.intellij.platform.lsp`）**只在付费版 IDE 里可用**，
IntelliJ IDEA Community 没有。三条路：

| 方案 | 代价 | 后果 |
| --- | --- | --- |
| 官方 LSP API | 低 | **Community 版用不了插件** |
| LSP4IJ（Red Hat 的开源 LSP 客户端） | 低 | Community 可用，但多一个第三方依赖 |
| 手写 PSI + 全套原生实现 | **很高**（估算 +8 周以上） | 两份语法实现，回到第五节第 1 点警告的坑 |

> 这条是按现有平台知识写的，**请按你要支持的 IntelliJ 版本复核一次**——JetBrains 近年在动这块 API。
> 它影响的是"支持哪些 IDE"这个产品决策，不只是技术选型。

---

## 六、分期与估算

估算单位是人月，按一个熟悉本仓库的人计。

| 阶段 | 内容 | 估算 | 阻塞谁 |
| --- | --- | --- | --- |
| **0. 语言服务层** | 无损 CST + 区间 + trivia；错误恢复；符号/作用域模型；无副作用分析模式；增量缓存 | **2.5–3.5 月** | 几乎所有东西 |
| 1. LSP 服务器 | 诊断、语义高亮、补全、hover、签名提示、跳转、引用、重命名、大纲、折叠 | 1–1.5 月 | 两个插件 |
| 2. 格式化器 | | 0.5–0.75 月 | |
| 3. 构建工具 | 项目模型、任务图、增量缓存、`--describe --json`、SFL 构建 DSL | 1–1.5 月 | 项目管理 |
| 4. 包管理补齐 | registry 客户端、锁文件、校验、解析器、publish | 0.75–1 月 | |
| 5. 解释器调试器 + DAP | 调试符号表、断点、单步、线程、帧内求值 | 1–1.5 月 | 调试功能 |
| 6. VSCode 插件 | | 0.5–0.75 月 | |
| 7. IntelliJ 插件 | 走 LSP4IJ 路线 | 0.75–1 月 | |
| 8. 编译产物 DWARF 调试 | **可选，风险高** | 1–2 月 | |
| 9. Windows 移植 | **可选** | 0.5–1 月 | |
| | **合计（不含 8、9）** | **约 9–11.5 月** | |
| | **合计（全含）** | **约 10.5–14.5 月** | |

### 可以先交付的最小可用切片

如果想早点看到东西，这条路径 **2–2.5 月**能出一个真能用的 VSCode 插件：

1. 容错 + 可重入的词法器（1 周）
2. TextMate 语法 + 语言配置（1 周）
3. `--diagnostics=json`，先只报单个错（3 天）
4. 一个只有诊断 + 内置函数补全/hover 的 LSP 服务器（2–3 周，内置函数元数据是现成的）
5. VSCode 插件外壳 + 运行/REPL 集成（1.5 周）

这个切片**刻意不碰** CST 重构，所以拿不到跳转、重命名、格式化、用户代码 hover。
它是投石问路，不是地基——地基还是第零阶段。

---

## 七、需要先拍板的三个决定

1. **要不要支持 IntelliJ IDEA Community？**
   影响 LSP4IJ 还是官方 API，最坏情况差 8 周以上（手写 PSI）。

2. **要不要支持 Windows？**
   今天不支持。补的话 `Backend`、`PkgTool`、`runtime/` 都要过。
   插件的主要受众里 Windows 不是小数。

3. **编译产物的源码级调试，做还是不做？**
   不做的话，"源码级调试"这个承诺就限定在解释器模式。
   我的建议是**先不做**——开发时用解释器，编译是发布行为，收益比不划算。

另外一个次要但要早定的事：**文档注释的语法**（`///`？`/** */`？）。
它很小，但一旦有人开始写代码就很难改了，而 hover 用户函数完全依赖它。

---

## 附：本文结论的核对方式

```bash
# AST 节点只有 line，没有区间
grep -c 'extends Node' src/main/scala/com/fartech/sfl/Ast.scala   # 30
grep -c 'var col'      src/main/scala/com/fartech/sfl/Ast.scala   # 2

# 编译器不发射任何调试信息
grep -n 'dbg\|DILocation\|DISubprogram\|"-g"' \
  src/main/scala/com/fartech/sfl/Compile.scala \
  src/main/scala/com/fartech/sfl/Backend.scala                    # 无输出

# 解析器遇错即抛，无恢复
sed -n '214,224p' src/main/scala/com/fartech/sfl/Parser.scala

# import 在解析期读盘并内联
sed -n '506,531p' src/main/scala/com/fartech/sfl/Parser.scala

# 运行帧不保存变量名
sed -n '187,195p' src/main/scala/com/fartech/sfl/Value.scala

# 词法器跳过注释，不产出 token
sed -n '195,209p' src/main/scala/com/fartech/sfl/Lexer.scala

# Unix-only 的外部命令依赖
grep -n '/usr/bin/which\|xcrun\|"tar"\|"cp"' \
  src/main/scala/com/fartech/sfl/Backend.scala \
  src/main/scala/com/fartech/sfl/PkgTool.scala
```
