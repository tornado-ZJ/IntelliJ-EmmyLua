package com.tang.intellij.lua.guieditor;

import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class MarkGuiExportRootAction extends DumbAwareAction {
    public MarkGuiExportRootAction() { super("设为 GUIExport 路径", "让此目录下的 Lua 文件使用 GUI 设计器", null); }

    @Override public void update(@NotNull AnActionEvent e) {
        VirtualFile file = selectedRoot(e);
        e.getPresentation().setVisible(e.getProject() != null);
        e.getPresentation().setEnabled(file != null && file.isDirectory());
    }

    @Override public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject(); VirtualFile root = selectedRoot(e);
        if (project == null || root == null) return;
        GuiEditorSettings.State state = GuiEditorSettings.getInstance().getState();
        if (!state.guiExportRoots.contains(root.getPath())) state.guiExportRoots.add(root.getPath());
        ProjectView.getInstance(project).refresh();
        for (VirtualFile open : FileEditorManager.getInstance(project).getOpenFiles()) {
            if (open.getPath().startsWith(root.getPath()) && open.getName().toLowerCase().endsWith(".lua")) {
                FileEditorManager.getInstance(project).closeFile(open);
                FileEditorManager.getInstance(project).openFile(open, true);
            }
        }
        Messages.showInfoMessage(project, "已启用：" + root.getPath() + "\n此目录中的 Lua 文件现在可使用 GUI 设计器。", "GUIExport 路径");
    }

    private static VirtualFile selectedRoot(AnActionEvent e) {
        VirtualFile file = e.getData(CommonDataKeys.VIRTUAL_FILE);
        if (file == null) {
            VirtualFile[] files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
            if (files != null && files.length == 1) file = files[0];
        }
        if (file != null && !file.isDirectory()) file = file.getParent();
        return file;
    }
}
