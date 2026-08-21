package com.fartech.sfl.ide.buildtool;

import com.fartech.sfl.ide.SflIcons;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.Map;

/**
 * The SFL tool window: every linked project with its tasks, sources and
 * dependencies, the way the Gradle and sbt windows show theirs. Double-click a
 * task to run it in a console, a source to open it; the toolbar reloads, links
 * and unlinks projects, builds everything, and opens the settings pages.
 */
public final class SflToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        Panel panel = new Panel(project);
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        toolWindow.getContentManager().addContent(content);
        SflProjectsService service = SflProjectsService.getInstance(project);
        if (service.roots().isEmpty() && !service.isReloading()) {
            service.reload(); // first look: discover and describe
        } else {
            panel.rebuild();
        }
    }

    // ---- tree nodes ------------------------------------------------------------

    /** A linked project root; `model` is null while it is loading or broken. */
    private record ProjectNode(String path, @Nullable SflProjectModel model, @Nullable String error) {
        String title() {
            return model != null ? model.displayName() : new File(path).getName();
        }
    }

    private record TaskNode(SflProjectModel model, String task, boolean userDefined) {
    }

    private record SourceNode(SflProjectModel model, String relativePath, boolean isMain) {
    }

    private record DepNode(String name, String range) {
    }

    private record GroupNode(String label) {
    }

    // ---- the panel ---------------------------------------------------------------

    private static final class Panel extends SimpleToolWindowPanel implements SflProjectsService.Listener {
        private final Project project;
        private final SflProjectsService service;
        private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("SFL");
        private final DefaultTreeModel treeModel = new DefaultTreeModel(root);
        private final Tree tree = new Tree(treeModel);

        Panel(Project project) {
            super(true, true);
            this.project = project;
            this.service = SflProjectsService.getInstance(project);

            tree.setRootVisible(false);
            tree.setShowsRootHandles(true);
            tree.setCellRenderer(new Renderer());
            tree.getEmptyText().setText("No SFL projects — press + to link a directory with a build.sfl");

            DefaultActionGroup toolbarGroup = new DefaultActionGroup(
                    new ReloadAction(), new Separator(),
                    new LinkAction(), new UnlinkAction(), new Separator(),
                    new RunTaskAction(), new BuildAllAction(), new Separator(),
                    new ExpandAllAction(), new CollapseAllAction(), new Separator(),
                    new SettingsAction());
            ActionToolbar toolbar = ActionManager.getInstance()
                    .createActionToolbar("SflToolWindow", toolbarGroup, true);
            toolbar.setTargetComponent(tree);
            setToolbar(toolbar.getComponent());
            setContent(ScrollPaneFactory.createScrollPane(tree));

            DefaultActionGroup popup = new DefaultActionGroup(
                    new RunTaskAction(), new OpenBuildFileAction(), new Separator(),
                    new ReloadAction(), new UnlinkAction());
            PopupHandler.installPopupMenu(tree, popup, "SflToolWindowPopup");

            new DoubleClickListener() {
                @Override
                protected boolean onDoubleClick(@NotNull MouseEvent event) {
                    return activateSelection();
                }
            }.installOn(tree);

            service.addListener(this);
        }

        @Override
        public void projectsChanged() {
            rebuild();
        }

        private @Nullable Object selected() {
            TreePath path = tree.getSelectionPath();
            if (path == null) {
                return null;
            }
            Object last = path.getLastPathComponent();
            return last instanceof DefaultMutableTreeNode n ? n.getUserObject() : null;
        }

        private @Nullable ProjectNode selectedProject() {
            TreePath path = tree.getSelectionPath();
            if (path == null) {
                return null;
            }
            for (Object o : path.getPath()) {
                if (o instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof ProjectNode p) {
                    return p;
                }
            }
            return null;
        }

        /** Double-click / Enter: run a task, open a source, toggle anything else. */
        private boolean activateSelection() {
            Object sel = selected();
            if (sel instanceof TaskNode t) {
                SflBuildOutput.runInConsole(project, t.model().root, t.task());
                return true;
            }
            if (sel instanceof SourceNode s) {
                VirtualFile vf = LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(new File(s.model().root, s.relativePath()));
                if (vf != null) {
                    new OpenFileDescriptor(project, vf).navigate(true);
                }
                return true;
            }
            return false;
        }

        void rebuild() {
            root.removeAllChildren();
            for (String path : service.roots()) {
                SflProjectModel model = service.model(path);
                String error = service.error(path);
                DefaultMutableTreeNode pn = new DefaultMutableTreeNode(new ProjectNode(path, model, error));
                root.add(pn);
                if (model == null) {
                    continue;
                }
                DefaultMutableTreeNode tasks = new DefaultMutableTreeNode(new GroupNode("Tasks"));
                for (String t : model.tasks) {
                    if (t.equals("init") || t.equals("tasks") || t.equals("describe")) {
                        continue; // plumbing, not something to run from a tree
                    }
                    tasks.add(new DefaultMutableTreeNode(
                            new TaskNode(model, t, !SflProjectModel.BUILTIN_TASKS.contains(t))));
                }
                pn.add(tasks);
                if (!model.sourceFiles.isEmpty()) {
                    DefaultMutableTreeNode sources = new DefaultMutableTreeNode(new GroupNode("Sources"));
                    for (String s : model.sourceFiles) {
                        sources.add(new DefaultMutableTreeNode(
                                new SourceNode(model, s, s.equals(model.main))));
                    }
                    pn.add(sources);
                }
                if (!model.deps.isEmpty()) {
                    DefaultMutableTreeNode deps = new DefaultMutableTreeNode(new GroupNode("Dependencies"));
                    for (Map.Entry<String, String> e : model.deps.entrySet()) {
                        deps.add(new DefaultMutableTreeNode(new DepNode(e.getKey(), e.getValue())));
                    }
                    pn.add(deps);
                }
            }
            treeModel.reload();
            // Projects and their task lists open by default; sources stay folded.
            for (int i = 0; i < root.getChildCount(); i++) {
                DefaultMutableTreeNode pn = (DefaultMutableTreeNode) root.getChildAt(i);
                tree.expandPath(new TreePath(pn.getPath()));
                if (pn.getChildCount() > 0) {
                    tree.expandPath(new TreePath(((DefaultMutableTreeNode) pn.getChildAt(0)).getPath()));
                }
            }
        }

        // ---- renderer ----------------------------------------------------------------

        private final class Renderer extends ColoredTreeCellRenderer {
            @Override
            public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected,
                                              boolean expanded, boolean leaf, int row, boolean hasFocus) {
                Object o = value instanceof DefaultMutableTreeNode n ? n.getUserObject() : value;
                if (o instanceof ProjectNode p) {
                    setIcon(p.error() != null ? AllIcons.General.Error : SflIcons.FILE);
                    append(p.title());
                    if (service.isReloading() && p.model() == null && p.error() == null) {
                        append("  loading…", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    } else if (p.error() != null) {
                        append("  " + p.error(), SimpleTextAttributes.ERROR_ATTRIBUTES);
                    }
                    append("  " + p.path(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
                } else if (o instanceof GroupNode g) {
                    setIcon(switch (g.label()) {
                        case "Tasks" -> AllIcons.Nodes.ConfigFolder;
                        case "Sources" -> AllIcons.Nodes.Folder;
                        default -> AllIcons.Nodes.PpLibFolder;
                    });
                    append(g.label(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                } else if (o instanceof TaskNode t) {
                    setIcon(AllIcons.RunConfigurations.TestState.Run);
                    append(t.task());
                    append(t.userDefined() ? "  build.sfl" : "  built-in", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else if (o instanceof SourceNode s) {
                    setIcon(SflIcons.FILE);
                    append(s.relativePath());
                    if (s.isMain()) {
                        append("  main", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    }
                } else if (o instanceof DepNode d) {
                    setIcon(AllIcons.Nodes.PpLib);
                    append(d.name());
                    append("  " + d.range(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else if (o != null) {
                    append(o.toString());
                }
            }
        }

        // ---- actions -------------------------------------------------------------------

        private abstract static class Edt extends AnAction {
            Edt(String text, String description, javax.swing.Icon icon) {
                super(text, description, icon);
            }

            @Override
            public @NotNull ActionUpdateThread getActionUpdateThread() {
                return ActionUpdateThread.EDT;
            }
        }

        private final class ReloadAction extends Edt {
            ReloadAction() {
                super("Reload All SFL Projects", "Re-run sfl build describe for every linked project", AllIcons.Actions.Refresh);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                service.reload();
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!service.isReloading());
            }
        }

        private final class LinkAction extends Edt {
            LinkAction() {
                super("Link SFL Project…", "Add a directory that contains a build.sfl", AllIcons.General.Add);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                VirtualFile dir = FileChooser.chooseFile(
                        FileChooserDescriptorFactory.createSingleFolderDescriptor()
                                .withTitle("Link SFL Project")
                                .withDescription("Choose a directory that contains a build.sfl"),
                        project, null);
                if (dir == null) {
                    return;
                }
                File io = VfsUtilCore.virtualToIoFile(dir);
                if (!new File(io, "build.sfl").isFile()) {
                    SflNotifications.warn(project, "Not an SFL project",
                            io.getPath() + " has no build.sfl. Create one with 'sfl build init'.");
                    return;
                }
                service.link(io);
                service.reload();
            }
        }

        private final class UnlinkAction extends Edt {
            UnlinkAction() {
                super("Unlink SFL Project", "Remove the selected project from this window", AllIcons.General.Remove);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ProjectNode p = selectedProject();
                if (p != null) {
                    service.unlink(p.path());
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(selectedProject() != null);
            }
        }

        private final class RunTaskAction extends Edt {
            RunTaskAction() {
                super("Run Task", "Run the selected task (or the project's build)", AllIcons.Actions.Execute);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                Object sel = selected();
                if (sel instanceof TaskNode t) {
                    SflBuildOutput.runInConsole(project, t.model().root, t.task());
                    return;
                }
                ProjectNode p = selectedProject();
                if (p != null && p.model() != null) {
                    SflBuildOutput.runInConsole(project, p.model().root, "build");
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                Object sel = selected();
                ProjectNode p = selectedProject();
                e.getPresentation().setEnabled(sel instanceof TaskNode || (p != null && p.model() != null));
            }
        }

        private final class BuildAllAction extends Edt {
            BuildAllAction() {
                super("Build All SFL Projects", "Run sfl build in every linked project, output in the Build window", AllIcons.Actions.Compile);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ApplicationManager.getApplication().executeOnPooledThread(() -> {
                    for (String path : service.roots()) {
                        if (service.model(path) != null) {
                            SflBuildOutput.buildInBuildView(project, new File(path));
                        }
                    }
                });
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(!service.roots().isEmpty());
            }
        }

        private final class OpenBuildFileAction extends Edt {
            OpenBuildFileAction() {
                super("Open build.sfl", "Open the selected project's build file", SflIcons.FILE);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ProjectNode p = selectedProject();
                if (p == null) {
                    return;
                }
                VirtualFile vf = LocalFileSystem.getInstance()
                        .refreshAndFindFileByIoFile(new File(p.path(), "build.sfl"));
                if (vf != null) {
                    new OpenFileDescriptor(project, vf).navigate(true);
                }
            }

            @Override
            public void update(@NotNull AnActionEvent e) {
                e.getPresentation().setEnabled(selectedProject() != null);
            }
        }

        private final class ExpandAllAction extends Edt {
            ExpandAllAction() {
                super("Expand All", null, AllIcons.Actions.Expandall);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TreeUtil.expandAll(tree);
            }
        }

        private final class CollapseAllAction extends Edt {
            CollapseAllAction() {
                super("Collapse All", null, AllIcons.Actions.Collapseall);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                TreeUtil.collapseAll(tree, 1);
            }
        }

        private final class SettingsAction extends Edt {
            SettingsAction() {
                super("SFL Build Tool Settings", "Linked projects, auto-detection, binary and language server", AllIcons.General.Settings);
            }

            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SflBuildToolConfigurable.class);
            }
        }
    }
}
