package com.anatawa12.downloader

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.seconds

/*
mod <id>
  from curse <slug>
  version <id> (<version>)
mod <id>
  from url "<url>"
  version <id> (<version>)
mod <id>
    from zip
        from drive <file-id>
            of "mcheli"
            into "mcheli"
    version 1.0.4
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
                            "zip" -> parseZipModSource()
                            else -> parseSingleJarModSource(kind)
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

        // zip from <single> of <pathInJar: str> into <destPath: str>
        private fun parseZipModSource(): ZipModSource {
            var from: SingleJarModSource? = null
            var pathInJar: String? = null
            var destPath: String? = null

            while (true) {
                if (kind() != TokenKind.Keyword) break
                when (text()?.lowercase()) {
                    "from" -> {
                        kind = null
                        from = parseSingleJarModSource()
                    }

                    "of" -> {
                        kind = null
                        pathInJar = getKeywordOrQuotedAndMove()
                    }

                    "into" -> {
                        kind = null
                        destPath = getKeywordOrQuotedAndMove()
                    }

                    else -> break
                }
            }

            if (from == null) error("expected 'from'")
            if (destPath == null) error("expected 'into'")
            if (pathInJar == null) pathInJar = ""
            if (pathInJar.isNotEmpty() && !pathInJar.endsWith("/")) {
                pathInJar = "$pathInJar/"
            }

            return ZipModSource(from, pathInJar, destPath)
        }

        @Suppress("NAME_SHADOWING")
        private fun parseSingleJarModSource(kind: String? = null): SingleJarModSource =
            when (val kind = kind ?: getKeywordAndMove().lowercase()) {
                "curse" -> parseCurseModSource()
                "url" -> parseUrlModSource()
                "optifine" -> parseOptifineModSource()
                "drive" -> parseGoogleDriveModSource()
                else -> error("unexpected mod source kind: '$kind'")
            }

        private fun parseCurseModSource(): CurseMod {
            val slug = getKeywordOrQuotedAndMove()
            var fileName: String? = null

            while (true) {
                if (kind() != TokenKind.Keyword) break
                when (text()?.lowercase()) {
                    "filename" -> {
                        kind = null
                        fileName = getKeywordOrQuotedAndMove()
                    }

                    else -> break
                }
            }

            return CurseMod(slug, fileName)
        }

        private fun parseUrlModSource(): URLPattern = URLPattern(getKeywordOrQuotedAndMove())

        private fun parseOptifineModSource(): Optifine = Optifine

        private fun parseGoogleDriveModSource(): GoogleDrive = GoogleDrive(getKeywordOrQuotedAndMove())

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

    sealed class ModSource {
        abstract suspend fun doDownload(
            client: HttpClient,
            info: ModInfo,
            logger: Logger,
            callback: suspend (String, ByteReadChannel) -> Unit
        )
    }

    data class ZipModSource(
        val source: SingleJarModSource,
        val pathInJar: String,
        val destPath: String,
    ) : ModSource() {
        override suspend fun doDownload(
            client: HttpClient,
            info: ModInfo,
            logger: Logger,
            callback: suspend (String, ByteReadChannel) -> Unit
        ) {
            val zipFile = source.doDownloadSingleJar(client, info, logger) { _, content -> content.toByteArray() }
            ZipInputStream(ByteArrayInputStream(zipFile)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    if (!entry.isDirectory) {
                        var normalized = normalizePath(entry.name)
                        if (pathInJar.isNotEmpty() && normalized.startsWith(pathInJar)) {
                            normalized = normalized.substring(pathInJar.length)
                        }
                        callback(Path(destPath).resolve(normalized).toString(), ByteReadChannel(zis.readBytes()))
                    }
                }
            }
        }

        private fun normalizePath(name: String): String = buildString {
            for (cur in name.splitToSequence('/')) {
                when (cur) {
                    ".." -> {
                        // skip '/../'
                        // find last slash. 'something/' -> ''
                        val lastSlash = this.lastIndexOf('/', lastIndex - 1)
                        setLength(lastSlash + 1)
                    }

                    ".", "" -> {
                        // skip '/./' or '//'
                    }

                    else -> append(cur).append('/')
                }
            }
            // remove last slash
            setLength(length - 1)
        }
    }

    sealed class SingleJarModSource : ModSource() {
        override suspend fun doDownload(
            client: HttpClient,
            info: ModInfo,
            logger: Logger,
            callback: suspend (String, ByteReadChannel) -> Unit
        ) {
            doDownloadSingleJar(client, info, logger, callback)
        }

        abstract suspend fun <T> doDownloadSingleJar(
            client: HttpClient,
            info: ModInfo,
            logger: Logger,
            callback: suspend (String, ByteReadChannel) -> T
        ): T
    }

    data class CurseMod(val slug: String, val fileName: String?) : SingleJarModSource() {
        override suspend fun <T> doDownloadSingleJar(
            client: HttpClient,
            info: ModInfo,
            logger: Logger,
            callback: suspend (String, ByteReadChannel) -> T
        ): T {
            val cfWidgetRequest = request {
                url {
                    takeFrom("https://api.cfwidget.com/")
                    path("minecraft", "mc-mods", slug)
                }
            }
            val projectInfo = run {
                while (true) {
                    logger.log("fetching project id and file info from cfwidget for ${info.id}")
                    val resJson = client.get(cfWidgetRequest).bodyAsText()
                    val res = Json.decodeFromString(CFWidgetResponse.serializer(), resJson)
                    if (res.id != null) return@run res
                    if (res.error != "in_queue")
                        throw IOException("unknown response from cfwidget: ${res.error}")
                    delay(10.seconds.inWholeMilliseconds)
                }
                @Suppress("UNREACHABLE_CODE") // compiler bug
                error("unreachable")
            }

            logger.log("fetching download url for ${info.id}")

            val fileName = projectInfo.files.firstOrNull { it.id.toString() == info.versionId }?.name
                ?: fileName
                ?: throw UserError(
                    "version ${info.versionId} not found in $slug (${projectInfo.id})\n" +
                            "Please tell server administer to update mods config filename."
                )

            val versionId = info.versionId
            val jarURL = "https://edge.forgecdn.net/files/" +
                    versionId.chunked(4).joinToString("/") + "/" +
                    fileName.encodeURLPath()

            return client.prepareGet(jarURL).execute { response ->
                if (!response.status.isSuccess())
                    throw UserError("$jarURL returns error code: ${response.status}")

                callback(getFileName(Url(jarURL), response), response.bodyAsChannel())
            }
        }
    }

    data class GoogleDrive(val id: String) : SingleJarModSource() {
        companion object {
            val href = "href=\"(/uc\\?export=download[^\"]+)".toRegex()
            val downloadForm = "id=\"downloadForm\" action=\"(.+?)\"".toRegex()
            val downloadUrl = "\"downloadUrl\":\"([^\"]+)".toRegex()
        }

        private fun findPublicURL(receive: String): String {
            receive.lineSequence().forEach { line ->
                href.find(line)?.let { match ->
                    return "https://docs.google.com" + match.groups[1]!!.value.replace("&amp;", "&")
                }
                downloadForm.find(line)?.let { match ->
                    return match.groups[1]!!.value.replace("&amp;", "&")
                }
                downloadUrl.find(line)?.let { match ->
                    return match.groups[1]!!.value.replace("\\u003d", "=").replace("\\u0026", "&")
                }
            }
            throw UserError("cannot find download url for google drive id=$id")
        }

        override suspend fun <T> doDownloadSingleJar(
            client: HttpClient,
            info: ModInfo,
            logger: Logger,
            callback: suspend (String, ByteReadChannel) -> T
        ): T {
            val cookieStorage = AcceptAllCookiesStorage()
            var url: Url?
            url = Url("https://drive.google.com/uc?export=download&id=$id")
            while (true) {
                val result = client.prepareGet(url!!) {
                    val cookies = cookieStorage.get(url!!)
                    if (cookies.isNotEmpty()) {
                        headers[HttpHeaders.Cookie] = cookies.joinToString(";", transform = ::renderCookieHeader)
                    } else {
                        headers.remove(HttpHeaders.Cookie)
                    }
                }.execute { response ->
                    val contentType =
                        runCatching { response.headers[HttpHeaders.ContentType]?.let(ContentType::parse) }.getOrNull()
                    if (contentType?.contentSubtype?.contains("html") != true) {
                        url = null
                        return@execute callback(getFileName(response.request.url, response), response.bodyAsChannel())
                    } else {
                        val firstUrl = response.request.url
                        response.setCookie().forEach {
                            cookieStorage.addCookie(firstUrl, it)
                        }
                        url = Url(findPublicURL(response.body()))
                        null
                    }
                }
                @Suppress("UNCHECKED_CAST")
                if (url == null)
                    return result as T
            }
        }
    }

    data class URLPattern(val urlPattern: String) : SingleJarModSource() {
        override suspend fun <T> doDownloadSingleJar(
            client: HttpClient,
            info: ModInfo,
            logger: Logger,
            callback: suspend (String, ByteReadChannel) -> T
        ): T {
            val url = urlPattern.replace("\$version", info.versionId)
            logger.log("fetching download url for ${info.id}: $url")
            val jarURL = Url(url)
            return client.prepareGet(jarURL).execute { response ->
                if (!response.status.isSuccess())
                    throw UserError("$jarURL returns error code: ${response.status}")

                val fileName = getFileName(jarURL, response)
                callback(fileName, response.bodyAsChannel())
            }
        }
    }

    @Suppress("SpellCheckingInspection")
    object Optifine : SingleJarModSource() {
        override suspend fun <T> doDownloadSingleJar(
            client: HttpClient,
            info: ModInfo,
            logger: Logger,
            callback: suspend (String, ByteReadChannel) -> T
        ): T {
            val adloadx = URLBuilder("https://optifine.net/adloadx")
                .apply { parameters["f"] = "OptiFine_${info.versionId}.jar" }
                .build()
            logger.log("fetching download url for optifine ${info.versionId}")
            val resHTML = client.get(adloadx).bodyAsText()

            val aLine = resHTML.lineSequence()
                .dropWhile { !it.contains("class=\"downloadButton\"") }
                .drop(1)
                .dropWhile { !it.contains("<a") }
                .firstOrNull()
                ?: throw IOException("unknown response from optifine.net: no download button: $resHTML")

            val regex = "<a [^>]*href=['\"]([^'\"]*)['\"]".toRegex()
            val match = regex.find(aLine)
                ?: throw IOException("unknown response from optifine.net: no a tag: $aLine")
            val href = match.groups[1]!!.value
            logger.log("href: $href")
            val jarURL = URLBuilder(adloadx).takeFrom(href).build()
            logger.log("downloading optifine: $jarURL")

            return client.prepareGet(jarURL).execute { response ->
                if (!response.status.isSuccess())
                    throw UserError("$jarURL returns error code: ${response.status}")

                val fileName = getFileName(jarURL, response)
                val content = response.bodyAsChannel()
                val result = ByteChannel(false)

                // check jar header.
                val bytes = ByteArray(4)
                content.readFully(bytes)
                if (!bytes.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                    //runCatching { jarLocation.deleteExisting() }
                    throw IOException("invalid response")
                }

                coroutineScope {
                    async {
                        try {
                            result.writeFully(bytes)
                        } catch (t: Throwable) {
                            result.close(t)
                            throw t
                        }
                        content.copyTo(result)
                        result.close()
                    }.job
                    callback(fileName, result)
                }
            }
        }
    }
}
