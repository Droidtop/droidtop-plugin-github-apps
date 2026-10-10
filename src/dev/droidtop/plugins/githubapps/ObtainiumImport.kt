package dev.droidtop.plugins.githubapps

import org.json.JSONArray
import org.json.JSONObject

/** One app from an Obtainium export that this plugin can track. */
class ImportedApp(val tracking: Tracking, val name: String?)

/** What was in an export: the apps to track, and how many were left out, by reason. */
class Imported(val apps: List<ImportedApp>, val skipped: Map<String, Int>)

/**
 * Reads an Obtainium export file (Obtainium > Import/Export > Export, https://github.com/ImranR98/Obtainium, GPL-3.0).
 * The file is JSON with an `apps` list; each app has its `url`, optional `name`, and `additionalSettings` (a JSON
 * object, or in Obtainium's own files a string holding one). Only apps whose address is a release page this plugin
 * reads (GitHub, GitLab, Codeberg) are taken, with these of Obtainium's settings: `includePrereleases`,
 * `apkFilterRegEx` with `invertAPKFilter`, and `sortMethodChoice` (a name sort becomes "highest version"). Obtainium's
 * `autoApkFilterByArch` is always on here. Apps that only track a version (`trackOnly`) are left out, since this
 * catalog exists to install. Pure.
 */
object ObtainiumImport {
    /** Largest export file read. */
    const val MAX_BYTES = 4 * 1024 * 1024

    fun parse(text: String): Result<Imported> {
        val root = runCatching { JSONObject(text) }.getOrNull()
        val list: JSONArray = root?.optJSONArray("apps") ?: runCatching { JSONArray(text) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("That is not an Obtainium export file (no list of apps in it)"))
        val apps = ArrayList<ImportedApp>()
        val skipped = LinkedHashMap<String, Int>()
        val seen = HashSet<String>()
        fun skip(reason: String) {
            skipped[reason] = (skipped[reason] ?: 0) + 1
        }
        for (i in 0 until list.length()) {
            val app = list.optJSONObject(i) ?: continue
            val url = app.optString("url").trim()
            val settings = settingsOf(app.opt("additionalSettings"))
            if (settings.optBoolean("trackOnly")) {
                skip("only tracked in Obtainium, not installed by it")
                continue
            }
            val base = Forges.parse(url).getOrNull()
            if (base == null) {
                skip("from another kind of source: " + (hostOf(url) ?: "no address"))
                continue
            }
            val invert = settings.optBoolean("invertAPKFilter")
            val filter = settings.optString("apkFilterRegEx").trim()
            val filterOk = filter.isEmpty() || runCatching { Regex(filter) }.isSuccess
            if (!filterOk) skip("a file filter droidtop could not read (the app is still taken, unfiltered)")
            val usable = if (filterOk) filter else ""
            val options = Options(
                prerelease = settings.optBoolean("includePrereleases"),
                pattern = if (!invert) usable else "",
                exclude = if (invert) usable else "",
                order = if (settings.optString("sortMethodChoice").let { it == "name" || it.startsWith("smartname") }) Options.ORDER_VERSION else Options.ORDER_PUBLISHED,
            )
            if (!seen.add(base.forge.id)) continue
            val name = settings.optString("appName").ifBlank { app.optString("name") }.trim().takeIf { it.isNotEmpty() }
            apps += ImportedApp(Tracking(base.forge, options, null), name)
        }
        return Result.success(Imported(apps, skipped))
    }

    /** `additionalSettings` is a JSON object, or a string holding one; anything else is "no settings". */
    private fun settingsOf(value: Any?): JSONObject = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrDefault(JSONObject())
        else -> JSONObject()
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
}
