package io.github.sabopura.morphe.patches.twitch.chat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import io.github.sabopura.morphe.patches.twitch.Constants.TWITCH_SUPPORTED
import io.github.sabopura.morphe.patches.twitch.ui.enableTwitchFeature
import java.util.logging.Logger

private const val PLAYER="Lio/github/sabopura/morphe/extension/twitch/player/PlayerTools;"
private object VodComments : Fingerprint(strings=listOf("parseChommentResponse"))
private object LiveManifest : Fingerprint(strings=listOf("usher.ttvnw.net","fast_bread"))

@Suppress("unused")
val playerEnhancementsPatch=bytecodePatch(
    name="Player enhancements",
    description="Playback speed, seek steps, statistics, sleep timer, VOD chat offset and chapters. " +
        "Optional public manifest proxy, gestures, layout and Following feed controls in the player menu settings."
) {
    compatibleWith(TWITCH_SUPPORTED)
    dependsOn(chatEnhancementsPatch)
    dependsOn(layoutPatch)
    dependsOn(feedPatch)
    execute {
        val application=mutableClassDefBy("Ltv/twitch/android/app/consumer/TwitchApplication;")
        val create=application.methods.single { it.name=="onCreate" && it.parameterTypes.isEmpty() }
        // Android attaches the Application Context before invoking onCreate. Twitch moves its
        // super call into a helper, so do not assume an inline invoke-super here.
        create.addInstructions(0,"invoke-static/range {p0 .. p0}, $PLAYER->initialize(Landroid/content/Context;)V")
        val core=mutableClassDefBy("Lcom/amazonaws/ivs/player/CorePlayerImpl;")
        for((name,hook) in listOf("play" to "onPlay","release" to "onRelease")) {
            core.methods.single { it.name==name && it.parameterTypes.isEmpty() }.addInstructions(0,
                "invoke-static/range {p0 .. p0}, $PLAYER->$hook(Ljava/lang/Object;)V")
        }
        val comments=mutableClassDefBy(VodComments.originalClassDef)
        val fetch=comments.methods.single { it.parameterTypes.map { p->p.toString() }==listOf("I","Ljava/lang/String;") && it.returnType.startsWith("Lio/reactivex/") }
        fetch.addInstructions(0,"""
            invoke-static/range {p1 .. p1}, $PLAYER->commentTime(I)I
            move-result p1
        """)
        val setup=comments.methods.single { it.parameterTypes.map { p->p.toString() }==listOf("Ltv/twitch/android/models/videos/VodModel;","Ltv/twitch/android/models/channel/ChannelModel;") && it.returnType=="V" }
        setup.addInstructions(0,"invoke-static/range {p1 .. p2}, $PLAYER->onVod(Ljava/lang/Object;Ljava/lang/Object;)V")
        val manifest=LiveManifest.method
        val returns=manifest.instructions.withIndex().filter { it.value.opcode==Opcode.RETURN_OBJECT }
        if(returns.isEmpty())throw PatchException("Player enhancements: live manifest return missing")
        returns.asReversed().forEach { (index,instruction)->
            val r=(instruction as OneRegisterInstruction).registerA
            manifest.addInstructions(index,"""
                check-cast v$r, Landroid/net/Uri;
                invoke-static/range {v$r .. v$r}, Lio/github/sabopura/morphe/extension/twitch/player/LiveProxy;->rewrite(Landroid/net/Uri;)Landroid/net/Uri;
                move-result-object v$r
            """)
        }
        Logger.getLogger("Sabopura.Player").info("Player enhancements: app init, IVS play/release, VOD setup/offset, ${returns.size} manifest hook(s)")
    }
    finalize {
        enableTwitchFeature("playerPatch")
    }
}
