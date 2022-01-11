package com.anatawa12.downloader

import java.net.MalformedURLException
import java.net.URL
import java.util.*

class EmbedConfiguration(
    val name: String,
    val location: ModsFileLocation,
) {
    companion object {
        fun load(configLocation: String = "config.properties"): EmbedConfiguration? = try {
            EmbedConfiguration::class.java.classLoader.getResourceAsStream(configLocation)?.use { stream ->
                val props = Properties().apply { load(stream.reader()) }
                val name = props.getProperty("name") ?: throw UserError("name is missing in config file")
                val location = props.getProperty("location") ?: throw UserError("location is missing in config file")
                EmbedConfiguration(name, parseModsFileLocation(location))
            }
        } catch (e: IllegalArgumentException) {
            throw UserError("invalid $configLocation: ${e.message}", e)
        }

        @Suppress("HttpUrlsUsage")
        fun parseModsFileLocation(string: String): ModsFileLocation = try {
            if (string.startsWith("https://") || string.startsWith("http://")) {
                ModsFileLocation.GlobalURL(URL(string))
            } else {
                val url = EmbedConfiguration::class.java.classLoader.getResource(string)
                    ?: throw UserError("invalid location: not found file in jar: $string")
                ModsFileLocation.InJar(string, url)
            }
        } catch (e: MalformedURLException) {
            throw UserError("invalid location URL: $string", e)
        }
    }
}
