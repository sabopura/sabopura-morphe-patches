package io.github.sabopura.morphe.patches.smartnews

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef

internal const val WEATHER_CONFIG_COMPANION =
    "Ljp/gocro/smartnews/android/ad/view/adinweather/AdsInWeatherConfig\$Companion;"

/** Resolve the obfuscated value type through its stable companion, never by release name. */
internal fun resolveWeatherConfigType(
    getterReturnType: String,
    configClass: ClassDef?,
    companionClass: ClassDef?,
): String {
    require(configClass != null && configClass.type == getterReturnType &&
        companionClass?.type == WEATHER_CONFIG_COMPANION) {
        "Weather ad config or its stable companion is missing for $getterReturnType"
    }
    require(configClass.staticFields.any {
        it.name == "Companion" && it.type == WEATHER_CONFIG_COMPANION &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    }) { "Weather ad config has no public static companion: $getterReturnType" }
    require(companionClass.methods.count {
        it.name == "default" && it.parameterTypes.isEmpty() &&
            it.returnType == getterReturnType && it.implementation != null &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } == 1) { "Weather ad config has no unique typed default factory: $getterReturnType" }
    return getterReturnType
}
