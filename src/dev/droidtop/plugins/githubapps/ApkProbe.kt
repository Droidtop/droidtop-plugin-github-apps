package dev.droidtop.plugins.githubapps

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.util.zip.Inflater

/** Random access to the bytes of one APK, wherever they are (a file, or an address read with HTTP Range requests). */
interface ByteSource {
    val size: Long

    /** Exactly [length] bytes from [offset]; [IOException] when that runs past the end or cannot be read. */
    fun read(offset: Long, length: Int): ByteArray
}

class MemorySource(private val bytes: ByteArray) : ByteSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray {
        if (offset < 0 || length < 0 || offset + length > bytes.size) throw IOException("read past the end")
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

/**
 * What an APK says about itself: its package, version and the SHA-256 of each signing certificate (lower-case hex).
 * [minSdk] is 0 when the manifest names none.
 */
class ApkFacts(val packageName: String, val versionCode: Long, val versionName: String?, val minSdk: Int, val signers: List<String>)

/**
 * Reads [ApkFacts] from an APK without the whole file: the zip directory at the end, `AndroidManifest.xml` (Android's
 * binary XML) and the APK Signing Block that sits just before the directory (schemes v2, v3 and v3.1), falling back
 * to the v1 signature file in META-INF. A release page names a file but not the app inside it, and droidtop needs the
 * package, the version code and the signing key before it downloads anything (docs/plugin-api.md 3 A12 `acquire`).
 * About a hundred kilobytes are read from the end of a typical APK instead of its tens of megabytes.
 *
 * This reads what the APK claims. It does not check the signature: droidtop checks the downloaded file's real
 * package, version and signing key against what this said before Android is asked to install it (docs/SPEC.md 10b).
 * The binary XML and signing block layouts are Android's (AOSP `AndroidManifest` / `ResXMLTree` and the APK Signature
 * Scheme v2 and v3 documents).
 */
object ApkProbe {
    private const val MAX_DIRECTORY = 8L * 1024 * 1024
    private const val MAX_ENTRY = 4L * 1024 * 1024
    private const val MAX_XML = 8L * 1024 * 1024
    private const val MAX_SIGNING_BLOCK = 4L * 1024 * 1024
    private const val TAIL = 98_304L

    private const val SCHEME_V2 = 0x7109871aL
    private const val SCHEME_V3 = 0xf05368c0L
    private const val SCHEME_V31 = 0x1b93ad61L

    private class Entry(val name: String, val method: Int, val compressed: Long, val headerOffset: Long)

    private fun bad(why: String) = IOException("not a readable APK: $why")

    private fun u16(b: ByteArray, o: Int): Int {
        if (o < 0 || o + 2 > b.size) throw bad("truncated")
        return (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(b: ByteArray, o: Int): Long {
        if (o < 0 || o + 4 > b.size) throw bad("truncated")
        return (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
    }

    private fun u64(b: ByteArray, o: Int): Long = u32(b, o) or (u32(b, o + 4) shl 32)

    fun probe(src: ByteSource): ApkFacts {
        val size = src.size
        if (size < 22) throw bad("too small")
        val tailLength = minOf(size, TAIL).toInt()
        val tail = src.read(size - tailLength, tailLength)
        var eocd = -1
        for (i in tail.size - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() && tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()) {
                eocd = i
                break
            }
        }
        if (eocd < 0) throw bad("no zip directory")
        val directorySize = u32(tail, eocd + 12)
        val directoryOffset = u32(tail, eocd + 16)
        if (directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) throw bad("zip64 is not read")
        if (directorySize > MAX_DIRECTORY || directoryOffset + directorySize > size) throw bad("zip directory out of range")
        val directory = src.read(directoryOffset, directorySize.toInt())

        var manifest: Entry? = null
        val v1 = ArrayList<Entry>()
        var p = 0
        while (p + 46 <= directory.size && u32(directory, p) == 0x02014b50L) {
            val nameLength = u16(directory, p + 28)
            val extraLength = u16(directory, p + 30)
            val commentLength = u16(directory, p + 32)
            if (p + 46 + nameLength > directory.size) throw bad("zip directory truncated")
            val name = String(directory, p + 46, nameLength, Charsets.UTF_8)
            val entry = Entry(name, u16(directory, p + 10), u32(directory, p + 20), u32(directory, p + 42))
            if (name == "AndroidManifest.xml") manifest = entry
            val upper = name.uppercase()
            if (upper.startsWith("META-INF/") && (upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC"))) v1 += entry
            p += 46 + nameLength + extraLength + commentLength
        }
        val xml = readEntry(src, manifest ?: throw bad("no AndroidManifest.xml"))
        val parsed = try {
            Manifest.parse(xml)
        } catch (e: IndexOutOfBoundsException) {
            throw bad("AndroidManifest.xml is cut short")
        }
        val signers = fromSigningBlock(src, directoryOffset).ifEmpty { fromV1(src, v1) }
        return ApkFacts(parsed.packageName, parsed.versionCode, parsed.versionName, parsed.minSdk, signers)
    }

    private fun readEntry(src: ByteSource, e: Entry): ByteArray {
        if (e.compressed > MAX_ENTRY) throw bad("${e.name} is too large to read ahead")
        val header = src.read(e.headerOffset, 30)
        if (u32(header, 0) != 0x04034b50L) throw bad("bad local header")
        val dataOffset = e.headerOffset + 30 + u16(header, 26) + u16(header, 28)
        val data = src.read(dataOffset, e.compressed.toInt())
        return when (e.method) {
            0 -> data
            8 -> inflate(data)
            else -> throw bad("compression method ${e.method}")
        }
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_XML) throw bad("manifest too large")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // ------------------------------------------------------------------
    // AndroidManifest.xml, Android's binary XML.
    // ------------------------------------------------------------------

    class Manifest(val packageName: String, val versionCode: Long, val versionName: String?, val minSdk: Int) {
        companion object {
            private const val CHUNK_STRINGS = 0x0001
            private const val CHUNK_START_ELEMENT = 0x0102
            private const val TYPE_STRING = 0x03
            private const val TYPE_INT_DEC = 0x10
            private const val TYPE_INT_HEX = 0x11
            private const val NONE = 0xFFFFFFFFL

            fun parse(xml: ByteArray): Manifest {
                if (xml.size < 8 || u16(xml, 0) != 0x0003) throw bad("AndroidManifest.xml is not binary XML")
                val strings = ArrayList<String>()
                var packageName: String? = null
                var code = 0L
                var major = 0L
                var name: String? = null
                var minSdk = 0
                var o = 8
                while (o + 8 <= xml.size) {
                    val type = u16(xml, o)
                    val chunkSize = u32(xml, o + 4)
                    if (chunkSize < 8 || o + chunkSize > xml.size) throw bad("binary XML chunk out of range")
                    if (type == CHUNK_STRINGS) {
                        readStrings(xml, o, strings)
                    } else if (type == CHUNK_START_ELEMENT) {
                        val element = strings.getOrNull(u32(xml, o + 20).toInt()) ?: ""
                        val count = u16(xml, o + 28)
                        if (element == "manifest" || element == "uses-sdk") {
                            for (k in 0 until count) {
                                val a = o + 36 + 20 * k
                                val attribute = strings.getOrNull(u32(xml, a + 4).toInt()) ?: continue
                                val raw = u32(xml, a + 8)
                                val valueType = xml[a + 15].toInt() and 0xff
                                val data = u32(xml, a + 16)
                                val text = if (valueType == TYPE_STRING && data != NONE) strings.getOrNull(data.toInt()) else if (raw != NONE) strings.getOrNull(raw.toInt()) else null
                                val number = if (valueType == TYPE_INT_DEC || valueType == TYPE_INT_HEX) data else null
                                if (element == "manifest") {
                                    when (attribute) {
                                        "package" -> packageName = text
                                        "versionCode" -> if (number != null) code = number
                                        "versionCodeMajor" -> if (number != null) major = number
                                        "versionName" -> name = text
                                    }
                                } else if (attribute == "minSdkVersion" && number != null) {
                                    minSdk = number.toInt()
                                }
                            }
                        }
                    }
                    o += chunkSize.toInt()
                }
                val pkg = packageName?.takeIf { it.isNotBlank() } ?: throw bad("the manifest names no package")
                return Manifest(pkg, (major shl 32) or code, name, minSdk)
            }

            private fun readStrings(xml: ByteArray, chunk: Int, into: MutableList<String>) {
                val count = u32(xml, chunk + 8).toInt()
                val utf8 = (u32(xml, chunk + 16) and 0x100L) != 0L
                val base = chunk + u32(xml, chunk + 20).toInt()
                if (count < 0 || count > 200_000) throw bad("string pool too large")
                for (i in 0 until count) {
                    var q = base + u32(xml, chunk + 28 + 4 * i).toInt()
                    if (utf8) {
                        // The UTF-16 length (one or two bytes), then the UTF-8 length (one or two bytes), then the bytes.
                        q += if ((xml[q].toInt() and 0x80) != 0) 2 else 1
                        var bytes = xml[q].toInt() and 0xff
                        q++
                        if ((bytes and 0x80) != 0) {
                            bytes = ((bytes and 0x7f) shl 8) or (xml[q].toInt() and 0xff)
                            q++
                        }
                        if (q + bytes > xml.size) throw bad("string out of range")
                        into += String(xml, q, bytes, Charsets.UTF_8)
                    } else {
                        var length = u16(xml, q)
                        q += 2
                        if ((length and 0x8000) != 0) {
                            length = ((length and 0x7fff) shl 16) or u16(xml, q)
                            q += 2
                        }
                        if (q + 2 * length > xml.size) throw bad("string out of range")
                        into += String(xml, q, 2 * length, Charsets.UTF_16LE)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Signing certificates.
    // ------------------------------------------------------------------

    private class Cursor(val b: ByteArray, var p: Int, val end: Int) {
        fun more() = p + 4 <= end

        /** The next length-prefixed part, as a cursor over it. */
        fun part(): Cursor {
            val n = u32(b, p)
            if (n < 0 || p + 4 + n > end) throw bad("signature data out of range")
            val c = Cursor(b, p + 4, p + 4 + n.toInt())
            p += 4 + n.toInt()
            return c
        }

        fun bytes(): ByteArray {
            val c = part()
            return b.copyOfRange(c.p, c.end)
        }
    }

    private fun sha256(bytes: ByteArray): String = EntryHex.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun fromSigningBlock(src: ByteSource, directoryOffset: Long): List<String> {
        if (directoryOffset < 32) return emptyList()
        val footer = src.read(directoryOffset - 24, 24)
        if (String(footer, 8, 16, Charsets.ISO_8859_1) != "APK Sig Block 42") return emptyList()
        val blockSize = u64(footer, 0)
        if (blockSize < 32 || blockSize > MAX_SIGNING_BLOCK || blockSize + 8 > directoryOffset) return emptyList()
        val block = src.read(directoryOffset - blockSize - 8, (blockSize + 8).toInt())
        if (u64(block, 0) != blockSize) return emptyList()
        val found = LinkedHashSet<String>()
        var q = 8
        val end = block.size - 24
        while (q + 12 <= end) {
            val length = u64(block, q)
            if (length < 4 || q + 8 + length > end) break
            val id = u32(block, q + 8)
            if (id == SCHEME_V2 || id == SCHEME_V3 || id == SCHEME_V31) {
                runCatching {
                    val signers = Cursor(block, q + 12, q + 8 + length.toInt()).part()
                    while (signers.more()) {
                        val signer = signers.part()
                        val signed = signer.part()
                        signed.part() // digests
                        val certificates = signed.part()
                        if (certificates.more()) found += sha256(certificates.bytes())
                    }
                }
            }
            q += 8 + length.toInt()
        }
        return found.toList()
    }

    private fun fromV1(src: ByteSource, entries: List<Entry>): List<String> {
        val found = LinkedHashSet<String>()
        for (entry in entries) {
            runCatching {
                val data = readEntry(src, entry)
                val first = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(data)).firstOrNull()
                if (first != null) found += sha256(first.encoded)
            }
        }
        return found.toList()
    }
}
