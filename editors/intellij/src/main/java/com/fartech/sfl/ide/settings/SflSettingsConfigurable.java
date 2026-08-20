package com.fartech.sfl.ide.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.io.File;

/** Settings → Tools → SFL. */
public final class SflSettingsConfigurable implements Configurable {

    private JBTextField pathField;
    private JBLabel status;

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "SFL";
    }

    @Override
    public @Nullable JComponent createComponent() {
        pathField = new JBTextField();
        pathField.getEmptyText().setText("sfl on PATH, SFL_PATH, or the usual install directories");
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
        return pathField != null
                && !pathField.getText().trim().equals(SflSettings.getInstance().getPath());
    }

    @Override
    public void apply() {
        SflSettings.getInstance().setPath(pathField.getText());
    }

    @Override
    public void reset() {
        if (pathField != null) {
            pathField.setText(SflSettings.getInstance().getPath());
            refreshStatus();
        }
    }
}
