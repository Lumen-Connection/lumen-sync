-keep class kotlinx.serialization.** { *; }
-keepattributes *Annotation*, InnerClasses
# Ktor loads serialization extensions through Java ServiceLoader.
-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider {
    *;
}
