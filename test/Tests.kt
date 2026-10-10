package dev.droidtop.plugins.githubapps

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess

// Plain JVM tests, no framework: test/run.sh compiles the pure sources and this file with kotlinc and runs main().
// What they cover: version comparison (Versions), choosing the APK (AssetPicker), reading addresses and releases
// (Forges), the Obtainium import, and reading an APK ahead (ApkProbe) against a real AndroidManifest.xml.

private var failures = 0
private var checks = 0

private fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
    checks++
    if (!ok) {
        failures++
        println("FAIL $name ${detail()}")
    }
}

private fun <T> same(name: String, expected: T, actual: T) = check(name, expected == actual) { "expected <$expected> but was <$actual>" }

private fun sha256(bytes: ByteArray) = EntryHex.hex(MessageDigest.getInstance("SHA-256").digest(bytes))

// ---------------------------------------------------------------- versions

private fun versions() {
    fun cmp(a: String, b: String) = Versions.compare(a, b).coerceIn(-1, 1)
    same("v prefix is ignored", 0, cmp("v1.2.3", "1.2.3"))
    same("numbers compare as numbers", -1, cmp("1.9", "1.10"))
    same("trailing zeros are the same version", 0, cmp("1.0", "1.0.0"))
    same("a release is newer than its rc", 1, cmp("1.0", "1.0-rc1"))
    same("beta is older than rc", -1, cmp("1.0-beta2", "1.0-rc1"))
    same("rc1 is older than rc2", -1, cmp("1.0-rc1", "1.0-rc2"))
    same("a patch is newer than the rc of its minor", 1, cmp("1.0.1", "1.0-rc1"))
    same("date versions", -1, cmp("2024.05.01", "2024.10.01"))
    same("a prefix word is ignored", 0, cmp("release-3.4", "3.4"))
    same("a longer version is newer", 1, cmp("3.4.1", "3.4"))
    same("build metadata is ignored", 0, cmp("1.2.3+45", "1.2.3"))
    same("huge numbers do not overflow", -1, cmp("1.99999999999999999999", "1.100000000000000000000"))
    same("alpha is older than beta", -1, cmp("2.0.0-alpha", "2.0.0-beta"))
    same("rc glued to a number", -1, cmp("1.0rc1", "1.0rc2"))
    check("rc is a pre-release", Versions.isPreRelease("1.0-rc1"))
    check("nightly is a pre-release", Versions.isPreRelease("nightly"))
    check("a plain version is not", !Versions.isPreRelease("v2.10.3"))
    same("newest", "v1.10", Versions.newest(listOf("v1.9", "v1.10", "v1.2")) { it })
    same("newest of none", null, Versions.newest(emptyList<String>()) { it })
}

// ---------------------------------------------------------------- assets

private fun asset(name: String, size: Long = 1000L) = Asset(name, name, "https://example.org/$name", size)

private fun assets() {
    val arm64 = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    same("arm64 file", setOf("arm64-v8a"), AssetPicker.abisNamedBy("app-arm64-v8a-release.apk"))
    same("aarch64 file", setOf("arm64-v8a"), AssetPicker.abisNamedBy("app-aarch64.apk"))
    same("x86_64 is not x86", setOf("x86_64"), AssetPicker.abisNamedBy("app-x86_64-release.apk"))
    same("x86 file", setOf("x86"), AssetPicker.abisNamedBy("app-x86-release.apk"))
    same("armeabi-v7a file", setOf("armeabi-v7a"), AssetPicker.abisNamedBy("app_armeabi-v7a.apk"))
    same("arm file", setOf("armeabi-v7a"), AssetPicker.abisNamedBy("app-arm-release.apk"))
    same("no abi in the name", emptySet<String>(), AssetPicker.abisNamedBy("app-release.apk"))
    same("a word that contains arm is not arm", emptySet<String>(), AssetPicker.abisNamedBy("farmer.apk"))

    val all = listOf(asset("app-x86_64.apk"), asset("app-armeabi-v7a.apk"), asset("app-arm64-v8a.apk"), asset("app-x86.apk"), asset("app.apks"), asset("notes.txt"))
    same("arm64 device takes the arm64 file", "app-arm64-v8a.apk", AssetPicker.pick(all, arm64).chosen?.name)
    same("an arm32 only device takes the arm32 file", "app-armeabi-v7a.apk", AssetPicker.pick(all, listOf("armeabi-v7a", "armeabi")).chosen?.name)
    same("an x86_64 device takes the x86_64 file", "app-x86_64.apk", AssetPicker.pick(all, listOf("x86_64", "x86")).chosen?.name)
    check("one file fits, so no tie", !AssetPicker.pick(all, arm64).ambiguous)

    val withUniversal = listOf(asset("app-universal.apk"), asset("app-arm64-v8a.apk"))
    same("a file made for the device beats a universal one", "app-arm64-v8a.apk", AssetPicker.pick(withUniversal, arm64).chosen?.name)
    same("a universal file is taken when nothing is made for the device", "app-universal.apk", AssetPicker.pick(withUniversal, listOf("x86_64")).chosen?.name)
    same("unknown device ABIs exclude nothing", "app-arm64-v8a.apk", AssetPicker.pick(listOf(asset("app-arm64-v8a.apk")), emptyList()).chosen?.name)

    val onlyX86 = AssetPicker.pick(listOf(asset("app-x86_64.apk")), arm64)
    check("nothing fits an arm device", onlyX86.chosen == null && onlyX86.problem!!.contains("built for this device"))
    val bundles = AssetPicker.pick(listOf(asset("app.apks"), asset("app.xapk"), asset("app.apkm")), arm64)
    check("bundles are not offered", bundles.chosen == null && bundles.problem!!.contains("no plain .apk"))
    check("an empty release", AssetPicker.pick(emptyList(), arm64).problem!!.contains("no files"))

    val flavours = listOf(asset("app-foss-arm64-v8a.apk"), asset("app-full-arm64-v8a.apk"))
    check("two flavours tie", AssetPicker.pick(flavours, arm64).ambiguous)
    same("a name pattern chooses", "app-foss-arm64-v8a.apk", AssetPicker.pick(flavours, arm64, Regex("foss")).chosen?.name)
    same("an exclude pattern chooses", "app-full-arm64-v8a.apk", AssetPicker.pick(flavours, arm64, null, Regex("foss")).chosen?.name)
    check("a pattern that matches nothing says so", AssetPicker.pick(flavours, arm64, Regex("nope")).problem!!.contains("name pattern"))

    val debug = listOf(asset("app-debug.apk"), asset("app-release.apk"))
    same("debug builds rank last", "app-release.apk", AssetPicker.pick(debug, arm64).chosen?.name)
    same("a debug build is still taken when it is all there is", "app-debug.apk", AssetPicker.pick(listOf(asset("app-debug.apk")), arm64).chosen?.name)
    same("upper-case extension", "APP.APK", AssetPicker.pick(listOf(asset("APP.APK")), arm64).chosen?.name)
}

// ---------------------------------------------------------------- addresses and releases

private fun forges() {
    fun parse(s: String) = Forges.parse(s).getOrNull()
    val a = parse("https://github.com/octo/app")!!
    same("github", "https://github.com/octo/app", a.forge.address)
    same("kind", ForgeKind.GITHUB, a.forge.kind)
    same("releases page", "https://github.com/octo/app", parse("https://github.com/octo/app/releases")!!.forge.address)
    same("a tag page", "https://github.com/octo/app", parse("https://github.com/octo/app/releases/tag/v1.2")!!.forge.address)
    same("a download link", "https://github.com/octo/app", parse("https://github.com/octo/app/releases/download/v1/app.apk")!!.forge.address)
    same("without a scheme", "https://github.com/octo/app", parse("github.com/octo/app")!!.forge.address)
    same("owner/repo", "https://github.com/octo/app", parse("octo/app")!!.forge.address)
    same(".git is dropped", "https://github.com/octo/app", parse("https://github.com/octo/app.git")!!.forge.address)
    same("codeberg", ForgeKind.FORGEJO, parse("https://codeberg.org/o/r")!!.forge.kind)
    same("gitlab with subgroups", "group/sub/project", parse("https://gitlab.com/group/sub/project/-/releases")!!.forge.path)
    same("forgejo elsewhere", ForgeKind.FORGEJO, parse("forgejo:https://git.example.org/o/r")!!.forge.kind)
    same("gitlab elsewhere", "gitlab.example.org", parse("gitlab:https://gitlab.example.org/g/p")!!.forge.host)
    check("an unknown site needs its kind", Forges.parse("https://git.example.org/o/r").exceptionOrNull()!!.message!!.contains("forgejo:"))
    check("http is refused", Forges.parse("http://github.com/o/r").isFailure)
    check("a bare owner is refused", Forges.parse("https://github.com/octo").isFailure)
    check("nothing is refused", Forges.parse("  ").isFailure)
    check("a bad pattern is refused", Forges.parse("https://github.com/o/r?pattern=(").isFailure)

    val o = parse("https://github.com/o/r?prerelease=1&pattern=arm64&exclude=debug&order=version&fingerprint=" + "AB:".repeat(31) + "AB")!!
    check("options", o.options == Options(true, "arm64", "debug", Options.ORDER_VERSION))
    same("fingerprint is lower-case without colons", "ab".repeat(32), o.fingerprint)
    check("a short fingerprint is refused", Forges.parse("https://github.com/o/r?fingerprint=abcd").isFailure)
    same("defaults", Options(), parse("https://github.com/o/r")!!.options)
    same("ids do not depend on case", parse("https://github.com/Octo/App")!!.forge.id, parse("https://github.com/octo/app")!!.forge.id)
    check("api urls", a.forge.listUrl(8) == "https://api.github.com/repos/octo/app/releases?per_page=8" && a.forge.latestUrl() == "https://api.github.com/repos/octo/app/releases/latest")
    same("gitlab api url", "https://gitlab.com/api/v4/projects/group%2Fsub%2Fproject/releases?per_page=3", parse("https://gitlab.com/group/sub/project")!!.forge.listUrl(3))
}

private const val GITHUB_LIST = """[
 {"tag_name":"v2.0.0-rc1","name":"2.0 rc","draft":false,"prerelease":true,"published_at":"2026-10-01T10:00:00Z","body":"candidate",
  "assets":[{"id":11,"name":"app-arm64-v8a.apk","size":5000,"browser_download_url":"https://github.com/o/r/releases/download/v2.0.0-rc1/app-arm64-v8a.apk","digest":"sha256:1111111111111111111111111111111111111111111111111111111111111111"}]},
 {"tag_name":"v1.9.0","name":"","draft":false,"prerelease":false,"published_at":"2026-09-01T10:00:00Z","body":"notes",
  "assets":[{"id":21,"name":"source.zip","size":10,"browser_download_url":"https://github.com/o/r/releases/download/v1.9.0/source.zip"}]},
 {"tag_name":"v1.8.0","name":"1.8","draft":false,"prerelease":false,"published_at":"2026-08-01T10:00:00Z","body":"older",
  "assets":[{"id":31,"name":"app-arm64-v8a.apk","size":4000,"browser_download_url":"https://github.com/o/r/releases/download/v1.8.0/app-arm64-v8a.apk","digest":"sha256:2222222222222222222222222222222222222222222222222222222222222222"},
            {"id":32,"name":"app-x86_64.apk","size":4100,"browser_download_url":"https://github.com/o/r/releases/download/v1.8.0/app-x86_64.apk"}]},
 {"tag_name":"v9.9.9","name":"draft","draft":true,"prerelease":false,"published_at":"","body":"","assets":[]}
]"""

private fun releases() {
    val list = Forges.parseList(ForgeKind.GITHUB, "github.com", GITHUB_LIST)
    same("four releases", 4, list.size)
    same("digest is read", "11".repeat(32), list[0].assets[0].sha256)
    same("no digest", null, list[2].assets[1].sha256)
    check("flags", list[0].prerelease && list[3].draft && !list[1].prerelease)
    val arm64 = listOf("arm64-v8a", "armeabi-v7a")

    val stable = Forges.choose(list, Options(), arm64).getOrThrow()
    same("stable skips the pre-release and the release without an APK", "v1.8.0", stable.release.tag)
    same("and takes the arm64 file", "app-arm64-v8a.apk", stable.asset.name)

    val pre = Forges.choose(list, Options(prerelease = true), arm64).getOrThrow()
    same("pre-releases on takes the newest", "v2.0.0-rc1", pre.release.tag)

    val byVersion = Forges.choose(list.reversed(), Options(prerelease = true, order = Options.ORDER_VERSION), arm64).getOrThrow()
    same("highest version wins whatever the order", "v2.0.0-rc1", byVersion.release.tag)

    val tagOnly = listOf(Release("v3.0.0-beta1", "", false, false, "", "", listOf(asset("a-arm64-v8a.apk"))), Release("v2.9.0", "", false, false, "", "", listOf(asset("a-arm64-v8a.apk"))))
    same("a tag that names a pre-release counts as one", "v2.9.0", Forges.choose(tagOnly, Options(), arm64).getOrThrow().release.tag)

    val none = Forges.choose(list, Options(pattern = "nope"), arm64)
    check("no matching file", none.isFailure && none.exceptionOrNull()!!.message!!.contains("No release has an APK"))
    check("no releases", Forges.choose(emptyList(), Options(), arm64).exceptionOrNull()!!.message!!.contains("No release is published"))
    check("not a list", Forges.parseList(ForgeKind.GITHUB, "github.com", "{\"message\":\"Not Found\"}").isEmpty())

    val gitlab = Forges.parseList(
        ForgeKind.GITLAB, "gitlab.com",
        """[{"tag_name":"v1.0","name":"One","description":"d","released_at":"2026-01-01T00:00:00Z","upcoming_release":false,
          "assets":{"count":2,"links":[{"id":5,"name":"app-arm64-v8a.apk","url":"https://gitlab.com/x/y/-/jobs/1/artifacts/raw/app.apk","direct_asset_url":"https://gitlab.com/x/y/-/releases/v1.0/downloads/app-arm64-v8a.apk","link_type":"package"},
                              {"id":6,"name":"web","url":"http://insecure.example/a.apk"}],"sources":[]}}]""",
    )
    same("gitlab release", "v1.0", gitlab[0].tag)
    same("gitlab keeps https links only", 1, gitlab[0].assets.size)
    same("gitlab uses the direct asset url", "https://gitlab.com/x/y/-/releases/v1.0/downloads/app-arm64-v8a.apk", gitlab[0].assets[0].url)
}

// ---------------------------------------------------------------- Obtainium import

private fun obtainium() {
    val export = """{"apps":[
      {"id":"org.a","url":"https://github.com/o/a","name":"A","additionalSettings":"{\"includePrereleases\":true,\"apkFilterRegEx\":\"arm64\",\"invertAPKFilter\":false,\"sortMethodChoice\":\"smartname\"}"},
      {"id":"org.b","url":"https://f-droid.org/packages/org.b","name":"B","additionalSettings":"{}"},
      {"id":"org.c","url":"https://github.com/o/c","name":"C","additionalSettings":{"apkFilterRegEx":"debug","invertAPKFilter":true,"appName":"Cee"}},
      {"id":"org.d","url":"https://github.com/o/d","additionalSettings":"{\"trackOnly\":true}"},
      {"id":"org.e","url":"https://github.com/o/a"},
      {"id":"org.f","url":"https://play.google.com/store/apps/details?id=org.f"}
    ],"settings":{}}"""
    val imported = ObtainiumImport.parse(export).getOrThrow()
    same("two apps are taken (the duplicate once)", 2, imported.apps.size)
    val a = imported.apps[0]
    check("settings carry over", a.tracking.options == Options(true, "arm64", "", Options.ORDER_VERSION) && a.name == "A")
    val c = imported.apps[1]
    check("an inverted filter becomes an exclude, the app name wins", c.tracking.options.exclude == "debug" && c.tracking.options.pattern == "" && c.name == "Cee")
    check("track-only apps are left out", imported.skipped.keys.any { it.contains("only tracked") })
    check("other sources are counted by host", imported.skipped.keys.any { it.contains("f-droid.org") } && imported.skipped.keys.any { it.contains("play.google.com") })
    check("not an export", ObtainiumImport.parse("hello").isFailure)
    same("a bare list is read too", 1, ObtainiumImport.parse("""[{"url":"https://github.com/o/z"}]""").getOrThrow().apps.size)
}

// ---------------------------------------------------------------- reading an APK ahead

private fun u32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

private fun u64(v: Long) = u32(v and 0xFFFFFFFFL) + u32(v ushr 32)

private fun lp(bytes: ByteArray) = u32(bytes.size.toLong()) + bytes

/** A zip with the manifest, plus an APK Signing Block holding one v2 signer with [cert], inserted before the directory. */
private fun apk(manifest: ByteArray, cert: ByteArray?, extra: Map<String, ByteArray> = emptyMap()): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
        zip.write(manifest)
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("resources.arsc"))
        zip.write(ByteArray(300_000).also { java.util.Random(1).nextBytes(it) })
        zip.closeEntry()
        for ((name, data) in extra) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    val zip = out.toByteArray()
    if (cert == null) return zip
    var eocd = zip.size - 22
    while (!(zip[eocd] == 0x50.toByte() && zip[eocd + 1] == 0x4b.toByte() && zip[eocd + 2] == 0x05.toByte() && zip[eocd + 3] == 0x06.toByte())) eocd--
    val directory = (zip[eocd + 16].toLong() and 0xff) or ((zip[eocd + 17].toLong() and 0xff) shl 8) or ((zip[eocd + 18].toLong() and 0xff) shl 16) or ((zip[eocd + 19].toLong() and 0xff) shl 24)
    val signedData = lp(ByteArray(0)) + lp(lp(cert)) + lp(ByteArray(0))
    val signer = lp(signedData) + lp(ByteArray(0)) + lp(ByteArray(0))
    val v2 = lp(lp(signer))
    val pair = u64(4L + v2.size) + u32(0x7109871aL) + v2
    val padding = u64(4L + 4) + u32(0x42726577L) + ByteArray(4)
    val pairs = pair + padding
    val size = pairs.size + 24L
    val block = u64(size) + pairs + u64(size) + "APK Sig Block 42".toByteArray(Charsets.ISO_8859_1)
    val patched = zip.copyOf()
    val moved = u32(directory + block.size)
    for (i in 0..3) patched[eocd + 16 + i] = moved[i]
    return patched.copyOfRange(0, directory.toInt()) + block + patched.copyOfRange(directory.toInt(), patched.size)
}

private class Counting(private val inner: ByteSource) : ByteSource {
    var bytes = 0L
    override val size: Long get() = inner.size

    override fun read(offset: Long, length: Int): ByteArray {
        bytes += length
        return inner.read(offset, length)
    }
}

private fun probe() {
    val manifest = File("test/fixtures/enginehost-manifest.axml").readBytes()
    val cert = "a certificate, as far as sha256 is concerned".toByteArray()
    val signed = apk(manifest, cert)
    val counting = Counting(MemorySource(signed))
    val facts = ApkProbe.probe(counting)
    same("package", "dev.enginehost", facts.packageName)
    same("version code", 234L, facts.versionCode)
    same("version name", "0.1.5-dev-234", facts.versionName)
    same("min sdk", 26, facts.minSdk)
    same("signer is the certificate's SHA-256", listOf(sha256(cert)), facts.signers)
    check("only the end of the file and the manifest are read", counting.bytes < signed.size / 2) { "read ${counting.bytes} of ${signed.size}" }

    val unsigned = ApkProbe.probe(MemorySource(apk(manifest, null)))
    same("an APK without a signing block has no signers here", emptyList<String>(), unsigned.signers)
    same("but is still read", "dev.enginehost", unsigned.packageName)

    val stored = ByteArrayOutputStream()
    ZipOutputStream(stored).use { zip ->
        zip.setMethod(ZipOutputStream.STORED)
        val e = ZipEntry("AndroidManifest.xml")
        e.size = manifest.size.toLong()
        e.compressedSize = manifest.size.toLong()
        val crc = java.util.zip.CRC32()
        crc.update(manifest)
        e.crc = crc.value
        zip.putNextEntry(e)
        zip.write(manifest)
        zip.closeEntry()
    }
    same("a stored manifest", 234L, ApkProbe.probe(MemorySource(stored.toByteArray())).versionCode)

    check("not a zip", runCatching { ApkProbe.probe(MemorySource(ByteArray(5000))) }.exceptionOrNull() is IOException)
    check("a zip without a manifest", runCatching {
        val z = ByteArrayOutputStream()
        ZipOutputStream(z).use { it.putNextEntry(ZipEntry("a.txt")); it.write(1); it.closeEntry() }
        ApkProbe.probe(MemorySource(z.toByteArray()))
    }.exceptionOrNull() is IOException)
    check("a cut-off manifest", runCatching { ApkProbe.probe(MemorySource(apk(manifest.copyOf(100), null))) }.exceptionOrNull() is IOException)
}

// ---------------------------------------------------------------- answers

private fun answers() {
    val json = """[{"tag_name":"v1.0"}]"""
    val asText = HttpAnswer.of(org.json.JSONObject().put("status", 200).put("body", json).put("headers", org.json.JSONObject().put("ETag", "W/\"x\"")))
    same("a text body is read (github.request without as)", json, asText.text)
    same("header names are lower-cased", "W/\"x\"", asText.headers["etag"])
    val asBase64 = HttpAnswer.of(org.json.JSONObject().put("status", 200).put("bodyBase64", java.util.Base64.getEncoder().encodeToString(json.toByteArray())).put("truncated", true))
    same("a base64 body is read", json, asBase64.text)
    check("truncated is carried", asBase64.truncated)
    same("an answer with no body is empty", "", HttpAnswer.of(org.json.JSONObject().put("status", 304)).text)
    same("the releases of a text answer are found", 1, Forges.parseList(ForgeKind.GITHUB, "github.com", asText.text).size)
}

fun main() {
    answers()
    versions()
    assets()
    forges()
    releases()
    obtainium()
    probe()
    println("$checks checks, $failures failed")
    if (failures > 0) exitProcess(1)
}
