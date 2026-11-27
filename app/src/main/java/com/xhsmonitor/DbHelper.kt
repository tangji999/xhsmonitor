package com.xhsmonitor

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "xhs_monitor.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE comments (" +
                    "id TEXT PRIMARY KEY, keyword TEXT, note_title TEXT, " +
                    "author TEXT, content TEXT, time_text TEXT, created_at INTEGER)"
        )
        db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, message TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS comments")
        db.execSQL("DROP TABLE IF EXISTS logs")
        onCreate(db)
    }

    fun insertComment(r: CommentRecord): Boolean {
        val cv = ContentValues().apply {
            put("id", r.id)
            put("keyword", r.keyword)
            put("note_title", r.noteTitle)
            put("author", r.author)
            put("content", r.content)
            put("time_text", r.timeText)
            put("created_at", r.createdAt)
        }
        return writableDatabase.insertWithOnConflict(
            "comments", null, cv, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun getCommentsSince(ts: Long, limit: Int = 1000): List<CommentRecord> {
        val cursor: Cursor = readableDatabase.rawQuery(
            "SELECT id,keyword,note_title,author,content,time_text,created_at " +
                    "FROM comments WHERE created_at >= ? ORDER BY created_at DESC LIMIT ?",
            arrayOf(ts.toString(), limit.toString())
        )
        return cursor.use { read(it) }
    }

    fun getAllComments(limit: Int = 300): List<CommentRecord> {
        val cursor: Cursor = readableDatabase.rawQuery(
            "SELECT id,keyword,note_title,author,content,time_text,created_at " +
                    "FROM comments ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        return cursor.use { read(it) }
    }

    private fun read(c: Cursor): List<CommentRecord> {
        val list = mutableListOf<CommentRecord>()
        while (c.moveToNext()) {
            list.add(
                CommentRecord(
                    id = c.getString(0),
                    keyword = c.getString(1) ?: "",
                    noteTitle = c.getString(2) ?: "",
                    author = c.getString(3) ?: "",
                    content = c.getString(4) ?: "",
                    timeText = c.getString(5) ?: "",
                    createdAt = c.getLong(6)
                )
            )
        }
        return list
    }

    fun clearComments() {
        writableDatabase.execSQL("DELETE FROM comments")
    }

    fun addLog(message: String) {
        val cv = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("message", message)
        }
        writableDatabase.insert("logs", null, cv)
    }

    fun getLogs(limit: Int = 50): List<String> {
        val c = readableDatabase.rawQuery(
            "SELECT ts,message FROM logs ORDER BY ts DESC LIMIT ?", arrayOf(limit.toString())
        )
        return c.use { cur ->
            val list = mutableListOf<String>()
            while (cur.moveToNext()) {
                list.add("${cur.getLong(0)}  ${cur.getString(1) ?: ""}")
            }
            list
        }
    }
}
