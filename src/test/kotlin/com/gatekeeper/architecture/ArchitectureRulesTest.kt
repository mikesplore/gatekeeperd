package com.gatekeeper.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureRulesTest {
    @Test
    fun `domain source files do not import framework or infrastructure packages`() {
        val sourceRoot = Path.of("src/main/kotlin/com/gatekeeper")
        val forbiddenPrefixes = listOf("io.ktor.", "org.jetbrains.exposed.", "com.github.dockerjava.")
        val legacyAllowList = emptySet<String>() // New feature domain packages start clean.
        val violations = mutableListOf<String>()

        Files.walk(sourceRoot).use { paths ->
            paths.toList().asSequence()
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it.toString().contains("/domain/") }
                .filterNot { sourceRoot.relativize(it).toString() in legacyAllowList }
                .forEach { file ->
                    Files.readAllLines(file).forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("import ")) return@forEachIndexed
                        val imported = trimmed.removePrefix("import ").substringBefore(" as ")
                        if (forbiddenPrefixes.any(imported::startsWith)) {
                            violations += "$file:${index + 1}: $imported"
                        }
                    }
                }
        }

        assertTrue(violations.isEmpty(), "Forbidden domain imports:\n${violations.joinToString("\n")}")
    }
}
