# ── kotlinx.serialization ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.mydevice.**$$serializer { *; }
-keepclassmembers class com.example.mydevice.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.mydevice.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Ktor ─────────────────────────────────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# ── OkHttp (used by Ktor engine) ────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── Room ─────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ── Koin ─────────────────────────────────────────────────────────────────────
-keep class org.koin.** { *; }
-dontwarn org.koin.**

# ── SignalR ──────────────────────────────────────────────────────────────────
-keep class com.microsoft.signalr.** { *; }
-dontwarn com.microsoft.signalr.**

# ── Zebra EMDK ───────────────────────────────────────────────────────────────
-keep class com.symbol.emdk.** { *; }
-dontwarn com.symbol.emdk.**

# ── App DTOs and entities (prevent stripping fields used by serialization) ───
-keep class com.example.mydevice.data.remote.dto.** { *; }
-keep class com.example.mydevice.data.remote.signalr.WifiProfilePayload { *; }
-keep class com.example.mydevice.data.local.database.entity.** { *; }

# ── Google Tink / ErrorProne (used by EncryptedSharedPreferences) ─────────────
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.crypto.tink.**
-keep class com.google.crypto.tink.** { *; }

# ── SLF4J (used by SignalR / Ktor logging) ───────────────────────────────────
-dontwarn org.slf4j.impl.**
-dontwarn org.slf4j.**

# ── Keep line numbers for crash reports ──────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
