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

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.fileEditor.FileEditorStateLevel;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JToolBar;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.TransferHandler;
import javax.swing.event.ChangeEvent;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * IDEA-native visual editor for GUIExport Lua files.
 *
 * <p>No external executable is started. The source remains a normal EmmyLua Document, so PSI, indices, completion,
 * navigation and the BaseClass/Module extension continue to see the same file.</p>
 */
public final class GuiEditorFileEditor extends UserDataHolderBase implements FileEditor {
    private static final String PROP_MODIFIED = "modified";
    private static final int HISTORY_LIMIT = 80;

    private final Project project;
    private final VirtualFile file;
    private final Document sourceDocument;
    private final JPanel component = new JPanel(new BorderLayout());
    private final PropertyChangeSupport propertyChangeSupport = new PropertyChangeSupport(this);
    private final AtomicBoolean disposed = new AtomicBoolean(false);
    private final Timer syncTimer;
    private final Timer externalReloadTimer;
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel fileLabel = new JLabel();
    private final JLabel parseLabel = new JLabel();
    private final JButton saveButton = new JButton("保存");
    private final JButton reloadButton = new JButton("重新解析");
    private final JButton undoButton = new JButton("撤销");
    private final JButton redoButton = new JButton("重做");
    private final JCheckBox gridCheckBox = new JCheckBox("网格");
    private final JCheckBox snapCheckBox = new JCheckBox("吸附");
    private final JSlider zoomSlider = new JSlider(25, 250, 100);
    private final JTree hierarchyTree = new JTree();
    private final JList<String> paletteList = new JList<>(GuiLuaDocument.PALETTE_TYPES.toArray(String[]::new));
    private final PropertyTableModel propertyModel = new PropertyTableModel();
    private final JTable propertyTable = new JTable(propertyModel);
    private final JList<String> diagnosticList = new JList<>();
    private final Map<GuiLuaDocument.Node, DefaultMutableTreeNode> treeNodeByModel = new LinkedHashMap<>();
    private final Deque<String> undoHistory = new ArrayDeque<>();
    private final Deque<String> redoHistory = new ArrayDeque<>();

    private GuiLuaDocument model;
    private GuiResourceResolver resourceResolver;
    private GuiEditorCanvas canvas;
    private boolean internalDocumentWrite;
    private boolean reloading;
    private boolean selectingTree;
    private boolean modelDirty;
    private String lastSyncedText;
    private String pendingHistoryBase;
    private final DocumentListener sourceListener = new DocumentListener() {
        @Override
        public void documentChanged(@NotNull DocumentEvent event) {
            if (internalDocumentWrite || disposed.get()) {
                return;
            }
            externalReloadTimer.restart();
        }
    };

    public GuiEditorFileEditor(@NotNull Project project, @NotNull VirtualFile file) {
        this.project = project;
        this.file = file;
        this.sourceDocument = FileDocumentManager.getInstance().getDocument(file);
        this.lastSyncedText = sourceDocument == null ? "" : sourceDocument.getText();
        this.model = parse(lastSyncedText);
        this.resourceResolver = createResourceResolver();
        this.canvas = createCanvas(model, resourceResolver);

        syncTimer = new Timer(130, event -> syncModelToDocument(false));
        syncTimer.setRepeats(false);
        externalReloadTimer = new Timer(220, event -> reloadFromDocument(false));
        externalReloadTimer.setRepeats(false);

        buildUi();
        installActions();
        rebuildHierarchy();
        refreshDiagnostics();
        applySettingsToCanvas();
        updateModifiedState();

        if (sourceDocument != null) {
            sourceDocument.addDocumentListener(sourceListener);
        } else {
            setStatus("无法取得 Lua Document；可切换到 Text 页检查文件类型。", true);
        }
    }

    private GuiLuaDocument parse(String source) {
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        try {
            return GuiLuaDocument.parse(source, settings.getCanvasWidth(), settings.getCanvasHeight());
        } catch (RuntimeException exception) {
            setStatus("可视化解析失败：" + Objects.toString(exception.getMessage(), exception.getClass().getSimpleName()), true);
            return GuiLuaDocument.parse("", settings.getCanvasWidth(), settings.getCanvasHeight());
        }
    }

    private GuiResourceResolver createResourceResolver() {
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        return new GuiResourceResolver(project.getBasePath(), file.getCanonicalPath(), settings.getResourceRoots());
    }

    private GuiEditorCanvas createCanvas(GuiLuaDocument document, GuiResourceResolver resolver) {
        return new GuiEditorCanvas(document, resolver, new GuiEditorCanvas.Listener() {
            @Override
            public void selectionChanged(@Nullable GuiLuaDocument.Node node) {
                onCanvasSelectionChanged(node);
            }

            @Override
            public void modelChanged() {
                onModelChanged(true);
            }

            @Override
            public void fileDropped(@NotNull File droppedFile) {
                openDroppedFile(droppedFile);
            }

            @Override
            public void status(@NotNull String message) {
                setStatus(message, false);
            }
        }, this);
    }

    private void buildUi() {
        component.setBorder(BorderFactory.createEmptyBorder());
        component.add(buildToolbar(), BorderLayout.NORTH);

        JScrollPane canvasScroll = new JScrollPane(canvas);
        canvasScroll.getViewport().setBackground(canvas.getBackground());
        canvasScroll.setBorder(BorderFactory.createEmptyBorder());

        JTabbedPane leftTabs = new JTabbedPane();
        leftTabs.addTab("层级", new JScrollPane(hierarchyTree));
        leftTabs.addTab("控件", new JScrollPane(paletteList));
        leftTabs.setMinimumSize(new Dimension(170, 200));
        leftTabs.setPreferredSize(new Dimension(230, 600));

        JTabbedPane rightTabs = new JTabbedPane();
        rightTabs.addTab("属性", new JScrollPane(propertyTable));
        rightTabs.addTab("解析信息", new JScrollPane(diagnosticList));
        rightTabs.setMinimumSize(new Dimension(220, 200));
        rightTabs.setPreferredSize(new Dimension(310, 600));

        JSplitPane canvasAndProperties = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvasScroll, rightTabs);
        canvasAndProperties.setResizeWeight(1.0);
        canvasAndProperties.setOneTouchExpandable(true);
        canvasAndProperties.setDividerLocation(0.76);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftTabs, canvasAndProperties);
        mainSplit.setResizeWeight(0.0);
        mainSplit.setOneTouchExpandable(true);
        mainSplit.setDividerLocation(225);
        component.add(mainSplit, BorderLayout.CENTER);

        JPanel status = new JPanel(new BorderLayout(8, 0));
        status.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        status.add(statusLabel, BorderLayout.CENTER);
        parseLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        status.add(parseLabel, BorderLayout.EAST);
        component.add(status, BorderLayout.SOUTH);

        configureHierarchyTree();
        configurePalette();
        configurePropertyTable();
        configureDiagnostics();
    }

    private JComponent buildToolbar() {
        JToolBar toolbar = new JToolBar();
        toolbar.setFloatable(false);
        toolbar.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        toolbar.add(saveButton);
        toolbar.add(reloadButton);
        toolbar.addSeparator();
        toolbar.add(undoButton);
        toolbar.add(redoButton);
        toolbar.addSeparator();
        toolbar.add(gridCheckBox);
        toolbar.add(snapCheckBox);
        toolbar.addSeparator();
        toolbar.add(new JLabel("缩放 "));
        zoomSlider.setPreferredSize(new Dimension(135, 24));
        zoomSlider.setMaximumSize(new Dimension(170, 24));
        zoomSlider.setToolTipText("Ctrl + 鼠标滚轮也可缩放");
        toolbar.add(zoomSlider);
        JButton fitButton = new JButton("适应");
        fitButton.addActionListener(event -> fitCanvas());
        toolbar.add(fitButton);
        toolbar.addSeparator();
        JButton refreshResources = new JButton("刷新资源");
        refreshResources.addActionListener(event -> {
            resourceResolver = createResourceResolver();
            canvas.setDocument(model, resourceResolver);
            canvas.refreshResources();
            setStatus("已刷新图片与资源路径", false);
        });
        toolbar.add(refreshResources);
        JButton settingsButton = new JButton("设置…");
        settingsButton.addActionListener(event ->
                ShowSettingsUtil.getInstance().showSettingsDialog(project, GuiEditorConfigurable.class));
        toolbar.add(settingsButton);
        toolbar.addSeparator();
        fileLabel.setText(file.getPresentableUrl());
        fileLabel.setToolTipText(file.getPresentableUrl());
        fileLabel.setFont(fileLabel.getFont().deriveFont(Font.PLAIN));
        toolbar.add(fileLabel);
        return toolbar;
    }

    private void configureHierarchyTree() {
        hierarchyTree.setRootVisible(false);
        hierarchyTree.setShowsRootHandles(true);
        hierarchyTree.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        hierarchyTree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(javax.swing.JTree tree, Object value, boolean selected,
                                                          boolean expanded, boolean leaf, int row, boolean hasFocus) {
                Component result = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
                if (value instanceof DefaultMutableTreeNode treeNode
                        && treeNode.getUserObject() instanceof GuiLuaDocument.Node node) {
                    setText(node.toString());
                    setToolTipText(node.getVariable() + " · line " + node.getSourceLine());
                }
                return result;
            }
        });
        hierarchyTree.addTreeSelectionListener(this::onTreeSelectionChanged);
    }

    private void configurePalette() {
        paletteList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        paletteList.setDragEnabled(true);
        paletteList.setTransferHandler(new TransferHandler() {
            @Override
            protected java.awt.datatransfer.Transferable createTransferable(JComponent component) {
                String type = paletteList.getSelectedValue();
                return type == null ? null : new StringSelection(GuiEditorCanvas.WIDGET_TRANSFER_PREFIX + type);
            }

            @Override
            public int getSourceActions(JComponent component) {
                return COPY;
            }
        });
        paletteList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setText("  " + value);
                label.setToolTipText("拖到中间画布，或双击添加");
                return label;
            }
        });
        paletteList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && paletteList.getSelectedValue() != null) {
                    canvas.addNodeAtCenter(paletteList.getSelectedValue());
                }
            }
        });
    }

    private void configurePropertyTable() {
        propertyTable.setFillsViewportHeight(true);
        propertyTable.setRowHeight(Math.max(22, propertyTable.getRowHeight()));
        propertyTable.getColumnModel().getColumn(0).setPreferredWidth(115);
        propertyTable.getColumnModel().getColumn(1).setPreferredWidth(180);
        propertyTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
    }

    private void configureDiagnostics() {
        diagnosticList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                label.setText("  " + value);
                return label;
            }
        });
    }

    private void installActions() {
        saveButton.addActionListener(event -> saveNow());
        reloadButton.addActionListener(event -> reloadFromDocument(true));
        undoButton.addActionListener(event -> undo());
        redoButton.addActionListener(event -> redo());
        gridCheckBox.addActionListener(event -> canvas.setShowGrid(gridCheckBox.isSelected()));
        snapCheckBox.addActionListener(event -> canvas.setSnapToGrid(snapCheckBox.isSelected()));
        zoomSlider.addChangeListener(this::onZoomChanged);

        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "designer-save", this::saveNow);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "designer-undo", this::undo);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "designer-redo", this::redo);
        bindKey(KeyStroke.getKeyStroke(KeyEvent.VK_0, InputEvent.CTRL_DOWN_MASK), "designer-fit", this::fitCanvas);
    }

    private void bindKey(KeyStroke keyStroke, String name, Runnable action) {
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(keyStroke, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                action.run();
            }
        });
    }

    private void applySettingsToCanvas() {
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        gridCheckBox.setSelected(settings.isShowGrid());
        snapCheckBox.setSelected(settings.isSnapToGrid());
        canvas.setShowGrid(settings.isShowGrid());
        canvas.setSnapToGrid(settings.isSnapToGrid());
        canvas.setGridSize(settings.getGridSize());
    }

    private void onZoomChanged(ChangeEvent event) {
        canvas.setZoom(zoomSlider.getValue() / 100.0);
    }

    private void fitCanvas() {
        Component parent = canvas.getParent();
        if (parent == null) {
            return;
        }
        double availableWidth = Math.max(100, parent.getWidth() - 80);
        double availableHeight = Math.max(100, parent.getHeight() - 80);
        double fit = Math.min(availableWidth / model.getCanvasWidth(), availableHeight / model.getCanvasHeight());
        int percent = (int) Math.round(Math.max(25, Math.min(250, fit * 100.0)));
        zoomSlider.setValue(percent);
    }

    private void onTreeSelectionChanged(TreeSelectionEvent event) {
        if (selectingTree) {
            return;
        }
        Object selectedPath = hierarchyTree.getLastSelectedPathComponent();
        GuiLuaDocument.Node node = null;
        if (selectedPath instanceof DefaultMutableTreeNode treeNode
                && treeNode.getUserObject() instanceof GuiLuaDocument.Node modelNode) {
            node = modelNode;
        }
        canvas.setSelectedNode(node);
    }

    private void onCanvasSelectionChanged(@Nullable GuiLuaDocument.Node node) {
        propertyModel.setNode(node);
        selectingTree = true;
        try {
            DefaultMutableTreeNode treeNode = node == null ? null : treeNodeByModel.get(node);
            if (treeNode == null) {
                hierarchyTree.clearSelection();
            } else {
                TreePath path = new TreePath(treeNode.getPath());
                hierarchyTree.setSelectionPath(path);
                hierarchyTree.scrollPathToVisible(path);
            }
        } finally {
            selectingTree = false;
        }
    }

    private void onModelChanged(boolean hierarchyMayHaveChanged) {
        if (reloading || disposed.get()) {
            return;
        }
        if (pendingHistoryBase == null) {
            pendingHistoryBase = lastSyncedText;
        }
        boolean oldModified = isModified();
        modelDirty = true;
        if (hierarchyMayHaveChanged) {
            rebuildHierarchyKeepingSelection();
        }
        propertyModel.refresh();
        canvas.repaint();
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        if (settings.isLiveSync()) {
            syncTimer.restart();
        }
        fireModifiedIfChanged(oldModified);
        updateModifiedState();
    }

    private void syncModelToDocument(boolean saveDocument) {
        if (disposed.get() || sourceDocument == null || !modelDirty) {
            if (saveDocument && sourceDocument != null) {
                FileDocumentManager.getInstance().saveDocument(sourceDocument);
            }
            return;
        }
        String rendered = model.serialize();
        if (!Objects.equals(rendered, sourceDocument.getText())) {
            String historyBase = pendingHistoryBase == null ? lastSyncedText : pendingHistoryBase;
            pushUndo(historyBase);
            redoHistory.clear();
            writeDocument(rendered);
        }
        lastSyncedText = rendered;
        pendingHistoryBase = null;
        modelDirty = false;
        if (saveDocument) {
            FileDocumentManager.getInstance().saveDocument(sourceDocument);
        }
        updateModifiedState();
        updateHistoryButtons();
    }

    private void saveNow() {
        syncTimer.stop();
        syncModelToDocument(true);
        setStatus("已保存到 " + file.getName(), false);
    }

    private void writeDocument(String text) {
        if (sourceDocument == null || Objects.equals(sourceDocument.getText(), text)) {
            return;
        }
        internalDocumentWrite = true;
        try {
            WriteCommandAction.runWriteCommandAction(project, () -> sourceDocument.setText(text));
        } finally {
            internalDocumentWrite = false;
        }
    }

    private void reloadFromDocument(boolean userInitiated) {
        if (disposed.get() || sourceDocument == null || internalDocumentWrite) {
            return;
        }
        if (!userInitiated && modelDirty) {
            setStatus("Text 页已有外部修改，但设计器仍有未同步内容；请先保存或手动重新解析。", true);
            return;
        }
        String source = sourceDocument.getText();
        if (!userInitiated && Objects.equals(source, lastSyncedText)) {
            return;
        }
        syncTimer.stop();
        reloading = true;
        try {
            model = parse(source);
            resourceResolver = createResourceResolver();
            canvas.setDocument(model, resourceResolver);
            applySettingsToCanvas();
            lastSyncedText = source;
            pendingHistoryBase = null;
            modelDirty = false;
            rebuildHierarchy();
            propertyModel.setNode(null);
            refreshDiagnostics();
            if (userInitiated) {
                setStatus("已从 Lua Document 重新解析", false);
            }
        } finally {
            reloading = false;
        }
        updateModifiedState();
    }

    private void undo() {
        syncTimer.stop();
        if (modelDirty) {
            syncModelToDocument(false);
        }
        if (undoHistory.isEmpty() || sourceDocument == null) {
            return;
        }
        String current = sourceDocument.getText();
        String previous = undoHistory.removeLast();
        redoHistory.addLast(current);
        trimHistory(redoHistory);
        applyHistoryText(previous, "已撤销可视化修改");
    }

    private void redo() {
        syncTimer.stop();
        if (redoHistory.isEmpty() || sourceDocument == null) {
            return;
        }
        String current = sourceDocument.getText();
        String next = redoHistory.removeLast();
        undoHistory.addLast(current);
        trimHistory(undoHistory);
        applyHistoryText(next, "已重做可视化修改");
    }

    private void applyHistoryText(String text, String status) {
        writeDocument(text);
        lastSyncedText = "";
        pendingHistoryBase = null;
        modelDirty = false;
        reloadFromDocument(false);
        updateHistoryButtons();
        setStatus(status, false);
    }

    private void pushUndo(String text) {
        if (text == null || (!undoHistory.isEmpty() && Objects.equals(undoHistory.peekLast(), text))) {
            return;
        }
        undoHistory.addLast(text);
        trimHistory(undoHistory);
    }

    private static void trimHistory(Deque<String> history) {
        while (history.size() > HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    private void rebuildHierarchyKeepingSelection() {
        GuiLuaDocument.Node selected = canvas.getSelectedNode();
        rebuildHierarchy();
        if (selected != null && !selected.isDeleted()) {
            onCanvasSelectionChanged(selected);
        } else {
            canvas.setSelectedNode(null);
        }
        refreshDiagnostics();
    }

    private void rebuildHierarchy() {
        treeNodeByModel.clear();
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(file.getName());
        for (GuiLuaDocument.Node node : model.getRoots()) {
            root.add(buildTreeNode(node));
        }
        hierarchyTree.setModel(new DefaultTreeModel(root));
        for (int row = 0; row < hierarchyTree.getRowCount(); row++) {
            hierarchyTree.expandRow(row);
        }
        parseLabel.setText(model.getNodes().size() + " 个控件");
    }

    private DefaultMutableTreeNode buildTreeNode(GuiLuaDocument.Node node) {
        DefaultMutableTreeNode result = new DefaultMutableTreeNode(node);
        treeNodeByModel.put(node, result);
        for (GuiLuaDocument.Node child : node.getChildren()) {
            result.add(buildTreeNode(child));
        }
        return result;
    }

    private void refreshDiagnostics() {
        List<String> values = new ArrayList<>();
        if (model.getDiagnostics().isEmpty()) {
            values.add("未发现结构性问题；未识别的 Lua 语句会原样保留。");
        } else {
            for (GuiLuaDocument.Diagnostic diagnostic : model.getDiagnostics()) {
                values.add(diagnostic.toString());
            }
        }
        diagnosticList.setListData(values.toArray(String[]::new));
        parseLabel.setToolTipText(String.join("\n", values));
    }

    private void openDroppedFile(File droppedFile) {
        if (!droppedFile.isFile() || !droppedFile.getName().toLowerCase(Locale.ROOT).endsWith(".lua")) {
            setStatus("只能拖入 .lua 文件", true);
            return;
        }
        VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(droppedFile);
        if (virtualFile == null) {
            setStatus("IDEA 无法定位拖入文件：" + droppedFile, true);
            return;
        }
        FileEditorManager.getInstance(project).openFile(virtualFile, true);
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        boolean nativeRoute = GuiEditorPathMatcher.isManagedLuaFile(project, virtualFile, settings);
        setStatus(nativeRoute ? "已在内嵌 GUI 设计器中打开 " + droppedFile.getName()
                : "该文件不在接管目录中，已使用普通 EmmyLua Text 打开", false);
    }

    private void setStatus(String message, boolean error) {
        statusLabel.setText((error ? "⚠ " : "") + message);
        statusLabel.setToolTipText(message);
    }

    private void updateModifiedState() {
        boolean modified = isModified();
        saveButton.setEnabled(modified);
        updateHistoryButtons();
        parseLabel.setText(model.getNodes().size() + " 个控件" + (modelDirty ? " · 待同步" : ""));
    }

    private void updateHistoryButtons() {
        undoButton.setEnabled(!undoHistory.isEmpty() || modelDirty);
        redoButton.setEnabled(!redoHistory.isEmpty());
    }

    private void fireModifiedIfChanged(boolean oldModified) {
        boolean now = isModified();
        if (oldModified != now) {
            propertyChangeSupport.firePropertyChange(PROP_MODIFIED, oldModified, now);
        }
    }

    @Override
    public @NotNull JComponent getComponent() {
        return component;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return canvas;
    }

    @Override
    public @NotNull String getName() {
        return "GUI 设计器";
    }

    @Override
    public @NotNull FileEditorState getState(@NotNull FileEditorStateLevel level) {
        return FileEditorState.INSTANCE;
    }

    @Override
    public void setState(@NotNull FileEditorState state) {
        // Selection and zoom are deliberately transient; source is the authoritative state.
    }

    @Override
    public boolean isModified() {
        if (modelDirty) {
            return true;
        }
        return sourceDocument != null && FileDocumentManager.getInstance().isDocumentUnsaved(sourceDocument);
    }

    @Override
    public boolean isValid() {
        return !disposed.get() && file.isValid();
    }

    @Override
    public void selectNotify() {
        if (sourceDocument != null && !Objects.equals(sourceDocument.getText(), lastSyncedText) && !modelDirty) {
            externalReloadTimer.restart();
        }
    }

    @Override
    public void deselectNotify() {
        GuiEditorProjectSettings settings = GuiEditorProjectSettings.getInstance(project);
        if (settings.isLiveSync()) {
            syncTimer.stop();
            syncModelToDocument(false);
        }
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
        syncTimer.stop();
        externalReloadTimer.stop();
        if (sourceDocument != null) {
            sourceDocument.removeDocumentListener(sourceListener);
        }
    }

    private final class PropertyTableModel extends AbstractTableModel {
        private final List<PropertyRow> rows = new ArrayList<>();
        private GuiLuaDocument.Node node;

        private void setNode(@Nullable GuiLuaDocument.Node node) {
            this.node = node;
            rebuildRows();
        }

        private void refresh() {
            rebuildRows();
        }

        private void rebuildRows() {
            rows.clear();
            if (node != null && !node.isDeleted()) {
                add("变量", "variable");
                add("类型", "type");
                add("父级", "parent");
                add("名称", "name");
                add("中文说明", "chineseName");
                add("X", "x");
                add("Y", "y");
                add("宽度", "width");
                add("高度", "height");
                add("锚点 X", "anchorX");
                add("锚点 Y", "anchorY");
                add("缩放 X", "scaleX");
                add("缩放 Y", "scaleY");
                add("旋转", "rotation");
                add("透明度", "opacity");
                add("层级", "zOrder");
                add("Tag", "tag");
                add("可见", "visible");
                add("可点击", "touchEnabled");
                add("鼠标事件", "mouseEnabled");
                add("吞噬触摸", "swallowTouches");

                String type = node.getType();
                if (List.of("Image", "Button", "LoadingBar", "ProgressTimer", "CheckBox",
                        "Layout", "ScrollView", "ListView", "PageView", "TableView").contains(type)) {
                    add("普通图片", "image");
                }
                if ("Button".equals(type) || "CheckBox".equals(type)) {
                    add("按下图片", "pressedImage");
                }
                if ("Button".equals(type)) {
                    add("禁用图片", "disabledImage");
                }
                if ("Slider".equals(type)) {
                    add("滑槽图片", "sliderBarImage");
                    add("进度图片", "sliderProgressImage");
                    add("滑块图片", "sliderBallImage");
                }
                if (List.of("Text", "Button", "TextInput", "RichText", "ScrollText", "TextAtlas").contains(type)) {
                    add("文本", "text");
                    add("文本颜色", "color");
                    add("字号", "fontSize");
                    add("字体路径", "fontPath");
                    add("描边颜色", "outlineColor");
                    add("描边宽度", "outlineWidth");
                }
                if (List.of("Layout", "ScrollView", "ListView", "PageView", "TableView").contains(type)) {
                    add("背景颜色", "backgroundColor");
                    add("背景透明度", "backgroundOpacity");
                    add("裁剪子节点", "clipping");
                }
                if (List.of("LoadingBar", "ProgressTimer", "Slider").contains(type)) {
                    add("百分比", "percent");
                }
                if ("CheckBox".equals(type)) {
                    add("选中", "selected");
                    add("分组", "group");
                }
                add("忽略内容尺寸", "ignoreContent");
                add("级联透明度", "cascadeOpacity");
            }
            fireTableDataChanged();
        }

        private void add(String label, String key) {
            rows.add(new PropertyRow(label, key));
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "属性" : "值";
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            PropertyRow row = rows.get(rowIndex);
            return columnIndex == 0 ? row.label : node.getProperty(row.key);
        }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 0 ? String.class : Object.class;
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 1 && node != null && node.isPropertyEditable(rows.get(rowIndex).key);
        }

        @Override
        public void setValueAt(Object value, int rowIndex, int columnIndex) {
            if (node == null || columnIndex != 1) {
                return;
            }
            PropertyRow row = rows.get(rowIndex);
            Object before = node.getProperty(row.key);
            node.setProperty(row.key, value);
            Object after = node.getProperty(row.key);
            if (!Objects.equals(before, after)) {
                onModelChanged("name".equals(row.key) || "chineseName".equals(row.key)
                        || "zOrder".equals(row.key));
            }
            fireTableRowsUpdated(rowIndex, rowIndex);
        }
    }

    private record PropertyRow(String label, String key) {
    }
}
