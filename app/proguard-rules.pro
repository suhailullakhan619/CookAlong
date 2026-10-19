# kotlinx.serialization: keep generated serializers for bundled recipe/config/session models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers @kotlinx.serialization.Serializable class com.cookalong.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.cookalong.app.**$$serializer { *; }
