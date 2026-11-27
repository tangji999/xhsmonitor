package com.xhsmonitor

import org.json.JSONObject

/**
 * 界面识别规则。默认值按小红书常见布局猜测，实际以手机导出的诊断节点树为准。
 * 所有匹配都是「包含」匹配，不区分大小写。
 */
data class Selectors(
    var searchEntryTexts: List<String> = listOf("搜索", "发现"),
    var searchEntryIds: List<String> = listOf("search", "iv_search", "tv_search", "search_icon"),
    var searchInputIds: List<String> = listOf("search_input", "et_search", "search_edit", "search_et"),
    var searchButtonTexts: List<String> = listOf("搜索", "Search", "搜一下"),
    var noteCardIds: List<String> = listOf("note_item", "note_card", "feed_item", "note_cell", "note_container"),
    var commentAreaIds: List<String> = listOf("comment", "comments", "comment_list", "comment_area", "comment_layout"),
    var commentItemIds: List<String> = listOf("comment_item", "comment_cell", "comment_row", "comment_root"),
    var titleIds: List<String> = listOf("title", "note_title", "tv_title"),
    var backIds: List<String> = listOf("back", "iv_back", "btn_back")
) {
    fun toJson(): String {
        val o = JSONObject()
        o.put("searchEntryTexts", JSONArrayHelper.array(searchEntryTexts))
        o.put("searchEntryIds", JSONArrayHelper.array(searchEntryIds))
        o.put("searchInputIds", JSONArrayHelper.array(searchInputIds))
        o.put("searchButtonTexts", JSONArrayHelper.array(searchButtonTexts))
        o.put("noteCardIds", JSONArrayHelper.array(noteCardIds))
        o.put("commentAreaIds", JSONArrayHelper.array(commentAreaIds))
        o.put("commentItemIds", JSONArrayHelper.array(commentItemIds))
        o.put("titleIds", JSONArrayHelper.array(titleIds))
        o.put("backIds", JSONArrayHelper.array(backIds))
        return o.toString()
    }

    companion object {
        fun fromJson(raw: String?): Selectors {
            if (raw.isNullOrBlank()) return Selectors()
            return try {
                val o = JSONObject(raw)
                Selectors(
                    searchEntryTexts = JSONArrayHelper.list(o.optJSONArray("searchEntryTexts")),
                    searchEntryIds = JSONArrayHelper.list(o.optJSONArray("searchEntryIds")),
                    searchInputIds = JSONArrayHelper.list(o.optJSONArray("searchInputIds")),
                    searchButtonTexts = JSONArrayHelper.list(o.optJSONArray("searchButtonTexts")),
                    noteCardIds = JSONArrayHelper.list(o.optJSONArray("noteCardIds")),
                    commentAreaIds = JSONArrayHelper.list(o.optJSONArray("commentAreaIds")),
                    commentItemIds = JSONArrayHelper.list(o.optJSONArray("commentItemIds")),
                    titleIds = JSONArrayHelper.list(o.optJSONArray("titleIds")),
                    backIds = JSONArrayHelper.list(o.optJSONArray("backIds"))
                ).let { s ->
                    // 缺失字段回退到默认值，避免旧版本配置导致识别为空
                    s.copy(
                        searchEntryTexts = if (s.searchEntryTexts.isEmpty()) Selectors().searchEntryTexts else s.searchEntryTexts,
                        searchEntryIds = if (s.searchEntryIds.isEmpty()) Selectors().searchEntryIds else s.searchEntryIds,
                        searchInputIds = if (s.searchInputIds.isEmpty()) Selectors().searchInputIds else s.searchInputIds,
                        searchButtonTexts = if (s.searchButtonTexts.isEmpty()) Selectors().searchButtonTexts else s.searchButtonTexts,
                        noteCardIds = if (s.noteCardIds.isEmpty()) Selectors().noteCardIds else s.noteCardIds,
                        commentAreaIds = if (s.commentAreaIds.isEmpty()) Selectors().commentAreaIds else s.commentAreaIds,
                        commentItemIds = if (s.commentItemIds.isEmpty()) Selectors().commentItemIds else s.commentItemIds,
                        titleIds = if (s.titleIds.isEmpty()) Selectors().titleIds else s.titleIds,
                        backIds = if (s.backIds.isEmpty()) Selectors().backIds else s.backIds
                    )
                }
            } catch (e: Exception) {
                Selectors()
            }
        }
    }
}

private object JSONArrayHelper {
    fun array(list: List<String>): org.json.JSONArray {
        val a = org.json.JSONArray()
        list.forEach { a.put(it) }
        return a
    }

    fun list(a: org.json.JSONArray?): List<String> {
        if (a == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until a.length()) result.add(a.optString(i))
        return result
    }
}
