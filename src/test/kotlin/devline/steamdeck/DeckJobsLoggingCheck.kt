package devline.steamdeck

import com.google.gson.JsonObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Job output remains readable after completion and is mirrored without changing its cursor protocol. */
fun main() {
    val mirrored = CopyOnWriteArrayList<String>()
    DeckJobs({ true }, { mirrored += it }).use { jobs ->
        fun await(id: String): JsonObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val value = jobs.read(id, "owned-project", 0)
                if (value["state"].asString != "running") return value
                check(System.nanoTime() < deadline) { "Operation timed out" }
                Thread.sleep(5)
            }
        }
        val success = jobs.start("prepare", "owned-project") { job -> job.log("Extract Chromium\n"); JsonObject() }
        val completed = await(success["id"].asString)
        check(completed["state"].asString == "succeeded")
        check(jobs.current("owned-project")!!["id"] == success["id"])
        check(completed["data"].asString == mirrored.joinToString(""))
        check(completed["data"].asString.contains("[INFO] prepare completed."))
        check(jobs.read(success["id"].asString, "owned-project", completed["cursor"].asLong)["data"].asString.isEmpty())
        check(jobs.current("other-project") == null)

        val failed = jobs.start("prepare", "owned-project") { error("Missing runtime/bin/java") }
        val failure = await(failed["id"].asString)
        check(failure["state"].asString == "failed" && failure["error"].asString == "Missing runtime/bin/java")
        check(jobs.current("owned-project")!!["state"].asString == "failed")
        check(failure["data"].asString.contains("[ERROR] prepare failed: Missing runtime/bin/java"))

        val running = CountDownLatch(1)
        val pending = jobs.start("prepare", "owned-project") { running.countDown(); Thread.sleep(10000); JsonObject() }
        check(running.await(2, TimeUnit.SECONDS))
        val cancelled = jobs.cancel(pending["id"].asString, "owned-project")
        check(cancelled["state"].asString == "cancelled")
        check(cancelled["data"].asString.split("prepare cancelled.").size == 2)
        check(jobs.current("owned-project")!!["state"].asString == "cancelled")
        check(runCatching { jobs.read(pending["id"].asString, "other-project", 0) }.isFailure)
    }
    val bounded = DeckJob("prepare", null) { true }
    bounded.log("x".repeat(300 * 1024))
    val chunk = bounded.read(0)
    check(chunk["truncated"].asBoolean && chunk["data"].asString.length == 32 * 1024)
    val unavailableSink = DeckJob("prepare", null, { true }, { error("Output closed") })
    unavailableSink.log("Local diagnostics survive\n")
    check(unavailableSink.read(0)["data"].asString == "Local diagnostics survive\n")
    println("DECK_JOBS_LOGGING_PASS: mirrored lifecycle/phases, retained terminal diagnostics, cursor bounds, cancellation and project ownership")
}
