package devline.steamdeck

import com.google.gson.JsonObject
import devline.extensions.BundledExtensions
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensions
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

fun main() {
    val archivePath = Path.of(System.getProperty("devline.deck.archive"))
    val checksum = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archivePath)).joinToString("") { "%02x".format(it) }
    val root = Files.createTempDirectory("devline-deck-check-")
    try {
        checkDeckSettings(root.resolve("scopes"), archivePath)
        val profile = root.resolve("profile")
        val distribution = root.resolve("extensions").also(Files::createDirectories)
        Files.copy(archivePath, distribution.resolve(archivePath.fileName))
        Files.writeString(distribution.resolve("catalog.json"), """{"schemaVersion":1,"extensions":[{"id":"devline.steamdeck","archive":"${archivePath.fileName}","sha256":"$checksum","enabled":true}]}""")
        val context = object : IdeExtensionContext {
            override val profileDirectory = profile
            override fun enabled() = true
            override fun projectRoot(projectFile: String): String = error("No project access expected")
            override fun callProject(projectFile: String, method: String, arguments: JsonObject): JsonObject = error("No device or project calls expected")
        }
        fun registry() = IdeExtensions(profile, context, emptyList())
        BundledExtensions.prepare(profile, distribution)
        registry().use { extensions ->
            check(extensions.overview().extensions.single().enabled)
            check(extensions.overview().extensions.single().commandViews == mapOf("status" to "device"))
            check(extensions.buildProfiles().size == 2 && extensions.buildProfiles().all { it.available })
            check(extensions.buildProfiles().map { it.profile.backend }.toSet() == setOf("javafx", "desktop-web"))
            check(extensions.buildProfiles().all { it.profile.platform == "linux" && it.profile.architecture == "amd64" })
            check(!extensions.request("steamDeck.status", JsonObject()).asJsonObject["toolchain"].asJsonObject["ready"].asBoolean)
            extensions.setEnabled("devline.steamdeck", false, extensions.overview().version)
            check(extensions.buildProfiles().all { !it.available })
        }
        BundledExtensions.prepare(profile, distribution)
        registry().use { extensions ->
            check(!extensions.overview().extensions.single().enabled)
            extensions.setEnabled("devline.steamdeck", true, extensions.overview().version)
            check(extensions.buildProfiles().all { it.available })
            extensions.remove("devline.steamdeck", extensions.overview().version)
        }
        BundledExtensions.prepare(profile, distribution)
        registry().use { check(it.overview().extensions.isEmpty() && it.buildProfiles().isEmpty()) }
        checkDeckUpgrade(root, archivePath)
        println("STEAM_DECK_PASS: API 6 settings archive/provider, build profiles, initial enablement, persistent disablement/removal, backend overrides and no device access")
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
