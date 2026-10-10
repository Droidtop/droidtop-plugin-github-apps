package dev.droidtop.plugins.githubapps

/** One file attached to a release. [sha256] is the digest the forge publishes for it (GitHub does), when it does. */
data class Asset(
    val id: String,
    val name: String,
    val url: String,
    val size: Long = 0L,
    val sha256: String? = null,
)

/**
 * Chooses which of a release's files is the APK for this device. Pure.
 *
 * The rules, in order (Obtainium's "filter by architecture" idea, https://github.com/ImranR98/Obtainium, GPL-3.0,
 * written out here as one function you can test):
 * 1. Only plain `.apk` files. Bundles (`.apks`, `.xapk`, `.apkm`) are several APKs in one file; droidtop installs
 *    one APK, so they are not offered.
 * 2. The person's name pattern keeps only the files it matches, and their exclude pattern drops the ones it matches.
 * 3. A file whose name says which ABI it is for must name one the device runs; the device's most preferred ABI
 *    wins. A file that names none (a universal build) ranks after every file made for the device.
 * 4. Debug and unsigned builds rank last. Among equals the shorter name wins, then the name in order.
 * [Pick.ambiguous] says when two or more files tie, so the review can tell the person to add a name pattern.
 */
object AssetPicker {
    class Pick(
        val chosen: Asset?,
        /** The files that tie with [chosen] (it is one of them), in order. */
        val tied: List<Asset>,
        /** Why nothing was chosen, in words for the person; null when something was. */
        val problem: String?,
    ) {
        val ambiguous: Boolean get() = tied.size > 1
    }

    private val ARM64 = Regex("arm64|aarch64|armv8|v8a")
    private val X86_64 = Regex("x86[_-]?64|x64|amd64")
    private val ARM32 = Regex("armeabi|armv7|v7a|arm32|armhf|(?<![a-z0-9])arm(?![a-z0-9])")
    private val X86 = Regex("(?<![a-z0-9])(x86|i386|i686|ia32)(?![a-z0-9])")
    private val LAST_RESORT = Regex("(?<![a-z])(debug|unsigned)(?![a-z])")

    /** The Android ABI names ([abis] of the device, such as `arm64-v8a`) a file name says it is built for. */
    fun abisNamedBy(fileName: String): Set<String> {
        var name = fileName.lowercase()
        val found = LinkedHashSet<String>()
        if (ARM64.containsMatchIn(name)) {
            found += "arm64-v8a"
            name = ARM64.replace(name, " ")
        }
        if (X86_64.containsMatchIn(name)) {
            found += "x86_64"
            name = X86_64.replace(name, " ")
        }
        if (ARM32.containsMatchIn(name)) found += "armeabi-v7a"
        if (X86.containsMatchIn(name)) found += "x86"
        return found
    }

    /** Rank of the ABI [wanted] among the device's [abis] (0 is the most preferred); armeabi counts as armeabi-v7a. */
    private fun rankOf(wanted: String, abis: List<String>): Int {
        val i = abis.indexOfFirst { it == wanted || (wanted == "armeabi-v7a" && it == "armeabi") }
        return if (i < 0) -1 else i
    }

    fun isApk(name: String): Boolean = name.endsWith(".apk", ignoreCase = true)

    /**
     * Picks the file for a device that runs [deviceAbis] (most preferred first; empty means "unknown", so no file is
     * excluded for its ABI). [include] and [exclude] are the person's name patterns.
     */
    fun pick(assets: List<Asset>, deviceAbis: List<String>, include: Regex? = null, exclude: Regex? = null): Pick {
        val apks = assets.filter { isApk(it.name) }
        if (apks.isEmpty()) {
            return Pick(null, emptyList(), if (assets.isEmpty()) "the release has no files" else "the release has no plain .apk file (it has: ${names(assets)})")
        }
        val named = apks.filter { (include == null || include.containsMatchIn(it.name)) && (exclude == null || !exclude.containsMatchIn(it.name)) }
        if (named.isEmpty()) return Pick(null, emptyList(), "no .apk file matches your name pattern (the release has: ${names(apks)})")
        val neutral = deviceAbis.size
        val ranked = named.mapNotNull { asset ->
            val lower = asset.name.lowercase()
            val abis = abisNamedBy(asset.name)
            val rank = when {
                abis.isNotEmpty() && deviceAbis.isNotEmpty() -> abis.map { rankOf(it, deviceAbis) }.filter { it >= 0 }.minOrNull() ?: return@mapNotNull null
                abis.isNotEmpty() -> 0
                else -> neutral
            }
            Triple(asset, rank, if (LAST_RESORT.containsMatchIn(lower)) 1 else 0)
        }
        if (ranked.isEmpty()) {
            return Pick(null, emptyList(), "no .apk file is built for this device (${deviceAbis.joinToString()}); the release has: ${names(named)}")
        }
        val order = compareBy<Triple<Asset, Int, Int>>({ it.third }, { it.second }, { it.first.name.length }, { it.first.name })
        val sorted = ranked.sortedWith(order)
        val best = sorted.first()
        val tied = sorted.filter { it.second == best.second && it.third == best.third }.map { it.first }
        return Pick(best.first, tied, null)
    }

    private fun names(assets: List<Asset>) = assets.take(8).joinToString { it.name } + if (assets.size > 8) ", ..." else ""
}
