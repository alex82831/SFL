// The SFL extension is deliberately thin: every language smart lives in the
// `sfl lsp` server inside the sfl binary itself, so this file only finds the
// binary, wires the Language Server Protocol client, and offers two terminal
// commands. If a feature is missing here, it belongs in the server.

import { execFile } from 'child_process';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { promisify } from 'util';
import {
  workspace,
  window,
  commands,
  debug,
  tasks,
  DebugAdapterExecutable,
  ExtensionContext,
  ShellExecution,
  Task,
  TaskGroup,
  TaskScope,
  Terminal,
  Uri,
} from 'vscode';

const execFileP = promisify(execFile);
import { registerProjectsView } from './projects';
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from 'vscode-languageclient/node';

let client: LanguageClient | undefined;
let terminal: Terminal | undefined;

// SFL_HOME and diagnostic switches travel to every sfl child process as
// environment — the same contract the IntelliJ plugin uses.
function sflEnv(): NodeJS.ProcessEnv {
  const cfg = workspace.getConfiguration('sfl');
  const env: NodeJS.ProcessEnv = { ...process.env };
  const home = (cfg.get<string>('home') ?? '').trim();
  if (home.length > 0) {
    env.SFL_HOME = home.startsWith('~') ? path.join(os.homedir(), home.slice(1)) : home;
  }
  if (cfg.get<boolean>('diagnostics.undefinedNames') === false) {
    env.SFL_LSP_UNDEFINED = 'off';
  }
  return env;
}

function sflBinary(): string {
  const configured = workspace.getConfiguration('sfl').get<string>('path');
  if (configured && configured.trim().length > 0) {
    return configured.trim();
  }
  const name = process.platform === 'win32' ? 'sfl.exe' : 'sfl';
  // A GUI-launched editor does not always carry the terminal's PATH, so check
  // it by hand and then the places people actually install things.
  const dirs = (process.env.PATH ?? '').split(path.delimiter).concat(
    process.platform === 'win32'
      ? [path.join(os.homedir(), 'bin')]
      : ['/opt/homebrew/bin', '/usr/local/bin', path.join(os.homedir(), '.local/bin')]
  );
  for (const dir of dirs) {
    if (dir.length === 0) {
      continue;
    }
    const candidate = path.join(dir, name);
    try {
      fs.accessSync(candidate, fs.constants.X_OK);
      return candidate;
    } catch {
      // keep looking
    }
  }
  return name; // last resort: let the OS try
}

async function startClient(context: ExtensionContext): Promise<void> {
  const command = sflBinary();
  // cwd decides what ./sfl_packages and ./packages mean to the importer —
  // match the terminal by resolving them against the workspace root.
  const cwd = workspace.workspaceFolders?.[0]?.uri.fsPath;
  const serverOptions: ServerOptions = {
    command,
    args: ['lsp'],
    options: { env: sflEnv(), cwd },
  };
  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: 'file', language: 'sfl' },
      { scheme: 'untitled', language: 'sfl' },
    ],
  };
  client = new LanguageClient('sfl', 'SFL Language Server', serverOptions, clientOptions);
  try {
    await client.start();
  } catch {
    client = undefined;
    void window.showWarningMessage(
      `SFL: cannot start '${command} lsp'. Syntax highlighting still works; ` +
        'set "sfl.path" to your sfl binary for diagnostics and completion.'
    );
  }
}

function runTerminal(): Terminal {
  if (!terminal || terminal.exitStatus !== undefined) {
    terminal = window.createTerminal('sfl');
  }
  terminal.show(true);
  return terminal;
}

function quoted(p: string): string {
  return p.includes(' ') ? `"${p}"` : p;
}

async function runFile(compile: boolean): Promise<void> {
  const editor = window.activeTextEditor;
  if (!editor || editor.document.languageId !== 'sfl') {
    return;
  }
  await editor.document.save();
  const file = editor.document.uri.fsPath;
  const bin = quoted(sflBinary());
  const cmd = compile ? `${bin} -c ${quoted(file)}` : `${bin} ${quoted(file)}`;
  runTerminal().sendText(cmd);
}

async function debugFile(): Promise<void> {
  const editor = window.activeTextEditor;
  if (!editor || editor.document.languageId !== 'sfl') {
    return;
  }
  await editor.document.save();
  const file = editor.document.uri.fsPath;
  await debug.startDebugging(workspace.getWorkspaceFolder(editor.document.uri), {
    type: 'sfl',
    request: 'launch',
    name: `Debug ${path.basename(file)}`,
    program: file,
  });
}

// Mirrors `sfl build init` for machines without the binary; if you change one,
// change the other: buildtool/main.sfl, __taskInit.
function writeFallbackScaffold(dir: string, name: string): void {
  fs.mkdirSync(path.join(dir, 'src'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'tests'), { recursive: true });
  fs.writeFileSync(
    path.join(dir, 'build.sfl'),
    'project({\n' +
      `  "name": "${name}",\n` +
      '  "version": "0.1.0",\n' +
      '  "main": "src/main.sfl"\n' +
      '})\n' +
      '\n' +
      "// Tasks are plain SFL; the function receives the task's arguments.\n" +
      '// `sfl build greet` runs this one:\n' +
      '// task("greet", (args) -> println("hello from a task"), "say hello")\n'
  );
  fs.writeFileSync(path.join(dir, 'src', 'main.sfl'), `println("hello, ${name}")\n`);
  fs.writeFileSync(
    path.join(dir, 'tests', 'smoke.sfl'),
    '// Every .sfl in this directory runs under `sfl build test`; a non-zero\n' +
      '// exit fails the suite.\n' +
      'val two = 1 + 1\n' +
      'if (two != 2) { eprintln("arithmetic is broken"); exit(1) }\n' +
      'println("smoke: ok")\n'
  );
  fs.writeFileSync(path.join(dir, '.gitignore'), 'build/\n');
}

async function newProject(): Promise<void> {
  const parentSel = await window.showOpenDialog({
    canSelectFiles: false,
    canSelectFolders: true,
    canSelectMany: false,
    openLabel: 'Use as Parent Folder',
    title: 'SFL: where should the project be created?',
  });
  if (!parentSel || parentSel.length === 0) {
    return;
  }
  const name = await window.showInputBox({
    prompt: 'Project name',
    value: 'hello-sfl',
    validateInput: (v) =>
      /^[A-Za-z_][A-Za-z0-9_-]*$/.test(v)
        ? undefined
        : 'Letters, digits, _ or -, starting with a letter or _',
  });
  if (!name) {
    return;
  }
  const dir = path.join(parentSel[0].fsPath, name);
  if (fs.existsSync(dir) && fs.readdirSync(dir).length > 0) {
    void window.showErrorMessage(`SFL: '${dir}' already exists and is not empty.`);
    return;
  }
  fs.mkdirSync(dir, { recursive: true });
  try {
    // The build tool is the scaffolder of record; the fallback only covers
    // machines where the binary is not installed yet.
    await execFileP(sflBinary(), ['build', 'init', name], { cwd: dir });
  } catch {
    writeFallbackScaffold(dir, name);
  }
  const choice = await window.showInformationMessage(
    `SFL project '${name}' created.`,
    'Open',
    'Open in New Window'
  );
  if (choice) {
    await commands.executeCommand('vscode.openFolder', Uri.file(dir), {
      forceNewWindow: choice === 'Open in New Window',
    });
  }
}

// Cmd/Ctrl+Shift+B in a workspace with a build.sfl runs the build tool; the
// test task mirrors `sfl build test`.
function provideSflTasks(): Task[] {
  const out: Task[] = [];
  for (const folder of workspace.workspaceFolders ?? []) {
    if (!fs.existsSync(path.join(folder.uri.fsPath, 'build.sfl'))) {
      continue;
    }
    const bin = quoted(sflBinary());
    const build = new Task(
      { type: 'sfl', task: 'build' }, folder, 'build', 'sfl',
      new ShellExecution(`${bin} build`, { env: sflEnv() as Record<string, string> })
    );
    build.group = TaskGroup.Build;
    const test = new Task(
      { type: 'sfl', task: 'test' }, folder, 'test', 'sfl',
      new ShellExecution(`${bin} build test`, { env: sflEnv() as Record<string, string> })
    );
    test.group = TaskGroup.Test;
    out.push(build, test);
  }
  return out;
}

export async function activate(context: ExtensionContext): Promise<void> {
  await startClient(context);

  // The SFL Projects view: every build.sfl, its tasks, sources and deps.
  context.subscriptions.push(...registerProjectsView(sflBinary, sflEnv).disposables);

  context.subscriptions.push(
    tasks.registerTaskProvider('sfl', {
      provideTasks: () => provideSflTasks(),
      resolveTask: (t) => {
        const def = t.definition as { type: string; task?: string };
        if (def.type !== 'sfl' || !def.task) {
          return undefined;
        }
        const bin = quoted(sflBinary());
        const args = def.task === 'build' ? 'build' : `build ${def.task}`;
        const root = (def as { project?: string }).project;
        const task = new Task(def, t.scope ?? TaskScope.Workspace, def.task, 'sfl',
          new ShellExecution(`${bin} ${args}`, {
            cwd: root, env: sflEnv() as Record<string, string>,
          }));
        task.group = def.task === 'test' ? TaskGroup.Test : TaskGroup.Build;
        return task;
      },
    }),
    // The debug adapter is the sfl binary itself; nothing to bundle or download.
    debug.registerDebugAdapterDescriptorFactory('sfl', {
      createDebugAdapterDescriptor: () =>
        new DebugAdapterExecutable(sflBinary(), ['dap'], { env: sflEnv() as Record<string, string> }),
    }),
    // F5 with no launch.json: a script is its own program, so debugging the
    // current file needs no configuration at all.
    debug.registerDebugConfigurationProvider('sfl', {
      resolveDebugConfiguration: (_folder, config) => {
        if (!config.type && !config.request && !config.name) {
          const editor = window.activeTextEditor;
          if (editor && editor.document.languageId === 'sfl') {
            return {
              type: 'sfl',
              request: 'launch',
              name: `Debug ${path.basename(editor.document.uri.fsPath)}`,
              program: editor.document.uri.fsPath,
            };
          }
        }
        return config;
      },
    }),
    commands.registerCommand('sfl.newProject', () => newProject()),
    commands.registerCommand('sfl.runFile', () => runFile(false)),
    commands.registerCommand('sfl.debugFile', () => debugFile()),
    commands.registerCommand('sfl.compileFile', () => runFile(true)),
    commands.registerCommand('sfl.restartServer', async () => {
      if (client) {
        await client.restart();
      } else {
        await startClient(context);
      }
    }),
    workspace.onDidChangeConfiguration(async (e) => {
      if (
        e.affectsConfiguration('sfl.path') ||
        e.affectsConfiguration('sfl.home') ||
        e.affectsConfiguration('sfl.diagnostics')
      ) {
        if (client) {
          await client.stop();
          client = undefined;
        }
        await startClient(context);
      }
    })
  );
}

export function deactivate(): Thenable<void> | undefined {
  return client?.stop();
}
