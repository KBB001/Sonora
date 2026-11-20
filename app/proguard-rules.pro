# --- General Debugging & Line Numbers ---
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- NewPipeExtractor & Rhino JS Engine ---
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn org.jsoup.**

# --- Kotlinx Serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.domain.model.**$$serializer { *; }
-keepclassmembers class com.example.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.ui.navigation.**$$serializer { *; }

# --- Room Database & Entities ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class com.example.data.local.entity.** { *; }
-keep @androidx.room.Dao interface com.example.data.local.dao.** { *; }
-dontwarn androidx.room.**

# --- Hilt & Dagger ---
-keep class dagger.hilt.** { *; }
-keep class com.example.di.** { *; }
-dontwarn dagger.hilt.**

# --- Media3 ExoPlayer & Session ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- OkHttp & Networking ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
