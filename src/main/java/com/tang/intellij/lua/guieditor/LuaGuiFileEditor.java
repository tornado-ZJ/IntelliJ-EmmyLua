package com.tang.intellij.lua.guieditor;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

final class LuaGuiFileEditor extends UserDataHolderBase implements FileEditor {
    private final Project project;
    private final VirtualFile file;
    private final LuaGuiDesignerPanel panel;
    private final PropertyChangeSupport changes = new PropertyChangeSupport(this);
    private final boolean nativeDetachedInstance;
    private boolean detachScheduled;

    LuaGuiFileEditor(Project project, VirtualFile file) {
        this.project = project;
        this.file = file;
        this.nativeDetachedInstance=LuaGuiFileEditorProvider.isOpeningDetached();
        this.panel = new LuaGuiDesignerPanel(project, file, modified -> {
            boolean next = modified.booleanValue();
            changes.firePropertyChange(PROP_MODIFIED, !next, next);
        }, () -> LuaGuiFileEditorProvider.openSource(project,file));
    }
    @Override public @NotNull VirtualFile getFile() { return file; }
    @Override public @NotNull JComponent getComponent() { return panel; }
    @Override public @Nullable JComponent getPreferredFocusedComponent() { return panel.preferredFocus(); }
    @Override public @NotNull String getName() { return "GUI 设计器"; }
    @Override public void setState(@NotNull FileEditorState state) { }
    @Override public boolean isModified() { return panel.isModified(); }
    @Override public boolean isValid() { return file.isValid(); }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) { changes.addPropertyChangeListener(listener); }
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) { changes.removePropertyChangeListener(listener); }
    @Override public @Nullable FileEditorLocation getCurrentLocation() { return null; }
    @Override public void dispose() { panel.dispose(); }
    @Override public void selectNotify() { panel.reload();if(!nativeDetachedInstance&&!detachScheduled){detachScheduled=true;ApplicationManager.getApplication().invokeLater(()->LuaGuiFileEditorProvider.detachFromMainWindow(project,file,panel));} }
}
