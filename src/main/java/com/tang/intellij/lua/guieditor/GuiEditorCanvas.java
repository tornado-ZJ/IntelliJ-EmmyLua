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

import com.intellij.ide.dnd.DnDEvent;
import com.intellij.ide.dnd.DnDSupport;
import com.intellij.ide.dnd.FileCopyPasteUtil;
import com.intellij.openapi.Disposable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Native Swing canvas used inside the IDEA FileEditor tab. */
public final class GuiEditorCanvas extends JComponent {
    public static final String WIDGET_TRANSFER_PREFIX = "EMMYLUA_WIDGET:";
    private static final int MARGIN = 36;
    private static final int HANDLE_SIZE = 9;

    public interface Listener {
        void selectionChanged(@Nullable GuiLuaDocument.Node node);

        void modelChanged();

        void fileDropped(@NotNull File file);

        void status(@NotNull String message);
    }

    private enum DragMode {
        NONE, MOVE, RESIZE
    }

    private GuiLuaDocument document;
    private GuiResourceResolver resourceResolver;
    private final Listener listener;
    private GuiLuaDocument.Node selected;
    private double zoom = 1.0;
    private boolean showGrid = true;
    private boolean snapToGrid = true;
    private int gridSize = 10;
    private final Map<GuiLuaDocument.Node, LogicalBounds> logicalBounds = new LinkedHashMap<>();
    private final Map<GuiLuaDocument.Node, Rectangle2D.Double> screenBounds = new LinkedHashMap<>();
    private DragMode dragMode = DragMode.NONE;
    private Point dragStart;
    private double startX;
    private double startY;
    private double startWidth;
    private double startHeight;
    private boolean changedDuringDrag;

    public GuiEditorCanvas(@NotNull GuiLuaDocument document,
                           @NotNull GuiResourceResolver resourceResolver,
                           @NotNull Listener listener) {
        this(document, resourceResolver, listener, null);
    }

    public GuiEditorCanvas(@NotNull GuiLuaDocument document,
                           @NotNull GuiResourceResolver resourceResolver,
                           @NotNull Listener listener,
                           @Nullable Disposable disposableParent) {
        this.document = document;
        this.resourceResolver = resourceResolver;
        this.listener = listener;
        setOpaque(true);
        setBackground(new Color(42, 45, 48));
        Font uiFont = UIManager.getFont("Label.font");
        setFont(uiFont != null ? uiFont : new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        setFocusable(true);
        setToolTipText("");
        installMouseHandlers();
        installKeyboardHandlers();
        setTransferHandler(new CanvasTransferHandler());
        installIdeaProjectViewDrop(disposableParent);
        updatePreferredSize();
    }

    /**
     * IntelliJ's Project view uses the platform DnD manager rather than plain Swing file-list flavors.
     * Registering a native target here makes dragging a Project-view Lua file onto the embedded canvas reliable,
     * while the Swing TransferHandler below continues to cover Explorer/Finder and palette drags.
     */
    private void installIdeaProjectViewDrop(@Nullable Disposable disposableParent) {
        try {
            var builder = DnDSupport.createBuilder(this)
                    .disableAsSource()
                    .enableAsNativeTarget()
                    .setTargetChecker(event -> {
                        File lua = firstLuaFile(FileCopyPasteUtil.getFileListFromAttachedObject(event.getAttachedObject()));
                        boolean accepted = lua != null;
                        event.setDropPossible(accepted, accepted ? "在 GUI 设计器中打开" : "只能拖入 .lua 文件");
                        return true;
                    })
                    .setDropHandler(this::handleIdeaDrop);
            if (disposableParent != null) {
                builder.setDisposableParent(disposableParent);
            }
            builder.install();
        } catch (LinkageError | RuntimeException ignored) {
            // Keep the standard Swing TransferHandler working on IDE builds where the optional DnD API differs.
        }
    }

    private void handleIdeaDrop(@NotNull DnDEvent event) {
        File lua = firstLuaFile(FileCopyPasteUtil.getFileListFromAttachedObject(event.getAttachedObject()));
        if (lua != null) {
            listener.fileDropped(lua);
        }
    }

    private static @Nullable File firstLuaFile(@Nullable Collection<? extends File> files) {
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file != null && file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".lua")) {
                return file;
            }
        }
        return null;
    }

    public void setDocument(@NotNull GuiLuaDocument document, @NotNull GuiResourceResolver resolver) {
        this.document = document;
        this.resourceResolver = resolver;
        selected = null;
        logicalBounds.clear();
        screenBounds.clear();
        updatePreferredSize();
        repaint();
    }

    public @NotNull GuiLuaDocument getDocument() {
        return document;
    }

    public @Nullable GuiLuaDocument.Node getSelectedNode() {
        return selected;
    }

    public void setSelectedNode(@Nullable GuiLuaDocument.Node node) {
        if (node != null && node.isDeleted()) {
            node = null;
        }
        if (selected != node) {
            selected = node;
            listener.selectionChanged(node);
            repaint();
        }
    }

    public void setZoom(double zoom) {
        double normalized = Math.max(0.25, Math.min(4.0, zoom));
        if (Math.abs(this.zoom - normalized) > 0.0001) {
            this.zoom = normalized;
            updatePreferredSize();
            repaint();
        }
    }

    public double getZoom() {
        return zoom;
    }

    public void setShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        repaint();
    }

    public boolean isShowGrid() {
        return showGrid;
    }

    public void setSnapToGrid(boolean snapToGrid) {
        this.snapToGrid = snapToGrid;
    }

    public boolean isSnapToGrid() {
        return snapToGrid;
    }

    public void setGridSize(int gridSize) {
        this.gridSize = Math.max(1, Math.min(256, gridSize));
        repaint();
    }

    public void addNodeAtCenter(@NotNull String type) {
        Point point = new Point(getVisibleRect().x + getVisibleRect().width / 2,
                getVisibleRect().y + getVisibleRect().height / 2);
        addNodeAt(type, point);
    }

    public void deleteSelection() {
        if (selected == null) {
            return;
        }
        GuiLuaDocument.Node old = selected;
        GuiLuaDocument.Node next = old.getParent();
        document.removeNode(old);
        selected = next;
        listener.selectionChanged(selected);
        listener.modelChanged();
        repaint();
    }

    public void refreshResources() {
        resourceResolver.clearCache();
        repaint();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        GuiLuaDocument.Node node = hitTest(event.getPoint());
        if (node == null) {
            return null;
        }
        StringBuilder result = new StringBuilder("<html><b>")
                .append(escape(node.getName())).append("</b> · ")
                .append(escape(node.getType()))
                .append("<br>")
                .append(escape(node.getVariable()))
                .append(" · x=").append(format(node.getX()))
                .append(" y=").append(format(node.getY()))
                .append(" · ").append(format(node.getWidth())).append("×").append(format(node.getHeight()));
        if (!node.getImage().isBlank()) {
            result.append("<br>").append(escape(node.getImage()));
        }
        return result.append("</html>").toString();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            paintWorkspace(g);
        } finally {
            g.dispose();
        }
    }

    private void paintWorkspace(Graphics2D g) {
        int canvasPixelWidth = (int) Math.round(document.getCanvasWidth() * zoom);
        int canvasPixelHeight = (int) Math.round(document.getCanvasHeight() * zoom);
        int x0 = MARGIN;
        int y0 = MARGIN;

        g.setColor(new Color(28, 30, 32));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(new Color(18, 18, 18));
        g.fillRoundRect(x0 + 5, y0 + 7, canvasPixelWidth, canvasPixelHeight, 4, 4);
        g.setColor(new Color(66, 69, 73));
        g.fillRect(x0, y0, canvasPixelWidth, canvasPixelHeight);

        if (showGrid) {
            paintGrid(g, x0, y0, canvasPixelWidth, canvasPixelHeight);
        }

        Shape oldClip = g.getClip();
        g.clipRect(x0, y0, canvasPixelWidth, canvasPixelHeight);
        rebuildBounds();
        List<GuiLuaDocument.Node> drawingOrder = flattenForDrawing(document.getRoots());
        for (GuiLuaDocument.Node node : drawingOrder) {
            paintNode(g, node);
        }
        g.setClip(oldClip);

        g.setColor(new Color(148, 151, 156));
        g.drawRect(x0 - 1, y0 - 1, canvasPixelWidth + 1, canvasPixelHeight + 1);
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g.drawString(document.getCanvasWidth() + " × " + document.getCanvasHeight()
                + "   " + Math.round(zoom * 100) + "%", x0, y0 - 10);
    }

    private void paintGrid(Graphics2D g, int x0, int y0, int width, int height) {
        int step = Math.max(2, (int) Math.round(gridSize * zoom));
        int major = step * 5;
        for (int x = 0; x <= width; x += step) {
            g.setColor(x % major == 0 ? new Color(88, 91, 95) : new Color(74, 77, 81));
            g.drawLine(x0 + x, y0, x0 + x, y0 + height);
        }
        for (int y = 0; y <= height; y += step) {
            g.setColor(y % major == 0 ? new Color(88, 91, 95) : new Color(74, 77, 81));
            g.drawLine(x0, y0 + y, x0 + width, y0 + y);
        }
    }

    private void rebuildBounds() {
        logicalBounds.clear();
        screenBounds.clear();
        for (GuiLuaDocument.Node root : document.getRoots()) {
            buildBounds(root, 0.0, 0.0);
        }
    }

    private void buildBounds(GuiLuaDocument.Node node, double parentOriginX, double parentOriginY) {
        BufferedImage naturalImage = loadPreviewImage(node);
        if (naturalImage != null && !node.hasExplicitSize()) {
            node.applyNaturalPreviewSize(naturalImage.getWidth(), naturalImage.getHeight());
        }
        double scaledWidth = Math.max(1.0, node.getWidth() * Math.abs(node.getScaleX()));
        double scaledHeight = Math.max(1.0, node.getHeight() * Math.abs(node.getScaleY()));
        double left = parentOriginX + node.getX() - node.getAnchorX() * scaledWidth;
        double bottom = parentOriginY + node.getY() - node.getAnchorY() * scaledHeight;
        LogicalBounds bounds = new LogicalBounds(left, bottom, scaledWidth, scaledHeight, parentOriginX, parentOriginY);
        logicalBounds.put(node, bounds);
        double sx = MARGIN + left * zoom;
        double sy = MARGIN + (document.getCanvasHeight() - bottom - scaledHeight) * zoom;
        screenBounds.put(node, new Rectangle2D.Double(sx, sy, scaledWidth * zoom, scaledHeight * zoom));
        for (GuiLuaDocument.Node child : node.getChildren()) {
            buildBounds(child, left, bottom);
        }
    }

    private static List<GuiLuaDocument.Node> flattenForDrawing(List<GuiLuaDocument.Node> roots) {
        List<GuiLuaDocument.Node> result = new ArrayList<>();
        for (GuiLuaDocument.Node root : roots) {
            flatten(root, result);
        }
        result.sort(Comparator.comparingInt(GuiLuaDocument.Node::getZOrder)
                .thenComparingInt(GuiLuaDocument.Node::getSourceLine));
        return result;
    }

    private static void flatten(GuiLuaDocument.Node node, List<GuiLuaDocument.Node> result) {
        result.add(node);
        for (GuiLuaDocument.Node child : node.getChildren()) {
            flatten(child, result);
        }
    }

    private void paintNode(Graphics2D g, GuiLuaDocument.Node node) {
        Rectangle2D.Double rect = screenBounds.get(node);
        if (rect == null || rect.width < 0.5 || rect.height < 0.5) {
            return;
        }
        Composite oldComposite = g.getComposite();
        float alpha = (float) Math.max(0.08, Math.min(1.0, node.getOpacity() / 255.0));
        if (!node.isVisible()) {
            alpha *= 0.30f;
        }
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));

        AffineTransform oldTransform = g.getTransform();
        if (Math.abs(node.getRotation()) > 0.001) {
            double anchorScreenX = rect.x + rect.width * node.getAnchorX();
            double anchorScreenY = rect.y + rect.height * (1.0 - node.getAnchorY());
            g.rotate(Math.toRadians(-node.getRotation()), anchorScreenX, anchorScreenY);
        }

        String type = node.getType();
        BufferedImage image = loadPreviewImage(node);
        if (image != null && Set.of("Image", "Button", "LoadingBar", "ProgressTimer", "CheckBox", "Slider",
                "Layout", "ScrollView", "ListView", "PageView", "TableView").contains(type)) {
            drawImage(g, image, rect);
        } else {
            paintFallbackNode(g, node, rect);
        }

        if ("Text".equals(type) || "TextInput".equals(type) || "RichText".equals(type)
                || "ScrollText".equals(type) || "Button".equals(type) || "TextAtlas".equals(type)) {
            paintText(g, node, rect);
        }
        if ("LoadingBar".equals(type) || "ProgressTimer".equals(type) || "Slider".equals(type)) {
            paintProgress(g, node, rect);
        }
        if ("CheckBox".equals(type) && node.isSelected()) {
            paintCheckMark(g, rect);
        }

        g.setTransform(oldTransform);
        g.setComposite(oldComposite);

        if (node == selected) {
            paintSelection(g, rect, node);
        } else if (!node.isVisible()) {
            Stroke oldStroke = g.getStroke();
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
                    new float[]{4f, 4f}, 0f));
            g.setColor(new Color(180, 180, 180, 130));
            g.draw(rect);
            g.setStroke(oldStroke);
        }
    }

    private void paintFallbackNode(Graphics2D g, GuiLuaDocument.Node node, Rectangle2D.Double rect) {
        String type = node.getType();
        Color base = switch (type) {
            case "Layout", "ListView", "ScrollView", "PageView", "TableView" -> parseColor(node.getBackgroundColor(), new Color(61, 92, 116));
            case "Button" -> new Color(64, 113, 164);
            case "Text", "TextInput", "RichText", "ScrollText" -> new Color(72, 72, 72);
            case "CheckBox", "Slider", "LoadingBar", "ProgressTimer" -> new Color(76, 100, 80);
            case "Effect", "FxEffect", "ParticleEffect", "Frames", "SpineAnim", "UIModel" -> new Color(103, 65, 120);
            case "ItemShow", "EquipShow", "CostItem", "ItemBox" -> new Color(123, 91, 45);
            default -> new Color(76, 79, 84);
        };
        int backgroundAlpha = (int) Math.max(50, Math.min(220,
                node.getBackgroundOpacity() > 0 ? node.getBackgroundOpacity() * 2.55 : 145));
        g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), backgroundAlpha));
        g.fill(rect);
        g.setColor(base.brighter());
        g.draw(rect);

        if (!Set.of("Text", "TextInput", "RichText", "ScrollText", "Button").contains(type)) {
            String label = node.getName() + " · " + type;
            Font font = getFont().deriveFont(Font.PLAIN, (float) Math.max(9, Math.min(14, 11 * zoom)));
            g.setFont(font);
            g.setColor(new Color(235, 235, 235, 210));
            FontMetrics fm = g.getFontMetrics();
            int tx = (int) Math.round(rect.x + 5);
            int ty = (int) Math.round(rect.y + Math.min(rect.height - 4, fm.getAscent() + 5));
            Shape oldClip = g.getClip();
            g.clip(rect);
            g.drawString(label, tx, ty);
            g.setClip(oldClip);
        }
    }

    private @Nullable BufferedImage loadPreviewImage(@NotNull GuiLuaDocument.Node node) {
        String path = node.getImage();
        if ((path == null || path.isBlank()) && "Slider".equals(node.getType())) {
            path = node.getSliderBarImage();
        }
        return resourceResolver.load(path);
    }

    private static void drawImage(Graphics2D g, BufferedImage image, Rectangle2D.Double rect) {
        g.drawImage(image,
                (int) Math.round(rect.x), (int) Math.round(rect.y),
                Math.max(1, (int) Math.round(rect.width)), Math.max(1, (int) Math.round(rect.height)), null);
    }

    private void paintText(Graphics2D g, GuiLuaDocument.Node node, Rectangle2D.Double rect) {
        String text = node.getText();
        if (text == null || text.isBlank()) {
            text = "Button".equals(node.getType()) ? node.getName() : node.getType();
        }
        int fontSize = Math.max(8, (int) Math.round(node.getFontSize() * zoom));
        g.setFont(getFont().deriveFont(Font.PLAIN, (float) fontSize));
        FontMetrics fm = g.getFontMetrics();
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        int tx = (int) Math.round(rect.x + Math.max(3, (rect.width - fm.stringWidth(oneLine)) / 2));
        int ty = (int) Math.round(rect.y + Math.max(fm.getAscent() + 2,
                (rect.height - fm.getHeight()) / 2 + fm.getAscent()));
        Shape oldClip = g.getClip();
        g.clip(rect);
        if (node.getOutlineWidth() > 0) {
            g.setColor(parseColor(node.getOutlineColor(), Color.BLACK));
            int d = Math.max(1, (int) Math.round(node.getOutlineWidth() * zoom));
            g.drawString(oneLine, tx - d, ty);
            g.drawString(oneLine, tx + d, ty);
            g.drawString(oneLine, tx, ty - d);
            g.drawString(oneLine, tx, ty + d);
        }
        g.setColor(parseColor(node.getColor(), Color.WHITE));
        g.drawString(oneLine, tx, ty);
        g.setClip(oldClip);
    }

    private static void paintProgress(Graphics2D g, GuiLuaDocument.Node node, Rectangle2D.Double rect) {
        double ratio = Math.max(0.0, Math.min(1.0, node.getPercent() / 100.0));
        g.setColor(new Color(25, 25, 25, 150));
        g.fill(rect);
        g.setColor(parseColor(node.getColor(), new Color(78, 181, 96)));
        g.fill(new Rectangle2D.Double(rect.x, rect.y, rect.width * ratio, rect.height));
        g.setColor(new Color(235, 235, 235, 160));
        g.draw(rect);
    }

    private static void paintCheckMark(Graphics2D g, Rectangle2D.Double rect) {
        g.setColor(new Color(85, 220, 110));
        g.setStroke(new BasicStroke(3f));
        int x1 = (int) Math.round(rect.x + rect.width * 0.20);
        int y1 = (int) Math.round(rect.y + rect.height * 0.52);
        int x2 = (int) Math.round(rect.x + rect.width * 0.43);
        int y2 = (int) Math.round(rect.y + rect.height * 0.76);
        int x3 = (int) Math.round(rect.x + rect.width * 0.82);
        int y3 = (int) Math.round(rect.y + rect.height * 0.23);
        g.drawLine(x1, y1, x2, y2);
        g.drawLine(x2, y2, x3, y3);
    }

    private void paintSelection(Graphics2D g, Rectangle2D.Double rect, GuiLuaDocument.Node node) {
        Stroke old = g.getStroke();
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(new Color(255, 196, 72));
        g.draw(rect);
        Rectangle handle = resizeHandle(rect);
        g.fill(handle);
        g.setColor(new Color(50, 50, 50));
        g.draw(handle);
        g.setStroke(old);

        String label = node.getVariable() + "  " + format(node.getX()) + ", " + format(node.getY())
                + "  " + format(node.getWidth()) + "×" + format(node.getHeight());
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        FontMetrics fm = g.getFontMetrics();
        int labelWidth = fm.stringWidth(label) + 10;
        int labelX = (int) Math.round(rect.x);
        int labelY = Math.max(MARGIN, (int) Math.round(rect.y) - fm.getHeight() - 2);
        g.setColor(new Color(32, 32, 32, 220));
        g.fillRoundRect(labelX, labelY, labelWidth, fm.getHeight() + 2, 4, 4);
        g.setColor(new Color(255, 211, 112));
        g.drawString(label, labelX + 5, labelY + fm.getAscent() + 1);
    }

    private static Rectangle resizeHandle(Rectangle2D.Double rect) {
        return new Rectangle((int) Math.round(rect.x + rect.width - HANDLE_SIZE / 2.0),
                (int) Math.round(rect.y - HANDLE_SIZE / 2.0), HANDLE_SIZE, HANDLE_SIZE);
    }

    private void installMouseHandlers() {
        MouseAdapter adapter = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                GuiLuaDocument.Node hit = hitTest(event.getPoint());
                setSelectedNode(hit);
                dragStart = event.getPoint();
                changedDuringDrag = false;
                if (selected != null) {
                    Rectangle2D.Double rect = screenBounds.get(selected);
                    dragMode = rect != null && resizeHandle(rect).contains(event.getPoint())
                            ? DragMode.RESIZE : DragMode.MOVE;
                    startX = selected.getX();
                    startY = selected.getY();
                    LogicalBounds selectedBounds = logicalBounds.get(selected);
                    startWidth = selectedBounds == null ? selected.getWidth()
                            : selectedBounds.width / Math.max(0.0001, Math.abs(selected.getScaleX()));
                    startHeight = selectedBounds == null ? selected.getHeight()
                            : selectedBounds.height / Math.max(0.0001, Math.abs(selected.getScaleY()));
                } else {
                    dragMode = DragMode.NONE;
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (selected == null || dragMode == DragMode.NONE || dragStart == null) {
                    return;
                }
                double dx = (event.getX() - dragStart.x) / zoom;
                double dy = (event.getY() - dragStart.y) / zoom;
                if (dragMode == DragMode.MOVE) {
                    double x = snap(startX + dx);
                    double y = snap(startY - dy);
                    document.moveNode(selected, x, y);
                } else {
                    double width = snapSize(startWidth + dx);
                    double height = snapSize(startHeight - dy);
                    document.resizeNode(selected, width, height);
                }
                changedDuringDrag = true;
                repaint();
                listener.selectionChanged(selected);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (changedDuringDrag) {
                    listener.modelChanged();
                }
                dragMode = DragMode.NONE;
                dragStart = null;
                changedDuringDrag = false;
                setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                if (selected != null) {
                    Rectangle2D.Double rect = screenBounds.get(selected);
                    if (rect != null && resizeHandle(rect).contains(event.getPoint())) {
                        setCursor(Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR));
                        return;
                    }
                }
                setCursor(hitTest(event.getPoint()) == null
                        ? Cursor.getDefaultCursor()
                        : Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                if ((event.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) == 0) {
                    return;
                }
                event.consume();
                double factor = event.getWheelRotation() < 0 ? 1.10 : 1.0 / 1.10;
                setZoom(zoom * factor);
            }
        };
        addMouseListener(adapter);
        addMouseMotionListener(adapter);
        addMouseWheelListener(adapter);
    }

    private void installKeyboardHandlers() {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "delete-node");
        getActionMap().put("delete-node", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                deleteSelection();
            }
        });
        bindMove(KeyEvent.VK_LEFT, -1, 0);
        bindMove(KeyEvent.VK_RIGHT, 1, 0);
        bindMove(KeyEvent.VK_UP, 0, 1);
        bindMove(KeyEvent.VK_DOWN, 0, -1);
    }

    private void bindMove(int keyCode, int dx, int dy) {
        String name = "move-" + keyCode;
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (selected == null) {
                    return;
                }
                double step = snapToGrid ? gridSize : 1.0;
                document.moveNode(selected, selected.getX() + dx * step, selected.getY() + dy * step);
                listener.selectionChanged(selected);
                listener.modelChanged();
                repaint();
            }
        });
    }

    private @Nullable GuiLuaDocument.Node hitTest(Point point) {
        if (screenBounds.isEmpty()) {
            rebuildBounds();
        }
        List<Map.Entry<GuiLuaDocument.Node, Rectangle2D.Double>> entries = new ArrayList<>(screenBounds.entrySet());
        entries.sort((left, right) -> {
            int z = Integer.compare(right.getKey().getZOrder(), left.getKey().getZOrder());
            if (z != 0) {
                return z;
            }
            int depth = Integer.compare(depth(right.getKey()), depth(left.getKey()));
            if (depth != 0) {
                return depth;
            }
            return Integer.compare(right.getKey().getSourceLine(), left.getKey().getSourceLine());
        });
        if (selected != null) {
            Rectangle2D.Double selectedRect = screenBounds.get(selected);
            if (selectedRect != null && resizeHandle(selectedRect).contains(point)) {
                return selected;
            }
        }
        for (Map.Entry<GuiLuaDocument.Node, Rectangle2D.Double> entry : entries) {
            if (entry.getValue().contains(point)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private static int depth(GuiLuaDocument.Node node) {
        int depth = 0;
        GuiLuaDocument.Node cursor = node.getParent();
        while (cursor != null && depth < 1024) {
            depth++;
            cursor = cursor.getParent();
        }
        return depth;
    }

    private void addNodeAt(String type, Point screenPoint) {
        rebuildBounds();
        GuiLuaDocument.Node parent = selected;
        if (parent != null && !isContainer(parent.getType())) {
            parent = parent.getParent();
        }
        LogicalBounds parentBounds = parent == null ? null : logicalBounds.get(parent);
        double parentOriginX = parentBounds == null ? 0.0 : parentBounds.left;
        double parentOriginY = parentBounds == null ? 0.0 : parentBounds.bottom;
        double logicalX = (screenPoint.x - MARGIN) / zoom;
        double logicalY = document.getCanvasHeight() - (screenPoint.y - MARGIN) / zoom;
        double localX = snap(logicalX - parentOriginX);
        double localY = snap(logicalY - parentOriginY);
        GuiLuaDocument.Node added = document.addNode(type, parent, localX, localY);
        setSelectedNode(added);
        listener.modelChanged();
        repaint();
        listener.status("已添加 " + added.getVariable() + "（" + added.getType() + "）");
    }

    private static boolean isContainer(String type) {
        return Set.of("Layout", "Node", "ListView", "ScrollView", "PageView", "TableView").contains(type);
    }

    private double snap(double value) {
        if (!snapToGrid) {
            return value;
        }
        return Math.rint(value / gridSize) * gridSize;
    }

    private double snapSize(double value) {
        double result = snapToGrid ? Math.rint(value / gridSize) * gridSize : value;
        return Math.max(1.0, result);
    }

    private void updatePreferredSize() {
        int width = (int) Math.ceil(document.getCanvasWidth() * zoom) + MARGIN * 2;
        int height = (int) Math.ceil(document.getCanvasHeight() * zoom) + MARGIN * 2;
        setPreferredSize(new Dimension(Math.max(480, width), Math.max(360, height)));
        revalidate();
    }

    private final class CanvasTransferHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            if (support.isDataFlavorSupported(DataFlavor.stringFlavor)
                    || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                support.setDropAction(COPY);
                return true;
            }
            return hasIntelliJFileList(support.getTransferable());
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            Point dropPoint = support.getDropLocation() instanceof DropLocation location
                    ? location.getDropPoint() : new Point(getWidth() / 2, getHeight() / 2);
            Transferable transferable = support.getTransferable();
            try {
                if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    String value = String.valueOf(transferable.getTransferData(DataFlavor.stringFlavor)).trim();
                    if (value.startsWith(WIDGET_TRANSFER_PREFIX)) {
                        addNodeAt(value.substring(WIDGET_TRANSFER_PREFIX.length()), dropPoint);
                        return true;
                    }
                    File file = parseFileFromText(value);
                    if (file != null) {
                        listener.fileDropped(file);
                        return true;
                    }
                }
                List<File> files = extractFiles(transferable);
                if (!files.isEmpty()) {
                    for (File file : files) {
                        if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".lua")) {
                            listener.fileDropped(file);
                            return true;
                        }
                    }
                }
            } catch (Exception exception) {
                listener.status("拖拽失败：" + Objects.toString(exception.getMessage(), exception.getClass().getSimpleName()));
            }
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<File> extractFiles(Transferable transferable) {
        try {
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                Object value = transferable.getTransferData(DataFlavor.javaFileListFlavor);
                if (value instanceof List<?> list) {
                    List<File> result = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof File file) {
                            result.add(file);
                        } else if (item instanceof Path path) {
                            result.add(path.toFile());
                        }
                    }
                    return result;
                }
            }
        } catch (Exception ignored) {
        }

        // IDEA project-view drag data has changed packages a few times. Reflection keeps this editor binary-compatible.
        for (String className : List.of("com.intellij.ide.dnd.FileCopyPasteUtil", "com.intellij.ide.util.FileCopyPasteUtil")) {
            try {
                Class<?> utility = Class.forName(className);
                Object value = utility.getMethod("getFileList", Transferable.class).invoke(null, transferable);
                if (value instanceof List<?> list) {
                    List<File> result = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof File file) {
                            result.add(file);
                        }
                    }
                    return result;
                }
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return List.of();
    }

    private static boolean hasIntelliJFileList(Transferable transferable) {
        return !extractFiles(transferable).isEmpty();
    }

    private static @Nullable File parseFileFromText(String value) {
        String first = value.lines().map(String::trim).filter(line -> !line.isEmpty()).findFirst().orElse("");
        if (first.isEmpty()) {
            return null;
        }
        try {
            if (first.startsWith("file:")) {
                return Path.of(URI.create(first)).toFile();
            }
            if ((first.startsWith("\"") && first.endsWith("\""))
                    || (first.startsWith("'") && first.endsWith("'"))) {
                first = first.substring(1, first.length() - 1);
            }
            File file = new File(first);
            return file.exists() ? file : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Color parseColor(String value, Color fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        if (text.startsWith("#")) {
            text = text.substring(1);
        }
        try {
            if (text.length() == 6) {
                return new Color(Integer.parseInt(text, 16));
            }
            if (text.length() == 8) {
                long rgba = Long.parseLong(text, 16);
                return new Color((int) ((rgba >> 24) & 0xff), (int) ((rgba >> 16) & 0xff),
                        (int) ((rgba >> 8) & 0xff), (int) (rgba & 0xff));
            }
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.0001) {
            return Long.toString(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private record LogicalBounds(double left, double bottom, double width, double height,
                                 double parentOriginX, double parentOriginY) {
    }
}
