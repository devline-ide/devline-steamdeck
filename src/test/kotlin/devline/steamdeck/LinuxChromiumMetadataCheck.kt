package devline.steamdeck

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/** Small local fixtures prove archive binding and preservation without downloading Chromium. */
internal fun checkLinuxChromiumMetadata(root: Path) {
    val manifest = LinuxToolchain::class.java.getResourceAsStream("/toolchain-linux-amd64.json")!!.reader(Charsets.UTF_8)
        .use { Gson().fromJson(it, ToolchainManifest::class.java) }
    val artifact = manifest.artifacts.single { it.id == "chromium-152" }
    val directory = root.resolve(artifact.destination)
    Files.createDirectories(directory)
    val file = directory.resolve("build_meta.json")
    check(!LinuxChromiumMetadata.ready(root, manifest))
    for (changed in listOf(artifact.copy(sha256 = "0".repeat(64)), artifact.copy(destination = "tools/foreign"))) {
        val other = manifest.copy(artifacts = manifest.artifacts.map { if (it.id == artifact.id) changed else it })
        check(runCatching { LinuxChromiumMetadata.install(root, other) }.isFailure)
        check(!Files.exists(file))
    }
    LinuxChromiumMetadata.install(root, manifest)
    check(LinuxChromiumMetadata.ready(root, manifest))
    val metadata = Gson().fromJson(Files.readString(file), JsonObject::class.java)
    check(metadata["release_tag"].asString == "jcef-17e805a+cef-152.0.6+g708dc14+chromium-152.0.7977.83")
    check(metadata["platform"].asString == "linux-amd64" && metadata["archive_sha256"].asString == artifact.sha256)
    val bytes = Files.readAllBytes(file)
    LinuxChromiumMetadata.install(root, manifest)
    check(Files.readAllBytes(file).contentEquals(bytes))
    Files.writeString(file, "{\"foreign\":\"preserve metadata\"}")
    check(!LinuxChromiumMetadata.ready(root, manifest))
    check(runCatching { LinuxChromiumMetadata.install(root, manifest) }.isFailure)
    check(Files.readString(file) == "{\"foreign\":\"preserve metadata\"}")
}
