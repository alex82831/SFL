package com.fartech.sfl.ide.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import com.redhat.devtools.lsp4ij.LanguageServerManager;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.io.File;

/** Settings → Tools → SFL. */
public final class SflSettingsConfigurable implements Configurable {

    private JBTextField pathField;
    private JBTextField homeField;
    private JBCheckBox serverBox;
    private JBCheckBox undefinedBox;
    private JBLabel status;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "SFL";
    }

    @Override
    public @Nullable JComponent createComponent() {
        pathField = new JBTextField();
        pathField.getEmptyText().setText("sfl on PATH, SFL_PATH, or the usual install directories");
        homeField = new JBTextField();
        homeField.getEmptyText().setText("directory whose libs/ and packages/ imports search ($SFL_HOME)");
        serverBox = new JBCheckBox("Enable the language server (diagnostics, completion, hover, navigation)", true);
        undefinedBox = new JBCheckBox("Flag undefined variables while typing", true);
        status = new JBLabel();
        status.setForeground(UIUtil.getContextHelpForeground());
        pathField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { refreshStatus(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { refreshStatus(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { refreshStatus(); }
        });

        JPanel panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Path to the sfl binary:", pathField, 1, false)
                .addComponentToRightColumn(status, 1)
                .addLabeledComponent("SFL_HOME:", homeField, 8, false)
                .addComponent(serverBox, 8)
                .addComponent(undefinedBox, 1)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    private void refreshStatus() {
        String text = pathField.getText().trim();
        if (text.isEmpty()) {
            File found = SflBinaryLocator.resolve();
            status.setText(found == null
                    ? "Not found — diagnostics and completion stay off until this is set."
                    : "Auto-detected: " + found.getAbsolutePath());
        } else {
            File f = new File(expand(text));
            status.setText(f.isFile() && f.canExecute()
                    ? "OK: " + f.getAbsolutePath()
                    : "No executable at this path.");
        }
    }

    private static String expand(String p) {
        if (p.startsWith("~" + File.separator) || p.equals("~")) {
            return System.getProperty("user.home") + p.substring(1);
        }
        return p;
    }

    @Override
    public boolean isModified() {
        if (pathField == null) {
            return false;
        }
        SflSettings s = SflSettings.getInstance();
        return !pathField.getText().trim().equals(s.getPath())
                || !homeField.getText().trim().equals(s.getHome())
                || serverBox.isSelected() != s.isServerEnabled()
                || undefinedBox.isSelected() != s.isUndefinedCheck();
    }

    @Override
    public void apply() {
        SflSettings s = SflSettings.getInstance();
        s.setPath(pathField.getText());
        s.setHome(homeField.getText());
        s.setServerEnabled(serverBox.isSelected());
        s.setUndefinedCheck(undefinedBox.isSelected());
        // The server reads its settings from the environment at start, so a
        // change only lands after a restart; stopping is enough — LSP4IJ starts
        // it again on demand for the next open .sfl file.
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            try {
                LanguageServerManager.getInstance(project).stop("sflLanguageServer");
            } catch (Exception ignored) {
                // A server that never started has nothing to stop.
            }
        }
    }

    @Override
    public void reset() {
        if (pathField != null) {
            SflSettings s = SflSettings.getInstance();
            pathField.setText(s.getPath());
            homeField.setText(s.getHome());
            serverBox.setSelected(s.isServerEnabled());
            undefinedBox.setSelected(s.isUndefinedCheck());
            refreshStatus();
        }
    }
}
