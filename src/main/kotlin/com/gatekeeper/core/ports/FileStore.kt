package com.gatekeeper.core.ports

import java.nio.file.Path

interface FileStore {
    fun exists(path: Path): Boolean
    fun read(path: Path): String
    fun write(path: Path, content: String)
    fun delete(path: Path)
}
