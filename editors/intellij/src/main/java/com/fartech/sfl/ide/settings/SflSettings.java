package com.fartech.sfl.ide.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Application-level settings. One field on purpose: where the sfl binary is.
 * Everything else the plugin knows, it asks the binary.
 */
@Service(Service.Level.APP)
@State(name = "SflSettings", storages = @Storage("sfl.xml"))
public final class SflSettings implements PersistentStateComponent<SflSettings.State> {

    public static final class State {
        public String path = "";
        public boolean serverEnabled = true;
    }

    private State state = new State();

    public static SflSettings getInstance() {
        return ApplicationManager.getApplication().getService(SflSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public String getPath() {
        return state.path == null ? "" : state.path;
    }

    public void setPath(String path) {
        state.path = path == null ? "" : path.trim();
    }

    public boolean isServerEnabled() {
        return state.serverEnabled;
    }

    public void setServerEnabled(boolean enabled) {
        state.serverEnabled = enabled;
    }
}
