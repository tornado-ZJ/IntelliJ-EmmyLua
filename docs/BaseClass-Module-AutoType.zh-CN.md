# BaseClass 自动聚合到 Module 类型

本分支为 IntelliJ-EmmyLua 增加一套项目级静态推导规则：项目中的每个直接调用
`BaseClass("模块名")`，都会自动成为 `---@class Module` 的虚拟字段。

## 使用方式

项目只需要声明一次 `Module`：

```lua
---@class Module
Module = Module or {}
```

模块文件不需要额外编写 `---@type` 或 `---@class`：

```lua
local m = BaseClass("一键满血")

---@param actor playerObj
function m.main(actor)
end

function m.refresh()
end

return m
```

IDEA 中输入：

```lua
Module.
```

补全列表会出现 `一键满血`。继续输入：

```lua
Module.一键满血.
```

可以补全 `main`、`refresh`，以及 `BaseClass` 上声明的继承成员。

运行环境不接受中文点标识符时，实际代码可写成：

```lua
Module["一键满血"].main(actor)
```

两种访问方式使用同一套静态字段类型。

## 类型推导方式

插件会为每个静态模块名创建独立的合成类型：

```text
$BaseClassModule$<模块名编码> extends BaseClass
```

对于没有显式注解的局部变量，虚拟字段类型同时合并接收变量的匿名类型：

```text
合成 BaseClass 子类型
    union
local m 的 EmmyLua 匿名类型
```

因此：

- 合成类型提供 `BaseClass` 的基类成员；
- 匿名类型提供 `m.main`、`m.xxx`、`m:xxx` 等模块自身成员；
- 不同模块的成员不会因为共同返回 `BaseClass` 而混在一起；
- 显式 `---@class` 或 `---@type` 仍然具有更高优先级；
- 虚拟字段可以导航到对应的 `BaseClass("模块名")` 注册位置。

## 当前识别范围

支持：

```lua
local m = BaseClass("静态字符串")
```

第一个参数也可以是单引号字符串或 Lua 长字符串。

暂不精确识别：

```lua
local m = BaseClass(dynamicName)
local m = CreateModule("名称") -- 包装函数或别名
m[dynamicKey] = function() end
```

模块名重复时，按照文件路径和代码位置稳定选择第一处注册。

## 索引与缓存

模块列表来自项目范围内的 Lua 文件，并按文件修改时间增量刷新。IDEA 正在 Dumb Mode
或重新建立索引时，动态字段会暂时不可用。

安装新构建后建议执行：

```text
File → Invalidate Caches… → Invalidate and Restart
```

等待 Lua 索引完成后再测试补全。

## 版本与构建

- 基础版本：IntelliJ-EmmyLua `v1.4.26`
- 基础提交：`7f72caa2bec7287165bd704a3700ffb331561749`
- 自定义版本：`1.4.26.3-IDEA2026.2`
- IDEA 兼容范围：build `253` 至 `262.*`
- Java 目标字节码：21

执行：

```bash
./gradlew buildPlugin
```

即可生成 IDEA 的插件安装 ZIP。
