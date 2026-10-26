/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentFormatSnifferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sniff psd format`() {
        val file = tmp.newFile("test_psd")
        file.writeBytes(byteArrayOf(0x38, 0x42, 0x50, 0x53, 0x00, 0x01))
        assertEquals("psd", sniffFileFormat(file))
    }

    @Test
    fun `sniff png format`() {
        val file = tmp.newFile("test_png")
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        assertEquals("png", sniffFileFormat(file))
    }

    @Test
    fun `sniff jpeg format`() {
        val file = tmp.newFile("test_jpg")
        file.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        assertEquals("jpg", sniffFileFormat(file))
    }

    @Test
    fun `sniff kra zip format`() {
        val file = tmp.newFile("test_kra")
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("maindoc.xml"))
            zos.write("<IMAGE width=\"100\" height=\"100\"/>".toByteArray())
            zos.closeEntry()
        }
        assertEquals("kra", sniffFileFormat(file))
    }

    @Test
    fun `sniff revp zip format`() {
        val file = tmp.newFile("test_revp")
        ZipOutputStream(FileOutputStream(file)).use { zos ->
            zos.putNextEntry(ZipEntry("meta.json"))
            zos.write("{}".toByteArray())
            zos.closeEntry()
        }
        assertEquals("revp", sniffFileFormat(file))
    }

    @Test
    fun `empty or short file returns null`() {
        val empty = tmp.newFile("empty")
        assertNull(sniffFileFormat(empty))

        val short = tmp.newFile("short")
        short.writeBytes(byteArrayOf(0x50, 0x4B))
        assertNull(sniffFileFormat(short))
    }
}
