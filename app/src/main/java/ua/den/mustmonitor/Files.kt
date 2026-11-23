package ua.den.mustmonitor

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Writes via a temp file + atomic rename, so a crash never leaves a half-written file. */
fun File.writeAtomically(write: (java.io.Writer) -> Unit) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.bufferedWriter().use(write)
    try {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: Exception) {
        Files.move(tmp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
