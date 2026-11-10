package com.example.aiwatcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Persona(val id: String, val name: String, val prompt: String)

/** 所有设置都存在手机本地,令牌不写进代码。 */
object Store {
    private lateinit var sp: SharedPreferences
    fun init(c: Context) {
        if (!::sp.isInitialized) sp = c.applicationContext.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    }

    private fun s(k: String, d: String) = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) = sp.edit().putString(k, v).apply()

    var baseUrl: String
        get() = s("baseUrl", "https://api-inference.modelscope.cn/v1")
        set(v) = put("baseUrl", v)
    var apiKey: String
        get() = s("apiKey", "")
        set(v) = put("apiKey", v)
    var model: String
        get() = s("model", "")
        set(v) = put("model", v)

    /** 0 = 图片直接发给模型(模型需支持图片); 1 = 本地 OCR 识字后发文字 */
    var visionMode: Int
        get() = sp.getInt("vm", 0)
        set(v) = sp.edit().putInt("vm", v).apply()
    var useScreen: Boolean
        get() = sp.getBoolean("useScreen", true)
        set(v) = sp.edit().putBoolean("useScreen", v).apply()
    var tts: Boolean
        get() = sp.getBoolean("tts", true)
        set(v) = sp.edit().putBoolean("tts", v).apply()
    var autoComment: Boolean
        get() = sp.getBoolean("auto", false)
        set(v) = sp.edit().putBoolean("auto", v).apply()
    var autoInterval: Int
        get() = sp.getInt("autoSec", 20)
        set(v) = sp.edit().putInt("autoSec", v).apply()
    var opacity: Int
        get() = sp.getInt("opacity", 90)
        set(v) = sp.edit().putInt("opacity", v).apply()
    var activeId: String
        get() = s("activeId", "default")
        set(v) = put("activeId", v)

    fun personas(): List<Persona> {
        val raw = s("personas", "")
        if (raw.isNotBlank()) {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Persona(o.getString("id"), o.getString("name"), o.getString("prompt"))
            }
            if (list.isNotEmpty()) return list
        }
        return listOf(Persona("default", "小助手", "你是一个温柔、聪明、有点幽默的朋友,陪我看手机屏幕、聊天,用简短口语回答。"))
    }

    fun savePersonas(l: List<Persona>) {
        val arr = JSONArray()
        l.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("prompt", it.prompt)) }
        put("personas", arr.toString())
    }

    fun active(): Persona = personas().let { l -> l.firstOrNull { it.id == activeId } ?: l.first() }
}
