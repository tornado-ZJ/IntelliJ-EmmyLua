package com.tang.intellij.lua.guieditor;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorPolicy;
import com.intellij.openapi.fileEditor.FileEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.fileEditor.impl.EditorWindow;
import com.intellij.openapi.fileEditor.impl.EditorsSplitters;
import com.intellij.openapi.fileEditor.impl.FileEditorManagerImpl;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;

public final class LuaGuiFileEditorProvider implements FileEditorProvider, DumbAware {
    private static final ThreadLocal<Boolean> OPENING_SOURCE = ThreadLocal.withInitial(()->false);
    private static final ThreadLocal<Boolean> OPENING_DETACHED = ThreadLocal.withInitial(()->false);

    @Override public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return !OPENING_SOURCE.get()&&!file.isDirectory()&&GuiEditorSettings.getInstance().accepts(file.getPath());
    }
    @Override public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new LuaGuiFileEditor(project, file);
    }
    @Override public @NotNull @NonNls String getEditorTypeId() { return "emmy-lua-gui-designer"; }
    @Override public @NotNull FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }

    static void openSource(Project project,VirtualFile file){OPENING_SOURCE.set(true);try{FileEditorManager.getInstance(project).openTextEditor(new OpenFileDescriptor(project,file),true);}finally{OPENING_SOURCE.remove();}}
    static boolean isOpeningDetached(){return OPENING_DETACHED.get();}

    static void detachFromMainWindow(Project project,VirtualFile file,JComponent editorComponent){
        if(project.isDisposed()||!file.isValid())return;
        FileEditorManagerEx manager=FileEditorManagerEx.getInstanceEx(project);if(!(manager instanceof FileEditorManagerImpl implementation))return;EditorsSplitters splitters=manager.getSplittersFor(editorComponent);if(splitters==null||splitters!=implementation.getMainSplitters())return;EditorWindow sourceWindow=splitters.getCurrentWindow();if(sourceWindow==null)return;
        boolean opened=false;OPENING_DETACHED.set(true);
        try{implementation.openFileInNewWindow(file);opened=true;}finally{OPENING_DETACHED.remove();}
        if(opened)manager.closeFile(file,sourceWindow);
    }
}
