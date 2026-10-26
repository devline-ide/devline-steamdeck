package devline.steamdeck

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensions
import java.nio.file.Files
import java.nio.file.Path

internal fun checkDeckSettingsStorage(root: Path) {
    val profile = root.resolve("profile"); Files.createDirectories(profile.resolve("devline.steamdeck"))
    val a = root.resolve("A"); val b = root.resolve("B"); Files.createDirectories(a); Files.createDirectories(b)
    val original = "{\"name\":\"untouched\"}"
    Files.writeString(a.resolve("Game.dlproject"), original); Files.writeString(b.resolve("Game.dlproject"), original)
    val legacy = profile.resolve("devline.steamdeck/settings.json")
    Files.writeString(legacy, """{"host":"shared.deck","appId":"999","backend":"desktop-web"}""")
    var current = "A"; var readOnly = false
    val context = object : IdeExtensionContext {
        override val profileDirectory = profile
        override fun enabled() = true
        override fun projectRoot(projectFile: String): String { check(projectFile == current); return (if (projectFile == "A") a else b).toString() }
        override fun callProject(projectFile: String, method: String, arguments: JsonObject): JsonObject {
            projectRoot(projectFile); check(method == "project.overview")
            return JsonObject().apply { add("project", JsonObject().apply { addProperty("readOnly", readOnly) }) }
        }
    }
    fun json(value: String) = JsonParser.parseString(value).asJsonObject
    fun store() = DeckSettingsStore(context)
    check(!store().read("A").has("appId") && !store().read("A").has("backend"))
    check(store().read(null, "user")["host"].asString == "shared.deck")
    check(runCatching { store().write(null, "user", json("""{"appId":"1"}""")) }.isFailure)
    check(runCatching { store().write(null, "workspace", json("""{"appId":"1"}""")) }.isFailure)
    store().write("A", "workspace", json("""{"appId":"101","cloudDirectory":"saves/a","development":false,"backend":"desktop-web"}"""))
    check(store().read("A")["appId"].asString == "101" && store().read("A")["host"].asString == "shared.deck")
    check(!store().read("A", "workspace").has("identityFile"))
    check(runCatching { store().write("A", "workspace", json("""{"identityFile":"secret"}""")) }.isFailure)
    current = "B"
    check(!store().read("B").has("appId"))
    store().write("B", "workspace", json("""{"appId":"202","cloudDirectory":"saves/b","development":true,"backend":"javafx"}"""))
    check(store().read("B")["appId"].asString == "202")
    check(runCatching { store().read("A") }.isFailure)
    store().write(null, "user", json("""{"host":"new.deck","port":22,"identityFile":"C:/owned/key","sdkPath":"C:/sdk"}"""))
    check(store().read("B")["host"].asString == "new.deck")
    current = "A"
    check(store().read("A")["appId"].asString == "101" && store().read("A")["backend"].asString == "desktop-web")
    readOnly = true
    check(runCatching { store().write("A", "workspace", json("""{"appId":"303"}""")) }.isFailure)
    check(store().read("A")["appId"].asString == "101")
    readOnly = false
    check(runCatching { store().write("A", "workspace", json("""{"cloudDirectory":"../B"}""")) }.isFailure)
    check(runCatching { store().write("A", "workspace", json("""{"appId":"4294967296"}""")) }.isFailure)
    check(json(Files.readString(legacy))["appId"].asString == "999")
    for (project in listOf(a, b)) check(Files.readString(project.resolve("Game.dlproject")) == original)
    println("Deck settings storage passed: separate games, shared machine values, reopen, read-only and retained unassigned legacy")
}

internal fun checkDeckSettings(root: Path, archive: Path) {
    val profile = root.resolve("profile").also(Files::createDirectories)
    val a = root.resolve("A").also(Files::createDirectories)
    Files.createDirectories(a.resolve(".devline/extensions"))
    Files.writeString(a.resolve(".devline/extensions/devline.steamdeck.json"), """{"appId":"101"}""")
    val context = object : IdeExtensionContext {
        override val profileDirectory = profile
        override fun enabled() = true
        override fun projectRoot(projectFile: String): String { check(projectFile == "A"); return a.toString() }
        override fun callProject(projectFile: String, method: String, arguments: JsonObject): JsonObject = error("No project actions expected")
    }
    fun json(value: String) = JsonParser.parseString(value).asJsonObject
    IdeExtensions(profile, context, emptyList()).use { registry ->
        val review = registry.inspect(archive.toString()); registry.install(review.path, review.checksum, review.expectedVersion)
    }
    IdeExtensions(profile, context, emptyList()).use { registry ->
        registry.setEnabled("devline.steamdeck", true, registry.overview().version)
        val global = registry.openWebview("devline.steamdeck", "settings", null)
        val workspace = registry.openWebview("devline.steamdeck", "settings", "A")
        val spoofed = json("""{"settingsScope":"user","expectedProjectFile":"B"}""")
        check(registry.callWebview(workspace.session, "status", spoofed).asJsonObject["settings"].asJsonObject["appId"].asString == "101")
        check(runCatching { registry.callWebview(global.session, "configure", json("""{"settingsScope":"workspace","expectedProjectFile":"A","settings":{"appId":"888"}}""")) }.isFailure)
        check(runCatching { registry.callWebview(workspace.session, "configure", json("""{"settingsScope":"user","settings":{"identityFile":"C:/wrong"}}""")) }.isFailure)
        registry.projectClosed()
        check(registry.allowsWebview(global.url) && !registry.allowsWebview(workspace.url))
        check(!registry.callWebview(global.session, "status", JsonObject()).asJsonObject["settings"].asJsonObject.has("appId"))
    }
    println("Deck settings SDK passed: captured scope/owner, revoked project sessions and surviving global session")
}
