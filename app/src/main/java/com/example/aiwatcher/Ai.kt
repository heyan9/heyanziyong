package com.example.aiwatcher

import android.graphics.Bitmap
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** OpenAI 兼容接口(魔搭 / DeepSeek 官方 / 其他都能用,在设置里改地址和模型名)。 */
object Ai {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun toDataUrl(src: Bitmap): String {
        val max = 1024f
        val k = minOf(1f, max / maxOf(src.width, src.height))
        val bmp = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, bos)
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun ocr(bmp: Bitmap): String {
        val rec = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val res = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)))
        return res.text.take(3000)
    }

    fun chat(persona: Persona, history: List<Pair<String, String>>, text: String, frame: Bitmap?): String {
        require(Store.apiKey.isNotBlank()) { "请先在设置里填写令牌" }
        require(Store.model.isNotBlank()) { "请先在设置里填写模型名" }

        val msgs = JSONArray()
        msgs.put(JSONObject().put("role", "system")
            .put("content", persona.prompt + "\n\n回答要简短、口语化,适合直接语音播报,不要用 Markdown。"))
        history.takeLast(12).forEach { msgs.put(JSONObject().put("role", it.first).put("content", it.second)) }

        val user = JSONObject().put("role", "user")
        when {
            frame != null && Store.visionMode == 0 -> user.put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", text))
                .put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", toDataUrl(frame)))))
            frame != null -> user.put("content", "$text\n\n[我当前屏幕上识别到的文字]\n" + ocr(frame))
            else -> user.put("content", text)
        }
        msgs.put(user)

        val body = JSONObject().put("model", Store.model).put("messages", msgs)
            .put("max_tokens", 800).put("stream", false)
        val req = Request.Builder()
            .url(Store.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer " + Store.apiKey.trim())
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw RuntimeException("HTTP ${r.code}: ${s.take(200)}")
            return JSONObject(s).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
        }
    }
}
