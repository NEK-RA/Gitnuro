package app.git

import app.extensions.systemSeparator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.IOException
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.attribute.BasicFileAttributes
import javax.inject.Inject

class FileChangesWatcher @Inject constructor() {

    private val _changesNotifier = MutableSharedFlow<Boolean>()
    val changesNotifier: SharedFlow<Boolean> = _changesNotifier
    val keys = mutableMapOf<WatchKey, Path>()

    suspend fun watchDirectoryPath(pathStr: String, ignoredDirsPath: List<String>) = withContext(Dispatchers.IO) {
        println(ignoredDirsPath)
        val watchService = FileSystems.getDefault().newWatchService()

        val path = Paths.get(pathStr)

        path.register(
            watchService,
            ENTRY_CREATE,
            ENTRY_DELETE,
            ENTRY_MODIFY
        )

        // register directory and sub-directories but ignore dirs by gitignore
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            @Throws(IOException::class)
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val isIgnoredDirectory = ignoredDirsPath.any { "$pathStr/$it" == dir.toString() }

                return if (!isIgnoredDirectory) {
                    val watchKey = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
                    keys[watchKey] = dir
                    FileVisitResult.CONTINUE
                } else {
                    FileVisitResult.SKIP_SUBTREE
                }
            }
        })

        var key: WatchKey
        while (watchService.take().also { key = it } != null) {
            key.pollEvents()

            println("Polled events on dir ${keys[key]}")

            val dir = keys[key] ?: return@withContext

            val hasGitDirectoryChanged = dir.startsWith("$pathStr$systemSeparator.git$systemSeparator")

            println("Has git dir changed: $hasGitDirectoryChanged")

            _changesNotifier.emit(hasGitDirectoryChanged)

            key.reset()
        }
    }
}