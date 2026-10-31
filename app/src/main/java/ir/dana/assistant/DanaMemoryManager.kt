package ir.dana.assistant

import android.content.Context
import org.json.JSONObject

class DanaMemoryManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("dana_memory_store", Context.MODE_PRIVATE)

    fun rememberFact(key: String, value: String) {
        val cleanKey = key.trim()
        val cleanVal = value.trim()
        if (cleanKey.isNotEmpty() && cleanVal.isNotEmpty()) {
            prefs.edit().putString(cleanKey, cleanVal).apply()
        }
    }

    fun recallFact(queryKey: String): String? {
        val q = queryKey.trim()
        val all = prefs.all
        for ((k, v) in all) {
            if (k.contains(q, ignoreCase = true) || q.contains(k, ignoreCase = true)) {
                return v?.toString()
            }
        }
        return null
    }

    fun dumpAllMemoryAsText(): String {
        val all = prefs.all
        if (all.isEmpty()) return "هیچ داده شخصی ذخیره نشده است."
        val obj = JSONObject()
        for ((k, v) in all) {
            obj.put(k, v?.toString() ?: "")
        }
        return obj.toString()
    }
}
