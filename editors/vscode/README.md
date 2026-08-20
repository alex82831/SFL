# SFL for Visual Studio Code

SFL 语言支持：语法高亮、诊断、补全、悬停文档、签名提示，以及一键运行/编译。

语言功能全部来自 `sfl` 二进制内置的语言服务器（`sfl lsp`）——插件本身只是接线。
高亮不依赖服务器，装上即有；诊断等功能需要 `sfl` 在 PATH 上，或在设置里指定：

```json
{
  "sfl.path": "/usr/local/bin/sfl",
  "sfl.home": "~/.sfl",
  "sfl.diagnostics.undefinedNames": true
}
```

`sfl.home` 很关键：GUI 启动的编辑器继承不到 shell 里 export 的 `SFL_HOME`，
已安装的包在 import 处标红时，把包根目录填在这里即可（语言服务器、调试器、
构建任务都会带上它）。

## 功能

| 功能 | 来源 |
| --- | --- |
| 语法高亮 | TextMate 语法（由 `sfl syntax` 生成，永不和编译器漂移） |
| 诊断 | `sfl lsp`，每次编辑即时解析：语法错误、逐个报告的 import 失败（不再一错遮全文件）、**未定义变量标红**（带 did-you-mean，`sfl.diagnostics.undefinedNames` 可关） |
| 补全 | 内置函数（336 个，带签名与文档）、关键字、当前文件的定义、结构片段（def/for/while/select/task） |
| **`.` 成员补全** | `obj.` 弹出该对象在本文件里可证明的键；`别名.` 弹出 `import "m" as 别名` 模块的公开函数与常量 |
| **`import "` 路径补全** | 同目录模块、SFL_HOME/libs、已安装的包与包内模块 |
| 悬停文档 / 签名提示 | 内置函数 + 当前文件与导入模块的用户函数（含 `别名.函数(` 形式） |
| 跳转到定义 / 实现 / 类型声明 | 当前文件的 def/val、别名成员与普通导入模块的函数（SFL 无类型层级，三者同落定义处） |
| 查找引用 | 当前文件内词边界匹配 |
| 大纲 / 面包屑 | 顶层 def 与 val（documentSymbol） |
| SFL: New Project… | 选目录、起名，用 `sfl build init` 生成项目并打开（File → New File… 菜单里也有） |
| SFL: Run File | 在终端里运行当前文件 |
| SFL: Compile File | `sfl -c` 编译为原生可执行文件 |
| **源码级调试**（F5，零配置） | `sfl dap`：断点、单步、调用栈、变量、悬停求值；无 launch.json 时直接调试当前文件 |
| SFL: Debug File（标题栏 🐞） | 一键调试当前文件 |
| 构建任务（⇧⌘B） | 工作区有 build.sfl 时提供 `sfl: build` 与 `sfl: test` 任务 |

## 构建插件

```bash
npm install
npm run compile
npx @vscode/vsce package   # 产出 sfl-lang-<version>.vsix
```

生成的 `.vsix` 通过「Extensions: Install from VSIX…」安装。

## 语法资产从哪来

`syntaxes/sfl.tmLanguage.json` 和 `language-configuration.json` 是生成物，
不要手改。改动语言后在仓库根目录运行：

```bash
sfl tools/gen-editor-syntax.sfl
```
