package com.tang.intellij.lua.guieditor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Property metadata reconstructed from GUIEditor.ObjInfo and GuiToLua in the
 * original GUI editor.  This registry is deliberately independent from the
 * calls found in an individual Lua file: a widget type always gets the same
 * editor, including properties which have not yet been written to Lua.
 */
final class GuiPropertySchema {
    enum Access { CREATE_ARG, CALL_ARG, TABLE_VALUE, EVENT_VALUE, OBJECT_CONFIG, WIDTH, HEIGHT }
    enum ValueType { TEXT, NUMBER, BOOLEAN, PATH, COLOR, RAW, READ_ONLY }

    record Field(String section, String label, String key, Access access,
                 ValueType valueType, int createArg, String method, int callArg,
                 String defaultValue, List<String> callDefaults) {
        static Field create(String section, String label, String key, ValueType type, int arg, String fallback) {
            return new Field(section, label, key, Access.CREATE_ARG, type, arg, null, -1, fallback, List.of());
        }
        static Field call(String section, String label, String key, ValueType type, String method, int arg,
                          String fallback, String... defaults) {
            return new Field(section, label, key, Access.CALL_ARG, type, -1, method, arg, fallback, List.of(defaults));
        }
        static Field size(String section, String label, String key, boolean width) {
            return new Field(section, label, key, width ? Access.WIDTH : Access.HEIGHT, ValueType.NUMBER,
                    -1, null, -1, "0", List.of());
        }
        static Field table(String section,String label,String key,ValueType type,int arg,String tableKey,String fallback){
            return new Field(section,label,key,Access.TABLE_VALUE,type,arg,tableKey,-1,fallback,List.of());
        }
        static Field event(String label,String key,ValueType type,String part,String fallback){
            return new Field("高级属性",label,key,Access.EVENT_VALUE,type,-1,part,-1,fallback,List.of());
        }
        static Field objectConfig(){
            return new Field("高级属性","object_config","objectConfig",Access.OBJECT_CONFIG,ValueType.RAW,-1,null,-1,"{}",List.of());
        }
        static Field readonly(String section, String label, String key, String fallback) {
            return new Field(section, label, key, Access.CALL_ARG, ValueType.READ_ONLY,
                    -1, null, 0, fallback, List.of());
        }
    }

    private static final List<Field> COMMON = List.of(
            Field.create("属性", "节点名字", "name", ValueType.TEXT, 1, ""),
            Field.call("属性", "中文名称", "chineseName", ValueType.TEXT, "setChineseName", 0, "", ""),

            Field.call("位置与尺寸", "锚点 X", "anchorX", ValueType.NUMBER, "setAnchorPoint", 0, "0", "0", "0"),
            Field.call("位置与尺寸", "锚点 Y", "anchorY", ValueType.NUMBER, "setAnchorPoint", 1, "0", "0", "0"),
            Field.create("位置与尺寸", "坐标 X", "x", ValueType.NUMBER, 2, "0"),
            Field.create("位置与尺寸", "坐标 Y", "y", ValueType.NUMBER, 3, "0"),
            Field.size("位置与尺寸", "宽度", "width", true),
            Field.size("位置与尺寸", "高度", "height", false),

            Field.call("常规", "可见性", "visible", ValueType.BOOLEAN, "setVisible", 0, "true", "true"),
            Field.call("常规", "自适应尺寸", "adaptSize", ValueType.BOOLEAN, "setIgnoreContentAdaptWithSize", 0, "false", "false"),
            Field.call("常规", "交互性", "touch", ValueType.BOOLEAN, "setTouchEnabled", 0, "false", "false"),
            Field.call("常规", "鼠标触摸", "mouse", ValueType.BOOLEAN, "setMouseEnabled", 0, "false", "false"),
            Field.call("常规", "吞噬触摸", "swallow", ValueType.BOOLEAN, "setSwallowTouches", 0, "false", "false"),
            Field.call("常规", "逻辑标签", "tag", ValueType.NUMBER, "setTag", 0, "0", "0"),
            Field.call("常规", "渲染层级", "zOrder", ValueType.NUMBER, "setLocalZOrder", 0, "0", "0"),
            Field.call("常规", "缩放 X", "scaleX", ValueType.NUMBER, "setScaleX", 0, "1", "1"),
            Field.call("常规", "缩放 Y", "scaleY", ValueType.NUMBER, "setScaleY", 0, "1", "1"),
            Field.call("常规", "旋转", "rotation", ValueType.NUMBER, "setRotation", 0, "0", "0"),
            Field.call("常规", "斜切 X", "skewX", ValueType.NUMBER, "setRotationSkewX", 0, "0", "0"),
            Field.call("常规", "斜切 Y", "skewY", ValueType.NUMBER, "setRotationSkewY", 0, "0", "0"),
            Field.call("常规", "水平翻转", "flipX", ValueType.BOOLEAN, "setFlippedX", 0, "false", "false"),
            Field.call("常规", "垂直翻转", "flipY", ValueType.BOOLEAN, "setFlippedY", 0, "false", "false"),
            Field.call("常规", "不透明度", "opacity", ValueType.NUMBER, "setOpacity", 0, "255", "255"),
            Field.call("常规", "子控件跟随透明度", "childOpacity", ValueType.BOOLEAN, "setChildrenCascadeOpacityEnabled", 0, "false", "false"),
            Field.call("常规", "画布节点", "canvasNode", ValueType.BOOLEAN, "setCanvas", -1, "false", "true"),
            Field.call("常规", "遮罩节点", "maskNode", ValueType.BOOLEAN, "setMask", -1, "false", "true")
    );

    private static final Map<String, List<Field>> TYPE_FIELDS = Map.ofEntries(
            Map.entry("Image", List.of(
                    Field.create("图片", "图片样式", "image", ValueType.PATH, 4, ""),
                    Field.call("图片", "九宫格", "image9", ValueType.BOOLEAN, "Image_setScale9Slice", -1, "false", "0", "0", "0", "0"),
                    Field.call("图片", "九宫格 左", "image9Left", ValueType.NUMBER, "Image_setScale9Slice", 0, "0", "0", "0", "0", "0"),
                    Field.call("图片", "九宫格 右", "image9Right", ValueType.NUMBER, "Image_setScale9Slice", 1, "0", "0", "0", "0", "0"),
                    Field.call("图片", "九宫格 上", "image9Top", ValueType.NUMBER, "Image_setScale9Slice", 2, "0", "0", "0", "0", "0"),
                    Field.call("图片", "九宫格 下", "image9Bottom", ValueType.NUMBER, "Image_setScale9Slice", 3, "0", "0", "0", "0", "0"),
                    Field.call("图片", "置灰", "grey", ValueType.BOOLEAN, "Image_setGrey", 0, "false", "false")
            )),
            Map.entry("Button", List.of(
                    Field.create("按钮", "普通图片", "normalImage", ValueType.PATH, 4, ""),
                    Field.call("按钮", "按下图片", "pressedImage", ValueType.PATH, "Button_loadTexturePressed", 0, "", ""),
                    Field.call("按钮", "禁用图片", "disabledImage", ValueType.PATH, "Button_loadTextureDisabled", 0, "", ""),
                    Field.call("按钮", "标题文字", "buttonText", ValueType.TEXT, "Button_setTitleText", 0, "", ""),
                    Field.call("按钮", "标题颜色", "buttonColor", ValueType.COLOR, "Button_setTitleColor", 0, "#FFFFFF", "#FFFFFF"),
                    Field.call("按钮", "标题字号", "buttonFontSize", ValueType.NUMBER, "Button_setTitleFontSize", 0, "16", "16"),
                    Field.call("按钮", "标题字体", "buttonFont", ValueType.PATH, "Button_setTitleFontName", 0, "", ""),
                    Field.call("按钮", "九宫格", "button9", ValueType.BOOLEAN, "Button_setScale9Slice", -1, "false", "0", "0", "0", "0"),
                    Field.call("按钮", "描边", "buttonOutline", ValueType.BOOLEAN, "Button_titleEnableOutline", -1, "false", "\"#000000\"", "1"),
                    Field.call("按钮", "描边颜色", "buttonOutlineColor", ValueType.COLOR, "Button_titleEnableOutline", 0, "#000000", "\"#000000\"", "1"),
                    Field.call("按钮", "描边大小", "buttonOutlineSize", ValueType.NUMBER, "Button_titleEnableOutline", 1, "1", "\"#000000\"", "1")
            )),
            Map.entry("Text", List.of(
                    Field.create("文本", "文字内容", "text", ValueType.TEXT, 6, ""),
                    Field.create("文本", "字体大小", "textSize", ValueType.NUMBER, 4, "16"),
                    Field.create("文本", "文字颜色", "textColor", ValueType.COLOR, 5, "#FFFFFF"),
                    Field.call("文本", "字体路径", "textFont", ValueType.PATH, "Text_setFontName", 0, "", ""),
                    Field.call("文本", "粗体", "textBold", ValueType.BOOLEAN, "Text_enableBold", 0, "false", "false"),
                    Field.call("文本", "下划线", "textUnderline", ValueType.BOOLEAN, "Text_enableUnderline", 0, "false", "false"),
                    Field.call("文本", "水平对齐", "textAlignH", ValueType.NUMBER, "Text_setTextHorizontalAlignment", 0, "0", "0"),
                    Field.call("文本", "垂直对齐", "textAlignV", ValueType.NUMBER, "Text_setTextVerticalAlignment", 0, "0", "0"),
                    Field.call("文本", "行间距", "textLineSpace", ValueType.NUMBER, "Text_setLineSpace", 0, "0", "0"),
                    Field.call("文本", "最大行宽", "textMaxWidth", ValueType.NUMBER, "Text_setMaxLineWidth", 0, "0", "0")
                    ,Field.call("文本", "描边", "textOutline", ValueType.BOOLEAN, "Text_enableOutline", -1, "false", "\"#000000\"", "1")
                    ,Field.call("文本", "描边颜色", "textOutlineColor", ValueType.COLOR, "Text_enableOutline", 0, "#000000", "\"#000000\"", "1")
                    ,Field.call("文本", "描边大小", "textOutlineSize", ValueType.NUMBER, "Text_enableOutline", 1, "1", "\"#000000\"", "1")
            )),
            Map.entry("BmpText", List.of(
                    Field.create("Bmp 文本", "文字内容", "bmpText", ValueType.TEXT, 5, ""),
                    Field.create("Bmp 文本", "文字颜色", "bmpColor", ValueType.COLOR, 4, "#FFFFFF"),
                    Field.call("Bmp 文本", "字体大小", "bmpFontSize", ValueType.NUMBER, "Text_setFontSize", 0, "16", "16"),
                    Field.call("Bmp 文本", "行间距", "bmpLineSpace", ValueType.NUMBER, "Text_setLineSpace", 0, "0", "0")
            )),
            Map.entry("RichText", List.of(
                    Field.create("富文本", "文字内容", "richText", ValueType.TEXT, 4, ""),
                    Field.create("富文本", "最大宽度", "richWidth", ValueType.NUMBER, 5, "100"),
                    Field.create("富文本", "字体大小", "richSize", ValueType.NUMBER, 6, "16"),
                    Field.create("富文本", "默认颜色", "richColor", ValueType.COLOR, 7, "#FFFFFF"),
                    Field.create("富文本", "行间距", "richLineSpace", ValueType.NUMBER, 8, "4")
            )),
            Map.entry("TextInput", List.of(
                    Field.create("输入框", "输入宽度", "inputWidth", ValueType.NUMBER, 4, "200"),
                    Field.create("输入框", "输入高度", "inputHeight", ValueType.NUMBER, 5, "30"),
                    Field.create("输入框", "默认字号", "inputBaseSize", ValueType.NUMBER, 6, "16"),
                    Field.call("输入框", "文字内容", "inputText", ValueType.TEXT, "TextInput_setString", 0, "", ""),
                    Field.call("输入框", "占位文字", "inputPlaceholder", ValueType.TEXT, "TextInput_setPlaceHolder", 0, "", ""),
                    Field.call("输入框", "字体大小", "inputSize", ValueType.NUMBER, "TextInput_setFontSize", 0, "16", "16"),
                    Field.call("输入框", "字体颜色", "inputColor", ValueType.COLOR, "TextInput_setFontColor", 0, "#FFFFFF", "#FFFFFF"),
                    Field.call("输入框", "最大长度", "inputLength", ValueType.NUMBER, "TextInput_setMaxLength", 0, "0", "0"),
                    Field.call("输入框", "输入模式", "inputMode", ValueType.NUMBER, "TextInput_setInputMode", 0, "0", "0"),
                    Field.call("输入框", "输入标志", "inputFlag", ValueType.NUMBER, "TextInput_setInputFlag", 0, "0", "0")
            )),
            Map.entry("LoadingBar", List.of(
                    Field.create("进度条", "进度图片", "loadingImage", ValueType.PATH, 4, ""),
                    Field.create("进度条", "方向", "loadingDirection", ValueType.NUMBER, 5, "0"),
                    Field.call("进度条", "百分比", "loadingPercent", ValueType.NUMBER, "LoadingBar_setPercent", 0, "100", "100"),
                    Field.call("进度条", "颜色", "loadingColor", ValueType.COLOR, "LoadingBar_setColor", 0, "#FFFFFF", "#FFFFFF")
            )),
            Map.entry("CheckBox", List.of(
                    Field.create("复选框", "背景图片", "checkBg", ValueType.PATH, 4, ""),
                    Field.create("复选框", "选中图片", "checkFront", ValueType.PATH, 5, ""),
                    Field.call("复选框", "默认选中", "checked", ValueType.BOOLEAN, "CheckBox_setSelected", 0, "false", "false"),
                    Field.call("复选框", "分组", "checkGroup", ValueType.TEXT, "CheckBox_setGroup", 0, "", "")
            )),
            Map.entry("Slider", List.of(
                    Field.create("滑动条", "背景图片", "sliderBack", ValueType.PATH, 4, ""),
                    Field.create("滑动条", "进度图片", "sliderProgress", ValueType.PATH, 5, ""),
                    Field.create("滑动条", "滑块图片", "sliderBall", ValueType.PATH, 6, ""),
                    Field.call("滑动条", "百分比", "sliderPercent", ValueType.NUMBER, "Slider_setPercent", 0, "0", "0")
            )),
            Map.entry("ProgressTimer", List.of(
                    Field.create("进度图", "图片路径", "progressImage", ValueType.PATH, 4, ""),
                    Field.call("进度图", "百分比", "progressPercent", ValueType.NUMBER, "ProgressTimer_setPercentage", 0, "100", "100"),
                    Field.call("进度图", "反向", "progressReverse", ValueType.BOOLEAN, "ProgressTimer_setReverseDirection", 0, "false", "false")
            )),
            Map.entry("ListView", List.of(
                    Field.call("列表容器", "回弹", "listBounce", ValueType.BOOLEAN, "ListView_setBounceEnabled", 0, "false", "false"),
                    Field.call("列表容器", "方向", "listDirection", ValueType.NUMBER, "ListView_setDirection", 0, "1", "1"),
                    Field.call("列表容器", "对齐方式", "listGravity", ValueType.NUMBER, "ListView_setGravity", 0, "0", "0"),
                    Field.call("列表容器", "元素间距", "listMargin", ValueType.NUMBER, "ListView_setItemsMargin", 0, "0", "0"),
                    Field.call("列表容器", "鼠标滚动比例", "listMouseScroll", ValueType.NUMBER, "ListView_addMouseScrollPercent", 0, "0", "0")
            )),
            Map.entry("ScrollView", List.of(
                    Field.call("滚动容器", "回弹", "scrollBounce", ValueType.BOOLEAN, "ScrollView_setBounceEnabled", 0, "false", "false"),
                    Field.call("滚动容器", "方向", "scrollDirection", ValueType.NUMBER, "ScrollView_setDirection", 0, "1", "1"),
                    Field.call("滚动容器", "内部宽度", "scrollInnerW", ValueType.NUMBER, "ScrollView_setInnerContainerSize", 0, "0", "0", "0"),
                    Field.call("滚动容器", "内部高度", "scrollInnerH", ValueType.NUMBER, "ScrollView_setInnerContainerSize", 1, "0", "0", "0")
            )),
            Map.entry("TableView", List.of(
                    Field.call("表格容器", "回弹", "tableBounce", ValueType.BOOLEAN, "TableView_setBounceEnabled", 0, "false", "false"),
                    Field.call("表格容器", "方向", "tableDirection", ValueType.NUMBER, "TableView_setDirection", 0, "1", "1"),
                    Field.call("表格容器", "虚拟列表", "tableVirtual", ValueType.BOOLEAN, "TableView_setVirtual", 0, "false", "false")
            )),
            Map.entry("TextAtlas", List.of(
                    Field.create("艺术字", "显示内容", "atlasText", ValueType.TEXT, 4, "0"),
                    Field.create("艺术字", "图片路径", "atlasImage", ValueType.PATH, 5, ""),
                    Field.create("艺术字", "单字宽度", "atlasWidth", ValueType.NUMBER, 6, "0"),
                    Field.create("艺术字", "单字高度", "atlasHeight", ValueType.NUMBER, 7, "0"),
                    Field.create("艺术字", "起始字符", "atlasStart", ValueType.TEXT, 8, "0"),
                    Field.call("艺术字", "字间距", "atlasSpace", ValueType.NUMBER, "TextAtlas_setWordSpace", 0, "0", "0")
            )),
            Map.entry("FxEffect", List.of(
                    Field.create("3D 特效", "特效 ID", "fxId", ValueType.NUMBER, 4, "0"),
                    Field.create("3D 特效", "播放速度", "fxSpeed", ValueType.NUMBER, 5, "1"),
                    Field.call("3D 特效", "观察距离", "fxDistance", ValueType.NUMBER, "FxEffect_setDistance", 0, "0", "0")
            )),
            Map.entry("Effect", List.of(
                    Field.create("特效", "特效类型", "effectType", ValueType.NUMBER, 4, "0"),
                    Field.create("特效", "特效 ID", "effectId", ValueType.NUMBER, 5, "0"),
                    Field.create("特效", "性别", "effectSex", ValueType.NUMBER, 6, "0"),
                    Field.create("特效", "动作", "effectAction", ValueType.NUMBER, 7, "0"),
                    Field.create("特效", "方向", "effectDirection", ValueType.NUMBER, 8, "0"),
                    Field.create("特效", "播放速度", "effectSpeed", ValueType.NUMBER, 9, "1")
            )),
            Map.entry("UIModel", List.of(
                    Field.create("3D 对象", "性别", "uiModelSex", ValueType.NUMBER, 4, "0"),
                    Field.create("3D 对象", "模型数据", "uiModelData", ValueType.RAW, 5, "{}"),
                    Field.create("3D 对象", "模型缩放", "uiModelScale", ValueType.NUMBER, 6, "1"),
                    Field.call("3D 对象", "观察距离", "uiModelDistance", ValueType.NUMBER, "UIModel_setDistance", 0, "0", "0")
            )),
            Map.entry("ParticleEffect", List.of(
                    Field.create("粒子特效", "资源路径", "particlePath", ValueType.PATH, 4, "")
            )),
            Map.entry("Frames", List.of(
                    Field.create("序列帧动画", "图片前缀", "framesPath", ValueType.PATH, 4, ""),
                    Field.create("序列帧动画", "文件后缀", "framesSuffix", ValueType.TEXT, 5, ".png"),
                    Field.create("序列帧动画", "起始编号", "framesStart", ValueType.NUMBER, 6, "1"),
                    Field.create("序列帧动画", "结束编号", "framesEnd", ValueType.NUMBER, 7, "1"),
                    Field.table("序列帧动画", "帧数量", "framesCount", ValueType.NUMBER, 8, "count", "1"),
                    Field.table("序列帧动画", "帧间隔(ms)", "framesSpeed", ValueType.NUMBER, 8, "speed", "100"),
                    Field.table("序列帧动画", "循环次数", "framesLoop", ValueType.NUMBER, 8, "loop", "-1"),
                    Field.table("序列帧动画", "结束隐藏", "framesFinishHide", ValueType.NUMBER, 8, "finishhide", "0")
            )),
            Map.entry("SpineAnim", List.of(
                    Field.create("骨骼动画", "骨骼文件", "spineJson", ValueType.PATH, 4, ""),
                    Field.create("骨骼动画", "图集文件", "spineAtlas", ValueType.PATH, 5, ""),
                    Field.create("骨骼动画", "轨道", "spineTrack", ValueType.NUMBER, 6, "0"),
                    Field.create("骨骼动画", "动画名称", "spineAnimation", ValueType.TEXT, 7, ""),
                    Field.create("骨骼动画", "循环", "spineLoop", ValueType.BOOLEAN, 8, "true")
            )),
            Map.entry("GradientColorText", List.of(
                    Field.call("渐变字", "渐变颜色", "gradientColors", ValueType.RAW, "Text_gradientColor", 0, "{}", "{}")
            )),
            Map.entry("ItemShow", List.of(
                    Field.table("道具框", "物品 ID", "itemId", ValueType.NUMBER, 4, "index", "1"),
                    Field.table("道具框", "数量", "itemCount", ValueType.NUMBER, 4, "count", "1"),
                    Field.table("道具框", "显示外观", "itemLook", ValueType.BOOLEAN, 4, "look", "true"),
                    Field.table("道具框", "显示背景", "itemBackground", ValueType.BOOLEAN, 4, "bgVisible", "true"),
                    Field.table("道具框", "颜色 ID", "itemColor", ValueType.NUMBER, 4, "color", "255"),
                    Field.table("道具框", "隐藏锁定提示", "itemNoLockTips", ValueType.BOOLEAN, 4, "noLockTips", "false"),
                    Field.table("道具框", "显示模型特效", "itemModelEffect", ValueType.BOOLEAN, 4, "showModelEffect", "false"),
                    Field.table("道具框", "只显示特效", "itemOnlySfx", ValueType.BOOLEAN, 4, "onlyShowSFX", "false"),
                    Field.table("道具框", "不吞噬触摸", "itemNoSwallow", ValueType.BOOLEAN, 4, "noSwallow", "false"),
                    Field.table("道具框", "关闭鼠标提示", "itemNoMouseTips", ValueType.BOOLEAN, 4, "noMouseTips", "false")
            )),
            Map.entry("EquipShow", List.of(
                    Field.create("装备框", "装备位置", "equipPosition", ValueType.NUMBER, 4, "0"),
                    Field.create("装备框", "英雄装备", "equipHero", ValueType.BOOLEAN, 5, "false"),
                    Field.table("装备框", "显示背景", "equipBackground", ValueType.BOOLEAN, 6, "bgVisible", "true"),
                    Field.table("装备框", "允许双击脱下", "equipTakeOff", ValueType.BOOLEAN, 6, "doubleTakeOff", "false"),
                    Field.table("装备框", "显示提示", "equipTips", ValueType.BOOLEAN, 6, "look", "true"),
                    Field.table("装备框", "允许移动", "equipMovable", ValueType.BOOLEAN, 6, "movable", "false"),
                    Field.table("装备框", "显示星级", "equipStar", ValueType.BOOLEAN, 6, "starLv", "false"),
                    Field.table("装备框", "查看角色", "equipLookPlayer", ValueType.BOOLEAN, 6, "lookPlayer", "false"),
                    Field.table("装备框", "显示模型特效", "equipModelEffect", ValueType.BOOLEAN, 6, "showModelEffect", "false"),
                    Field.call("装备框", "自动更新", "equipAutoUpdate", ValueType.BOOLEAN, "EquipShow_setAutoUpdate", 0, "false", "false")
            )),
            Map.entry("CostItem", List.of(
                    Field.table("消耗组件", "物品 ID", "costItemId", ValueType.NUMBER, 4, "itemId", "1"),
                    Field.table("消耗组件", "数量", "costItemCount", ValueType.NUMBER, 4, "itemCount", "1"),
                    Field.table("消耗组件", "图标缩放", "costItemScale", ValueType.NUMBER, 4, "itemScale", "1"),
                    Field.table("消耗组件", "标题文字", "costTitle", ValueType.TEXT, 4, "titleText", ""),
                    Field.table("消耗组件", "字体大小", "costFontSize", ValueType.NUMBER, 4, "fontSize", "16"),
                    Field.table("消耗组件", "简写数量", "costSimpleNumber", ValueType.BOOLEAN, 4, "simplenum", "false")
            )),
            Map.entry("ItemBox", List.of(
                    Field.create("物品放入框", "物品 ID", "itemBoxId", ValueType.TEXT, 4, ""),
                    Field.create("物品放入框", "背景显示", "itemBoxBg", ValueType.BOOLEAN, 5, "true")
            )),
            Map.entry("RedDot", List.of(
                    Field.call("红点", "组 ID", "redGid", ValueType.NUMBER, "RedDot_setGID", 0, "0", "0"),
                    Field.call("红点", "绑定条件 ID", "redCondition", ValueType.NUMBER, "RedDot_setBindConditionID", 0, "0", "0")
            )),
            Map.entry("ScrollText", List.of(
                    Field.call("滚动文本", "水平对齐", "scrollTextAlign", ValueType.NUMBER, "ScrollText_setHorizontalAlignment", 0, "0", "0")
            ))
    );

    private static final List<Field> ADVANCED = List.of(
            Field.objectConfig(),
            Field.event("点击事件脚本", "eventBody", ValueType.RAW, "body", "")
    );

    private static final List<String> ALL_TYPES = List.of(
            "Node","Layout","Image","Button","Text","BmpText","RichText","GradientColorText","TextInput",
            "LoadingBar","ProgressTimer","ListView","ScrollView","PageView","TableView","CheckBox","Slider",
            "Effect","FxEffect","ItemShow","EquipShow","CostItem","ItemBox","UIModel","ParticleEffect","Frames",
            "SpineAnim","Spine38Anim","TextAtlas","ScrollText","RedDot","LoadExport"
    );

    static List<Field> forType(String type) {
        List<Field> result = new ArrayList<>(COMMON);
        if (List.of("Layout", "ListView", "ScrollView", "PageView", "TableView").contains(type)) {
            result.add(Field.call("容器","显示背景","backgroundEnabled",ValueType.BOOLEAN,type+"_setBackGroundColorType",-1,"false","1"));
            result.add(Field.call("容器","背景图片","background",ValueType.PATH,type+"_setBackGroundImage",0,"",""));
            result.add(Field.call("容器","背景颜色类型","backgroundType",ValueType.NUMBER,type+"_setBackGroundColorType",0,"0","0"));
            result.add(Field.call("容器","背景颜色","backgroundColor",ValueType.COLOR,type+"_setBackGroundColor",0,"#FFFFFF","\"#FFFFFF\""));
            result.add(Field.call("容器","背景透明度","backgroundOpacity",ValueType.NUMBER,type+"_setBackGroundColorOpacity",0,"255","255"));
            if(type.equals("Layout"))result.add(Field.create("容器","裁剪内容","clip",ValueType.BOOLEAN,6,"false"));
            else result.add(Field.call("容器","裁剪内容","clip",ValueType.BOOLEAN,type+"_setClippingEnabled",0,"false","false"));
            result.add(Field.call("容器","背景九宫格","background9",ValueType.BOOLEAN,type+"_setBackGroundImageScale9Slice",-1,"false","0","0","0","0"));
        }
        result.addAll(TYPE_FIELDS.getOrDefault(type,
                type.equals("Spine38Anim") ? TYPE_FIELDS.getOrDefault("SpineAnim", List.of()) : List.of()));
        result.addAll(ADVANCED);
        return result;
    }

    static List<Field> contextMenuCandidates() {
        LinkedHashMap<String,Field> result=new LinkedHashMap<>();
        for(String type:ALL_TYPES)for(Field field:forType(type))
            if(field.valueType()!=ValueType.READ_ONLY)result.putIfAbsent(field.key(),field);
        return new ArrayList<>(result.values());
    }

    private GuiPropertySchema() { }
}
