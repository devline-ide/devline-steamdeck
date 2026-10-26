package devline.steamdeck

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class DeckJob(val kind: String, val owner: String?, private val enabled: () -> Boolean, private val logSink: (String) -> Unit) {
    constructor(kind: String, owner: String?, enabled: () -> Boolean) : this(kind, owner, enabled, {})
    val id = UUID.randomUUID().toString()
    @Volatile var state = "running"
    @Volatile var cancelled = false
    @Volatile var downloaded = 0L
    @Volatile var total = 0L
    @Volatile var error: String? = null
    @Volatile var result: JsonObject? = null
    var future: Future<*>? = null
    private val output = StringBuilder()
    private var end = 0L
    fun check() { if (cancelled || Thread.currentThread().isInterrupted || !enabled()) throw CancellationException("Operation cancelled") }
    @Synchronized fun log(text: String) {
        output.append(text); end += text.length
        if (output.length > 256 * 1024) output.delete(0, output.length - 256 * 1024)
        // Diagnostics must not fail a preparation if the host's output stream is unavailable.
        runCatching { logSink(text) }
    }
    @Synchronized fun finish(next: String, failure: String? = null) {
        if (state != "running") return
        error = failure
        log(when (next) {
            "succeeded" -> "[INFO] $kind completed.\n"
            "cancelled" -> "[INFO] $kind cancelled.\n"
            else -> "[ERROR] $kind failed: $failure\n"
        })
        state = next
    }
    @Synchronized fun read(cursor: Long): JsonObject {
        require(cursor in 0..end) { "Invalid operation output cursor" }
        val first = end - output.length
        val from = maxOf(first, cursor)
        val to = minOf(end, from + 32 * 1024)
        return Gson().toJsonTree(mapOf("id" to id, "kind" to kind, "state" to state, "downloaded" to downloaded,
            "total" to total, "error" to error, "result" to result, "cursor" to to,
            "truncated" to (cursor < first), "data" to output.substring((from - first).toInt(), (to - first).toInt()))).asJsonObject
    }
}

internal class DeckJobs(private val enabled: () -> Boolean, private val logSink: (String) -> Unit = {}) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "devline-steamdeck").apply { isDaemon = true } }
    private val jobs = linkedMapOf<String, DeckJob>()
    @Synchronized fun start(kind: String, owner: String?, action: (DeckJob) -> JsonObject): JsonObject {
        require(jobs.values.none { it.state == "running" }) { "A Steam Deck operation is already running" }
        while (jobs.size >= 16) jobs.remove(jobs.keys.first())
        val job = DeckJob(kind, owner, enabled, logSink)
        jobs[job.id] = job
        job.log("[INFO] $kind started.\n")
        job.future = worker.submit {
            try { job.check(); job.result = action(job); job.check(); job.finish("succeeded") }
            catch (_: CancellationException) { job.finish("cancelled") }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); job.finish("cancelled") }
            catch (cause: Exception) { if (job.cancelled) job.finish("cancelled") else job.finish("failed", cause.message ?: cause.javaClass.simpleName) }
        }
        return job.read(0)
    }
    @Synchronized fun read(id: String, owner: String?, cursor: Long): JsonObject = owned(id, owner).read(cursor)
    @Synchronized fun current(owner: String?): JsonObject? = jobs.values.lastOrNull { it.owner == owner }?.read(0)
    @Synchronized fun cancel(id: String, owner: String?): JsonObject {
        val job = owned(id, owner)
        job.cancelled = true; job.future?.cancel(true)
        job.finish("cancelled")
        return job.read(0)
    }
    @Synchronized private fun owned(id: String, owner: String?) = jobs[id]?.also { require(it.owner == owner) { "The operation belongs to another project" } }
        ?: throw IllegalArgumentException("Unknown Steam Deck operation")
    @Synchronized fun closeProject() { jobs.values.filter { it.owner != null && it.state == "running" }.forEach { it.cancelled = true; it.future?.cancel(true); it.finish("cancelled") } }
    @Synchronized override fun close() { jobs.values.forEach { it.cancelled = true; it.future?.cancel(true); it.finish("cancelled") }; worker.shutdownNow() }
}
