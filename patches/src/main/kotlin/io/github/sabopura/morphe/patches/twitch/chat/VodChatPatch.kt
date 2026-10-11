package io.github.sabopura.morphe.patches.twitch.chat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import io.github.sabopura.morphe.patches.twitch.support.classesMatching
import io.github.sabopura.morphe.patches.twitch.support.uniqueContract
import java.util.logging.Logger

private object VodModel : Fingerprint(strings=listOf("ChommentModel(id="))

internal val vodChatPatch=bytecodePatch {
    extendWith("extensions/twitch.mpe")
    val fields=linkedMapOf<String,FieldReference>()
    execute {
        val model=VodModel.originalClassDef
        fun described(type:String)=dataClassFields(classDefBy(type).methods.single { it.name=="toString" }) { ref->
            classDefBy(ref.definingClass).methods.single { it.name==ref.name&&it.parameterTypes==ref.parameterTypes&&it.returnType==ref.returnType }
        }
        val labels=described(model.type)
        for((name,label) in mapOf("id" to "ChommentModel(id=","channel" to ", channelId=","createdAt" to ", createdAt=","commenter" to ", commenter="))
            fields[name]=labels[label]?:throw PatchException("VOD chat: unresolved $label")
        val commenter=described(fields.getValue("commenter").type)
        fields["userId"]=commenter["ChommentCommenterModel(id="]?:throw PatchException("VOD chat: commenter ID missing")
        fields["username"]=commenter[", username="]?:throw PatchException("VOD chat: commenter name missing")
        val row=classesMatching { isVodRow(it,model.type) }.uniqueContract("VOD message row")
        fields["model"]=row.instanceFields.filter { it.type==model.type }.uniqueContract("VOD row model field")
        val binder=mutableClassDefBy(row).methods.filter(::isVodBinder).uniqueContract("VOD row text binder")
        fields["textView"]=binder.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? FieldReference
        }.distinct().single { it.type=="Landroid/widget/TextView;" }
        fields.values.forEach { f->if(!AccessFlags.PUBLIC.isSet(classDefBy(f.definingClass).fields.single { it.name==f.name }.accessFlags))throw PatchException("VOD chat: inaccessible field") }
        val returns=binder.instructions.withIndex().filter { it.value.opcode==Opcode.RETURN_VOID }
        if(returns.isEmpty())throw PatchException("VOD chat: missing binder return")
        returns.asReversed().forEach { (index,_)->binder.addInstructions(index,"invoke-static/range {p0 .. p1}, Lio/github/sabopura/morphe/extension/twitch/chat/ChatEnhancements;->bindVod(Ljava/lang/Object;Ljava/lang/Object;)V") }
        Logger.getLogger("Sabopura.Chat").info("VOD chat: ${returns.size} binder hook(s), ${fields.size} resolved fields")
    }
    finalize {
        val bridge=mutableClassDefBy("Lio/github/sabopura/morphe/extension/twitch/chat/VodRowAccess;")
        fields.forEach { (name,field)->val method=bridge.methods.single { it.name==name };method.removeInstructions(0,method.instructions.count());
            method.addInstructions(0,"check-cast p0, ${field.definingClass}\niget-object p0, p0, $field\nreturn-object p0") }
    }
}
