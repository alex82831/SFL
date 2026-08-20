# SFL for IntelliJ IDEA

SFL 语言支持，**Community 与 Ultimate 都能用**（LSP 客户端走 Red Hat 的 LSP4IJ，
不依赖付费版专有的 `com.intellij.platform.lsp`）。

| 功能 | 来源 |
| --- | --- |
| 语法高亮、注释切换、括号配对 | 插件内置的小词法器（词表由 `sfl syntax` 生成） |
| 诊断、补全、悬停、签名提示 | `sfl lsp`（通过 LSP4IJ 接入） |
| `.` 成员补全 / `import "` 路径补全 / Structure 视图 | 同一个 `sfl lsp`——服务器声明触发字符与能力，插件零改动获得 |
| Go to Definition / Implementation / Type Declaration / Find Usages | 全部可用（SFL 无类型层级，前三者同落定义处；Usages 为当前文件词边界匹配） |
| 未定义变量标红 | 语义级诊断，含拼写建议；import 失败逐个报告且不遮蔽全文件 |
| **新建项目向导**（New Project → SFL） | 生成与 `sfl build init` 相同的布局：build.sfl、src/、可选 tests/；可设版本号；src 与 tests 标为源码根 |
| **运行/调试当前文件** | 行首 ▶、右键、以及工具栏 Current File 的 Run/Debug；SFL 脚本即程序，无需 main。断点、单步、变量走 `sfl dap`（LSP4IJ 的 DAP 客户端） |
| **Build Project** | SFL 模块由插件接管（不进 JPS，不需要 JDK）：在模块根跑 `sfl build`，输出进 Build 工具窗 |

## 设置（Settings → Tools → SFL）

| 项 | 说明 |
| --- | --- |
| Path to the sfl binary | 二进制路径，空则自动探测 |
| SFL_HOME | 包根与 libs 的家目录，**只有全局安装的包需要它**（项目内 ./packages、./sfl_packages 自动可见——语言服务器以项目根为工作目录，与终端行为一致）。GUI 启动的 IDE 继承不到 shell 的 export，全局包标红就在这里填 |
| Enable the language server | LSP 总开关 |
| Flag undefined variables | 未定义变量标红（带 did-you-mean）开关 |

改动 Apply 后语言服务器自动重启生效。

## 找到 sfl 二进制

按顺序尝试：**Settings → Tools → SFL** 里填的路径 → 环境变量 `SFL_PATH` →
PATH（手动逐目录查，因为 Dock 启动的 IDE 常常没有终端的 PATH）→ 常见安装位置
（`/opt/homebrew/bin`、`/usr/local/bin`、`~/.local/bin`）。

找不到时不再报未捕获异常，而是弹一条带「Set Path…」按钮的通知；
语法高亮不受影响，诊断/补全在路径设好后自动恢复。

## 构建

```bash
./gradlew buildPlugin      # 产出 build/distributions/sfl-intellij-<version>.zip
./gradlew runIde           # 起一个装了插件的沙箱 IDE 调试
./gradlew verifyPlugin     # 平台兼容性检查
```

首次构建会下载 IntelliJ IDEA Community 平台（约 1 GB），需要网络。

产出的 zip 通过「Settings → Plugins → ⚙ → Install Plugin from Disk…」安装；
LSP4IJ 会作为依赖自动从 Marketplace 拉取。

## 词表是生成物

`src/main/resources/syntax/*.txt` 由仓库根的 `sfl tools/gen-editor-syntax.sfl`
生成，不要手改。语言加了关键字或内置函数后重新生成即可，无需改插件代码。
