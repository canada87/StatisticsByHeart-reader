# kotlinx.serialization: keep generated serializers of our @Serializable classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class app.sbh.reader.** {
    *** Companion;
}
-keepclasseswithmembers class app.sbh.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# OkHttp / Okio optional platforms
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
# HealthTypeSpec resolves the time accessors of Health Connect record classes by name (reflection).
-keep class androidx.health.connect.client.records.** { *; }
