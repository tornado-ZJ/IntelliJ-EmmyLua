/*
 * Copyright (c) 2026.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.tang.intellij.test.guieditor

import com.tang.intellij.lua.guieditor.GuiLuaDocument
import junit.framework.TestCase

class GuiLuaDocumentTest : TestCase() {
    private val source = """
        local ui = {}
        local _V = function(...) return SL:GetMetaValue(...) end

        function ui.init(parent, __data__, __update__)
            if __update__ then return ui.update(__data__) end
            -- Create Scene
            local Scene = GUI:Layout_Create(parent, "Scene", _V("SCREEN_WIDTH") * 0.5, _V("SCREEN_HEIGHT") * 0.5, _V("SCREEN_WIDTH"), _V("SCREEN_HEIGHT"), false)
            GUI:setAnchorPoint(Scene, 0.50, 0.50)

            -- Create Button_1
            local Button_1 = GUI:Button_Create(Scene, "Button_1", 100.00, 80.00, "res/button.png")
            GUI:setAnchorPoint(Button_1, 0.00, 0.00)
            GUI:setTag(Button_1, 7)
            GUI:FutureSetter(Button_1, runtimeValue)
            local untouched = callback(Button_1)

            ui.update(__data__)
            return Scene
        end

        function ui.update(data)
        end

        return ui
    """.trimIndent() + "\n"

    fun testUnchangedSourceRoundTripsExactly() {
        val document = GuiLuaDocument.parse(source, 1136, 640)
        assertEquals(source, document.serialize())
        assertEquals(2, document.nodes.size)
    }

    fun testScreenExpressionsAndHierarchyAreResolved() {
        val document = GuiLuaDocument.parse(source, 1136, 640)
        val scene = document.findNode("Scene")!!
        val button = document.findNode("Button_1")!!
        assertEquals(568.0, scene.x, 0.01)
        assertEquals(320.0, scene.y, 0.01)
        assertSame(scene, button.parent)
    }

    fun testPropertyEditPreservesUnknownLua() {
        val document = GuiLuaDocument.parse(source, 1136, 640)
        val button = document.findNode("Button_1")!!
        button.setProperty("x", 210.0)
        button.setProperty("opacity", 128.0)
        val result = document.serialize()
        assertTrue(result.contains("GUI:Button_Create(Scene, \"Button_1\", 210, 80"))
        assertTrue(result.contains("GUI:setOpacity(Button_1, 128)"))
        assertTrue(result.contains("GUI:FutureSetter(Button_1, runtimeValue)"))
        assertTrue(result.contains("local untouched = callback(Button_1)"))
    }

    fun testNewWidgetIsInsertedBeforeUpdateCall() {
        val document = GuiLuaDocument.parse(source, 1136, 640)
        val text = document.addNode("Text", document.findNode("Scene"), 200.0, 150.0)
        text.setProperty("name", "Title")
        text.setProperty("text", "活动标题")
        val result = document.serialize()
        val createIndex = result.indexOf("GUI:Text_Create(Scene, \"Title\"")
        val updateIndex = result.indexOf("ui.update(__data__)")
        assertTrue(createIndex >= 0)
        assertTrue(createIndex < updateIndex)
        assertTrue(result.contains("活动标题"))
    }

    fun testDeletingOneWidgetDoesNotDeleteOtherLua() {
        val document = GuiLuaDocument.parse(source, 1136, 640)
        document.removeNode(document.findNode("Button_1")!!)
        val result = document.serialize()
        assertFalse(result.contains("GUI:Button_Create"))
        assertFalse(result.contains("GUI:FutureSetter(Button_1"))
        assertTrue(result.contains("local untouched = callback(Button_1)"))
        assertTrue(result.contains("GUI:Layout_Create"))
    }
}
