package com.mobilechat.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

class AttachmentStore(private val context: Context) {
    private val directory = File(context.filesDir, "attachments").apply { mkdirs() }
    private val cameraDirectory = File(context.cacheDir, "camera").apply { mkdirs() }
    fun createCameraPhoto(): File = File.createTempFile("photo-", ".jpg", cameraDirectory)
    fun cameraPhoto(name: String): File {
        require(name.startsWith("photo-") && name.endsWith(".jpg") && File(name).name == name) { "无效照片路径" }
        return File(cameraDirectory, name)
    }
    fun cameraUri(photo: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", photo)
    fun file(id: String): File {
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "无效附件标识" }
        return File(directory, id)
    }
    fun input(entity: AttachmentEntity) = InputAttachment(entity.name, entity.mime, file(entity.id), entity.kind)
    fun delete(id: String) { file(id).delete() }
    fun cleanupOrphans(usedIds: Set<String>) {
        directory.listFiles()?.filter { it.name !in usedIds && it.isFile }?.forEach { it.delete() }
        cameraDirectory.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 86_400_000 }?.forEach { it.delete() }
    }
    fun import(uri: Uri, image: Boolean): AttachmentEntity {
        var name = "附件"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)?.take(200) ?: name
        }
        val detected = context.contentResolver.getType(uri)?.takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) }
        val extension = name.substringAfterLast('.', "").lowercase()
        val mime = if (image) detected else when (extension) {
            "pdf" -> "application/pdf"
            "txt", "md", "markdown" -> "text/plain"
            else -> detected
        }
        var kind = when {
            mime in setOf("image/jpeg", "image/png", "image/webp") -> "image"
            !image && mime == "application/pdf" -> "pdf"
            !image && (extension in setOf("txt", "md", "markdown") || mime in setOf("text/plain", "text/markdown")) -> "text"
            else -> "file"
        }
        val id = UUID.randomUUID().toString()
        val destination = file(id)
        try {
            context.contentResolver.openInputStream(uri)?.use { source ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count == -1) break
                        total += count
                        require(total < FILE_LIMIT) { "单个附件须小于 50 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("无法读取附件，请重新选择")
            require(destination.length() > 0) { "附件为空" }
            if (kind == "text") {
                if (destination.length() > TEXT_LIMIT || runCatching {
                    com.mobilechat.app.network.readTextFile(InputAttachment(name, "text/plain", destination, kind))
                }.isFailure) kind = "file"
            }
            return AttachmentEntity(id, "", name, mime ?: "application/octet-stream", kind, destination.length())
        } catch (e: Exception) { destination.delete(); throw e }
    }
}
