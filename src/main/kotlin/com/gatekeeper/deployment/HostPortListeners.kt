package com.gatekeeper.deployment

import java.io.File

/** Linux TCP LISTEN ports from procfs, including listeners not owned by Docker. */
internal object HostPortListeners {
    fun read(files: List<File> = listOf(File("/proc/net/tcp"), File("/proc/net/tcp6"))): Set<Int> =
        files.filter(File::isFile).flatMap { file ->
            file.readLines().drop(1).mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 4 || fields[3] != "0A") return@mapNotNull null
                fields[1].substringAfterLast(':').toIntOrNull(16)?.takeIf { it in 1..65535 }
            }
        }.toSet()
}

internal fun overlaps(a: IntRange, b: IntRange): Boolean = a.first <= b.last && b.first <= a.last
