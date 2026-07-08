# ────────────────────────────────────────────────────────────────────────────
# ProGuard / R8 rules for the release build of "El tiempo".
#
# Only the rules below are strictly necessary — everything else (Compose,
# AndroidX, Kotlin stdlib, coroutines) ships its own consumer rules via
# the corresponding artifacts.
# ────────────────────────────────────────────────────────────────────────────

# ── kotlinx.serialization ──────────────────────────────────────────────────
# Keep every @Serializable class along with its generated $Companion and
# its synthetic ::serializer() accessor; without this, R8 strips the
# generated Serializer and the AEMET JSON deserialisation crashes.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses

-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keep,allowobfuscation @kotlinx.serialization.Serializable class * {
    <init>(...);
    <fields>;
    public static ** INSTANCE;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep our entire data-model package so field names match the AEMET JSON.
-keep class com.example.aemet_tiempo.data.** { *; }
-keepclassmembers class com.example.aemet_tiempo.data.** { *; }

# ── OkHttp ──────────────────────────────────────────────────────────────────
# OkHttp uses Java platform APIs that R8 can't always see; the warnings
# are noise on modern Android (API 21+) so we silence them.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ── Kotlin metadata ─────────────────────────────────────────────────────────
# Required so reflective lookups in kotlinx.serialization keep working.
-keep class kotlin.Metadata { *; }


