package com.fartech.sfl.ide.project;

import com.fartech.sfl.ide.settings.SflBinaryLocator;
import com.intellij.ide.util.projectWizard.ModuleWizardStep;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.io.File;

/** The one page of SFL options in the New Project wizard. */
final class SflModuleWizardStep extends ModuleWizardStep {
    private final SflModuleBuilder builder;
    private final JBTextField version = new JBTextField("0.1.0");
    private final JBCheckBox tests = new JBCheckBox("Generate a sample test (tests/smoke.sfl)", true);
    private final JBLabel binaryStatus = new JBLabel();
    private JComponent panel;

    SflModuleWizardStep(SflModuleBuilder builder) {
        this.builder = builder;
    }

    @Override
    public JComponent getComponent() {
        if (panel == null) {
            File bin = SflBinaryLocator.resolve();
            binaryStatus.setText(bin == null
                    ? "sfl binary: not found. The project still generates; set the path in "
                        + "Settings | Tools | SFL to build, run and get diagnostics."
                    : "sfl binary: " + bin.getAbsolutePath());
            binaryStatus.setForeground(UIUtil.getContextHelpForeground());
            panel = FormBuilder.createFormBuilder()
                    .addLabeledComponent("Version:", version, 1, false)
                    .addComponent(tests, 1)
                    .addComponent(binaryStatus, 8)
                    .addComponentFillVertically(new JPanel(), 0)
                    .getPanel();
        }
        return panel;
    }

    @Override
    public void updateDataModel() {
        String v = version.getText().trim();
        builder.projectVersion = v.isEmpty() ? "0.1.0" : v;
        builder.withTests = tests.isSelected();
    }
}
