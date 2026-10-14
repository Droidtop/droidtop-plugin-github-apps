package dev.droidtop.plugins.githubapps

import dev.droidtop.pluginhost.DroidtopPlugin
import dev.droidtop.pluginhost.PluginArgs
import dev.droidtop.pluginhost.PluginCall
import dev.droidtop.pluginhost.PluginCapability
import dev.droidtop.pluginhost.PluginContext
import dev.droidtop.pluginhost.PluginErrorCode
import dev.droidtop.pluginhost.PluginJobProgress
import dev.droidtop.pluginhost.PluginReply
import dev.droidtop.pluginhost.PluginResult
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/**
 * Release pages as an app catalog for droidtop (`apps.catalog@1`, droidtop docs/plugin-api.md 3 A12), in the spirit
 * of Obtainium (https://github.com/ImranR98/Obtainium, GPL-3.0). Each source is one tracked release page on GitHub,
 * GitLab, Codeberg or another Forgejo/Gitea server; this plugin finds the newest release, chooses the APK in it for
 * this device, reads ahead what app and signing key that APK has, and tells droidtop. droidtop draws everything,
 * downloads, checks the file against what this said, installs it and decides what is an update by comparing with
 * the installed apps; this plugin is never told what is installed.
 */
class GithubAppsPlugin : DroidtopPlugin {
    private lateinit var host: Host
    private lateinit var store: Store
    private lateinit var tracker: Tracker
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    override fun onLoad(context: PluginContext) {
        host = Host(context)
        store = Store(host)
        tracker = Tracker(host, Prober(host))
    }

    override fun invoke(capability: PluginCapability, args: PluginArgs): PluginResult =
        PluginResult.failure("this plugin answers only contract 2 calls")

    override fun handle(call: PluginCall): PluginReply {
        if (call.point != POINT) return PluginReply.error(PluginErrorCode.UNSUPPORTED, "unsupported point ${call.point}")
        val args = call.args
        val device = Device.of(args)
        return try {
            when (call.op) {
                "list_sources", "sources" -> PluginReply.ok(sources())
                "open_link" -> review(args.optString("link"), device)
                "remove_source" -> {
                    val source = store.sources().firstOrNull { it.id == args.optString("source") }
                        ?: return PluginReply.error(PluginErrorCode.NOT_FOUND, "no such tracked page")
                    store.remove(source.id)
                    PluginReply.ok(JSONObject().put("message", "Stopped tracking ${source.name}"))
                }
                "set_source_enabled" -> {
                    val source = store.sources().firstOrNull { it.id == args.optString("source") }
                        ?: return PluginReply.error(PluginErrorCode.NOT_FOUND, "no such tracked page")
                    val enabled = args.optBoolean("enabled", true)
                    store.put(source.copy(enabled = enabled))
                    PluginReply.ok(JSONObject().put("message", if (enabled) "${source.name} is tracked again" else "${source.name} is switched off"))
                }
                "search" -> PluginReply.ok(search(args.optString("query"), args.optInt("limit", 200), device))
                "get_app", "app" -> app(args.optString("id"), device)?.let { PluginReply.ok(it) }
                    ?: PluginReply.error(PluginErrorCode.NOT_FOUND, "no page you track has ${args.optString("id")} for this device")
                "list_latest", "latest" -> PluginReply.ok(latest(args.optString("after").takeIf { it.isNotBlank() }, args.optInt("limit", 500), device))
                else -> PluginReply.error(PluginErrorCode.UNSUPPORTED, "unsupported op ${call.op}")
            }
        } catch (limited: RateLimited) {
            PluginReply.error(PluginErrorCode.RATE_LIMITED, limited.message ?: "the request limit is used up")
        } catch (failure: IOException) {
            PluginReply.error(PluginErrorCode.FAILED, failure.message ?: "the release page could not be read")
        }
    }

    // ------------------------------------------------------------------
    // Sources.
    // ------------------------------------------------------------------

    private fun sources(): JSONObject {
        val list = store.sources()
        val rows = JSONArray(list.map { t ->
            val state = store.state(t.id)
            val note = listOfNotNull(
                state.problem,
                state.newest?.let { "Newest: ${it.shown} (${it.file})" },
                t.options.describe().takeIf { it.isNotEmpty() },
            ).joinToString(". ").take(300)
            JSONObject().put("id", t.id).put("name", t.name).put("address", t.forge.address).put("enabled", t.enabled)
                .put("fingerprint", t.pinned.firstOrNull() ?: "").put("apps", if (state.newest != null) 1 else 0)
                .apply { (t.updated.takeIf { it > 0 } ?: state.checked.takeIf { it > 0 })?.let { put("updated", it) } }
                .apply { if (note.isNotEmpty()) put("note", note) }
        })
        val known = JSONArray().put(
            JSONObject().put("name", "Import from an Obtainium export file").put("address", IMPORT_ADDRESS)
                .put("about", "Tracks the GitHub, GitLab and Codeberg apps in an Obtainium export"),
        )
        return JSONObject().put("sources", rows).put("known", known)
    }

    /** `open_link`: reads what a link or address names, for the person's review; nothing is stored but the review. */
    private fun review(link: String, device: Device): PluginReply {
        val text = link.trim()
        if (text.equals(IMPORT_ADDRESS, ignoreCase = true) || text.equals("import", ignoreCase = true)) return importReview()
        val tracking = Forges.parse(text).getOrElse { return PluginReply.error(PluginErrorCode.INVALID_ARGS, it.message ?: "That is not a release page address") }
        val outcome = tracker.look(tracking.forge, tracking.options, device, emptyMap(), false) as Tracker.Outcome.Chosen
        val fact = tracker.read(outcome.choice, { false }) { _, _ -> }
        if (fact.minSdk > device.sdk) {
            return PluginReply.error(PluginErrorCode.FAILED, "${fact.shown} needs Android API ${fact.minSdk}; this device runs API ${device.sdk}")
        }
        if (fact.signers.isEmpty() && fact.sha256 == null) {
            return PluginReply.error(PluginErrorCode.FAILED, "${fact.file} carries no signature this plugin could read and ${tracking.forge.kind.label} publishes no digest for it, so droidtop could not check it")
        }
        val wanted = tracking.fingerprint
        if (wanted != null && wanted !in fact.signers) {
            return PluginReply.error(PluginErrorCode.FAILED, "The newest release is signed with another key than the fingerprint in the link; nothing was added")
        }
        val tracked = Tracked(tracking.forge, tracking.forge.name, tracking.options, true, fact.signers)
        val token = UUID.randomUUID().toString()
        store.putPending(Pending(token, listOf(Pending.Entry(tracked, fact)), System.currentTimeMillis()))
        val tied = outcome.choice.pick.tied
        val existing = store.sources().any { it.id == tracked.id && it.options == tracked.options }
        val description = buildString {
            append("Newest release ${fact.tag}: ${fact.file}, ${fact.packageName} ${fact.shown} (version code ${fact.versionCode}).")
            if (tracking.options.describe().isNotEmpty()) append(" Options: ${tracking.options.describe()}.")
        }
        val notes = listOfNotNull(
            if (wanted == null && fact.signers.isNotEmpty()) "This signing key is trusted from now on. Compare it with the developer's page first" else null,
            if (fact.signers.isEmpty()) "The signing key could not be read yet; the file's checksum from ${tracking.forge.kind.label} is checked instead" else null,
            if (tied.size > 1) "${tied.size} files fit this device (${tied.joinToString { it.name }}); add a name pattern, for example ?pattern=arm64, to choose" else null,
            if (fact.prerelease) "This release is marked as a pre-release" else null,
        )
        return PluginReply.ok(
            JSONObject().put(
                "review",
                JSONObject().put("token", token).put("name", tracked.name).put("address", tracking.forge.address)
                    .put("fingerprint", fact.signers.firstOrNull() ?: "").put("fingerprintFromLink", wanted != null)
                    .put("description", description).put("apps", 1).put("existing", existing)
                    .apply { if (notes.isNotEmpty()) put("note", notes.joinToString(". ")) },
            ),
        )
    }

    /** Asks the person for an Obtainium export file and reviews the apps in it. */
    private fun importReview(): PluginReply {
        val bytes: ByteArray? = try {
            host.pickFile("application/json", ObtainiumImport.MAX_BYTES)
        } catch (failure: BrokerFailure) {
            return PluginReply.error(PluginErrorCode.FAILED, "droidtop could not ask you for a file (${failure.message}). Allow this plugin to pick files and try again")
        }
        if (bytes == null) return PluginReply.error(PluginErrorCode.FAILED, "No file was picked")
        val imported = ObtainiumImport.parse(bytes.toString(Charsets.UTF_8)).getOrElse { return PluginReply.error(PluginErrorCode.INVALID_ARGS, it.message ?: "That file could not be read") }
        val skipped = imported.skipped.entries.joinToString("; ") { "${it.value} ${it.key}" }
        if (imported.apps.isEmpty()) {
            return PluginReply.error(PluginErrorCode.FAILED, "No app in that file can be tracked here" + if (skipped.isNotEmpty()) " ($skipped)" else "")
        }
        val now = System.currentTimeMillis()
        val entries = imported.apps.map { Pending.Entry(Tracked(it.tracking.forge, it.name ?: it.tracking.forge.name, it.tracking.options, true, emptyList(), now), null) }
        val token = UUID.randomUUID().toString()
        store.putPending(Pending(token, entries, now))
        val have = store.sources().map { it.id }.toSet()
        val names = entries.take(12).joinToString { it.tracked.name } + if (entries.size > 12) ", and ${entries.size - 12} more" else ""
        return PluginReply.ok(
            JSONObject().put(
                "review",
                JSONObject().put("token", token).put("name", "Obtainium export").put("address", IMPORT_ADDRESS)
                    .put("fingerprint", "").put("fingerprintFromLink", false)
                    .put("description", "${entries.size} apps: $names").put("apps", entries.size)
                    .put("existing", entries.all { it.tracked.id in have })
                    .put(
                        "note",
                        listOfNotNull(
                            skipped.takeIf { it.isNotEmpty() }?.let { "Left out: $it" },
                            "Each app's signing key is trusted from its newest release. Refresh reads any the site's limit held back",
                        ).joinToString(". "),
                    ),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Search, app page, latest offers (from what the last look found; no network).
    // ------------------------------------------------------------------

    /** A source and the newest version of it this device can run. */
    private class Offered(val source: Tracked, val fact: Fact)

    private fun offered(device: Device): List<Offered> = store.sources().filter { it.enabled }.mapNotNull { t ->
        store.state(t.id).facts.firstOrNull { it.minSdk <= device.sdk }?.let { Offered(t, it) }
    }

    private fun search(query: String, limit: Int, device: Device): JSONObject {
        val q = query.trim().lowercase()
        val rows = offered(device).mapNotNull { o ->
            val score = when {
                q.isEmpty() -> 0
                o.source.name.lowercase().contains(q) -> 4
                o.fact.packageName.lowercase().contains(q) -> 3
                o.source.forge.path.lowercase().contains(q) -> 2
                o.fact.notes.lowercase().contains(q) -> 1
                else -> return@mapNotNull null
            }
            score to o
        }.sortedWith(compareByDescending<Pair<Int, Offered>> { it.first }.thenBy { it.second.source.name.lowercase() }).take(limit.coerceIn(1, 200))
        return JSONObject().put(
            "apps",
            JSONArray(rows.map { (_, o) ->
                JSONObject().put("id", o.fact.packageName).put("name", o.source.name).put("summary", "${o.source.forge.path} on ${o.source.forge.kind.label}")
                    .put("version", o.fact.shown).put("versionCode", o.fact.versionCode).put("source", o.source.forge.kind.label)
                    .put("antiFeatures", JSONArray())
            }),
        )
    }

    private fun app(id: String, device: Device): JSONObject? {
        val o = store.sources().filter { it.enabled }.firstNotNullOfOrNull { t ->
            val facts = store.state(t.id).facts.filter { it.packageName == id }
            if (facts.isEmpty()) null else t to facts
        } ?: return null
        val (tracked, facts) = o
        val usable = facts.filter { it.minSdk <= device.sdk }
        return JSONObject().put(
            "app",
            JSONObject().put("id", id).put("name", tracked.name).put("summary", "${tracked.forge.path} on ${tracked.forge.kind.label}")
                .put("description", "Tracked from ${tracked.forge.address}." + (tracked.options.describe().takeIf { it.isNotEmpty() }?.let { " Options: $it." } ?: ""))
                .put("website", tracked.forge.address).put("sourceCode", tracked.forge.address).put("author", tracked.forge.owner)
                .put("source", tracked.forge.kind.label)
                .put(
                    "versions",
                    JSONArray(usable.map { f ->
                        JSONObject().put("version", f.shown).put("versionCode", f.versionCode).put("size", f.size)
                            .put("signers", JSONArray(f.signers)).put("minSdk", f.minSdk).put("whatsNew", f.notes.take(1000))
                            .apply { runCatching { java.time.Instant.parse(f.published).toEpochMilli() }.getOrNull()?.let { put("added", it) } }
                            .put(
                                "antiFeatures",
                                JSONArray(
                                    if (f.prerelease) {
                                        listOf(JSONObject().put("key", "PreRelease").put("label", "Pre-release").put("reason", "${tracked.forge.kind.label} marks this release as a pre-release"))
                                    } else {
                                        emptyList()
                                    },
                                ),
                            )
                    }),
                ),
        )
    }

    private fun latest(after: String?, limit: Int, device: Device): JSONObject {
        val rows = offered(device).distinctBy { it.fact.packageName }.sortedBy { it.fact.packageName }.filter { after == null || it.fact.packageName > after }
        val page = rows.take(limit.coerceIn(1, 500))
        val out = JSONObject().put(
            "apps",
            JSONArray(page.map { o ->
                JSONObject().put("id", o.fact.packageName).put("name", o.source.name).put("version", o.fact.shown)
                    .put("versionCode", o.fact.versionCode).put("signers", JSONArray(o.fact.signers)).put("source", o.source.forge.kind.label)
            }),
        )
        if (rows.size > page.size) out.put("next", page.last().fact.packageName)
        return out
    }

    // ------------------------------------------------------------------
    // Jobs: add_source, refresh, acquire.
    // ------------------------------------------------------------------

    override fun startJob(jobId: String, capability: PluginCapability, args: PluginArgs, progress: PluginJobProgress) {
        val call = args.string("call")?.let { PluginCall.fromJson(it) }
        if (call == null || call.point != POINT) {
            progress.complete(PluginResult.failure("unsupported job"))
            return
        }
        Thread({
            val result = try {
                runJob(jobId, call, progress)
            } catch (failure: Exception) {
                PluginResult.failure(failure.message ?: failure.javaClass.simpleName)
            } finally {
                cancelled.remove(jobId)
            }
            progress.complete(result)
        }, "github-apps-$jobId").start()
    }

    private fun runJob(jobId: String, call: PluginCall, progress: PluginJobProgress): PluginResult {
        val device = Device.of(call.args)
        val isCancelled = { jobId in cancelled }
        return when (call.op) {
            "add_source" -> {
                val pending = store.pending()[call.args.optString("token")] ?: return PluginResult.failure("That review has expired; open the address again")
                val now = System.currentTimeMillis()
                val toRead = ArrayList<Tracked>()
                var added = 0
                for (entry in pending.entries) {
                    val have = store.sources().firstOrNull { it.id == entry.tracked.id }
                    if (entry.fact != null) {
                        store.put(entry.tracked.copy(added = have?.added ?: now, updated = now))
                        store.putState(entry.tracked.id, State(listOf(entry.fact), emptyMap(), now, null))
                        added++
                    } else if (have == null) {
                        store.put(entry.tracked.copy(added = now))
                        toRead += entry.tracked
                        added++
                    }
                }
                store.dropPending(pending.token)
                val read = if (toRead.isEmpty()) "" else " " + refreshAll(toRead, device, isCancelled, progress)
                PluginResult.success(mapOf("message" to (if (added == 1 && pending.entries.size == 1) "Tracking ${pending.entries[0].tracked.name}." else "Added $added of ${pending.entries.size} apps.") + read))
            }
            "refresh" -> {
                val only = call.args.optString("source").takeIf { it.isNotBlank() }
                val list = store.sources().filter { it.enabled && (only == null || it.id == only) }
                PluginResult.success(mapOf("message" to if (list.isEmpty()) "No page to refresh" else refreshAll(list, device, isCancelled, progress)))
            }
            "acquire" -> acquire(call.args.optString("id"), call.args.optLong("versionCode", 0L), device)
            else -> PluginResult.failure("unsupported job ${call.op}")
        }
    }

    /** Looks at every page in [list] in turn; stops at the forge's request limit. Returns one line about it. */
    private fun refreshAll(list: List<Tracked>, device: Device, isCancelled: () -> Boolean, progress: PluginJobProgress): String {
        var changed = 0
        var same = 0
        val problems = ArrayList<String>()
        var limit: String? = null
        for ((i, t) in list.withIndex()) {
            if (isCancelled()) break
            progress.report(i * 100 / list.size, "Checking ${t.name}")
            try {
                val before = store.state(t.id).newest?.versionCode
                val after = refreshOne(t, device, isCancelled) { percent, line -> progress.report(if (percent >= 0) (i * 100 + percent) / list.size else i * 100 / list.size, line) }
                if (after.problem != null) problems += "${t.name}: ${after.problem}" else if (after.newest?.versionCode != before) changed++ else same++
            } catch (stop: RateLimited) {
                limit = stop.message
                break
            }
        }
        return listOfNotNull(
            "$changed new, $same unchanged" + if (problems.isNotEmpty()) ", ${problems.size} with a problem" else "",
            limit?.let { "Stopped: $it" },
            problems.take(3).joinToString("; ").takeIf { it.isNotEmpty() },
        ).joinToString(". ") + "."
    }

    /** Looks at one page, reads a new release's APK ahead and stores what it found. [RateLimited] stops the caller. */
    private fun refreshOne(t: Tracked, device: Device, isCancelled: () -> Boolean, progress: (Int, String) -> Unit): State {
        val old = store.state(t.id)
        val now = System.currentTimeMillis()
        fun failed(why: String) = old.copy(checked = now, problem = why).also { store.putState(t.id, it) }
        val outcome = try {
            tracker.look(t.forge, t.options, device, old.etags, old.facts.isNotEmpty())
        } catch (limited: RateLimited) {
            throw limited
        } catch (failure: IOException) {
            return failed(failure.message ?: "could not be read")
        }
        if (outcome is Tracker.Outcome.Unchanged) return old.copy(checked = now, problem = null).also { store.putState(t.id, it) }
        outcome as Tracker.Outcome.Chosen
        val release = outcome.choice.release
        val asset = outcome.choice.asset
        val known = old.facts.firstOrNull { it.tag == release.tag && it.file == asset.name && it.url == asset.url && it.size == asset.size }
        val fact = known ?: try {
            tracker.read(outcome.choice, isCancelled, progress)
        } catch (failure: IOException) {
            return failed("${release.tag}: ${failure.message ?: "its APK could not be read"}")
        }
        var pinned = t.pinned
        if (pinned.isEmpty() && fact.signers.isNotEmpty()) {
            pinned = fact.signers
            store.put(t.copy(pinned = pinned))
        }
        if (pinned.isNotEmpty() && fact.signers.isNotEmpty() && fact.signers.none { it in pinned }) {
            return failed("${release.tag} is signed with a different key than the one you accepted, so it is not offered. Remove this page and add it again if you trust the new key")
        }
        val facts = (listOf(fact) + old.facts.filterNot { it.versionCode == fact.versionCode }.filter { it.packageName == fact.packageName })
            .sortedByDescending { it.versionCode }.take(State.MAX_FACTS)
        val state = State(facts, outcome.etags, now, null)
        store.putState(t.id, state)
        store.sources().firstOrNull { it.id == t.id }?.let { store.put(it.copy(updated = now)) }
        return state
    }

    /** `acquire`: the download for [id] at [versionCode] (0: the newest this device can run) and what the APK must be. */
    private fun acquire(id: String, versionCode: Long, device: Device): PluginResult {
        for (t in store.sources().filter { it.enabled }) {
            val fact = store.state(t.id).facts.firstOrNull { it.packageName == id && it.minSdk <= device.sdk && (versionCode <= 0 || it.versionCode == versionCode) } ?: continue
            val signers = t.pinned.ifEmpty { fact.signers }
            if (signers.isEmpty() && fact.sha256 == null) return PluginResult.failure("${fact.file} has neither a digest nor a readable signing key to check it with")
            if (t.pinned.isNotEmpty() && fact.signers.isNotEmpty() && fact.signers.none { it in t.pinned }) {
                return PluginResult.failure("${fact.tag} is signed with a different key than the one you accepted for ${t.name}")
            }
            val download = JSONObject().put("url", fact.url).put("fileName", "${id}_${fact.versionCode}.apk")
            fact.sha256?.let { download.put("sha256", it) }
            fact.size.takeIf { it > 0 }?.let { download.put("size", it) }
            return PluginResult.success(
                mapOf(
                    "download" to download.toString(),
                    "packageName" to id,
                    "versionCode" to fact.versionCode.toString(),
                    "signers" to signers.joinToString(","),
                    "summary" to "${t.name} ${fact.shown} from ${t.forge.kind.label}",
                ),
            )
        }
        return PluginResult.failure("No page you track has that version for this device")
    }

    override fun cancelJob(jobId: String) {
        cancelled += jobId
    }

    companion object {
        const val POINT = "apps.catalog"

        /** The "address" of the import entry in the list of sources to add. */
        const val IMPORT_ADDRESS = "obtainium:import"
    }
}
