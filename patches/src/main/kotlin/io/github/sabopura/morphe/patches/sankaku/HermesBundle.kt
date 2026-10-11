package io.github.sabopura.morphe.patches.sankaku

import app.morphe.patcher.patch.PatchException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Minimal reader/writer for Hermes bytecode bundles (versions 98 and 99), enough to locate
 * functions by name and rewrite their first instructions. Layout follows Hermes'
 * BytecodeFileFormat.h as decoded by hermes-dec (P1 Security).
 */
internal class HermesBundle(private val bytes: ByteArray) {
    data class Function(
        val index: Int,
        val nameId: Int,
        val offset: Int,
        val size: Int,
        val frameSize: Int,
    )

    private val buffer: ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val version: Int
    val functions: List<Function>
    private val smallStringTable: Int
    private val overflowStringTable: Int
    private val stringStorage: Int
    private val stringCount: Int

    init {
        if (bytes.size < HEADER_SIZE || buffer.getLong(0) != MAGIC) {
            throw PatchException("Not a Hermes bytecode bundle")
        }
        version = buffer.getInt(8)
        if (version !in SUPPORTED_VERSIONS) {
            throw PatchException("Unsupported Hermes bytecode version $version (supported: $SUPPORTED_VERSIONS)")
        }
        if (buffer.getInt(32) != bytes.size) throw PatchException("Hermes bundle length mismatch")

        val functionCount = buffer.getInt(40)
        val stringKindCount = buffer.getInt(44)
        val identifierCount = buffer.getInt(48)
        stringCount = buffer.getInt(52)
        val overflowStringCount = buffer.getInt(56)

        var position = HEADER_SIZE
        functions = List(functionCount) { index ->
            readFunction(index, position + index * SMALL_FUNCTION_HEADER_SIZE)
        }
        position = align(position + functionCount * SMALL_FUNCTION_HEADER_SIZE)
        position = align(position + stringKindCount * 4)
        position = align(position + identifierCount * 4)
        smallStringTable = position
        position = align(position + stringCount * 4)
        overflowStringTable = position
        position = align(position + overflowStringCount * 8)
        stringStorage = position
    }

    private fun readFunction(index: Int, at: Int): Function {
        val word0 = buffer.getInt(at)
        val word1 = buffer.getInt(at + 4)
        val flags = bytes[at + 11].toInt()
        val overflowed = flags and 0x20 != 0
        val nameBits = (word1 ushr 14) and 0xFF
        val offsetBits = word0 and 0x1FFFFFF
        if (!overflowed) {
            return Function(index, nameBits, offsetBits, word1 and 0x3FFF, bytes[at + 8].toInt() and 0xFF)
        }
        // Large header: offset, paramCount, loopDepth, bytecodeSize, functionName,
        // numberRegCount, nonPtrRegCount, frameSize (u32 each), then 4 bytes.
        val large = (nameBits shl 24) or offsetBits
        return Function(
            index = index,
            nameId = buffer.getInt(large + 16),
            offset = buffer.getInt(large),
            size = buffer.getInt(large + 12),
            frameSize = buffer.getInt(large + 28),
        )
    }

    /** Ids of every string table entry equal to [value] (ASCII only). */
    fun stringIds(value: String): Set<Int> {
        val wanted = value.toByteArray(Charsets.ISO_8859_1)
        return (0 until stringCount).filterTo(mutableSetOf()) { id ->
            val entry = buffer.getInt(smallStringTable + id * 4)
            if (entry and 1 != 0) return@filterTo false // UTF-16
            var offset = (entry ushr 1) and 0x7FFFFF
            var length = (entry ushr 24) and 0xFF
            if (length == 0xFF) {
                val overflow = overflowStringTable + offset * 8
                offset = buffer.getInt(overflow)
                length = buffer.getInt(overflow + 4)
            }
            length == wanted.size &&
                (wanted.indices).all { bytes[stringStorage + offset + it] == wanted[it] }
        }
    }

    fun functionsNamed(name: String): List<Function> {
        val ids = stringIds(name)
        return functions.filter { it.nameId in ids }
    }

    /** Whether [function] loads one of the strings with [stringIds] into a register. */
    fun loadsString(function: Function, stringIds: Set<Int>): Boolean {
        val end = function.offset + function.size
        for (position in function.offset until end) {
            when (bytes[position]) {
                OP_LOAD_CONST_STRING -> if (position + 4 <= end &&
                    (buffer.getShort(position + 2).toInt() and 0xFFFF) in stringIds
                ) return true

                OP_LOAD_CONST_STRING_LONG -> if (position + 6 <= end &&
                    buffer.getInt(position + 2) in stringIds
                ) return true
            }
        }
        return false
    }

    /** Replaces the function's first instructions with `load r0, <constant>; ret r0`. */
    fun returnConstant(function: Function, loadOpcode: Byte) {
        if (function.frameSize < 1 || function.size < 4) {
            throw PatchException("Function #${function.index} is too small to rewrite")
        }
        byteArrayOf(loadOpcode, 0, OP_RET, 0).copyInto(bytes, function.offset)
    }

    /** Recomputes the SHA-1 footer over everything before it. */
    fun updateFooter() {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(0, bytes.size - FOOTER_SIZE))
        digest.copyInto(bytes, bytes.size - FOOTER_SIZE)
    }

    fun toByteArray(): ByteArray = bytes

    companion object {
        private const val MAGIC = 0x1F1903C103BC1FC6L
        private val SUPPORTED_VERSIONS = 98..99
        private const val HEADER_SIZE = 128
        private const val SMALL_FUNCTION_HEADER_SIZE = 12
        private const val FOOTER_SIZE = 20

        // Opcodes are identical in bytecode versions 98 and 99.
        const val OP_LOAD_CONST_UNDEFINED: Byte = 0x93.toByte()
        const val OP_LOAD_CONST_NULL: Byte = 0x94.toByte()
        const val OP_LOAD_CONST_FALSE: Byte = 0x96.toByte()
        private const val OP_RET: Byte = 0x76
        private const val OP_LOAD_CONST_STRING: Byte = 0x90.toByte()
        private const val OP_LOAD_CONST_STRING_LONG: Byte = 0x91.toByte()

        private fun align(position: Int) = (position + 3) and 3.inv()
    }
}
