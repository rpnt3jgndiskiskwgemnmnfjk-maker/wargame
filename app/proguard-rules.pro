# ルールエンジン・AI・アプリのクラスは、保存データ(JSON)の読み書きで名前を使うため縮小しない
-keep class afwi.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# kotlinx.serialization
-dontnote kotlinx.serialization.**
-keepclassmembers class afwi.** {
    *** Companion;
}
-keepclasseswithmembers class afwi.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class afwi.**$$serializer { *; }
