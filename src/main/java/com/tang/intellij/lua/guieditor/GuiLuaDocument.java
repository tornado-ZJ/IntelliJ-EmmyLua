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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A loss-minimising parser/editor for the GUIExport Lua format used by the visual editor.
 *
 * <p>The parser recognises widget creation statements and the common {@code GUI:*} setter calls, while preserving
 * every unrecognised line verbatim. Saving patches only recognised statements and inserts setters for properties that
 * were changed in the designer. This is deliberately not a general Lua parser; EmmyLua continues to own the real PSI
 * and syntax model.</p>
 */
public final class GuiLuaDocument {
    private static final Pattern CREATE_PATTERN = Pattern.compile(
            "(?s)^\\s*(?:local\\s+)?([\\p{L}_][\\p{L}\\p{N}_]*)\\s*=\\s*GUI:([A-Za-z0-9_]+)\\s*\\((.*)\\)\\s*;?\\s*$");
    private static final Pattern CALL_PATTERN = Pattern.compile(
            "(?s)^\\s*GUI:([A-Za-z0-9_]+)\\s*\\((.*)\\)\\s*;?\\s*$");
    private static final Pattern CREATE_COMMENT_PATTERN = Pattern.compile("^\\s*--\\s*Create\\s+(.+?)\\s*$");
    private static final Pattern SCREEN_WIDTH_PATTERN = Pattern.compile(
            "(?:_V|SL:GetMetaValue)\\s*\\(\\s*[\"']SCREEN_WIDTH[\"']\\s*\\)");
    private static final Pattern SCREEN_HEIGHT_PATTERN = Pattern.compile(
            "(?:_V|SL:GetMetaValue)\\s*\\(\\s*[\"']SCREEN_HEIGHT[\"']\\s*\\)");

    public static final List<String> PALETTE_TYPES = List.of(
            "Layout", "Node", "Image", "Button", "Text", "TextInput", "RichText",
            "LoadingBar", "ProgressTimer", "CheckBox", "Slider", "ListView", "ScrollView",
            "PageView", "TableView", "ItemShow", "EquipShow", "CostItem", "ItemBox",
            "Effect", "FxEffect", "ParticleEffect", "Frames", "SpineAnim", "UIModel", "RedDot"
    );

    private final String lineSeparator;
    private final boolean endedWithLineBreak;
    private final List<String> originalLines;
    private final int canvasWidth;
    private final int canvasHeight;
    private final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final Set<Node> addedNodes = new LinkedHashSet<>();
    private final Set<Node> deletedNodes = new LinkedHashSet<>();
    private String defaultIndent = "\t";
    private int insertionLine = -1;
    private long version;

    private GuiLuaDocument(String source, int canvasWidth, int canvasHeight) {
        this.lineSeparator = source.contains("\r\n") ? "\r\n" : "\n";
        this.endedWithLineBreak = source.endsWith("\n") || source.endsWith("\r");
        this.originalLines = new ArrayList<>(Arrays.asList(source.split("\\R", -1)));
        if (endedWithLineBreak && !originalLines.isEmpty() && originalLines.get(originalLines.size() - 1).isEmpty()) {
            originalLines.remove(originalLines.size() - 1);
        }
        this.canvasWidth = canvasWidth;
        this.canvasHeight = canvasHeight;
        parse();
    }

    public static @NotNull GuiLuaDocument parse(@NotNull String source, int canvasWidth, int canvasHeight) {
        return new GuiLuaDocument(source, canvasWidth, canvasHeight);
    }

    public @NotNull Collection<Node> getNodes() {
        List<Node> result = new ArrayList<>();
        for (Node node : nodes.values()) {
            if (!node.deleted) {
                result.add(node);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public @NotNull List<Node> getRoots() {
        List<Node> roots = new ArrayList<>();
        for (Node node : getNodes()) {
            if (node.parent == null || node.parent.deleted) {
                roots.add(node);
            }
        }
        roots.sort(Node.Z_ORDER_COMPARATOR);
        return roots;
    }

    public @Nullable Node findNode(String variable) {
        Node node = nodes.get(variable);
        return node != null && !node.deleted ? node : null;
    }

    public @NotNull List<Diagnostic> getDiagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    public long getVersion() {
        return version;
    }

    public int getCanvasWidth() {
        return canvasWidth;
    }

    public int getCanvasHeight() {
        return canvasHeight;
    }

    public boolean hasRecognisedWidgets() {
        return !nodes.isEmpty();
    }

    public @NotNull Node addNode(@NotNull String requestedType,
                                 @Nullable Node parent,
                                 double x,
                                 double y) {
        String type = normalizeType(requestedType);
        String base = safeIdentifier(type);
        int suffix = 1;
        String variable;
        do {
            variable = base + "_" + suffix++;
        } while (nodes.containsKey(variable));

        Node node = Node.newNode(this, variable, type, parent == null ? "parent" : parent.variable);
        double[] size = defaultSize(type);
        node.width = size[0];
        node.height = size[1];
        node.anchorX = defaultAnchor(type);
        node.anchorY = defaultAnchor(type);
        node.x = x;
        node.y = y;
        node.parent = parent;
        if (parent != null) {
            parent.children.add(node);
        }
        nodes.put(variable, node);
        addedNodes.add(node);
        version++;
        return node;
    }

    public void removeNode(@NotNull Node node) {
        if (node.deleted) {
            return;
        }
        for (Node child : new ArrayList<>(node.children)) {
            removeNode(child);
        }
        node.deleted = true;
        deletedNodes.add(node);
        if (node.parent != null) {
            node.parent.children.remove(node);
        }
        version++;
    }

    public void moveNode(@NotNull Node node, double x, double y) {
        node.setNumber("x", x);
        node.setNumber("y", y);
    }

    public void resizeNode(@NotNull Node node, double width, double height) {
        node.setNumber("width", Math.max(1.0, width));
        node.setNumber("height", Math.max(1.0, height));
    }

    public @NotNull String serialize() {
        Map<Integer, LinePatch> patches = new HashMap<>();
        Map<Integer, List<String>> insertAfter = new HashMap<>();

        for (Node node : nodes.values()) {
            if (node.deleted) {
                registerDeletion(node, patches);
                continue;
            }
            if (node.added) {
                continue;
            }
            boolean createDirty = node.createRef != null
                    && node.dirtyKeys.stream().anyMatch(node::isRepresentedInCreate);
            if (createDirty) {
                patches.put(node.createRef.startLine,
                        new LinePatch(node.createRef.endLine, List.of(node.renderCreateStatement())));
            }

            int lastLine = node.createRef == null ? -1 : node.createRef.endLine;
            Set<String> represented = new HashSet<>();
            for (Map.Entry<String, List<StatementRef>> entry : node.setterRefs.entrySet()) {
                String key = entry.getKey();
                for (StatementRef ref : entry.getValue()) {
                    if (node.dirtyKeys.contains(key)) {
                        String rendered = node.renderSetter(key, ref.functionName);
                        if (rendered != null) {
                            patches.put(ref.startLine, new LinePatch(ref.endLine, List.of(rendered)));
                            represented.add(key);
                        }
                    }
                    lastLine = Math.max(lastLine, ref.endLine);
                }
            }

            List<String> missing = new ArrayList<>();
            for (String key : node.dirtyKeys) {
                if (represented.contains(key) || node.isRepresentedInCreate(key)) {
                    continue;
                }
                String setter = node.renderSetter(key, null);
                if (setter != null) {
                    missing.add(setter);
                }
            }
            if (!missing.isEmpty()) {
                int insertLine = Math.max(0, lastLine);
                insertAfter.computeIfAbsent(insertLine, ignored -> new ArrayList<>()).addAll(missing);
            }
        }

        List<String> result = new ArrayList<>();
        int line = 0;
        while (line < originalLines.size()) {
            LinePatch patch = patches.get(line);
            int effectiveEnd = line;
            if (patch != null) {
                result.addAll(patch.lines);
                effectiveEnd = patch.endLine;
            } else {
                result.add(originalLines.get(line));
            }
            List<String> additions = insertAfter.get(effectiveEnd);
            if (additions != null) {
                result.addAll(additions);
            }
            line = effectiveEnd + 1;
        }

        if (!addedNodes.isEmpty()) {
            List<String> blocks = new ArrayList<>();
            for (Node node : addedNodes) {
                if (!node.deleted) {
                    if (!blocks.isEmpty()) {
                        blocks.add("");
                    }
                    blocks.addAll(node.renderNewBlock());
                }
            }
            int index = resolveInsertionIndex(result);
            if (index > 0 && !result.get(index - 1).isBlank()) {
                blocks.add(0, "");
            }
            result.addAll(index, blocks);
        }

        String joined = String.join(lineSeparator, result);
        if (endedWithLineBreak || !joined.isEmpty()) {
            joined += lineSeparator;
        }
        return joined;
    }

    private void registerDeletion(Node node, Map<Integer, LinePatch> patches) {
        if (node.createRef == null) {
            return;
        }
        int start = node.createRef.startLine;
        int end = node.createRef.endLine;
        if (start > 0 && CREATE_COMMENT_PATTERN.matcher(originalLines.get(start - 1)).matches()) {
            start--;
        }
        for (List<StatementRef> setters : node.setterRefs.values()) {
            for (StatementRef setter : setters) {
                start = Math.min(start, setter.startLine);
                end = Math.max(end, setter.endLine);
            }
        }
        // Calls that target this widget but use a tool-specific/unknown GUI setter belong to
        // the widget block as well. Remove them with the widget, while leaving arbitrary Lua
        // statements (callbacks, local variables, conditions) untouched.
        for (StatementRef call : node.unknownCalls) {
            start = Math.min(start, call.startLine);
            end = Math.max(end, call.endLine);
        }
        while (end + 1 < originalLines.size() && originalLines.get(end + 1).isBlank()) {
            end++;
            break;
        }
        patches.put(start, new LinePatch(end, Collections.emptyList()));
    }

    private int resolveInsertionIndex(List<String> currentLines) {
        if (insertionLine >= 0) {
            int translated = Math.min(insertionLine, currentLines.size());
            return Math.max(0, translated);
        }
        for (int i = 0; i < currentLines.size(); i++) {
            String text = currentLines.get(i).trim();
            if (text.startsWith("ui.update(") || text.matches("return\\s+[\\p{L}_][\\p{L}\\p{N}_]*")) {
                return i;
            }
        }
        return currentLines.size();
    }

    private void parse() {
        List<StatementRef> statements = collectStatements(originalLines);
        int indentVotesTabs = 0;
        int indentVotesSpaces = 0;

        for (StatementRef statement : statements) {
            String trimmed = statement.text.trim();
            if (trimmed.startsWith("ui.update(") && insertionLine < 0) {
                insertionLine = statement.startLine;
            }

            Matcher create = CREATE_PATTERN.matcher(statement.text);
            if (create.matches()) {
                String variable = create.group(1);
                String function = create.group(2);
                List<String> args = splitArguments(create.group(3));
                if (!isCreationFunction(function) || args.size() < 2) {
                    continue;
                }
                if (nodes.containsKey(variable)) {
                    diagnostics.add(new Diagnostic(Severity.WARNING, statement.startLine + 1,
                            "变量 " + variable + " 有多个 GUI 创建语句，只使用第一处"));
                    continue;
                }
                String indent = leadingWhitespace(originalLines.get(statement.startLine));
                if (indent.contains("\t")) {
                    indentVotesTabs++;
                } else if (indent.length() >= 2) {
                    indentVotesSpaces++;
                }
                Node node = Node.fromCreate(this, variable, function, args, statement, indent);
                nodes.put(variable, node);
                continue;
            }

            Matcher call = CALL_PATTERN.matcher(statement.text);
            if (!call.matches()) {
                continue;
            }
            String function = call.group(1);
            List<String> args = splitArguments(call.group(2));
            if (args.isEmpty()) {
                continue;
            }
            String target = simpleIdentifier(args.get(0));
            Node node = nodes.get(target);
            if (node == null) {
                continue;
            }
            String key = logicalProperty(function);
            if (key == null) {
                node.unknownCalls.add(statement);
                continue;
            }
            node.applySetter(function, args);
            node.setterRefs.computeIfAbsent(key, ignored -> new ArrayList<>()).add(statement.withFunction(function));
        }

        defaultIndent = indentVotesSpaces > indentVotesTabs ? "    " : "\t";
        linkHierarchy();
        if (nodes.isEmpty()) {
            diagnostics.add(new Diagnostic(Severity.INFO, 1,
                    "没有找到 GUI:*_Create 语句；此文件仍可在 Text 页正常编辑"));
        }
    }

    private void linkHierarchy() {
        for (Node node : nodes.values()) {
            node.children.clear();
            node.parent = nodes.get(node.parentVariable);
        }
        for (Node node : nodes.values()) {
            if (node.parent != null && node.parent != node && !wouldCreateCycle(node, node.parent)) {
                node.parent.children.add(node);
            } else if (node.parent == node || wouldCreateCycle(node, node.parent)) {
                diagnostics.add(new Diagnostic(Severity.WARNING,
                        node.createRef == null ? 1 : node.createRef.startLine + 1,
                        "控件 " + node.variable + " 的父级关系形成循环，按根节点显示"));
                node.parent = null;
            }
        }
        for (Node node : nodes.values()) {
            node.children.sort(Node.Z_ORDER_COMPARATOR);
        }
    }

    private static boolean wouldCreateCycle(Node node, @Nullable Node parent) {
        Node cursor = parent;
        int guard = 0;
        while (cursor != null && guard++ < 2048) {
            if (cursor == node) {
                return true;
            }
            cursor = cursor.parent;
        }
        return false;
    }

    private static List<StatementRef> collectStatements(List<String> lines) {
        List<StatementRef> result = new ArrayList<>();
        int line = 0;
        while (line < lines.size()) {
            int start = line;
            StringBuilder text = new StringBuilder(lines.get(line));
            LuaBalance balance = LuaBalance.scan(lines.get(line));
            while (line + 1 < lines.size() && balance.needsMore()) {
                line++;
                text.append('\n').append(lines.get(line));
                balance = LuaBalance.scan(text.toString());
            }
            result.add(new StatementRef(start, line, text.toString(), null));
            line++;
        }
        return result;
    }

    private static boolean isCreationFunction(String function) {
        return function.endsWith("_Create") || "LoadExport".equals(function);
    }

    private static @NotNull String logicalProperty(String function) {
        return switch (function) {
            case "setPosition", "setPositionX", "setPositionY",
                    "setPositionPercentTop", "setPositionPercentBottom",
                    "setPositionPercentLeft", "setPositionPercentRight" -> "position";
            case "setContentSize", "Text_setTextAreaSize", "ScrollView_setInnerContainerSize" -> "size";
            case "setAnchorPoint" -> "anchor";
            case "setScale" -> "scale";
            case "setScaleX" -> "scaleX";
            case "setScaleY" -> "scaleY";
            case "setRotation" -> "rotation";
            case "setOpacity" -> "opacity";
            case "setVisible" -> "visible";
            case "setTouchEnabled" -> "touchEnabled";
            case "setMouseEnabled" -> "mouseEnabled";
            case "setSwallowTouches" -> "swallowTouches";
            case "setTag" -> "tag";
            case "setLocalZOrder" -> "zOrder";
            case "setChineseName" -> "chineseName";
            case "setName" -> "name";
            case "setIgnoreContentAdaptWithSize" -> "ignoreContent";
            case "setChildrenCascadeOpacityEnabled" -> "cascadeOpacity";
            case "Image_loadTexture", "LoadingBar_loadTexture", "ProgressTimer_ChangeImg",
                    "Layout_setBackGroundImage", "ScrollView_setBackGroundImage",
                    "ListView_setBackGroundImage", "PageView_setBackGroundImage",
                    "TableView_setBackGroundImage" -> "image";
            case "Button_loadTextureNormal", "CheckBox_loadTextureBackGround" -> "image";
            case "Button_loadTexturePressed", "CheckBox_loadTextureFrontCross" -> "pressedImage";
            case "Button_loadTextureDisabled" -> "disabledImage";
            case "Button_setTitleText", "Text_setString", "TextInput_setString",
                    "TextInput_setPlaceHolder", "TextAtlas_setString" -> "text";
            case "Button_setTitleColor", "Text_setTextColor", "TextInput_setFontColor",
                    "TextInput_setPlaceholderFontColor", "LoadingBar_setColor" -> "color";
            case "Button_setTitleFontSize", "Text_setFontSize", "TextInput_setFontSize" -> "fontSize";
            case "Button_setTitleFontName", "Text_setFontName" -> "fontPath";
            case "Text_enableOutline", "Button_titleEnableOutline", "ScrollText_enableOutline" -> "outline";
            case "Text_disableOutLine", "Button_titleDisableOutLine" -> "outlineDisabled";
            case "Layout_setBackGroundColor", "ScrollView_setBackGroundColor",
                    "ListView_setBackGroundColor", "PageView_setBackGroundColor",
                    "TableView_setBackGroundColor" -> "backgroundColor";
            case "Layout_setBackGroundColorOpacity", "ScrollView_setBackGroundColorOpacity",
                    "ListView_setBackGroundColorOpacity", "PageView_setBackGroundColorOpacity" -> "backgroundOpacity";
            case "Layout_setClippingEnabled", "ScrollView_setClippingEnabled",
                    "ListView_setClippingEnabled", "PageView_setClippingEnabled" -> "clipping";
            case "LoadingBar_setPercent", "ProgressTimer_setPercentage", "Slider_setPercent" -> "percent";
            case "CheckBox_setSelected" -> "selected";
            case "CheckBox_setGroup" -> "group";
            case "Slider_loadBarTexture" -> "sliderBarImage";
            case "Slider_loadProgressBarTexture" -> "sliderProgressImage";
            case "Slider_loadSlidBallTextureNormal" -> "sliderBallImage";
            default -> null;
        };
    }

    private static @NotNull List<String> splitArguments(@NotNull String input) {
        List<String> result = new ArrayList<>();
        int start = 0;
        int round = 0;
        int curly = 0;
        int square = 0;
        char quote = 0;
        int longEquals = -1;
        boolean escape = false;
        boolean lineComment = false;
        int longCommentEquals = -1;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            char next = i + 1 < input.length() ? input.charAt(i + 1) : '\0';

            if (lineComment) {
                if (c == '\n' || c == '\r') {
                    lineComment = false;
                }
                continue;
            }
            if (longCommentEquals >= 0) {
                int end = matchLongBracketEnd(input, i, longCommentEquals);
                if (end >= 0) {
                    i = end;
                    longCommentEquals = -1;
                }
                continue;
            }
            if (longEquals >= 0) {
                int end = matchLongBracketEnd(input, i, longEquals);
                if (end >= 0) {
                    i = end;
                    longEquals = -1;
                }
                continue;
            }
            if (quote != 0) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }

            if (c == '-' && next == '-') {
                int open = matchLongBracketStart(input, i + 2);
                if (open >= 0) {
                    longCommentEquals = open;
                    i += 2 + open + 1;
                } else {
                    lineComment = true;
                    i++;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '[') {
                int open = matchLongBracketStart(input, i);
                if (open >= 0) {
                    longEquals = open;
                    i += open + 1;
                    continue;
                }
                square++;
                continue;
            }
            if (c == ']') {
                square = Math.max(0, square - 1);
                continue;
            }
            if (c == '(') {
                round++;
            } else if (c == ')') {
                round = Math.max(0, round - 1);
            } else if (c == '{') {
                curly++;
            } else if (c == '}') {
                curly = Math.max(0, curly - 1);
            } else if (c == ',' && round == 0 && curly == 0 && square == 0) {
                result.add(input.substring(start, i).trim());
                start = i + 1;
            }
        }
        String last = input.substring(start).trim();
        if (!last.isEmpty() || !result.isEmpty()) {
            result.add(last);
        }
        return result;
    }

    private static int matchLongBracketStart(String text, int at) {
        if (at < 0 || at >= text.length() || text.charAt(at) != '[') {
            return -1;
        }
        int i = at + 1;
        int equals = 0;
        while (i < text.length() && text.charAt(i) == '=') {
            equals++;
            i++;
        }
        return i < text.length() && text.charAt(i) == '[' ? equals : -1;
    }

    private static int matchLongBracketEnd(String text, int at, int equals) {
        if (at < 0 || at >= text.length() || text.charAt(at) != ']') {
            return -1;
        }
        int i = at + 1;
        for (int count = 0; count < equals; count++, i++) {
            if (i >= text.length() || text.charAt(i) != '=') {
                return -1;
            }
        }
        return i < text.length() && text.charAt(i) == ']' ? i : -1;
    }

    private static @Nullable String simpleIdentifier(String expression) {
        String value = expression.trim();
        return value.matches("[\\p{L}_][\\p{L}\\p{N}_]*") ? value : null;
    }

    private static @NotNull String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    private static @NotNull String normalizeType(String type) {
        String value = type.trim();
        if (value.endsWith("_Create")) {
            value = value.substring(0, value.length() - "_Create".length());
        }
        return value.isEmpty() ? "Node" : value;
    }

    private static @NotNull String safeIdentifier(String value) {
        String result = value.replaceAll("[^\\p{L}\\p{N}_]", "_");
        if (result.isEmpty() || Character.isDigit(result.charAt(0))) {
            result = "Node_" + result;
        }
        return result;
    }

    private static double[] defaultSize(String type) {
        return switch (type) {
            case "Text" -> new double[]{160, 28};
            case "TextInput" -> new double[]{220, 42};
            case "RichText", "ScrollText" -> new double[]{260, 90};
            case "Image" -> new double[]{120, 120};
            case "Button" -> new double[]{150, 48};
            case "CheckBox" -> new double[]{32, 32};
            case "LoadingBar", "ProgressTimer", "Slider" -> new double[]{220, 28};
            case "Layout", "ListView", "ScrollView", "PageView", "TableView" -> new double[]{320, 220};
            case "ItemShow", "EquipShow", "CostItem", "ItemBox" -> new double[]{64, 64};
            case "Effect", "FxEffect", "ParticleEffect", "Frames", "SpineAnim", "Spine38Anim", "UIModel" -> new double[]{120, 120};
            case "RedDot" -> new double[]{24, 24};
            default -> new double[]{120, 80};
        };
    }

    private static double defaultAnchor(String type) {
        return switch (type) {
            case "Text", "Image", "Button", "CheckBox", "LoadingBar", "ProgressTimer", "Slider",
                    "ItemShow", "EquipShow", "CostItem", "ItemBox", "Effect", "FxEffect",
                    "ParticleEffect", "Frames", "SpineAnim", "Spine38Anim", "UIModel", "RedDot" -> 0.5;
            default -> 0.0;
        };
    }

    private static @NotNull String luaString(String value) {
        if (value.contains("\n") || value.contains("\r") || value.contains("]]" ) || value.contains("\"") || value.contains("'")) {
            int equals = 0;
            while (value.contains("]" + "=".repeat(equals) + "]")) {
                equals++;
            }
            return "[" + "=".repeat(equals) + "[" + value + "]" + "=".repeat(equals) + "]";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static @NotNull String decodeLuaString(String expression) {
        String value = expression.trim();
        if (value.length() >= 2 && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
            String inner = value.substring(1, value.length() - 1);
            StringBuilder out = new StringBuilder(inner.length());
            boolean escape = false;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (!escape && c == '\\') {
                    escape = true;
                    continue;
                }
                if (escape) {
                    out.append(switch (c) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        default -> c;
                    });
                    escape = false;
                } else {
                    out.append(c);
                }
            }
            if (escape) {
                out.append('\\');
            }
            return out.toString();
        }
        int equals = matchLongBracketStart(value, 0);
        if (equals >= 0) {
            String close = "]" + "=".repeat(equals) + "]";
            int contentStart = equals + 2;
            if (value.endsWith(close) && value.length() >= contentStart + close.length()) {
                return value.substring(contentStart, value.length() - close.length());
            }
        }
        return value;
    }

    private static boolean parseBoolean(String expression, boolean fallback) {
        String value = expression.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        return fallback;
    }

    private double parseNumber(String expression, double fallback) {
        if (expression == null || expression.isBlank()) {
            return fallback;
        }
        String value = SCREEN_WIDTH_PATTERN.matcher(expression).replaceAll(Double.toString(canvasWidth));
        value = SCREEN_HEIGHT_PATTERN.matcher(value).replaceAll(Double.toString(canvasHeight));
        try {
            return new NumericExpression(value).parse();
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static @NotNull String formatNumber(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        double rounded = Math.rint(value);
        if (Math.abs(value - rounded) < 0.0000001) {
            return Long.toString((long) rounded);
        }
        String result = String.format(Locale.ROOT, "%.4f", value);
        while (result.contains(".") && (result.endsWith("0") || result.endsWith("."))) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record LinePatch(int endLine, List<String> lines) {
    }

    private record StatementRef(int startLine, int endLine, String text, String functionName) {
        private StatementRef withFunction(String functionName) {
            return new StatementRef(startLine, endLine, text, functionName);
        }
    }

    public enum Severity {
        INFO, WARNING, ERROR
    }

    public record Diagnostic(@NotNull Severity severity, int line, @NotNull String message) {
        @Override
        public String toString() {
            return severity + " · line " + line + " · " + message;
        }
    }

    public static final class Node {
        private static final Comparator<Node> Z_ORDER_COMPARATOR = Comparator
                .comparingInt(Node::getZOrder)
                .thenComparing(node -> node.variable);

        private final GuiLuaDocument owner;
        private final String variable;
        private String name;
        private final String type;
        private String createFunction;
        private String parentVariable;
        private Node parent;
        private final List<Node> children = new ArrayList<>();
        private final List<String> createArgs;
        private final StatementRef createRef;
        private final LinkedHashMap<String, List<StatementRef>> setterRefs = new LinkedHashMap<>();
        private final List<StatementRef> unknownCalls = new ArrayList<>();
        private final Set<String> dirtyKeys = new LinkedHashSet<>();
        private final String indent;
        private final boolean localDeclaration;
        private boolean added;
        private boolean deleted;

        private double x;
        private double y;
        private double width;
        private double height;
        private double anchorX;
        private double anchorY;
        private double scaleX = 1.0;
        private double scaleY = 1.0;
        private double rotation;
        private double opacity = 255.0;
        private int zOrder;
        private int tag;
        private boolean visible = true;
        private boolean touchEnabled;
        private boolean mouseEnabled;
        private boolean swallowTouches;
        private boolean ignoreContent;
        private boolean cascadeOpacity;
        private boolean clipping;
        private boolean selected;
        private double percent;
        private int fontSize = 16;
        private String text = "";
        private String color = "#ffffff";
        private String image = "";
        private String pressedImage = "";
        private String disabledImage = "";
        private String sliderBarImage = "";
        private String sliderProgressImage = "";
        private String sliderBallImage = "";
        private String fontPath = "";
        private String chineseName = "";
        private String outlineColor = "#000000";
        private int outlineWidth;
        private String backgroundColor = "#000000";
        private double backgroundOpacity;
        private String group = "";

        private Node(GuiLuaDocument owner,
                     String variable,
                     String type,
                     String createFunction,
                     String parentVariable,
                     String name,
                     List<String> createArgs,
                     StatementRef createRef,
                     String indent,
                     boolean localDeclaration) {
            this.owner = owner;
            this.variable = variable;
            this.type = type;
            this.createFunction = createFunction;
            this.parentVariable = parentVariable;
            this.name = name;
            this.createArgs = createArgs;
            this.createRef = createRef;
            this.indent = indent.isEmpty() ? owner.defaultIndent : indent;
            this.localDeclaration = localDeclaration;
        }

        private static Node fromCreate(GuiLuaDocument owner,
                                       String variable,
                                       String function,
                                       List<String> args,
                                       StatementRef ref,
                                       String indent) {
            String type = normalizeType(function);
            String parent = args.isEmpty() ? "parent" : args.get(0).trim();
            String name = args.size() > 1 ? decodeLuaString(args.get(1)) : variable;
            Node node = new Node(owner, variable, type, function, parent, name,
                    new ArrayList<>(args), ref, indent, ref.text.trim().startsWith("local "));
            double[] defaults = defaultSize(type);
            node.width = defaults[0];
            node.height = defaults[1];
            node.anchorX = defaultAnchor(type);
            node.anchorY = defaultAnchor(type);
            node.readCreateArguments();
            return node;
        }

        private static Node newNode(GuiLuaDocument owner, String variable, String type, String parent) {
            String function = creationFunction(type);
            Node node = new Node(owner, variable, type, function, parent, variable,
                    new ArrayList<>(), null, owner.defaultIndent, true);
            node.added = true;
            node.touchEnabled = "Button".equals(type) || "CheckBox".equals(type) || "Slider".equals(type);
            return node;
        }

        private void readCreateArguments() {
            x = numberArg(2, 0.0);
            y = numberArg(3, 0.0);
            switch (type) {
                case "Layout", "ScrollView", "ListView", "PageView", "TableView" -> {
                    width = numberArg(4, width);
                    height = numberArg(5, height);
                }
                case "TextInput", "RichText", "ScrollText" -> {
                    width = numberArg(4, width);
                    height = numberArg(5, height);
                    fontSize = intArg(6, fontSize);
                }
                case "Text" -> {
                    fontSize = intArg(4, fontSize);
                    color = stringArg(5, color);
                    text = stringArg(6, text);
                    width = estimateTextWidth(text, fontSize);
                    height = Math.max(24, fontSize + 8);
                }
                case "Image", "Button", "LoadingBar", "ProgressTimer" -> image = stringArg(4, image);
                case "CheckBox" -> {
                    image = stringArg(4, image);
                    pressedImage = stringArg(5, pressedImage);
                }
                case "Slider" -> {
                    sliderBarImage = stringArg(4, sliderBarImage);
                    sliderProgressImage = stringArg(5, sliderProgressImage);
                    sliderBallImage = stringArg(6, sliderBallImage);
                }
                default -> {
                    // Type-specific arguments are preserved verbatim even if the native preview cannot render them yet.
                }
            }
        }

        private double numberArg(int index, double fallback) {
            return index < createArgs.size() ? owner.parseNumber(createArgs.get(index), fallback) : fallback;
        }

        private int intArg(int index, int fallback) {
            return (int) Math.round(numberArg(index, fallback));
        }

        private String stringArg(int index, String fallback) {
            return index < createArgs.size() ? decodeLuaString(createArgs.get(index)) : fallback;
        }

        private static double estimateTextWidth(String text, int fontSize) {
            int codePoints = Math.max(1, text.codePointCount(0, text.length()));
            return Math.max(40, Math.min(1200, codePoints * Math.max(8, fontSize * 0.72)));
        }

        private void applySetter(String function, List<String> args) {
            switch (function) {
                case "setPosition" -> {
                    x = number(args, 1, x);
                    y = number(args, 2, y);
                }
                case "setPositionX" -> x = number(args, 1, x);
                case "setPositionY" -> y = number(args, 1, y);
                case "setContentSize", "Text_setTextAreaSize", "ScrollView_setInnerContainerSize" -> {
                    width = number(args, 1, width);
                    height = number(args, 2, height);
                }
                case "setAnchorPoint" -> {
                    anchorX = number(args, 1, anchorX);
                    anchorY = number(args, 2, anchorY);
                }
                case "setScale" -> {
                    scaleX = number(args, 1, scaleX);
                    scaleY = args.size() > 2 ? number(args, 2, scaleY) : scaleX;
                }
                case "setScaleX" -> scaleX = number(args, 1, scaleX);
                case "setScaleY" -> scaleY = number(args, 1, scaleY);
                case "setRotation" -> rotation = number(args, 1, rotation);
                case "setOpacity" -> opacity = number(args, 1, opacity);
                case "setVisible" -> visible = bool(args, 1, visible);
                case "setTouchEnabled" -> touchEnabled = bool(args, 1, touchEnabled);
                case "setMouseEnabled" -> mouseEnabled = bool(args, 1, mouseEnabled);
                case "setSwallowTouches" -> swallowTouches = bool(args, 1, swallowTouches);
                case "setIgnoreContentAdaptWithSize" -> ignoreContent = bool(args, 1, ignoreContent);
                case "setChildrenCascadeOpacityEnabled" -> cascadeOpacity = bool(args, 1, cascadeOpacity);
                case "setTag" -> tag = (int) Math.round(number(args, 1, tag));
                case "setLocalZOrder" -> zOrder = (int) Math.round(number(args, 1, zOrder));
                case "setChineseName" -> chineseName = string(args, 1, chineseName);
                case "setName" -> name = string(args, 1, name);
                case "Image_loadTexture", "LoadingBar_loadTexture", "ProgressTimer_ChangeImg",
                        "Layout_setBackGroundImage", "ScrollView_setBackGroundImage",
                        "ListView_setBackGroundImage", "PageView_setBackGroundImage",
                        "TableView_setBackGroundImage", "Button_loadTextureNormal",
                        "CheckBox_loadTextureBackGround" -> image = string(args, 1, image);
                case "Button_loadTexturePressed", "CheckBox_loadTextureFrontCross" -> pressedImage = string(args, 1, pressedImage);
                case "Button_loadTextureDisabled" -> disabledImage = string(args, 1, disabledImage);
                case "Button_setTitleText", "Text_setString", "TextInput_setString",
                        "TextInput_setPlaceHolder", "TextAtlas_setString" -> {
                    text = string(args, 1, text);
                    if ("Text".equals(type) && !setterRefs.containsKey("size")) {
                        width = estimateTextWidth(text, fontSize);
                    }
                }
                case "Button_setTitleColor", "Text_setTextColor", "TextInput_setFontColor",
                        "TextInput_setPlaceholderFontColor", "LoadingBar_setColor" -> color = string(args, 1, color);
                case "Button_setTitleFontSize", "Text_setFontSize", "TextInput_setFontSize" -> fontSize = (int) Math.round(number(args, 1, fontSize));
                case "Button_setTitleFontName", "Text_setFontName" -> fontPath = string(args, 1, fontPath);
                case "Text_enableOutline", "Button_titleEnableOutline", "ScrollText_enableOutline" -> {
                    outlineColor = string(args, 1, outlineColor);
                    outlineWidth = (int) Math.round(number(args, 2, outlineWidth));
                }
                case "Text_disableOutLine", "Button_titleDisableOutLine" -> outlineWidth = 0;
                case "Layout_setBackGroundColor", "ScrollView_setBackGroundColor",
                        "ListView_setBackGroundColor", "PageView_setBackGroundColor",
                        "TableView_setBackGroundColor" -> backgroundColor = string(args, 1, backgroundColor);
                case "Layout_setBackGroundColorOpacity", "ScrollView_setBackGroundColorOpacity",
                        "ListView_setBackGroundColorOpacity", "PageView_setBackGroundColorOpacity" -> backgroundOpacity = number(args, 1, backgroundOpacity);
                case "Layout_setClippingEnabled", "ScrollView_setClippingEnabled",
                        "ListView_setClippingEnabled", "PageView_setClippingEnabled" -> clipping = bool(args, 1, clipping);
                case "LoadingBar_setPercent", "ProgressTimer_setPercentage", "Slider_setPercent" -> percent = number(args, 1, percent);
                case "CheckBox_setSelected" -> selected = bool(args, 1, selected);
                case "CheckBox_setGroup" -> group = string(args, 1, group);
                case "Slider_loadBarTexture" -> sliderBarImage = string(args, 1, sliderBarImage);
                case "Slider_loadProgressBarTexture" -> sliderProgressImage = string(args, 1, sliderProgressImage);
                case "Slider_loadSlidBallTextureNormal" -> sliderBallImage = string(args, 1, sliderBallImage);
                default -> {
                }
            }
        }

        private double number(List<String> args, int index, double fallback) {
            return index < args.size() ? owner.parseNumber(args.get(index), fallback) : fallback;
        }

        private static boolean bool(List<String> args, int index, boolean fallback) {
            return index < args.size() ? parseBoolean(args.get(index), fallback) : fallback;
        }

        private static String string(List<String> args, int index, String fallback) {
            return index < args.size() ? decodeLuaString(args.get(index)) : fallback;
        }

        public @NotNull String getVariable() {
            return variable;
        }

        public @NotNull String getName() {
            return name;
        }

        public void setName(String value) {
            String normalized = value == null || value.isBlank() ? variable : value.trim();
            if (!Objects.equals(name, normalized)) {
                name = normalized;
                markDirty("name");
            }
        }

        public @NotNull String getType() {
            return type;
        }

        public @Nullable Node getParent() {
            return parent;
        }

        public @NotNull List<Node> getChildren() {
            List<Node> result = new ArrayList<>();
            for (Node child : children) {
                if (!child.deleted) {
                    result.add(child);
                }
            }
            result.sort(Z_ORDER_COMPARATOR);
            return Collections.unmodifiableList(result);
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public double getWidth() {
            return width;
        }

        public double getHeight() {
            return height;
        }

        /**
         * Returns whether the Lua source explicitly controls this node's content size.
         * Image-backed controls without an explicit size use the texture's natural dimensions
         * for the native preview, without rewriting the source.
         */
        public boolean hasExplicitSize() {
            return isRepresentedInCreate("size") || setterRefs.containsKey("size") || dirtyKeys.contains("size");
        }

        void applyNaturalPreviewSize(double naturalWidth, double naturalHeight) {
            if (hasExplicitSize() || !Double.isFinite(naturalWidth) || !Double.isFinite(naturalHeight)
                    || naturalWidth <= 0.0 || naturalHeight <= 0.0) {
                return;
            }
            width = naturalWidth;
            height = naturalHeight;
        }

        public double getAnchorX() {
            return anchorX;
        }

        public double getAnchorY() {
            return anchorY;
        }

        public double getScaleX() {
            return scaleX;
        }

        public double getScaleY() {
            return scaleY;
        }

        public double getRotation() {
            return rotation;
        }

        public double getOpacity() {
            return opacity;
        }

        public int getZOrder() {
            return zOrder;
        }

        public int getTag() {
            return tag;
        }

        public boolean isVisible() {
            return visible;
        }

        public boolean isTouchEnabled() {
            return touchEnabled;
        }

        public boolean isMouseEnabled() {
            return mouseEnabled;
        }

        public boolean isSwallowTouches() {
            return swallowTouches;
        }

        public boolean isIgnoreContent() {
            return ignoreContent;
        }

        public boolean isCascadeOpacity() {
            return cascadeOpacity;
        }

        public boolean isClipping() {
            return clipping;
        }

        public boolean isSelected() {
            return selected;
        }

        public double getPercent() {
            return percent;
        }

        public int getFontSize() {
            return fontSize;
        }

        public @NotNull String getText() {
            return text;
        }

        public @NotNull String getColor() {
            return color;
        }

        public @NotNull String getImage() {
            return image;
        }

        public @NotNull String getPressedImage() {
            return pressedImage;
        }

        public @NotNull String getDisabledImage() {
            return disabledImage;
        }

        public @NotNull String getSliderBarImage() {
            return sliderBarImage;
        }

        public @NotNull String getSliderProgressImage() {
            return sliderProgressImage;
        }

        public @NotNull String getSliderBallImage() {
            return sliderBallImage;
        }

        public @NotNull String getFontPath() {
            return fontPath;
        }

        public @NotNull String getChineseName() {
            return chineseName;
        }

        public @NotNull String getOutlineColor() {
            return outlineColor;
        }

        public int getOutlineWidth() {
            return outlineWidth;
        }

        public @NotNull String getBackgroundColor() {
            return backgroundColor;
        }

        public double getBackgroundOpacity() {
            return backgroundOpacity;
        }

        public @NotNull String getGroup() {
            return group;
        }

        public boolean isDeleted() {
            return deleted;
        }

        public int getSourceLine() {
            return createRef == null ? -1 : createRef.startLine + 1;
        }

        public @NotNull Object getProperty(@NotNull String key) {
            return switch (key) {
                case "variable" -> variable;
                case "name" -> name;
                case "type" -> type;
                case "parent" -> parent == null ? parentVariable : parent.variable;
                case "x" -> x;
                case "y" -> y;
                case "width" -> width;
                case "height" -> height;
                case "anchorX" -> anchorX;
                case "anchorY" -> anchorY;
                case "scaleX" -> scaleX;
                case "scaleY" -> scaleY;
                case "rotation" -> rotation;
                case "opacity" -> opacity;
                case "zOrder" -> zOrder;
                case "tag" -> tag;
                case "visible" -> visible;
                case "touchEnabled" -> touchEnabled;
                case "mouseEnabled" -> mouseEnabled;
                case "swallowTouches" -> swallowTouches;
                case "ignoreContent" -> ignoreContent;
                case "cascadeOpacity" -> cascadeOpacity;
                case "clipping" -> clipping;
                case "selected" -> selected;
                case "percent" -> percent;
                case "fontSize" -> fontSize;
                case "text" -> text;
                case "color" -> color;
                case "image" -> image;
                case "pressedImage" -> pressedImage;
                case "disabledImage" -> disabledImage;
                case "sliderBarImage" -> sliderBarImage;
                case "sliderProgressImage" -> sliderProgressImage;
                case "sliderBallImage" -> sliderBallImage;
                case "fontPath" -> fontPath;
                case "chineseName" -> chineseName;
                case "outlineColor" -> outlineColor;
                case "outlineWidth" -> outlineWidth;
                case "backgroundColor" -> backgroundColor;
                case "backgroundOpacity" -> backgroundOpacity;
                case "group" -> group;
                default -> "";
            };
        }

        public boolean isPropertyEditable(@NotNull String key) {
            return !Set.of("variable", "type", "parent").contains(key);
        }

        public void setProperty(@NotNull String key, @Nullable Object value) {
            String textValue = value == null ? "" : String.valueOf(value).trim();
            try {
                switch (key) {
                    case "name" -> setName(textValue);
                    case "x", "y", "width", "height", "anchorX", "anchorY", "scaleX", "scaleY",
                            "rotation", "opacity", "percent", "backgroundOpacity" -> setNumber(key, Double.parseDouble(textValue));
                    case "zOrder", "tag", "fontSize", "outlineWidth" -> setInteger(key, Integer.parseInt(textValue));
                    case "visible", "touchEnabled", "mouseEnabled", "swallowTouches", "ignoreContent",
                            "cascadeOpacity", "clipping", "selected" -> setBoolean(key, Boolean.parseBoolean(textValue));
                    case "text", "color", "image", "pressedImage", "disabledImage", "sliderBarImage",
                            "sliderProgressImage", "sliderBallImage", "fontPath", "chineseName",
                            "outlineColor", "backgroundColor", "group" -> setString(key, value == null ? "" : String.valueOf(value));
                    default -> {
                    }
                }
            } catch (NumberFormatException ignored) {
                // The property table restores the canonical value on its next refresh.
            }
        }

        private void setNumber(String key, double value) {
            if (!Double.isFinite(value)) {
                return;
            }
            double adjusted = switch (key) {
                case "width", "height" -> Math.max(1.0, value);
                case "anchorX", "anchorY" -> Math.max(0.0, Math.min(1.0, value));
                case "opacity" -> Math.max(0.0, Math.min(255.0, value));
                case "percent", "backgroundOpacity" -> Math.max(0.0, Math.min(100.0, value));
                default -> value;
            };
            double previous = switch (key) {
                case "x" -> x;
                case "y" -> y;
                case "width" -> width;
                case "height" -> height;
                case "anchorX" -> anchorX;
                case "anchorY" -> anchorY;
                case "scaleX" -> scaleX;
                case "scaleY" -> scaleY;
                case "rotation" -> rotation;
                case "opacity" -> opacity;
                case "percent" -> percent;
                case "backgroundOpacity" -> backgroundOpacity;
                default -> adjusted;
            };
            if (Math.abs(previous - adjusted) < 0.0000001) {
                return;
            }
            switch (key) {
                case "x" -> x = adjusted;
                case "y" -> y = adjusted;
                case "width" -> width = adjusted;
                case "height" -> height = adjusted;
                case "anchorX" -> anchorX = adjusted;
                case "anchorY" -> anchorY = adjusted;
                case "scaleX" -> scaleX = adjusted;
                case "scaleY" -> scaleY = adjusted;
                case "rotation" -> rotation = adjusted;
                case "opacity" -> opacity = adjusted;
                case "percent" -> percent = adjusted;
                case "backgroundOpacity" -> backgroundOpacity = adjusted;
                default -> {
                    return;
                }
            }
            markDirty(keyToLogical(key));
        }

        private void setInteger(String key, int value) {
            int adjusted = switch (key) {
                case "fontSize" -> Math.max(1, Math.min(512, value));
                case "outlineWidth" -> Math.max(0, Math.min(64, value));
                default -> value;
            };
            int previous = switch (key) {
                case "zOrder" -> zOrder;
                case "tag" -> tag;
                case "fontSize" -> fontSize;
                case "outlineWidth" -> outlineWidth;
                default -> adjusted;
            };
            if (previous == adjusted) {
                return;
            }
            switch (key) {
                case "zOrder" -> zOrder = adjusted;
                case "tag" -> tag = adjusted;
                case "fontSize" -> fontSize = adjusted;
                case "outlineWidth" -> outlineWidth = adjusted;
                default -> {
                    return;
                }
            }
            markDirty(keyToLogical(key));
        }

        private void setBoolean(String key, boolean value) {
            boolean previous = switch (key) {
                case "visible" -> visible;
                case "touchEnabled" -> touchEnabled;
                case "mouseEnabled" -> mouseEnabled;
                case "swallowTouches" -> swallowTouches;
                case "ignoreContent" -> ignoreContent;
                case "cascadeOpacity" -> cascadeOpacity;
                case "clipping" -> clipping;
                case "selected" -> selected;
                default -> value;
            };
            if (previous == value) {
                return;
            }
            switch (key) {
                case "visible" -> visible = value;
                case "touchEnabled" -> touchEnabled = value;
                case "mouseEnabled" -> mouseEnabled = value;
                case "swallowTouches" -> swallowTouches = value;
                case "ignoreContent" -> ignoreContent = value;
                case "cascadeOpacity" -> cascadeOpacity = value;
                case "clipping" -> clipping = value;
                case "selected" -> selected = value;
                default -> {
                    return;
                }
            }
            markDirty(keyToLogical(key));
        }

        private void setString(String key, String value) {
            String normalized = value == null ? "" : value;
            String previous = switch (key) {
                case "text" -> text;
                case "color" -> color;
                case "image" -> image;
                case "pressedImage" -> pressedImage;
                case "disabledImage" -> disabledImage;
                case "sliderBarImage" -> sliderBarImage;
                case "sliderProgressImage" -> sliderProgressImage;
                case "sliderBallImage" -> sliderBallImage;
                case "fontPath" -> fontPath;
                case "chineseName" -> chineseName;
                case "outlineColor" -> outlineColor;
                case "backgroundColor" -> backgroundColor;
                case "group" -> group;
                default -> normalized;
            };
            if (Objects.equals(previous, normalized)) {
                return;
            }
            switch (key) {
                case "text" -> text = normalized;
                case "color" -> color = normalized;
                case "image" -> image = normalized;
                case "pressedImage" -> pressedImage = normalized;
                case "disabledImage" -> disabledImage = normalized;
                case "sliderBarImage" -> sliderBarImage = normalized;
                case "sliderProgressImage" -> sliderProgressImage = normalized;
                case "sliderBallImage" -> sliderBallImage = normalized;
                case "fontPath" -> fontPath = normalized;
                case "chineseName" -> chineseName = normalized;
                case "outlineColor" -> outlineColor = normalized;
                case "backgroundColor" -> backgroundColor = normalized;
                case "group" -> group = normalized;
                default -> {
                    return;
                }
            }
            markDirty(keyToLogical(key));
        }

        private static String keyToLogical(String key) {
            return switch (key) {
                case "x", "y" -> "position";
                case "width", "height" -> "size";
                case "anchorX", "anchorY" -> "anchor";
                case "outlineColor", "outlineWidth" -> "outline";
                default -> key;
            };
        }

        private void markDirty(String logicalKey) {
            dirtyKeys.add(logicalKey);
            owner.version++;
        }

        private boolean isRepresentedInCreate(String key) {
            return switch (key) {
                case "position", "name" -> true;
                case "size" -> switch (type) {
                    case "Layout", "ScrollView", "ListView", "PageView", "TableView", "TextInput", "RichText", "ScrollText" -> true;
                    default -> false;
                };
                case "image" -> Set.of("Image", "Button", "LoadingBar", "ProgressTimer", "CheckBox").contains(type);
                case "text", "color", "fontSize" -> "Text".equals(type);
                case "sliderBarImage", "sliderProgressImage", "sliderBallImage" -> "Slider".equals(type);
                default -> false;
            };
        }

        private String renderCreateStatement() {
            List<String> args = new ArrayList<>(createArgs);
            ensureSize(args, Math.max(4, args.size()));
            setArg(args, 0, parent == null ? parentVariable : parent.variable);
            setArg(args, 1, luaString(name));
            setArg(args, 2, formatNumber(x));
            setArg(args, 3, formatNumber(y));

            switch (type) {
                case "Layout", "ScrollView", "ListView", "PageView", "TableView" -> {
                    setArg(args, 4, formatNumber(width));
                    setArg(args, 5, formatNumber(height));
                }
                case "TextInput", "RichText", "ScrollText" -> {
                    setArg(args, 4, formatNumber(width));
                    setArg(args, 5, formatNumber(height));
                    setArg(args, 6, Integer.toString(fontSize));
                }
                case "Text" -> {
                    setArg(args, 4, Integer.toString(fontSize));
                    setArg(args, 5, luaString(color));
                    setArg(args, 6, luaString(text));
                }
                case "Image", "Button", "LoadingBar", "ProgressTimer" -> setArg(args, 4, luaString(image));
                case "CheckBox" -> {
                    setArg(args, 4, luaString(image));
                    setArg(args, 5, luaString(pressedImage));
                }
                case "Slider" -> {
                    setArg(args, 4, luaString(sliderBarImage));
                    setArg(args, 5, luaString(sliderProgressImage));
                    setArg(args, 6, luaString(sliderBallImage));
                }
                default -> {
                }
            }
            return indent + (localDeclaration ? "local " : "")
                    + variable + " = GUI:" + createFunction + "(" + String.join(", ", args) + ")";
        }

        private static void ensureSize(List<String> args, int size) {
            while (args.size() < size) {
                args.add("nil");
            }
        }

        private static void setArg(List<String> args, int index, String value) {
            ensureSize(args, index + 1);
            args.set(index, value);
        }

        private @Nullable String renderSetter(String key, @Nullable String originalFunction) {
            String function = originalFunction != null ? originalFunction : preferredSetter(key);
            if (function == null) {
                return null;
            }
            String body = switch (key) {
                case "position" -> switch (function) {
                    case "setPositionX" -> variable + ", " + formatNumber(x);
                    case "setPositionY" -> variable + ", " + formatNumber(y);
                    default -> variable + ", " + formatNumber(x) + ", " + formatNumber(y);
                };
                case "size" -> variable + ", " + formatNumber(width) + ", " + formatNumber(height);
                case "anchor" -> variable + ", " + formatNumber(anchorX) + ", " + formatNumber(anchorY);
                case "scale" -> variable + ", " + formatNumber(scaleX) + ", " + formatNumber(scaleY);
                case "scaleX" -> variable + ", " + formatNumber(scaleX);
                case "scaleY" -> variable + ", " + formatNumber(scaleY);
                case "rotation" -> variable + ", " + formatNumber(rotation);
                case "opacity" -> variable + ", " + formatNumber(opacity);
                case "visible" -> variable + ", " + visible;
                case "touchEnabled" -> variable + ", " + touchEnabled;
                case "mouseEnabled" -> variable + ", " + mouseEnabled;
                case "swallowTouches" -> variable + ", " + swallowTouches;
                case "ignoreContent" -> variable + ", " + ignoreContent;
                case "cascadeOpacity" -> variable + ", " + cascadeOpacity;
                case "clipping" -> variable + ", " + clipping;
                case "tag" -> variable + ", " + tag;
                case "zOrder" -> variable + ", " + zOrder;
                case "chineseName" -> variable + ", " + luaString(chineseName);
                case "name" -> variable + ", " + luaString(name);
                case "image" -> variable + ", " + luaString(image);
                case "pressedImage" -> variable + ", " + luaString(pressedImage);
                case "disabledImage" -> variable + ", " + luaString(disabledImage);
                case "sliderBarImage" -> variable + ", " + luaString(sliderBarImage);
                case "sliderProgressImage" -> variable + ", " + luaString(sliderProgressImage);
                case "sliderBallImage" -> variable + ", " + luaString(sliderBallImage);
                case "text" -> variable + ", " + luaString(text);
                case "color" -> variable + ", " + luaString(color);
                case "fontSize" -> variable + ", " + fontSize;
                case "fontPath" -> variable + ", " + luaString(fontPath);
                case "outline" -> variable + ", " + luaString(outlineColor) + ", " + outlineWidth;
                case "outlineDisabled" -> variable;
                case "backgroundColor" -> variable + ", " + luaString(backgroundColor);
                case "backgroundOpacity" -> variable + ", " + formatNumber(backgroundOpacity);
                case "percent" -> variable + ", " + formatNumber(percent);
                case "selected" -> variable + ", " + selected;
                case "group" -> variable + ", " + luaString(group);
                default -> null;
            };
            return body == null ? null : indent + "GUI:" + function + "(" + body + ")";
        }

        private @Nullable String preferredSetter(String key) {
            return switch (key) {
                case "position" -> "setPosition";
                case "size" -> "setContentSize";
                case "anchor" -> "setAnchorPoint";
                case "scale" -> "setScale";
                case "scaleX" -> "setScaleX";
                case "scaleY" -> "setScaleY";
                case "rotation" -> "setRotation";
                case "opacity" -> "setOpacity";
                case "visible" -> "setVisible";
                case "touchEnabled" -> "setTouchEnabled";
                case "mouseEnabled" -> "setMouseEnabled";
                case "swallowTouches" -> "setSwallowTouches";
                case "ignoreContent" -> "setIgnoreContentAdaptWithSize";
                case "cascadeOpacity" -> "setChildrenCascadeOpacityEnabled";
                case "clipping" -> switch (type) {
                    case "Layout" -> "Layout_setClippingEnabled";
                    case "ScrollView" -> "ScrollView_setClippingEnabled";
                    case "ListView" -> "ListView_setClippingEnabled";
                    case "PageView" -> "PageView_setClippingEnabled";
                    default -> null;
                };
                case "tag" -> "setTag";
                case "zOrder" -> "setLocalZOrder";
                case "chineseName" -> "setChineseName";
                case "name" -> "setName";
                case "image" -> switch (type) {
                    case "Button" -> "Button_loadTextureNormal";
                    case "LoadingBar" -> "LoadingBar_loadTexture";
                    case "ProgressTimer" -> "ProgressTimer_ChangeImg";
                    case "Layout" -> "Layout_setBackGroundImage";
                    case "ScrollView" -> "ScrollView_setBackGroundImage";
                    case "ListView" -> "ListView_setBackGroundImage";
                    case "PageView" -> "PageView_setBackGroundImage";
                    case "TableView" -> "TableView_setBackGroundImage";
                    case "CheckBox" -> "CheckBox_loadTextureBackGround";
                    default -> "Image_loadTexture";
                };
                case "pressedImage" -> "CheckBox".equals(type) ? "CheckBox_loadTextureFrontCross" : "Button_loadTexturePressed";
                case "disabledImage" -> "Button_loadTextureDisabled";
                case "sliderBarImage" -> "Slider_loadBarTexture";
                case "sliderProgressImage" -> "Slider_loadProgressBarTexture";
                case "sliderBallImage" -> "Slider_loadSlidBallTextureNormal";
                case "text" -> switch (type) {
                    case "Button" -> "Button_setTitleText";
                    case "TextInput" -> "TextInput_setString";
                    case "TextAtlas" -> "TextAtlas_setString";
                    default -> "Text_setString";
                };
                case "color" -> switch (type) {
                    case "Button" -> "Button_setTitleColor";
                    case "TextInput" -> "TextInput_setFontColor";
                    case "LoadingBar" -> "LoadingBar_setColor";
                    default -> "Text_setTextColor";
                };
                case "fontSize" -> switch (type) {
                    case "Button" -> "Button_setTitleFontSize";
                    case "TextInput" -> "TextInput_setFontSize";
                    default -> "Text_setFontSize";
                };
                case "fontPath" -> "Button".equals(type) ? "Button_setTitleFontName" : "Text_setFontName";
                case "outline" -> "Button".equals(type) ? "Button_titleEnableOutline" : "Text_enableOutline";
                case "outlineDisabled" -> "Button".equals(type) ? "Button_titleDisableOutLine" : "Text_disableOutLine";
                case "backgroundColor" -> type + "_setBackGroundColor";
                case "backgroundOpacity" -> type + "_setBackGroundColorOpacity";
                case "percent" -> switch (type) {
                    case "LoadingBar" -> "LoadingBar_setPercent";
                    case "ProgressTimer" -> "ProgressTimer_setPercentage";
                    default -> "Slider_setPercent";
                };
                case "selected" -> "CheckBox_setSelected";
                case "group" -> "CheckBox_setGroup";
                default -> null;
            };
        }

        private List<String> renderNewBlock() {
            List<String> result = new ArrayList<>();
            result.add(indent + "-- Create " + name);
            result.add(renderNewCreate());
            result.add(indent + "GUI:setAnchorPoint(" + variable + ", " + formatNumber(anchorX) + ", " + formatNumber(anchorY) + ")");
            if (!isRepresentedInCreate("size")) {
                result.add(indent + "GUI:setContentSize(" + variable + ", " + formatNumber(width) + ", " + formatNumber(height) + ")");
            }
            result.add(indent + "GUI:setTouchEnabled(" + variable + ", " + touchEnabled + ")");
            result.add(indent + "GUI:setTag(" + variable + ", " + tag + ")");
            if (!visible) {
                result.add(indent + "GUI:setVisible(" + variable + ", false)");
            }
            if (zOrder != 0) {
                result.add(indent + "GUI:setLocalZOrder(" + variable + ", " + zOrder + ")");
            }
            return result;
        }

        private String renderNewCreate() {
            List<String> args = defaultCreateArgs(type,
                    parent == null ? parentVariable : parent.variable,
                    name, x, y, width, height, fontSize, color, text, image,
                    pressedImage, sliderBarImage, sliderProgressImage, sliderBallImage);
            return indent + "local " + variable + " = GUI:" + creationFunction(type)
                    + "(" + String.join(", ", args) + ")";
        }

        private static String creationFunction(String type) {
            return switch (type) {
                case "LoadExport", "Prefab" -> "LoadExport";
                default -> type + "_Create";
            };
        }

        private static List<String> defaultCreateArgs(String type,
                                                      String parent,
                                                      String name,
                                                      double x,
                                                      double y,
                                                      double width,
                                                      double height,
                                                      int fontSize,
                                                      String color,
                                                      String text,
                                                      String image,
                                                      String pressedImage,
                                                      String sliderBar,
                                                      String sliderProgress,
                                                      String sliderBall) {
            List<String> common = new ArrayList<>(List.of(parent, luaString(name), formatNumber(x), formatNumber(y)));
            switch (type) {
                case "Layout" -> common.addAll(List.of(formatNumber(width), formatNumber(height), "false"));
                case "ScrollView" -> common.addAll(List.of(formatNumber(width), formatNumber(height), "1"));
                case "ListView" -> common.addAll(List.of(formatNumber(width), formatNumber(height), "1"));
                case "PageView" -> common.addAll(List.of(formatNumber(width), formatNumber(height)));
                case "TableView" -> common.addAll(List.of(formatNumber(width), formatNumber(height), "1", "1", "0", "0"));
                case "Image", "Button", "LoadingBar", "ProgressTimer" -> common.add(luaString(image));
                case "Text" -> common.addAll(List.of(Integer.toString(fontSize), luaString(color), luaString(text)));
                case "TextInput" -> common.addAll(List.of(formatNumber(width), formatNumber(height), Integer.toString(fontSize)));
                case "RichText" -> common.addAll(List.of(formatNumber(width), formatNumber(height), Integer.toString(fontSize),
                        luaString(color), "1", luaString("Arial"), luaString(text)));
                case "ScrollText" -> common.addAll(List.of(formatNumber(width), formatNumber(height), Integer.toString(fontSize),
                        luaString(color), luaString(text), luaString("Arial"), "0"));
                case "CheckBox" -> common.addAll(List.of(luaString(image), luaString(pressedImage)));
                case "Slider" -> common.addAll(List.of(luaString(sliderBar), luaString(sliderProgress), luaString(sliderBall)));
                case "Effect", "FxEffect" -> common.addAll(List.of("0", "0"));
                case "ItemShow" -> common.add("{}");
                case "EquipShow" -> common.add("{}");
                case "CostItem" -> common.add("{}");
                case "ItemBox" -> common.addAll(List.of(luaString(""), "0", "1"));
                case "UIModel" -> common.addAll(List.of("0", "0", "0"));
                case "ParticleEffect" -> common.add(luaString(""));
                case "Frames" -> common.addAll(List.of(luaString(""), luaString(""), "1", "1"));
                case "SpineAnim", "Spine38Anim" -> common.addAll(List.of(luaString(""), luaString(""), "true", luaString(""), "true"));
                case "RedDot" -> common.add("0");
                default -> {
                }
            }
            return common;
        }

        @Override
        public String toString() {
            String label = chineseName.isBlank() ? name : chineseName;
            return label + "  [" + type + "]";
        }
    }

    private static final class LuaBalance {
        private final int round;
        private final int curly;
        private final int square;
        private final boolean openString;

        private LuaBalance(int round, int curly, int square, boolean openString) {
            this.round = round;
            this.curly = curly;
            this.square = square;
            this.openString = openString;
        }

        private boolean needsMore() {
            return round > 0 || curly > 0 || square > 0 || openString;
        }

        private static LuaBalance scan(String text) {
            int round = 0;
            int curly = 0;
            int square = 0;
            char quote = 0;
            boolean escape = false;
            boolean lineComment = false;
            int longString = -1;
            int longComment = -1;

            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
                if (lineComment) {
                    if (c == '\n' || c == '\r') {
                        lineComment = false;
                    }
                    continue;
                }
                if (longComment >= 0) {
                    int end = matchLongBracketEnd(text, i, longComment);
                    if (end >= 0) {
                        i = end;
                        longComment = -1;
                    }
                    continue;
                }
                if (longString >= 0) {
                    int end = matchLongBracketEnd(text, i, longString);
                    if (end >= 0) {
                        i = end;
                        longString = -1;
                    }
                    continue;
                }
                if (quote != 0) {
                    if (escape) {
                        escape = false;
                    } else if (c == '\\') {
                        escape = true;
                    } else if (c == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (c == '-' && next == '-') {
                    int open = matchLongBracketStart(text, i + 2);
                    if (open >= 0) {
                        longComment = open;
                        i += 2 + open + 1;
                    } else {
                        lineComment = true;
                        i++;
                    }
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quote = c;
                } else if (c == '[') {
                    int open = matchLongBracketStart(text, i);
                    if (open >= 0) {
                        longString = open;
                        i += open + 1;
                    } else {
                        square++;
                    }
                } else if (c == ']') {
                    square = Math.max(0, square - 1);
                } else if (c == '(') {
                    round++;
                } else if (c == ')') {
                    round = Math.max(0, round - 1);
                } else if (c == '{') {
                    curly++;
                } else if (c == '}') {
                    curly = Math.max(0, curly - 1);
                }
            }
            return new LuaBalance(round, curly, square, quote != 0 || longString >= 0 || longComment >= 0);
        }
    }

    /** Simple arithmetic evaluator for screen-relative X/Y expressions. */
    private static final class NumericExpression {
        private final String input;
        private int index;

        private NumericExpression(String input) {
            this.input = input;
        }

        private double parse() {
            double result = expression();
            skipSpace();
            if (index != input.length()) {
                throw new IllegalArgumentException("Unsupported token");
            }
            return result;
        }

        private double expression() {
            double result = term();
            while (true) {
                skipSpace();
                if (take('+')) {
                    result += term();
                } else if (take('-')) {
                    result -= term();
                } else {
                    return result;
                }
            }
        }

        private double term() {
            double result = factor();
            while (true) {
                skipSpace();
                if (take('*')) {
                    result *= factor();
                } else if (take('/')) {
                    result /= factor();
                } else {
                    return result;
                }
            }
        }

        private double factor() {
            skipSpace();
            if (take('+')) {
                return factor();
            }
            if (take('-')) {
                return -factor();
            }
            if (take('(')) {
                double value = expression();
                skipSpace();
                if (!take(')')) {
                    throw new IllegalArgumentException("Missing )");
                }
                return value;
            }
            int start = index;
            while (index < input.length()) {
                char c = input.charAt(index);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E'
                        || ((c == '+' || c == '-') && index > start
                        && (input.charAt(index - 1) == 'e' || input.charAt(index - 1) == 'E'))) {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new IllegalArgumentException("Expected number");
            }
            return Double.parseDouble(input.substring(start, index));
        }

        private void skipSpace() {
            while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
                index++;
            }
        }

        private boolean take(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }
    }
}
