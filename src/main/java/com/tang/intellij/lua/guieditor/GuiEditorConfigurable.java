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

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Project Settings | Languages & Frameworks | EmmyLua GUI Editor.
 */
public final class GuiEditorConfigurable implements SearchableConfigurable {
    public static final String ID = "preferences.EmmyLua.GuiEditor";

    private final Project project;
    private JPanel panel;
    private JCheckBox enabledCheckBox;
    private JCheckBox autoLaunchCheckBox;
    private JTextField executableField;
    private DefaultListModel<String> rootsModel;
    private JList<String> rootsList;

    public GuiEditorConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "EmmyLua GUI 编辑器";
    }

    @Override
    public @Nullable JComponent createComponent() {
        if (panel == null) {
            createUi();
            reset();
        }
        return panel;
    }

    @Override
    public boolean isModified() {
        if (panel == null) {
            return false;
        }
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        return enabledCheckBox.isSelected() != settings.isEnabled()
                || autoLaunchCheckBox.isSelected() != settings.isAutoLaunch()
                || !Objects.equals(executableField.getText().trim(), settings.getExecutablePath())
                || !rootsFromUi().equals(settings.getSourceRoots());
    }

    @Override
    public void apply() throws ConfigurationException {
        if (panel == null) {
            return;
        }
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        settings.setEnabled(enabledCheckBox.isSelected());
        settings.setAutoLaunch(autoLaunchCheckBox.isSelected());
        settings.setExecutablePath(executableField.getText());
        settings.setSourceRoots(rootsFromUi());
    }

    @Override
    public void reset() {
        if (panel == null) {
            return;
        }
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        enabledCheckBox.setSelected(settings.isEnabled());
        autoLaunchCheckBox.setSelected(settings.isAutoLaunch());
        executableField.setText(settings.getExecutablePath());
        rootsModel.clear();
        for (String root : settings.getSourceRoots()) {
            rootsModel.addElement(root);
        }
        updateEnabledState();
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        enabledCheckBox = null;
        autoLaunchCheckBox = null;
        executableField = null;
        rootsModel = null;
        rootsList = null;
    }

    private void createUi() {
        panel = new JPanel(new BorderLayout(0, 12));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(4, 4, 4, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        enabledCheckBox = new JCheckBox("为指定目录启用 GUI 可视化编辑器");
        enabledCheckBox.addActionListener(event -> updateEnabledState());
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.gridwidth = 3;
        constraints.weightx = 1.0;
        form.add(enabledCheckBox, constraints);

        constraints.gridy++;
        autoLaunchCheckBox = new JCheckBox("打开匹配文件时自动启动外部编辑器");
        form.add(autoLaunchCheckBox, constraints);

        constraints.gridy++;
        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        form.add(new JLabel("编辑器 EXE："), constraints);

        executableField = new JTextField();
        constraints.gridx = 1;
        constraints.weightx = 1.0;
        form.add(executableField, constraints);

        JButton browseExecutable = new JButton("浏览…");
        browseExecutable.addActionListener(event -> chooseExecutable());
        constraints.gridx = 2;
        constraints.weightx = 0.0;
        form.add(browseExecutable, constraints);

        panel.add(form, BorderLayout.NORTH);

        JPanel rootsPanel = new JPanel(new BorderLayout(0, 6));
        rootsPanel.setBorder(BorderFactory.createTitledBorder("由 GUI 编辑器接管的目录（递归）"));

        rootsModel = new DefaultListModel<>();
        rootsList = new JList<>(rootsModel);
        rootsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane scrollPane = new JScrollPane(rootsList);
        scrollPane.setPreferredSize(new Dimension(640, 220));
        rootsPanel.add(scrollPane, BorderLayout.CENTER);

        JPanel rootActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton addRoot = new JButton("添加目录…");
        JButton removeRoot = new JButton("删除选中");
        JButton addProjectRelative = new JButton("添加项目相对目录…");
        addRoot.addActionListener(event -> chooseRoots(false));
        addProjectRelative.addActionListener(event -> chooseRoots(true));
        removeRoot.addActionListener(event -> removeSelectedRoots());
        rootActions.add(addRoot);
        rootActions.add(addProjectRelative);
        rootActions.add(removeRoot);
        rootsPanel.add(rootActions, BorderLayout.SOUTH);

        panel.add(rootsPanel, BorderLayout.CENTER);

        JTextArea explanation = new JTextArea(
                "匹配目录内的 .lua 文件会多出一个“GUI 编辑器”页，并排在普通 Text 页之前；目录外文件完全保持 EmmyLua 原行为。\n"
                        + "当前上传工具会从文件路径中的 GUIExport 段推导资源根目录，因此路由目录应位于 GUIExport 内。\n"
                        + "EXE 仅支持 Windows。建议把工具放在非系统盘，并保持工具自身可正常独立启动。\n"
                        + "保存设置后，已经打开的文件需要关闭并重新打开，编辑器页签路由才会重新计算。"
        );
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        panel.add(explanation, BorderLayout.SOUTH);
    }

    private void updateEnabledState() {
        boolean enabled = enabledCheckBox != null && enabledCheckBox.isSelected();
        if (autoLaunchCheckBox != null) {
            autoLaunchCheckBox.setEnabled(enabled);
        }
        if (executableField != null) {
            executableField.setEnabled(enabled);
        }
        if (rootsList != null) {
            rootsList.setEnabled(enabled);
        }
    }

    private void chooseExecutable() {
        JFileChooser chooser = new JFileChooser(initialDirectory(executableField.getText()));
        chooser.setDialogTitle("选择 GUI 编辑器 EXE");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setFileFilter(new FileNameExtensionFilter("Windows 可执行文件 (*.exe)", "exe"));
        if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
            executableField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseRoots(boolean projectRelative) {
        JFileChooser chooser = new JFileChooser(initialDirectory(project.getBasePath()));
        chooser.setDialogTitle("选择 GUI 编辑器接管目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(panel) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File[] selected = chooser.getSelectedFiles();
        if (selected.length == 0 && chooser.getSelectedFile() != null) {
            selected = new File[]{chooser.getSelectedFile()};
        }
        for (File directory : selected) {
            String value = directory.getAbsolutePath();
            if (projectRelative) {
                value = toProjectRelative(value);
            }
            addRootIfMissing(value);
        }
    }

    private @NotNull String toProjectRelative(@NotNull String absolutePath) {
        String base = project.getBasePath();
        if (base == null || base.isBlank()) {
            return absolutePath;
        }
        String normalizedBase = base.replace('\\', '/');
        String normalizedPath = absolutePath.replace('\\', '/');
        boolean caseInsensitive = normalizedPath.regionMatches(true, 0, normalizedBase, 0, normalizedBase.length());
        if (caseInsensitive && normalizedPath.length() >= normalizedBase.length()
                && (normalizedPath.length() == normalizedBase.length()
                || normalizedPath.charAt(normalizedBase.length()) == '/')) {
            String suffix = normalizedPath.substring(normalizedBase.length());
            return GuiEditorPathMatcher.PROJECT_DIR_MACRO + suffix;
        }
        return absolutePath;
    }

    private void addRootIfMissing(@NotNull String root) {
        String value = root.trim();
        if (value.isEmpty()) {
            return;
        }
        for (int i = 0; i < rootsModel.size(); i++) {
            if (value.equals(rootsModel.get(i))) {
                return;
            }
        }
        rootsModel.addElement(value);
    }

    private void removeSelectedRoots() {
        int[] indices = rootsList.getSelectedIndices();
        for (int i = indices.length - 1; i >= 0; i--) {
            rootsModel.remove(indices[i]);
        }
    }

    private @NotNull List<String> rootsFromUi() {
        List<String> roots = new ArrayList<>();
        for (int i = 0; i < rootsModel.size(); i++) {
            String value = rootsModel.get(i);
            if (value != null && !value.isBlank() && !roots.contains(value.trim())) {
                roots.add(value.trim());
            }
        }
        return roots;
    }

    private @NotNull File initialDirectory(@Nullable String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            File file = new File(candidate.trim());
            if (file.isDirectory()) {
                return file;
            }
            File parent = file.getParentFile();
            if (parent != null && parent.isDirectory()) {
                return parent;
            }
        }
        String basePath = project.getBasePath();
        return basePath == null ? new File(System.getProperty("user.home")) : new File(basePath);
    }
}
