package dev.droidtop.plugins.githubapps

import org.json.JSONArray
import org.json.JSONObject

/**
 * One tracked release page, which is one source (and one app) of this catalog. [pinned] is the signing key(s) the
 * person accepted when they added it (SHA-256 of the certificate, lower-case hex); a later release signed by another
 * key is not offered, and the install is checked against these keys.
 */
internal data class Tracked(
    val forge: Forge,
    val name: String,
    val options: Options,
    val enabled: Boolean = true,
    val pinned: List<String> = emptyList(),
    val added: Long = 0L,
    val updated: Long = 0L,
) {
    val id: String get() = forge.id

    fun toJson(): JSONObject = JSONObject().put("kind", forge.kind.name).put("host", forge.host).put("path", forge.path)
        .put("name", name).put("options", options.toJson()).put("enabled", enabled).put("pinned", JSONArray(pinned))
        .put("added", added).put("updated", updated)

    companion object {
        fun fromJson(o: JSONObject): Tracked? {
            val kind = ForgeKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
            val host = o.optString("host").takeIf { it.isNotBlank() } ?: return null
            val path = o.optString("path").takeIf { it.isNotBlank() } ?: return null
            return Tracked(
                Forge(kind, host, path), o.optString("name").ifBlank { path.substringAfterLast('/') }, Options.fromJson(o.optJSONObject("options")),
                o.optBoolean("enabled", true), strings(o.optJSONArray("pinned")), o.optLong("added"), o.optLong("updated"),
            )
        }

        fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
    }
}

/** A release's APK as read ahead ([ApkProbe]): what droidtop must see in the file it downloads. */
internal data class Fact(
    val tag: String,
    val title: String,
    val published: String,
    val prerelease: Boolean,
    val notes: String,
    val file: String,
    val url: String,
    val size: Long,
    val sha256: String?,
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int,
    val signers: List<String>,
) {
    /** The version shown: the APK's own version name, else the release tag. */
    val shown: String get() = versionName?.takeIf { it.isNotBlank() } ?: tag

    fun toJson(): JSONObject = JSONObject().put("tag", tag).put("title", title).put("published", published).put("prerelease", prerelease)
        .put("notes", notes).put("file", file).put("url", url).put("size", size).put("sha256", sha256 ?: "")
        .put("package", packageName).put("versionCode", versionCode).put("versionName", versionName ?: "").put("minSdk", minSdk)
        .put("signers", JSONArray(signers))

    companion object {
        /** Release notes kept per version, at most. */
        const val MAX_NOTES = 1500

        fun of(release: Release, asset: Asset, facts: ApkFacts) = Fact(
            release.tag, release.title, release.published, release.prerelease, release.notes.take(MAX_NOTES), asset.name, asset.url, asset.size,
            asset.sha256, facts.packageName, facts.versionCode, facts.versionName, facts.minSdk, facts.signers,
        )

        fun fromJson(o: JSONObject): Fact? {
            val pkg = o.optString("package").takeIf { it.isNotBlank() } ?: return null
            val url = o.optString("url").takeIf { it.startsWith("https://") } ?: return null
            return Fact(
                o.optString("tag"), o.optString("title"), o.optString("published"), o.optBoolean("prerelease"), o.optString("notes"),
                o.optString("file"), url, o.optLong("size"), o.optString("sha256").takeIf { it.isNotBlank() }, pkg,
                o.optLong("versionCode"), o.optString("versionName").takeIf { it.isNotBlank() }, o.optInt("minSdk"), Tracked.strings(o.optJSONArray("signers")),
            )
        }
    }
}

/**
 * What the last refresh learned about one source: the versions read ahead (newest first, at most [MAX_FACTS]), the
 * validators the forge gave (`etag` per request address, so an unchanged page costs no request quota), when it was
 * checked and what went wrong.
 */
internal data class State(
    val facts: List<Fact> = emptyList(),
    val etags: Map<String, String> = emptyMap(),
    val checked: Long = 0L,
    val problem: String? = null,
) {
    val newest: Fact? get() = facts.firstOrNull()

    fun toJson(): JSONObject = JSONObject().put("facts", JSONArray(facts.map { it.toJson() })).put("etags", JSONObject(etags))
        .put("checked", checked).put("problem", problem ?: "")

    companion object {
        const val MAX_FACTS = 20

        fun fromJson(o: JSONObject): State = State(
            facts = o.optJSONArray("facts")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Fact::fromJson) } }.orEmpty(),
            etags = o.optJSONObject("etags")?.let { e -> e.keys().asSequence().associateWith { e.optString(it) } }.orEmpty(),
            checked = o.optLong("checked"),
            problem = o.optString("problem").takeIf { it.isNotBlank() },
        )
    }
}

/** A review fetched for the person, kept until Accept (the plugin process may end in between). */
internal data class Pending(val token: String, val entries: List<Entry>, val created: Long) {
    /** One source to add: what it is, and what was read from it (a single source's review has the facts; an import's has none). */
    class Entry(val tracked: Tracked, val fact: Fact?)

    fun toJson(): JSONObject = JSONObject().put("token", token).put("created", created).put(
        "entries",
        JSONArray(entries.map { JSONObject().put("tracked", it.tracked.toJson()).apply { it.fact?.let { f -> put("fact", f.toJson()) } } }),
    )

    companion object {
        fun fromJson(o: JSONObject): Pending = Pending(
            o.optString("token"),
            o.optJSONArray("entries")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val e = a.optJSONObject(i) ?: return@mapNotNull null
                    val tracked = e.optJSONObject("tracked")?.let(Tracked::fromJson) ?: return@mapNotNull null
                    Entry(tracked, e.optJSONObject("fact")?.let(Fact::fromJson))
                }
            }.orEmpty(),
            o.optLong("created"),
        )
    }
}

/**
 * The plugin's own state, in its data folder (docs/plugin-api.md 3 H1): `sources.json` (the tracked pages, in the
 * order added), `pending.json` (reviews awaiting Accept) and `state/<id>.json` per source ([State]).
 */
internal class Store(private val host: Host) {
    private val lock = Any()
    private val states = HashMap<String, State>()

    fun sources(): List<Tracked> = synchronized(lock) {
        val text = host.readText(SOURCES) ?: return emptyList()
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(Tracked::fromJson) }
    }

    private fun saveSources(list: List<Tracked>) = host.writeText(SOURCES, JSONArray(list.map { it.toJson() }).toString())

    /** Adds [tracked], or replaces the source with the same id in place. */
    fun put(tracked: Tracked) = synchronized(lock) {
        val all = sources()
        saveSources(if (all.any { it.id == tracked.id }) all.map { if (it.id == tracked.id) tracked else it } else all + tracked)
    }

    fun remove(id: String) = synchronized(lock) {
        saveSources(sources().filterNot { it.id == id })
        host.delete(stateName(id))
        states.remove(id)
    }

    fun state(id: String): State = synchronized(lock) {
        states[id] ?: (host.readText(stateName(id))?.let { runCatching { State.fromJson(JSONObject(it)) }.getOrNull() } ?: State()).also { states[id] = it }
    }

    fun putState(id: String, state: State) = synchronized(lock) {
        host.writeText(stateName(id), state.toJson().toString())
        states[id] = state
    }

    fun pending(): Map<String, Pending> = synchronized(lock) {
        val o = runCatching { JSONObject(host.readText(PENDING) ?: "{}") }.getOrDefault(JSONObject())
        o.keys().asSequence().associateWith { Pending.fromJson(o.getJSONObject(it)) }
            .filterValues { System.currentTimeMillis() - it.created < PENDING_FOR_MS }
    }

    private fun savePending(all: Map<String, Pending>) =
        host.writeText(PENDING, JSONObject().apply { all.forEach { (k, v) -> put(k, v.toJson()) } }.toString())

    fun putPending(p: Pending) = synchronized(lock) { savePending(pending() + (p.token to p)) }

    fun dropPending(token: String) = synchronized(lock) { savePending(pending() - token) }

    companion object {
        const val SOURCES = "sources.json"
        const val PENDING = "pending.json"
        const val PENDING_FOR_MS = 24L * 60 * 60 * 1000

        fun stateName(id: String) = "state/$id.json"
    }
}
