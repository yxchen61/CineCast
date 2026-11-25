# Keep Kotlinx Serialization models
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cinecast.app.data.model.**$$serializer { *; }
-keepclassmembers class com.cinecast.app.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.cinecast.app.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
