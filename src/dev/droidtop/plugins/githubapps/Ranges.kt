package dev.droidtop.plugins.githubapps

import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer

/** The server does not answer byte-range requests, so the end of the file cannot be read on its own. */
internal class RangeUnsupported(what: String) : IOException(what)

/**
 * An APK at an address, read with HTTP Range requests through `net.http` (at most 96 KiB each). The first request asks
 * for the end of the file, which also tells the file's size (`Content-Range`); the zip directory and the signing block
 * are there, so a probe is usually that one request and one for the manifest. Redirects (a release file is served from
 * another host) are followed by droidtop, which checks every hop.
 */
internal class HttpRanges private constructor(
    private val host: Host,
    private val url: String,
    override val size: Long,
    private val tailStart: Long,
    private val tail: ByteArray,
) : ByteSource {
    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > size) throw IOException("read past the end")
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val at = offset + done
            if (at >= tailStart) {
                val from = (at - tailStart).toInt()
                val n = minOf(length - done, tail.size - from)
                System.arraycopy(tail, from, out, done, n)
                done += n
                continue
            }
            val want = minOf((length - done).toLong(), CHUNK.toLong(), tailStart - at).toInt()
            val answer = host.http(url, mapOf("Range" to "bytes=$at-${at + want - 1}"))
            if (answer.status != 206 || answer.truncated || answer.body.size != want) {
                throw IOException("The server answered a range request with HTTP ${answer.status} and ${answer.body.size} of $want bytes")
            }
            System.arraycopy(answer.body, 0, out, done, want)
            done += want
        }
        return out
    }

    companion object {
        const val CHUNK = 96 * 1024
        private val CONTENT_RANGE = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

        fun open(host: Host, url: String): HttpRanges {
            val answer = host.http(url, mapOf("Range" to "bytes=-$CHUNK"))
            if (answer.status == 404) throw IOException("The file is gone (HTTP 404)")
            // A file smaller than the range may come back whole, as a plain 200.
            if (answer.status == 200 && !answer.truncated) return HttpRanges(host, url, answer.body.size.toLong(), 0L, answer.body)
            if (answer.status != 206) throw RangeUnsupported("The server does not answer byte-range requests (HTTP ${answer.status})")
            val m = CONTENT_RANGE.matchEntire(answer.headers["content-range"].orEmpty().trim())
                ?: throw RangeUnsupported("The server gave no usable Content-Range")
            val total = m.groupValues[3].toLong()
            if (answer.truncated || answer.body.size.toLong() != m.groupValues[2].toLong() - m.groupValues[1].toLong() + 1 || m.groupValues[2].toLong() != total - 1) {
                throw RangeUnsupported("The server answered the range request with something else")
            }
            return HttpRanges(host, url, total, total - answer.body.size, answer.body)
        }
    }
}

/** An APK in a file of the plugin's data (opened by droidtop, so only a descriptor). */
internal class FileSource(private val stream: FileInputStream) : ByteSource {
    private val channel = stream.channel

    override val size: Long get() = channel.size()

    override fun read(offset: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var at = offset
        while (buffer.hasRemaining()) {
            val n = channel.read(buffer, at)
            if (n < 0) throw IOException("read past the end")
            at += n
        }
        return buffer.array()
    }
}

/** Reads what an APK says about itself from its release address ([ApkProbe]). */
internal class Prober(private val host: Host) {
    /**
     * The facts of the APK at [asset], read ahead in ranges. When the server cannot answer range requests the file is
     * downloaded once into the plugin's data (at most [MAX_DOWNLOAD] bytes), read, and deleted.
     */
    fun probe(asset: Asset, cancelled: () -> Boolean, progress: (Int, String) -> Unit): ApkFacts {
        val ranges = try {
            HttpRanges.open(host, asset.url)
        } catch (unsupported: RangeUnsupported) {
            return viaDownload(asset, cancelled, progress)
        }
        return ApkProbe.probe(ranges)
    }

    private fun viaDownload(asset: Asset, cancelled: () -> Boolean, progress: (Int, String) -> Unit): ApkFacts {
        if (asset.size > MAX_DOWNLOAD) throw IOException("${asset.name} is too large to download just to read what app it is")
        val name = "probe/${asset.id.filter { it.isLetterOrDigit() }.take(40).ifEmpty { "apk" }}.apk"
        try {
            host.download(asset.url, name, cancelled) { percent, line -> progress(percent, "Reading ${asset.name}" + if (line.isNotBlank()) ": $line" else "") }
            host.openForSeeking(name).use { return ApkProbe.probe(FileSource(it)) }
        } finally {
            host.delete(name)
        }
    }

    companion object {
        const val MAX_DOWNLOAD = 300L * 1024 * 1024
    }
}
