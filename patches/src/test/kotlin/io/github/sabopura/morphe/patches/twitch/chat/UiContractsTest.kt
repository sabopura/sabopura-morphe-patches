package io.github.sabopura.morphe.patches.twitch.chat

import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.*
import com.android.tools.smali.dexlib2.immutable.instruction.*
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class UiContractsTest {
    private inline fun <reified T> fixture(vararg values: Pair<String, Any?>): T {
        val map = values.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            check(map.containsKey(method.name)) { "Unexpected ${method.name}" }; map[method.name]
        } as T
    }
    private fun method(name: String, params: List<String>, result: String, code: List<Instruction>, flags: Int = 1, registers: Int = 8) =
        fixture<Method>("getName" to name, "getParameterTypes" to params, "getReturnType" to result,
            "getAccessFlags" to flags, "getImplementation" to fixture<MethodImplementation>(
                "getInstructions" to code, "getRegisterCount" to registers))
    private fun call(owner: String, name: String, params: List<String>, result: String) =
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, params.size + 1, 0, 1, 2, 3, 4,
            ImmutableMethodReference(owner, name, params, result))
    private fun row(model: String, binder: Method, idName: String = "getItemId") = fixture<ClassDef>(
        "getInstanceFields" to listOf(fixture<Field>("getType" to model)),
        "getMethods" to listOf(binder, method(idName, emptyList(), "Ljava/lang/String;", emptyList())))
    private fun binder(owner: String = "Landroid/widget/TextView;", flags: Int = 1) = method(
        "renamed", listOf("Lrenamed/ViewHolder;"), "V",
        listOf(call(owner, "setText", listOf("Ljava/lang/CharSequence;"), "V")), flags)

    @Test fun resolvesVodRowAfterNullCheckStringsAndNamesDisappear() {
        // No CONST_STRING instructions: the constructor's old parameter labels are absent.
        assertTrue(isVodRow(row("Lnew/Model;", binder()), "Lnew/Model;"))
    }
    @Test fun rejectsDecoyRowsAndUnrelatedTextSetters() {
        assertFalse(isVodRow(row("Lother/Model;", binder()), "Lnew/Model;"))
        assertFalse(isVodRow(row("Lnew/Model;", binder(), "otherMethod"), "Lnew/Model;"))
        assertFalse(isVodRow(row("Lnew/Model;", binder("Lother/Widget;")), "Lnew/Model;"))
        assertFalse(isVodBinder(binder(flags = 9)))
    }
    private fun cards(owner: String, flags: Int = 1) = method("renamed",
        listOf("Ljava/util/List;", "Z", "Ljava/util/Map;", "Ljava/util/Map;", "Ljava/util/Map;"),
        "Ljava/util/ArrayList;", listOf(call(owner, "getChannelId", emptyList(), "Ltv/twitch/android/models/Tuid;")), flags)
    @Test fun resolvesFollowingBuilderWithoutConstructorLabels() {
        assertTrue(isFollowingCardsBuilder(cards("Ltv/twitch/android/models/streams/StreamModelBase;")))
    }
    @Test fun rejectsSameShapeWithoutStreamModelContract() {
        assertFalse(isFollowingCardsBuilder(cards("Lother/Model;")))
        assertFalse(isFollowingCardsBuilder(cards("Ltv/twitch/android/models/streams/StreamModelBase;", 9)))
    }
    @Test fun rejectsUnencodableReorderedWidthArguments() {
        assertThrows(PatchException::class.java) {
            widthHook(method("renamed", listOf("I", "Landroid/view/View;"), "V", emptyList(), 9, 17))
        }
    }
    @Test fun rejectsWrongWidthShapeBeforeWritingInstructions() {
        assertThrows(PatchException::class.java) {
            widthHook(method("renamed", listOf("I", "Landroid/view/View;"), "V", emptyList(), 1))
        }
        assertThrows(PatchException::class.java) {
            widthHook(method("renamed", listOf("I", "Ljava/lang/Object;"), "V", emptyList(), 9))
        }
    }
    private fun constructor(idType: String, overwrittenReceiver: Boolean = false): Method = ImmutableMethod(
        "Lfixture/ChannelKey;", "<init>",
        listOf(idType, "Ljava/lang/String;").map { ImmutableMethodParameter(it, emptySet(), null) },
        "V", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(3, buildList {
            if (overwrittenReceiver) add(ImmutableInstruction11n(Opcode.CONST_4, 0, 0))
            add(ImmutableInstruction10x(Opcode.RETURN_VOID))
        }, emptyList(), emptyList()))

    @Test fun channelKeySupportsStringAndTuidButRejectsUnknownIds() {
        assertFalse(channelConstructorHook(constructor("Ljava/lang/String;")).contains("Tuid;->toString"))
        assertTrue(channelConstructorHook(constructor("Ltv/twitch/android/models/Tuid;")).contains("Tuid;->toString"))
        assertThrows(PatchException::class.java) { channelConstructorHook(constructor("Ljava/lang/Long;")) }
    }

    @Test fun channelKeyRejectsOverwrittenReceiverBeforeGeneratingUnsafeReads() {
        assertThrows(PatchException::class.java) { channelConstructorHook(constructor("Ljava/lang/String;", true)) }
    }

    @Test fun inlinedFollowingLoopRequiresHeaderAndIndexMapSemantics() {
        val field = ImmutableFieldReference("Lfixture/Header;", "renamed", "Lfixture/Section;")
        val code = listOf(
            call("Ljava/util/ArrayList;", "iterator", emptyList(), "Ljava/util/Iterator;"),
            call("Ljava/util/Iterator;", "next", emptyList(), "Ljava/lang/Object;"),
            ImmutableInstruction22c(Opcode.INSTANCE_OF, 1, 2, ImmutableTypeReference(field.definingClass)),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 2, field),
            call("Ljava/lang/Integer;", "valueOf", listOf("I"), "Ljava/lang/Integer;"),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            call("Ljava/util/Map;", "put", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "Ljava/lang/Object;"))
        val native = method("invokeSuspend", listOf("Ljava/lang/Object;"), "Ljava/lang/Object;", code)
        assertEquals(FollowingIndexHook(0, 0), followingIndexLoop(native, field))
        assertNull(followingIndexLoop(method("other", emptyList(), "V", code.dropLast(1)), field))
        assertNull(followingIndexLoop(method("other", emptyList(), "V", code.filter { it.opcode != Opcode.INSTANCE_OF }), field))
        assertNull(followingIndexLoop(native, ImmutableFieldReference("Ldecoy/Header;", field.name, field.type)))
    }
}
