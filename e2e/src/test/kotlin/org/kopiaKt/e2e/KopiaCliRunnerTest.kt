package org.kopiaKt.e2e

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

/**
 * Tests for [KopiaCliRunner]'s low-level process execution (`run` method).
 *
 * These tests exercise stream capture, large-output deadlock avoidance, and
 * timeout behaviour using simple shell commands (not the Kopia binary).
 */
@EnabledOnOs(OS.MAC, OS.LINUX)
class KopiaCliRunnerTest {

    /**
     * Helper that creates a runner whose "binary" is /bin/sh -c,
     * so we can execute arbitrary shell one-liners.
     */
    private fun shellRunner(): KopiaCliRunner =
        KopiaCliRunner(
            kopiaBinary = Path.of("/bin/sh"),
            configDir = null,
            environment = emptyMap()
        )

    // ------------------------------------------------------------------
    // Stream capture
    // ------------------------------------------------------------------

    @Test
    fun `run captures both stdout and stderr`() = runTest(timeout = 1.minutes) {
        val runner = shellRunner()
        val result = runner.run(
            "-c", "echo hello-stdout; echo hello-stderr >&2"
        )

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.stdout.trim()).isEqualTo("hello-stdout")
        assertThat(result.stderr.trim()).isEqualTo("hello-stderr")
    }

    // ------------------------------------------------------------------
    // Deadlock avoidance with large output
    // ------------------------------------------------------------------

    @Test
    fun `run reads large stdout and stderr without deadlock`() = runTest(timeout = 2.minutes) {
        // Generate ~200KB on each stream. The default pipe buffer is typically
        // 64KB on Linux/macOS, so this would deadlock with sequential reads.
        val lineCount = 5000
        val runner = shellRunner()
        val result = runner.run(
            "-c",
            """
            i=0; while [ ${'$'}i -lt $lineCount ]; do echo "stdout-line-${'$'}i-padding-to-make-line-longer"; i=${'$'}((i+1)); done
            i=0; while [ ${'$'}i -lt $lineCount ]; do echo "stderr-line-${'$'}i-padding-to-make-line-longer" >&2; i=${'$'}((i+1)); done
            """.trimIndent()
        )

        assertThat(result.exitCode).isEqualTo(0)

        val stdoutLines = result.stdout.lines().filter { it.isNotEmpty() }
        val stderrLines = result.stderr.lines().filter { it.isNotEmpty() }

        assertThat(stdoutLines).hasSize(lineCount)
        assertThat(stderrLines).hasSize(lineCount)
        assertThat(stdoutLines.first()).startsWith("stdout-line-0")
        assertThat(stderrLines.first()).startsWith("stderr-line-0")
    }

    @Test
    fun `run reads interleaved large stdout and stderr without deadlock`() = runTest(timeout = 2.minutes) {
        // Writes to both streams in an interleaved fashion, which is the
        // pattern most likely to trigger the original deadlock.
        val iterations = 3000
        val runner = shellRunner()
        val result = runner.run(
            "-c",
            """
            i=0; while [ ${'$'}i -lt $iterations ]; do echo "out-${'$'}i"; echo "err-${'$'}i" >&2; i=${'$'}((i+1)); done
            """.trimIndent()
        )

        assertThat(result.exitCode).isEqualTo(0)

        val stdoutLines = result.stdout.lines().filter { it.isNotEmpty() }
        val stderrLines = result.stderr.lines().filter { it.isNotEmpty() }

        assertThat(stdoutLines).hasSize(iterations)
        assertThat(stderrLines).hasSize(iterations)
    }

    // ------------------------------------------------------------------
    // Timeout
    // ------------------------------------------------------------------

    @Test
    fun `run times out and kills long-running process`() = runTest(timeout = 1.minutes) {
        val runner = shellRunner()

        val exception = assertThrows<KopiaCliRunner.KopiaCommandException> {
            runner.run("-c", "sleep 120", timeoutSeconds = 2)
        }

        assertThat(exception.exitCode).isEqualTo(-1)
        assertThat(exception.message).contains("timed out")
    }
}
