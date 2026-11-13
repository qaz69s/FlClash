package com.follow.clash.common

import java.util.concurrent.TimeUnit

object Su {
    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    fun run(script: String, timeoutSeconds: Long = 30): Result {
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (error: Exception) {
            return Result(-1, "su: ${error.message}")
        }
        return try {
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(script)
                writer.write("\nexit $?\n")
            }
            val output = StringBuilder()
            val reader = Thread {
                runCatching { output.append(process.inputStream.bufferedReader().readText()) }
            }.apply { start() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result(-1, "su timed out")
            }
            reader.join(1000)
            Result(process.exitValue(), output.toString().trim())
        } catch (error: Exception) {
            process.destroy()
            Result(-1, "su: ${error.message}")
        }
    }

    fun available(): Boolean = run("id -u", 15).let { it.ok && it.output.lines().lastOrNull()?.trim() == "0" }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
