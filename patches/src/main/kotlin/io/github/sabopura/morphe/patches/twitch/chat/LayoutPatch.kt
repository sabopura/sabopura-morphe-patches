package io.github.sabopura.morphe.patches.twitch.chat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.sabopura.morphe.patches.twitch.support.classesMatching
import io.github.sabopura.morphe.patches.twitch.support.uniqueContract

private object LandscapeConfig : Fingerprint(strings = listOf("LandscapeChatConfiguration(chatOverlayingPlayerOff=false)"))
internal val layoutPatch = bytecodePatch {
    extendWith("extensions/twitch.mpe")
    execute {
        val config=LandscapeConfig.originalClassDef.type
        val layout=classesMatching { c ->
            c.instanceFields.any { it.type==config } &&
                c.instanceFields.any { it.type=="Landroid/widget/RelativeLayout\$LayoutParams;" }
        }.uniqueContract("landscape chat layout controller")
        val refs=layout.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .filter { it.opcode==Opcode.INVOKE_STATIC || it.opcode==Opcode.INVOKE_STATIC_RANGE }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { it.parameterTypes.map { p->p.toString() }.let { p ->
                p==listOf("Landroid/view/View;","I") || p==listOf("I","Landroid/view/View;")
            } && it.returnType=="V" }.distinct()
        val ref=refs.singleOrNull() ?: throw PatchException("Layout: native percentage-width helper is not unique")
        val helper=mutableClassDefBy(ref.definingClass).methods.single { it.name==ref.name && it.parameterTypes==ref.parameterTypes && it.returnType==ref.returnType }
        helper.addInstructions(0,widthHook(helper))
        val mini=mutableClassDefBy("Ltv/twitch/android/shared/ui/elements/draggable/ConstraintTheatreContainerView;")
            .methods.singleOrNull { it.parameterTypes.size==2 && it.parameterTypes.first()=="Ltv/twitch/android/util/Size;" && it.returnType=="V" }
            ?:throw PatchException("Layout: mini-player size entry point missing")
        mini.addInstructions(0,"""
            invoke-static/range {p0 .. p1}, Lio/github/sabopura/morphe/extension/twitch/player/LayoutTools;->miniNativeSize(Landroid/view/View;Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object p1
            check-cast p1, Ltv/twitch/android/util/Size;
        """)
    }
}
