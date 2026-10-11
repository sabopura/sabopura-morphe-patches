package io.github.sabopura.morphe.patches.twitch.points

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod

internal const val POINTS_RENDER_HELPER = "sabopuraRenderPointsState"

/** Isolate native scratch registers: newer R8 reuses p0 as a boolean and even a return value. */
internal fun wrapPointsRenderer(original: Method): Pair<MutableMethod, MutableMethod> {
    val implementation = original.implementation ?: throw PatchException("Twitch points: renderer has no code")
    if (AccessFlags.STATIC.isSet(original.accessFlags) || original.parameterTypes.size != 1 ||
        !original.parameterTypes[0].startsWith("L") || !original.returnType.startsWith("L") ||
        implementation.instructions.none { it.opcode == Opcode.RETURN_OBJECT })
        throw PatchException("Twitch points: unsupported renderer contract")
    val privateFlags = (original.accessFlags and (AccessFlags.PUBLIC.value or AccessFlags.PROTECTED.value).inv()) or
        AccessFlags.PRIVATE.value or AccessFlags.SYNTHETIC.value
    val native = MutableMethod(ImmutableMethod(original.definingClass, POINTS_RENDER_HELPER, original.parameters,
        original.returnType, privateFlags, emptySet(), original.hiddenApiRestrictions, implementation))
    val wrapper = MutableMethod(ImmutableMethod(original.definingClass, original.name, original.parameters,
        original.returnType, original.accessFlags, original.annotations, original.hiddenApiRestrictions,
        MutableMethodImplementation(4)))
    wrapper.addInstructions(0, """
        invoke-direct {p0, p1}, $native
        move-result-object v0
        invoke-static {v0, p0}, Lio/github/sabopura/morphe/extension/twitch/points/AutoClaimPoints;->onViewState(Ljava/lang/Object;Ljava/lang/Object;)V
        return-object v0
    """)
    return wrapper to native
}
