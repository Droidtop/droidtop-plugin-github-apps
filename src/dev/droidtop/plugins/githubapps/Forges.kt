package dev.droidtop.plugins.githubapps

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** The kinds of release page this plugin reads. */
enum class ForgeKind(val label: String) {
    GITHUB("GitHub"),
    GITLAB("GitLab"),
    /** Forgejo and Gitea (Codeberg runs Forgejo); their release API is the same. */
    FORGEJO("Forgejo"),
}

/** What the person chose for one tracked app. They ride on the address as query parameters (see [Forges.parse]). */
data class Options(
    val prerelease: Boolean = false,
    /** Keep only APK files whose name matches this regular expression; empty keeps all. */
    val pattern: String = "",
    /** Drop APK files whose name matches this regular expression. */
    val exclude: String = "",
    /** `published` (the forge's own order, newest first) or `version` (the highest version number). */
    val order: String = ORDER_PUBLISHED,
) {
    val includeRegex: Regex? get() = pattern.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) }
    val excludeRegex: Regex? get() = exclude.takeIf { it.isNotBlank() }?.let { Regex(it, RegexOption.IGNORE_CASE) }

    /** The options in words, for a source's note; empty when all are the defaults. */
    fun describe(): String = listOfNotNull(
        if (prerelease) "pre-releases included" else null,
        pattern.takeIf { it.isNotBlank() }?.let { "files matching \"$it\"" },
        exclude.takeIf { it.isNotBlank() }?.let { "not \"$it\"" },
        if (order == ORDER_VERSION) "highest version, not newest release" else null,
    ).joinToString(", ")

    fun toJson(): JSONObject = JSONObject().put("prerelease", prerelease).put("pattern", pattern).put("exclude", exclude).put("order", order)

    companion object {
        const val ORDER_PUBLISHED = "published"
        const val ORDER_VERSION = "version"

        fun fromJson(o: JSONObject?): Options = if (o == null) Options() else Options(
            o.optBoolean("prerelease"), o.optString("pattern"), o.optString("exclude"),
            o.optString("order").takeIf { it == ORDER_VERSION } ?: ORDER_PUBLISHED,
        )
    }
}

/** A repository on a forge: where its releases are. [path] is `owner/repo` (GitLab: `group/subgroup/project`). */
data class Forge(val kind: ForgeKind, val host: String, val path: String) {
    /** The repository's own page, which is the address the person sees and the source is known by. */
    val address: String get() = "https://$host/$path"

    val id: String get() = EntryHex.sha256(("${kind.name}|$host|$path").lowercase()).take(16)

    /** The owner (or top group), for an app's "author". */
    val owner: String get() = path.substringBefore('/')

    val name: String get() = path.substringAfterLast('/')

    /** The API address of the release list, newest [count] releases. */
    fun listUrl(count: Int): String = when (kind) {
        ForgeKind.GITHUB -> "https://api.github.com/repos/$path/releases?per_page=$count"
        ForgeKind.GITLAB -> "https://$host/api/v4/projects/${URLEncoder.encode(path, "UTF-8")}/releases?per_page=$count"
        ForgeKind.FORGEJO -> "https://$host/api/v1/repos/$path/releases?limit=$count"
    }

    /** The API address of the newest release that is not a draft or pre-release, where the forge has one call for it. */
    fun latestUrl(): String? = when (kind) {
        ForgeKind.GITHUB -> "https://api.github.com/repos/$path/releases/latest"
        ForgeKind.FORGEJO -> "https://$host/api/v1/repos/$path/releases/latest"
        ForgeKind.GITLAB -> null
    }
}

/** A tracked address as typed, read: the repository, the options it carried and a signing-key fingerprint it named. */
data class Tracking(val forge: Forge, val options: Options, val fingerprint: String?)

object EntryHex {
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun sha256(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}

/** One release as a forge lists it. [published] is the forge's timestamp text (ISO 8601), only shown. */
data class Release(
    val tag: String,
    val title: String,
    val prerelease: Boolean,
    val draft: Boolean,
    val published: String,
    val notes: String,
    val assets: List<Asset>,
)

object Forges {
    private val KNOWN_HOSTS = mapOf("github.com" to ForgeKind.GITHUB, "gitlab.com" to ForgeKind.GITLAB, "codeberg.org" to ForgeKind.FORGEJO)
    private val FINGERPRINT = Regex("[0-9a-fA-F]{64}")
    private val SEGMENT = Regex("[A-Za-z0-9._~-]+")

    /**
     * Reads what the person gave: a repository address or a release page of one (`https://github.com/owner/repo`,
     * `.../releases`, `.../releases/tag/v1.2`, `github.com/owner/repo`, `owner/repo` for GitHub); on a Forgejo or Gitea
     * server, or a GitLab other than gitlab.com, the kind goes first (`forgejo:https://git.example.org/owner/repo`,
     * `gitlab:https://gitlab.example.org/group/project`). Query parameters are the options: `prerelease=1`,
     * `pattern=<regex>`, `exclude=<regex>`, `order=version`, and `fingerprint=<SHA-256 of the signing key>`.
     * Returns the reason in [Result.failure] when it is not an address this plugin can read.
     */
    fun parse(input: String): Result<Tracking> {
        var text = input.trim()
        if (text.isEmpty()) return fail("Give the address of a release page, such as https://github.com/owner/repo")
        var kind: ForgeKind? = null
        for ((prefix, k) in listOf("github:" to ForgeKind.GITHUB, "gitlab:" to ForgeKind.GITLAB, "forgejo:" to ForgeKind.FORGEJO, "gitea:" to ForgeKind.FORGEJO)) {
            if (text.startsWith(prefix, ignoreCase = true) && !text.startsWith("$prefix//")) {
                kind = k
                text = text.substring(prefix.length).trim()
            }
        }
        if (!text.contains("://")) {
            text = if (text.count { it == '/' } == 1 && !text.substringBefore('/').contains('.') && (kind == null || kind == ForgeKind.GITHUB)) "https://github.com/$text" else "https://$text"
        }
        val uri = runCatching { URI(text) }.getOrNull() ?: return fail("That is not an address")
        if (!"https".equals(uri.scheme, ignoreCase = true)) return fail("Only https addresses are read")
        val host = uri.host?.lowercase() ?: return fail("That address has no host")
        val resolved = kind ?: KNOWN_HOSTS[host] ?: return fail(
            "droidtop does not know what kind of site $host is. Put the kind first: forgejo:$text or gitlab:$text",
        )
        val segments = (uri.rawPath ?: "").split('/').filter { it.isNotEmpty() }
        val repoSegments = when (resolved) {
            ForgeKind.GITLAB -> segments.takeWhile { it != "-" && it != "releases" }
            else -> segments.take(2)
        }
        val ok = repoSegments.size >= 2 && repoSegments.all { SEGMENT.matches(it) && it != "." && it != ".." }
        if (!ok) return fail("That address does not name a repository (owner and name)")
        val path = repoSegments.joinToString("/").removeSuffix(".git")
        val query = queryOf(uri.rawQuery)
        val pattern = query["pattern"].orEmpty()
        val exclude = query["exclude"].orEmpty()
        for (p in listOf(pattern, exclude)) {
            if (p.length > 200) return fail("A name pattern is at most 200 characters")
            if (p.isNotBlank() && runCatching { Regex(p) }.isFailure) return fail("\"$p\" is not a valid pattern")
        }
        val fingerprint = query["fingerprint"]?.replace(":", "")?.takeIf { it.isNotBlank() }
        if (fingerprint != null && !FINGERPRINT.matches(fingerprint)) return fail("The fingerprint must be 64 hexadecimal digits (a SHA-256)")
        val options = Options(
            prerelease = query["prerelease"]?.lowercase() in setOf("1", "true", "yes", "on"),
            pattern = pattern,
            exclude = exclude,
            order = query["order"]?.takeIf { it == Options.ORDER_VERSION } ?: Options.ORDER_PUBLISHED,
        )
        return Result.success(Tracking(Forge(resolved, host, path), options, fingerprint?.lowercase()))
    }

    private fun fail(reason: String): Result<Tracking> = Result.failure(IllegalArgumentException(reason))

    private fun queryOf(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.contains('=') }.associate { pair ->
        val key = pair.substringBefore('=')
        key to runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrDefault("")
    }

    // ------------------------------------------------------------------
    // What the forges answer.
    // ------------------------------------------------------------------

    /** The releases a list call answered, newest first as the forge ordered them. A body that is not a JSON list gives none. */
    fun parseList(kind: ForgeKind, host: String, body: String): List<Release> {
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { o -> parseRelease(kind, host, o) } }
    }

    /** One release object (the answer of a "latest" call, or an element of a list), or null when it names no tag. */
    fun parseRelease(kind: ForgeKind, host: String, o: JSONObject): Release? {
        val tag = o.optString("tag_name").trim().takeIf { it.isNotEmpty() } ?: return null
        return when (kind) {
            ForgeKind.GITHUB, ForgeKind.FORGEJO -> Release(
                tag = tag,
                title = o.optString("name").trim(),
                prerelease = o.optBoolean("prerelease"),
                draft = o.optBoolean("draft"),
                published = o.optString("published_at").ifBlank { o.optString("created_at") },
                notes = o.optString("body"),
                assets = (o.optJSONArray("assets") ?: JSONArray()).let { a ->
                    (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::gitHubAsset) }
                },
            )
            ForgeKind.GITLAB -> Release(
                tag = tag,
                title = o.optString("name").trim(),
                prerelease = o.optBoolean("upcoming_release"),
                draft = false,
                published = o.optString("released_at"),
                notes = o.optString("description"),
                assets = (o.optJSONObject("assets")?.optJSONArray("links") ?: JSONArray()).let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        val link = a.optJSONObject(i) ?: return@mapNotNull null
                        val url = link.optString("direct_asset_url").ifBlank { link.optString("url") }
                        val name = link.optString("name").ifBlank { url.substringAfterLast('/') }
                        if (url.isBlank() || !url.startsWith("https://")) null else Asset(link.opt("id")?.toString() ?: name, name, url)
                    }
                },
            )
        }
    }

    /** A GitHub, Gitea or Forgejo asset object. GitHub's `digest` is `sha256:<hex>`. */
    private fun gitHubAsset(o: JSONObject): Asset? {
        val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
        val url = o.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: return null
        val digest = o.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()?.takeIf { FINGERPRINT.matches(it) }
        return Asset(o.opt("id")?.toString() ?: name, name, url, o.optLong("size"), digest)
    }

    // ------------------------------------------------------------------
    // Which release.
    // ------------------------------------------------------------------

    /** The release and file chosen, and the other releases looked at in vain (for a message). */
    class Choice(val release: Release, val asset: Asset, val pick: AssetPicker.Pick)

    /**
     * Chooses the release to offer from [releases] (the forge's order, newest first): drafts are never offered,
     * pre-releases only when [Options.prerelease] is on (a release the forge does not flag but whose tag names itself a
     * pre-release counts too), and a release with no usable APK is passed over for an older one. Under
     * [Options.ORDER_VERSION] the highest version among those wins instead of the newest. Returns the reason when none.
     */
    fun choose(releases: List<Release>, options: Options, deviceAbis: List<String>): Result<Choice> {
        val include = options.includeRegex
        val exclude = options.excludeRegex
        var why: String? = null
        val candidates = ArrayList<Choice>()
        for (release in releases) {
            if (release.draft) continue
            if (!options.prerelease && (release.prerelease || Versions.isPreRelease(release.tag))) continue
            val pick = AssetPicker.pick(release.assets, deviceAbis, include, exclude)
            val asset = pick.chosen
            if (asset == null) {
                if (why == null) why = "${release.tag}: ${pick.problem}"
                continue
            }
            candidates += Choice(release, asset, pick)
            if (options.order != Options.ORDER_VERSION) break
        }
        val best = if (options.order == Options.ORDER_VERSION) Versions.newest(candidates) { it.release.tag } else candidates.firstOrNull()
        return if (best != null) {
            Result.success(best)
        } else {
            Result.failure(
                IllegalStateException(
                    when {
                        releases.isEmpty() -> "No release is published there"
                        why != null -> "No release has an APK for this device"
                        else -> "No release is left once drafts" + (if (options.prerelease) "" else " and pre-releases") + " are set aside"
                    },
                ),
            )
        }
    }
}
