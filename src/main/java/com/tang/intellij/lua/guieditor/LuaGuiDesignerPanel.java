package com.tang.intellij.lua.guieditor;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.LanguageTextField;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.*;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import com.tang.intellij.lua.lang.LuaLanguage;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

final class LuaGuiDesignerPanel extends JBPanel<LuaGuiDesignerPanel> implements Disposable {
    private static final Color ACCENT = new JBColor(new Color(0x3574F0), new Color(0x4C8DFF));
    private static final Pattern CALL_LINE = Pattern.compile("^(\\s*)GUI:([A-Za-z_][\\w]*)\\s*\\(\\s*([A-Za-z_][\\w]*)\\s*(?:,\\s*(.*))?\\)\\s*$");
    private final Project project;
    private final VirtualFile file;
    private final Document document;
    private final Consumer<Boolean> modifiedCallback;
    private final Runnable sourceCallback;
    private final DesignerCanvas canvas = new DesignerCanvas();
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("Scene");
    private final JTree hierarchy = new JTree(new DefaultTreeModel(treeRoot));
    private final PropertyModel propertyModel = new PropertyModel();
    private final JBTable properties = new JBTable(propertyModel){
        @Override public TableCellEditor getCellEditor(int row,int column){return column==1&&propertyModel.isPath(row)?new PathPropertyEditor(row):super.getCellEditor(row,column);}
    };
    private final JBLabel breadcrumb = new JBLabel("未选择控件");
    private final JBLabel status = new JBLabel("就绪");
    private final JBLabel layerCount = new JBLabel("0");
    private final JBTextField search = new JBTextField();
    private final JBCheckBox gridToggle = new JBCheckBox("网格", GuiEditorSettings.getInstance().getState().showGrid);
    private final JSlider zoom = new JSlider(25, 200, 100);
    private LuaGuiDocument model = new LuaGuiDocument();
    private LuaGuiDocument.Widget selected;
    private String copiedWidgetId;
    private boolean internalChange;
    private String workingText;
    private String savedText;
    private boolean designerDirty;
    private boolean externalConflict;
    private final Deque<String> undoStack = new ArrayDeque<>();
    private final Deque<String> redoStack = new ArrayDeque<>();
    private boolean groupingEdit;
    private String groupStartText;
    private final Map<String, Optional<BufferedImage>> imageCache = new HashMap<>();
    private final Map<String, Optional<Path>> resourcePathCache = new HashMap<>();
    private final Map<String, List<GuiEffectAtlas.Frame>> effectAtlasCache = new ConcurrentHashMap<>();
    private final Set<String> pendingEffectDownloads = ConcurrentHashMap.newKeySet();
    private final Map<String,Long> effectDownloadRetryAt = new ConcurrentHashMap<>();
    private final javax.swing.Timer animationTimer;

    LuaGuiDesignerPanel(Project project, VirtualFile file, Consumer<Boolean> modifiedCallback, Runnable sourceCallback) {
        super(new BorderLayout()); this.project = project; this.file = file; this.modifiedCallback = modifiedCallback; this.sourceCallback = sourceCallback;
        Document found = FileDocumentManager.getInstance().getDocument(file);
        if (found == null) throw new IllegalArgumentException("无法读取 " + file.getPath());
        document = found;
        workingText = document.getText();
        savedText = workingText;
        setBorder(JBUI.Borders.empty());
        add(createHeader(), BorderLayout.NORTH);
        add(createWorkspace(), BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);
        document.addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(@NotNull DocumentEvent event) {
                if (internalChange) return;
                if (designerDirty) {
                    externalConflict = true;
                    status.setText("源码已在其他编辑器中修改；保存设计前会要求确认");
                } else {
                    workingText = document.getText();
                    savedText = workingText;
                    undoStack.clear();
                    redoStack.clear();
                    reload();
                }
            }
        }, this);
        configureInteractions(); reload();
        animationTimer=new javax.swing.Timer(100,e->{if(model.widgets.stream().anyMatch(w->w.type.equals("Frames")))canvas.repaint();});
        animationTimer.start();
    }

    JComponent preferredFocus() { return canvas; }
    boolean isModified() { return designerDirty || FileDocumentManager.getInstance().isDocumentUnsaved(document); }
    @Override public void dispose() { animationTimer.stop(); }
    void writeState(Element e) { e.setAttribute("zoom", String.valueOf(zoom.getValue())); e.setAttribute("grid", String.valueOf(gridToggle.isSelected())); }
    void readState(Element e) { try { zoom.setValue(Integer.parseInt(e.getAttributeValue("zoom", "100"))); } catch (Exception ignored) {} gridToggle.setSelected(Boolean.parseBoolean(e.getAttributeValue("grid", "true"))); }

    private JComponent createHeader() {
        JBPanel<?> header = new JBPanel<>(new BorderLayout());
        header.setBorder(JBUI.Borders.empty(9, 12));
        JBLabel title = new JBLabel("GUI 设计器"); title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)); left.setOpaque(false); left.add(title); left.add(new JBLabel(file.getName())); left.add(breadcrumb);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); tools.setOpaque(false);
        JButton save = button("保存  Ctrl+S", e -> saveDesigner());
        JButton add = button("＋ 添加控件", e -> showAddMenu(addButtonFrom(e)));
        JButton duplicate = button("复制", e -> duplicateSelected());
        JButton delete = button("删除", e -> deleteSelected());
        JButton format = button("重新解析", e -> discardAndReload());
        JButton source = button("打开源码", e -> sourceCallback.run());
        JButton alignLeft=toolButton("靠左",e->alignSelected(Alignment.LEFT));JButton alignCenter=toolButton("水平居中",e->alignSelected(Alignment.CENTER));JButton alignRight=toolButton("靠右",e->alignSelected(Alignment.RIGHT));
        JButton alignTop=toolButton("靠上",e->alignSelected(Alignment.TOP));JButton alignMiddle=toolButton("垂直居中",e->alignSelected(Alignment.MIDDLE));JButton alignBottom=toolButton("靠下",e->alignSelected(Alignment.BOTTOM));
        tools.add(save); tools.add(add); tools.add(duplicate);tools.add(alignLeft);tools.add(alignCenter);tools.add(alignRight);tools.add(alignTop);tools.add(alignMiddle);tools.add(alignBottom); tools.add(delete); tools.add(format); tools.add(gridToggle); tools.add(new JBLabel("缩放"));
        zoom.setPreferredSize(new Dimension(110, 24)); tools.add(zoom); tools.add(source);
        header.add(left, BorderLayout.WEST); header.add(tools, BorderLayout.EAST); return header;
    }

    private JButton addButtonFrom(ActionEvent e) { return (JButton)e.getSource(); }
    private JButton button(String text, ActionListener l) { JButton b = new JButton(text); b.addActionListener(l); b.setFocusable(false); return b; }
    private JButton toolButton(String tooltip,ActionListener listener){String text=switch(tooltip){case "靠左"->"⇤";case "水平居中"->"↔";case "靠右"->"⇥";case "靠上"->"↥";case "垂直居中"->"↕";default->"↧";};JButton button=button(text,listener);button.setToolTipText(tooltip+"（相对父容器）");button.setMargin(JBUI.insets(2,7));return button;}

    private JComponent createWorkspace() {
        JPanel left = new JPanel(new BorderLayout()); left.setBorder(JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 1));
        JPanel layerHeader=new JPanel(new BorderLayout());layerHeader.setBorder(JBUI.Borders.empty(8,10,7,8));
        JBLabel layerTitle=new JBLabel("控件层级");layerTitle.setFont(layerTitle.getFont().deriveFont(Font.BOLD));layerCount.setForeground(JBColor.GRAY);layerHeader.add(layerTitle,BorderLayout.WEST);layerHeader.add(layerCount,BorderLayout.EAST);
        search.getEmptyText().setText("搜索名称或类型…");search.setBorder(JBUI.Borders.empty(5,8));
        JPanel layerTop=new JPanel(new BorderLayout());layerTop.add(layerHeader,BorderLayout.NORTH);layerTop.add(search,BorderLayout.CENTER);
        hierarchy.setCellRenderer(new WidgetTreeRenderer());hierarchy.setRowHeight(JBUI.scale(28));hierarchy.setRootVisible(false);hierarchy.setShowsRootHandles(true);hierarchy.setToggleClickCount(0);hierarchy.setLargeModel(true);hierarchy.setBorder(JBUI.Borders.empty(5,4,8,4));
        left.add(layerTop, BorderLayout.NORTH); left.add(ScrollPaneFactory.createScrollPane(hierarchy, true), BorderLayout.CENTER);
        left.setMinimumSize(new Dimension(220, 100));

        JScrollPane canvasScroll = ScrollPaneFactory.createScrollPane(canvas, true); canvasScroll.getViewport().setBackground(new JBColor(new Color(0xE9ECF2), new Color(0x17191D)));
        JPanel center = new JPanel(new BorderLayout()); center.add(canvasScroll, BorderLayout.CENTER);

        JPanel right = new JPanel(new BorderLayout()); right.setBorder(JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0));
        JBLabel pTitle = new JBLabel("  属性"); pTitle.setFont(pTitle.getFont().deriveFont(Font.BOLD)); pTitle.setBorder(JBUI.Borders.empty(8));
        properties.setShowGrid(false); properties.setRowHeight(JBUI.scale(27)); properties.getColumnModel().getColumn(0).setPreferredWidth(105);
        properties.setDefaultRenderer(Object.class, new PropertyRenderer());
        properties.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int row=properties.rowAtPoint(e.getPoint()),column=properties.columnAtPoint(e.getPoint());
                if(row<0||column<0)return;
                int modelRow=properties.convertRowIndexToModel(row);
                if(column==1&&propertyModel.isObjectConfig(modelRow)){
                    GuiPropertySchema.Field field=propertyModel.fieldAt(modelRow);
                    SwingUtilities.invokeLater(()->editObjectConfigTable(field));e.consume();
                }else if(column==1&&propertyModel.isBoolean(modelRow)){
                    propertyModel.setValueAt(!Boolean.TRUE.equals(propertyModel.getValueAt(modelRow,1)),modelRow,1);e.consume();
                }else if(column==1&&propertyModel.isPath(modelRow)&&e.getX()>=properties.getCellRect(row,column,true).x+properties.getCellRect(row,column,true).width-JBUI.scale(32)){
                    if(properties.editCellAt(row,column)&&properties.getCellEditor() instanceof PathPropertyEditor editor)SwingUtilities.invokeLater(editor::browse);e.consume();
                }
            }
        });
        right.add(pTitle, BorderLayout.NORTH); right.add(ScrollPaneFactory.createScrollPane(properties, true), BorderLayout.CENTER); right.setMinimumSize(new Dimension(280, 100));

        OnePixelSplitter inner = new OnePixelSplitter(false, 0.76f); inner.setFirstComponent(center); inner.setSecondComponent(right);
        OnePixelSplitter outer = new OnePixelSplitter(false, 0.18f); outer.setFirstComponent(left); outer.setSecondComponent(inner);
        return outer;
    }

    private JComponent createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout()); bar.setBorder(JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(4, 10)));
        bar.add(status, BorderLayout.WEST); bar.add(new JBLabel("空格/中键拖动画布 · Ctrl/Alt+滚轮缩放 · Shift 锁定方向 · Ctrl+Z 撤销"), BorderLayout.EAST); return bar;
    }

    private void configureInteractions() {
        hierarchy.setRootVisible(false); hierarchy.addTreeSelectionListener(e -> {
            Object item = ((DefaultMutableTreeNode)Optional.ofNullable(hierarchy.getLastSelectedPathComponent()).orElse(treeRoot)).getUserObject();
            if (item instanceof LuaGuiDocument.Widget w) select(w);
        });
        MouseAdapter treePopup=new MouseAdapter(){private void showPopup(MouseEvent e){if(!e.isPopupTrigger())return;TreePath path=hierarchy.getPathForLocation(e.getX(),e.getY());if(path==null)return;hierarchy.setSelectionPath(path);showWidgetContextMenu(hierarchy,e.getX(),e.getY());e.consume();}@Override public void mousePressed(MouseEvent e){showPopup(e);}@Override public void mouseReleased(MouseEvent e){showPopup(e);}};hierarchy.addMouseListener(treePopup);
        properties.getSelectionModel().addListSelectionListener((ListSelectionEvent e) -> {});
        gridToggle.addActionListener(e -> canvas.repaint());
        zoom.addChangeListener(e -> { canvas.revalidate(); canvas.repaint(); });
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuildTree(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuildTree(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuildTree(); }
        });
        InputMap im = canvas.getInputMap(JComponent.WHEN_FOCUSED); ActionMap am = canvas.getActionMap();
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete"); am.put("delete", new AbstractAction(){ public void actionPerformed(ActionEvent e){ deleteSelected(); }});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_D,InputEvent.CTRL_DOWN_MASK),"duplicate");am.put("duplicate",new AbstractAction(){public void actionPerformed(ActionEvent e){duplicateSelected();}});
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_C,InputEvent.CTRL_DOWN_MASK),"copyWidget");getActionMap().put("copyWidget",new AbstractAction(){public void actionPerformed(ActionEvent e){copySelected();}});
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_V,InputEvent.CTRL_DOWN_MASK),"pasteWidget");getActionMap().put("pasteWidget",new AbstractAction(){public void actionPerformed(ActionEvent e){pasteCopied();}});
        // JTree installs its own focused copy/paste bindings, which take precedence over
        // the designer panel's ancestor bindings. Override them so tree-focused shortcuts
        // operate on the selected widget subtree instead of copying the row label.
        InputMap treeInput=hierarchy.getInputMap(JComponent.WHEN_FOCUSED);ActionMap treeActions=hierarchy.getActionMap();
        treeInput.put(KeyStroke.getKeyStroke(KeyEvent.VK_C,InputEvent.CTRL_DOWN_MASK),"copyWidgetSubtree");treeActions.put("copyWidgetSubtree",new AbstractAction(){public void actionPerformed(ActionEvent e){copySelected();}});
        treeInput.put(KeyStroke.getKeyStroke(KeyEvent.VK_V,InputEvent.CTRL_DOWN_MASK),"pasteWidgetSubtree");treeActions.put("pasteWidgetSubtree",new AbstractAction(){public void actionPerformed(ActionEvent e){pasteCopied();}});
        treeInput.put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE,0),"deleteWidgetSubtree");treeActions.put("deleteWidgetSubtree",new AbstractAction(){public void actionPerformed(ActionEvent e){deleteSelected();}});
        int[] keys={KeyEvent.VK_LEFT,KeyEvent.VK_RIGHT,KeyEvent.VK_UP,KeyEvent.VK_DOWN}; String[] names={"left","right","up","down"};
        for(int i=0;i<keys.length;i++){
            final int dx=i==0?-1:i==1?1:0, dy=i==2?1:i==3?-1:0;
            im.put(KeyStroke.getKeyStroke(keys[i],0),names[i]);am.put(names[i],new AbstractAction(){public void actionPerformed(ActionEvent e){nudge(dx,dy);}});
            im.put(KeyStroke.getKeyStroke(keys[i],InputEvent.SHIFT_DOWN_MASK),names[i]+"Fast");am.put(names[i]+"Fast",new AbstractAction(){public void actionPerformed(ActionEvent e){nudge(dx*10,dy*10);}});
        }
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK),"undoDesigner");am.put("undoDesigner",new AbstractAction(){public void actionPerformed(ActionEvent e){undoDesigner();}});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Y,InputEvent.CTRL_DOWN_MASK),"redoDesigner");am.put("redoDesigner",new AbstractAction(){public void actionPerformed(ActionEvent e){redoDesigner();}});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK),"redoDesigner");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0,InputEvent.CTRL_DOWN_MASK),"fitCanvas");am.put("fitCanvas",new AbstractAction(){public void actionPerformed(ActionEvent e){canvas.fitCanvas();}});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_1,InputEvent.CTRL_DOWN_MASK),"actualPixels");am.put("actualPixels",new AbstractAction(){public void actionPerformed(ActionEvent e){canvas.zoomFromKeyboard(100);}});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS,InputEvent.CTRL_DOWN_MASK),"zoomIn");im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD,0),"zoomIn");am.put("zoomIn",new AbstractAction(){public void actionPerformed(ActionEvent e){canvas.zoomFromKeyboard(zoom.getValue()+10);}});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS,InputEvent.CTRL_DOWN_MASK|InputEvent.SHIFT_DOWN_MASK),"zoomIn");
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS,InputEvent.CTRL_DOWN_MASK),"zoomOut");im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SUBTRACT,0),"zoomOut");am.put("zoomOut",new AbstractAction(){public void actionPerformed(ActionEvent e){canvas.zoomFromKeyboard(zoom.getValue()-10);}});
        im.put(KeyStroke.getKeyStroke("pressed SPACE"),"handOn");am.put("handOn",new AbstractAction(){public void actionPerformed(ActionEvent e){canvas.setSpaceHeld(true);}});
        im.put(KeyStroke.getKeyStroke("released SPACE"),"handOff");am.put("handOff",new AbstractAction(){public void actionPerformed(ActionEvent e){canvas.setSpaceHeld(false);}});
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),"cancelInteraction");am.put("cancelInteraction",new AbstractAction(){public void actionPerformed(ActionEvent e){canvas.cancelInteraction();}});
        getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "saveDesigner");
        getActionMap().put("saveDesigner", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { saveDesigner(); }
        });
        new AnAction() {
            @Override public void actionPerformed(@NotNull AnActionEvent e) { saveDesigner(); }
        }.registerCustomShortcutSet(ActionManager.getInstance().getAction("SaveAll").getShortcutSet(), this, this);
    }

    void reload() {
        String selectedId = selected == null ? null : selected.id;
        model = LuaGuiDocument.parse(workingText);
        selected = selectedId == null ? null : model.byId.get(selectedId);
        rebuildTree(); propertyModel.fireTableDataChanged(); canvas.revalidate(); canvas.repaint();
        status.setText((designerDirty ? "● 未保存  ·  " : "") + model.widgets.size() + " 个控件  ·  " + file.getPath());
    }

    private void saveDesigner() {
        if (properties.isEditing() && !properties.getCellEditor().stopCellEditing()) return;
        if (!designerDirty) {
            FileDocumentManager.getInstance().saveDocument(document);
            status.setText("已保存  ·  " + file.getPath());
            return;
        }
        if (externalConflict && !document.getText().equals(savedText)) {
            int answer = Messages.showYesNoDialog(project,
                    "源码编辑器中的内容也发生了变化。是否以当前 GUI 设计稿覆盖并保存？",
                    "保存 GUI 设计", Messages.getWarningIcon());
            if (answer != Messages.YES) return;
        }
        internalChange = true;
        try {
            WriteCommandAction.runWriteCommandAction(project,
                    () -> document.replaceString(0, document.getTextLength(), workingText));
            FileDocumentManager.getInstance().saveDocument(document);
        } finally {
            internalChange = false;
        }
        savedText = workingText;
        designerDirty = false;
        externalConflict = false;
        modifiedCallback.accept(false);
        status.setText("已保存  ·  " + file.getPath());
    }

    private void discardAndReload() {
        if (designerDirty) {
            int answer = Messages.showYesNoDialog(project,
                    "当前 GUI 设计稿尚未保存。重新解析会放弃这些修改，是否继续？",
                    "重新解析", Messages.getWarningIcon());
            if (answer != Messages.YES) return;
        }
        imageCache.clear();
        resourcePathCache.clear();effectAtlasCache.clear();pendingEffectDownloads.clear();effectDownloadRetryAt.clear();
        workingText = document.getText();
        savedText = workingText;
        undoStack.clear();
        redoStack.clear();
        designerDirty = false;
        externalConflict = false;
        modifiedCallback.accept(false);
        reload();
    }

    private void markDesignerDirty() {
        boolean dirty=!workingText.equals(savedText);
        if(designerDirty!=dirty){designerDirty=dirty;modifiedCallback.accept(dirty);}
    }

    private void beginEditGroup(){if(!groupingEdit){groupingEdit=true;groupStartText=workingText;}}
    private void endEditGroup(){
        if(!groupingEdit)return;groupingEdit=false;
        if(groupStartText!=null&&!groupStartText.equals(workingText)){undoStack.push(groupStartText);redoStack.clear();}
        groupStartText=null;markDesignerDirty();
    }
    private void undoDesigner(){
        if(undoStack.isEmpty())return;redoStack.push(workingText);workingText=undoStack.pop();markDesignerDirty();reload();
    }
    private void redoDesigner(){
        if(redoStack.isEmpty())return;undoStack.push(workingText);workingText=redoStack.pop();markDesignerDirty();reload();
    }

    private void rebuildTree() {
        Set<String> expanded=expandedWidgetIds();boolean wasEmpty=treeRoot.getChildCount()==0;
        treeRoot.removeAllChildren(); String q = search.getText().trim().toLowerCase(Locale.ROOT);
        for (LuaGuiDocument.Widget w : model.widgets) if (w.parentWidget==null) addTree(treeRoot, w, q);
        layerCount.setText(q.isEmpty()?model.widgets.size()+" 个":hierarchyNodeCount()+" / "+model.widgets.size());
        ((DefaultTreeModel)hierarchy.getModel()).reload();
        if(!q.isEmpty())for(int i=0;i<hierarchy.getRowCount();i++)hierarchy.expandRow(i);
        else {restoreExpanded(expanded);if(wasEmpty)for(int i=0;i<Math.min(hierarchy.getRowCount(),12);i++)hierarchy.expandRow(i);}
        selectTreeNode(selected,false);
    }
    private int hierarchyNodeCount(){int count=0;Enumeration<?> nodes=treeRoot.depthFirstEnumeration();while(nodes.hasMoreElements()){nodes.nextElement();count++;}return Math.max(0,count-1);}
    private boolean addTree(DefaultMutableTreeNode parent, LuaGuiDocument.Widget w, String q) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(w); boolean childMatch=false;
        for (LuaGuiDocument.Widget child:w.children) childMatch |= addTree(node,child,q);
        boolean self=q.isEmpty()||w.displayName().toLowerCase().contains(q)||w.type.toLowerCase().contains(q);
        if(self||childMatch){parent.add(node);return true;} return false;
    }

    private void select(LuaGuiDocument.Widget widget) {
        selected=widget; breadcrumb.setText("/ " + widget.displayName() + "  ·  " + widget.type); propertyModel.fireTableDataChanged(); canvas.repaint();selectTreeNode(widget,true);
    }

    private Set<String> expandedWidgetIds(){Set<String> result=new HashSet<>();Enumeration<TreePath> paths=hierarchy.getExpandedDescendants(new TreePath(treeRoot.getPath()));if(paths!=null)while(paths.hasMoreElements()){Object value=((DefaultMutableTreeNode)paths.nextElement().getLastPathComponent()).getUserObject();if(value instanceof LuaGuiDocument.Widget widget)result.add(widget.id);}return result;}
    private void restoreExpanded(Set<String> ids){Enumeration<?> nodes=treeRoot.depthFirstEnumeration();while(nodes.hasMoreElements()){DefaultMutableTreeNode node=(DefaultMutableTreeNode)nodes.nextElement();Object value=node.getUserObject();if(value instanceof LuaGuiDocument.Widget widget&&ids.contains(widget.id))hierarchy.expandPath(new TreePath(node.getPath()));}}
    private void selectTreeNode(LuaGuiDocument.Widget widget,boolean scroll){if(widget==null)return;Enumeration<?> nodes=treeRoot.depthFirstEnumeration();while(nodes.hasMoreElements()){DefaultMutableTreeNode node=(DefaultMutableTreeNode)nodes.nextElement();Object value=node.getUserObject();if(value instanceof LuaGuiDocument.Widget candidate&&candidate.id.equals(widget.id)){TreePath path=new TreePath(node.getPath());if(!path.equals(hierarchy.getSelectionPath()))hierarchy.setSelectionPath(path);if(scroll)hierarchy.scrollPathToVisible(path);return;}}}

    private void showAddMenu(Component owner) {
        JPopupMenu menu = new JPopupMenu();
        String[] types={"Node","Layout","Image","Button","Text","BmpText","RichText","GradientColorText","TextInput","LoadingBar","ProgressTimer","ListView","ScrollView","PageView","TableView","CheckBox","Slider","Effect","FxEffect","ItemShow","EquipShow","CostItem","ItemBox","UIModel","ParticleEffect","Frames","Spine38Anim","TextAtlas","ScrollText","RedDot","LoadExport"};
        for(String type:types){JMenuItem item=new JMenuItem(type);item.addActionListener(e->addWidget(type));menu.add(item);} menu.show(owner,0,owner.getHeight());
    }

    private void addWidget(String type) {
        String parent = selected == null ? "parent" : selected.variable;
        String base = type; int n=1; while(model.byVariable.containsKey(base+"_"+n))n++; String name=base+"_"+n;
        String args = switch(type) {
            case "Layout" -> parent+", \""+name+"\", 0.00, 0.00, 200.00, 120.00, false";
            case "ListView","ScrollView" -> parent+", \""+name+"\", 0.00, 0.00, 200.00, 120.00, 1";
            case "PageView" -> parent+", \""+name+"\", 0.00, 0.00, 200.00, 120.00";
            case "TableView" -> parent+", \""+name+"\", 0.00, 0.00, 200.00, 120.00, 1, 50.00, 50.00, 1, 1";
            case "Image" -> parent+", \""+name+"\", 0.00, 0.00, \"\"";
            case "Text" -> parent+", \""+name+"\", 0.00, 0.00, 16, \"#FFFFFF\", [[]]";
            case "Button" -> parent+", \""+name+"\", 0.00, 0.00, \"\"";
            case "RichText" -> parent+", \""+name+"\", 0.00, 0.00, [[富文本]], 200, 16, \"#FFFFFF\", 4";
            case "BmpText" -> parent+", \""+name+"\", 0.00, 0.00, \"#FFFFFF\", [[文本]]";
            case "TextInput" -> parent+", \""+name+"\", 0.00, 0.00, 200.00, 30.00, 16";
            case "TextAtlas" -> parent+", \""+name+"\", 0.00, 0.00, \"0\", \"\", 16, 24, \"0\"";
            case "CheckBox" -> parent+", \""+name+"\", 0.00, 0.00, \"\", \"\"";
            case "LoadingBar" -> parent+", \""+name+"\", 0.00, 0.00, \"\", 0";
            case "ProgressTimer" -> parent+", \""+name+"\", 0.00, 0.00, \"\", 0";
            case "Slider" -> parent+", \""+name+"\", 0.00, 0.00, \"\", \"\", \"\"";
            case "Effect" -> parent+", \""+name+"\", 0.00, 0.00, 0, 0, 0, 0, 0, 1";
            case "Frames" -> parent+", \""+name+"\", 0.00, 0.00, \"\", \".png\", 1, 1, {count=1, speed=100, loop=-1, finishhide=0}";
            case "ItemShow" -> parent+", \""+name+"\", 0.00, 0.00, {index=1, count=1, look=true, bgVisible=true, color=255}";
            case "EquipShow" -> parent+", \""+name+"\", 0.00, 0.00, 0, false, {bgVisible=true, doubleTakeOff=false, look=true, movable=false, starLv=false, lookPlayer=false, showModelEffect=false}";
            case "CostItem" -> parent+", \""+name+"\", 0.00, 0.00, {itemId=1, itemCount=1, itemScale=1, titleText=\"\", fontSize=16, simplenum=false}";
            case "FxEffect" -> parent+", \""+name+"\", 0.00, 0.00, 0, 1";
            case "UIModel" -> parent+", \""+name+"\", 0.00, 0.00, 0, {}, 1";
            case "Spine38Anim" -> parent+", \""+name+"\", 0.00, 0.00, \"\", \"\", 0, \"animation\", true";
            case "LoadExport" -> parent+", \"\"";
            default -> parent+", \""+name+"\", 0.00, 0.00";
        };
        String factory=type.equals("LoadExport")?"LoadExport":type+"_Create";String block="\n\t-- Create "+name+"\n\tlocal "+name+" = GUI:"+factory+"("+args+")\n\tGUI:setAnchorPoint("+name+", 0.00, 0.00)\n\tGUI:setTouchEnabled("+name+", false)\n\tGUI:setTag("+name+", 0)\n";
        String text=workingText; int at=text.indexOf("\n\tui.update(__data__)"); if(at<0) at=text.lastIndexOf("\nend"); if(at<0) at=text.length();
        replace(at,at,block); reload(); select(model.byVariable.get(name));
    }

    private void duplicateSelected() {
        if(selected!=null)duplicateWidget(selected);
    }
    private void copySelected(){if(selected==null)return;copiedWidgetId=selected.id;status.setText("已复制到控件剪贴板："+selected.displayName()+"（含 "+subtreeSize(selected)+" 个控件）");}
    private void pasteCopied(){LuaGuiDocument.Widget source=copiedWidgetId==null?null:model.byId.get(copiedWidgetId);if(source==null){status.setText("控件剪贴板为空或源控件已不存在");return;}duplicateWidget(source);}
    private void duplicateWidget(LuaGuiDocument.Widget source){LuaGuiDocument.DuplicateResult copy=model.duplicateSubtree(workingText,source);int at=lineEndOffset(Math.min(lineCount()-1,copy.insertionLine()));replace(at,at,copy.block());reload();select(model.byVariable.get(copy.rootVariable()));status.setText("● 未保存  ·  已粘贴 "+copy.widgetCount()+" 个控件，仅根节点已重命名");}
    private void showWidgetContextMenu(Component owner,int x,int y){
        if(selected==null)return;JPopupMenu menu=new JPopupMenu();JMenuItem title=new JMenuItem(selected.displayName()+"  ·  "+selected.type);title.setEnabled(false);menu.add(title);menu.addSeparator();
        JMenuItem copy=new JMenuItem("复制控件    Ctrl+C");copy.addActionListener(e->copySelected());menu.add(copy);JMenuItem paste=new JMenuItem("粘贴控件    Ctrl+V");paste.setEnabled(copiedWidgetId!=null);paste.addActionListener(e->pasteCopied());menu.add(paste);JMenuItem delete=new JMenuItem("删除控件    Delete");delete.addActionListener(e->deleteSelected());menu.add(delete);
        Set<String> enabled=new LinkedHashSet<>(GuiEditorSettings.getInstance().getState().contextMenuPropertyKeys);String section=null;boolean added=false;
        for(GuiPropertySchema.Field field:GuiPropertySchema.forType(selected.type)){if(!enabled.contains(field.key())||field.valueType()==GuiPropertySchema.ValueType.READ_ONLY)continue;if(!Objects.equals(section,field.section())){menu.addSeparator();section=field.section();}addContextProperty(menu,field);added=true;}
        if(!added){menu.addSeparator();JMenuItem empty=new JMenuItem("未配置快捷属性");empty.setEnabled(false);menu.add(empty);}
        menu.addSeparator();JMenuItem settings=new JMenuItem("配置右键菜单属性…");settings.addActionListener(e->ShowSettingsUtil.getInstance().showSettingsDialog(project,GuiEditorConfigurable.class));menu.add(settings);menu.show(owner,x,y);
    }
    private void addContextProperty(JPopupMenu menu,GuiPropertySchema.Field field){
        Object value=propertyModel.propertyValue(field);String display=String.valueOf(value);if(display.length()>36)display=display.substring(0,33)+"…";
        if(field.key().equals("objectConfig")){LinkedHashMap<String,String> entries=parseObjectConfig(String.valueOf(value));JMenuItem item=new JMenuItem("object_config  ·  "+entries.size()+" 项…");item.addActionListener(e->editObjectConfigTable(field));menu.add(item);return;}
        if(field.key().equals("eventBody")){JMenuItem event=new JMenuItem("点击事件脚本  ·  "+(String.valueOf(value).isBlank()?"未配置":"已配置"));event.addActionListener(e->editClickEventScript(field,String.valueOf(propertyModel.propertyValue(field))));menu.add(event);return;}
        if(field.valueType()==GuiPropertySchema.ValueType.BOOLEAN){JCheckBoxMenuItem item=new JCheckBoxMenuItem(field.label(),Boolean.TRUE.equals(value));item.addActionListener(e->propertyModel.setFieldValue(field,item.isSelected()));menu.add(item);return;}
        if(field.valueType()==GuiPropertySchema.ValueType.PATH){JMenu pathMenu=new JMenu(field.label()+"  ·  "+display);JMenuItem input=new JMenuItem("手动输入…");input.addActionListener(e->editContextProperty(field));pathMenu.add(input);JMenuItem choose=new JMenuItem("选择文件…");choose.addActionListener(e->{String chosen=chooseResourceFile(String.valueOf(propertyModel.propertyValue(field)));if(chosen!=null)propertyModel.setFieldValue(field,normalizeChosenPath(field,chosen));});pathMenu.add(choose);menu.add(pathMenu);return;}
        JMenuItem item=new JMenuItem(field.label()+"  ·  "+display);item.addActionListener(e->editContextProperty(field));menu.add(item);
    }
    private void editContextProperty(GuiPropertySchema.Field field){Object current=propertyModel.propertyValue(field);String value=Messages.showInputDialog(project,"请输入“"+field.label()+"”的新值",field.label(),null,String.valueOf(current),null);if(value!=null)propertyModel.setFieldValue(field,value);}
    private void editClickEventScript(GuiPropertySchema.Field field,String current){LanguageTextField editor=new LanguageTextField(LuaLanguage.INSTANCE,project,current,false);editor.setOneLineMode(false);editor.setPreferredSize(new Dimension(680,300));JBPanel<?> panel=new JBPanel<>(new BorderLayout(0,6));panel.add(new JBLabel("Lua 编辑器 · 使用当前项目索引和代码提示（Ctrl+Space） · 清空即删除"),BorderLayout.NORTH);panel.add(editor,BorderLayout.CENTER);int result=JOptionPane.showConfirmDialog(this,panel,"点击事件脚本",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);if(result==JOptionPane.OK_OPTION)propertyModel.setFieldValue(field,editor.getText().trim().replaceAll("\\R\\s*"," "));}
    private void editObjectConfigTable(GuiPropertySchema.Field field){
        if(field==null)return;
        LinkedHashMap<String,String> entries=parseObjectConfig(String.valueOf(propertyModel.propertyValue(field)));
        DefaultTableModel tableModel=new DefaultTableModel(new Object[]{"键","值（Lua 值）"},0){@Override public boolean isCellEditable(int row,int column){return true;}};
        entries.forEach((key,value)->tableModel.addRow(new Object[]{key,value}));
        JBTable table=new JBTable(tableModel);table.setRowHeight(JBUI.scale(28));table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);table.setShowGrid(true);table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(190));table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(430));
        JButton add=button("＋ 新增行",e->{Set<String> keys=new HashSet<>();for(int row=0;row<tableModel.getRowCount();row++)keys.add(String.valueOf(tableModel.getValueAt(row,0)).trim());String key="key";for(int suffix=2;keys.contains(key);suffix++)key="key"+suffix;tableModel.addRow(new Object[]{key,"\"\""});int row=tableModel.getRowCount()-1;table.setRowSelectionInterval(row,row);table.scrollRectToVisible(table.getCellRect(row,0,true));table.editCellAt(row,0);Component editor=table.getEditorComponent();if(editor!=null)editor.requestFocusInWindow();});
        JButton remove=button("删除选中行",e->{int[] rows=table.getSelectedRows();for(int i=rows.length-1;i>=0;i--)tableModel.removeRow(table.convertRowIndexToModel(rows[i]));});
        JPanel toolbar=new JPanel(new FlowLayout(FlowLayout.LEFT,JBUI.scale(6),0));toolbar.add(add);toolbar.add(remove);
        JBLabel hint=new JBLabel("值支持 Lua 数字、true/false/nil、字符串和 table；普通文字保存时会自动加引号。");hint.setForeground(JBColor.GRAY);
        JPanel content=new JPanel(new BorderLayout(0,JBUI.scale(8)));content.add(toolbar,BorderLayout.NORTH);content.add(ScrollPaneFactory.createScrollPane(table,true),BorderLayout.CENTER);content.add(hint,BorderLayout.SOUTH);content.setPreferredSize(new Dimension(JBUI.scale(700),JBUI.scale(360)));
        while(true){
            int result=JOptionPane.showConfirmDialog(this,content,"编辑 object_config",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE);if(result!=JOptionPane.OK_OPTION)return;
            if(table.isEditing()&&!table.getCellEditor().stopCellEditing())continue;
            LinkedHashMap<String,String> updated=new LinkedHashMap<>();String error=null;
            for(int row=0;row<tableModel.getRowCount();row++){
                String key=String.valueOf(tableModel.getValueAt(row,0)==null?"":tableModel.getValueAt(row,0)).trim();String value=String.valueOf(tableModel.getValueAt(row,1)==null?"":tableModel.getValueAt(row,1)).trim();
                if(key.isEmpty()){error="第 "+(row+1)+" 行的键不能为空。";break;}
                if(!key.matches("[\\p{L}_][\\p{L}\\p{N}_]*")){error="第 "+(row+1)+" 行的键“"+key+"”无效：只能使用字母、数字、下划线，且不能以数字开头。";break;}
                if(updated.containsKey(key)){error="键“"+key+"”重复，请修改或删除重复行。";break;}
                updated.put(key,autoEncodeObjectConfigValue(value));
            }
            if(error!=null){Messages.showErrorDialog(project,error,"object_config 校验失败");continue;}
            writeObjectConfigEntries(field,updated);return;
        }
    }
    private LinkedHashMap<String,String> parseObjectConfig(String source){
        LinkedHashMap<String,String> result=new LinkedHashMap<>();String value=source==null?"":source.trim();if(value.startsWith("{")&&value.endsWith("}"))value=value.substring(1,value.length()-1);if(value.isBlank())return result;
        for(String part:LuaGuiDocument.splitArgs(value)){int equals=topLevelEquals(part);if(equals<=0)continue;String key=part.substring(0,equals).trim(),entryValue=part.substring(equals+1).trim();if(key.matches("[\\p{L}_][\\p{L}\\p{N}_]*")&&!entryValue.isEmpty())result.put(key,entryValue);}
        return result;
    }
    private int topLevelEquals(String value){int level=0;boolean quoted=false;char quote=0;for(int i=0;i<value.length();i++){char c=value.charAt(i);if(quoted){if(c==quote&&(i==0||value.charAt(i-1)!='\\'))quoted=false;continue;}if(c=='\''||c=='\"'){quoted=true;quote=c;continue;}if(c=='{'||c=='['||c=='(')level++;else if(c=='}'||c==']'||c==')')level--;else if(c=='='&&level==0)return i;}return -1;}
    private String autoEncodeObjectConfigValue(String input){String value=input.trim();if(value.isEmpty())return "\"\"";if(value.matches("(?i:true|false|nil)")||value.matches("[-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?")||value.matches("0[xX][0-9a-fA-F]+")||value.startsWith("{")||value.startsWith("[")||value.startsWith("\"")||value.startsWith("'"))return value;return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
    private void writeObjectConfigEntries(GuiPropertySchema.Field field,LinkedHashMap<String,String> entries){StringJoiner joiner=new StringJoiner(", ","{","}");entries.forEach((key,value)->joiner.add(key+"="+value));propertyModel.setFieldValue(field,joiner.toString());}
    private int subtreeSize(LuaGuiDocument.Widget widget){int count=1;for(LuaGuiDocument.Widget child:widget.children)count+=subtreeSize(child);return count;}
    private enum Alignment { LEFT,CENTER,RIGHT,TOP,MIDDLE,BOTTOM }
    private void alignSelected(Alignment alignment){if(selected==null)return;Dimension size=canvas.widgetSize(selected);double width=size.width*Math.abs(selected.scaleX()),height=size.height*Math.abs(selected.scaleY());double parentWidth=selected.parentWidget==null?DesignerCanvas.SCENE_WIDTH:canvas.widgetSize(selected.parentWidget).width;double parentHeight=selected.parentWidget==null?DesignerCanvas.SCENE_HEIGHT:canvas.widgetSize(selected.parentWidget).height;double x=selected.x(),y=selected.y();switch(alignment){case LEFT->x=selected.anchorX()*width;case CENTER->x=parentWidth/2+(selected.anchorX()-.5)*width;case RIGHT->x=parentWidth-(1-selected.anchorX())*width;case BOTTOM->y=selected.anchorY()*height;case MIDDLE->y=parentHeight/2+(selected.anchorY()-.5)*height;case TOP->y=parentHeight-(1-selected.anchorY())*height;}beginEditGroup();updatePosition(x,y);endEditGroup();}

    private final class WidgetTreeRenderer extends DefaultTreeCellRenderer {
        @Override public Component getTreeCellRendererComponent(JTree tree,Object value,boolean selectedCell,boolean expanded,boolean leaf,int row,boolean focus){
            super.getTreeCellRendererComponent(tree,value,selectedCell,expanded,leaf,row,focus);setBorder(JBUI.Borders.empty(3,5));setOpaque(selectedCell);if(!selectedCell)setBackground(tree.getBackground());
            Object item=value instanceof DefaultMutableTreeNode node?node.getUserObject():null;
            if(item instanceof LuaGuiDocument.Widget widget){boolean ownVisible=widget.visible(),inheritedHidden=false;for(LuaGuiDocument.Widget parent=widget.parentWidget;parent!=null;parent=parent.parentWidget)if(!parent.visible()){inheritedHidden=true;break;}boolean effectiveVisible=ownVisible&&!inheritedHidden;String children=widget.children.isEmpty()?"":"  ·  "+widget.children.size(),visibility=ownVisible?(inheritedHidden?"  ·  随父级隐藏":""):"  ·  已隐藏";String nameColor=effectiveVisible?"":selectedCell?"color:#D9DDE5;":"color:#747981;";String stateColor=selectedCell?"#E4C47A":"#A87923";setText("<html><span style='font-weight:600;"+nameColor+"'>"+html(widget.displayName())+"</span> <span style='color:#8C8C8C'>"+html(widget.type)+children+"</span>"+(visibility.isEmpty()?"":"<span style='color:"+stateColor+"'>"+visibility+"</span>")+"</html>");setIcon(new WidgetTypeIcon(widget.type,effectiveVisible));setToolTipText(widget.variable+"  ·  "+widget.type+(widget.children.isEmpty()?"":"  ·  "+widget.children.size()+" 个子控件")+(effectiveVisible?"":ownVisible?"  ·  因父节点隐藏":"  ·  当前控件已隐藏"));}
            return this;
        }
    }
    private static String html(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static final class WidgetTypeIcon implements Icon {
        private final String type;private final boolean visible;WidgetTypeIcon(String type,boolean visible){this.type=type;this.visible=visible;}public int getIconWidth(){return 18;}public int getIconHeight(){return 18;}
        public void paintIcon(Component c,Graphics graphics,int x,int y){Graphics2D g=(Graphics2D)graphics.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color color=switch(type){case "Image","Frames","Effect","FxEffect","SpineAnim","Spine38Anim"->new Color(0xD29922);case "Button","CheckBox","Slider"->new Color(0x2EA043);case "Text","RichText","TextAtlas","BmpText","TextInput"->new Color(0xA371F7);case "Layout","ListView","ScrollView","PageView","TableView"->new Color(0x3574F0);default->new Color(0x6E7681);};if(!visible)color=new Color((color.getRed()+100)/2,(color.getGreen()+100)/2,(color.getBlue()+100)/2);g.setColor(color);g.fillRoundRect(x+1,y+1,16,16,5,5);g.setColor(visible?Color.WHITE:new Color(0xD0D3D8));g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,10));String letter=type.substring(0,1).toUpperCase(Locale.ROOT);FontMetrics fm=g.getFontMetrics();g.drawString(letter,x+9-fm.stringWidth(letter)/2,y+12);if(!visible){g.setColor(new Color(0xE0A83A));g.setStroke(new BasicStroke(2f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawLine(x+2,y+3,x+16,y+16);}g.dispose();}
    }

    private void deleteSelected() {
        if(selected==null)return; if(!selected.children.isEmpty() && Messages.showYesNoDialog(project,"该控件包含子控件，也会一并删除。","删除控件",Messages.getWarningIcon())!=Messages.YES)return;
        Set<LuaGuiDocument.Widget> widgets=new LinkedHashSet<>();collect(selected,widgets);Set<Integer> removeLines=new HashSet<>();widgets.forEach(w->removeLines.addAll(w.ownedLines));String[] lines=workingText.split("\\R",-1);StringBuilder out=new StringBuilder();
        for(int i=0;i<lines.length;i++)if(!removeLines.contains(i))out.append(lines[i]).append('\n');
        selected=null; replace(0,workingText.length(),out.toString()); reload();
    }
    private void collect(LuaGuiDocument.Widget w,Set<LuaGuiDocument.Widget>s){s.add(w);w.children.forEach(c->collect(c,s));}
    private void nudge(int dx,int dy){if(selected!=null)updatePosition(selected.x()+dx,selected.y()+dy);}

    private void updatePosition(double x,double y){
        if(selected==null)return;boolean ownGroup=!groupingEdit;if(ownGroup)beginEditGroup();LuaGuiDocument.Widget widget=selected;String id=widget.id;String fx=fmt(x),fy=fmt(y);LuaGuiDocument.Call position=widget.call("setPosition");
        if(position!=null)replaceLine(position.line,widget.indent+"GUI:setPosition("+widget.variable+", "+fx+", "+fy+")");
        else {
            LuaGuiDocument.Call positionX=widget.call("setPositionX"),positionY=widget.call("setPositionY");
            if(positionX!=null)replaceLine(positionX.line,widget.indent+"GUI:setPositionX("+widget.variable+", "+fx+")");
            if(positionY!=null)replaceLine(positionY.line,widget.indent+"GUI:setPositionY("+widget.variable+", "+fy+")");
            if((positionX==null||positionY==null)&&widget.args.size()>3){List<String> args=new ArrayList<>(widget.args);if(positionX==null)args.set(2,fx);if(positionY==null)args.set(3,fy);String factory=widget.type.equals("LoadExport")?"LoadExport":widget.type+"_Create";replaceLine(widget.createLine,widget.indent+(widget.localDeclaration?"local ":"")+widget.variable+" = GUI:"+factory+"("+String.join(", ",args)+")");}
        }
        reload();selected=model.byId.get(id);if(ownGroup)endEditGroup();
    }
    private String fmt(double n){return String.format(Locale.US,"%.2f",n);}

    private void updateCreateArg(LuaGuiDocument.Widget w,int index,String value){
        if(w==null||index>=w.args.size())return;String id=w.id;List<String>a=new ArrayList<>(w.args);a.set(index,value);String factory=w.type.equals("LoadExport")?"LoadExport":w.type+"_Create";String line=w.indent+(w.localDeclaration?"local ":"")+w.variable+" = GUI:"+factory+"("+String.join(", ",a)+")";replaceLine(w.createLine,line);reload();selected=model.byId.get(id);}
    private void updateCall(LuaGuiDocument.Widget w,String method,String value){
        String id=w.id;LuaGuiDocument.Call c=w.calls.get(method);if(c!=null){replaceLine(c.line,w.indent+"GUI:"+method+"("+w.variable+(value.isBlank()?"":", "+value)+")");}
        else {int at=lineEndOffset(Math.min(lineCount()-1,w.createLine));replace(at,at,"\n"+w.indent+"GUI:"+method+"("+w.variable+(value.isBlank()?"":", "+value)+")");}reload();selected=model.byId.get(id);}
    private void updateCallArg(LuaGuiDocument.Widget w, GuiPropertySchema.Field field, String value){
        LuaGuiDocument.Call call=w.calls.get(field.method());
        List<String> args=new ArrayList<>(call==null?field.callDefaults():call.args);
        while(args.size()<=field.callArg())args.add("0");
        args.set(field.callArg(),value);
        updateCall(w,field.method(),String.join(", ",args));
    }
    private void updateCoordinate(LuaGuiDocument.Widget w,boolean xAxis,String value){
        LuaGuiDocument.Call position=w.call("setPosition");if(position!=null){String x=xAxis?value:(position.args.isEmpty()?fmt(w.x()):position.args.get(0)),y=xAxis?(position.args.size()>1?position.args.get(1):fmt(w.y())):value;updateCall(w,"setPosition",x+", "+y);return;}
        String method=xAxis?"setPositionX":"setPositionY";if(w.call(method)!=null){updateCall(w,method,value);return;}updateCreateArg(w,xAxis?2:3,value);
    }
    private void updateTableArg(LuaGuiDocument.Widget w,GuiPropertySchema.Field field,String value){
        if(field.createArg()>=w.args.size())return;List<String> args=new ArrayList<>(w.args);String table=args.get(field.createArg());
        Pattern keyPattern=Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(field.method())+"\\s*=\\s*([^,}]+)");Matcher matcher=keyPattern.matcher(table);
        if(matcher.find())table=table.substring(0,matcher.start(1))+value+table.substring(matcher.end(1));
        else {int close=table.lastIndexOf('}');if(close<0)table="{"+field.method()+" = "+value+"}";else {String prefix=table.substring(0,close).trim();String separator=prefix.endsWith("{")?"":", ";table=table.substring(0,close)+separator+field.method()+" = "+value+table.substring(close);}}
        args.set(field.createArg(),table);updateCreateArg(w,field.createArg(),table);
    }
    private void updateObjectConfig(LuaGuiDocument.Widget w,String value){
        String id=w.id,normalized=value.trim();LuaGuiDocument.ObjectConfig config=w.objectConfig;
        if(normalized.isBlank()){if(config!=null)replaceLine(config.line,"");}
        else {String line=w.indent+w.variable+"[\"object_config\"] = "+normalized;if(config!=null)replaceLine(config.line,line);else {int insertionLine=w.ownedLines.stream().mapToInt(Integer::intValue).max().orElse(w.createLine);int at=lineEndOffset(Math.min(lineCount()-1,insertionLine));replace(at,at,"\n"+line);}}
        reload();selected=model.byId.get(id);
    }
    private String eventBody(LuaGuiDocument.Widget w){LuaGuiDocument.Call call=w.call("addOnClickEvent");if(call==null||call.args.isEmpty())return "";return call.args.get(0).trim().replaceFirst("^function\\s*\\(\\s*\\)\\s*","").replaceFirst("\\s*end\\s*$","").trim();}
    private List<String> networkEventArgs(LuaGuiDocument.Widget w){Matcher matcher=Pattern.compile("SL:(?:SendNetMsg|SendLuaNetMsg)\\s*\\((.*)\\)\\s*$").matcher(eventBody(w));return matcher.find()?LuaGuiDocument.splitArgs(matcher.group(1)):new ArrayList<>();}
    private String eventValue(LuaGuiDocument.Widget w,String part,String fallback){if(part.equals("body"))return eventBody(w);List<String> args=networkEventArgs(w);int index=part.equals("networkId")?0:Integer.parseInt(part.substring(5));return index<args.size()?LuaGuiDocument.Widget.unquote(args.get(index)):fallback;}
    private void updateEventValue(LuaGuiDocument.Widget w,String part,String value){if(part.equals("body")){if(value.isBlank())removeCall(w,"addOnClickEvent");else updateCall(w,"addOnClickEvent","function() "+value+" end");return;}List<String> args=networkEventArgs(w);while(args.size()<4)args.add("0");int index=part.equals("networkId")?0:Integer.parseInt(part.substring(5));args.set(index,value.isBlank()?"0":value);String method=eventBody(w).contains("SendLuaNetMsg")?"SendLuaNetMsg":"SendNetMsg";updateCall(w,"addOnClickEvent","function() SL:"+method+"("+String.join(", ",args)+") end");}
    private void removeCall(LuaGuiDocument.Widget w,String method){
        String id=w.id;LuaGuiDocument.Call call=w.calls.get(method);if(call==null)return;replaceLine(call.line,"");reload();selected=model.byId.get(id);
    }
    private void replaceLine(int line,String value){int start=lineStartOffset(line),end=lineEndOffset(line);replace(start,end,value);}
    private void replace(int start,int end,String value){String before=workingText;workingText=workingText.substring(0,start)+value+workingText.substring(end);if(!groupingEdit&&!before.equals(workingText)){undoStack.push(before);redoStack.clear();}markDesignerDirty();}
    private int lineCount(){int count=1;for(int i=0;i<workingText.length();i++)if(workingText.charAt(i)=='\n')count++;return count;}
    private int lineStartOffset(int line){int at=0;for(int i=0;i<line&&at<workingText.length();i++){int next=workingText.indexOf('\n',at);at=next<0?workingText.length():next+1;}return at;}
    private int lineEndOffset(int line){int start=lineStartOffset(line),end=workingText.indexOf('\n',start);if(end<0)end=workingText.length();if(end>start&&workingText.charAt(end-1)=='\r')end--;return end;}

    private final class PropertyModel extends AbstractTableModel {
        private final List<Row> rows=new ArrayList<>();
        void build(){
            rows.clear();if(selected==null)return;String section=null;
            for(GuiPropertySchema.Field field:GuiPropertySchema.forType(selected.type)){
                if(!field.section().equals(section)){
                    section=field.section();rows.add(Row.section(section));
                    if(section.equals("属性")){rows.add(Row.info("变量",selected.variable));rows.add(Row.info("控件类型",selected.type));rows.add(Row.info("父级",String.valueOf(selected.parent())));}
                }
                rows.add(new Row(field.label(),propertyValue(field),field,false));
            }
        }
        private Object propertyValue(GuiPropertySchema.Field field){
            String value;
            switch(field.access()){
                case CREATE_ARG -> value=field.createArg()<selected.args.size()?selected.args.get(field.createArg()):field.defaultValue();
                case CALL_ARG -> {LuaGuiDocument.Call call=selected.calls.get(field.method());if(field.callArg()<0)value=String.valueOf(call!=null);else value=call!=null&&field.callArg()<call.args.size()?call.args.get(field.callArg()):field.defaultValue();}
                case TABLE_VALUE -> {String table=field.createArg()<selected.args.size()?selected.args.get(field.createArg()):"";Matcher matcher=Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(field.method())+"\\s*=\\s*([^,}]+)").matcher(table);value=matcher.find()?matcher.group(1).trim():field.defaultValue();}
                case EVENT_VALUE -> value=eventValue(selected,field.method(),field.defaultValue());
                case OBJECT_CONFIG -> value=selected.objectConfig==null?field.defaultValue():selected.objectConfig.value;
                case WIDTH -> value=fmt(canvas.widgetSize(selected).width);
                case HEIGHT -> value=fmt(canvas.widgetSize(selected).height);
                default -> value=field.defaultValue();
            }
            value=LuaGuiDocument.Widget.unquote(value);
            if(field.key().equals("scaleX")&&selected.call("setScaleX")==null)value=fmt(selected.scaleX());
            if(field.key().equals("scaleY")&&selected.call("setScaleY")==null)value=fmt(selected.scaleY());
            if(field.key().equals("x"))value=fmt(selected.x());
            if(field.key().equals("y"))value=fmt(selected.y());
            if(field.key().equals("adaptSize"))value=String.valueOf(!Boolean.parseBoolean(value));
            return field.valueType()==GuiPropertySchema.ValueType.BOOLEAN?(value.equals("1")||Boolean.parseBoolean(value)):value;
        }
        @Override public void fireTableDataChanged(){build();super.fireTableDataChanged();}
        public int getRowCount(){return rows.size();}public int getColumnCount(){return 2;}public String getColumnName(int c){return c==0?"属性":"值";}public Object getValueAt(int r,int c){Row x=rows.get(r);return c==0?x.name:x.value;}public boolean isCellEditable(int r,int c){return c==1&&rows.get(r).field!=null&&!rows.get(r).section&&!isBoolean(r)&&!isObjectConfig(r)&&rows.get(r).field.valueType()!=GuiPropertySchema.ValueType.READ_ONLY;}
        boolean isBoolean(int row){return row>=0&&row<rows.size()&&rows.get(row).field!=null&&rows.get(row).field.valueType()==GuiPropertySchema.ValueType.BOOLEAN;}
        boolean isObjectConfig(int row){return row>=0&&row<rows.size()&&rows.get(row).field!=null&&rows.get(row).field.key().equals("objectConfig");}
        boolean isPath(int row){return row>=0&&row<rows.size()&&rows.get(row).field!=null&&rows.get(row).field.valueType()==GuiPropertySchema.ValueType.PATH;}
        GuiPropertySchema.Field fieldAt(int row){return row>=0&&row<rows.size()?rows.get(row).field:null;}
        boolean isSection(int row){return row>=0&&row<rows.size()&&rows.get(row).section;}
        public void setValueAt(Object value,int row,int col){
            if(row<0||row>=rows.size())return;GuiPropertySchema.Field field=rows.get(row).field;if(field!=null)setFieldValue(field,value);
        }
        void setFieldValue(GuiPropertySchema.Field field,Object value){
            if(selected==null||field==null)return;
            String raw=String.valueOf(value),encoded=switch(field.valueType()){
                case TEXT,PATH,COLOR -> "\""+raw.replace("\\","/").replace("\"","\\\"")+"\"";
                case BOOLEAN -> String.valueOf(Boolean.parseBoolean(raw));
                default -> raw;
            };
            if(field.key().equals("adaptSize"))encoded=String.valueOf(!Boolean.parseBoolean(raw));
            if(field.valueType()==GuiPropertySchema.ValueType.PATH)imageCache.clear();
            switch(field.access()){
                case CREATE_ARG -> {if(field.key().equals("x"))updateCoordinate(selected,true,encoded);else if(field.key().equals("y"))updateCoordinate(selected,false,encoded);else updateCreateArg(selected,field.createArg(),encoded);}
                case CALL_ARG -> {if(field.callArg()<0){if(Boolean.parseBoolean(raw))updateCall(selected,field.method(),String.join(", ",field.callDefaults()));else removeCall(selected,field.method());}else updateCallArg(selected,field,encoded);}
                case TABLE_VALUE -> updateTableArg(selected,field,encoded);
                case EVENT_VALUE -> updateEventValue(selected,field.method(),raw);
                case OBJECT_CONFIG -> updateObjectConfig(selected,raw);
                case WIDTH -> {if(isSizedContainer(selected)&&selected.args.size()>4)updateCreateArg(selected,4,raw);else updateCall(selected,"setContentSize",raw+", "+fmt(canvas.widgetSize(selected).height));}
                case HEIGHT -> {if(isSizedContainer(selected)&&selected.args.size()>5)updateCreateArg(selected,5,raw);else updateCall(selected,"setContentSize",fmt(canvas.widgetSize(selected).width)+", "+raw);}
            }
        }
        private boolean isSizedContainer(LuaGuiDocument.Widget widget){return Set.of("Layout","ListView","ScrollView","PageView","TableView","TextInput").contains(widget.type);}
    }
    private record Row(String name,Object value,GuiPropertySchema.Field field,boolean section){
        static Row section(String name){return new Row("▾ "+name,"",null,true);}
        static Row info(String name,String value){return new Row(name,value,null,false);}
    }

    private final class PathPropertyEditor extends AbstractCellEditor implements TableCellEditor {
        private final int modelRow;private final JBTextField field=new JBTextField();private final JPanel panel=new JPanel(new BorderLayout());
        PathPropertyEditor(int viewRow){modelRow=properties.convertRowIndexToModel(viewRow);JButton browse=new JButton("…");browse.setToolTipText("选择资源文件（仍可直接输入路径）");browse.setMargin(JBUI.insets(0,6));browse.setFocusable(false);field.setBorder(JBUI.Borders.empty(0,5));field.addActionListener(e->stopCellEditing());browse.addActionListener(e->browse());panel.add(field,BorderLayout.CENTER);panel.add(browse,BorderLayout.EAST);}
        void browse(){String chosen=chooseResourceFile(field.getText());if(chosen!=null){GuiPropertySchema.Field schema=propertyModel.fieldAt(modelRow);field.setText(normalizeChosenPath(schema,chosen));stopCellEditing();}}
        @Override public Object getCellEditorValue(){return field.getText();}
        @Override public Component getTableCellEditorComponent(JTable table,Object value,boolean selectedCell,int row,int column){field.setText(value==null?"":String.valueOf(value));SwingUtilities.invokeLater(field::requestFocusInWindow);return panel;}
    }

    private String chooseResourceFile(String current){
        FileChooserDescriptor descriptor=FileChooserDescriptorFactory.createSingleFileDescriptor();descriptor.setTitle("选择 GUI 资源文件");descriptor.setDescription("选择后自动写入相对于 dev 的资源路径；也可以直接在属性中输入路径。");
        Path initial=resolveResource(current==null?"":current);if(initial==null){for(Path root:resourceRoots())if(Files.isDirectory(root.resolve("res"))){initial=root.resolve("res");break;}}if(initial==null&&project.getBasePath()!=null)initial=Path.of(project.getBasePath());
        VirtualFile initialFile=initial==null?null:LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Files.isDirectory(initial)?initial:initial.getParent());VirtualFile chosen=FileChooser.chooseFile(descriptor,project,initialFile);if(chosen==null)return null;Path selectedPath=Path.of(chosen.getPath()).toAbsolutePath().normalize();
        for(Path root:resourceRoots())try{Path normalized=root.toAbsolutePath().normalize();if(selectedPath.startsWith(normalized))return normalized.relativize(selectedPath).toString().replace('\\','/');}catch(Exception ignored){}
        return selectedPath.toString().replace('\\','/');
    }

    private List<Path> resourceRoots(){
        LinkedHashSet<Path> roots=new LinkedHashSet<>();Path source=Path.of(file.getPath()).toAbsolutePath().normalize();for(Path at=source.getParent();at!=null;at=at.getParent())if(at.getFileName()!=null&&at.getFileName().toString().equalsIgnoreCase("GUIExport")){if(at.getParent()!=null)roots.add(at.getParent());break;}
        for(String configured:GuiEditorSettings.getInstance().getState().guiExportRoots)try{Path export=Path.of(configured).toAbsolutePath().normalize();if(export.getParent()!=null)roots.add(export.getParent());}catch(Exception ignored){}if(project.getBasePath()!=null){Path base=Path.of(project.getBasePath()).toAbsolutePath().normalize();roots.add(base);roots.add(base.resolve("dev"));}return new ArrayList<>(roots);
    }

    private String normalizeChosenPath(GuiPropertySchema.Field schema,String path){
        if(schema!=null&&schema.key().equals("framesPath")&&selected!=null){String suffix=selected.argString(5,".png");if(!suffix.isEmpty()&&path.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT)))path=path.substring(0,path.length()-suffix.length());String frame=String.valueOf(selected.argInt(6,1));if(path.endsWith(frame))path=path.substring(0,path.length()-frame.length());}return path;
    }

    private final class PropertyRenderer implements TableCellRenderer {
        private final DefaultTableCellRenderer textRenderer=new DefaultTableCellRenderer();
        private final JCheckBox checkBox=new JCheckBox();
        private final DefaultTableCellRenderer pathText=new DefaultTableCellRenderer();private final JBLabel pathButton=new JBLabel("  …  ");private final JPanel pathPanel=new JPanel(new BorderLayout());
        PropertyRenderer(){pathButton.setToolTipText("选择文件");pathButton.setHorizontalAlignment(SwingConstants.CENTER);pathPanel.add(pathText,BorderLayout.CENTER);pathPanel.add(pathButton,BorderLayout.EAST);}
        @Override public Component getTableCellRendererComponent(JTable table,Object value,boolean selectedCell,boolean focus,int row,int column){
            if(column==1&&propertyModel.isBoolean(row)){
                checkBox.setSelected(Boolean.TRUE.equals(value));checkBox.setHorizontalAlignment(SwingConstants.LEFT);checkBox.setOpaque(true);
                checkBox.setBackground(selectedCell?table.getSelectionBackground():table.getBackground());return checkBox;
            }
            if(column==1&&propertyModel.isPath(table.convertRowIndexToModel(row))){pathText.getTableCellRendererComponent(table,value,selectedCell,focus,row,column);pathPanel.setBackground(pathText.getBackground());pathButton.setOpaque(true);pathButton.setBackground(pathText.getBackground());pathButton.setForeground(pathText.getForeground());return pathPanel;}
            Component component=textRenderer.getTableCellRendererComponent(table,value,selectedCell,focus,row,column);
            if(propertyModel.isSection(row)){
                component.setFont(component.getFont().deriveFont(Font.BOLD));component.setBackground(new JBColor(new Color(0xE7E9ED),new Color(0x34363A)));
            }else component.setFont(component.getFont().deriveFont(Font.PLAIN));
            return component;
        }
    }

    private final class DesignerCanvas extends JComponent {
        static final int SCENE_WIDTH = 1136, SCENE_HEIGHT = 640;private static final int PAD = 80;
        private LuaGuiDocument.Widget dragging; private Point dragStart; private double startX,startY;
        private double dragPreviewX,dragPreviewY;private boolean dragPreviewActive;
        private boolean spaceHeld,panning;
        private Point panStartScreen,panStartView;

        DesignerCanvas(){
            setFocusable(true); setOpaque(true); setBackground(new JBColor(new Color(0x303030), new Color(0x202124)));
            MouseAdapter mouse=new MouseAdapter(){
                public void mousePressed(MouseEvent e){
                    requestFocusInWindow();
                    if(e.isPopupTrigger()){showPopup(e);return;}
                    if(SwingUtilities.isMiddleMouseButton(e)||(spaceHeld&&SwingUtilities.isLeftMouseButton(e))){startPan(e);return;}
                    if(!SwingUtilities.isLeftMouseButton(e))return;
                    dragging=hit(e.getPoint());
                    if(dragging!=null){
                        select(dragging);beginEditGroup();
                        if(e.isAltDown()){duplicateSelected();dragging=selected;}
                        dragStart=e.getPoint();startX=dragging.x();startY=dragging.y();dragPreviewActive=false;
                    }
                }
                public void mouseDragged(MouseEvent e){
                    if(panning){panTo(e);return;}
                    if(dragging!=null){
                        double z=zoom.getValue()/100.0,dx=(e.getX()-dragStart.x)/z,dy=-(e.getY()-dragStart.y)/z;
                        if(e.isShiftDown()){if(Math.abs(dx)>=Math.abs(dy))dy=0;else dx=0;}
                        double nx=startX+dx,ny=startY+dy;int step=GuiEditorSettings.getInstance().getState().gridSize;
                        if(gridToggle.isSelected()&&!e.isControlDown()){nx=Math.round(nx/step)*step;ny=Math.round(ny/step)*step;}
                        dragPreviewX=nx;dragPreviewY=ny;dragPreviewActive=true;repaint();
                    }
                }
                public void mouseReleased(MouseEvent e){
                    if(e.isPopupTrigger()){showPopup(e);return;}
                    if(panning){panning=false;setCursor(spaceHeld?Cursor.getPredefinedCursor(Cursor.HAND_CURSOR):Cursor.getDefaultCursor());}
                    if(dragging!=null){boolean commit=dragPreviewActive;double x=dragPreviewX,y=dragPreviewY;dragPreviewActive=false;if(commit)updatePosition(x,y);dragging=null;endEditGroup();}
                }
                private void showPopup(MouseEvent e){LuaGuiDocument.Widget target=hit(e.getPoint());if(target!=null)select(target);if(selected!=null)showWidgetContextMenu(DesignerCanvas.this,e.getX(),e.getY());e.consume();}
            }; addMouseListener(mouse); addMouseMotionListener(mouse);
            addMouseWheelListener(e -> {
                if(e.isControlDown()||e.isAltDown()){e.consume();adjustZoomAt(e);return;}
                if(e.isShiftDown()){
                    JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);
                    if(viewport!=null){Point p=viewport.getViewPosition();p.x+=e.getWheelRotation()*40;viewport.setViewPosition(clampViewPosition(viewport,p));e.consume();}
                }
            });
        }

        void setSpaceHeld(boolean held){spaceHeld=held;if(!panning)setCursor(held?Cursor.getPredefinedCursor(Cursor.HAND_CURSOR):Cursor.getDefaultCursor());}
        void cancelInteraction(){
            if(dragging!=null&&groupingEdit&&groupStartText!=null){workingText=groupStartText;groupingEdit=false;groupStartText=null;dragging=null;dragPreviewActive=false;markDesignerDirty();reload();}
            panning=false;setCursor(spaceHeld?Cursor.getPredefinedCursor(Cursor.HAND_CURSOR):Cursor.getDefaultCursor());
        }
        private void startPan(MouseEvent event){
            JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);if(viewport==null)return;
            panning=true;dragging=null;panStartScreen=event.getLocationOnScreen();panStartView=viewport.getViewPosition();setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));event.consume();
        }
        private void panTo(MouseEvent event){
            JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);if(viewport==null)return;
            Point now=event.getLocationOnScreen();Point target=new Point(panStartView.x-(now.x-panStartScreen.x),panStartView.y-(now.y-panStartScreen.y));viewport.setViewPosition(clampViewPosition(viewport,target));event.consume();
        }
        private Point clampViewPosition(JViewport viewport,Point point){
            Dimension extent=viewport.getExtentSize(),view=getPreferredSize();return new Point(Math.max(0,Math.min(point.x,Math.max(0,view.width-extent.width))),Math.max(0,Math.min(point.y,Math.max(0,view.height-extent.height))));
        }

        private void adjustZoomAt(MouseWheelEvent event){
            int oldValue=zoom.getValue();
            int direction=event.getWheelRotation()==0?(event.getPreciseWheelRotation()>0?1:-1):event.getWheelRotation();
            int newValue=Math.max(zoom.getMinimum(),Math.min(zoom.getMaximum(),oldValue-direction*10));
            if(newValue==oldValue)return;
            JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);
            if(viewport==null){zoom.setValue(newValue);return;}
            Point oldView=viewport.getViewPosition();
            Point cursor=SwingUtilities.convertPoint(this,event.getPoint(),viewport);
            double ratio=(double)newValue/oldValue;
            int targetX=(int)Math.round((oldView.x+cursor.x)*ratio-cursor.x);
            int targetY=(int)Math.round((oldView.y+cursor.y)*ratio-cursor.y);
            zoom.setValue(newValue);
            SwingUtilities.invokeLater(()->{
                Dimension extent=viewport.getExtentSize(),view=getPreferredSize();
                viewport.setViewPosition(new Point(
                        Math.max(0,Math.min(targetX,Math.max(0,view.width-extent.width))),
                        Math.max(0,Math.min(targetY,Math.max(0,view.height-extent.height)))));
            });
        }

        void zoomFromKeyboard(int requested){
            int value=Math.max(zoom.getMinimum(),Math.min(zoom.getMaximum(),requested));JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);
            if(viewport==null){zoom.setValue(value);return;}Point p=viewport.getViewPosition();Dimension extent=viewport.getExtentSize();zoomAt(value,new Point(p.x+extent.width/2,p.y+extent.height/2));
        }
        private void zoomAt(int newValue,Point componentPoint){
            int oldValue=zoom.getValue();if(newValue==oldValue)return;JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);if(viewport==null){zoom.setValue(newValue);return;}
            Point oldView=viewport.getViewPosition(),cursor=SwingUtilities.convertPoint(this,componentPoint,viewport);double ratio=(double)newValue/oldValue;
            Point target=new Point((int)Math.round((oldView.x+cursor.x)*ratio-cursor.x),(int)Math.round((oldView.y+cursor.y)*ratio-cursor.y));zoom.setValue(newValue);SwingUtilities.invokeLater(()->viewport.setViewPosition(clampViewPosition(viewport,target)));
        }
        void fitCanvas(){
            JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);if(viewport==null)return;Dimension extent=viewport.getExtentSize();int value=(int)Math.floor(100*Math.min((extent.width-20d)/(SCENE_WIDTH+PAD*2),(extent.height-20d)/(SCENE_HEIGHT+PAD*2)));zoom.setValue(Math.max(zoom.getMinimum(),Math.min(zoom.getMaximum(),value)));SwingUtilities.invokeLater(()->{Dimension view=getPreferredSize();viewport.setViewPosition(clampViewPosition(viewport,new Point((view.width-extent.width)/2,(view.height-extent.height)/2)));});
        }

        @Override public Dimension getPreferredSize(){double z=zoom.getValue()/100.0;return new Dimension((int)((SCENE_WIDTH+PAD*2)*z),(int)((SCENE_HEIGHT+PAD*2)*z));}

        @Override protected void paintComponent(Graphics raw){
            super.paintComponent(raw); Graphics2D g=(Graphics2D)raw.create(); double z=zoom.getValue()/100.0; g.scale(z,z);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(new JBColor(new Color(0x3A3A3A),new Color(0x292A2D))); g.fillRect(PAD,PAD,SCENE_WIDTH,SCENE_HEIGHT);
            if(gridToggle.isSelected()){g.setColor(new JBColor(new Color(0x4A4A4A),new Color(0x34363A)));int s=GuiEditorSettings.getInstance().getState().gridSize;for(int x=0;x<=SCENE_WIDTH;x+=s)g.drawLine(PAD+x,PAD,PAD+x,PAD+SCENE_HEIGHT);for(int y=0;y<=SCENE_HEIGHT;y+=s)g.drawLine(PAD,PAD+y,PAD+SCENE_WIDTH,PAD+y);}
            List<LuaGuiDocument.Widget> order=renderOrder();
            for(LuaGuiDocument.Widget w:order)if(effectiveVisible(w)){Graphics2D widgetGraphics=(Graphics2D)g.create();applyAncestorClip(widgetGraphics,w);paintWidget(widgetGraphics,w);widgetGraphics.dispose();}
            for(LuaGuiDocument.Widget w:order)if(w.type.equals("ItemShow"))paintItemShowOverlay(g,w);
            g.setColor(new JBColor(new Color(0x777777),new Color(0x62656A)));g.drawRect(PAD,PAD,SCENE_WIDTH,SCENE_HEIGHT);g.dispose();
        }

        private void paintWidget(Graphics2D g,LuaGuiDocument.Widget w){
            Rectangle r=bounds(w);boolean sel=selected!=null&&selected.id.equals(w.id);BufferedImage image=widgetImage(w);Rectangle paintBounds=w.type.equals("Effect")&&image!=null?effectPaintBounds(w):r;
            Graphics2D item=(Graphics2D)g.create();float alpha=(float)effectiveOpacity(w);item.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,alpha));
            if(w.rotation()!=0){item.rotate(Math.toRadians(-w.rotation()),r.getCenterX(),r.getCenterY());}
            if(w.callBoolean("setFlippedX",0,false)||w.scaleX()<0){item.translate(paintBounds.x*2+paintBounds.width,0);item.scale(-1,1);}
            if(w.callBoolean("setFlippedY",0,false)||w.scaleY()<0){item.translate(0,paintBounds.y*2+paintBounds.height);item.scale(1,-1);}
            if(w.type.equals("TextAtlas")){paintTextAtlas(item,w,r,image);image=null;}
            else if(image!=null&&w.type.equals("Slider")){paintSlider(item,w,r,image);}
            else if(image!=null&&(w.type.equals("LoadingBar")||w.type.equals("ProgressTimer"))){paintLoadingBar(item,w,r,image);}
            else if(image!=null){Image shown=w.callBoolean("Image_setGrey",0,false)?GrayFilter.createDisabledImage(image):image;LuaGuiDocument.Call slice=scale9Call(w);if(slice!=null&&shown instanceof BufferedImage buffered)drawNineSlice(item,buffered,paintBounds,slice);else item.drawImage(shown,paintBounds.x,paintBounds.y,paintBounds.width,paintBounds.height,null);}
            else if(isContainer(w.type)){Color background=parseColor(LuaGuiDocument.Widget.unquote(w.callArg(w.type+"_setBackGroundColor",0,"")),new Color(53,116,240));int opacity=(int)number(w.callArg(w.type+"_setBackGroundColorOpacity",0,sel?"45":"12"),sel?45:12);item.setColor(new Color(background.getRed(),background.getGreen(),background.getBlue(),Math.max(0,Math.min(255,opacity))));item.fillRect(r.x,r.y,r.width,r.height);}
            else if(!Set.of("Text","RichText","BmpText","TextInput").contains(w.type)){Color fill=color(w.type);item.setColor(new Color(fill.getRed(),fill.getGreen(),fill.getBlue(),55));item.fillRoundRect(r.x,r.y,r.width,r.height,6,6);}
            paintText(item,w,r);
            if(w.type.equals("ItemShow")){String count=w.tableValue(4,"count","1");if(number(count,1)>1){item.setColor(Color.WHITE);item.setFont(item.getFont().deriveFont(Font.BOLD,12f));item.drawString(count,r.x+Math.max(2,r.width-item.getFontMetrics().stringWidth(count)-3),r.y+r.height-3);}}
            if(image==null&&!w.texture().isBlank()&&!w.type.equals("TextAtlas")){item.setColor(new Color(255,90,90));item.setFont(item.getFont().deriveFont(11f));item.drawString("缺少资源: "+new File(w.texture()).getName(),r.x+4,r.y+Math.min(r.height-3,14));}
            if(image==null&&Set.of("Effect","FxEffect","ParticleEffect","SpineAnim","Spine38Anim","UIModel","EquipShow","ItemBox","CostItem").contains(w.type))paintAdvancedPlaceholder(item,w,r);
            item.dispose();
            if(sel) paintSelection(g,r); else if(isContainer(w.type)){g.setColor(new Color(53,116,240,100));g.drawRect(r.x,r.y,r.width,r.height);}
        }

        private void paintText(Graphics2D g,LuaGuiDocument.Widget w,Rectangle r){
            String text=w.text();if(text.isBlank()||w.type.equals("TextAtlas"))return;if(w.type.equals("RichText")){paintRichText(g,w,r,text);return;}
            int style=w.callBoolean("Text_enableBold",0,false)?Font.BOLD:Font.PLAIN;int size=Math.max(8,w.fontSize());Font font=getFont().deriveFont(style,(float)size);g.setFont(font);FontMetrics fm=g.getFontMetrics();Color color=parseColor(w.textColor(),Color.WHITE);
            int x=r.x,y=r.y+fm.getAscent();String alignMethod=w.type.equals("Button")?null:"Text_setTextHorizontalAlignment";int align=(int)w.callNumber(alignMethod==null?"":alignMethod,0,0);if(w.type.equals("Button")||align==1)x=r.x+(r.width-fm.stringWidth(text))/2;else if(align==2)x=r.x+r.width-fm.stringWidth(text);if(w.type.equals("Button"))y=r.y+(r.height-fm.getHeight())/2+fm.getAscent();
            String enable=w.type.equals("Button")?"Button_titleEnableOutline":"Text_enableOutline",disable=w.type.equals("Button")?"Button_titleDisableOutLine":"Text_disableOutLine";LuaGuiDocument.Call outline=w.callAfter(enable,disable)?w.call(enable):null;
            if(outline!=null){Color oc=parseColor(LuaGuiDocument.Widget.unquote(outline.args.isEmpty()?"#000000":outline.args.get(0)),Color.BLACK);int os=(int)Math.max(1,outline.args.size()>1?number(outline.args.get(1),1):1);g.setColor(oc);for(int dx=-os;dx<=os;dx++)for(int dy=-os;dy<=os;dy++)if(dx!=0||dy!=0)g.drawString(text,x+dx,y+dy);}
            g.setColor(color);g.drawString(text,x,y);if(w.callBoolean("Text_enableUnderline",0,false))g.drawLine(x,y+2,x+fm.stringWidth(text),y+2);
        }

        private BufferedImage widgetImage(LuaGuiDocument.Widget w){
            if(w.type.equals("Frames")){int start=w.argInt(6,1),end=Math.max(start,w.argInt(7,start)),speed=Math.max(20,w.tableInt(8,"speed",100));int frame=start+(int)((System.currentTimeMillis()/speed)%(end-start+1));return loadImage(w.argString(4,"")+frame+w.argString(5,".png"));}
            if(w.type.equals("Effect"))return effectImage(w);
            if(w.type.equals("ItemShow"))return itemShowImage(w);
            return loadImage(w.texture());
        }
        private void paintAdvancedPlaceholder(Graphics2D g,LuaGuiDocument.Widget w,Rectangle r){
            String detail=switch(w.type){
                case "Effect" -> "ID "+w.argInt(5,0);
                case "FxEffect" -> "ID "+w.argInt(4,0);
                case "ItemShow" -> "ItemShow · 缺少 000115.png";
                case "EquipShow" -> "装备位 "+w.argInt(4,0);
                case "UIModel" -> "模型预览";
                default -> w.type;
            };
            g.setColor(new Color(255,255,255,205));g.setFont(getFont().deriveFont(Font.BOLD,11f));
            int x=r.x+Math.max(3,(r.width-g.getFontMetrics().stringWidth(detail))/2),y=r.y+Math.max(g.getFontMetrics().getAscent()+2,(r.height+g.getFontMetrics().getAscent())/2);
            g.drawString(detail,x,y);
        }
        private void paintItemShowOverlay(Graphics2D g,LuaGuiDocument.Widget w){
            Rectangle r=bounds(w);boolean rendered=itemShowImage(w)!=null,visible=effectiveVisible(w);Stroke old=g.getStroke();Composite oldComposite=g.getComposite();g.setComposite(AlphaComposite.SrcOver);
            if(!rendered||!visible){g.setColor(new Color(22,24,28,220));g.fillRoundRect(r.x,r.y,r.width,r.height,6,6);}
            g.setStroke(new BasicStroke(2f,BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER,10f,new float[]{5f,3f},0f));g.setColor(visible?new Color(255,174,45):new Color(155,160,170));g.drawRoundRect(r.x,r.y,r.width,r.height,6,6);g.setStroke(old);
            String label=visible?"ItemShow":"ItemShow · 隐藏";g.setFont(getFont().deriveFont(Font.BOLD,10f));int labelWidth=g.getFontMetrics().stringWidth(label)+8,labelHeight=15;g.setColor(new Color(24,26,30,235));g.fillRoundRect(r.x,r.y-labelHeight+2,labelWidth,labelHeight,5,5);g.setColor(visible?new Color(255,190,70):new Color(190,195,205));g.drawString(label,r.x+4,r.y-2);
            if(!rendered){g.setFont(getFont().deriveFont(9f));String missing="缺少 000115.png";int textX=r.x+Math.max(2,(r.width-g.getFontMetrics().stringWidth(missing))/2);int textY=r.y+Math.max(12,(r.height+g.getFontMetrics().getAscent())/2);g.setColor(new Color(255,205,105));g.drawString(missing,textX,textY);}
            g.setComposite(oldComposite);
        }
        private BufferedImage itemShowImage(LuaGuiDocument.Widget w){
            BufferedImage coin=loadImage("res/item/item_0/000115.png");if(coin==null)return null;boolean showBackground=Boolean.parseBoolean(w.tableValue(4,"bgVisible","true"));BufferedImage background=showBackground?loadImage("res/private/item_tips/1900025001.png"):null;
            int size=60;BufferedImage result=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);Graphics2D graphics=result.createGraphics();graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);if(background!=null)graphics.drawImage(background,0,0,size,size,null);double scale=Math.min(1d,Math.min(48d/coin.getWidth(),48d/coin.getHeight()));int width=Math.max(1,(int)Math.round(coin.getWidth()*scale)),height=Math.max(1,(int)Math.round(coin.getHeight()*scale));graphics.drawImage(coin,(size-width)/2,(size-height)/2,width,height,null);graphics.dispose();return result;
        }
        private LuaGuiDocument.Call scale9Call(LuaGuiDocument.Widget w){if(w.type.equals("Image"))return w.call("Image_setScale9Slice");if(w.type.equals("Button"))return w.call("Button_setScale9Slice");if(isContainer(w.type))return w.call(w.type+"_setBackGroundImageScale9Slice");return null;}
        private void drawNineSlice(Graphics2D g,BufferedImage image,Rectangle r,LuaGuiDocument.Call call){int left=(int)number(call.args.size()>0?call.args.get(0):"0",0),right=(int)number(call.args.size()>1?call.args.get(1):"0",0),top=(int)number(call.args.size()>2?call.args.get(2):"0",0),bottom=(int)number(call.args.size()>3?call.args.get(3):"0",0);left=Math.max(0,Math.min(left,image.getWidth()));right=Math.max(0,Math.min(right,image.getWidth()-left));top=Math.max(0,Math.min(top,image.getHeight()));bottom=Math.max(0,Math.min(bottom,image.getHeight()-top));int[] sx={0,left,image.getWidth()-right,image.getWidth()},sy={0,top,image.getHeight()-bottom,image.getHeight()},dx={r.x,r.x+left,r.x+r.width-right,r.x+r.width},dy={r.y,r.y+top,r.y+r.height-bottom,r.y+r.height};for(int ix=0;ix<3;ix++)for(int iy=0;iy<3;iy++)if(sx[ix+1]>sx[ix]&&sy[iy+1]>sy[iy]&&dx[ix+1]>dx[ix]&&dy[iy+1]>dy[iy])g.drawImage(image,dx[ix],dy[iy],dx[ix+1],dy[iy+1],sx[ix],sy[iy],sx[ix+1],sy[iy+1],null);}
        private void paintLoadingBar(Graphics2D g,LuaGuiDocument.Widget w,Rectangle r,BufferedImage image){String method=w.type.equals("ProgressTimer")?"ProgressTimer_setPercentage":"LoadingBar_setPercent";double percent=Math.max(0,Math.min(100,w.callNumber(method,0,100)))/100d;int direction=w.argInt(5,0);Shape clip=g.getClip();boolean reverse=w.callBoolean("ProgressTimer_setReverseDirection",0,false)||direction==1;if(reverse)g.clipRect(r.x+(int)(r.width*(1-percent)),r.y,(int)(r.width*percent),r.height);else g.clipRect(r.x,r.y,(int)(r.width*percent),r.height);g.drawImage(image,r.x,r.y,r.width,r.height,null);g.setClip(clip);}
        private void paintSlider(Graphics2D g,LuaGuiDocument.Widget w,Rectangle r,BufferedImage background){g.drawImage(background,r.x,r.y,r.width,r.height,null);double percent=Math.max(0,Math.min(100,w.callNumber("Slider_setPercent",0,0)))/100d;BufferedImage progress=loadImage(w.argString(5,""));if(progress!=null){Shape clip=g.getClip();g.clipRect(r.x,r.y,(int)(r.width*percent),r.height);g.drawImage(progress,r.x,r.y,r.width,r.height,null);g.setClip(clip);}BufferedImage ball=loadImage(w.argString(6,""));if(ball!=null){int x=r.x+(int)Math.round(r.width*percent)-ball.getWidth()/2,y=r.y+(r.height-ball.getHeight())/2;g.drawImage(ball,x,y,null);}}
        private void paintTextAtlas(Graphics2D g,LuaGuiDocument.Widget w,Rectangle r,BufferedImage atlas){
            if(atlas==null)return;String text=w.text(),start=w.argString(8,"0");if(text.isEmpty()||start.isEmpty())return;
            int cw=Math.max(1,w.argInt(6,atlas.getWidth()/10)),ch=Math.max(1,w.argInt(7,atlas.getHeight())),space=(int)w.callNumber("TextAtlas_setWordSpace",0,0),startCode=start.codePointAt(0),count=text.codePointCount(0,text.length());
            int logicalWidth=Math.max(1,cw*count+space*Math.max(0,count-1)),atlasColumns=atlas.getWidth()/cw,pen=0;
            for(int offset=0;offset<text.length();){int code=text.codePointAt(offset),index=code-startCode;int dx1=r.x+(int)Math.round((double)pen*r.width/logicalWidth),dx2=r.x+(int)Math.round((double)(pen+cw)*r.width/logicalWidth);if(index>=0&&index<atlasColumns)g.drawImage(atlas,dx1,r.y,dx2,r.y+r.height,index*cw,0,Math.min(atlas.getWidth(),(index+1)*cw),Math.min(atlas.getHeight(),ch),null);pen+=cw+space;offset+=Character.charCount(code);}
        }
        private void paintRichText(Graphics2D g,LuaGuiDocument.Widget w,Rectangle r,String source){Pattern tag=Pattern.compile("<font\\s+([^>]*)>(.*?)</font>",Pattern.CASE_INSENSITIVE);Matcher matcher=tag.matcher(source);int[] pen={r.x,r.y+Math.max(8,w.fontSize()),Math.max(12,w.fontSize()+4)};int cursor=0;while(matcher.find()){if(matcher.start()>cursor)drawRichFragment(g,source.substring(cursor,matcher.start()),r,pen,w.fontSize(),parseColor(w.textColor(),Color.WHITE));String attrs=matcher.group(1),run=matcher.group(2);Matcher cm=Pattern.compile("color=['\"]([^'\"]+)").matcher(attrs),sm=Pattern.compile("size=['\"](\\d+)").matcher(attrs);Color color=cm.find()?parseColor(cm.group(1),Color.WHITE):parseColor(w.textColor(),Color.WHITE);int size=sm.find()?Integer.parseInt(sm.group(1)):w.fontSize();drawRichFragment(g,run,r,pen,size,color);cursor=matcher.end();}if(cursor<source.length())drawRichFragment(g,source.substring(cursor),r,pen,w.fontSize(),parseColor(w.textColor(),Color.WHITE));}
        private void drawRichFragment(Graphics2D g,String source,Rectangle r,int[] pen,int size,Color color){String normalized=source.replaceAll("(?i)<br\\s*/?>","\n").replaceAll("<[^>]+>","");String[] lines=normalized.split("\\n",-1);for(int line=0;line<lines.length;line++){if(line>0){pen[0]=r.x;pen[1]+=pen[2];pen[2]=Math.max(12,size+4);}g.setFont(getFont().deriveFont((float)Math.max(8,size)));g.setColor(color);for(String token:lines[line].split("(?<=.)",-1)){int width=g.getFontMetrics().stringWidth(token);if(pen[0]+width>r.x+r.width&&pen[0]>r.x){pen[0]=r.x;pen[1]+=pen[2];}g.drawString(token,pen[0],pen[1]);pen[0]+=width;pen[2]=Math.max(pen[2],g.getFontMetrics().getHeight());}}}

        private void paintSelection(Graphics2D g,Rectangle r){
            g.setColor(new Color(220,45,45));g.setStroke(new BasicStroke(1.5f));g.drawRect(r.x,r.y,r.width,r.height);int s=7;int[] xs={r.x,r.x+r.width/2,r.x+r.width};int[] ys={r.y,r.y+r.height/2,r.y+r.height};for(int x:xs)for(int y:ys){g.fillOval(x-s/2,y-s/2,s,s);}
        }

        private boolean isContainer(String type){return Set.of("Layout","ListView","ScrollView","PageView","TableView","Node").contains(type);}
        private Color color(String type){return switch(type){case"Text","RichText","TextInput"->new Color(0xA371F7);case"Button","CheckBox","Slider"->new Color(0x2EA043);case"Image","Frames","SpineAnim","Spine38Anim","Effect"->new Color(0xD29922);case"Layout","ListView","ScrollView","PageView"->new Color(0x3574F0);default->new Color(0x6E7681);};}

        private Dimension widgetSize(LuaGuiDocument.Widget w){
            LuaGuiDocument.Call explicit=w.call("setContentSize");if(explicit!=null)return new Dimension(Math.max(1,(int)w.width()),Math.max(1,(int)w.height()));
            if(Set.of("Layout","ListView","ScrollView","PageView","TableView").contains(w.type))return new Dimension(Math.max(1,(int)w.width()),Math.max(1,(int)w.height()));
            if(w.type.equals("TextAtlas"))return new Dimension(Math.max(1,(int)Math.round(w.width())),Math.max(1,(int)Math.round(w.height())));
            BufferedImage image=widgetImage(w);if(image!=null)return new Dimension(image.getWidth(),image.getHeight());
            if(w.type.equals("ItemShow"))return new Dimension(60,60);if(w.type.equals("EquipShow")||w.type.equals("CostItem"))return new Dimension(64,64);
            if(w.type.equals("Text")||w.type.equals("RichText")){FontMetrics fm=getFontMetrics(getFont().deriveFont((float)Math.max(8,w.fontSize())));return new Dimension(Math.max(8,fm.stringWidth(w.text())),Math.max(8,fm.getHeight()));}
            return new Dimension(Math.max(24,(int)w.width()),Math.max(20,(int)w.height()));
        }

        private Rectangle bounds(LuaGuiDocument.Widget w){
            Dimension d=widgetSize(w);double sx=Math.abs(w.scaleX()),sy=Math.abs(w.scaleY());double[] origin=absoluteOrigin(w);int width=Math.max(1,(int)Math.round(d.width*sx)),height=Math.max(1,(int)Math.round(d.height*sy));double anchorX=w.type.equals("Effect")?.5:w.anchorX(),anchorY=w.type.equals("Effect")?.5:w.anchorY();double left=origin[0]+displayX(w)-anchorX*width,bottom=origin[1]+displayY(w)-anchorY*height;return new Rectangle(PAD+(int)Math.round(left),PAD+SCENE_HEIGHT-(int)Math.round(bottom)-height,width,height);
        }

        private Rectangle effectPaintBounds(LuaGuiDocument.Widget w){
            GuiEffectAtlas.Frame frame=effectFrame(w);if(frame==null)return bounds(w);double[] origin=absoluteOrigin(w);double sx=Math.abs(w.scaleX()),sy=Math.abs(w.scaleY());int width=Math.max(1,(int)Math.round(frame.image().getWidth()*sx)),height=Math.max(1,(int)Math.round(frame.image().getHeight()*sy));int left=PAD+(int)Math.round(origin[0]+displayX(w)+frame.offsetX()*sx);int top=PAD+SCENE_HEIGHT-(int)Math.round(origin[1]+displayY(w))+(int)Math.round(-frame.offsetY()*sy);return new Rectangle(left,top,width,height);
        }

        private double displayX(LuaGuiDocument.Widget widget){return dragPreviewActive&&dragging!=null&&dragging.id.equals(widget.id)?dragPreviewX:widget.x();}
        private double displayY(LuaGuiDocument.Widget widget){return dragPreviewActive&&dragging!=null&&dragging.id.equals(widget.id)?dragPreviewY:widget.y();}

        private double[] absoluteOrigin(LuaGuiDocument.Widget w){
            LuaGuiDocument.Widget parent=w.parentWidget;if(parent==null)return new double[]{0,0};Rectangle pr=bounds(parent);return new double[]{pr.x-PAD,SCENE_HEIGHT-(pr.y-PAD)-pr.height};
        }

        private List<LuaGuiDocument.Widget> renderOrder(){List<LuaGuiDocument.Widget> out=new ArrayList<>();model.widgets.stream().filter(w->w.parentWidget==null).sorted(Comparator.comparingInt(LuaGuiDocument.Widget::zOrder).thenComparingInt(w->w.createLine)).forEach(w->appendRenderOrder(w,out));return out;}
        private void appendRenderOrder(LuaGuiDocument.Widget widget,List<LuaGuiDocument.Widget> out){out.add(widget);widget.children.stream().sorted(Comparator.comparingInt(LuaGuiDocument.Widget::zOrder).thenComparingInt(w->w.createLine)).forEach(child->appendRenderOrder(child,out));}
        private boolean effectiveVisible(LuaGuiDocument.Widget widget){for(LuaGuiDocument.Widget at=widget;at!=null;at=at.parentWidget)if(!at.visible())return false;return true;}
        private double effectiveOpacity(LuaGuiDocument.Widget widget){double opacity=widget.opacity();for(LuaGuiDocument.Widget at=widget.parentWidget;at!=null;at=at.parentWidget)if(at.callBoolean("setChildrenCascadeOpacityEnabled",0,false))opacity*=at.opacity();return Math.max(0,Math.min(1,opacity));}
        private void applyAncestorClip(Graphics2D graphics,LuaGuiDocument.Widget widget){for(LuaGuiDocument.Widget at=widget.parentWidget;at!=null;at=at.parentWidget)if(at.clipsChildren())graphics.clip(bounds(at));}
        private LuaGuiDocument.Widget hit(Point p){double z=zoom.getValue()/100.0;Point q=new Point((int)(p.x/z),(int)(p.y/z));List<LuaGuiDocument.Widget> ws=renderOrder();for(int i=ws.size()-1;i>=0;i--)if(effectiveVisible(ws.get(i))&&bounds(ws.get(i)).contains(q))return ws.get(i);return null;}
    }

    private BufferedImage loadImage(String texture){
        if(texture==null||texture.isBlank())return null;String key=texture.replace('\\','/');Optional<BufferedImage> cached=imageCache.get(key);if(cached!=null)return cached.orElse(null);
        Path candidate=resolveResource(key);try{if(candidate!=null){BufferedImage image=GuiEffectAtlas.readImage(candidate);if(image!=null){imageCache.put(key,Optional.of(image));return image;}}}catch(Exception ignored){}
        imageCache.put(key,Optional.empty());return null;
    }

    private BufferedImage effectImage(LuaGuiDocument.Widget widget){GuiEffectAtlas.Frame frame=effectFrame(widget);return frame==null?null:frame.image();}

    private GuiEffectAtlas.Frame effectFrame(LuaGuiDocument.Widget widget){
        int id=widget.argInt(5,0),type=widget.argInt(4,0),sex=widget.argInt(6,0),action=widget.argInt(7,0),direction=widget.argInt(8,0);String code=String.format(Locale.ROOT,"%04d",id);
        BufferedImage direct=loadImage("FXFrame/sfx_"+code+"/sfx_"+code+"_0000.png");if(direct!=null)return new GuiEffectAtlas.Frame(direct,-direct.getWidth()/2,direct.getHeight()/2);
        String folder,prefix;switch(type){case 1->{folder="npc";prefix="npc";}case 2->{folder="monster";prefix="monster";}case 3->{folder="weapon";prefix="weapon";}case 4->{folder="wings";prefix="wings";}case 5->{folder="hair";prefix="hair";}case 6->{folder="shield";prefix="shield";}default->{folder="effect";prefix="sfx";direction=0;}}
        int effectDirection=direction;boolean sfx=prefix.equals("sfx");List<String> names=new ArrayList<>();if(sfx){names.add(prefix+"_"+code+"_"+effectDirection);names.add(prefix+"_"+code+"_"+effectDirection+"_0");}else{String base=prefix+"_"+code+"_"+sex+"_"+action;names.add(base);names.add(base+"_0");}
        for(String name:names){String relative="anim/"+folder+"/"+name+".plist";Path plist=resolveResource(relative);String cacheKey=relative+"#"+effectDirection;List<GuiEffectAtlas.Frame> frames=effectAtlasCache.get(cacheKey);if(frames==null&&plist!=null){frames=GuiEffectAtlas.read(plist,effectDirection,sfx);effectAtlasCache.put(cacheKey,frames);}if((frames==null||frames.isEmpty()))scheduleEffectDownload(relative,plist,effectDirection,sfx,cacheKey);if(frames!=null&&!frames.isEmpty())return frames.get(0);}
        return null;
    }

    private void scheduleEffectDownload(String plistRelative,Path localPlist,int direction,boolean sfx,String cacheKey){if(localPlist==null||System.currentTimeMillis()<effectDownloadRetryAt.getOrDefault(cacheKey,0L)||!pendingEffectDownloads.add(cacheKey))return;CompletableFuture.supplyAsync(()->{try{String pngRelative=plistRelative.replaceFirst("(?i)\\.plist$",".png");URLConnection connection=URI.create("https://cdnsfy.dhsf.xqhuyu.com/996tools_m2_assets/"+pngRelative).toURL().openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(12000);connection.setUseCaches(true);BufferedImage atlas;try(var input=connection.getInputStream()){atlas=javax.imageio.ImageIO.read(input);}if(atlas==null)return List.<GuiEffectAtlas.Frame>of();return GuiEffectAtlas.read(localPlist,atlas,direction,sfx);}catch(Exception ignored){return List.<GuiEffectAtlas.Frame>of();}}).thenAccept(frames->{pendingEffectDownloads.remove(cacheKey);if(frames.isEmpty())effectDownloadRetryAt.put(cacheKey,System.currentTimeMillis()+60000);else{effectDownloadRetryAt.remove(cacheKey);effectAtlasCache.put(cacheKey,frames);SwingUtilities.invokeLater(canvas::repaint);}});}

    private List<Path> resourceCandidates(String texture){
        String clean=texture.replaceFirst("^[./]+","").replace('/',File.separatorChar);LinkedHashSet<Path> candidates=new LinkedHashSet<>();Path source=Path.of(file.getPath()).toAbsolutePath();
        for(Path p=source.getParent();p!=null;p=p.getParent())if(p.getFileName()!=null&&p.getFileName().toString().equalsIgnoreCase("GUIExport")){Path dev=p.getParent();if(dev!=null){candidates.add(dev.resolve(clean));Path client=dev.getParent();if(client!=null)addClientResourceCandidates(candidates,client,clean);}break;}
        for(String root:GuiEditorSettings.getInstance().getState().guiExportRoots)try{Path p=Path.of(root).toAbsolutePath().normalize();candidates.add(p.resolve(clean));if(p.getParent()!=null)candidates.add(p.getParent().resolve(clean));}catch(Exception ignored){}
        if(project.getBasePath()!=null){Path p=Path.of(project.getBasePath());candidates.add(p.resolve(clean));candidates.add(p.resolve("dev").resolve(clean));}
        return new ArrayList<>(candidates);
    }
    private Path resolveResource(String texture){String key=texture.replace('\\','/');Optional<Path> cached=resourcePathCache.get(key);if(cached!=null)return cached.orElse(null);for(Path candidate:resourceCandidates(key))if(Files.isRegularFile(candidate)){Path found=candidate.toAbsolutePath().normalize();resourcePathCache.put(key,Optional.of(found));return found;}resourcePathCache.put(key,Optional.empty());return null;}
    private static void addClientResourceCandidates(Set<Path> candidates,Path client,String clean){candidates.add(client.resolve("cache/mod_fgcq/stab").resolve(clean));candidates.add(client.resolve("cache/mod_chuanqi3/stab").resolve(clean));candidates.add(client.resolve("mod_fgcq/stab").resolve(clean));Path cache=client.resolve("cache");if(!Files.isDirectory(cache))return;try(var mods=Files.list(cache)){for(Path mod:mods.filter(Files::isDirectory).toList()){candidates.add(mod.resolve("stab").resolve(clean));try(var packages=Files.list(mod)){for(Path pack:packages.filter(Files::isDirectory).toList())candidates.add(pack.resolve(clean));}catch(Exception ignored){}}}catch(Exception ignored){}}

    private static double number(String value,double fallback){try{return Double.parseDouble(value.replaceAll("[^0-9+\\-.]",""));}catch(Exception ignored){return fallback;}}
    private static Color parseColor(String value,Color fallback){try{String s=value.trim().replace("#","");if(s.length()==8)s=s.substring(2);return new Color(Integer.parseInt(s,16));}catch(Exception ignored){return fallback;}}
}
