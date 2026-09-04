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

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A process-backed IDEA editor tab for the uploaded WPF GUI editor.
 *
 * <p>The WPF application remains an external Windows process. This avoids unsafe HWND parenting while still making
 * directory routing, launch, lifecycle feedback and VFS refresh part of the EmmyLua editor experience.</p>
 */
public final class GuiEditorFileEditor extends UserDataHolderBase implements FileEditor {
    private final Project project;
    private final VirtualFile file;
    private final JPanel component;
    private final JLabel statusLabel;
    private final JLabel detailLabel;
    private final JButton launchButton;
    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final AtomicBoolean launchScheduled = new AtomicBoolean(false);
    private final Object processLock = new Object();

    private volatile Process process;
    private volatile String lastOutput = "";
    private Timer refreshTimer;

    public GuiEditorFileEditor(@NotNull Project project, @NotNull VirtualFile file) {
        this.project = project;
        this.file = file;

        component = new JPanel(new BorderLayout());
        component.setBorder(BorderFactory.createEmptyBorder(28, 36, 28, 36));

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("GUI 可视化编辑器", SwingConstants.CENTER);
        title.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 5.0f));

        JLabel fileLabel = new JLabel(file.getPresentableUrl(), SwingConstants.CENTER);
        fileLabel.setAlignmentX(JComponent.CENTER_ALIGNMENT);

        statusLabel = new JLabel("准备打开", SwingConstants.CENTER);
        statusLabel.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD));

        detailLabel = new JLabel(" ", SwingConstants.CENTER);
        detailLabel.setAlignmentX(JComponent.CENTER_ALIGNMENT);

        JPanel actions = new JPanel();
        launchButton = new JButton("启动 GUI 编辑器");
        JButton refreshButton = new JButton("刷新文件");
        JButton settingsButton = new JButton("配置目录与工具");
        actions.add(launchButton);
        actions.add(refreshButton);
        actions.add(settingsButton);
        actions.setAlignmentX(JComponent.CENTER_ALIGNMENT);

        launchButton.addActionListener(event -> launchEditor(false));
        refreshButton.addActionListener(event -> refreshVirtualFile());
        settingsButton.addActionListener(event ->
                ShowSettingsUtil.getInstance().showSettingsDialog(project, GuiEditorConfigurable.class));

        JLabel note = new JLabel(
                "<html><div style='text-align:center'>此页负责启动目录专用 GUI 编辑器；右侧的 Text 页仍由 EmmyLua 正常解析、补全和索引。<br>"
                        + "当前工具要求目标文件路径中包含 <b>GUIExport</b> 目录。</div></html>",
                SwingConstants.CENTER);
        note.setAlignmentX(JComponent.CENTER_ALIGNMENT);

        content.add(Box.createVerticalGlue());
        content.add(title);
        content.add(Box.createRigidArea(new Dimension(0, 16)));
        content.add(fileLabel);
        content.add(Box.createRigidArea(new Dimension(0, 22)));
        content.add(statusLabel);
        content.add(Box.createRigidArea(new Dimension(0, 8)));
        content.add(detailLabel);
        content.add(Box.createRigidArea(new Dimension(0, 18)));
        content.add(actions);
        content.add(Box.createRigidArea(new Dimension(0, 24)));
        content.add(note);
        content.add(Box.createVerticalGlue());

        component.add(content, BorderLayout.CENTER);
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return launchButton;
    }

    @Override
    public @NotNull String getName() {
        return "GUI 编辑器";
    }

    @Override
    public @NotNull FileEditorState getState(@NotNull FileEditorStateLevel level) {
        return FileEditorState.INSTANCE;
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
        // No editor-local state. The external tool owns its visual layout state.
    }

    @Override
    public boolean isModified() {
        return false;
    }

    @Override
    public boolean isValid() {
        return !disposed.get() && file.isValid();
    }

    @Override
    public void selectNotify() {
        refreshVirtualFile();
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        if (settings.isAutoLaunch() && launchScheduled.compareAndSet(false, true)) {
            ApplicationManager.getApplication().invokeLater(() -> launchEditor(true));
        }
    }

    @Override
    public void deselectNotify() {
        refreshVirtualFile();
    }

    @Override
    public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChangeSupport.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
        propertyChangeSupport.removePropertyChangeListener(listener);
    }

    @Override
    public @NotNull VirtualFile getFile() {
        return file;
    }

    @Override
    public void dispose() {
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        stopRefreshTimer();
        // Do not kill the external editor when the IDEA tab is closed. The user may still be saving work there.
    }

    private void launchEditor(boolean automatic) {
        if (disposed.get()) {
            return;
        }
        synchronized (processLock) {
            if (process != null && process.isAlive()) {
                setStatus("GUI 编辑器正在运行", "进程 PID：" + process.pid(), false);
                return;
            }
        }

        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        if (!settings.isEnabled()) {
            setStatus("GUI 编辑器路由已关闭", "请在设置中启用。", true);
            return;
        }
        if (!SystemInfo.isWindows) {
            setStatus("当前系统不支持这个工具", "上传的编辑器是 Windows .NET Framework WPF 程序。", true);
            return;
        }

        String executableValue = settings.getExecutablePath();
        if (executableValue.isBlank()) {
            setStatus("尚未配置 GUI 编辑器 EXE", "打开“配置目录与工具”并选择可执行文件。", true);
            return;
        }

        Path executable;
        try {
            executable = Path.of(GuiEditorPathMatcher.expandProjectMacro(executableValue, project.getBasePath()))
                    .toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            setStatus("GUI 编辑器路径无效", exception.getMessage(), true);
            return;
        }
        if (!Files.isRegularFile(executable)) {
            setStatus("找不到 GUI 编辑器", executable.toString(), true);
            return;
        }

        String localPath = file.getCanonicalPath();
        if (localPath == null || localPath.isBlank()) {
            setStatus("文件不是本地文件", file.getPresentableUrl(), true);
            return;
        }
        if (!GuiEditorPathMatcher.containsGuiExportSegment(localPath)) {
            setStatus("目标路径缺少 GUIExport 目录",
                    "当前 EXE 会拒绝此文件；请把路由目录设置在 GUIExport 内。", true);
            return;
        }

        String matchedRoot = GuiEditorPathMatcher.findMatchingRoot(project, file, settings);
        if (matchedRoot == null) {
            setStatus("文件已不在配置目录中", "保存设置后重新打开此文件。", true);
            return;
        }

        FileDocumentManager.getInstance().saveAllDocuments();
        setStatus(automatic ? "正在自动启动 GUI 编辑器…" : "正在启动 GUI 编辑器…", " ", false);
        launchButton.setEnabled(false);

        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            Process started = null;
            try {
                ProcessBuilder builder = new ProcessBuilder(executable.toString(), localPath);
                Path parent = executable.getParent();
                if (parent != null && Files.isDirectory(parent)) {
                    builder.directory(parent.toFile());
                }
                builder.redirectErrorStream(true);
                builder.environment().put("EMMYLUA_PROJECT_DIR", project.getBasePath() == null ? "" : project.getBasePath());
                builder.environment().put("EMMYLUA_GUI_EDITOR_ROOT", matchedRoot);

                started = builder.start();
                synchronized (processLock) {
                    process = started;
                }
                Process running = started;
                updateUi(() -> {
                    setStatus("GUI 编辑器已启动", "进程 PID：" + running.pid(), false);
                    launchButton.setText("GUI 编辑器运行中");
                    launchButton.setEnabled(true);
                    startRefreshTimer();
                });

                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        running.getInputStream(), Charset.defaultCharset()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.isBlank()) {
                            if (output.length() > 4096) {
                                output.delete(0, output.length() - 2048);
                            }
                            output.append(line).append('\n');
                        }
                    }
                }
                int exitCode = running.waitFor();
                lastOutput = output.toString().trim();
                synchronized (processLock) {
                    if (process == running) {
                        process = null;
                    }
                }
                refreshVirtualFile();
                updateUi(() -> {
                    stopRefreshTimer();
                    launchButton.setText("重新启动 GUI 编辑器");
                    launchButton.setEnabled(true);
                    String detail = lastOutput.isBlank()
                            ? "进程退出码：" + exitCode
                            : compactMessage(lastOutput) + "（退出码 " + exitCode + "）";
                    setStatus(exitCode == 0 ? "GUI 编辑器已关闭" : "GUI 编辑器异常退出", detail, exitCode != 0);
                });
            } catch (IOException exception) {
                synchronized (processLock) {
                    if (process == started) {
                        process = null;
                    }
                }
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                updateUi(() -> {
                    stopRefreshTimer();
                    launchButton.setText("重新启动 GUI 编辑器");
                    launchButton.setEnabled(true);
                    setStatus("启动失败", message, true);
                });
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                updateUi(() -> {
                    stopRefreshTimer();
                    launchButton.setEnabled(true);
                    setStatus("等待 GUI 编辑器时被中断", "可手动重新启动。", true);
                });
            }
        });
    }

    private void setStatus(@NotNull String status, @Nullable String detail, boolean error) {
        statusLabel.setText(error ? "⚠ " + status : status);
        detailLabel.setText(toHtml(detail == null || detail.isBlank() ? " " : detail));
        detailLabel.setToolTipText(detail);
    }

    private static @NotNull String toHtml(@NotNull String text) {
        return "<html><div style='text-align:center'>" + escapeHtml(text) + "</div></html>";
    }

    private static @NotNull String escapeHtml(@NotNull String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>");
    }

    private static @NotNull String compactMessage(@NotNull String output) {
        String compact = output.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 220 ? compact : compact.substring(0, 217) + "…";
    }

    private void startRefreshTimer() {
        stopRefreshTimer();
        refreshTimer = new Timer(1500, event -> refreshVirtualFile());
        refreshTimer.setRepeats(true);
        refreshTimer.start();
    }

    private void stopRefreshTimer() {
        if (refreshTimer != null) {
            refreshTimer.stop();
            refreshTimer = null;
        }
    }

    private void refreshVirtualFile() {
        if (!disposed.get() && file.isValid()) {
            file.refresh(true, false);
        }
    }

    private void updateUi(@NotNull Runnable runnable) {
        if (!disposed.get()) {
            ApplicationManager.getApplication().invokeLater(() -> {
                if (!disposed.get()) {
                    runnable.run();
                }
            });
        }
    }
}
