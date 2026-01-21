package org.kopiaKt.snapshot.fs

/**
 * Wildcard pattern matcher compatible with .gitignore-style patterns.
 *
 * Supports:
 * - `*` matches any characters except path separator
 * - `**` matches any characters including path separator (any number of directories)
 * - `?` matches single character
 * - `[abc]` matches character set
 * - `[a-z]` matches character range
 * - `[!abc]` or `[^abc]` matches characters NOT in set
 * - `!` prefix negates the pattern
 * - `/` suffix matches directories only
 * - `\` escapes special characters
 *
 * Go type: wcmatch.WildcardMatcher
 */
class WildcardMatcher private constructor(
    val pattern: String,
    private val tokens: List<Token>,
    val negated: Boolean,
    val dirOnly: Boolean,
    private val matchBasename: Boolean, // If true, match against basename only (no / in original pattern)
    private val options: Options = Options()
) {

    data class Options(
        val ignoreCase: Boolean = false,
        val baseDir: String = ""
    )

    private sealed class Token {
        data object Star : Token()                     // *
        data object DoubleStar : Token()              // **
        data object Question : Token()                // ?
        data class Literal(val text: String) : Token()
        data class CharClass(val chars: Set<Char>, val negated: Boolean) : Token()
        data object Separator : Token()               // /
    }

    /**
     * Matches the pattern against a path.
     *
     * @param path The path to match (using / as separator)
     * @param isDir Whether the path represents a directory
     * @return true if the pattern matches
     */
    fun match(path: String, isDir: Boolean = false): Boolean {
        // Directory-only patterns don't match files
        if (dirOnly && !isDir) {
            return false
        }

        val normalizedPath = normalizePath(path)

        // If pattern has no /, match against basename only
        if (matchBasename) {
            val basename = normalizedPath.substringAfterLast('/')
            return matchTokens(basename, 0, 0)
        }

        return matchTokens(normalizedPath, 0, 0)
    }

    private fun normalizePath(path: String): String {
        var p = path.replace('\\', '/')
        if (options.ignoreCase) {
            p = p.lowercase()
        }
        // Remove leading ./
        while (p.startsWith("./")) {
            p = p.substring(2)
        }
        // Remove trailing /
        while (p.endsWith("/") && p.length > 1) {
            p = p.dropLast(1)
        }
        return p
    }

    private fun matchTokens(path: String, pathIndex: Int, tokenIndex: Int): Boolean {
        var pi = pathIndex
        var ti = tokenIndex

        while (ti < tokens.size) {
            val token = tokens[ti]

            when (token) {
                is Token.DoubleStar -> {
                    // ** matches zero or more path components
                    // Check if next token is a separator (common pattern: **/)
                    val hasFollowingSeparator = ti + 1 < tokens.size && tokens[ti + 1] is Token.Separator

                    if (hasFollowingSeparator) {
                        // Pattern like **/ - match zero or more path segments
                        // Skip the separator token - we'll handle both ** and / together
                        val afterSeparatorIndex = ti + 2

                        // Try matching zero segments (skip both ** and /)
                        if (matchTokens(path, pi, afterSeparatorIndex)) {
                            return true
                        }

                        // Try matching at each path segment boundary
                        var searchFrom = pi
                        while (true) {
                            val nextSlash = path.indexOf('/', searchFrom)
                            if (nextSlash < 0) {
                                // No more slashes - try matching from after last segment
                                break
                            }
                            // Try matching after this slash
                            if (matchTokens(path, nextSlash + 1, afterSeparatorIndex)) {
                                return true
                            }
                            searchFrom = nextSlash + 1
                        }
                        return false
                    } else {
                        // ** at end or before non-separator token
                        // Match zero or more characters including /
                        for (nextPi in pi..path.length) {
                            if (matchTokens(path, nextPi, ti + 1)) {
                                return true
                            }
                        }
                        return false
                    }
                }

                is Token.Star -> {
                    // * matches any characters except /
                    val nextSeparator = path.indexOf('/', pi).let { if (it < 0) path.length else it }
                    // Try matching with each possible length
                    for (nextPi in pi..nextSeparator) {
                        if (matchTokens(path, nextPi, ti + 1)) {
                            return true
                        }
                    }
                    return false
                }

                is Token.Question -> {
                    // ? matches single character except /
                    if (pi >= path.length || path[pi] == '/') {
                        return false
                    }
                    pi++
                    ti++
                }

                is Token.Literal -> {
                    val text = if (options.ignoreCase) token.text.lowercase() else token.text
                    if (!path.substring(pi).startsWith(text)) {
                        return false
                    }
                    pi += text.length
                    ti++
                }

                is Token.CharClass -> {
                    if (pi >= path.length) {
                        return false
                    }
                    val c = if (options.ignoreCase) path[pi].lowercaseChar() else path[pi]
                    val inClass = c in token.chars
                    if (inClass == token.negated) {
                        return false
                    }
                    pi++
                    ti++
                }

                is Token.Separator -> {
                    // Special case: if separator is followed by ** at end, and we're at end of path,
                    // allow matching (e.g., "build/**" should match "build")
                    if (pi >= path.length) {
                        // Check if remaining pattern is just /**
                        if (ti + 1 < tokens.size && tokens[ti + 1] is Token.DoubleStar && ti + 2 >= tokens.size) {
                            return true
                        }
                        return false
                    }
                    if (path[pi] != '/') {
                        return false
                    }
                    pi++
                    ti++
                }
            }
        }

        // Pattern is fully consumed; path must also be fully consumed
        return pi >= path.length
    }

    companion object {
        /**
         * Parses a pattern string into a WildcardMatcher.
         *
         * @param pattern The pattern to parse
         * @param options Matching options
         * @return The compiled matcher
         */
        fun parse(pattern: String, options: Options = Options()): WildcardMatcher {
            var p = pattern.trim()

            // Check for negation prefix
            val negated = p.startsWith("!")
            if (negated) {
                p = p.substring(1)
            }

            // Check for directory-only suffix
            val dirOnly = p.endsWith("/")
            if (dirOnly) {
                p = p.dropLast(1)
            }

            // Check if pattern contains / (excluding leading /)
            // Patterns without / match against basename only
            val hasSlash = p.contains("/")

            // Handle leading /
            if (p.startsWith("/")) {
                p = p.substring(1)
            }

            // If pattern has no / (except leading), match basename only
            // Exception: ** patterns should match full path
            val matchBasename = !hasSlash && !p.startsWith("**")

            val tokens = tokenize(p, options)
            return WildcardMatcher(pattern, tokens, negated, dirOnly, matchBasename, options)
        }

        /**
         * Creates a list of matchers from multiple patterns.
         */
        fun parseAll(patterns: List<String>, options: Options = Options()): List<WildcardMatcher> {
            return patterns
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { parse(it, options) }
        }

        private fun tokenize(pattern: String, options: Options): List<Token> {
            val tokens = mutableListOf<Token>()
            var i = 0

            while (i < pattern.length) {
                when {
                    // ** globstar
                    pattern.substring(i).startsWith("**/") -> {
                        tokens.add(Token.DoubleStar)
                        tokens.add(Token.Separator)
                        i += 3
                    }
                    pattern.substring(i).startsWith("**") -> {
                        tokens.add(Token.DoubleStar)
                        i += 2
                    }
                    // Single *
                    pattern[i] == '*' -> {
                        tokens.add(Token.Star)
                        i++
                    }
                    // ?
                    pattern[i] == '?' -> {
                        tokens.add(Token.Question)
                        i++
                    }
                    // Character class [...]
                    pattern[i] == '[' -> {
                        val (charClass, endIndex) = parseCharClass(pattern, i, options)
                        tokens.add(charClass)
                        i = endIndex
                    }
                    // Separator
                    pattern[i] == '/' -> {
                        tokens.add(Token.Separator)
                        i++
                    }
                    // Escape
                    pattern[i] == '\\' && i + 1 < pattern.length -> {
                        val escaped = pattern[i + 1].toString()
                        tokens.add(Token.Literal(if (options.ignoreCase) escaped.lowercase() else escaped))
                        i += 2
                    }
                    // Literal character
                    else -> {
                        // Collect consecutive literal characters
                        val start = i
                        while (i < pattern.length && pattern[i] !in "*?[/\\") {
                            i++
                        }
                        val literal = pattern.substring(start, i)
                        tokens.add(Token.Literal(if (options.ignoreCase) literal.lowercase() else literal))
                    }
                }
            }

            return tokens
        }

        private fun parseCharClass(pattern: String, startIndex: Int, options: Options): Pair<Token.CharClass, Int> {
            val chars = mutableSetOf<Char>()
            var i = startIndex + 1 // Skip opening [
            var negated = false

            // Check for negation
            if (i < pattern.length && (pattern[i] == '!' || pattern[i] == '^')) {
                negated = true
                i++
            }

            // Handle ] as first character (literal)
            if (i < pattern.length && pattern[i] == ']') {
                chars.add(']')
                i++
            }

            while (i < pattern.length && pattern[i] != ']') {
                when {
                    // Range a-z
                    i + 2 < pattern.length && pattern[i + 1] == '-' && pattern[i + 2] != ']' -> {
                        val start = pattern[i]
                        val end = pattern[i + 2]
                        for (c in start..end) {
                            chars.add(if (options.ignoreCase) c.lowercaseChar() else c)
                        }
                        i += 3
                    }
                    // Escape within character class
                    pattern[i] == '\\' && i + 1 < pattern.length -> {
                        val c = pattern[i + 1]
                        chars.add(if (options.ignoreCase) c.lowercaseChar() else c)
                        i += 2
                    }
                    // Regular character
                    else -> {
                        val c = pattern[i]
                        chars.add(if (options.ignoreCase) c.lowercaseChar() else c)
                        i++
                    }
                }
            }

            // Skip closing ]
            if (i < pattern.length && pattern[i] == ']') {
                i++
            }

            return Token.CharClass(chars, negated) to i
        }
    }
}

/**
 * Checks if a path matches any of the given patterns.
 *
 * @param path The path to check
 * @param isDir Whether the path represents a directory
 * @param matchers The list of matchers to check against
 * @return true if the path should be ignored (matched by a non-negated pattern
 *         and not un-matched by a negated pattern)
 */
fun shouldIgnore(path: String, isDir: Boolean, matchers: List<WildcardMatcher>): Boolean {
    var ignored = false

    for (matcher in matchers) {
        if (matcher.match(path, isDir)) {
            ignored = !matcher.negated
        }
    }

    return ignored
}
