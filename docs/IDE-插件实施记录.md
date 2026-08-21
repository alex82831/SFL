# IDE 插件与构建工具 · 实施记录

针对 SFL 0.6.0 · 2026-08-19 · 前置阅读：[评估-IDE-插件与构建工具.md](评估-IDE-插件与构建工具.md)

评估之后拍板的三个决定，以及按"最小可用切片"路线已经落地的部分。

## 已拍板的决定

| 决定 | 选择 | 落点 |
| --- | --- | --- |
| IntelliJ Community | **支持** | LSP 客户端走 LSP4IJ 0.19.3，不用付费版专有 API |
| Windows | **支持（分期）** | 新代码全部按跨平台写（exePath 留了 Windows 分支位、语言服务不 shell out）；存量 Unix 依赖见下文清单 |
| 编译产物调试 | **暂不做** | 调试针对解释器；开发用解释器、编译是发布行为 |
| 文档注释语法 | 未定 | 依赖语言服务层的 trivia 保留，与该重构一起定 |

## 已交付

| 件 | 位置 | 验证 |
| --- | --- | --- |
| `sfl syntax` 语法元数据 JSON | `IdeTool.scala` | tests/run.sh tooling 段 |
| `sfl check [--json]` 解析诊断 | `IdeTool.scala` | 同上（好/坏文件、退出码、JSON 定位） |
| `sfl lsp` LSP 服务器 | `Lsp.scala`（零依赖，复用 `Json`） | `tests/lsp.js` 57 项端到端断言 |
| `fileMtime` `exePath` `passthrough` 内置 | `BuiltinsSys.scala` | 构建工具全流程即验证 |
| 语法资产生成器 | `tools/gen-editor-syntax.sfl`（SFL 写） | 生成物三处同源 |
| TextMate 语法 + 语言配置 | `editors/vscode/`（生成物） | `editors/vscode/test/grammar.js` 28 项断言 |
| VSCode 插件 | `editors/vscode/`，产出 `sfl-lang-0.2.0.vsix` | tsc 零警告 + vsce 打包 |
| IntelliJ 插件 | `editors/intellij/`，产出 `sfl-intellij-0.2.0.zip` | gradle buildPlugin 成功（IC 2024.2.4） |
| `sfl build` 构建工具 | `buildtool/main.sfl`（SFL 写，嵌入二进制） | tests/run.sh：init/编译/增量/run/test/describe |
| `sfl dap` 调试适配器 | `Dap.scala` + `Debug.hook` 钩子 | `tests/dap.js` 25 项端到端断言 |
| 二进制探测与设置页（IJ） | `settings/`：Settings→Tools→SFL、四级探测（设置→SFL_PATH→PATH 手查→常见目录）、找不到时通知而非崩栈 | gradle 构建 |
| 新建项目向导（IJ） | `project/`：ModuleType+ModuleBuilder+向导页（版本、测试开关、二进制状态） | gradle 构建；模板与 `sfl build init` 互为镜像（两处都有交叉引用注释） |
| 新建项目命令（VSCode） | `sfl.newProject`：选目录→起名→`sfl build init`→打开；File→New File… 菜单入口 | tsc + vsce |
| `sfl build init` 参数 | `--version <v>`、`--no-tests`，向导传参复用 | tests/run.sh |
| **私有名误报修复** | 命名空间改造后 `_`-private 名以 **fileTag（文件路径）** 为 tag 声明，而 `ParseIntel.inScope` 只认 rootTag（命名空间）与 opened → 每个 `_helper` 都被判未定义（mysql/main.sfl 一个文件 62 条误报）。修复：`ParseIntel` 新增 `rootFile`，inScope 认 `rootTag ∥ rootFile ∥ opened`；Lsp.analyze 传入。测试钉两侧：同文件私有 def/val 零诊断、未定义的私有名仍标红 | tests/lsp.js（59 项） |
| **原生 Go To 接通（IJ）** | `navigation/`：扁平 PSI 无引用，平台的 Go to Implementation / Type Declaration 找不到"目标元素"而静默无效。`SflTargetElementEvaluator`（`targetElementEvaluator` EP）把标识符/内置/命名空间 token 定义为符号；`SflImplementationSearcher`（`definitionsScopedSearch`）与 `SflTypeDeclarationProvider`（`typeDeclarationProvider`）经 `LSPFileSupport.getSupport(file).get*Support()` + `CompletableFutures.waitUntilDone`（与 LSP4IJ 自家 GoTo 动作同一条路径，javap 核对）取 LSP 结果，映射为目标文件里的真实 PSI 叶子，平台弹窗/导航原样工作。LSP4IJ 的 "LSP …" 菜单项是其全局通用项，不动 | gradle 构建 |
| **构建工具窗口（IJ）** | `buildtool/`：`SflProjectsService`（项目级持久化：已链接根/忽略列表/自动发现；`sfl build describe` 异步取模型）、`SflToolWindowFactory`（树：项目→任务/源文件/依赖；工具栏重载/链接/取消链接/运行/Build All/展开折叠/设置；双击运行或打开）、`SflBuildOutput`（Build 窗与 Run 控制台两条输出路径，Build Project 的 runner 也走它）、`SflBuildToolConfigurable`（Build Tools → SFL 项目级配置页）、`SflProjectsStartup`（打开项目即发现）。插件从不解析 build.sfl，只消费 describe 契约 | gradle 构建 |
| **SFL Projects 视图（VSCode）** | `src/projects.ts`：活动栏容器 + TreeDataProvider（findFiles `**/build.sfl` 排除 build/packages 等）；任务经 Task API 运行于项目根；build.sfl 文件监视自动刷新 | tsc + vsce |
| 当前文件 Run/Debug（IJ） | `debug/SflDebugAdapterDescriptorFactory`（LSP4IJ `debugAdapterServer` EP：isDebuggableFile 认 .sfl、prepareConfiguration 注入命令与工作目录）+ 行首 ▶（RunLineMarkerContributor）；配置类型/producer/断点/DAP 客户端全部复用 LSP4IJ | gradle 构建（API 经 javap 对 0.19.3 jar 核对） |
| **断点静默丢弃根因修复** | 断点 handler 的 `supportsBreakpoint` 问的是 **descriptor** 的 `isDebuggableFile`（不是 factory 的同名方法），其默认实现走 server definition 的 mappings —— 而 0.19.3 的 `debugAdapterServer` EP bean 根本没有 mapping 元素，恒空 → 恒 false → 断点从不发送、程序直通。修复：descriptor 覆写 `isDebuggableFile`（fileType == SFL）；顺带 `setServerMappings` 填配置层 Mappings。教训：LSP4IJ 里 file/factory/definition/options 四层各有一份 `isDebuggableFile`，消费者各问各的 | 本地 lsp4j 复现（单行脚本第 1 行断点）+ 编译期核对 |
| **launch 空参数根因修复** | 真根因（源码级证实）：LSP4IJ 的 `LaunchUtils.getDapParameters` 读运行配置存的 launch JSON，而 producer 流程从不填它 → `launch` 带空参数 → 适配器合法拒绝 → 异常沉入 future 链 = 僵尸会话。修复双保险：工厂 `setLaunchConfiguration(LAUNCH_JSON)`（治新配置）+ descriptor 覆写 `getDapParameters()` 在缺 `program` 时按模板与 file/cwd 重建（治一切来路）。另：LSP4IJ 的 DAP 设置页 Trace 选项存不上（Apply 不激活），诊断时别依赖它 | 编译期对 0.19.3 校验 |
| **探活免疫** | tracker 的 `isSocketAvailable` 用"连上即断"探测端口；适配器 accept 循环只把"先说话"的连接当客户端，静默连接关掉继续等 | tests/dap.js socket 模式内置探活模拟 |
| **调试装配自愈** | lsp4j 0.21 + 反射校验器的本地 harness 证明协议层双通道全通过 → 症结在 IDE 装配层。对策：`SflDebugAdapterDescriptor extends DefaultDebugAdapterDescriptor` 在 descriptor 层强制命令（`sfl dap --socket`）与就绪模式，**不信任运行配置里存的字段**（临时配置会跨插件版本存活）；适配器端 ready 行每 250ms 重播直至连接建立（原生二进制 5ms 就绪，快过 IDE 挂监听器，重播消除竞态） | harness + tests/run.sh |
| **IJ 走 socket 传输** | `sfl dap --socket`：绑 loopback 临时端口，先打一行 `DAP server listening on port <N>` 再 accept；工厂配 TRACE 等待策略 + `${port}` 提取模式。stdio 在 IDEA 的进程 handler 里要经过字节→文本→字节重建（首版在真机上握手挂起），socket 是 LSP4IJ 打磨最多的路径。ready 行是双边契约，socket 版 dap.js 测试钉死它 | tests/run.sh：dap stdio 25 项 + socket 26 项 |
| 当前文件 Run/Debug（VSCode） | `sfl.debugFile` 命令 + 标题栏 🐞 + DebugConfigurationProvider（无 launch.json 的 F5） | tsc + vsce |
| Build Project（IJ） | `build/SflProjectTaskRunner`：认领 SFL 模块的 ModuleBuildTask（绕开 JPS 的 JDK 要求），在模块根跑 `sfl build`，事件流进 Build 工具窗；向导不再打 Java 源码根标记（那正是 "SDK is not specified" 的根因）并 inheritSdk | gradle 构建 |
| 构建任务（VSCode） | TaskProvider：工作区含 build.sfl 时提供 `sfl: build`（Build 组，⇧⌘B）与 `sfl: test`（Test 组） | tsc + vsce |

架构与评估第五节一致：一个 `sfl` 二进制是唯一事实源，插件是薄客户端；
`buildtool/` 沿用 stdlib 的嵌入机制（`build.sbt` sourceGenerator → `BuildToolSources`）。

`build.sfl` 的 DSL 无需任何编译器支持：构建工具先定义 `project()`/`task()`，
再 `eval(readFile("build.sfl"))`。包依赖仍然只写在 `sfl.pkg` 里（importer 在
解析期读它），构建文件不重复声明——单一事实源。

## 调试链路验证状态

2026-08-19 用户在真实 IDEA（Community + LSP4IJ 0.19.3）里确认：断点命中、单步、
变量、输出事件、干净退出全部工作。三个 IDE 侧根因按发现顺序：装配层传输
（stdio→socket）、launch 空参数（setLaunchConfiguration + descriptor 兜底）、
断点静默丢弃（descriptor 层 isDebuggableFile）。细节见上表。

## LSP v1 的能力边界（有意为之）

有：诊断（逐击键）、上下文感知补全（`.` 成员补全：别名模块表面经真实 Importer 解析并按
mtime 缓存、对象键经字面量/赋值/set() 收集、全文件键池回退；`import "` 路径补全：同目录 +
libs + 已装包；平代码列内置 336 + 关键字 + 文件内 def/val + 片段）、悬停与签名提示
（内置 + 用户函数 + `别名.函数`）、跳转定义（文件内 + 别名成员 + 普通导入模块，正则级）、
大纲（documentSymbol，顶层 def/val）。触发字符 `.` `"` `/`，在无关位置（除法、普通字符串、
浮点数点）应答空列表而非噪音。
新增（语义层）：**未定义变量诊断**——Parser 挂可选 ParseIntel 收集器（默认 null 零开销），
记录全局读/全局定义/容错化的 import 失败；LSP 端 refs−defined−builtins 得未定义集，
仅在全部 import 成功时启用（防误报），带 Err.suggest 拼写建议；import 失败逐条成诊断，
文件其余部分照常解析。四个导航 provider（declaration/implementation/typeDefinition 落
definition，references 词边界匹配）。build.sfl 特判：project()/task() 的表面从
二进制内嵌的构建工具源码（BuildToolSources）提取——DSL 与白名单/补全/签名同源，
不会漂移；LSP 进程工作目录 = 项目根（插件设置），./packages 与 ./sfl_packages
的解析与终端一致。SFL_HOME/开关经环境变量传给 lsp/dap/build 子进程
（SFL_LSP_UNDEFINED=off），两端设置页可配，IDEA 侧 Apply 即重启服务器。
没有：重命名、语义高亮、格式化——这些需要评估第二节说的语言服务层
（无损 CST、错误恢复、符号模型）。现有"跳转/成员补全"是正则与启发式级别，够用但
不认作用域；语言服务层落地后按符号模型重做。
RE2 注意：Scala Native 的正则是 RE2，**不支持前瞻/后顾断言**（JVM 上编译通过、
运行时才炸）——写 `(?!…)` 会让 `sfl lsp` 启动即崩，用可选组后过滤替代（此坑由
同工作区另一会话捕获并修复）。

## 命名空间（0.8.0）

语言把顶层名字划进命名空间之后，三处 IDE 资产都跟着改了：

- **`sfl syntax`** 多一个 `namespaces` 数组：`std` 与每个内置分组，各带成员表。
  编辑器据此把 `math.` 认成命名空间而不是某人的变量，且不必问语言服务器。
- **`sfl lsp`**：`ns.` 后的补全按命名空间给成员——包按**整个单元**给（`httpd.`
  能列出七个模块里的任何一个公开名），普通模块按文件给，`std.`/`math.` 给内置；
  悬停、签名提示、跳转定义同样穿透命名空间（跳转会在单元内逐文件找定义）。
  未定义变量诊断也跟着收紧：`ParseIntel` 现在记录每个声明落在哪个命名空间，
  只有本文件所在命名空间与被 `open` 的那些才算在作用域内——所以
  `import "csv"` 之后写裸 `parse(...)` 会被指出来。
- **TextMate 语法 / IntelliJ 词法**：`ns.member` 是一条独立规则。内置命名空间
  取 `support.class.namespace`（有词表兜底），其余按形状认（标识符 + `.` +
  标识符 + `(`）取 `entity.name.namespace`，成员照常按调用着色。IntelliJ 侧新增
  `SFL_NAMESPACE` token 与 `sfl-namespaces.txt` 词表，同一套生成器产出。

## DAP 调试器（已交付，解释器模式）

评估 3.3 的方案已按计划落地：

1. **调试符号表**：`FnCtx.slotNames`（槽位只增不复用，弹出块也不失名）→
   `FnProto.debugNames`，三处 proto 收尾点写入。
2. **断点钩子**：`Debug.hook` 静态字段，`Block.eval` 逐语句空检查；
   单语句循环体不经过 Block，`While`/`ForIn` 按迭代补一次钩子（`hookBody` 预判）。
   实测 fib(32) 解释执行 352ms，与 0.6.0 基线 360ms 持平——不调试就没有代价。
3. **协议通道纪律**：`sfl dap` 启动即 `dup(1)` 保存协议 fd、`dup2(2,1)` 把杂散
   fd1 写入者引到 stderr；脚本 println 经 Console 重定向变成 output 事件，
   `passthrough()` 的子进程输出落在 stderr，协议流不可能被污染。（Windows 分支留位。）
4. **能力面**：断点（逐文件全量替换）、continue/next/stepIn/stepOut/pause、
   多线程各自停靠、全栈帧 Locals+Globals 变量（含闭包外层帧）、悬停求值
   （裸标识符查帧、表达式走全局）、stopOnEntry、output/exited/terminated 事件。
5. **已知边界**：循环作用域帧的槽位名可能按位显示（slot#N）；spawn 出的线程
   自由运行直到自己命中断点；变量树暂平铺（对象不展开）。都等语言服务层。
6. **客户端**：VSCode `debuggers` 贡献点 + `DebugAdapterExecutable(sfl, ["dap"])`，
   F5 即用；IntelliJ 侧 LSP4IJ 0.19 自带 DAP 客户端，设置里指 `sfl dap` 即可，
   专用 RunConfiguration 留作后续。

## Windows 移植清单（决定支持后的存量债）

- `Backend.scala`：`/usr/bin/which`、`xcrun`、`-Wl,--gc-sections` → 需按平台分支
- `PkgTool.scala`：shell out 到 `tar`/`cp` → 换 java.util.zip + 手写 tar 或纯 Scala 复制
- `runtime/`：pthread/fifo/socket/dlopen 均为 POSIX → 编译产物在 Windows 需 MinGW/clang-cl 评估
- `BuiltinsIpc.scala`：POSIX externs → 接口层面按平台分支
- `exePath`：补 `GetModuleFileNameW` 分支（已留位）
- 插件侧已就绪：两端都按 `sfl.exe` 探测

## 复核命令

```bash
./tests/run.sh                             # tooling 段（lsp+dap 在内）+ 存量全部套件
node tests/lsp.js                          # LSP 57 项
node tests/dap.js                          # DAP 25 项
cd editors/vscode && node test/grammar.js  # 语法 28 项（含命名空间）
cd editors/vscode && npx @vscode/vsce package
cd editors/intellij && ./gradlew buildPlugin
```
