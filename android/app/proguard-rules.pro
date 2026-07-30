# kotlinx.serialization genera i serializer come membri statici delle classi
# @Serializable: senza queste regole R8 li rimuove e il parsing esplode a
# runtime solo nella build di release.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class it.spotifystats.app.data.api.** {
    *** Companion;
}
-keepclasseswithmembers class it.spotifystats.app.data.api.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit conserva i tipi generici delle interfacce solo se non offuscati.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
