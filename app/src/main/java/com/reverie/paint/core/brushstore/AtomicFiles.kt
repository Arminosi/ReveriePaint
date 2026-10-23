package com.reverie.paint.core.brushstore

import java.io.File

/** 笔刷存储的原子写：先写同目录 .tmp 再 rename，避免进程被杀时留下半个文件 */
object AtomicFiles {
    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { it.write(bytes); it.fd.sync() }
        if (target.exists() && !target.canWrite()) target.setWritable(true)
        if (!tmp.renameTo(target)) {
            // 部分文件系统 rename 不覆盖已存在目标
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                throw java.io.IOException("atomic rename failed: $target")
            }
        }
    }

    fun writeText(target: File, text: String) = write(target, text.toByteArray(Charsets.UTF_8))
}
