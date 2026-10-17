package com.gatekeeper.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchitectureRulesTest {
    private val sourceRoot = Path.of("src/main/kotlin/com/gatekeeper")
    private val ratchetPath = Path.of("docs/architecture-ratchet-allowlist.txt")

    @Test
    fun `domain source files do not import framework or infrastructure packages`() {
        val forbiddenPrefixes = listOf(
            "io.ktor.", "org.jetbrains.exposed.", "com.github.dockerjava.",
            "com.gatekeeper.db.", "com.gatekeeper.payments.", "com.gatekeeper.mpesa.", "com.gatekeeper.paystack.",
            "com.gatekeeper.feature.payment.data.", "com.gatekeeper.feature.payment.presentation."
        )
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

    @Test
    fun `production sources have packages and no test imports`() {
        val violations = mutableListOf<String>()
        productionFiles().forEach { file ->
            val lines = Files.readAllLines(file)
            if (lines.none { it.trimStart().startsWith("package ") }) {
                violations += "$file:1: missing package declaration"
            }
            lines.forEachIndexed { index, line ->
                val imported = line.trim().removePrefix("import ").substringBefore(" as ")
                if (line.trim().startsWith("import ") &&
                    (imported.startsWith("src.test.") || imported.startsWith("com.gatekeeper.test."))
                ) {
                    violations += "$file:${index + 1}: $imported"
                }
            }
        }
        assertTrue(violations.isEmpty(), "Production source violations:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `docker construction violations are ratcheted`() {
        val allowlisted = Files.readAllLines(ratchetPath)
            .asSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { it.split('|', limit = 2) }
            .filter { it.size == 2 }
            .groupBy({ it[0] }, { it[1] })
        val current = productionFiles()
            .filterNot {
                it.fileName.toString() == "DockerService.kt" ||
                    it.fileName.toString() == "DependencyInjection.kt"
            }
            .filter { Files.readString(it).contains("DockerService(") }
            .map { it.toString() }
            .toSet()
        val allowed = allowlisted["docker-construction"].orEmpty().toSet()
        assertEquals(allowed, current, "Docker construction allowlist must exactly match current violations")
    }

    private fun productionFiles(): List<Path> =
        Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.toList()
        }

    @Test
    fun `payment presentation does not import persistence or provider adapters`() {
        val presentationRoot = Path.of("src/main/kotlin/com/gatekeeper/feature/payment/presentation")
        val forbiddenPrefixes = listOf(
            "org.jetbrains.exposed.", "com.gatekeeper.db.repositories.",
            "com.gatekeeper.feature.payment.data.", "com.gatekeeper.paystack.",
            "com.gatekeeper.mpesa.", "com.gatekeeper.payments."
        )
        val violations = mutableListOf<String>()

        Files.walk(presentationRoot).use { paths ->
            paths.toList().asSequence()
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .forEach { file ->
                    Files.readAllLines(file).forEachIndexed { index, line ->
                        val trimmed = line.trim()
                        if (!trimmed.startsWith("import ")) return@forEachIndexed
                        val imported = trimmed.removePrefix("import ").substringBefore(" as ")
                        if (forbiddenPrefixes.any(imported::startsWith)) violations += "$file:${index + 1}: $imported"
                    }
                }
        }

        assertTrue(violations.isEmpty(), "Forbidden payment presentation imports:\n${violations.joinToString("\n")}")
    }
}
