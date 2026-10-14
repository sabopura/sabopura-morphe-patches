package io.github.sabopura.morphe.patches.twitch.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import io.github.sabopura.morphe.patches.twitch.Constants.TWITCH_SUPPORTED

private const val ENTRY = "Lio/github/sabopura/morphe/extension/twitch/ui/SettingsEntry;"

/**
 * Inserts code before the instruction at [index] so that branches into that instruction run
 * the new code too: the instruction is copied after the new code and the original removed,
 * which moves its labels onto the first inserted instruction. (Same technique as bakwudo/uyu.)
 */
private fun MutableMethod.insertBeforeKeepingLabels(index: Int, smali: String) {
    addInstruction(index + 1, getInstruction(index))
    addInstructions(index + 1, smali)
    removeInstruction(index)
}

private fun BytecodePatchContext.filterReturnedView(type: String, call: String) {
    val method = mutableClassDefBy(type).methods.singleOrNull {
        it.name == "onCreateView" && it.returnType == "Landroid/view/View;"
    } ?: throw PatchException("Settings entry: $type has no onCreateView")
    val returns = method.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { it.index }
    if (returns.isEmpty()) throw PatchException("Settings entry: $type.onCreateView has no return")
    returns.asReversed().forEach { index ->
        val register = (method.getInstruction(index) as OneRegisterInstruction).registerA
        method.insertBeforeKeepingLabels(index, call.replace("REG", "v$register"))
    }
}

/**
 * Adds a "patch settings" row to the top of Twitch's own settings screen (the gear on the
 * profile page), in both the current screen and the older list-style one.
 */
internal val settingsEntryPatch = bytecodePatch(
    description = "Adds the patch settings to Twitch's settings screen.",
) {
    compatibleWith(TWITCH_SUPPORTED)
    extendWith("extensions/twitch.mpe")

    execute {
        filterReturnedView(
            "Ltv/twitch/android/settings/main/MainSettingsFragmentV2;",
            """
                invoke-static/range {REG .. REG}, $ENTRY->wrap(Landroid/view/View;)Landroid/view/View;
                move-result-object REG
            """,
        )
        // The older screen shares onCreateView with every list-style settings page; the
        // extension only wraps the main one. p0 is the fragment, REG its view.
        filterReturnedView(
            "Ltv/twitch/android/shared/settings/BaseSettingsFragment;",
            """
                invoke-static {p0, REG}, $ENTRY->wrapIfMain(Ljava/lang/Object;Landroid/view/View;)Landroid/view/View;
                move-result-object REG
            """,
        )
    }
}
