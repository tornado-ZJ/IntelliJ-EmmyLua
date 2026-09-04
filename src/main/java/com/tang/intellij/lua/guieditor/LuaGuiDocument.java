package com.tang.intellij.lua.guieditor;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LuaGuiDocument {
    private static final String IDENTIFIER = "[\\p{L}_][\\p{L}\\p{N}_]*";
    private static final Pattern CREATE = Pattern.compile("^\\s*(?:local\\s+)?("+IDENTIFIER+")\\s*=\\s*GUI:("+IDENTIFIER+")_Create\\s*\\((.*)\\)\\s*$");
    private static final Pattern LOAD_EXPORT = Pattern.compile("^\\s*(?:local\\s+)?("+IDENTIFIER+")\\s*=\\s*GUI:(LoadExport)\\s*\\((.*)\\)\\s*$");
    private static final Pattern CALL = Pattern.compile("^\\s*GUI:("+IDENTIFIER+")\\s*\\(\\s*("+IDENTIFIER+")\\s*(?:,\\s*(.*))?\\)\\s*$");
    private static final Pattern OBJECT_CONFIG = Pattern.compile("^\\s*("+IDENTIFIER+")\\s*(?:\\[\\s*[\"']object_config[\"']\\s*]|\\.object_config)\\s*=\\s*(.+?)\\s*$");

    final List<Widget> widgets = new ArrayList<>();
    final Map<String, Widget> byId = new LinkedHashMap<>();
    final Map<String, Widget> byVariable = new LinkedHashMap<>();

    record DuplicateResult(String block, String rootVariable, int insertionLine, int widgetCount) { }

    static LuaGuiDocument parse(String text) {
        LuaGuiDocument result = new LuaGuiDocument();
        String[] lines = text.split("\\R", -1);
        Map<String,Integer> occurrence = new HashMap<>();
        for (int i = 0; i < lines.length; i++) {
            Matcher create = CREATE.matcher(lines[i]);
            if (!create.matches()) create = LOAD_EXPORT.matcher(lines[i]);
            if (create.matches()) {
                List<String> args = splitArgs(create.group(3));
                String variable=create.group(1);int ordinal=occurrence.merge(variable,1,Integer::sum);
                String sourceLine=lines[i];String indent=sourceLine.substring(0,sourceLine.length()-sourceLine.stripLeading().length());
                Widget w = new Widget(variable, create.group(2), args, i, variable+"#"+ordinal,indent,sourceLine.stripLeading().startsWith("local "));
                w.parentWidget=result.byVariable.get(w.parent());
                Widget previous=result.byVariable.get(variable);if(previous!=null){previous.duplicateName=true;w.duplicateName=true;}
                if(w.parentWidget!=null)w.parentWidget.children.add(w);
                w.ownedLines.add(i);
                result.widgets.add(w);result.byId.put(w.id,w);result.byVariable.put(w.variable,w);
                continue;
            }
            Matcher call = CALL.matcher(lines[i]);
            if (call.matches()) {
                Widget w = result.byVariable.get(call.group(2));
                if (w != null) {w.calls.put(call.group(1), new Call(call.group(1), splitArgs(call.group(3) == null ? "" : call.group(3)), i));w.ownedLines.add(i);}
                continue;
            }
            Matcher objectConfig = OBJECT_CONFIG.matcher(lines[i]);
            if (objectConfig.matches()) {
                Widget w = result.byVariable.get(objectConfig.group(1));
                if (w != null) {w.objectConfig = new ObjectConfig(objectConfig.group(2).trim(), i);w.ownedLines.add(i);}
            }
        }
        return result;
    }

    static List<String> splitArgs(String input) {
        List<String> out = new ArrayList<>();
        if (input == null || input.isBlank()) return out;
        int level = 0; boolean quote = false; char quoteChar = 0; int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quote) { if (c == quoteChar && (i == 0 || input.charAt(i - 1) != '\\')) quote = false; continue; }
            if (c == '\'' || c == '"') { quote = true; quoteChar = c; continue; }
            if (c == '[' && i + 1 < input.length() && input.charAt(i + 1) == '[') { int end = input.indexOf("]]", i + 2); if (end >= 0) { i = end + 1; continue; } }
            if (c == '(' || c == '{' || c == '[') level++;
            else if (c == ')' || c == '}' || c == ']') level--;
            else if (c == ',' && level == 0) { out.add(input.substring(start, i).trim()); start = i + 1; }
        }
        out.add(input.substring(start).trim()); return out;
    }

    DuplicateResult duplicateSubtree(String text, Widget root) {
        List<Widget> subtree = new ArrayList<>();
        collectSubtree(root, subtree);
        Set<String> used = new HashSet<>();
        for (Widget widget : widgets) used.add(widget.variable);
        Map<Widget, String> renamed = new IdentityHashMap<>();
        for (Widget widget : subtree) {
            if (widget != root) { renamed.put(widget, widget.variable); continue; }
            String base = widget.variable + "_copy", candidate = base;
            for (int suffix = 2; used.contains(candidate); suffix++) candidate = base + suffix;
            used.add(candidate);
            renamed.put(widget, candidate);
        }

        Map<Integer, Widget> ownerByLine = new HashMap<>();
        TreeSet<Integer> sourceLines = new TreeSet<>();
        for (Widget widget : subtree) for (int line : widget.ownedLines) {
            sourceLines.add(line);
            ownerByLine.put(line, widget);
        }
        String[] lines = text.split("\\R", -1);
        StringBuilder block = new StringBuilder("\n");
        for (int line : sourceLines) {
            if (line >= lines.length) continue;
            Widget owner = ownerByLine.get(line);
            block.append(rewriteOwnedLine(lines[line], owner, renamed)).append('\n');
        }
        int insertionLine = sourceLines.isEmpty() ? root.createLine : sourceLines.last();
        return new DuplicateResult(block.toString(), renamed.get(root), insertionLine, subtree.size());
    }

    private static void collectSubtree(Widget widget, List<Widget> result) {
        result.add(widget);
        for (Widget child : widget.children) collectSubtree(child, result);
    }

    private static String rewriteOwnedLine(String source, Widget owner, Map<Widget, String> renamed) {
        String indent = source.substring(0, source.length() - source.stripLeading().length());
        Matcher create = CREATE.matcher(source);
        if (!create.matches()) create = LOAD_EXPORT.matcher(source);
        if (create.matches()) {
            List<String> args = new ArrayList<>(owner.args);
            if (!args.isEmpty() && owner.parentWidget != null && renamed.containsKey(owner.parentWidget))
                args.set(0, renamed.get(owner.parentWidget));
            for (int i = 1; i < args.size(); i++) args.set(i, rewriteIdentifiers(args.get(i), owner, renamed));
            String factory = owner.type.equals("LoadExport") ? "LoadExport" : owner.type + "_Create";
            return indent + (owner.localDeclaration ? "local " : "") + renamed.get(owner)
                    + " = GUI:" + factory + "(" + String.join(", ", args) + ")";
        }
        Matcher call = CALL.matcher(source);
        if (call.matches()) {
            List<String> args = splitArgs(call.group(3) == null ? "" : call.group(3));
            for (int i = 0; i < args.size(); i++) args.set(i, rewriteIdentifiers(args.get(i), owner, renamed));
            return indent + "GUI:" + call.group(1) + "(" + renamed.get(owner)
                    + (args.isEmpty() ? "" : ", " + String.join(", ", args)) + ")";
        }
        return rewriteIdentifiers(source, owner, renamed);
    }

    private static String rewriteIdentifiers(String source, Widget owner, Map<Widget, String> renamed) {
        Pattern identifier = Pattern.compile("(?<![\\p{L}\\p{N}_])(" + IDENTIFIER + ")(?![\\p{L}\\p{N}_])");
        Matcher matcher = identifier.matcher(source);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String token = matcher.group(1), replacement = null;
            if (token.equals(owner.variable)) replacement = renamed.get(owner);
            else for (Map.Entry<Widget, String> entry : renamed.entrySet())
                if (entry.getKey().variable.equals(token)) { replacement = entry.getValue(); break; }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement == null ? token : replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    static final class Call {
        final String method; final List<String> args; final int line;
        Call(String method, List<String> args, int line) { this.method = method; this.args = args; this.line = line; }
    }

    static final class ObjectConfig {
        final String value; final int line;
        ObjectConfig(String value, int line) { this.value = value; this.line = line; }
    }

    static final class Widget {
        final String variable, type, id, indent; final List<String> args; final int createLine, ordinal; final boolean localDeclaration;
        boolean duplicateName;
        final Map<String, Call> calls = new LinkedHashMap<>(); final List<Widget> children = new ArrayList<>();
        final Set<Integer> ownedLines = new LinkedHashSet<>();
        ObjectConfig objectConfig;
        Widget parentWidget;
        Widget(String variable, String type, List<String> args, int createLine, String id, String indent, boolean localDeclaration) { this.variable = variable; this.type = type; this.args = args; this.createLine = createLine; this.id=id; this.ordinal=Integer.parseInt(id.substring(id.lastIndexOf('#')+1)); this.indent=indent; this.localDeclaration=localDeclaration; }
        String parent() { return args.isEmpty() ? null : args.get(0); }
        String displayName() { return args.size() > 1 ? unquote(args.get(1)) : variable; }
        @Override public String toString() { return displayName() + (duplicateName?" ["+ordinal+"]":"") + "  ·  " + type; }
        double x() {Call position=calls.get("setPosition");if(position!=null)return number(position.args,0,number(args,2,0));return callNumber("setPositionX",0,number(args,2,0));}
        double y() {Call position=calls.get("setPosition");if(position!=null)return number(position.args,1,number(args,3,0));return callNumber("setPositionY",0,number(args,3,0));}
        double width() {
            Call size = calls.get("setContentSize");
            if (size != null) return number(size.args, 0, 120);
            if(type.equals("RichText"))return number(args,5,100);
            if(type.equals("TextAtlas")){int count=Math.max(1,text().codePointCount(0,text().length()));double glyphWidth=number(args,6,16),space=callNumber("TextAtlas_setWordSpace",0,0);return glyphWidth*count+space*Math.max(0,count-1);}
            if(type.equals("TextInput"))return number(args,4,200);
            return Set.of("Layout", "ListView", "ScrollView", "PageView", "TableView").contains(type) ? number(args, 4, 180) : (type.equals("Button") ? 120 : 100);
        }
        double height() {
            Call size = calls.get("setContentSize");
            if (size != null) return number(size.args, 1, 40);
            if(type.equals("RichText"))return number(args,6,16)+8;
            if(type.equals("TextAtlas"))return number(args,7,24);
            if(type.equals("TextInput"))return number(args,5,30);
            return Set.of("Layout", "ListView", "ScrollView", "PageView", "TableView").contains(type) ? number(args, 5, 120) : (type.equals("Text") ? 28 : 40);
        }
        Call call(String name) { return calls.get(name); }
        String callArg(String name, int index, String fallback) {
            Call c = calls.get(name);
            return c != null && index < c.args.size() ? c.args.get(index) : fallback;
        }
        double callNumber(String name, int index, double fallback) {
            Call c = calls.get(name);
            return c == null ? fallback : number(c.args, index, fallback);
        }
        boolean callBoolean(String name, int index, boolean fallback) {
            String value = callArg(name, index, String.valueOf(fallback)).trim();
            return value.equalsIgnoreCase("true") || value.equals("1");
        }
        double anchorX() { return callNumber("setAnchorPoint", 0, 0); }
        double anchorY() { return callNumber("setAnchorPoint", 1, 0); }
        double scaleX() { return callNumber("setScaleX", 0, callNumber("setScale", 0, 1)); }
        double scaleY() { return callNumber("setScaleY", 0, callNumber("setScale", 0, 1)); }
        double rotation() { return callNumber("setRotation", 0, 0); }
        double opacity() {
            double value = callNumber("setOpacity", 0, 255);
            return Math.max(0, Math.min(1, value > 1 ? value / 255d : value));
        }
        boolean visible() { return callBoolean("setVisible", 0, true); }
        int zOrder(){return (int)callNumber("setLocalZOrder",0,0);}
        String texture() {
            if ((type.equals("Image") || type.equals("Button") || type.equals("LoadingBar")||type.equals("ProgressTimer")||type.equals("Slider")) && args.size() > 4) return unquote(args.get(4));
            if(type.equals("CheckBox")&&args.size()>4){boolean checked=callBoolean("CheckBox_setSelected",0,false);return unquote(args.get(checked&&args.size()>5?5:4));}
            if(type.equals("TextAtlas")&&args.size()>5)return unquote(args.get(5));
            String background = callArg(type + "_setBackGroundImage", 0, "");
            if (background.isBlank()) background = callArg("setBackGroundImage", 0, "");
            return unquote(background);
        }
        String text() {
            if (type.equals("Text") && args.size() > 6) return unquote(args.get(6));
            if (type.equals("Button")) return unquote(callArg("Button_setTitleText", 0, ""));
            if (type.equals("RichText")&&args.size()>4) return unquote(args.get(4));
            if (type.equals("TextAtlas")&&args.size()>4)return unquote(args.get(4));
            if(type.equals("BmpText")&&args.size()>5)return unquote(args.get(5));
            if(type.equals("TextInput"))return unquote(callArg("TextInput_setString",0,callArg("TextInput_setPlaceHolder",0,"")));
            return "";
        }
        String textColor() {
            if (type.equals("Text") && args.size() > 5) return unquote(args.get(5));
            if(type.equals("RichText")&&args.size()>7)return unquote(args.get(7));
            if(type.equals("BmpText")&&args.size()>4)return unquote(args.get(4));
            if(type.equals("TextInput"))return unquote(callArg("TextInput_setFontColor",0,"#FFFFFF"));
            return unquote(callArg(type.equals("Button") ? "Button_setTitleColor" : "Text_setTextColor", 0, "#FFFFFF"));
        }
        int fontSize() {
            if (type.equals("Text") && args.size() > 4) return (int)number(args, 4, 16);
            if(type.equals("RichText")&&args.size()>6)return (int)number(args,6,16);
            if(type.equals("TextInput"))return (int)callNumber("TextInput_setFontSize",0,number(args,6,16));
            return (int)callNumber(type.equals("Button") ? "Button_setTitleFontSize" : "Text_setFontSize", 0, 16);
        }
        int argInt(int index,int fallback){return (int)number(args,index,fallback);}
        String argString(int index,String fallback){return index<args.size()?unquote(args.get(index)):fallback;}
        boolean argBoolean(int index,boolean fallback){String value=argString(index,String.valueOf(fallback));return value.equals("1")||Boolean.parseBoolean(value);}
        boolean clipsChildren(){return type.equals("Layout")?argBoolean(6,false):callBoolean(type+"_setClippingEnabled",0,false);}
        boolean callAfter(String first,String second){Call a=calls.get(first),b=calls.get(second);return a!=null&&(b==null||a.line>b.line);}
        String tableValue(int argIndex,String key,String fallback){
            if(argIndex>=args.size())return fallback;Matcher matcher=Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(key)+"\\s*=\\s*([^,}]+)").matcher(args.get(argIndex));return matcher.find()?unquote(matcher.group(1).trim()):fallback;
        }
        int tableInt(int argIndex,String key,int fallback){try{return Integer.parseInt(tableValue(argIndex,key,String.valueOf(fallback)).replaceAll("[^0-9-]",""));}catch(Exception ignored){return fallback;}}
        private static double number(List<String> a, int index, double fallback) {
            if (index >= a.size()) return fallback;
            try {
                String expression = a.get(index)
                        .replaceAll("_V\\s*\\(\\s*[\"']SCREEN_WIDTH[\"']\\s*\\)", "1136")
                        .replaceAll("_V\\s*\\(\\s*[\"']SCREEN_HEIGHT[\"']\\s*\\)", "640")
                        .replaceAll("SL:GetMetaValue\\s*\\(\\s*[\"']SCREEN_WIDTH[\"']\\s*\\)", "1136")
                        .replaceAll("SL:GetMetaValue\\s*\\(\\s*[\"']SCREEN_HEIGHT[\"']\\s*\\)", "640");
                return new Arithmetic(expression).parse();
            } catch (Exception ignored) { return fallback; }
        }
        static String unquote(String value) { return value.replaceAll("^\\[\\[|\\]\\]$", "").replaceAll("^[\"']|[\"']$", ""); }
    }

    private static final class Arithmetic {
        private final String value; private int index;
        Arithmetic(String value) { this.value = value.replace(" ", ""); }
        double parse() { double n = expression(); if (index != value.length()) throw new IllegalArgumentException(); return n; }
        private double expression() { double n = term(); while(index<value.length()){char c=value.charAt(index);if(c!='+'&&c!='-')break;index++;double r=term();n=c=='+'?n+r:n-r;}return n; }
        private double term() { double n = factor(); while(index<value.length()){char c=value.charAt(index);if(c!='*'&&c!='/')break;index++;double r=factor();n=c=='*'?n*r:n/r;}return n; }
        private double factor() { if(index<value.length()&&value.charAt(index)=='('){index++;double n=expression();if(index>=value.length()||value.charAt(index++)!=')')throw new IllegalArgumentException();return n;}int start=index;if(index<value.length()&&(value.charAt(index)=='+'||value.charAt(index)=='-'))index++;while(index<value.length()&&(Character.isDigit(value.charAt(index))||value.charAt(index)=='.'))index++;if(start==index)throw new IllegalArgumentException();return Double.parseDouble(value.substring(start,index)); }
    }
}
