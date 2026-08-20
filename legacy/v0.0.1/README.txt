SFL (Scripting for Fun) — Scala Native project
================================================

Overview
--------
SFL is a tiny functional scripting language implemented in Scala 3 and compiled to a native executable using Scala Native. It provides:
- A REPL (interactive shell) for experimenting with the language
- Running SFL scripts from files
- A small but practical standard library of predefined functions for I/O, strings, process execution, HTTP GET, filesystem utilities, simple parsing/eval, and iteration

This repository contains the SFL implementation (parser, AST, evaluator, runtime environment) plus a sample script (test.sfl).

Project layout
--------------
- build.sbt — project and Scala Native configuration
- project/ — sbt plugin (+ Scala Native plugin 0.5.x)
- src/main/scala/
  - Main.scala — CLI entry point (-repl, -source, -tasty)
  - com/fartech/sfl/
    - Expr.scala — AST (expressions), evaluator, and core runtime logic
    - Environment.scala — symbol tables, scoping, and execution pipeline
    - SFLParser.scala — language parser (scala-parser-combinators)
    - PreDefs.scala — predefined functions (I/O, HTTP, FS, strings, etc.)
    - Utils.scala — FileUtils, JsonUtils helpers
- src/test/scala/BasicTest.scala — simple JUnit test for Scala Native
- test.sfl — example SFL script demonstrating language features

Requirements and platform notes
-------------------------------
This project is configured for Scala Native and contains macOS-specific build flags in build.sbt:
- Xcode macOS SDK is referenced via -isysroot
- Homebrew-installed libraries are referenced via -L flags:
  - OpenSSL 3 at /opt/homebrew/opt/openssl@3/lib
  - libidn2 at /opt/homebrew/Cellar/libidn2/…/lib

To build on macOS (Apple Silicon/Homebrew layout assumed):
1) Install Xcode Command Line Tools and ensure the SDK path exists as used in build.sbt.
2) Install Homebrew and the required libraries:
   - brew install openssl@3 libidn2
3) Ensure the paths in build.sbt match your system. If your paths differ, update the linking/compile options accordingly (nativeConfig.withLinkingOptions / withCompileOptions).

On Linux/Windows:
- You will likely need to adjust or remove the macOS-specific -isysroot and -L options in build.sbt and point them to the equivalent libraries or rely on your system layout. Scala Native toolchain prerequisites apply (LLVM/Clang, etc.). See Scala Native docs for your platform.

Technology stack
----------------
- Language: Scala 3.3.1
- Runtime: Scala Native 0.5.8 (via sbt plugin)
- Testing: Scala Native JUnit
- Parser: scala-parser-combinators 2.4.0
- HTTP client: sttp client4 core 4.0.9 (used in httpGet predefined)
- Utility: scala-native-crypto 0.2.1 is available as a dependency for crypto use-cases (not heavily used in the core flow)

How to build, run, and test
---------------------------
Build and run in REPL mode:
- sbt run -- -repl
  - Starts an interactive prompt: type an expression and press Enter to evaluate.
  - Optionally add -tasty to print the parsed expression representation.

Run a script file:
- sbt run -- -source test.sfl
  - Optionally add -tasty to also print parsed expressions.

Build a native executable (faster startup for repeated runs):
- sbt nativeLink
  - The produced binary will be placed under target/scala-<scalaVersion>/native/.
  - Its exact name depends on the project name; for this project it will be based on the directory name (e.g., sfl_scala_native or similar).
  - Run it with the same CLI flags, for example:
    - ./target/scala-3.3.1/native/<binary-name> -repl
    - ./target/scala-3.3.1/native/<binary-name> -source test.sfl

Run tests:
- sbt test

CLI usage
---------
The program supports the following flags:
- -repl
  Start the interactive shell. Predefined functions are loaded and ready to use.
- -source <filename>
  Execute an SFL source file.
- -tasty
  Print the parsed expression(s) prior to evaluation (debug output).

SFL language overview (quick reference)
--------------------------------------
Values
- Numbers (integers and floats), booleans (true/false), strings
- JSON-like objects and arrays: {"k": 1, "x": [1,2,3]} and [1, 2, 3]

Variables and assignment
- val x = 10           // immutable variable
- var y = 20           // mutable variable
- x = x + 1            // assignment (for mutable)
- x += 1, x -= 2, etc. // augmented assignment supported

Functions
- Definition (block):
  def add(a, b) {
    a + b
  }
- Definition (single-expression):
  def add(a, b) = a + b
- Invocation:
  add(1, 2)
- Lambdas:
  { (a, b) -> a + b }
  or
  (a, b) -> a + b
- Nested/curry-style calls are supported by the evaluator; arguments are collected in order and validated against the function arity.

Control flow
- if / elsif / else with then or blocks:
  val m = if (x > 0) then "pos" else "non-pos"
- while:
  while (x > 0) { x = x - 1 }
- select / case / default (switch-like):
  select (value) {
    case 1: "one"
    case 2: "two"
    default: "other"
  }
- return is supported inside function bodies.

Collections and JSON access
- Map/JSON-style indexing with chains:
  data["users"][0]["name"]

Importing other scripts
- import "module"    // tries module or module.sfl
- Resolution order:
  1) Relative to current working directory
  2) $SFL_HOME/libs/<name>.sfl
- Environment:
  - Set SFL_HOME to a base directory that contains a libs folder if you wish to share/import libraries.

Predefined functions (selected)
- print(x), println(x) — console output
- length(x) — length of strings (error for numbers)
- readFile(path), writeFile(path, content), removeFile(path), listFiles(dir), isDir(path)
- charAt(str, n), subString(str, start, end), toString(x)
- execute(cmd) — run a shell command; returns (exitCode, stdout, stderr)
- httpGet(url) — perform HTTP GET and return body (String)
- readLine() — read a line from stdin
- timeMillis() — current epoch millis
- parse(s) — parse a string into an Expr (first expression)
- eval(expr) — evaluate an Expr and return its value
- iter(x) — get an iterator for arrays or JSON objects; hasNext(it), iterNext(it)

Example script (test.sfl)
-------------------------
// 1. Variables and Arithmetic
val x = 10
val y = 20
val z = x + y * 2
print("Result of x + y * 2 is: ")
print(z)

// 2. Function Definition and Call
def my_add(a, b) {
  a + b
}
val sum = my_add(z, 10)
print("Result of my_add(z, 10) is: ")
print(sum)

// 3. Conditional Expression
val message = if (sum > 50) then {
  "Sum is greater than 50"
} else {
  "Sum is not greater than 50"
}
print(message)

// 4. Print a final message
print("SFL script execution finished.")

Troubleshooting
---------------
- Linker errors for OpenSSL/libidn2 or SDK:
  - Install the required libraries via Homebrew and verify the paths used in build.sbt.
  - Adjust -L (linking) and -isysroot (compile) options to match your system.
- Native toolchain not found:
  - Ensure the Scala Native prerequisites (LLVM/Clang, etc.) are installed for your platform.
- Import cannot find module:
  - Either use a relative path, or set SFL_HOME so that $SFL_HOME/libs/<module>.sfl is resolvable.

License
-------
No license file is provided in this repository. If you plan to distribute, consider adding an appropriate LICENSE.
