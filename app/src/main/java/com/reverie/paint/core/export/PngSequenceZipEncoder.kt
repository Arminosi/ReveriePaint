/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.core.export

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * PNG 序列帧 Zip 打包器。
 * 将动画中的每一帧以序列编号 (如 frame_0000.png, frame_0001.png) 无损打包进 Zip 归档。
 * 使用 BufferedOutputStream 与 Deflater.NO_COMPRESSION 消除二次 Deflate 压缩开销。
 */
internal class PngSequenceZipEncoder {
    private var zipOut: ZipOutputStream? = null
    private var frameIndex = 0

    /** 开始写入 Zip 文件 */
    fun start(outputFile: File): Boolean {
        return try {
            val fos = FileOutputStream(outputFile)
            val bos = BufferedOutputStream(fos, 64 * 1024)
            val zos = ZipOutputStream(bos)
            zos.setLevel(Deflater.NO_COMPRESSION)
            zipOut = zos
            frameIndex = 0
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 写入一帧或重复帧 PNG */
    fun addFrame(
        bitmap: Bitmap,
        isCancelled: () -> Boolean = { false },
        repeatCount: Int = 1,
    ): Boolean {
        if (isCancelled()) return false
        val zip = zipOut ?: return false
        val count = repeatCount.coerceAtLeast(1)
        return try {
            if (count == 1) {
                val entryName = String.format(Locale.US, "frame_%04d.png", frameIndex)
                val entry = ZipEntry(entryName)
                zip.putNextEntry(entry)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                frameIndex++
            } else {
                // 重复帧 (如结尾定格): 单次压缩 PNG 字节并复用，批量写入连续 Entry
                val baos = ByteArrayOutputStream(bitmap.byteCount / 4)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val pngBytes = baos.toByteArray()
                for (i in 0 until count) {
                    if (isCancelled()) return false
                    val entryName = String.format(Locale.US, "frame_%04d.png", frameIndex)
                    val entry = ZipEntry(entryName)
                    zip.putNextEntry(entry)
                    zip.write(pngBytes)
                    zip.closeEntry()
                    frameIndex++
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 完成 Zip 归档写入并关闭流 */
    fun finish(): Boolean {
        val zip = zipOut ?: return false
        return try {
            zip.finish()
            zip.close()
            zipOut = null
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 取消导出时关闭未完成的 Zip 流（幂等）。 */
    fun abort() {
        try {
            zipOut?.close()
        } catch (_: Exception) {
        }
        zipOut = null
    }
}
