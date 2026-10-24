package dev.chatocr.app

import android.content.Context

/** Reading preferences only; chat contents stay in SessionStore. */
class UiPreferences(context: Context) {
    private val values = context.getSharedPreferences("reading-preferences", Context.MODE_PRIVATE)
    var theme: String
        get() = values.getString("theme", "system") ?: "system"
        set(value) { values.edit().putString("theme", value).apply() }
    var textSize: Float
        get() = values.getFloat("textSize", 16f).coerceIn(14f, 22f)
        set(value) { values.edit().putFloat("textSize", value.coerceIn(14f, 22f)).apply() }
    var lastSession: String?
        get() = values.getString("lastSession", null)
        set(value) { values.edit().putString("lastSession", value).apply() }
    var library: String
        get() = values.getString("library", "chats")?.takeIf { it in setOf("chats", "articles") } ?: "chats"
        set(value) { values.edit().putString("library", value).apply() }
    var lastArticle: String?
        get() = values.getString("lastArticle", null)
        set(value) { values.edit().putString("lastArticle", value).apply() }
    fun position(id: String): Pair<Int, Int> = values.getInt("$id-index", 0) to values.getInt("$id-offset", 0)
    fun savePosition(id: String, index: Int, offset: Int) {
        values.edit().putInt("$id-index", index.coerceAtLeast(0)).putInt("$id-offset", offset.coerceAtLeast(0)).apply()
    }
    fun forget(id: String) {
        values.edit().remove("$id-index").remove("$id-offset").apply()
        if (lastSession == id) lastSession = null
    }
}
