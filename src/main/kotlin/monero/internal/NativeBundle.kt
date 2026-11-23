package monero.internal

import com.sun.jna.Platform
import java.io.File
import java.io.IOException
import java.net.JarURLConnection
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The native library of monero-c when it comes in the classpath, as the monero-native-jvm jar of a release of monero-c has it: the files of each
 * platform in a folder named as [Platform.RESOURCE_PREFIX] (linux-x86-64, darwin-aarch64, win32-x86-64, ...), the library and the libusb that it
 * needs. A program can only load a library from a file, and JNA takes only the library out of a jar, which then does not find libusb next to it.
 * So all the files of the folder are copied into one folder, where the library is loaded from.
 */
internal object NativeBundle {

    /**
     * Copy the files of the folder of the platform that has the library to a folder of their own.
     *
     * @param loader is the class loader to look in
     * @param prefix is the folder of the platform in the classpath
     * @param library is the file name of the library
     * @param directory is where to copy them, or null for a new folder in the temporary one (the one of jna.tmpdir when it is set) whose files are
     * deleted when the process ends, which Windows does not let it do with a library that is loaded
     * @return the copy of the library, or null if the classpath has no folder of this platform with the library in it
     * @throws IOException if the files can not be copied
     */
    fun extract(
        loader: ClassLoader,
        prefix: String = Platform.RESOURCE_PREFIX,
        library: String = System.mapLibraryName("monero_c"),
        directory: Path? = null
    ): File? {
        val resource = loader.getResource("$prefix/$library") ?: return null
        val names = names(resource, prefix) ?: return null
        val target = directory ?: newDirectory()
        for (name in names) {
            // an entry that the listing has but the copy can not open is a broken classpath, and skipping it would leave the
            // library without a file it needs or without itself, which fails later with no word of this one
            val input = loader.getResourceAsStream("$prefix/$name")
                ?: throw IOException("The file $name of the native bundle of monero-c can not be read from the classpath")
            input.use { Files.copy(it, target.resolve(name), StandardCopyOption.REPLACE_EXISTING) }
        }
        if (directory == null) deleteWhenTheProcessEnds(target)
        return target.resolve(library).toFile()
    }

    // the files in the folder of the platform, which is in a jar or in a directory of the classpath
    private fun names(resource: java.net.URL, prefix: String): List<String>? {
        return when (resource.protocol) {
            "jar" -> {
                val connection = resource.openConnection() as JarURLConnection

                // this jar is ours to close: a cached one is the class loader's
                connection.useCaches = false
                connection.jarFile.use { jar ->
                    jar.entries().asSequence()
                        .filter { !it.isDirectory && it.name.startsWith("$prefix/") && isFileName(it.name.removePrefix("$prefix/")) }
                        .map { it.name.removePrefix("$prefix/") }
                        .toList()
                }
            }
            "file" -> File(resource.toURI()).parentFile.listFiles { file -> file.isFile }?.map { it.name }
            else -> null
        }
    }

    // a jar can name an entry with the parent in it, with a backslash, which windows takes as a separator, or with a colon, which it takes for
    // the drive of a path (D:name is in the current folder of that drive) or for the stream of a file: the copy above would write them outside
    // the folder of the platform or somewhere else in it. Only a plain file name is taken, and none of the files of a bundle has a colon
    private fun isFileName(name: String): Boolean {
        return name.isNotEmpty() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == ':' }
    }

    private fun newDirectory(): Path {
        val parent = System.getProperty("jna.tmpdir")
        return if (parent != null) Files.createTempDirectory(File(parent).toPath(), "monero-c-") else Files.createTempDirectory("monero-c-")
    }

    private fun deleteWhenTheProcessEnds(directory: Path) {
        Runtime.getRuntime().addShutdownHook(Thread {
            directory.toFile().listFiles()?.forEach { it.delete() }
            directory.toFile().delete()
        })
    }
}
