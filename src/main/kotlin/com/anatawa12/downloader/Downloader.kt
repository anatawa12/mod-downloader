package com.anatawa12.downloader

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URL
import java.nio.charset.Charset
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.*
import kotlin.time.Duration.Companion.seconds

class DownloadParameters(
    val downloadTo: File,
    val mode: DownloadMode,
    val force: Boolean,
    val logger: Logger,
    val optionalModsList: Set<String>,
    val downloadFor: ModsConfig.ModSide,
)

suspend fun doDownload(config: ModsFileLocation, params: DownloadParameters) =
    doDownloadImpl(config, params, ::loadModsConfig)

suspend fun doDownload(config: ModsConfig, params: DownloadParameters) =
    doDownloadImpl(config, params) { it }

val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private const val DOWNLOADED_TXT = "downloaded.txt"

private fun checkDownloadToDir(
    downloadTo: Path,
    mode: DownloadMode,
    force: Boolean,
    logger: Logger,
): List<DownloadedMod> {
    downloadTo.createDirectories()
    if (!downloadTo.isDirectory()) throw UserError("$downloadTo is not directory. may be a file")

    val downloadedList: List<DownloadedMod>

    when (mode) {
        DownloadMode.DOWNLOAD -> {
            // nothing to do as prepare
            val downloadedListStr = runCatching { downloadTo.resolve(DOWNLOADED_TXT).readText() }.getOrNull()
            downloadedList = if (downloadedListStr == null) emptyList() else {
                val parsed = DownloadedMod.parse(downloadedListStr)
                parsed.filter {
                    val path = downloadTo.resolve(it.fileName)
                    if (path.exists()) {
                        true
                    } else {
                        logger.log("WARNING: ${it.fileName}(${it.id} version ${it.versionId}) not found in directory.")
                        false
                    }
                }
            }
        }
        DownloadMode.CLEAN_DOWNLOAD -> {
            val entries = downloadTo.listDirectoryEntries()
            if (force) {
                downloadTo.listDirectoryEntries().forEach { entry ->
                    Files.walkFileTree(entry, object : SimpleFileVisitor<Path>() {
                        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                            exc?.let { throw it }
                            dir.deleteExisting()
                            return FileVisitResult.CONTINUE
                        }

                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            file.deleteExisting()
                            return FileVisitResult.CONTINUE
                        }
                    })
                }
            } else {
                if (entries.isEmpty())
                    throw UserError("$downloadTo is not empty. to remove files in the directory, run with --force")
            }
            downloadedList = emptyList()
        }
    }

    logger.log("downloaded mods:")
    for (downloadedMod in downloadedList)
        logger.log("  $downloadedMod")

    return downloadedList
}

suspend fun loadModsConfig(config: ModsFileLocation): ModsConfig {
    return when (config) {
        is ModsFileLocation.FileSystem -> {
            try {
                ModsConfig.Parser(config.path.name,
                    withContext(Dispatchers.IO) { config.path.readText() })
                    .parseModsConfig()
            } catch (e: FileNotFoundException) {
                throw UserError("config file not found: ${config.path}")
            }
        }
        is ModsFileLocation.InJar,
        is ModsFileLocation.GlobalURL,
        -> {
            try {
                ModsConfig.Parser(config.url.path.substringAfterLast('/'),
                    withContext(Dispatchers.IO) { config.url.openStream().bufferedReader().use { it.readText() } }
                ).parseModsConfig()
            } catch (e: FileNotFoundException) {
                throw UserError("config file not found: ${config.url}")
            }
        }
    }
}

private fun deleteAll(removed: List<DownloadedMod>, downloadTo: Path) {
    if (removed.isNotEmpty()) {
        for (downloadedMod in removed) {
            downloadTo.resolve(downloadedMod.fileName).deleteIfExists()
        }
    }
}

private suspend fun download(
    updated: List<ModsConfig.ModInfo>,
    downloadTo: Path,
    force: Boolean,
    logger: Logger,
): List<DownloadedMod> {
    if (updated.isEmpty()) return emptyList()

    return coroutineScope {
        val completeCount = AtomicInteger(0)
        val client = HttpClient(CIO) {
            engine {
                requestTimeout = 1000 * 60
            }
        }
        val updatedCount = updated.size
        updated.map { info ->
            async {
                try {
                    logger.log("downloading ${info.id} version ${info.versionName ?: info.versionId}")
                    downloadMod(client, info, downloadTo, force, logger)
                } finally {
                    logger.log("download complete: ${completeCount.incrementAndGet()} / $updatedCount: " +
                            "${info.id} version ${info.versionName ?: info.versionId}")
                }
            }
        }.awaitAll()
    }
}

private suspend fun <A> doDownloadImpl(
    config: A,
    params: DownloadParameters,
    load: suspend (A) -> ModsConfig,
) {
    val downloadTo = params.downloadTo.toPath()
    val mode = params.mode
    val logger = params.logger
    logger.log("config: $config")
    logger.log("downloadTo: $downloadTo")
    logger.log("mode: $mode")

    val downloadedList = checkDownloadToDir(downloadTo, mode, params.force, logger)

    val modsConfig = load(config)

    val (updated, removed, keep) = computeModDiff(
        filterMods(modsConfig.list, params.optionalModsList, params.downloadFor), 
        downloadedList,
    )

    logger.log("mods to be downloaded: ${updated.size} mod(s)")
    logger.log("mods to be removed: ${removed.size} mod(s)")
    logger.log("mods to be keep: ${keep.size} mod(s)")

    deleteAll(removed, downloadTo)

    val downloadedUpdated = download(updated, downloadTo, params.force, logger)

    downloadTo.resolve(DOWNLOADED_TXT).toFile().writeChannel().use {
        writeFully(DownloadedMod.write(downloadedUpdated + keep).toByteArray())
    }
}

@Serializable
data class CFWidgetResponse(val id: Int? = null, val error: String? = null)

suspend fun downloadMod(
    client: HttpClient,
    info: ModsConfig.ModInfo,
    downloadTo: Path,
    force: Boolean,
    logger: Logger,
): DownloadedMod {
    val body: ByteReadChannel
    val fileName: String
    when (val source = info.source) {
        is ModsConfig.CurseMod -> {
            val cfWidgetURL = URLBuilder("https://api.cfwidget.com/")
                .path("minecraft", "mc-mods", source.slug)
                .build()
            val projectId = run {
                while (true) {
                    logger.log("fetching project id and file info from cfwidget for ${info.id}")
                    val resJson = client.get<String>(cfWidgetURL)
                    val res = Json.decodeFromString(CFWidgetResponse.serializer(), resJson)
                    if (res.id != null) return@run res.id
                    if (res.error != "in_queue")
                        throw IOException("unknown response from cfwidget: ${res.error}")
                    delay(10.seconds.inWholeMilliseconds)
                }
                @Suppress("UNREACHABLE_CODE") // compiler bug
                error("unreachable")
            }
            logger.log("fetching download url for ${info.id}")
            val downloadURL = URLBuilder("https://addons-ecs.forgesvc.net/")
                .path("api", "v2", "addon", projectId.toString(), "file", info.versionId, "download-url")
                .build()
            val jarURL = client.get<ByteArray>(downloadURL).toString(Charsets.UTF_8).let(::Url)
            val jar = client.get<ByteReadChannel>(jarURL)
            fileName = jarURL.encodedPath.substringAfterLast('/').decodeURLPart()
            body = jar
        }
        is ModsConfig.URLPattern -> {
            val url = source.urlPattern.replace("\$version", info.versionId)
            val response = client.get<HttpResponse>(url)
            val disposition = runCatching {
                response.headers[HttpHeaders.ContentDisposition]?.let(ContentDisposition::parse)
            }.getOrNull()
            if (!response.status.isSuccess())
                throw UserError("$url returns error code: ${response.status}")
            fileName = disposition?.parameter(ContentDisposition.Parameters.FileNameAsterisk)?.let(::parseRFC5987)
                ?: disposition?.parameter(ContentDisposition.Parameters.FileName)
                        ?: url.substringAfterLast('/')
            body = response.content
        }
    }
    val jarLocation = downloadTo.resolve(fileName)

    if (force) jarLocation.deleteIfExists()
    try {
        jarLocation.createFile()
    } catch (e: FileAlreadyExistsException) {
        throw UserError("$fileName already exists", e)
    }

    jarLocation.toFile().writeChannel().use { body.copyTo(this) }

    return DownloadedMod(info.id, info.versionId, fileName)
}

private fun filterMods(
    mods: List<ModsConfig.ModInfo>,
    optionalModsList: Set<String>,
    downloadFor: ModsConfig.ModSide,
): List<ModsConfig.ModInfo> {
    val optionalMods = optionalModsList.toMutableSet()
    val filtered = mods.filter { mod ->
        (!mod.optional || optionalMods.remove(mod.id)) && (mod.modSide == null || mod.modSide == downloadFor)
    }
    if (optionalMods.isNotEmpty())
        throw UserError("some optional mod not found: $optionalMods")
    return filtered
}

private fun computeModDiff(
    mods: List<ModsConfig.ModInfo>,
    downloadedMods: List<DownloadedMod>,
): Triple<List<ModsConfig.ModInfo>, List<DownloadedMod>, List<DownloadedMod>> {
    if (downloadedMods.isEmpty())
        return Triple(mods, emptyList(), emptyList())

    data class ModInfoPair(val modInfo: ModsConfig.ModInfo? = null, var downloadedMod: DownloadedMod? = null)
    val modMap = mutableMapOf<Pair<String, String>, ModInfoPair>()

    for (modInfo in mods) {
        modMap[modInfo.id to modInfo.versionId] = ModInfoPair(modInfo)
    }

    for (mod in downloadedMods) {
        modMap.getOrPut(mod.id to mod.versionId, ::ModInfoPair).downloadedMod = mod
    }

    val updated = mutableListOf<ModsConfig.ModInfo>()
    val removed = mutableListOf<DownloadedMod>()
    val keep = mutableListOf<DownloadedMod>()

    for ((info, downloaded) in modMap.values) {
        if (info == null) {
            if (downloaded != null)
                removed.add(downloaded)
        } else {
            if (downloaded == null) {
                updated.add(info)
            } else {
                keep.add(downloaded)
            }
        }
    }
    return Triple(updated, removed, keep)
}

private fun parseRFC5987(text: String): String? = runCatching {
    val pair = text.split('\'', limit = 3)
    if (pair.size < 3) return null
    return pair[1].decodeURLPart(charset = Charset.forName(pair[0]))
}.getOrNull()

fun interface Logger {
    fun log(message: String)
}

enum class DownloadMode {
    DOWNLOAD,
    CLEAN_DOWNLOAD,
}

sealed class ModsFileLocation {
    abstract val url: URL
    data class InJar(val path: String, override val url: URL) : ModsFileLocation()
    data class GlobalURL(override val url: URL) : ModsFileLocation()
    data class FileSystem(val path: File) : ModsFileLocation() {
        override val url: URL get() = path.toURI().toURL()
    }
}
