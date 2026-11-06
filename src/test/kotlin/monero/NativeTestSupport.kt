package monero

import com.sun.jna.NativeLibrary
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import monero.common.MoneroError
import monero.common.MoneroUtils
import monero.internal.MoneroNative
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * What the tests that call monero-c share. The unit tests of the models need no library, so a test that calls monero-c is
 * skipped when the library is not found, and unit-tests.yml runs it with the library that native.yml built. To run it, set
 * the system property monero.c.library or the environment variable MONERO_C_LIBRARY to the path of the library.
 */
object NativeTestSupport {

    const val LOG_LEVEL_ENVIRONMENT = "MONERO_C_TEST_LOG_LEVEL"
    const val LOG_CATEGORIES_ENVIRONMENT = "MONERO_C_TEST_LOG_CATEGORIES"

    /**
     * The file of the log of monero-c during the tests, which is one for the process.
     */
    val nativeLog: File = File(TestLog.directory, "monero_tests_cpp_${SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())}.log")

    val isAvailable: Boolean by lazy {
        try {
            MoneroNative.lib
            configureNativeLog()
            true
        } catch (e: MoneroError) {
            false
        }
    }

    /**
     * Whether the library of monero-c has the light wallet surface. Not every build of it has the four functions of the
     * light wallet: the submodule of this branch pins the fork of monero-c that carries them, but a library of the
     * environment can point elsewhere, so a test that calls them skips when it does not have them.
     */
    val isLightWalletAvailable: Boolean by lazy {
        if (!isAvailable) false
        else try {
            val path = System.getProperty(MoneroNative.LIBRARY_PROPERTY) ?: System.getenv(MoneroNative.LIBRARY_ENVIRONMENT) ?: "monero_c"
            NativeLibrary.getInstance(path).getFunction("monero_wallet_light_create")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /**
     * Skip the test if monero-c can not be loaded.
     */
    fun assumeAvailable() {
        assumeTrue(isAvailable, "monero-c is not found: set ${MoneroNative.LIBRARY_ENVIRONMENT} to run the tests that call it")
    }

    /**
     * Write the log of monero-c to [nativeLog], not to the console, with the categories of the environment variable
     * MONERO_C_TEST_LOG_CATEGORIES, or else the level of MONERO_C_TEST_LOG_LEVEL, from 0 to 4, which is 0 when it is not set.
     * A test that changes the log of monero-c calls this when it is done.
     */
    fun configureNativeLog() {
        nativeLog.parentFile.mkdirs()
        MoneroUtils.configureLogging(nativeLog.path, false)
        val categories = System.getenv(LOG_CATEGORIES_ENVIRONMENT)
        if (categories != null) MoneroUtils.setLogCategories(categories)
        else MoneroUtils.setLogLevel(System.getenv(LOG_LEVEL_ENVIRONMENT)?.toInt() ?: 0)
    }

    /**
     * Skip the test if the library of monero-c has no light wallet surface.
     */
    fun assumeLightWalletAvailable() {
        assumeTrue(isLightWalletAvailable, "the library of monero-c has no light wallet: the tests that call it need a monero-c with the light wallet surface")
    }
}
