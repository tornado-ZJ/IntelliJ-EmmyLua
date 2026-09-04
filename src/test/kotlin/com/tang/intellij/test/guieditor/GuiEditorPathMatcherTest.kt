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
                "/project/script/gui/panel/main.lua",
                "/project/script/gui",
                "/project",
                false
            )
        )
        assertFalse(
            GuiEditorPathMatcher.matchesPath(
                "/project/script/gui-backup/panel/main.lua",
                "/project/script/gui",
                "/project",
                false
            )
        )
    }

    fun testProjectMacroAndRelativePath() {
        assertTrue(
            GuiEditorPathMatcher.matchesPath(
                "/project/script/gui/panel/main.lua",
                "\$PROJECT_DIR\$/script/gui",
                "/project",
                false
            )
        )
        assertTrue(
            GuiEditorPathMatcher.matchesPath(
                "/project/script/gui/panel/main.lua",
                "script/gui",
                "/project",
                false
            )
        )
    }

    fun testWindowsPathsAreCaseInsensitiveAndBoundarySafe() {
        assertTrue(
            GuiEditorPathMatcher.matchesPath(
                "D:\\Game\\Gui\\Panel\\Main.lua",
                "d:/game/gui",
                null,
                true
            )
        )
        assertFalse(
            GuiEditorPathMatcher.matchesPath(
                "D:\\Game\\GuiOld\\Panel\\Main.lua",
                "d:/game/gui",
                null,
                true
            )
        )
    }

    fun testDotSegmentsAreNormalized() {
        assertTrue(
            GuiEditorPathMatcher.matchesPath(
                "/project/script/gui/panel/main.lua",
                "/project/script/tmp/../gui/./",
                "/project",
                false
            )
        )
    }
}
