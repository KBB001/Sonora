# --- General Debugging & Line Numbers ---
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SourceFile

# --- NewPipeExtractor, NanoJSON, Jsoup & Rhino JS Engine ---
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn org.schabi.newpipe.extractor.**
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.mozilla.javascript.tools.**
-dontwarn com.grack.nanojson.**
-dontwarn org.jsoup.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Kotlinx Serialization ---
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.domain.model.**$$serializer { *; }
-keep class com.example.domain.model.** { *; }
-keepclassmembers class com.example.domain.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.domain.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.ui.navigation.**$$serializer { *; }
-keep class com.example.ui.navigation.** { *; }

# --- Room Database, Entities & Tuple Projections ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class com.example.data.local.entity.** { *; }
-keep @androidx.room.Dao interface com.example.data.local.dao.** { *; }
-keep class com.example.data.local.dao.PlaylistSummaryTuple { *; }
-dontwarn androidx.room.**

# --- Hilt, Dagger & ViewModels ---
-keep class dagger.hilt.** { *; }
-keep class com.example.di.** { *; }
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-dontwarn dagger.hilt.**

# --- Glance AppWidget ---
-keep class androidx.glance.** { *; }
-keep class com.example.widget.** { *; }
-dontwarn androidx.glance.**

# --- Media3 ExoPlayer & Session ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- OkHttp, Okio & Conscrypt ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
