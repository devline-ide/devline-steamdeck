package devline.steamdeck

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensions
import devline.extensions.IdeExtensionException
import java.nio.file.Files
import java.nio.file.Path

fun main() {
    checkDeckRunBackend()
    val root = Files.createTempDirectory("devline-deck-storage-")
    try {
        checkLinuxToolchainPaths(root.resolve("toolchain-paths"))
        checkDeckSettingsStorage(root)
    }
    finally { Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) } }
}

internal fun checkDeckRunBackend() {
    val saved = JsonParser.parseString("""{"backend":"desktop-web","host":"qa-device"}""").asJsonObject
    val original = saved.toString()
    check(deckRunBackend(JsonObject(), JsonObject()) == "javafx")
    check(deckRunBackend(JsonObject(), saved) == "desktop-web")
    for (backend in listOf("javafx", "desktop-web"))
        check(deckRunBackend(JsonObject().apply { addProperty("backend", backend) }, saved) == backend)
    for (value in listOf("null", "true", "123", "\"invalid\"", "\"\"", "{}"))
        check(runCatching { deckRunBackend(JsonObject().apply { add("backend", JsonParser.parseString(value)) }, saved) }.isFailure)
    check(saved.toString() == original)
}

internal fun checkDeckUpgrade(root: Path, archive: Path) {
    val previous = System.getProperty("devline.deck.previousArchive")?.let(Path::of) ?: return
    val profile = root.resolve("upgrade-profile")
    val context = object : IdeExtensionContext {
        override val profileDirectory = profile
        override fun enabled() = true
        override fun projectRoot(projectFile: String): String = error("Upgrade must not access a project")
        override fun callProject(projectFile: String, method: String, arguments: JsonObject): JsonObject = error("No device/project access")
    }
    fun registry() = IdeExtensions(profile, context, emptyList())
    registry().use { registry ->
        val review = registry.inspect(previous.toString())
        registry.install(review.path, review.checksum, review.expectedVersion)
    }
    registry().use { registry ->
        registry.setEnabled("devline.steamdeck", true, registry.overview().version)
        check(registry.overview().extensions.single().webviews.none { it.location == "settings" })
    }
    val settings = profile.resolve("devline.steamdeck/settings.json")
    Files.createDirectories(settings.parent)
    val saved = """{"host":"qa-device","backend":"desktop-web","port":2222,"user":"deck"}"""
    Files.writeString(settings, saved)
    registry().use { registry ->
        val review = registry.inspect(archive.toString())
        registry.install(review.path, review.checksum, review.expectedVersion)
    }
    registry().use { registry ->
        val extension = registry.overview().extensions.single()
        check(extension.enabled && extension.manifest.version == "0.2.7")
        check(extension.webviews.single { it.id == "settings" }.location == "settings")
        val session = registry.openWebview(extension.manifest.id, "settings", null)
        check(registry.callWebview(session.session, "status", JsonObject()).asJsonObject["settings"].asJsonObject["host"].asString == "qa-device")
        val task = registry.openWebview(extension.manifest.id, "device", null)
        check((runCatching { registry.callWebview(task.session, "configure", JsonObject()) }.exceptionOrNull() as IdeExtensionException).code == "UNKNOWN_METHOD")
        check(Files.readString(settings) == saved)
    }
    println("Steam Deck upgrade passed: enabled state/profile retained, scoped settings registered and task configure blocked.")
}
