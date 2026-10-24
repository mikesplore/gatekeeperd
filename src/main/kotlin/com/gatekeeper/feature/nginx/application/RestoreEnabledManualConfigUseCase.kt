package com.gatekeeper.feature.nginx.application

import java.io.File
import java.nio.file.Files

class RestoreEnabledManualConfigUseCase(
    private val available: File,
    private val enabled: File,
    private val wasSymlink: Boolean,
    private val linkTarget: java.nio.file.Path?
) {
    fun execute(): Unit = restoreEnabledManualConfig(
        available = available,
        enabled = enabled,
        wasSymlink = wasSymlink,
        linkTarget = linkTarget
    )

    fun restoreEnabledManualConfig(
        available: File,
        enabled: File,
        wasSymlink: Boolean,
        linkTarget: java.nio.file.Path?
    ) {
        if (wasSymlink && linkTarget != null) Files.createSymbolicLink(enabled.toPath(), linkTarget)
        else if (available.isFile) Files.copy(available.toPath(), enabled.toPath())
    }
}
