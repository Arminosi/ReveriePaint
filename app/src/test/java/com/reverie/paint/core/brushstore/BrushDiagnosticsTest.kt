package com.reverie.paint.core.brushstore

import com.reverie.paint.core.DiagnosticsManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrushDiagnosticsTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("bdiag").toFile()
        BrushDiagnostics.reset()
        BrushDiagnostics.init(dir)
        BrushDiagnostics.clock = { 0L }
    }

    @After
    fun tearDown() {
        BrushDiagnostics.reset()
        dir.deleteRecursively()
    }

    @Test
    fun `事件格式为一行 event 加 k=v，含空格的值加引号`() {
        val line = BrushDiagnostics.format("select", listOf("id" to "builtin:a", "name" to "b c", "n" to 3, "x" to null))
        val body = line.substringAfter(' ').substringAfter(' ') // 去掉日期与时间
        assertEquals("select id=builtin:a name=\"b c\" n=3 x=null", body)
        val multi = BrushDiagnostics.format("e", listOf("v" to "a\nb"))
        assertFalse(multi.contains('\n'))
    }

    @Test
    fun `log 写入 brush log 且 tail 能读回`() {
        BrushDiagnostics.log("build", "id" to "imp:1", "ms" to 4)
        val f = File(dir, "brush_diag/brush.log")
        assertTrue(f.exists())
        assertTrue(f.readText().contains("build id=imp:1 ms=4"))
        assertTrue(BrushDiagnostics.tail().contains("build id=imp:1 ms=4"))
    }

    @Test
    fun `超过 512KB 滚动到 brush log 1`() {
        val big = "x".repeat(1000)
        repeat(600) { BrushDiagnostics.log("fill", "i" to it, "pad" to big) }
        val f = File(dir, "brush_diag/brush.log")
        val rolled = File(dir, "brush_diag/brush.log.1")
        assertTrue(rolled.exists())
        assertTrue(f.length() <= BrushDiagnostics.MAX_BYTES)
        assertTrue(rolled.length() <= BrushDiagnostics.MAX_BYTES)
        // tail 截断在行首
        val t = BrushDiagnostics.tail(5000)
        assertTrue(t.length <= 5000)
        assertTrue(t.startsWith("1970-") || t.matches(Regex("(?s)^\\d{4}-.*")))
    }

    @Test
    fun `报告追加前经过现有脱敏`() {
        BrushDiagnostics.log("import", "src" to "https://user:hunter2@example.com/a.kpp", "token" to "abcdef123")
        val sanitized = DiagnosticsManager.sanitize(BrushDiagnostics.tail())
        assertFalse(sanitized.contains("hunter2"))
        assertFalse(sanitized.contains("abcdef123"))
    }

    @Test
    fun `dumpPreset 写出 md5 覆盖与有效 XML`() {
        val out = BrushDiagnostics.dumpPreset("imp:abc", byteArrayOf(1), byteArrayOf(2), "{}", "<Preset/>")!!
        assertEquals("imp_abc", out.name)
        assertEquals(BrushDiagnostics.md5(byteArrayOf(1)), File(out, "snapshot.md5").readText())
        assertEquals("<Preset/>", File(out, "effective.xml").readText())
    }

    @Test
    fun `未 init 时只发 logcat 不抛`() {
        BrushDiagnostics.reset()
        BrushDiagnostics.log("noop")
        assertEquals("", BrushDiagnostics.tail())
    }
}
