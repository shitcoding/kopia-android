package org.kopiaKt.snapshot.fs

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.text.Normalizer
import kotlin.io.path.writeText

/**
 * Tests for Unicode and special character handling in filenames.
 * Verifies that LocalFilesystem.entry() correctly handles filenames with
 * various Unicode forms, emoji, CJK characters, RTL scripts, maximum
 * lengths, and special characters.
 */
@DisplayName("Unicode and Special Filename Tests")
class UnicodeFilenameTest {

    @TempDir
    lateinit var tempDir: Path

    @Nested
    @DisplayName("Unicode Normalization")
    inner class UnicodeNormalization {

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `should handle NFC normalized unicode filenames`() {
            // "cafe\u0301" composed to single code point e-acute (NFC form)
            val nfcName = Normalizer.normalize("caf\u00E9.txt", Normalizer.Form.NFC)
            val file = tempDir.resolve(nfcName)
            file.writeText("nfc content")

            val entry = LocalFilesystem.entry(file)

            assertThat(entry.type).isEqualTo(EntryType.FILE)
            assertThat(entry.size).isEqualTo(11L)
            // On macOS HFS+/APFS, the filesystem may store NFD, so the name we read
            // back should normalize to the same NFC form we wrote.
            val actualNfc = Normalizer.normalize(entry.name, Normalizer.Form.NFC)
            assertThat(actualNfc).isEqualTo(nfcName)
        }

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `should handle NFD normalized unicode filenames`() {
            // "cafe" + combining acute accent (NFD form)
            val nfdName = Normalizer.normalize("caf\u0065\u0301.txt", Normalizer.Form.NFD)
            val file = tempDir.resolve(nfdName)
            file.writeText("nfd content")

            val entry = LocalFilesystem.entry(file)

            assertThat(entry.type).isEqualTo(EntryType.FILE)
            assertThat(entry.size).isEqualTo(11L)
            // Normalize both to NFD for comparison since macOS stores NFD natively
            val actualNfd = Normalizer.normalize(entry.name, Normalizer.Form.NFD)
            assertThat(actualNfd).isEqualTo(nfdName)
        }
    }

    @Nested
    @DisplayName("Emoji Filenames")
    inner class EmojiFilenames {

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `should handle emoji in filenames`() {
            val emojiName = "\uD83C\uDF89test.txt" // "🎉test.txt"
            val file = tempDir.resolve(emojiName)
            file.writeText("emoji content")

            val entry = LocalFilesystem.entry(file)

            assertThat(entry.type).isEqualTo(EntryType.FILE)
            assertThat(entry.name).isEqualTo(emojiName)
            assertThat(entry.size).isEqualTo(13L)
            assertThat(entry.isFile()).isTrue()
        }
    }

    @Nested
    @DisplayName("CJK Filenames")
    inner class CjkFilenames {

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `should handle CJK characters in filenames`() {
            val cjkNames = listOf(
                "\u30C6\u30B9\u30C8.txt",  // テスト.txt (Japanese Katakana)
                "\u6D4B\u8BD5.txt",          // 测试.txt (Chinese Simplified)
                "\uD14C\uC2A4\uD2B8.txt"    // 테스트.txt (Korean Hangul)
            )

            for (cjkName in cjkNames) {
                val file = tempDir.resolve(cjkName)
                file.writeText("cjk content")

                val entry = LocalFilesystem.entry(file)

                assertThat(entry.type).isEqualTo(EntryType.FILE)
                assertThat(entry.name).isEqualTo(cjkName)
                assertThat(entry.isFile()).isTrue()
            }
        }
    }

    @Nested
    @DisplayName("RTL Filenames")
    inner class RtlFilenames {

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `should handle right-to-left characters in filenames`() {
            val rtlNames = listOf(
                "\u0645\u0644\u0641.txt",    // Arabic: ملف.txt ("file" in Arabic)
                "\u05E7\u05D5\u05D1\u05E5.txt" // Hebrew: קובץ.txt ("file" in Hebrew)
            )

            for (rtlName in rtlNames) {
                val file = tempDir.resolve(rtlName)
                file.writeText("rtl content")

                val entry = LocalFilesystem.entry(file)

                assertThat(entry.type).isEqualTo(EntryType.FILE)
                assertThat(entry.name).isEqualTo(rtlName)
                assertThat(entry.isFile()).isTrue()
            }
        }
    }

    @Nested
    @DisplayName("Maximum Length Filenames")
    inner class MaxLengthFilenames {

        @Test
        fun `should handle maximum length filenames`() {
            // Most filesystems (ext4, APFS, NTFS) support 255-byte filenames.
            // Build a name that is exactly 255 bytes in UTF-8 (using ASCII for simplicity).
            val maxName = "a".repeat(251) + ".txt" // 251 + 4 = 255 bytes

            val file = tempDir.resolve(maxName)
            // Some filesystems may not support 255-byte names; skip gracefully.
            try {
                file.writeText("max length content")
            } catch (e: Exception) {
                Assumptions.assumeTrue(false, "Filesystem does not support 255-byte filenames: ${e.message}")
                return
            }

            val entry = LocalFilesystem.entry(file)

            assertThat(entry.type).isEqualTo(EntryType.FILE)
            assertThat(entry.name).isEqualTo(maxName)
            assertThat(entry.name.toByteArray(Charsets.UTF_8).size).isEqualTo(255)
            assertThat(entry.isFile()).isTrue()
        }
    }

    @Nested
    @DisplayName("Special Character Filenames")
    inner class SpecialCharFilenames {

        @Test
        @DisabledOnOs(OS.WINDOWS)
        fun `should handle filenames with spaces and special chars`() {
            val specialNames = listOf(
                "[test].txt",
                "(test).txt",
                "test&file.txt",
                "test#file.txt",
                "test file.txt",
                "test  double-space.txt",
                "test'quote.txt",
                "test@at.txt",
                "test+plus.txt",
                "test=equals.txt",
                "test{brace}.txt",
                "test\$dollar.txt",
                "test!bang.txt",
                "test^caret.txt",
                "test~tilde.txt",
                "test,comma.txt",
                "test;semicolon.txt"
            )

            for (specialName in specialNames) {
                val file = tempDir.resolve(specialName)
                file.writeText("special content")

                val entry = LocalFilesystem.entry(file)

                assertWithMessage("type for filename '$specialName'")
                    .that(entry.type).isEqualTo(EntryType.FILE)
                assertWithMessage("name for filename '$specialName'")
                    .that(entry.name).isEqualTo(specialName)
                assertWithMessage("isFile for filename '$specialName'")
                    .that(entry.isFile()).isTrue()
            }
        }
    }
}
