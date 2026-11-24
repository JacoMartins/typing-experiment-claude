package dev.trechos

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Histórico da área de transferência, salvo só no aparelho.
 * Guarda até [MAX] itens; os não fixados somem depois de [TTL_MS].
 */
class ClipStore(private val file: File) {

    data class Item(val text: String, val time: Long, var pinned: Boolean)

    val items = ArrayList<Item>()

    fun load() {
        items.clear()
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                items.add(Item(o.getString("t"), o.getLong("at"), o.optBoolean("pin")))
            }
        } catch (_: Exception) {
            items.clear()
        }
        prune()
    }

    private fun save() {
        val arr = JSONArray()
        for (it in items) arr.put(JSONObject().put("t", it.text).put("at", it.time).put("pin", it.pinned))
        try { file.writeText(arr.toString()) } catch (_: Exception) {}
    }

    fun add(text: String) {
        if (text.isBlank()) return
        val old = items.firstOrNull { it.text == text }
        items.remove(old)
        items.add(0, Item(text, System.currentTimeMillis(), old?.pinned ?: false))
        prune()
        save()
    }

    fun togglePin(item: Item) {
        item.pinned = !item.pinned
        save()
    }

    fun remove(item: Item) {
        items.remove(item)
        save()
    }

    fun clearUnpinned() {
        items.removeAll { !it.pinned }
        save()
    }

    fun clearAll() {
        items.clear()
        save()
    }

    /** Remove os vencidos e os excedentes (sempre os não fixados mais antigos). */
    fun prune() {
        val now = System.currentTimeMillis()
        val before = items.size
        items.removeAll { !it.pinned && now - it.time > TTL_MS }
        while (items.size > MAX) {
            val victim = items.lastOrNull { !it.pinned } ?: break
            items.remove(victim)
        }
        if (items.size != before) save()
    }

    companion object {
        const val FILE = "clipboard.json"
        const val MAX = 30
        const val TTL_MS = 60 * 60 * 1000L
    }
}
