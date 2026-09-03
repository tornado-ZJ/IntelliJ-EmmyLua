/*
 * Copyright (c) 2026.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.test.completion

class TestBaseClassModule : TestCompletionBase() {

    fun `test BaseClass registrations are exposed on Module`() {
        doTest(
            """
            --- base.lua
            ---@class BaseClass
            ---@field inherited fun()
            local BaseClassType = {}

            ---@return BaseClass
            function BaseClass(name)
                return BaseClassType
            end

            ---@class Module
            Module = {}

            --- heal.lua
            local m = BaseClass("一键满血")

            function m.main(actor)
            end

            --- use.lua
            Module.--[[caret]]
            """
        ) { lookupStrings ->
            assertTrue(lookupStrings.contains("一键满血"))
        }
    }

    fun `test Module registration exposes local and inherited members`() {
        doTest(
            """
            --- base.lua
            ---@class BaseClass
            ---@field inherited fun()
            local BaseClassType = {}

            ---@return BaseClass
            function BaseClass(name)
                return BaseClassType
            end

            ---@class Module
            Module = {}

            --- heal.lua
            local m = BaseClass("一键满血")

            function m.main(actor)
            end

            function m.refresh()
            end

            --- use.lua
            Module.一键满血.--[[caret]]
            """
        ) { lookupStrings ->
            assertTrue(lookupStrings.containsAll(listOf("main", "refresh", "inherited")))
        }
    }

    fun `test BaseClass module members stay isolated`() {
        doTest(
            """
            --- base.lua
            ---@class BaseClass
            local BaseClassType = {}

            ---@return BaseClass
            function BaseClass(name)
                return BaseClassType
            end

            ---@class Module
            Module = {}

            --- first.lua
            local first = BaseClass("first")

            function first.onlyFirst()
            end

            --- second.lua
            local second = BaseClass("second")

            function second.onlySecond()
            end

            --- use.lua
            Module.first.--[[caret]]
            """
        ) { lookupStrings ->
            assertTrue(lookupStrings.contains("onlyFirst"))
            assertFalse(lookupStrings.contains("onlySecond"))
        }
    }
}
