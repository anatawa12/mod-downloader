package com.anatawa12.downloader

/*
mod <id>
  from curse <slug>
  version <id> (<version>)
mod <id>
  from url "<url>"
  version <id> (<version>)
 */
class ModsConfig(
    val version: Version?,
    val list: List<ModInfo>,
) {
    internal class Reader1(private val body: String) {
        var line: Int = 1
        private var index = 0

        fun read(): Int {
            if (index == body.length) return -1
            val isLastCR = if (index !in 1 until body.length) false else body[index - 1] == '\r'
            val new = body[index++]
            when (new) {
                '\n' -> if (!isLastCR) line++
                '\r' -> line++
            }
            return new.code
        }

        fun peek(): Int {
            if (index == body.length) return -1
            return body[index].code
        }

        fun back() {
            if (index == 0) error("")
            index--
        }

        internal inline fun readUntil(isEnd: (Char) -> Boolean) = buildString {
            if (index == body.length) return@buildString
            val start = index
            val body = body
            for (i in index until body.length) {
                if (isEnd(body[i])) {
                    index = i
                    append(body, start, i)
                    return@buildString
                }
            }
            append(body, start, body.length)
            index = body.length
        }
    }

    class Parser(private val fileName: String, body: String) {
        private var kind: TokenKind? = null
        private var text: String? = null
        private val reader1 = Reader1(body)

        private fun prepareToken() {
            if (kind != null) return
            text = null
            // skip whitespace
            while (true) {
                val c = reader1.read()
                if (c == -1) return // eof
                if (c == '#'.code) { // #: skip until newline
                    while (reader1.peek() != '\r'.code && reader1.peek() != '\n'.code && reader1.peek() != -1)
                        reader1.read()
                } else if (!c.toChar().isWhitespace()) {
                    reader1.back()
                    break
                }
            }

            when (val was = reader1.read()) {
                '('.code -> {
                    kind = TokenKind.Parenthesized
                    text = reader1.readUntil { it == ')' }
                    if (reader1.read() != ')'.code) error("Unexpected EOF")
                }
                '"'.code -> {
                    kind = TokenKind.Quoted
                    text = reader1.readUntil { it == '"' }
                    if (reader1.read() != '"'.code) error("Unexpected EOF")
                }
                '\''.code -> {
                    kind = TokenKind.Quoted
                    text = reader1.readUntil { it == '\'' }
                    if (reader1.read() != '"'.code) error("Unexpected EOF")
                }
                else -> {
                    reader1.back()
                    kind = TokenKind.Keyword
                    text = reader1.readUntil { !it.isKeywordPart() }
                    if (text!!.isEmpty()) error("Expected Keyword but was ${was.toChar()}")
                }
            }
        }

        private fun kind(): TokenKind? {
            prepareToken()
            return kind
        }

        private fun text(): String? {
            prepareToken()
            return text
        }

        private fun expectKeyword(@Suppress("SameParameterValue") keyword: String) {
            if (kind() != TokenKind.Keyword) error("expexted '$keyword'")
            if (text()?.lowercase() != keyword) error("expexted '$keyword'")
            kind = null
        }

        private fun moveNext(): String {
            prepareToken()
            kind = null
            return text ?: error("")
        }

        private fun getKeywordAndMove(): String {
            if (kind() != TokenKind.Keyword) error("expected Keyword")
            kind = null
            return text!!
        }

        private fun getKeywordOrQuotedAndMove(): String {
            if (kind() != TokenKind.Keyword && kind() != TokenKind.Quoted) error("expected Keyword or Quoted")
            kind = null
            return text!!
        }

        private fun tryParenthesized(): String? = if (kind() == TokenKind.Parenthesized) moveNext() else null

        private fun error(message: String): Nothing = throw ParsingError(message, fileName, reader1.line)

        fun parseModsConfig(): ModsConfig = ModsConfig(
            version = tryParseFileVersionAndThrowErrorIfUnsupported(),
            list = buildList { while (kind() != null) add(parseMod()) }
        )

        private fun tryParseFileVersionAndThrowErrorIfUnsupported(): Version? {
            if (!(kind() == TokenKind.Keyword && text() == "version")) return null
            kind = null
            val keyword = getKeywordOrQuotedAndMove()
            val version = try {
                Version.parse(keyword)
            } catch (ignored: IllegalArgumentException) {
                error("invalid version name: $keyword")
            }
            if (!version.isSupported())
                error("config file for unsupported version found! Please upgrade mod downloader! :$version")
            return version
        }

        private fun parseMod(): ModInfo {
            var optional = false
            var side: ModSide? = null
            while (true) {
                if (kind() != TokenKind.Keyword) error("expected 'mod' or mod modifier")
                when (text()) {
                    "mod" -> {
                        kind = null
                        break
                    }
                    "optional" -> {
                        if (optional) error("multiple optional")
                        kind = null
                        optional = true
                    }
                    "server" -> {
                        if (side != null) error("multiple server or client")
                        kind = null
                        side = ModSide.SERVER
                    }
                    "client" -> {
                        if (side != null) error("multiple server or client")
                        kind = null
                        side = ModSide.CLIENT
                    }
                    else -> error("unknown mod modifier: $text")
                }
            }
            val id = getKeywordOrQuotedAndMove()
            var source: ModSource? = null
            var versionId: String? = null
            var versionName: String? = null
            while (true) {
                if (kind() != TokenKind.Keyword) break
                when (text()) {
                    "from" -> {
                        if (source != null) error("multiple from")
                        kind = null
                        source = when (val kind = getKeywordAndMove().lowercase()) {
                            "curse" -> parseCurseModSource()
                            "url" -> parseUrlModSource()
                            "optifine" -> parseOptifineModSource()
                            else -> error("unexpected mod source kind: '$kind'")
                        }
                    }
                    "version" -> {
                        if (versionId != null) error("multiple from")
                        kind = null
                        versionId = getKeywordOrQuotedAndMove()
                        versionName = tryParenthesized()
                    }
                    else -> break
                }
            }
            if (source == null) error("expected 'from'")
            if (versionId == null) error("expected 'version'")
            return ModInfo(id, source, versionId, versionName, optional, side)
        }

        private fun parseCurseModSource(): CurseMod = CurseMod(getKeywordOrQuotedAndMove())

        private fun parseUrlModSource(): URLPattern = URLPattern(getKeywordOrQuotedAndMove())

        private fun parseOptifineModSource(): Optifine = Optifine

        enum class TokenKind {
            Keyword,
            Quoted,
            Parenthesized
        }

        companion object {
            @JvmStatic
            private fun Char.isKeywordPart(): Boolean = this in '0'..'9' || this in 'a'..'z'
                    || this in 'A'..'Z' || this == '-' || this == '_' || this == '.'
        }
    }

    data class ModInfo(
        val id: String,
        val source: ModSource,
        val versionId: String,
        val versionName: String?,
        val optional: Boolean = false,
        val modSide: ModSide? = null,
    )

    enum class ModSide {
        SERVER,
        CLIENT,
    }

    sealed class ModSource
    data class CurseMod(val slug: String) : ModSource()
    data class URLPattern(val urlPattern: String) : ModSource()

    @Suppress("SpellCheckingInspection")
    object Optifine : ModSource()
}
