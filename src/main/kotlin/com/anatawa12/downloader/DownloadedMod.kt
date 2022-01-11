package com.anatawa12.downloader

data class DownloadedMod(val id: String, val versionId: String, val fileName: String) {
    companion object {
        fun parse(text: String): List<DownloadedMod> = buildList {
            val seq = text.lineSequence().iterator()

            // skip until "START"
            while (seq.hasNext() && seq.next() != "BEGIN") {
                // nop
            }

            for (line in seq) {
                val elements = line.split('"')
                if (elements.size >= 3) {
                    add(DownloadedMod(elements[0], elements[1], elements[2]))
                }
            }
        }

        fun write(downloadedMods: List<DownloadedMod>) = buildString {
            appendLine("This file is the list of mods downloaded by mod-downloader by anatawa12")
            appendLine("BEGIN")
            for (downloadedMod in downloadedMods) {
                append(downloadedMod.id)
                    .append('"').append(downloadedMod.versionId)
                    .append('"').append(downloadedMod.fileName)
                    .appendLine()
            }
        }
    }
}
