package com.reverie.paint.core.brushstore

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SnapshotImmutabilityTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("snap").toFile()
        PresetSnapshotStore.open(root)
    }

    @After
    fun tearDown() {
        root.walkBottomUp().forEach { it.setWritable(true) }
        root.deleteRecursively()
    }

    @Test
    fun `导入快照写入后只读且同 id 不能再写`() {
        val id = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(id, "kpp", byteArrayOf(1, 2, 3), JSONObject().put("pack", "a.zip"))
        val f = PresetSnapshotStore.snapshotFile(id)!!
        assertFalse(f.canWrite())
        assertArrayEquals(byteArrayOf(1, 2, 3), PresetSnapshotStore.read(id))
        assertEquals("a.zip", PresetSnapshotStore.source(id)!!.getString("pack"))
        var threw = false
        try {
            PresetSnapshotStore.putImported(id, "kpp", byteArrayOf(9), JSONObject())
        } catch (_: PresetSnapshotStore.SnapshotExistsException) {
            threw = true
        }
        assertTrue(threw)
        assertArrayEquals(byteArrayOf(1, 2, 3), PresetSnapshotStore.read(id))
    }

    @Test
    fun `出厂快照按版本目录安装，CURRENT 决定解析`() {
        PresetSnapshotStore.installFactory("v1", mapOf("a.kpp" to byteArrayOf(1), "m.myb" to byteArrayOf(7)))
        assertNull(PresetSnapshotStore.read("builtin:a"))
        PresetSnapshotStore.setCurrentFactoryVersion("v1")
        assertArrayEquals(byteArrayOf(1), PresetSnapshotStore.read("builtin:a"))
        assertArrayEquals(byteArrayOf(7), PresetSnapshotStore.read("builtin:m"))
        assertFalse(PresetSnapshotStore.snapshotFile("builtin:a")!!.canWrite())
        // 同版本重复安装不改写已有字节
        PresetSnapshotStore.installFactory("v1", mapOf("a.kpp" to byteArrayOf(5)))
        assertArrayEquals(byteArrayOf(1), PresetSnapshotStore.read("builtin:a"))
        PresetSnapshotStore.installFactory("v2", mapOf("a.kpp" to byteArrayOf(2)))
        PresetSnapshotStore.setCurrentFactoryVersion("v2")
        PresetSnapshotStore.pruneFactory(setOf("v2"))
        assertArrayEquals(byteArrayOf(2), PresetSnapshotStore.read("builtin:a"))
        assertFalse(File(root, "snapshots/factory/v1").exists())
    }

    @Test
    fun `删除导入快照后不可再解析`() {
        val id = BrushIdentity.newImportedId()
        PresetSnapshotStore.putImported(id, "myb", byteArrayOf(4), JSONObject())
        PresetSnapshotStore.removeImported(id)
        assertNull(PresetSnapshotStore.read(id))
    }
}
