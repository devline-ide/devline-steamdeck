package devline.steamdeck

import com.google.gson.Gson
import com.google.gson.JsonObject
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import devline.extensions.IdeExtensionFiles
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal class DeckSettingsStore(private val context: IdeExtensionContext) {
    private val global = context.profileDirectory.resolve("devline.steamdeck/settings.json")
    private val machineKeys = setOf("host", "port", "user", "identityFile", "sdkPath")
    private val gameKeys = setOf("appId", "cloudDirectory", "development", "backend")
    private val gson = Gson()
    private fun select(values: JsonObject, keys: Set<String>) = JsonObject().apply { for (key in keys) values[key]?.let { add(key, it.deepCopy()) } }
    private fun read(file: Path): JsonObject {
        if (!Files.exists(file, NOFOLLOW_LINKS)) return JsonObject()
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 8192)
        return gson.fromJson(Files.readString(file), JsonObject::class.java)
    }
    private fun projectFile(owner: String, create: Boolean = false): Path {
        val root = Path.of(context.projectRoot(owner)).toRealPath()
        var directory = root
        for (segment in listOf(".devline", "extensions")) {
            directory = directory.resolve(segment)
            if (Files.exists(directory, NOFOLLOW_LINKS)) require(Files.isDirectory(directory, NOFOLLOW_LINKS))
            else if (create) Files.createDirectory(directory)
        }
        return directory.resolve("devline.steamdeck.json")
    }
    fun read(owner: String?, scope: String? = null): JsonObject {
        require(scope == null || scope in setOf("user", "workspace"))
        if (scope == "workspace") return select(read(projectFile(requireNotNull(owner) { "Open a project first" })), gameKeys)
        return select(read(global), machineKeys).apply {
            if (scope == null && owner != null) for ((key, value) in select(read(projectFile(owner)), gameKeys).entrySet()) add(key, value)
        }
    }
    fun write(owner: String?, scope: String, values: JsonObject): JsonObject {
        val keys = when (scope) { "user" -> machineKeys; "workspace" -> gameKeys; else -> error("Invalid settings scope") }
        require(values.keySet().all { it in keys } && values.toString().length <= 8192)
        for ((key, value) in values.entrySet()) require(value.isJsonPrimitive && when (key) {
            "development" -> value.asJsonPrimitive.isBoolean
            "port" -> value.asJsonPrimitive.isNumber && value.asString.matches(Regex("[0-9]{1,5}")) && value.asInt in 1..65535
            else -> value.asJsonPrimitive.isString
        })
        require(values["host"]?.asString.orEmpty().let { it.isEmpty() || it.matches(Regex("[A-Za-z0-9][A-Za-z0-9.:-]{0,252}")) })
        require(values["user"]?.asString.orEmpty().let { it.isEmpty() || it.matches(Regex("[a-z_][a-z0-9_-]{0,31}")) })
        require(values["backend"]?.asString.orEmpty() in setOf("", "javafx", "desktop-web"))
        require(values["appId"]?.asString.orEmpty().let { it.isEmpty() || it.matches(Regex("[1-9][0-9]{0,9}")) && it.toLong() <= 0xffffffffL })
        require(values["cloudDirectory"]?.asString.orEmpty().let { it.isEmpty() || !it.startsWith('/') && !it.startsWith('\\') && ':' !in it && it.replace('\\', '/').split('/').none { part -> part in setOf("", ".", "..") } })
        val target = if (scope == "workspace") {
            val project = requireNotNull(owner) { "Open a project first" }
            val overview = context.callProject(project, "project.overview", JsonObject()).asJsonObject
            if (overview.getAsJsonObject("project")["readOnly"]?.asBoolean != false) throw IdeExtensionException("ACCESS_DENIED", "The project is read-only.")
            projectFile(project, true)
        } else global
        val previous = read(target)
        // Old globally stored game keys remain recoverable, but are never inherited by games.
        for ((key, value) in values.entrySet()) previous.add(key, value)
        IdeExtensionFiles.writeAtomic(target, gson.toJson(previous))
        return read(owner, scope)
    }
}
