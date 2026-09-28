import io.heapy.harmon.setup.InstalledFileAttributes
import io.heapy.harmon.setup.SetupFileSystem

class InMemorySetupFileSystem : SetupFileSystem {
    val files = mutableMapOf<String, String>()
    val directories = mutableSetOf<String>()
    val symlinks = mutableMapOf<String, String>()
    val attributes = mutableMapOf<String, InstalledFileAttributes>()
    val writtenTargets = mutableListOf<String>()
    val treeRemovalAttempts = mutableListOf<String>()
    val failedTreeRemovals = mutableSetOf<String>()

    override fun ensureDirectory(path: String, attributes: InstalledFileAttributes) {
        directories += path
        this.attributes[path] = attributes
    }

    override fun copyFileAtomically(
        source: String,
        target: String,
        attributes: InstalledFileAttributes,
        validateTemporaryFile: ((String) -> Unit)?,
        beforePublish: (() -> Unit)?,
    ) {
        val temporary = "$target.fake.tmp"
        validateTemporaryFile?.invoke(temporary)
        beforePublish?.invoke()
        files[target] = files[source] ?: "<binary:$source>"
        this.attributes[target] = attributes
        writtenTargets += target
    }

    override fun writeTextAtomically(
        target: String,
        content: String,
        attributes: InstalledFileAttributes,
        validateTemporaryFile: ((String) -> Unit)?,
    ) {
        val temporary = "$target.fake.tmp"
        validateTemporaryFile?.invoke(temporary)
        files[target] = content
        this.attributes[target] = attributes
        writtenTargets += target
    }

    override fun setMode(path: String, mode: UInt) {
        attributes[path] = InstalledFileAttributes(mode)
    }

    override fun exists(path: String): Boolean =
        path in files || path in directories || path in symlinks

    override fun isRegularFile(path: String): Boolean = path in files

    override fun isReadable(path: String): Boolean = path in files

    override fun isSymbolicLink(path: String): Boolean = path in symlinks

    override fun readSymbolicLink(path: String): String? = symlinks[path]

    override fun readText(path: String): String =
        files[path] ?: error("missing fake file $path")

    override fun removeFileIfExists(path: String) {
        files.remove(path)
        symlinks.remove(path)
    }

    override fun removeTreeIfExists(path: String) {
        treeRemovalAttempts += path
        if (path in failedTreeRemovals) {
            throw IllegalStateException("Unable to remove '$path': injected failure")
        }
        files.keys.filter { it == path || it.startsWith("$path/") }.forEach(files::remove)
        directories.removeAll { it == path || it.startsWith("$path/") }
        symlinks.keys.filter { it == path || it.startsWith("$path/") }.forEach(symlinks::remove)
    }
}
