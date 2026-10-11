package io.github.sabopura.morphe.patches.shared

object SmaliTemplates {
    fun returnVoid() = "return-void"

    fun returnBoolean(value: Boolean): String {
        val flag = if (value) "0x1" else "0x0"
        return """
            const/4 v0, $flag
            return v0
        """.trimIndent()
    }

    fun returnInt(value: Int): String {
        return """
            const/4 v0, $value
            return v0
        """.trimIndent()
    }

    fun returnNull(): String {
        return """
            const/4 v0, 0x0
            return-object v0
        """.trimIndent()
    }

    fun returnEmptyList(): String {
        return """
            invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
            move-result-object v0
            return-object v0
        """.trimIndent()
    }

    fun returnDisabledWeatherConfig(configType: String): String {
        return """
            sget-object v0, $configType->Companion:Ljp/gocro/smartnews/android/ad/view/adinweather/AdsInWeatherConfig${'$'}Companion;
            invoke-virtual {v0}, Ljp/gocro/smartnews/android/ad/view/adinweather/AdsInWeatherConfig${'$'}Companion;->default()$configType
            move-result-object v0
            return-object v0
        """.trimIndent()
    }

    fun hideReaderWebAds(): String {
        return """
            const-string v0, "(()=>{if(document.getElementById('morphe-smartnews-ad-style'))return;const s=document.createElement('style');s.id='morphe-smartnews-ad-style';s.textContent='[data-sn-ad],#sponsored-links,[data-pixel-type=sponsored-link]{display:none!important;width:0!important;height:0!important;min-height:0!important;margin:0!important;padding:0!important}';(document.head||document.documentElement).appendChild(s)})();"
            const/4 v1, 0x0
            invoke-virtual {p1, v0, v1}, Landroid/webkit/WebView;->evaluateJavascript(Ljava/lang/String;Landroid/webkit/ValueCallback;)V
        """.trimIndent()
    }

    fun hideViewAndReturn(): String {
        return """
            const/16 v0, 0x8
            invoke-virtual {p0, v0}, Landroid/view/View;->setVisibility(I)V
            return-void
        """.trimIndent()
    }

    fun zeroMeasureAndReturn(): String {
        return """
            const/4 v0, 0x0
            const/4 v1, 0x0
            invoke-virtual {p0, v0, v1}, Landroid/view/View;->setMeasuredDimension(II)V
            return-void
        """.trimIndent()
    }

    fun unit(): String {
        return """
            sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
            return-object v0
        """.trimIndent()
    }
}
