package devline.steamdeck

import com.google.gson.Gson
import java.nio.file.Files
import java.nio.file.Path

internal fun checkLinuxToolchainPaths(root: Path) {
    val absolute = root.toAbsolutePath().resolve("missing/.././profile")
    val relative = Path.of(".").resolve(root.parent.fileName).resolve(root.fileName).resolve("missing/.././profile")
    for (profile in listOf(absolute, relative)) {
        val expected = profile.toAbsolutePath().normalize().resolve("toolchains/targets/linux-amd64")
        val toolchain = LinuxToolchain(profile)
        check(toolchain.target == expected)
        check(!toolchain.status()["ready"].asBoolean)
        check(!Files.exists(expected.parent))
    }
    check(!Files.exists(root)) { "Reading toolchain status must not create or download files" }
    val manifest = LinuxToolchain::class.java.getResourceAsStream("/toolchain-linux-amd64.json")!!.reader(Charsets.UTF_8)
        .use { Gson().fromJson(it, ToolchainManifest::class.java) }
    for (destination in listOf("../escape", "runtime/../escape", "/escape", "C:/escape", "runtime\\escape")) {
        val invalid = manifest.copy(artifacts = manifest.artifacts.mapIndexed { index, artifact ->
            if (index == 0) artifact.copy(destination = destination) else artifact
        })
        check(runCatching { LinuxToolchain(absolute, invalid) }.exceptionOrNull() is IllegalArgumentException)
    }
}
