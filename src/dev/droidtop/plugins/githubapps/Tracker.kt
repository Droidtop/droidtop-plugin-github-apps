package dev.droidtop.plugins.githubapps

import java.io.IOException

/** The device a call is for (`device {sdk, abis, locale}`, docs/plugin-api.md A12). */
internal data class Device(val sdk: Int, val abis: List<String>, val locale: String) {
    companion object {
        fun of(args: org.json.JSONObject): Device {
            val d = args.optJSONObject("device") ?: org.json.JSONObject()
            val abis = d.optJSONArray("abis")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            return Device(d.optInt("sdk", 26), abis, d.optString("locale", "en-US").ifBlank { "en-US" })
        }
    }
}

/**
 * Reads a tracked release page: asks the forge for the releases, chooses the release and the APK in it
 * ([Forges.choose]) and reads the APK ahead ([Prober]) so droidtop is told the package, version code and signing key
 * it must find in the file.
 */
internal class Tracker(private val host: Host, private val prober: Prober) {
    /** What one look at a release page found. */
    sealed class Outcome {
        /** The forge says nothing changed since the last look (HTTP 304). */
        object Unchanged : Outcome()

        class Chosen(val choice: Forges.Choice, val etags: Map<String, String>) : Outcome()
    }

    /**
     * Looks at [forge]. With [etags] from the last look (and [haveFacts]) an unchanged page answers `Unchanged` and costs
     * no request quota. A failure to read the page or to find an APK in it is an [IOException] with the reason.
     */
    fun look(forge: Forge, options: Options, device: Device, etags: Map<String, String>, haveFacts: Boolean): Outcome {
        val seen = HashMap(etags)
        val latest = forge.latestUrl()
        if (!options.prerelease && options.order == Options.ORDER_PUBLISHED && latest != null) {
            val answer = get(forge, latest, seen, haveFacts) ?: return Outcome.Unchanged
            if (answer.status == 200) {
                val release = runCatching { org.json.JSONObject(answer.text) }.getOrNull()?.let { Forges.parseRelease(forge.kind, forge.host, it) }
                if (release != null) {
                    val chosen = Forges.choose(listOf(release), options, device.abis)
                    if (chosen.isSuccess) return Outcome.Chosen(chosen.getOrThrow(), seen)
                }
            }
            // No release marked as the latest, or none with an APK for this device: look through the list.
        }
        var releases: List<Release> = emptyList()
        var read = false
        for (count in LIST_SIZES) {
            val answer = get(forge, forge.listUrl(count), seen, haveFacts) ?: return Outcome.Unchanged
            if (answer.status != 200) throw IOException("${forge.kind.label} answered HTTP ${answer.status} for ${forge.address}")
            // A page of releases with long notes can be larger than droidtop hands over in one answer; ask for fewer.
            if (answer.truncated) continue
            releases = Forges.parseList(forge.kind, forge.host, answer.text)
            read = true
            break
        }
        if (!read) throw IOException("${forge.kind.label}'s list of releases is too large to read")
        val chosen = Forges.choose(releases, options, device.abis)
        return Outcome.Chosen(chosen.getOrElse { throw IOException(it.message ?: "no release found") }, seen)
    }

    /** One GET with the forge's headers and the validator from last time; null on 304. Fills [etags] on a 200. */
    private fun get(forge: Forge, url: String, etags: MutableMap<String, String>, haveFacts: Boolean): HttpAnswer? {
        val headers = HashMap<String, String>()
        headers["Accept"] = if (forge.kind == ForgeKind.GITHUB) "application/vnd.github+json" else "application/json"
        if (haveFacts) etags[url]?.let { headers["If-None-Match"] = it }
        val answer = if (forge.kind == ForgeKind.GITHUB) host.githubApi(url, headers) else host.http(url, headers)
        when {
            answer.status == 304 -> return null
            answer.status == 429 || (answer.status == 403 && limited(answer)) -> throw RateLimited(
                answer.headers["x-ratelimit-reset"]?.toLongOrNull() ?: 0L,
                "${forge.kind.label} has used up the requests it allows" + resetWords(answer.headers["x-ratelimit-reset"]?.toLongOrNull()) +
                    if (forge.kind == ForgeKind.GITHUB && host.usedGithubAccount() != true) ". Signing in to GitHub in droidtop's Accounts raises the limit" else "",
            )
            answer.status == 404 -> return answer
            answer.status !in 200..299 -> throw IOException("${forge.kind.label} answered HTTP ${answer.status} for ${forge.address}")
        }
        answer.headers["etag"]?.let { etags[url] = it }
        return answer
    }

    private fun limited(answer: HttpAnswer): Boolean =
        answer.headers["x-ratelimit-remaining"] == "0" || answer.text.contains("rate limit", ignoreCase = true)

    private fun resetWords(reset: Long?): String {
        if (reset == null || reset <= 0) return ""
        val minutes = ((reset * 1000 - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        return " (it opens again in about $minutes minutes)"
    }

    /** The APK of [choice] read ahead, as a [Fact]. [IOException] when it cannot be read or is not an APK. */
    fun read(choice: Forges.Choice, cancelled: () -> Boolean, progress: (Int, String) -> Unit): Fact =
        Fact.of(choice.release, choice.asset, prober.probe(choice.asset, cancelled, progress))

    companion object {
        /** How many releases to ask for, and the smaller sizes to retry with when a page is too large for one answer. */
        val LIST_SIZES = listOf(8, 3, 1)
    }
}
