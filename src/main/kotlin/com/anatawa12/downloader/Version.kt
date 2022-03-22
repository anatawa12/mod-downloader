package com.anatawa12.downloader

data class Version(
    val major: UByte,
    val minor: UByte = 0u,
    val patch: UByte = 0u,
    val snapshot: Boolean = false,
) : Comparable<Version> {
    override fun toString(): String = if (snapshot) "$major.$minor.$patch-SNAPSHOT" else "$major.$minor.$patch"

    override fun compareTo(other: Version): Int {
        var r: Int = major.compareTo(other.major)
        if (r != 0) return r
        r = minor.compareTo(other.minor)
        if (r != 0) return r
        r = patch.compareTo(other.patch)
        if (r != 0) return r
        if (snapshot) {
            if (!other.snapshot)
                // SNAPSHOT < stable
                return -1 // < 0
        } else {
            if (other.snapshot)
                // stable > SNAPSHOT
                return 1 // > 0
        }
        return 0
    }

    companion object {
        val current = parse(Constants.version)

        fun parse(textIn: String): Version {
            try {
                val text: String
                val snapshot: Boolean
                if (textIn.endsWith("-SNAPSHOT")) {
                    text = textIn.substring(0, textIn.length - "-SNAPSHOT".length)
                    snapshot = true
                } else {
                    text = textIn
                    snapshot = false
                }
                val firstDot = text.indexOf('.')
                if (firstDot == -1) return Version(text.toUByte(), snapshot = snapshot)
                val major = text.substring(0, firstDot).toUByte()
                val secondDot = text.indexOf('.', firstDot + 1)
                if (secondDot == -1) return Version(major, text.substring(firstDot + 1).toUByte(), snapshot = snapshot)
                val minor = text.substring(firstDot + 1, secondDot).toUByte()
                return Version(major, minor, text.substring(secondDot + 1).toUByte(), snapshot = snapshot)
            } catch (ignored: NumberFormatException) {
                throw IllegalArgumentException("invalid version name: $textIn")
            }
        }
    }
}
