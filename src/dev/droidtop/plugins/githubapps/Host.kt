package dev.droidtop.plugins.githubapps

import android.os.ParcelFileDescriptor
import dev.droidtop.pluginhost.PluginContext
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import org.json.JSONObject

/** The broker refused or failed a call; [code] is the broker's error code (`UNSUPPORTED`, `PERMISSION_DENIED`, ...). */
class BrokerFailure(val code: String, message: String) : IOException(message)

/** A forge refused because the request limit is used up; [resetAt] is the epoch second it resets, or 0 when not said. */
class RateLimited(val resetAt: Long, message: String) : IOException(message)

/** An HTTP answer. [headers] are lower-case. [truncated] is true when the body was longer than droidtop hands over. */
class HttpAnswer(val status: Int, val headers: Map<String, String>, val body: ByteArray, val truncated: Boolean) {
    val text: String get() = body.toString(Charsets.UTF_8)
}

/**
 * Everything this plugin asks of droidtop, through the broker (docs/plugin-api.md 1.4): it runs contained, with no
 * socket and no file path of its own, so the network is `net.http` (small answers) and `net.download` (a whole file,
 * as a job into the plugin's own data), and its files are `data.open` descriptors.
 */
internal class Host(private val context: PluginContext) {
    /** One broker call; the reply's `data`, or a [BrokerFailure] with the broker's code and reason. */
    fun call(api: String, op: String, args: JSONObject = JSONObject()): JSONObject {
        val reply = JSONObject(context.call(api, 1, op, args.toString()))
        if (!reply.optBoolean("ok")) {
            val error = reply.optJSONObject("error")
            throw BrokerFailure(
                error?.optString("code").orEmpty(),
                error?.optString("message")?.takeIf { it.isNotBlank() } ?: "droidtop refused $api.$op",
            )
        }
        return reply.optJSONObject("data") ?: JSONObject()
    }

    /** One `net.http` request: any status is an answer. At most 128 KiB of body come back (the rest sets `truncated`). */
    fun http(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): HttpAnswer {
        val args = JSONObject().put("url", url).put("as", "base64").put("timeoutMs", timeoutMs)
        if (headers.isNotEmpty()) args.put("headers", JSONObject(headers))
        return answerOf(call("net", "http", args))
    }

    private fun answerOf(data: JSONObject): HttpAnswer {
        val headers = HashMap<String, String>()
        data.optJSONObject("headers")?.let { h -> h.keys().forEach { headers[it.lowercase()] = h.optString(it) } }
        val body = data.optString("bodyBase64").let { if (it.isEmpty()) ByteArray(0) else Base64.getDecoder().decode(it) }
        return HttpAnswer(data.optInt("status"), headers, body, data.optBoolean("truncated"))
    }

    @Volatile private var githubHostOp: Boolean? = null

    /**
     * A GitHub API request ([url] starts with https://api.github.com). Where droidtop offers the signed-in GitHub
     * account to plugins (docs/plugin-api.md 3 G4: the `github` API's `request` op, permission `github.api`; the
     * token stays in droidtop), the request goes through it and gets the account's higher request limit. Where it does
     * not (a droidtop that does not have the op yet, the permission not granted, nobody signed in), the same request
     * goes out as an ordinary, unauthenticated `net.http` one. The answer is remembered for the rest of this process.
     */
    fun githubApi(url: String, headers: Map<String, String> = emptyMap()): HttpAnswer {
        if (githubHostOp != false && url.startsWith(GITHUB_API)) {
            try {
                val args = JSONObject().put("path", url.removePrefix(GITHUB_API)).put("timeoutMs", 30_000)
                if (headers.isNotEmpty()) args.put("headers", JSONObject(headers))
                val answer = answerOf(call("github", "request", args))
                githubHostOp = true
                return answer
            } catch (failure: BrokerFailure) {
                // Not built, not granted, not signed in: all mean "use the public API".
                githubHostOp = false
            }
        }
        return http(url, headers)
    }

    /** Whether the last [githubApi] went through the signed-in account (null: not asked yet). */
    fun usedGithubAccount(): Boolean? = githubHostOp

    /** Downloads [url] into the plugin's data as [name], as a droidtop download job, and waits for it. */
    fun download(url: String, name: String, cancelled: () -> Boolean, onProgress: (Int, String) -> Unit) {
        val jobId = call("net", "download", JSONObject().put("url", url).put("name", name)).optString("jobId")
        while (true) {
            if (cancelled()) {
                runCatching { call("plugins", "job_cancel", JSONObject().put("jobId", jobId)) }
                throw IOException("cancelled")
            }
            val status = call("plugins", "job_status", JSONObject().put("jobId", jobId))
            if (status.optBoolean("done")) {
                if (!status.optBoolean("ok")) throw IOException(status.optString("message").ifBlank { "the download of $url failed" })
                return
            }
            onProgress(status.optInt("percent", -1), status.optString("status"))
            Thread.sleep(1_000)
        }
    }

    /** The file [name] of the plugin's data for reading with random access. The caller closes it. */
    fun openForSeeking(name: String): FileInputStream = ParcelFileDescriptor.AutoCloseInputStream(open(name, "r"))

    fun openRead(name: String): InputStream = ParcelFileDescriptor.AutoCloseInputStream(open(name, "r"))

    fun openWrite(name: String): OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(open(name, "w"))

    private fun open(name: String, mode: String): ParcelFileDescriptor {
        val reply = context.openFile("data", 1, "open", JSONObject().put("name", name).put("mode", mode).toString())
        return reply.fd ?: throw if (JSONObject(reply.reply).optJSONObject("error")?.optString("code") == "NOT_FOUND") {
            NotFound(name)
        } else {
            IOException("droidtop did not open $name: " + (JSONObject(reply.reply).optJSONObject("error")?.optString("message") ?: "refused"))
        }
    }

    /** The text of data file [name], or null when there is none. */
    fun readText(name: String): String? = try {
        openRead(name).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (_: NotFound) {
        null
    }

    /** Replaces data file [name] whole: written beside it, then moved over it. */
    fun writeText(name: String, text: String) {
        openWrite("$name.tmp").use { it.write(text.toByteArray(Charsets.UTF_8)) }
        call("data", "move", JSONObject().put("from", "$name.tmp").put("to", name))
    }

    fun delete(name: String) {
        runCatching { call("data", "delete", JSONObject().put("name", name)) }
    }

    /** Asks the person to pick a file (`files.pick`, only during a call they started) and returns its bytes, at most [max]. */
    fun pickFile(mime: String, max: Int): ByteArray? {
        val picked = call("files", "pick", JSONObject().put("mode", "open").put("mime", mime))
        if (!picked.optBoolean("picked")) return null
        val token = picked.optString("token")
        try {
            val reply = context.openFile("files", 1, "open", JSONObject().put("token", token).put("mode", "r").toString())
            val fd = reply.fd ?: throw IOException("droidtop did not open the file you picked")
            ParcelFileDescriptor.AutoCloseInputStream(fd).use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > max) throw IOException("That file is larger than ${max / 1024} KiB, too large for an export file")
                }
                return out.toByteArray()
            }
        } finally {
            runCatching { call("files", "forget", JSONObject().put("token", token)) }
        }
    }

    class NotFound(what: String) : IOException("$what was not found")

    companion object {
        const val GITHUB_API = "https://api.github.com"
    }
}
