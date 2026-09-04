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

import com.tang.intellij.lua.guieditor.GuiEditorPathMatcher
import junit.framework.TestCase

class GuiEditorPathMatcherTest : TestCase() {
    fun testRoutesOnlyDescendants() {
        assertTrue(
            GuiEditorPathMatcher.matchesPath(
                "/project/script/GUIExport/panel/main.lua",
                "/project/script/GUIExport",
                "/project",
                false
            )
        )
        assertFalse(
            GuiEditorPathMatcher.matchesPath(
                "/project/script/GUIExport2/panel/main.lua",
                "/project/script/GUIExport",
                "/project",
                false
            )
        )
    }

    fun testProjectMacro() {
        assertTrue(
            GuiEditorPathMatcher.matchesPath(
                "/project/script/GUIExport/panel/main.lua",
                "\$PROJECT_DIR\$/script/GUIExport",
                "/project",
                false
            )
        )
    }

    fun testWindowsPathsAreCaseInsensitive() {
        assertTrue(
            GuiEditorPathMatcher.matchesPath(
                "D:\\Game\\GUIExport\\Panel\\Main.lua",
                "d:/game/guiexport",
                null,
                true
            )
        )
    }

    fun testGuiExportSegmentDetection() {
        assertTrue(GuiEditorPathMatcher.containsGuiExportSegment("D:/Game/GUIExport/Panel/Main.lua"))
        assertFalse(GuiEditorPathMatcher.containsGuiExportSegment("D:/Game/GUIExportBackup/Panel/Main.lua"))
    }
}
