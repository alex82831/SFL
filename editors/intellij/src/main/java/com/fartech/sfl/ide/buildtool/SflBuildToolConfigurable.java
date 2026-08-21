package com.fartech.sfl.ide.buildtool;

import com.fartech.sfl.ide.settings.SflSettingsConfigurable;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.ActionLink;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Settings → Build, Execution, Deployment → Build Tools → SFL: the linked
 * projects and how they are found — the per-IDE-project half of the
 * configuration. The binary, SFL_HOME and language-server switches live under
 * Tools → SFL because they are per machine, not per project.
 */
public final class SflBuildToolConfigurable implements Configurable {
    private final Project project;
    private final DefaultListModel<String> listModel = new DefaultListModel<>();
    private JBList<String> list;
    private JBCheckBox autoDetect;

    public SflBuildToolConfigurable(Project project) {
        this.project = project;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "SFL";
    }

    @Override
    public @Nullable JComponent createComponent() {
        list = new JBList<>(listModel);
        list.getEmptyText().setText("No linked SFL projects");
        JPanel listPanel = ToolbarDecorator.createDecorator(list)
                .setAddAction(button -> {
                    VirtualFile dir = FileChooser.chooseFile(
                            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                                    .withTitle("Link SFL Project")
                                    .withDescription("Choose a directory that contains a build.sfl"),
                            project, null);
                    if (dir != null) {
                        File io = VfsUtilCore.virtualToIoFile(dir);
                        if (new File(io, "build.sfl").isFile() && !listModel.contains(io.getAbsolutePath())) {
                            listModel.addElement(io.getAbsolutePath());
                        }
                    }
                })
                .setRemoveAction(button -> {
                    int i = list.getSelectedIndex();
                    if (i >= 0) {
                        listModel.remove(i);
                    }
                })
                .disableUpDownActions()
                .createPanel();

        autoDetect = new JBCheckBox("Detect build.sfl files under the content roots automatically", true);
        JBLabel hint = new JBLabel("Each linked directory is an SFL project: its tasks, sources and "
                + "dependencies appear in the SFL tool window, and Build All builds every one.");
        hint.setForeground(UIUtil.getContextHelpForeground());
        // The (String, ActionListener) constructor — spelled out, because the
        // Kotlin overload taking a function type makes a bare lambda ambiguous.
        java.awt.event.ActionListener openToolSettings =
                e -> ShowSettingsUtil.getInstance().showSettingsDialog(project, SflSettingsConfigurable.class);
        ActionLink toolSettings = new ActionLink("Binary, SFL_HOME and language server: Tools → SFL", openToolSettings);

        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponentFillVertically("Linked SFL projects:", listPanel)
                .addComponent(autoDetect, 8)
                .addComponent(hint, 4)
                .addComponent(toolSettings, 8)
                .getPanel();
        reset();
        return panel;
    }

    private List<String> current() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < listModel.size(); i++) out.add(listModel.get(i));
        return out;
    }

    @Override
    public boolean isModified() {
        if (list == null) {
            return false;
        }
        SflProjectsService s = SflProjectsService.getInstance(project);
        return !current().equals(s.roots()) || autoDetect.isSelected() != s.isAutoDetect();
    }

    @Override
    public void apply() {
        SflProjectsService s = SflProjectsService.getInstance(project);
        List<String> wanted = current();
        for (String existing : s.roots()) {
            if (!wanted.contains(existing)) {
                s.unlink(existing);
            }
        }
        for (String path : wanted) {
            s.link(new File(path));
        }
        s.setAutoDetect(autoDetect.isSelected());
        s.reload();
    }

    @Override
    public void reset() {
        if (list == null) {
            return;
        }
        SflProjectsService s = SflProjectsService.getInstance(project);
        listModel.clear();
        List<String> roots = new ArrayList<>(s.roots());
        Collections.sort(roots);
        for (String r : roots) listModel.addElement(r);
        autoDetect.setSelected(s.isAutoDetect());
    }
}
