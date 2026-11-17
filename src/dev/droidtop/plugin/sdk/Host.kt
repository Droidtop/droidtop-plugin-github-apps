package dev.droidtop.plugin.sdk

import android.os.ParcelFileDescriptor
import dev.droidtop.pluginhost.PluginContext
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import org.json.JSONObject

/*
 * The Kotlin plugin SDK's host calls (Droidtop/tracker#946, step 4). One source, here in droidtop-plugin-template's
 * tools/kotlin, copied byte for byte into each Kotlin plugin under src/dev/droidtop/plugin/sdk/ and checked against
 * the template's tag by the Template drift workflow. A fix is made here, tagged, and the plugins' pins move.
 */

/** The broker refused or failed a call; [code] is the broker's error code (`UNSUPPORTED`, `PERMISSION_DENIED`, ...). */
class BrokerFailure(val code: String, message: String) : IOException(message)

/**
 * Everything a contained plugin asks of droidtop, through its broker (docs/plugin-api.md 1.4): it has no socket and no
 * file path of its own, so the network is `net.request` (small answers) and `net.download` (a whole file, as a job
 * into the plugin's own data), and its files are `data.open` descriptors. A plugin adds its own calls in a subclass.
 */
open class Host(protected val context: PluginContext) {
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

    /** GET [url], at most 128 KiB, as bytes; [NotFound] for a 404, [IOException] for anything but a 2xx answer that fits. */
    fun get(url: String): ByteArray {
        val answer = call("net", "request", JSONObject().put("url", url).put("as", "base64").put("timeoutMs", 30_000))
        val status = answer.optInt("status")
        if (status == 404) throw NotFound(url)
        if (status !in 200..299) throw IOException("HTTP $status from $url")
        if (answer.optBoolean("truncated")) throw IOException("$url is larger than droidtop hands over in one answer")
        return Base64.getDecoder().decode(answer.optString("bodyBase64"))
    }

    /** The first part of [url] as text (at most 128 KiB), whether or not there is more. */
    fun head(url: String): String {
        val answer = call("net", "request", JSONObject().put("url", url).put("timeoutMs", 30_000))
        val status = answer.optInt("status")
        if (status !in 200..299) throw IOException("HTTP $status from $url")
        return answer.optString("body")
    }

    /**
     * Downloads [url] into the plugin's data as [name], as a droidtop download job, and waits for it, telling
     * [onProgress] how far it is. [IOException] when it fails or is cancelled ([cancelled] asks each second).
     */
    fun download(url: String, name: String, cancelled: () -> Boolean, onProgress: (Int, String) -> Unit) {
        val jobId = call("net", "download", JSONObject().put("url", url).put("name", name)).optString("jobId")
        while (true) {
            if (cancelled()) {
                runCatching { call("job", "cancel", JSONObject().put("jobId", jobId)) }
                throw IOException("cancelled")
            }
            val status = call("job", "get_status", JSONObject().put("jobId", jobId))
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

    fun move(from: String, to: String) {
        call("data", "move", JSONObject().put("from", from).put("to", to))
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
                    if (out.size() > max) throw IOException("That file is larger than ${max / 1024} KiB")
                }
                return out.toByteArray()
            }
        } finally {
            runCatching { call("files", "forget", JSONObject().put("token", token)) }
        }
    }

    class NotFound(what: String) : IOException("$what was not found")
}
