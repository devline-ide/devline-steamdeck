package devline.steamdeck

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import devline.extensions.IdeExtensionContext
import devline.extensions.IdeExtensionException
import devline.extensions.IdeExtensionRuntime
import devline.decktools.DeckDevice
import devline.decktools.DeckProjectRun
import java.nio.file.Files
import java.nio.file.Path

internal class SteamDeckRuntime(private val context: IdeExtensionContext) : IdeExtensionRuntime {
    private val settings = DeckSettingsStore(context)
    private val toolchain = LinuxToolchain(context.profileDirectory)
    private val jobs = DeckJobs(context::enabled) { text ->
        text.lineSequence().filter(String::isNotBlank).forEach { System.out.println("[Steam Deck] $it") }
    }
    override fun request(method: String, params: JsonObject): JsonElement {
        val owner = params["expectedProjectFile"]?.asString
        val scope = params["settingsScope"]?.asString
        owner?.let(context::projectRoot)
        return when (method.substringAfter('.')) {
            "status" -> JsonObject().apply {
                add("toolchain", toolchain.status()); add("settings", settings.read(owner, scope)); addProperty("hardwareVerified", false)
                add("capabilities", JsonObject().apply { addProperty("device", true); addProperty("steamworks", false) })
                jobs.current(owner)?.let { add("operation", it) }
            }
            "configure" -> {
                val values = params.getAsJsonObject("settings") ?: throw IllegalArgumentException("Settings are required")
                settings.write(owner, scope ?: "user", values)
            }
            "prepare" -> jobs.start("prepare", owner, toolchain::prepare)
            "check" -> jobs.start("check", owner) { job -> device(job).check() }
            "deploy", "run" -> {
                val project = owner ?: throw IllegalArgumentException("Open a project before running on Steam Deck")
                val backend = deckRunBackend(params, settings.read(owner))
                jobs.start(method.substringAfter('.'), owner) { job ->
                    DeckProjectRun(context, device(job), job::log, job::check).deploy(project, backend, method.substringAfter('.') == "run")
                }
            }
            "deviceStatus" -> device().call("status")
            "frame", "logs", "stop", "remove" -> device().call(method.substringAfter('.'), params.deepCopy().apply {
                remove("expectedProjectFile"); remove("panelId")
                if (method.substringAfter('.') == "logs") addProperty("maxBytes", minOf(get("maxBytes")?.asInt ?: 16384, 16384))
            }).also { value ->
                if (method.substringAfter('.') == "logs") value["data"]?.asString?.takeIf(String::isNotBlank)?.lineSequence()?.forEach { System.out.println("[Steam Deck app] $it") }
            }
            "plugin" -> {
                val directory = context.profileDirectory.resolve("devline.steamdeck/tools")
                Files.createDirectories(directory)
                val target = directory.resolve("devline-test-runner.zip")
                val bytes = requireNotNull(DeckDevice::class.java.getResourceAsStream("/devline/decktools/devline-test-runner.zip")).use { it.readAllBytes() }
                val temporary = Files.createTempFile(directory, ".plugin-", ".zip")
                try {
                    Files.write(temporary, bytes)
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(temporary) }
                JsonObject().apply { addProperty("path", target.toString()) }
            }
            "read" -> jobs.read(params["id"].asString, owner, params["cursor"]?.asLong ?: 0)
            "cancel" -> jobs.cancel(params["id"].asString, owner)
            else -> throw IdeExtensionException("UNKNOWN_METHOD", "Unknown Steam Deck action.")
        }
    }
    private fun device(job: DeckJob? = null): DeckDevice {
        val values = settings.read(null, "user")
        return DeckDevice(DeckDevice.Connection(
            values["host"]?.asString.orEmpty(), values["port"]?.asInt ?: 22,
            values["user"]?.asString?.ifBlank { "deck" } ?: "deck",
            values["identityFile"]?.asString?.takeIf(String::isNotBlank)?.let(Path::of),
        ), { value -> if (job != null) job.log(value.trimEnd() + "\n") }, { job?.check() ?: check(context.enabled()) { "The Steam Deck extension is disabled" } })
    }
    override fun projectClosed() = jobs.closeProject()
    override fun close() = jobs.close()
}
