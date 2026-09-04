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

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Adds a GUI-editor tab only for Lua files located below explicitly configured roots.
 * The standard EmmyLua text editor remains available as a second tab.
 */
public final class GuiEditorFileEditorProvider implements FileEditorProvider, DumbAware {
    public static final String EDITOR_TYPE_ID = "EmmyLua-GUI-Editor";

    @Override
    public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return GuiEditorPathMatcher.isManagedLuaFile(project, file, GuiEditorProjectSettings.getInstance(project));
    }

    @Override
    public boolean acceptRequiresReadAction() {
        return false;
    }

    @Override
    public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new GuiEditorFileEditor(project, file);
    }

    @Override
    public @NotNull String getEditorTypeId() {
        return EDITOR_TYPE_ID;
    }

    @Override
    public @NotNull FileEditorPolicy getPolicy() {
        // GUI first, normal EmmyLua text editor kept as a safe fallback and for source editing.
        return FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR;
    }
}
