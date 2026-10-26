package devline.steamdeck

import com.google.gson.Gson
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** Own profiles only. An optional argument replays the pinned production archives without network access. */
fun main(args: Array<String>) {
    val root = Files.createTempDirectory("devline-linux-toolchain-check-").toRealPath()
    fun job() = DeckJob("prepare", null) { true }
    fun output(job: DeckJob) = job.read(0)["data"].asString
    fun archive(name: String, entries: Map<String, String>): Path {
        val path = root.resolve(name)
        TarArchiveOutputStream(GzipCompressorOutputStream(Files.newOutputStream(path))).use { tar ->
            for ((name, value) in entries) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                tar.putArchiveEntry(TarArchiveEntry(name).apply { size = bytes.size.toLong() })
                tar.write(bytes); tar.closeArchiveEntry()
            }
        }
        return path
    }
    fun fixture(profile: Path, chromiumEntries: Map<String, String>): LinuxToolchain {
        val cache = profile.resolve("toolchains/downloads").also(Files::createDirectories)
        val java = archive("java.tar.gz", mapOf("jdk/bin/java" to "java", "jdk/release" to "OS_NAME=\"Linux\"\nOS_ARCH=\"x86_64\"\n"))
        val chromium = archive("chromium.tar.gz", chromiumEntries)
        val javafx = root.resolve("javafx.jar").also { Files.writeString(it, "javafx") }
        val artifacts = listOf(
            Triple(java, "runtime", 1), Triple(chromium, "tools/jcef/152.0.6/linux-amd64/native", 0), Triple(javafx, "lib/javafx/base.jar", 0),
        ).mapIndexed { index, (file, destination, strip) ->
            val hash = LinuxToolchain.sha256(file); Files.copy(file, cache.resolve(hash))
            ToolchainArtifact("fixture-$index", "https://github.com/devline/fixture-$index", hash, Files.size(file), if (index == 2) "file" else "tar.gz", destination, strip)
        }
        return LinuxToolchain(profile, ToolchainManifest(1, "linux-amd64", artifacts))
    }
    try {
        checkLinuxChromiumMetadata(root.resolve("metadata-check"))
        val brokenProfile = root.resolve("broken")
        val incomplete = fixture(brokenProfile, mapOf("bin/lib/linux64/libcef.so" to "cef"))
        repeat(2) {
            val attempt = job()
            val failure = runCatching { incomplete.prepare(attempt) }.exceptionOrNull()
            check(failure?.message?.contains("libjcef.so") == true && failure.message!!.contains("jcef_helper")) { failure.toString() }
            check(!Files.exists(incomplete.target))
            check(Files.list(incomplete.target.parent).use { files -> files.noneMatch { it.fileName.toString().startsWith(".linux-amd64-") } })
            check(output(attempt).contains("Use verified offline cache"))
        }
        val cacheBefore = Files.list(brokenProfile.resolve("toolchains/downloads")).use { files -> files.toList().associate { it.fileName.toString() to LinuxToolchain.sha256(it) } }
        var cancellationChecks = 0
        val cancelled = DeckJob("prepare", null) { ++cancellationChecks < 4 }
        check(runCatching { incomplete.prepare(cancelled) }.exceptionOrNull() is CancellationException)
        check(!Files.exists(incomplete.target))
        val cacheAfter = Files.list(brokenProfile.resolve("toolchains/downloads")).use { files -> files.toList().associate { it.fileName.toString() to LinuxToolchain.sha256(it) } }
        check(cacheBefore == cacheAfter)
        println("PASS missing-file diagnostics, retry from unchanged cache, cancellation and owned staging cleanup")

        args.firstOrNull()?.let { source ->
            val cacheSource = Path.of(source).toRealPath()
            val manifest = LinuxToolchain::class.java.getResourceAsStream("/toolchain-linux-amd64.json")!!.reader(Charsets.UTF_8).use { Gson().fromJson(it, ToolchainManifest::class.java) }
            val profile = root.resolve("production")
            val cache = profile.resolve("toolchains/downloads").also(Files::createDirectories)
            val originals = manifest.artifacts.associate { artifact ->
                val file = cacheSource.resolve(artifact.sha256)
                check(Files.size(file) == artifact.bytes && LinuxToolchain.sha256(file) == artifact.sha256)
                Files.copy(file, cache.resolve(artifact.sha256))
                artifact.sha256 to Files.getLastModifiedTime(file)
            }
            val toolchain = LinuxToolchain(profile, manifest)
            val first = job()
            check(toolchain.prepare(first)["ready"].asBoolean)
            check(output(first).contains("Normalize Chromium native payload: bin/lib/linux64"))
            check(!output(first).contains("Download java-") && !output(first).contains("Download chromium-"))
            val native = toolchain.target.resolve("tools/jcef/152.0.6/linux-amd64/native")
            check(Files.isRegularFile(native.resolve("libjcef.so")) && Files.isRegularFile(native.resolve("locales/en-US.pak")))
            check(LinuxChromiumMetadata.ready(toolchain.target, manifest))
            val second = job()
            check(toolchain.prepare(second)["ready"].asBoolean && output(second).contains("already verified; using offline cache"))
            // A target created by the previous nine-artifact installer upgrades without replacing Java/CEF.
            val javaBefore = LinuxToolchain.sha256(toolchain.target.resolve("runtime/bin/java"))
            val oldReceipt = Gson().fromJson(Files.readString(toolchain.target.resolve("ready.json")), com.google.gson.JsonObject::class.java)
            oldReceipt.getAsJsonObject("files").keySet().filter { it.startsWith("lib/pcre/") || it.endsWith("/build_meta.json") }.toList().forEach { oldReceipt.getAsJsonObject("files").remove(it) }
            Files.writeString(toolchain.target.resolve("ready.json"), Gson().toJson(oldReceipt))
            Files.move(toolchain.target.resolve("lib/pcre"), root.resolve("previous-pcre"))
            Files.move(native.resolve("build_meta.json"), root.resolve("previous-build-meta.json"))
            check(!toolchain.ready())
            val upgrade = job()
            check(toolchain.prepare(upgrade)["ready"].asBoolean)
            check(output(upgrade).contains("verified existing toolchain") && !output(upgrade).contains("Download "))
            check(LinuxToolchain.sha256(toolchain.target.resolve("runtime/bin/java")) == javaBefore)
            check(LinuxChromiumMetadata.ready(toolchain.target, manifest))
            // Never replace a user's unrecognized folder while upgrading an otherwise valid target.
            Files.writeString(toolchain.target.resolve("ready.json"), Gson().toJson(oldReceipt))
            Files.move(toolchain.target.resolve("lib/pcre"), root.resolve("upgraded-pcre"))
            Files.createDirectory(toolchain.target.resolve("lib/pcre"))
            Files.writeString(toolchain.target.resolve("lib/pcre/keep.txt"), "preserve foreign files")
            check(runCatching { toolchain.prepare(job()) }.isFailure)
            check(Files.readString(toolchain.target.resolve("lib/pcre/keep.txt")) == "preserve foreign files")
            for ((hash, modified) in originals) check(Files.getLastModifiedTime(cacheSource.resolve(hash)) == modified && LinuxToolchain.sha256(cacheSource.resolve(hash)) == hash)
            println("PASS pinned Java/JavaFX/JCEF archives, offline reuse, metadata and PCRE2 upgrade, foreign-file preservation, source cache unchanged")
        }
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
