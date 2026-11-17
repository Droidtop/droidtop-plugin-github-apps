package dev.droidtop.plugins.githubapps

import dev.droidtop.pluginhost.PluginContext
import org.json.JSONObject

/** The broker's refusal or failure, the Kotlin plugin SDK's (Droidtop/tracker#946); this plugin's code names it as before. */
typealias BrokerFailure = dev.droidtop.plugin.sdk.BrokerFailure

/** A forge refused because the request limit is used up; [resetAt] is the epoch second it resets, or 0 when not said. */
class RateLimited(val resetAt: Long, message: String) : java.io.IOException(message)

/**
 * The Kotlin plugin SDK's host calls (dev.droidtop.plugin.sdk.Host: the broker call, net.request and net.download,
 * data files and files.pick, copied from droidtop-plugin-template's tools/kotlin and checked by Template drift,
 * Droidtop/tracker#946), with this plugin's own: an HTTP answer of any status ([http]) and the GitHub API through the
 * signed-in account when droidtop offers it ([githubApi]).
 */
internal class Host(context: PluginContext) : dev.droidtop.plugin.sdk.Host(context) {
    /** One `net.request` request: any status is an answer. At most 128 KiB of body come back (the rest sets `truncated`). */
    fun http(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30_000): HttpAnswer {
        val args = JSONObject().put("url", url).put("as", "base64").put("timeoutMs", timeoutMs)
        if (headers.isNotEmpty()) args.put("headers", JSONObject(headers))
        return HttpAnswer.of(call("net", "request", args))
    }

    @Volatile private var githubHostOp: Boolean? = null

    /**
     * A GitHub API request ([url] starts with https://api.github.com). Where droidtop offers the signed-in GitHub
     * account to plugins (docs/plugin-api.md 3 G4: the `github` API's `request` op, permission `github.api`; the
     * token stays in droidtop), the request goes through it and gets the account's higher request limit. Where it does
     * not (a droidtop that does not have the op yet, the permission not granted, nobody signed in), the same request
     * goes out as an ordinary, unauthenticated `net.request` one. The answer is remembered for the rest of this process.
     */
    fun githubApi(url: String, headers: Map<String, String> = emptyMap()): HttpAnswer {
        if (githubHostOp != false && url.startsWith(GITHUB_API)) {
            try {
                val args = JSONObject().put("path", url.removePrefix(GITHUB_API)).put("as", "base64").put("timeoutMs", 30_000)
                if (headers.isNotEmpty()) args.put("headers", JSONObject(headers))
                val answer = HttpAnswer.of(call("github", "request", args))
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

    companion object {
        const val GITHUB_API = "https://api.github.com"
    }
}
