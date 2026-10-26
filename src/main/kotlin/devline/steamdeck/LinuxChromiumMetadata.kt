package devline.steamdeck

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** The official native archive omits metadata. Bind the shipped descriptor to that exact archive. */
internal object LinuxChromiumMetadata {
    private data class Descriptor(val schemaVersion: Int, val artifactId: String, val archiveSha256: String,
        val destination: String, val releaseTag: String, val platform: String, val sourceMetadataSha256: String)
    private val descriptor by lazy {
        val input = requireNotNull(javaClass.getResourceAsStream("/chromium-linux-amd64-metadata.json"))
        input.reader(Charsets.UTF_8).use { Gson().fromJson(it, Descriptor::class.java) }.also {
            require(it.schemaVersion == 1 && it.platform == "linux-amd64" && it.archiveSha256.matches(Regex("[a-f0-9]{64}")) &&
                it.sourceMetadataSha256.matches(Regex("[a-f0-9]{64}")))
            require(it.destination == "tools/jcef/152.0.6/linux-amd64/native")
        }
    }

    private fun file(root: Path, manifest: ToolchainManifest): Path {
        val artifact = manifest.artifacts.singleOrNull { it.id == descriptor.artifactId }
        require(artifact != null && artifact.kind == "tar.gz" && artifact.sha256 == descriptor.archiveSha256 &&
            artifact.destination == descriptor.destination && artifact.strip == 0) { "Bundled Chromium metadata does not match the selected archive and destination" }
        return root.resolve(descriptor.destination).resolve("build_meta.json")
    }

    private fun expected() = JsonObject().apply {
        addProperty("release_tag", descriptor.releaseTag)
        addProperty("platform", descriptor.platform)
        addProperty("archive_sha256", descriptor.archiveSha256)
    }

    fun ready(root: Path, manifest: ToolchainManifest): Boolean = runCatching {
        val file = file(root, manifest)
        Files.isRegularFile(file, NOFOLLOW_LINKS) && file.toRealPath() == file.toAbsolutePath().normalize() &&
            Files.size(file) <= 8192 && Gson().fromJson(Files.readString(file), JsonObject::class.java) == expected()
    }.getOrDefault(false)

    fun install(root: Path, manifest: ToolchainManifest, check: () -> Unit = {}) {
        val file = file(root, manifest)
        if (Files.exists(file, NOFOLLOW_LINKS)) {
            require(ready(root, manifest)) { "Existing Chromium metadata is unrecognized or changed; preserve it before preparing a replacement" }
            return
        }
        require(Files.isDirectory(file.parent, NOFOLLOW_LINKS) && file.parent.toRealPath() == file.parent.toAbsolutePath().normalize()) { "Chromium metadata destination is missing or linked" }
        val temporary = Files.createTempFile(file.parent, ".build-meta-", ".json")
        try {
            check()
            Files.writeString(temporary, Gson().toJson(expected()), Charsets.UTF_8)
            check(); Files.move(temporary, file, ATOMIC_MOVE)
            require(ready(root, manifest)) { "Chromium metadata verification failed" }
        } finally { Files.deleteIfExists(temporary) }
    }
}
