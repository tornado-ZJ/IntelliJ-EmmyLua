package com.tang.intellij.lua.guieditor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@State(name = "EmmyLuaGuiEditor", storages = @Storage("emmy-gui-editor.xml"))
public final class GuiEditorSettings implements PersistentStateComponent<GuiEditorSettings.State> {
    public static final class State {
        public List<String> guiExportRoots = new ArrayList<>();
        public boolean showGrid = true;
        public int gridSize = 10;
        public List<String> contextMenuPropertyKeys = new ArrayList<>(List.of(
                "visible", "touch", "x", "y", "width", "height", "scaleX", "scaleY", "rotation", "opacity", "objectConfig", "eventBody"));
        public int contextMenuSchemaVersion;
    }

    private State state = new State();

    public static GuiEditorSettings getInstance() {
        GuiEditorSettings settings=ApplicationManager.getApplication().getService(GuiEditorSettings.class);settings.normalizeState();return settings;
    }

    @Override public @NotNull State getState() { normalizeState();return state; }
    @Override public void loadState(@NotNull State state) {
        if(state.guiExportRoots==null)state.guiExportRoots=new ArrayList<>();
        if(state.contextMenuPropertyKeys==null)state.contextMenuPropertyKeys=new ArrayList<>();
        this.state = state;normalizeState();
    }

    private void normalizeState(){if(state.contextMenuSchemaVersion<1){if(!state.contextMenuPropertyKeys.contains("eventBody"))state.contextMenuPropertyKeys.add("eventBody");state.contextMenuSchemaVersion=1;}}

    public boolean accepts(String filePath) {
        if (filePath == null || !filePath.toLowerCase().endsWith(".lua")) return false;
        Path file = normalize(filePath);
        if (file == null) return false;
        for (String value : state.guiExportRoots) {
            Path root = normalize(value);
            if (root != null && file.startsWith(root)) return true;
        }
        return false;
    }

    private static Path normalize(String value) {
        try { return Paths.get(value).toAbsolutePath().normalize(); }
        catch (Exception ignored) { return null; }
    }
}
