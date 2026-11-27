package com.mobilegh.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeOutlineTest {
    @Test fun extractsKotlinSymbolsWithNesting() {
        val src = """
            package demo
            // class IgnoredComment
            sealed class State {
                data class Loaded(val items: List<String>) : State()
                private suspend fun refresh(force: Boolean) {
                }
            }
            fun topLevel() = Unit
        """.trimIndent()
        val symbols = CodeOutline.extract("State.kt", src)
        assertEquals(listOf("State", "Loaded", "refresh", "topLevel"), symbols.map { it.name })
        assertEquals(listOf(0, 1, 1, 0), symbols.map { it.depth })
        assertEquals(3, symbols.first().line)
    }

    @Test fun extractsPythonGoTypeScriptAndRustSymbols() {
        val py = "class Runner:\n    async def step(self):\n        pass\n"
        assertEquals(listOf("Runner", "step"), CodeOutline.extract("run.py", py).map { it.name })

        val go = "type Server struct {\n}\nfunc (s *Server) Start() {}\n"
        assertEquals(listOf("Server", "Start"), CodeOutline.extract("main.go", go).map { it.name })

        val ts = "export interface Config {}\nexport const build = (n: number) => n * 2\n"
        assertEquals(listOf("Config", "build"), CodeOutline.extract("index.ts", ts).map { it.name })

        val rs = "pub struct Engine {}\nimpl Engine {\n    pub async fn tick(&self) {}\n}\n"
        val rsSymbols = CodeOutline.extract("lib.rs", rs)
        assertTrue(rsSymbols.map { it.name }.containsAll(listOf("Engine", "tick")))
    }
}
