package devline.steamdeck

import com.google.gson.Gson
import com.google.gson.JsonObject
import devline.decktools.DeckTargetPcre
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
import java.util.Properties

internal data class ToolchainArtifact(val id: String, val url: String, val sha256: String, val bytes: Long,
    val kind: String, val destination: String, val strip: Int = 0)
internal data class ToolchainManifest(val schemaVersion: Int, val id: String, val artifacts: List<ToolchainArtifact>)

internal class LinuxToolchain(private val profile: Path,
    private val manifest: ToolchainManifest = LinuxToolchain::class.java.getResourceAsStream("/toolchain-linux-amd64.json")!!.reader(Charsets.UTF_8).use { Gson().fromJson(it, ToolchainManifest::class.java) }) {
    val target: Path = profile.resolve("toolchains/targets/linux-amd64")
    private val cache = profile.resolve("toolchains/downloads")
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
    init {
        require(manifest.schemaVersion == 1 && manifest.id == "linux-amd64" && manifest.artifacts.size in 3..32)
        require(manifest.artifacts.map { it.id }.distinct().size == manifest.artifacts.size)
        manifest.artifacts.forEach {
            val uri = URI(it.url)
            require(uri.scheme == "https" && uri.host in setOf("github.com", "repo.maven.apache.org"))
            require(it.sha256.matches(Regex("[a-f0-9]{64}")) && it.bytes in 1..1024L * 1024 * 1024)
            require(it.kind in setOf("file", "tar.gz") && it.strip in 0..1)
            confined(target, it.destination)
        }
    }
    fun status(): JsonObject = Gson().toJsonTree(mapOf("ready" to ready(), "bytes" to manifest.artifacts.sumOf { it.bytes }, "target" to target.toString(), "hardwareVerified" to false)).asJsonObject
    fun ready(): Boolean = runtimeReady() && DeckTargetPcre.ready(target) && LinuxChromiumMetadata.ready(target, manifest)
    private fun runtimeReady(): Boolean = runCatching {
        val ready = target.resolve("ready.json")
        if (!Files.isRegularFile(ready, NOFOLLOW_LINKS) || Files.size(ready) > 1024 * 1024) return false
        val receipt = Gson().fromJson(Files.readString(ready), JsonObject::class.java)
        if (receipt["manifest"]?.asString != manifestHash()) return false
        validateLayout(target)
        val release = Properties().apply { Files.newBufferedReader(target.resolve("runtime/release")).use { load(it) } }
        if (release.getProperty("OS_NAME", "").trim('"') != "Linux" || release.getProperty("OS_ARCH", "").trim('"') !in setOf("x86_64", "amd64")) return false
        receipt.getAsJsonObject("files").entrySet().all { (relative, pin) ->
            val path = confined(target, relative)
            Files.isRegularFile(path, NOFOLLOW_LINKS) && sha256(path) == pin.asString
        }
    }.getOrDefault(false)
    fun prepare(job: DeckJob): JsonObject {
        job.log("Checking Linux build tools in $target\n")
        Files.createDirectories(profile.resolve("toolchains"))
        val lockFile = profile.resolve("toolchains/linux-amd64.lock")
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
            val lock = channel.tryLock() ?: throw IllegalStateException("Linux dependencies are being prepared by another DevLine instance")
            lock.use {
                if (ready()) { job.log("Linux toolchain already verified; using offline cache.\n"); return status() }
                job.check()
                if (runtimeReady()) {
                    job.log("Add pinned Chromium metadata and the bundled Linux PCRE2 provider to the verified existing toolchain\n")
                    LinuxChromiumMetadata.install(target, manifest, job::check)
                    DeckTargetPcre.install(target, job::check)
                    receipt(target, job)
                    return status()
                }
                require(!Files.exists(target, NOFOLLOW_LINKS)) { "The existing Linux toolchain is incomplete or changed. Move it aside before preparing a replacement." }
                Files.createDirectories(cache); Files.createDirectories(target.parent)
                val stage = Files.createTempDirectory(target.parent, ".linux-amd64-")
                try {
                    job.total = manifest.artifacts.sumOf { it.bytes }
                    for (artifact in manifest.artifacts) {
                        job.check(); job.log("Prepare ${artifact.id}\n")
                        val archive = download(artifact, job)
                        val destination = confined(stage, artifact.destination)
                        job.log("${if (artifact.kind == "file") "Install" else "Extract"} ${artifact.id} -> ${artifact.destination}\n")
                        if (artifact.kind == "file") { Files.createDirectories(destination.parent); Files.copy(archive, destination) }
                        else extract(archive, destination, artifact.strip, job)
                    }
                    // jcefbuild release archives contain bin/lib/linux64; Maven native
                    // archives can contain a native/ wrapper. Normalize complete payloads.
                    val cef = stage.resolve("tools/jcef/152.0.6/linux-amd64/native")
                    if (!Files.isRegularFile(cef.resolve("libcef.so"), NOFOLLOW_LINKS)) {
                        val payload = listOf("bin/lib/linux64", "native").map(cef::resolve)
                            .firstOrNull { Files.isRegularFile(it.resolve("libcef.so"), NOFOLLOW_LINKS) }
                        if (payload != null) {
                            job.log("Normalize Chromium native payload: ${cef.relativize(payload).toString().replace('\\', '/')}\n")
                            Files.list(payload).use { files -> files.toList().forEach { file ->
                                job.check(); Files.move(file, cef.resolve(file.fileName))
                            } }
                            Files.delete(payload)
                        }
                    }
                    job.log("Verify Linux runtime, Chromium libraries, helper, resources and locales\n")
                    validateLayout(stage)
                    job.log("Install Chromium release metadata bound to the verified archive\n")
                    LinuxChromiumMetadata.install(stage, manifest, job::check)
                    job.log("Install the bundled Linux PCRE2 provider\n")
                    DeckTargetPcre.install(stage, job::check)
                    receipt(stage, job)
                    job.check()
                    job.log("Publish verified Linux build tools to $target\n")
                    Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE)
                    job.log("Linux JavaFX and Chromium dependencies prepared. Hardware acceptance pending.\n")
                    return status()
                } finally { if (Files.exists(stage, NOFOLLOW_LINKS)) deleteOwned(stage) }
            }
        }
    }
    private fun receipt(root: Path, job: DeckJob) {
        val pins = JsonObject()
        job.log("Create SHA-256 receipt for installed files\n")
        Files.walk(root).use { paths -> paths.filter { it != root.resolve("ready.json") && Files.isRegularFile(it, NOFOLLOW_LINKS) }.sorted().forEach { file ->
            job.check(); pins.addProperty(root.relativize(file).toString().replace('\\', '/'), sha256(file, job::check))
        } }
        val value = JsonObject().apply { addProperty("schemaVersion", 1); addProperty("manifest", manifestHash()); add("files", pins) }
        val temporary = Files.createTempFile(root, ".ready-", ".json")
        try {
            Files.writeString(temporary, Gson().toJson(value), Charsets.UTF_8)
            job.check(); Files.move(temporary, root.resolve("ready.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun manifestHash() = MessageDigest.getInstance("SHA-256").digest(Gson().toJson(manifest).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun download(artifact: ToolchainArtifact, job: DeckJob): Path {
        val output = cache.resolve(artifact.sha256)
        job.log("Verify cached SHA-256: ${artifact.id}\n")
        if (Files.isRegularFile(output, NOFOLLOW_LINKS) && Files.size(output) == artifact.bytes && sha256(output, job::check) == artifact.sha256) {
            job.log("Use verified offline cache: ${artifact.id}\n")
            job.downloaded += artifact.bytes; return output
        }
        job.log("Download ${artifact.id} (${artifact.bytes} bytes)\n${artifact.url}\n")
        val temporary = Files.createTempFile(cache, ".download-", ".part")
        try {
            val request = HttpRequest.newBuilder(URI(artifact.url)).timeout(Duration.ofMinutes(10)).header("User-Agent", "DevLine-SteamDeck/0.1").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            response.body().use { input ->
                require(response.statusCode() == 200 && response.uri().scheme == "https") { "Download failed: ${artifact.id}, HTTP ${response.statusCode()}" }
                Files.newOutputStream(temporary).use { out ->
                    val bytes = ByteArray(64 * 1024); var received = 0L
                    while (true) {
                        job.check(); val size = input.read(bytes); if (size < 0) break
                        received += size; require(received <= artifact.bytes) { "Download exceeds its declared size" }
                        out.write(bytes, 0, size); job.downloaded += size
                    }
                    require(received == artifact.bytes) { "Incomplete download: ${artifact.id}" }
                }
            }
            job.log("Verify downloaded SHA-256: ${artifact.id}\n")
            require(sha256(temporary, job::check) == artifact.sha256) { "SHA-256 mismatch: ${artifact.id}" }
            Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING)
            return output
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun extract(archive: Path, destination: Path, strip: Int, job: DeckJob) {
        Files.createDirectories(destination)
        val links = mutableListOf<Pair<Path, Path>>()
        TarArchiveInputStream(GzipCompressorInputStream(Files.newInputStream(archive))).use { tar ->
            var expanded = 0L; var count = 0
            while (true) {
                job.check(); val entry = tar.nextEntry ?: break
                require(++count <= 50000)
                val parts = entry.name.removePrefix("./").trimEnd('/').split('/')
                if (parts.size <= strip) continue
                val file = confined(destination, parts.drop(strip).joinToString("/"))
                require(!Files.exists(file, NOFOLLOW_LINKS)) { "Duplicate toolchain archive entry" }
                if (entry.isDirectory) { Files.createDirectories(file); continue }
                Files.createDirectories(file.parent)
                if (entry.isSymbolicLink || entry.isLink) {
                    val target = if (entry.isSymbolicLink) file.parent.resolve(entry.linkName).normalize()
                        else confined(destination, entry.linkName.removePrefix("./").split('/').drop(strip).joinToString("/"))
                    require(target.startsWith(destination) && target != file) { "Toolchain link escapes its root" }
                    links += file to target; continue
                }
                require(entry.isFile && entry.size in 0..1024L * 1024 * 1024)
                expanded += entry.size; require(expanded <= 3L * 1024 * 1024 * 1024)
                Files.newOutputStream(file, StandardOpenOption.CREATE_NEW).use { out ->
                    val buffer = ByteArray(64 * 1024); var written = 0L
                    while (true) { job.check(); val size = tar.read(buffer); if (size < 0) break; written += size; require(written <= entry.size); out.write(buffer, 0, size) }
                    require(written == entry.size)
                }
                if (entry.mode and 73 != 0) file.toFile().setExecutable(true, false)
            }
        }
        // Materialize confined file links so Windows exports do not require symlink privileges.
        repeat(links.size + 1) { links.removeAll { (file, target) ->
            if (Files.isRegularFile(target, NOFOLLOW_LINKS)) { Files.copy(target, file); true } else false
        } }
        require(links.isEmpty()) { "Unsupported directory or unresolved toolchain link" }
    }
    private fun confined(root: Path, relative: String): Path {
        require(relative.isNotBlank() && !relative.startsWith('/') && '\\' !in relative && ':' !in relative && relative.split('/').none { it.isBlank() || it in setOf(".", "..") })
        return root.resolve(relative).normalize().also { require(it.startsWith(root) && it != root) }
    }
    private fun validateLayout(root: Path) {
        val cef = "tools/jcef/152.0.6/linux-amd64/native/"
        val required = listOf("runtime/bin/java", "runtime/release") + listOf(
            "libcef.so", "libjcef.so", "jcef_helper", "icudtl.dat", "resources.pak",
            "chrome_100_percent.pak", "chrome_200_percent.pak", "v8_context_snapshot.bin", "locales/en-US.pak",
        ).map { cef + it }
        val missing = required.filterNot { Files.isRegularFile(confined(root, it), NOFOLLOW_LINKS) }
        require(missing.isEmpty()) { "Linux runtime files are missing after extraction: ${missing.joinToString(", ")}" }
        val release = Properties().apply { Files.newBufferedReader(root.resolve("runtime/release")).use { load(it) } }
        require(release.getProperty("OS_NAME", "").trim('"') == "Linux" && release.getProperty("OS_ARCH", "").trim('"') in setOf("x86_64", "amd64")) {
            "The downloaded Java runtime must target Linux amd64"
        }
    }
    companion object {
        fun sha256(file: Path, check: () -> Unit = {}): String {
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file, NOFOLLOW_LINKS).use { input -> val bytes = ByteArray(64 * 1024); while (true) { check(); val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) } }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        private fun deleteOwned(root: Path) { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
    }
}
