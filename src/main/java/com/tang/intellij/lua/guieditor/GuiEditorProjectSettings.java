/*
 * Copyright (c) 2026.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.tang.intellij.lua.guieditor;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Project-local routing settings for the external GUI Lua editor.
 */
@State(name = "EmmyLuaGuiEditorSettings", storages = @Storage("emmy-gui-editor.xml"))
public final class GuiEditorProjectSettings implements PersistentStateComponent<GuiEditorProjectSettings.StateData> {
    public static final class StateData {
        public boolean enabled = true;
        public boolean autoLaunch = true;
        public String executablePath = "";
        public List<String> sourceRoots = new ArrayList<>();
    }

    private StateData state = new StateData();

    public static @NotNull GuiEditorProjectSettings getInstance(@NotNull Project project) {
        return project.getService(GuiEditorProjectSettings.class);
    }

    @Override
    public @NotNull StateData getState() {
        normalizeState();
        return state;
    }

    @Override
    public void loadState(@NotNull StateData state) {
        XmlSerializerUtil.copyBean(state, this.state);
        normalizeState();
    }

    public boolean isEnabled() {
        return state.enabled;
    }

    public void setEnabled(boolean enabled) {
        state.enabled = enabled;
    }

    public boolean isAutoLaunch() {
        return state.autoLaunch;
    }

    public void setAutoLaunch(boolean autoLaunch) {
        state.autoLaunch = autoLaunch;
    }

    public @NotNull String getExecutablePath() {
        return state.executablePath == null ? "" : state.executablePath.trim();
    }

    public void setExecutablePath(String executablePath) {
        state.executablePath = executablePath == null ? "" : executablePath.trim();
    }

    public @NotNull List<String> getSourceRoots() {
        normalizeState();
        return List.copyOf(state.sourceRoots);
    }

    public void setSourceRoots(List<String> sourceRoots) {
        state.sourceRoots = sanitizeRoots(sourceRoots);
    }

    private void normalizeState() {
        if (state.executablePath == null) {
            state.executablePath = "";
        }
        state.sourceRoots = sanitizeRoots(state.sourceRoots);
    }

    private static @NotNull List<String> sanitizeRoots(List<String> roots) {
        Set<String> unique = new LinkedHashSet<>();
        if (roots != null) {
            for (String root : roots) {
                if (root == null) {
                    continue;
                }
                String value = root.trim();
                if (!value.isEmpty()) {
                    unique.add(value);
                }
            }
        }
        return new ArrayList<>(unique);
    }
}
