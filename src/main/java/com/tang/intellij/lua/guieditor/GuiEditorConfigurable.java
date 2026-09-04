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
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Project Settings | Languages & Frameworks | EmmyLua Native GUI Editor. */
public final class GuiEditorConfigurable implements SearchableConfigurable {
    public static final String ID = "preferences.EmmyLua.GuiEditor";

    private final Project project;
    private JPanel panel;
    private JCheckBox enabledCheckBox;
    private JCheckBox showGridCheckBox;
    private JCheckBox snapGridCheckBox;
    private JCheckBox liveSyncCheckBox;
    private JSpinner canvasWidthSpinner;
    private JSpinner canvasHeightSpinner;
    private JSpinner gridSizeSpinner;
    private DefaultListModel<String> rootsModel;
    private DefaultListModel<String> resourceRootsModel;
    private JList<String> rootsList;
    private JList<String> resourceRootsList;

    public GuiEditorConfigurable(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String getId() {
        return ID;
    }

    @Override
    public @Nls(capitalization = Nls.Capitalization.Title) String getDisplayName() {
        return "EmmyLua 原生 GUI 编辑器";
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
                || showGridCheckBox.isSelected() != settings.isShowGrid()
                || snapGridCheckBox.isSelected() != settings.isSnapToGrid()
                || liveSyncCheckBox.isSelected() != settings.isLiveSync()
                || intValue(canvasWidthSpinner) != settings.getCanvasWidth()
                || intValue(canvasHeightSpinner) != settings.getCanvasHeight()
                || intValue(gridSizeSpinner) != settings.getGridSize()
                || !rootsFromModel(rootsModel).equals(settings.getSourceRoots())
                || !rootsFromModel(resourceRootsModel).equals(settings.getResourceRoots());
    }

    @Override
    public void apply() throws ConfigurationException {
        if (panel == null) {
            return;
        }
        if (enabledCheckBox.isSelected() && rootsModel.isEmpty()) {
            throw new ConfigurationException("请至少添加一个由原生 GUI 编辑器接管的 Lua 目录。");
        }
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        settings.setEnabled(enabledCheckBox.isSelected());
        settings.setShowGrid(showGridCheckBox.isSelected());
        settings.setSnapToGrid(snapGridCheckBox.isSelected());
        settings.setLiveSync(liveSyncCheckBox.isSelected());
        settings.setCanvasWidth(intValue(canvasWidthSpinner));
        settings.setCanvasHeight(intValue(canvasHeightSpinner));
        settings.setGridSize(intValue(gridSizeSpinner));
        settings.setSourceRoots(rootsFromModel(rootsModel));
        settings.setResourceRoots(rootsFromModel(resourceRootsModel));
    }

    @Override
    public void reset() {
        if (panel == null) {
            return;
        }
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        enabledCheckBox.setSelected(settings.isEnabled());
        showGridCheckBox.setSelected(settings.isShowGrid());
        snapGridCheckBox.setSelected(settings.isSnapToGrid());
        liveSyncCheckBox.setSelected(settings.isLiveSync());
        canvasWidthSpinner.setValue(settings.getCanvasWidth());
        canvasHeightSpinner.setValue(settings.getCanvasHeight());
        gridSizeSpinner.setValue(settings.getGridSize());
        setModel(rootsModel, settings.getSourceRoots());
        setModel(resourceRootsModel, settings.getResourceRoots());
        updateEnabledState();
    }

    @Override
    public void disposeUIResources() {
        panel = null;
        enabledCheckBox = null;
        showGridCheckBox = null;
        snapGridCheckBox = null;
        liveSyncCheckBox = null;
        canvasWidthSpinner = null;
        canvasHeightSpinner = null;
        gridSizeSpinner = null;
        rootsModel = null;
        resourceRootsModel = null;
        rootsList = null;
        resourceRootsList = null;
    }

    private void createUi() {
        panel = new JPanel(new BorderLayout(0, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel options = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 6;
        c.weightx = 1.0;

        enabledCheckBox = new JCheckBox("为指定目录启用 IDEA 内嵌可视化编辑器");
        enabledCheckBox.addActionListener(event -> updateEnabledState());
        options.add(enabledCheckBox, c);

        c.gridy++;
        c.gridwidth = 1;
        c.weightx = 0.0;
        options.add(new JLabel("画布宽度："), c);
        canvasWidthSpinner = new JSpinner(new SpinnerNumberModel(1136, 160, 8192, 1));
        c.gridx = 1;
        options.add(canvasWidthSpinner, c);
        c.gridx = 2;
        options.add(new JLabel("画布高度："), c);
        canvasHeightSpinner = new JSpinner(new SpinnerNumberModel(640, 120, 8192, 1));
        c.gridx = 3;
        options.add(canvasHeightSpinner, c);
        c.gridx = 4;
        options.add(new JLabel("网格："), c);
        gridSizeSpinner = new JSpinner(new SpinnerNumberModel(10, 1, 256, 1));
        c.gridx = 5;
        c.weightx = 1.0;
        options.add(gridSizeSpinner, c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        showGridCheckBox = new JCheckBox("显示网格");
        options.add(showGridCheckBox, c);
        c.gridx = 2;
        snapGridCheckBox = new JCheckBox("拖动时吸附网格");
        options.add(snapGridCheckBox, c);
        c.gridx = 4;
        liveSyncCheckBox = new JCheckBox("修改后实时同步到 Lua Document");
        options.add(liveSyncCheckBox, c);
        panel.add(options, BorderLayout.NORTH);

        JPanel lists = new JPanel(new GridBagLayout());
        GridBagConstraints listConstraints = new GridBagConstraints();
        listConstraints.insets = new Insets(4, 4, 4, 4);
        listConstraints.fill = GridBagConstraints.BOTH;
        listConstraints.weightx = 1.0;
        listConstraints.weighty = 1.0;
        listConstraints.gridx = 0;
        listConstraints.gridy = 0;

        rootsModel = new DefaultListModel<>();
        rootsList = new JList<>(rootsModel);
        rootsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        lists.add(createDirectoryPanel("由原生编辑器接管的 Lua 目录（递归）", rootsList, rootsModel, true), listConstraints);

        listConstraints.gridx = 1;
        resourceRootsModel = new DefaultListModel<>();
        resourceRootsList = new JList<>(resourceRootsModel);
        resourceRootsList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        lists.add(createDirectoryPanel("资源搜索目录（图片、字体等）", resourceRootsList, resourceRootsModel, false), listConstraints);
        panel.add(lists, BorderLayout.CENTER);

        JTextArea explanation = new JTextArea(
                "匹配目录内的 .lua 文件会出现“GUI 设计器”内嵌页签；目录外文件完全保持 EmmyLua 原来的 Text 编辑方式。\n"
                        + "设计器不会启动任何 EXE。它直接解析 GUI:*_Create 和常见 GUI:set* 语句，未识别的 Lua 代码原样保留。\n"
                        + "可从 IDEA Project 面板或系统文件管理器把 .lua 文件拖到画布；也可把左侧控件拖到画布。\n"
                        + "保存目录设置后，已打开文件需要关闭再打开，FileEditor 路由才会重新计算。"
        );
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setBorder(BorderFactory.createEmptyBorder(4, 4, 0, 4));
        panel.add(explanation, BorderLayout.SOUTH);
    }

    private JPanel createDirectoryPanel(String title,
                                        JList<String> list,
                                        DefaultListModel<String> model,
                                        boolean offerProjectRelative) {
        JPanel result = new JPanel(new BorderLayout(0, 6));
        result.setBorder(BorderFactory.createTitledBorder(title));
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(420, 220));
        result.add(scroll, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JButton add = new JButton("添加目录…");
        JButton remove = new JButton("删除选中");
        add.addActionListener(event -> chooseDirectories(model, false));
        remove.addActionListener(event -> removeSelected(list, model));
        actions.add(add);
        if (offerProjectRelative) {
            JButton relative = new JButton("添加项目相对目录…");
            relative.addActionListener(event -> chooseDirectories(model, true));
            actions.add(relative);
        }
        actions.add(remove);
        result.add(actions, BorderLayout.SOUTH);
        return result;
    }

    private void chooseDirectories(DefaultListModel<String> model, boolean projectRelative) {
        JFileChooser chooser = new JFileChooser(initialDirectory());
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
            if (!contains(model, value)) {
                model.addElement(value);
            }
        }
    }

    private File initialDirectory() {
        String base = project.getBasePath();
        return base == null ? new File(System.getProperty("user.home", ".")) : new File(base);
    }

    private String toProjectRelative(String raw) {
        String base = project.getBasePath();
        if (base == null || base.isBlank()) {
            return raw;
        }
        try {
            Path basePath = Path.of(base).toAbsolutePath().normalize();
            Path selected = Path.of(raw).toAbsolutePath().normalize();
            if (selected.startsWith(basePath)) {
                String relative = basePath.relativize(selected).toString().replace(File.separatorChar, '/');
                return relative.isBlank() ? GuiEditorPathMatcher.PROJECT_DIR_MACRO
                        : GuiEditorPathMatcher.PROJECT_DIR_MACRO + "/" + relative;
            }
        } catch (RuntimeException ignored) {
        }
        return raw;
    }

    private static void removeSelected(JList<String> list, DefaultListModel<String> model) {
        int[] indices = list.getSelectedIndices();
        for (int i = indices.length - 1; i >= 0; i--) {
            model.remove(indices[i]);
        }
    }

    private static boolean contains(DefaultListModel<String> model, String value) {
        for (int i = 0; i < model.getSize(); i++) {
            if (Objects.equals(model.get(i), value)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> rootsFromModel(DefaultListModel<String> model) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < model.getSize(); i++) {
            String value = model.get(i).trim();
            if (!value.isEmpty() && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private static void setModel(DefaultListModel<String> model, List<String> values) {
        model.clear();
        for (String value : values) {
            model.addElement(value);
        }
    }

    private static int intValue(JSpinner spinner) {
        Object value = spinner.getValue();
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private void updateEnabledState() {
        boolean enabled = enabledCheckBox != null && enabledCheckBox.isSelected();
        for (JComponent component : List.of(showGridCheckBox, snapGridCheckBox, liveSyncCheckBox,
                canvasWidthSpinner, canvasHeightSpinner, gridSizeSpinner,
                rootsList, resourceRootsList)) {
            if (component != null) {
                component.setEnabled(enabled);
            }
        }
    }
}
