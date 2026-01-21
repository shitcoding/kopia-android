package org.kopiaKt.snapshot.fs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for WildcardMatcher pattern matching.
 */
class WildcardMatcherTest {

    @Nested
    inner class LiteralPatterns {
        @Test
        fun `matches exact filename`() {
            val matcher = WildcardMatcher.parse("file.txt")
            assertTrue(matcher.match("file.txt"))
            assertFalse(matcher.match("other.txt"))
            assertFalse(matcher.match("file.txt.bak"))
        }

        @Test
        fun `matches exact path`() {
            val matcher = WildcardMatcher.parse("dir/file.txt")
            assertTrue(matcher.match("dir/file.txt"))
            assertFalse(matcher.match("file.txt"))
            assertFalse(matcher.match("other/file.txt"))
        }
    }

    @Nested
    inner class StarPatterns {
        @Test
        fun `star matches any characters in filename`() {
            val matcher = WildcardMatcher.parse("*.txt")
            assertTrue(matcher.match("file.txt"))
            assertTrue(matcher.match("a.txt"))
            assertTrue(matcher.match("long-filename.txt"))
            assertFalse(matcher.match("file.md"))
            // Patterns without / match against basename, so *.txt matches dir/file.txt
            assertTrue(matcher.match("dir/file.txt"))
        }

        @Test
        fun `star in middle of pattern`() {
            val matcher = WildcardMatcher.parse("file*.txt")
            assertTrue(matcher.match("file.txt"))
            assertTrue(matcher.match("file1.txt"))
            assertTrue(matcher.match("file_backup.txt"))
            assertFalse(matcher.match("myfile.txt"))
        }

        @Test
        fun `star matches empty string`() {
            val matcher = WildcardMatcher.parse("test*")
            assertTrue(matcher.match("test"))
            assertTrue(matcher.match("test123"))
        }
    }

    @Nested
    inner class DoubleStarPatterns {
        @Test
        fun `double star matches any path depth`() {
            val matcher = WildcardMatcher.parse("**/*.txt")
            assertTrue(matcher.match("file.txt"))
            assertTrue(matcher.match("dir/file.txt"))
            assertTrue(matcher.match("a/b/c/file.txt"))
        }

        @Test
        fun `double star at start`() {
            val matcher = WildcardMatcher.parse("**/node_modules")
            assertTrue(matcher.match("node_modules"))
            assertTrue(matcher.match("project/node_modules"))
            assertTrue(matcher.match("a/b/c/node_modules"))
        }

        @Test
        fun `double star in middle`() {
            val matcher = WildcardMatcher.parse("src/**/test.kt")
            assertTrue(matcher.match("src/test.kt"))
            assertTrue(matcher.match("src/main/test.kt"))
            assertTrue(matcher.match("src/a/b/c/test.kt"))
            assertFalse(matcher.match("other/test.kt"))
        }

        @Test
        fun `double star at end`() {
            val matcher = WildcardMatcher.parse("build/**")
            assertTrue(matcher.match("build"))
            assertTrue(matcher.match("build/classes"))
            assertTrue(matcher.match("build/a/b/c"))
        }
    }

    @Nested
    inner class QuestionMarkPatterns {
        @Test
        fun `question mark matches single character`() {
            val matcher = WildcardMatcher.parse("file?.txt")
            assertTrue(matcher.match("file1.txt"))
            assertTrue(matcher.match("fileA.txt"))
            assertFalse(matcher.match("file.txt"))
            assertFalse(matcher.match("file12.txt"))
        }

        @Test
        fun `multiple question marks`() {
            val matcher = WildcardMatcher.parse("???.txt")
            assertTrue(matcher.match("abc.txt"))
            assertFalse(matcher.match("ab.txt"))
            assertFalse(matcher.match("abcd.txt"))
        }
    }

    @Nested
    inner class CharacterClassPatterns {
        @Test
        fun `character class matches single char from set`() {
            val matcher = WildcardMatcher.parse("file[123].txt")
            assertTrue(matcher.match("file1.txt"))
            assertTrue(matcher.match("file2.txt"))
            assertTrue(matcher.match("file3.txt"))
            assertFalse(matcher.match("file4.txt"))
            assertFalse(matcher.match("file.txt"))
        }

        @Test
        fun `character range`() {
            val matcher = WildcardMatcher.parse("file[a-z].txt")
            assertTrue(matcher.match("filea.txt"))
            assertTrue(matcher.match("filez.txt"))
            assertFalse(matcher.match("fileA.txt"))
            assertFalse(matcher.match("file1.txt"))
        }

        @Test
        fun `negated character class with exclamation`() {
            val matcher = WildcardMatcher.parse("file[!0-9].txt")
            assertTrue(matcher.match("filea.txt"))
            assertTrue(matcher.match("fileZ.txt"))
            assertFalse(matcher.match("file1.txt"))
            assertFalse(matcher.match("file9.txt"))
        }

        @Test
        fun `negated character class with caret`() {
            val matcher = WildcardMatcher.parse("file[^0-9].txt")
            assertTrue(matcher.match("filea.txt"))
            assertFalse(matcher.match("file1.txt"))
        }
    }

    @Nested
    inner class NegationPatterns {
        @Test
        fun `negated pattern`() {
            val matcher = WildcardMatcher.parse("!important.txt")
            assertTrue(matcher.negated)
            assertTrue(matcher.match("important.txt"))
        }

        @Test
        fun `shouldIgnore respects negation`() {
            val matchers = WildcardMatcher.parseAll(listOf(
                "*.log",
                "!important.log"
            ))

            assertTrue(shouldIgnore("debug.log", false, matchers))
            assertTrue(shouldIgnore("error.log", false, matchers))
            assertFalse(shouldIgnore("important.log", false, matchers)) // Un-ignored
            assertFalse(shouldIgnore("file.txt", false, matchers))
        }
    }

    @Nested
    inner class DirectoryOnlyPatterns {
        @Test
        fun `directory-only pattern matches directories`() {
            val matcher = WildcardMatcher.parse("build/")
            assertTrue(matcher.dirOnly)
            assertTrue(matcher.match("build", isDir = true))
            assertFalse(matcher.match("build", isDir = false))
        }

        @Test
        fun `directory-only with wildcard`() {
            val matcher = WildcardMatcher.parse("*/node_modules/")
            assertTrue(matcher.match("project/node_modules", isDir = true))
            assertFalse(matcher.match("project/node_modules", isDir = false))
        }
    }

    @Nested
    inner class EscapePatterns {
        @Test
        fun `escaped special characters`() {
            val matcher = WildcardMatcher.parse("file\\*.txt")
            assertTrue(matcher.match("file*.txt"))
            assertFalse(matcher.match("fileA.txt"))
        }

        @Test
        fun `escaped brackets`() {
            val matcher = WildcardMatcher.parse("file\\[1\\].txt")
            assertTrue(matcher.match("file[1].txt"))
            assertFalse(matcher.match("file1.txt"))
        }
    }

    @Nested
    inner class CaseInsensitiveMatching {
        @Test
        fun `case insensitive matching`() {
            val matcher = WildcardMatcher.parse(
                "readme.md",
                WildcardMatcher.Options(ignoreCase = true)
            )
            assertTrue(matcher.match("README.md"))
            assertTrue(matcher.match("Readme.MD"))
            assertTrue(matcher.match("readme.md"))
        }

        @Test
        fun `case sensitive by default`() {
            val matcher = WildcardMatcher.parse("readme.md")
            assertTrue(matcher.match("readme.md"))
            assertFalse(matcher.match("README.md"))
        }
    }

    @Nested
    inner class PathNormalization {
        @Test
        fun `normalizes backslashes`() {
            val matcher = WildcardMatcher.parse("dir/file.txt")
            assertTrue(matcher.match("dir\\file.txt"))
        }

        @Test
        fun `removes leading dot slash`() {
            val matcher = WildcardMatcher.parse("file.txt")
            assertTrue(matcher.match("./file.txt"))
        }

        @Test
        fun `handles leading slash in pattern`() {
            val matcher = WildcardMatcher.parse("/root.txt")
            assertTrue(matcher.match("root.txt"))
        }
    }

    @Nested
    inner class ParseAll {
        @Test
        fun `parses multiple patterns`() {
            val matchers = WildcardMatcher.parseAll(listOf(
                "*.log",
                "*.tmp",
                "build/"
            ))
            assertEquals(3, matchers.size)
        }

        @Test
        fun `filters out comments and blank lines`() {
            val matchers = WildcardMatcher.parseAll(listOf(
                "# This is a comment",
                "*.log",
                "",
                "   ",
                "*.tmp"
            ))
            assertEquals(2, matchers.size)
        }
    }

    @Nested
    inner class CommonIgnorePatterns {
        @Test
        fun `gitignore-style patterns`() {
            val matchers = WildcardMatcher.parseAll(listOf(
                "node_modules/",
                ".git/",
                "*.class",
                "build/",
                "**/*.log",
                "!important.log"
            ))

            assertTrue(shouldIgnore("node_modules", true, matchers))
            assertTrue(shouldIgnore(".git", true, matchers))
            assertTrue(shouldIgnore("Main.class", false, matchers))
            assertTrue(shouldIgnore("build", true, matchers))
            assertTrue(shouldIgnore("logs/app.log", false, matchers))
            assertFalse(shouldIgnore("important.log", false, matchers))
            assertFalse(shouldIgnore("src/Main.kt", false, matchers))
        }

        @Test
        fun `kopiaignore patterns`() {
            val matchers = WildcardMatcher.parseAll(listOf(
                "*.tmp",
                "*.swp",
                ".DS_Store",
                "Thumbs.db",
                "__pycache__/",
                "*.pyc"
            ))

            assertTrue(shouldIgnore("temp.tmp", false, matchers))
            assertTrue(shouldIgnore(".file.swp", false, matchers))
            assertTrue(shouldIgnore(".DS_Store", false, matchers))
            assertTrue(shouldIgnore("Thumbs.db", false, matchers))
            assertTrue(shouldIgnore("__pycache__", true, matchers))
            assertTrue(shouldIgnore("module.pyc", false, matchers))
            assertFalse(shouldIgnore("document.pdf", false, matchers))
        }
    }
}
