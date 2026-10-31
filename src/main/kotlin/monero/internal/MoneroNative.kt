package monero.internal

import com.fasterxml.jackson.core.type.TypeReference
import com.sun.jna.FromNativeContext
import com.sun.jna.FromNativeConverter
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ToNativeContext
import com.sun.jna.ToNativeConverter
import com.sun.jna.TypeMapper
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.math.BigInteger
import monero.common.MoneroError
import monero.common.MoneroJson

/**
 * Loads monero-c, checks the results of its functions and reads what they return. The last error is per thread: a call
 * and its check run in the same thread.
 */
internal object MoneroNative {

    /**
     * The name of the system property or the environment variable with the path of the library, when it is not in the
     * path of the system or in the resources of the classpath.
     */
    const val LIBRARY_PROPERTY = "monero.c.library"
    const val LIBRARY_ENVIRONMENT = "MONERO_C_LIBRARY"

    /**
     * The version of the C ABI that this code was written for. The minor version grows when functions are added, and
     * a library with a greater one has everything that this code calls, so it is the minimum, not the exact version.
     */
    const val ABI_MAJOR = 0
    const val ABI_MINOR = 3

    private const val ENCODING = "UTF-8"

    private val lock = Any()
    private var loaded: MoneroC? = null

    /**
     * The functions of monero-c. The library is loaded the first time, and its version of the ABI is compared with the
     * one that this code was written for.
     *
     * @throws MoneroError if the library is not found, or it is not of the version of the ABI that is needed
     */
    val lib: MoneroC
        get() {
            loaded?.let { return it }
            synchronized(lock) {
                loaded?.let { return it }
                val library = load()
                checkAbi(library)
                loaded = library
                return library
            }
        }

    private fun load(): MoneroC {
        val path = System.getProperty(LIBRARY_PROPERTY) ?: System.getenv(LIBRARY_ENVIRONMENT) ?: "monero_c"
        try {
            return Native.load(path, MoneroC::class.java, mapOf(Library.OPTION_STRING_ENCODING to ENCODING, Library.OPTION_TYPE_MAPPER to BooleanTypeMapper()))
        } catch (e: UnsatisfiedLinkError) {
            throw MoneroError("The native library monero_c is not found, or it can not be loaded. Set the system property $LIBRARY_PROPERTY or the environment variable $LIBRARY_ENVIRONMENT to its path, or put it in the library path: ${e.message}", e)
        }
    }

    private fun checkAbi(library: MoneroC) {
        val major = IntByReference()
        val minor = IntByReference()
        val patch = IntByReference()
        try {
            ok(library, library.monero_utils_get_abi_version(major, minor, patch))
        } catch (e: UnsatisfiedLinkError) {
            throw MoneroError("The native library monero_c is too old: it has no version of its ABI", e)
        }
        if (major.value != ABI_MAJOR || minor.value < ABI_MINOR) {
            throw MoneroError("The version of the ABI of monero-c is ${major.value}.${minor.value}.${patch.value}, and $ABI_MAJOR.$ABI_MINOR.x or newer is needed")
        }
    }

    /**
     * Throws if the result of a call is an error, with the message that monero-c recorded for this thread.
     */
    fun ok(result: Int) = ok(lib, result)

    private fun ok(library: MoneroC, result: Int) {
        if (result == 0) return
        val message = library.monero_last_error()?.getString(0, ENCODING)
        throw MoneroError(if (message.isNullOrEmpty()) "monero-c error" else message)
    }

    /**
     * Read the text that a call returned in a native string, and free the native string.
     *
     * @return the text, or null if the call had no result
     */
    fun text(result: Int, out: PointerByReference): String? {
        ok(result)
        val ptr = out.value ?: return null
        try {
            return ptr.getString(0, ENCODING)
        } finally {
            lib.monero_utils_free(ptr)
        }
    }

    /**
     * Read the text that a call returned in a native string, and overwrite and free the native string, which a
     * secret is: the seed, the private keys and the transaction keys (monero_c_utils.h:151).
     *
     * @return the text, or null if the call had no result
     */
    fun secret(result: Int, out: PointerByReference): String? {
        ok(result)
        val ptr = out.value ?: return null
        try {
            return ptr.getString(0, ENCODING)
        } finally {
            lib.monero_utils_free_secret(ptr)
        }
    }

    /**
     * Read the JSON that a call returned in a native string as a model, and free the native string.
     */
    fun <T> json(result: Int, out: PointerByReference, type: Class<T>): T {
        val json = text(result, out) ?: throw MoneroError("monero-c returned no JSON, and a ${type.simpleName} is needed")
        return MoneroJson.deserialize(json, type)
    }

    /**
     * Read the JSON that a call returned in a native string as a model, such as a list, and free the native string.
     */
    fun <T> json(result: Int, out: PointerByReference, type: TypeReference<T>): T {
        val json = text(result, out) ?: throw MoneroError("monero-c returned no JSON, and a ${type.type.typeName} is needed")
        return MoneroJson.deserialize(json, type)
    }

    /**
     * Read a JSON array of strings that a call returned.
     */
    fun strings(result: Int, out: PointerByReference): List<String> = json(result, out, object : TypeReference<List<String>>() {})

    /**
     * Read the array of 64-bit numbers that a call returned, and free it.
     */
    fun longs(result: Int, out: PointerByReference, count: Pointer): List<Long> {
        ok(result)
        val ptr = out.value ?: return emptyList()
        try {
            return ptr.getLongArray(0, size(count)).toList()
        } finally {
            lib.monero_utils_free(ptr)
        }
    }

    /**
     * Read the array of 32-bit numbers that a call returned, and free it.
     */
    fun ints(result: Int, out: PointerByReference, count: Pointer): List<Int> {
        ok(result)
        val ptr = out.value ?: return emptyList()
        try {
            return ptr.getIntArray(0, size(count)).toList()
        } finally {
            lib.monero_utils_free(ptr)
        }
    }

    /**
     * Read the bytes that a call returned, and free them.
     */
    fun bytes(result: Int, out: PointerByReference, count: Pointer): ByteArray {
        ok(result)
        val ptr = out.value ?: return ByteArray(0)
        try {
            return ptr.getByteArray(0, size(count))
        } finally {
            lib.monero_utils_free(ptr)
        }
    }

    /**
     * The value of an unsigned 64-bit number, such as a balance in atomic units, which a Long holds as a negative number when it
     * is bigger than Long.MAX_VALUE.
     */
    fun unsigned(value: Long): BigInteger = BigInteger(java.lang.Long.toUnsignedString(value))

    /**
     * Memory for a size_t that a call writes.
     */
    fun newSize(): Memory = Memory(Native.SIZE_T_SIZE.toLong()).also { it.clear() }

    private fun size(count: Pointer): Int {
        val value = if (Native.SIZE_T_SIZE == 8) count.getLong(0) else count.getInt(0).toLong() and 0xffffffffL
        if (value < 0 || value > Int.MAX_VALUE) throw MoneroError("monero-c returned an array that is too big: $value")
        return value.toInt()
    }
}

/**
 * Maps a Boolean of the declarations of MoneroC to the one byte of a C bool, and the byte back to a Boolean. JNA
 * marshals a Boolean as a 32-bit number without the mapping: a parameter that is true arrives as 0xff in the byte of
 * a C bool, which C99 defines to hold only 0 or 1, and a return is read as all the 32 bits of the register, whose
 * high ones the ABI of x86-64 leaves undefined when a bool is returned in AL.
 */
internal class BooleanTypeMapper : TypeMapper {

    override fun getFromNativeConverter(type: Class<*>): FromNativeConverter? {
        return if (type == Boolean::class.java || type == Boolean::class.javaObjectType) FROM_NATIVE else null
    }

    override fun getToNativeConverter(type: Class<*>): ToNativeConverter? {
        return if (type == Boolean::class.java || type == Boolean::class.javaObjectType) TO_NATIVE else null
    }

    private companion object {

        val FROM_NATIVE = object : FromNativeConverter {

            override fun nativeType(): Class<*> = Byte::class.javaObjectType

            override fun fromNative(nativeValue: Any?, context: FromNativeContext?): Boolean {
                return (nativeValue as Number).toInt() != 0
            }
        }

        val TO_NATIVE = object : ToNativeConverter {

            override fun nativeType(): Class<*> = Byte::class.javaObjectType

            override fun toNative(value: Any?, context: ToNativeContext?): Byte {
                return if (value as Boolean) 1 else 0
            }
        }
    }
}
