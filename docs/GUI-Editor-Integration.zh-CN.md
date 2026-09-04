# 按目录接入 GUI 可视化编辑器

本功能将 Windows GUI 编辑器接入 IntelliJ-EmmyLua 的文件编辑器体系，同时保持 Lua 语言解析、索引和补全由 EmmyLua 负责。

## 路由规则

在项目设置中配置一个或多个目录后：

- 目录内及其子目录中的 `.lua` 文件，会出现 `GUI 编辑器` 页；
- `GUI 编辑器` 页排在普通 `Text` 页之前；
- 普通 `Text` 页仍然存在，可查看和修改 Lua 源码；
- 配置目录之外的 Lua 文件完全保持 EmmyLua 原来的打开方式；
- 文件即使使用 GUI 编辑器打开，仍会进入 EmmyLua 的 PSI、Stub 和类型索引。

因此，GUI 文件仍能使用项目中的函数、类型和 `Module/BaseClass` 补全，普通 Lua 文件也能引用 GUI 文件中的符号。

## 配置

打开：

```text
Settings / Preferences
→ Languages & Frameworks
→ EmmyLua GUI 编辑器
```

配置项：

1. 启用或关闭目录路由；
2. GUI 编辑器 EXE 的绝对路径；
3. 打开匹配文件时是否自动启动工具；
4. 一个或多个接管目录，支持 `$PROJECT_DIR$` 项目相对路径。

目录匹配采用完整目录边界。例如配置：

```text
$PROJECT_DIR$/script/GUIExport
```

会匹配：

```text
$PROJECT_DIR$/script/GUIExport/panel/main.lua
```

不会误匹配：

```text
$PROJECT_DIR$/script/GUIExportBackup/panel/main.lua
```

## 外部工具约束

当前接入的工具是 Windows .NET Framework WPF 程序，通过以下命令行形式打开文件：

```text
GUI编辑器.exe "D:\项目\GUIExport\界面\main.lua"
```

该工具自身会从文件路径中的 `GUIExport` 目录段推导资源根目录。因此，第一版要求被路由的实际文件路径中包含独立的 `GUIExport` 目录段，否则 IDEA 页会显示明确错误而不启动工具。

建议将 EXE 放置在非系统盘，并确认它可以独立运行。工具关闭或保存后，插件会刷新对应 `VirtualFile`，普通 Text 页和 EmmyLua 索引随之更新。

## 实现结构

- `GuiEditorProjectSettings`：保存项目级 EXE 路径和接管目录；
- `GuiEditorPathMatcher`：无 PSI、无索引访问的快速路径匹配；
- `GuiEditorFileEditorProvider`：只接受配置目录中的 `.lua` 文件；
- `GuiEditorFileEditor`：启动和监控 WPF 编辑器进程，并刷新 VFS；
- `GuiEditorConfigurable`：IDEA 项目设置页。

编辑器 Provider 使用 `PLACE_BEFORE_DEFAULT_EDITOR`，所以只给匹配文件增加一个 GUI 页，不会替换 EmmyLua 的语言文件类型和解析器。

## 当前边界

- 仅 Windows 可启动上传的 EXE；其他系统自动退回普通 Text 编辑；
- WPF 窗口作为独立进程显示，不通过 HWND 强制嵌入 IDEA；
- 动态修改路由后，已经打开的文件需要关闭并重新打开；
- 工具目前只接受路径中包含 `GUIExport` 的文件；
- EXE 本体不提交到源码仓库，用户在设置中选择本机路径。
