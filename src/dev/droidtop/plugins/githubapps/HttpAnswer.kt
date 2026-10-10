package dev.droidtop.plugins.githubapps

import java.util.Base64
import org.json.JSONObject

/** An HTTP answer. [headers] are lower-case. [truncated] is true when the body was longer than droidtop hands over. */
class HttpAnswer(val status: Int, val headers: Map<String, String>, val body: ByteArray, val truncated: Boolean) {
    val text: String get() = body.toString(Charsets.UTF_8)

    companion object {
        /**
         * The answer in the `data` of a `net.request` or `github.request` reply. The body is `bodyBase64` or, where
         * droidtop sent text (it does when `as` is not asked for and the answer is text), `body`: reading only the
         * first made every GitHub answer through the signed-in account look empty, so a repository with releases
         * was reported as having none (Droidtop/tracker#479).
         */
        fun of(data: JSONObject): HttpAnswer {
            val headers = HashMap<String, String>()
            data.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it.lowercase()] = h.optString(it) } }
            val base64 = data.optString("bodyBase64")
            val body = if (base64.isNotEmpty()) Base64.getDecoder().decode(base64) else data.optString("body").toByteArray(Charsets.UTF_8)
            return HttpAnswer(data.optInt("status"), headers, body, data.optBoolean("truncated"))
        }
    }
}
