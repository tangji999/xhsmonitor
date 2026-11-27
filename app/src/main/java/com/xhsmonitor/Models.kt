package com.xhsmonitor

data class CommentRecord(
    val id: String,
    val keyword: String,
    val noteTitle: String,
    val author: String,
    val content: String,
    val timeText: String,
    val createdAt: Long
) {
    companion object {
        fun makeId(keyword: String, noteTitle: String, author: String, content: String): String {
            val raw = "$keyword|$noteTitle|$author|$content"
            return try {
                val bytes = java.security.MessageDigest.getInstance("MD5")
                    .digest(raw.toByteArray(Charsets.UTF_8))
                bytes.joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                raw.hashCode().toString()
            }
        }
    }
}

data class ReportFile(val name: String, val location: String, val isUri: Boolean)
