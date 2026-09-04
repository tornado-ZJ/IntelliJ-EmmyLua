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
 * Project-local settings for the native embedded GUI Lua editor.
 */
@State(name = "EmmyLuaGuiEditorSettings", storages = @Storage("emmy-gui-editor.xml"))
public final class GuiEditorProjectSettings implements PersistentStateComponent<GuiEditorProjectSettings.StateData> {
    public static final class StateData {
        public boolean enabled = true;
        public List<String> sourceRoots = new ArrayList<>();
        public List<String> resourceRoots = new ArrayList<>();
        public int canvasWidth = 1136;
        public int canvasHeight = 640;
        public int gridSize = 10;
        public boolean showGrid = true;
        public boolean snapToGrid = true;
        public boolean liveSync = true;

        // Kept only so projects configured with 1.4.26.4 can be loaded without XML errors.
        @Deprecated public boolean autoLaunch = false;
        @Deprecated public String executablePath = "";
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
    public void loadState(@NotNull StateData loaded) {
        XmlSerializerUtil.copyBean(loaded, state);
        normalizeState();
    }

    public boolean isEnabled() {
        return state.enabled;
    }

    public void setEnabled(boolean enabled) {
        state.enabled = enabled;
    }

    public @NotNull List<String> getSourceRoots() {
        normalizeState();
        return List.copyOf(state.sourceRoots);
    }

    public void setSourceRoots(List<String> roots) {
        state.sourceRoots = sanitizeRoots(roots);
    }

    public @NotNull List<String> getResourceRoots() {
        normalizeState();
        return List.copyOf(state.resourceRoots);
    }

    public void setResourceRoots(List<String> roots) {
        state.resourceRoots = sanitizeRoots(roots);
    }

    public int getCanvasWidth() {
        return state.canvasWidth;
    }

    public void setCanvasWidth(int value) {
        state.canvasWidth = clamp(value, 160, 8192);
    }

    public int getCanvasHeight() {
        return state.canvasHeight;
    }

    public void setCanvasHeight(int value) {
        state.canvasHeight = clamp(value, 120, 8192);
    }

    public int getGridSize() {
        return state.gridSize;
    }

    public void setGridSize(int value) {
        state.gridSize = clamp(value, 1, 256);
    }

    public boolean isShowGrid() {
        return state.showGrid;
    }

    public void setShowGrid(boolean value) {
        state.showGrid = value;
    }

    public boolean isSnapToGrid() {
        return state.snapToGrid;
    }

    public void setSnapToGrid(boolean value) {
        state.snapToGrid = value;
    }

    public boolean isLiveSync() {
        return state.liveSync;
    }

    public void setLiveSync(boolean value) {
        state.liveSync = value;
    }

    private void normalizeState() {
        state.sourceRoots = sanitizeRoots(state.sourceRoots);
        state.resourceRoots = sanitizeRoots(state.resourceRoots);
        state.canvasWidth = clamp(state.canvasWidth, 160, 8192);
        state.canvasHeight = clamp(state.canvasHeight, 120, 8192);
        state.gridSize = clamp(state.gridSize, 1, 256);
        if (state.executablePath == null) {
            state.executablePath = "";
        }
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

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
