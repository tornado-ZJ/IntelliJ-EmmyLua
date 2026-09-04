package com.tang.intellij.lua.guieditor;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBPanel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class GuiEditorConfigurable implements Configurable {
    private final DefaultListModel<String> roots = new DefaultListModel<>();
    private final JBList<String> rootList = new JBList<>(roots);
    private final JBCheckBox grid = new JBCheckBox("默认显示网格");
    private final JBTextField gridSize = new JBTextField();
    private final DefaultListModel<PropertyChoice> contextChoices = new DefaultListModel<>();
    private final JBList<PropertyChoice> contextList = new JBList<>(contextChoices);
    private final Set<String> selectedContextKeys = new LinkedHashSet<>();
    private JPanel panel;

    @Override public @Nls String getDisplayName() { return "Lua GUI 设计器"; }

    @Override public @Nullable JComponent createComponent() {
        if (panel == null) {
            JPanel paths = ToolbarDecorator.createDecorator(rootList)
                    .setAddAction(button -> chooseRoot())
                    .setRemoveAction(button -> { for (String value : rootList.getSelectedValuesList()) roots.removeElement(value); })
                    .disableUpDownActions().createPanel();
            paths.setPreferredSize(new Dimension(620, 230));
            for(GuiPropertySchema.Field field:GuiPropertySchema.contextMenuCandidates())contextChoices.addElement(new PropertyChoice(field.key(),field.section()+" · "+field.label()));
            contextList.setCellRenderer(new ContextChoiceRenderer());contextList.setFixedCellHeight(26);
            contextList.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){int index=contextList.locationToIndex(e.getPoint());if(index>=0&&contextList.getCellBounds(index,index).contains(e.getPoint()))toggleContextChoice(index);}});
            contextList.getInputMap().put(KeyStroke.getKeyStroke("SPACE"),"toggleContextProperty");contextList.getActionMap().put("toggleContextProperty",new AbstractAction(){@Override public void actionPerformed(java.awt.event.ActionEvent e){toggleContextChoice(contextList.getSelectedIndex());}});
            JScrollPane contextScroll=new JScrollPane(contextList);contextScroll.setPreferredSize(new Dimension(620,260));
            panel = FormBuilder.createFormBuilder()
                    .addLabeledComponent(new JBLabel("GUIExport 路径（可配置多个）"), paths, 1, true)
                    .addComponent(grid)
                    .addLabeledComponent("网格尺寸", gridSize)
                    .addLabeledComponent(new JBLabel("控件右键菜单属性"),contextScroll,1,true)
                    .addComponentFillVertically(new JBPanel<>(), 0).getPanel();
        }
        reset();
        return panel;
    }

    private void chooseRoot() {
        VirtualFile selected = FileChooser.chooseFile(
                FileChooserDescriptorFactory.createSingleFolderDescriptor(), null,
                rootList.getSelectedValue() == null ? null : LocalFileSystem.getInstance().findFileByPath(rootList.getSelectedValue()));
        if (selected != null && !roots.contains(selected.getPath())) roots.addElement(selected.getPath());
    }

    @Override public boolean isModified() {
        GuiEditorSettings.State s = GuiEditorSettings.getInstance().getState();
        return !List.copyOf(java.util.Collections.list(roots.elements())).equals(s.guiExportRoots)
                || grid.isSelected() != s.showGrid || parseGrid() != s.gridSize
                || !selectedContextPropertyKeys().equals(s.contextMenuPropertyKeys);
    }

    @Override public void apply() {
        GuiEditorSettings.State s = GuiEditorSettings.getInstance().getState();
        s.guiExportRoots = new java.util.ArrayList<>(java.util.Collections.list(roots.elements()));
        s.showGrid = grid.isSelected();
        s.gridSize = parseGrid();
        s.contextMenuPropertyKeys = new ArrayList<>(selectedContextPropertyKeys());
        s.contextMenuSchemaVersion = 1;
    }

    @Override public void reset() {
        if (panel == null) return;
        GuiEditorSettings.State s = GuiEditorSettings.getInstance().getState();
        roots.clear(); s.guiExportRoots.forEach(roots::addElement);
        grid.setSelected(s.showGrid); gridSize.setText(String.valueOf(s.gridSize));
        selectedContextKeys.clear();selectedContextKeys.addAll(s.contextMenuPropertyKeys);contextList.repaint();
    }

    private int parseGrid() {
        try { return Math.max(2, Math.min(100, Integer.parseInt(gridSize.getText().trim()))); }
        catch (Exception ignored) { return 10; }
    }

    private void toggleContextChoice(int index){if(index<0||index>=contextChoices.size())return;String key=contextChoices.get(index).key();if(!selectedContextKeys.remove(key))selectedContextKeys.add(key);contextList.repaint(contextList.getCellBounds(index,index));}
    private List<String> selectedContextPropertyKeys(){List<String> result=new ArrayList<>();for(int i=0;i<contextChoices.size();i++){String key=contextChoices.get(i).key();if(selectedContextKeys.contains(key))result.add(key);}return result;}
    private record PropertyChoice(String key,String label){@Override public String toString(){return label;}}
    private final class ContextChoiceRenderer extends JBCheckBox implements ListCellRenderer<PropertyChoice>{
        @Override public Component getListCellRendererComponent(JList<? extends PropertyChoice> list,PropertyChoice value,int index,boolean selected,boolean focus){setText(value.label());setSelected(selectedContextKeys.contains(value.key()));setOpaque(true);setBorder(BorderFactory.createEmptyBorder(2,6,2,6));setBackground(selected?list.getSelectionBackground():list.getBackground());setForeground(selected?list.getSelectionForeground():list.getForeground());return this;}
    }
}
