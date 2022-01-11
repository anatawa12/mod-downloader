@file:JvmName("Main")

package com.anatawa12.downloader

import java.awt.GraphicsEnvironment
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty() && !GraphicsEnvironment.isHeadless()) startGui()
    else runCui(args)
}

private fun error(error: String): Nothing {
    System.err.println(error)
    exitProcess(-1)
}

fun runCui(args: Array<String>) {
    if (args.isNotEmpty()) when (args[0]) {
        "--gui" -> return startGui()
        "--help" -> printHelpAndExit(runCatching { EmbedConfiguration.load() }.getOrNull())
        "--version" -> printVersion(runCatching { EmbedConfiguration.load() }.getOrNull())
        "--licenses" -> printLicenseInfoAndExit()
        "--new-embed" -> return createEmbed(args.copyOfRange(1, args.size))
    }

    val embedConfig = EmbedConfiguration.load()

    var force = false
    var clean = false
    var config: String? = null
    var dest: String? = null
    var i = 0
    while (i in args.indices) {
        val opt = args[i]
        if (opt.getOrNull(0) != '-') error("unexpected argument: $opt")
        if (opt.getOrNull(1) == '-') when (opt) {
            "--force" -> force = true
            "--clean" -> clean = true
            "--config" -> config = args.getOrElse(++i) { error("argument required for --config") }
            "--dest" -> dest = args.getOrElse(++i) { error("argument required for --dest") }
            "--help" -> printHelpAndExit(embedConfig)
            "--version" -> printVersionAndExit(embedConfig)
            "--licenses" -> printLicenseInfoAndExit()
            else -> error("unknown option: $opt")
        } else {
            for (c in opt.substring(1)) {
                when (c) {
                    'f' -> force = true
                    'l' -> clean = true
                    'c' -> config = args.getOrElse(++i) { error("argument required for -c") }
                    'd' -> dest = args.getOrElse(++i) { error("argument required for -d") }
                    'h' -> printHelpAndExit(embedConfig)
                    'V' -> printVersionAndExit(embedConfig)
                    else -> error("unknown option: $opt")
                }
            }
        }
        i++
    }
    if (config != null && embedConfig != null)
        error("option -c or --config is not valid for config-embed mod-downloader")

    doDownload(
        config = embedConfig?.location ?: config?.let(::File)?.let(ModsFileLocation::FileSystem) ?:
            error("option -c or --config is required"),
        downloadTo = dest?.let(::File) ?: error("option -d or --dest is required"),
        mode = if (clean) {
            if (force) DownloadMode.CLEAN_DOWNLOAD_FORCE
            else DownloadMode.CLEAN_DOWNLOAD
        } else DownloadMode.DOWNLOAD,
        logger = System.err::println
    )
}

private fun printHelpAndExit(embedConfig: EmbedConfiguration?): Nothing {
    val embedEnv = embedConfig != null
    printVersion(embedConfig)
    System.err.println("")
    System.err.println("COMMANDS:")
    System.err.println("\t--gui           Launch GUI")
    if (!embedEnv) {
        System.err.println("\t--new-embed     Create config-embed mod-downloader")
        System.err.println("\t                --new-embeds --help for more information")
    }
    System.err.println("")
    System.err.println("OPTOPNS:")
    System.err.println("\t-h, --help              Prints this message")
    System.err.println("\t-V, --version           Prints version information")
    System.err.println("\t    --licenses          Prints third-party libraries license information")
    System.err.println("\t-l, --clean             Download to clean directory")
    System.err.println("\t-f, --force             With --cleam, this delete files in directory")
    if (!embedEnv) System.err.println("\t-c, --config <CONFIG>   Path to config file")
    System.err.println("\t-d, --dest <DIRECTORY>  Path to destination directory")

    exitProcess(0)
}

private fun printVersionAndExit(embedConfig: EmbedConfiguration?): Nothing {
    printVersion(embedConfig)
    exitProcess(0)
}

fun printVersion(embedConfig: EmbedConfiguration?, command: String = "") {
    val version = Constants.version
    if (embedConfig != null) {
        System.err.println("mod-downloader$command version $version for ${embedConfig.name}")
    } else {
        System.err.println("mod-downloader$command version $version")
    }
    System.err.println("Copyright (c) 2021 anatawa12 and other contributors")
    System.err.println("Published under MIT License. see https://github.com/anatawa12/mod-downloader/blob/master/LICENSE")
    System.err.println("For library information, see --licenses")
}

fun printLicenseInfoAndExit() {
    printVersion(null)
    System.err.run {
        println()
        println("THIRD-PARTY LICENSE INFORMATION")
        println("Kotlin - a jvm programming language")
        println("  Published under Apache License 2.0. see https://github.com/JetBrains/kotlin/tree/master/license")
        println("  Copyright 2010-2020 JetBrains s.r.o and respective authors and developers")
        println()
        println("Ktor - a multiplatform io library")
        println("  Published under Apache License 2.0. see https://github.com/ktorio/ktor/blob/main/LICENSE")
        println("  Copyright 2014-2020 JetBrains s.r.o and contributors")
        println()
        println("kotlinx.coroutines - a kotlin coroutines library")
        println("  Published under Apache License 2.0. see https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt")
        println("  Copyright 2016-2021 JetBrains s.r.o.")
        println()
        println("slf4j - a logging facade")
        println("  Published under MIT license. see https://www.slf4j.org/license.html")
        println("  Copyright (c) 2004-2017 QOS.ch")
    }
    exitProcess(0)
}
