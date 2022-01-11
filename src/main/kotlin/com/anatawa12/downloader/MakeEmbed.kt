@file:JvmName("MakeEmbed")

package com.anatawa12.downloader

import io.ktor.http.*
import java.lang.Exception
import java.net.URL
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.*
import kotlin.system.exitProcess

fun main(args: Array<String>) = createEmbed(args)

private fun error(error: String): Nothing {
    System.err.println("ERROR: $error")
    exitProcess(-1)
}

fun createEmbed(args: Array<String>) {
    if (runCatching { EmbedConfiguration.load() }.getOrNull() != null)
        error("--new-embed is not supported for config-embed downloader")

    val jar = findJarPath()

    var config: String? = null
    var url: String? = null
    var name: String? = null
    var dest: String? = null
    var i = 0
    while (i in args.indices) {
        val opt = args[i]
        if (opt.getOrNull(0) != '-') error("unexpected argument: $opt")
        if (opt.getOrNull(1) == '-') when (opt) {
            "--config" -> config = args.getOrElse(++i) { error("argument required for --config") }
            "--url" -> url = args.getOrElse(++i) { error("argument required for --url") }
            "--name" -> name = args.getOrElse(++i) { error("argument required for --name") }
            "--dest" -> dest = args.getOrElse(++i) { error("argument required for --dest") }
            "--help" -> printHelpAndExit()
            else -> error("unknown option: $opt")
        } else {
            for (c in opt.substring(1)) {
                when (c) {
                    'c' -> config = args.getOrElse(++i) { error("argument required for -c") }
                    'u' -> url = args.getOrElse(++i) { error("argument required for -u") }
                    'n' -> name = args.getOrElse(++i) { error("argument required for -n") }
                    'd' -> dest = args.getOrElse(++i) { error("argument required for -d") }
                    'h' -> printHelpAndExit()
                    else -> error("unknown option: $opt")
                }
            }
        }
        i++
    }
    if (config == null && url == null)
        error("--config or --url is required")
    if (config != null && url != null)
        error("You can't set both --config and --url")
    if (name == null)
        error("--name is required")
    if (dest == null)
        error("--dest is required")
    @Suppress("HttpUrlsUsage")
    if (url != null) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) error("invalid url scheme. use http or https")
        try {
            Url(url)
        } catch (e: Exception) {
            error("invalid url: ${e.message}")
        }
    }

    val destOut = if (dest == "-") System.out else Path(dest).also { it.parent.createDirectories() }.outputStream()
    val configPath = config?.let(::Path)
    val configIn = config?.let(::Path)?.inputStream()?.buffered()

    ZipInputStream(jar.openStream().buffered()).use { zipIn ->
        ZipOutputStream(destOut.buffered()).use { zipOut ->
            while (true) {
                zipOut.putNextEntry(zipIn.nextEntry ?: break)
                zipIn.copyTo(zipOut)
            }
            if (configPath != null) { 
                zipOut.putNextEntry(ZipEntry(configPath.name))
                configIn!!.copyTo(zipOut)
            }
            zipOut.putNextEntry(ZipEntry("config.properties"))
            val props = Properties()
            props.setProperty("name", name)
            if (configPath != null) {
                props.setProperty("location", configPath.name)
            } else {
                props.setProperty("location", url!!)
            }
            val writer = zipOut.bufferedWriter()
            props.store(writer, "mod-downloader config file for $name")
            writer.flush()
        }
    }

}

fun findJarPath() = try {
    val clazz = EmbedConfiguration::class.java
    val clazzFilePath = clazz.name.replace('.', '/') + ".class"
    val clazzURL = clazz.classLoader.getResource(clazzFilePath)!!.toString()
    if (!clazzURL.startsWith("jar:") || !clazzURL.endsWith("!/$clazzFilePath"))
        error("mod-downloader is not in jar (${clazzURL})")
    URL(clazzURL.substring("jar:".length, clazzURL.length - "!/$clazzFilePath".length))
} catch (e: Exception) {
    System.err.println("error getting mod-downloader jar")
    e.printStackTrace()
    exitProcess(-1)
}

private fun printHelpAndExit() {
    printVersion(null, " --new-embed")
    System.err.println("")
    System.err.println("Creates a config-embed mod-downloader.")
    System.err.println("")
    System.err.println("OPTOPNS:")
    System.err.println("\t-h, --help              Prints this message.")
    System.err.println("\t-c, --config <CONFIG>   Path to config file.")
    System.err.println("\t-u, --url <URL>         URL to config file.")
    System.err.println("\t-n, --name <NAME>       Display name. This should be name of server or mod pack.")
    System.err.println("\t-d, --dest <FILE>       Path to destination jar. '-' for stdout")
    exitProcess(0)
}
