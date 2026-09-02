# ProGuard / R8 rules for MindMax V4.
# R8 is currently disabled (isMinifyEnabled = false). These rules are written for the
# day we turn it on — keep them lean and use them as a checklist.

# Keep Compose runtime annotations.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# kotlinx-serialization — companion serializer objects are looked up reflectively.
-keep,includedescriptorclasses class dev.mindmax.v4.**$$serializer { *; }
-keepclassmembers class dev.mindmax.v4.** {
    *** Companion;
}
-keepclasseswithmembers class dev.mindmax.v4.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room — keep entity columns and DAO method signatures stable.
-keep class dev.mindmax.v4.data.entity.** { *; }
-keep class dev.mindmax.v4.data.dao.** { *; }
