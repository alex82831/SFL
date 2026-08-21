// The "SFL Projects" view: every build.sfl in the workspace with its tasks,
// sources and dependencies — the VSCode counterpart of the IntelliJ tool window.
// Everything shown comes from `sfl build describe`; the view never reads
// build.sfl itself, so what it lists is exactly what the build tool will do.

import { execFile } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { promisify } from 'util';
import {
  Event,
  EventEmitter,
  RelativePattern,
  ShellExecution,
  Task,
  TaskGroup,
  TaskScope,
  ThemeIcon,
  TreeDataProvider,
  TreeItem,
  TreeItemCollapsibleState,
  Uri,
  commands,
  tasks,
  window,
  workspace,
} from 'vscode';

const execFileP = promisify(execFile);

export interface ProjectModel {
  root: string;
  name: string;
  version: string;
  main: string | null;
  sourceFiles: string[];
  tasks: string[];
  deps: Record<string, string>;
  error?: string;
}

const BUILTIN_TASKS = ['build', 'run', 'test', 'clean', 'pkg', 'describe', 'tasks', 'init'];
const HIDDEN_TASKS = ['init', 'tasks', 'describe'];

type Node =
  | { kind: 'project'; model: ProjectModel }
  | { kind: 'group'; label: 'Tasks' | 'Sources' | 'Dependencies'; model: ProjectModel }
  | { kind: 'task'; model: ProjectModel; task: string }
  | { kind: 'source'; model: ProjectModel; file: string }
  | { kind: 'dep'; name: string; range: string };

export class SflProjectsProvider implements TreeDataProvider<Node> {
  private readonly changed = new EventEmitter<Node | undefined>();
  readonly onDidChangeTreeData: Event<Node | undefined> = this.changed.event;
  private models: ProjectModel[] = [];
  private loading = false;

  constructor(
    private readonly binary: () => string,
    private readonly env: () => NodeJS.ProcessEnv
  ) {}

  // ---- discovery + describe ------------------------------------------------

  async refresh(): Promise<void> {
    if (this.loading) {
      return;
    }
    this.loading = true;
    this.changed.fire(undefined);
    try {
      const files = await workspace.findFiles(
        '**/build.sfl',
        '{**/node_modules/**,**/build/**,**/sfl_packages/**,**/packages/**,**/.git/**,**/target/**}'
      );
      const roots = [...new Set(files.map((f) => path.dirname(f.fsPath)))].sort();
      const out: ProjectModel[] = [];
      for (const root of roots) {
        out.push(await this.describe(root));
      }
      this.models = out;
    } finally {
      this.loading = false;
      this.changed.fire(undefined);
    }
  }

  private async describe(root: string): Promise<ProjectModel> {
    const base: ProjectModel = {
      root, name: path.basename(root), version: '', main: null, sourceFiles: [], tasks: [], deps: {},
    };
    try {
      const { stdout } = await execFileP(this.binary(), ['build', 'describe'], {
        cwd: root, env: this.env(), timeout: 15000,
      });
      const j = JSON.parse(stdout);
      return {
        root,
        name: typeof j.name === 'string' ? j.name : base.name,
        version: typeof j.version === 'string' ? j.version : '',
        main: typeof j.main === 'string' ? j.main : null,
        sourceFiles: Array.isArray(j.sourceFiles) ? j.sourceFiles : [],
        tasks: Array.isArray(j.tasks) ? j.tasks : [],
        deps: j.deps && typeof j.deps === 'object' ? j.deps : {},
      };
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      return { ...base, error: msg.split('\n')[0] };
    }
  }

  projects(): ProjectModel[] {
    return this.models;
  }

  // ---- tree -------------------------------------------------------------------

  getTreeItem(node: Node): TreeItem {
    switch (node.kind) {
      case 'project': {
        const m = node.model;
        const item = new TreeItem(
          m.version ? `${m.name} ${m.version}` : m.name,
          m.error ? TreeItemCollapsibleState.None : TreeItemCollapsibleState.Expanded
        );
        item.description = m.error ?? (path.relative(workspace.workspaceFolders?.[0]?.uri.fsPath ?? '', m.root) || '.');
        item.tooltip = m.error ? `${m.root}\n${m.error}` : m.root;
        item.iconPath = new ThemeIcon(m.error ? 'error' : 'package');
        item.contextValue = 'sflProject';
        item.resourceUri = Uri.file(m.root);
        return item;
      }
      case 'group': {
        const item = new TreeItem(
          node.label,
          node.label === 'Tasks' ? TreeItemCollapsibleState.Expanded : TreeItemCollapsibleState.Collapsed
        );
        item.iconPath = new ThemeIcon(
          node.label === 'Tasks' ? 'checklist' : node.label === 'Sources' ? 'folder' : 'library'
        );
        return item;
      }
      case 'task': {
        const item = new TreeItem(node.task, TreeItemCollapsibleState.None);
        item.description = BUILTIN_TASKS.includes(node.task) ? 'built-in' : 'build.sfl';
        item.iconPath = new ThemeIcon('play');
        item.contextValue = 'sflTask';
        item.command = { command: 'sfl.projects.runTask', title: 'Run Task', arguments: [node] };
        return item;
      }
      case 'source': {
        const item = new TreeItem(node.file, TreeItemCollapsibleState.None);
        item.description = node.file === node.model.main ? 'main' : undefined;
        item.resourceUri = Uri.file(path.join(node.model.root, node.file));
        item.iconPath = ThemeIcon.File;
        item.command = { command: 'vscode.open', title: 'Open', arguments: [item.resourceUri] };
        return item;
      }
      case 'dep': {
        const item = new TreeItem(node.name, TreeItemCollapsibleState.None);
        item.description = node.range;
        item.iconPath = new ThemeIcon('library');
        return item;
      }
    }
  }

  getChildren(node?: Node): Node[] {
    if (!node) {
      return this.models.map((model) => ({ kind: 'project', model }));
    }
    if (node.kind === 'project') {
      const m = node.model;
      if (m.error) {
        return [];
      }
      const groups: Node[] = [{ kind: 'group', label: 'Tasks', model: m }];
      if (m.sourceFiles.length > 0) {
        groups.push({ kind: 'group', label: 'Sources', model: m });
      }
      if (Object.keys(m.deps).length > 0) {
        groups.push({ kind: 'group', label: 'Dependencies', model: m });
      }
      return groups;
    }
    if (node.kind === 'group') {
      const m = node.model;
      if (node.label === 'Tasks') {
        return m.tasks.filter((t) => !HIDDEN_TASKS.includes(t)).map((task) => ({ kind: 'task', model: m, task }));
      }
      if (node.label === 'Sources') {
        return m.sourceFiles.map((file) => ({ kind: 'source', model: m, file }));
      }
      return Object.entries(m.deps).map(([name, range]) => ({ kind: 'dep', name, range: String(range) }));
    }
    return [];
  }

  // ---- running ------------------------------------------------------------------

  /** `sfl build <task>` as a VSCode task in the project's root, so output lands in the Terminal panel. */
  runTask(model: ProjectModel, task: string): Thenable<unknown> {
    const bin = this.binary();
    const quoted = bin.includes(' ') ? `"${bin}"` : bin;
    const folder = workspace.getWorkspaceFolder(Uri.file(model.root));
    const t = new Task(
      { type: 'sfl', task, project: model.root },
      folder ?? TaskScope.Workspace,
      `${task} · ${model.name}`,
      'sfl',
      new ShellExecution(`${quoted} build ${task}`, { cwd: model.root, env: this.env() as Record<string, string> })
    );
    t.group = task === 'test' ? TaskGroup.Test : task === 'build' ? TaskGroup.Build : undefined;
    t.presentationOptions = { reveal: 2, clear: false, panel: 1 }; // reveal=Always, panel=Shared
    return tasks.executeTask(t);
  }
}

/** Wires the view, its commands and the build.sfl watcher; returns disposables. */
export function registerProjectsView(
  binary: () => string,
  env: () => NodeJS.ProcessEnv
): { provider: SflProjectsProvider; disposables: { dispose(): unknown }[] } {
  const provider = new SflProjectsProvider(binary, env);
  const view = window.createTreeView('sflProjects', { treeDataProvider: provider, showCollapseAll: true });
  const watcher = workspace.createFileSystemWatcher(
    new RelativePattern(workspace.workspaceFolders?.[0] ?? '', '**/build.sfl')
  );
  watcher.onDidChange(() => provider.refresh());
  watcher.onDidCreate(() => provider.refresh());
  watcher.onDidDelete(() => provider.refresh());

  const disposables: { dispose(): unknown }[] = [
    view,
    watcher,
    commands.registerCommand('sfl.projects.refresh', () => provider.refresh()),
    commands.registerCommand('sfl.projects.runTask', (node?: Node) => {
      if (node && node.kind === 'task') {
        return provider.runTask(node.model, node.task);
      }
      if (node && node.kind === 'project' && !node.model.error) {
        return provider.runTask(node.model, 'build');
      }
      return undefined;
    }),
    commands.registerCommand('sfl.projects.buildAll', async () => {
      for (const m of provider.projects()) {
        if (!m.error) {
          await provider.runTask(m, 'build');
        }
      }
    }),
    commands.registerCommand('sfl.projects.openBuildFile', (node?: Node) => {
      if (node && node.kind === 'project') {
        const file = path.join(node.model.root, 'build.sfl');
        if (fs.existsSync(file)) {
          return commands.executeCommand('vscode.open', Uri.file(file));
        }
      }
      return undefined;
    }),
    commands.registerCommand('sfl.projects.settings', () =>
      commands.executeCommand('workbench.action.openSettings', '@ext:fartech.sfl-lang')
    ),
  ];
  void provider.refresh();
  return { provider, disposables };
}
