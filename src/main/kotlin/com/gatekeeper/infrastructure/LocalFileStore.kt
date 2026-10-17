package com.gatekeeper.infrastructure

import com.gatekeeper.core.ports.FileStore
import java.nio.file.Files
import java.nio.file.Path

class LocalFileStore : FileStore {
    override fun exists(path: Path): Boolean = Files.exists(path)
    override fun read(path: Path): String = Files.readString(path)
    override fun write(path: Path, content: String) {
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, content)
    }
    override fun delete(path: Path) {
        Files.deleteIfExists(path)
    }
}
