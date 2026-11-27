package com.xhsmonitor

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ReportGenerator {
    private const val SUB_DIR = "XhsMonitor"

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun generate(context: Context, since: Long): String? {
        val list = DbHelper(context).use { it.getCommentsSince(since) }
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date(now))
        val name = "小红书评论汇总_${stamp}.md"

        val sb = StringBuilder()
        sb.append("# 小红书评论监控汇总\n\n")
        sb.append("生成时间：").append(fmt.format(Date(now))).append("\n\n")
        sb.append("本批评论：").append(list.size).append(" 条\n\n")

        if (list.isEmpty()) {
            sb.append("本时间段内没有发现新评论。\n")
        } else {
            val grouped = list.groupBy { it.keyword }
            for ((keyword, items) in grouped) {
                sb.append("## 关键词：").append(keyword).append("（").append(items.size).append(" 条）\n\n")
                for (item in items) {
                    sb.append("- **").append(safe(item.author)).append("**")
                    if (item.timeText.isNotBlank()) sb.append(" · ").append(safe(item.timeText))
                    sb.append("\n")
                    sb.append("  笔记：").append(safe(item.noteTitle)).append("\n")
                    sb.append("  评论：").append(safe(item.content.replace("\n", " "))).append("\n")
                    sb.append("  记录于：").append(fmt.format(Date(item.createdAt))).append("\n\n")
                }
            }
        }
        return saveText(context, name, sb.toString())
    }

    private fun safe(s: String): String =
        s.replace("*", "\\*").replace("[", "\\[").replace("]", "\\]")

    fun saveText(context: Context, name: String, text: String): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            try {
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {
                return null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB_DIR
            )
            if (!dir.exists() && !dir.mkdirs()) return null
            return try {
                File(dir, name).writeText(text, Charsets.UTF_8)
                File(dir, name).absolutePath
            } catch (e: Exception) {
                null
            }
        }
    }

    fun listReports(context: Context): List<ReportFile> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val result = mutableListOf<ReportFile>()
            val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME)
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val cursor = context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf("%$SUB_DIR%"),
                "${MediaStore.Downloads.DATE_ADDED} DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val name = it.getString(1) ?: continue
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    result.add(ReportFile(name, uri.toString(), true))
                }
            }
            result
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUB_DIR
            )
            dir.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.map { ReportFile(it.name, it.absolutePath, false) }
                ?: emptyList()
        }
    }

    fun uriFor(context: Context, file: ReportFile): Uri? {
        if (file.isUri) return Uri.parse(file.location)
        return try {
            androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", File(file.location)
            )
        } catch (e: Exception) {
            null
        }
    }
}
