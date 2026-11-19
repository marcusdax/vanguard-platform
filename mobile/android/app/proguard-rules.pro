# Fieldwright release rules.
#
# The release build is not minified in this sample build (isMinifyEnabled = false), so these
# rules are a starting point for when R8 is switched on. They are intentionally explicit:
# reflective JSON codecs and Room must keep their generated names, everything else is
# obfuscated. Do not add blanket -keep rules; name the class you actually need.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# Room generated implementations
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }

# WorkManager instantiates workers reflectively
-keep class * extends androidx.work.ListenableWorker { <init>(...); }

# Keep the sealed-packet data model field names: canonical JSON hashes depend on them.
-keepclassmembers class com.odasi.fieldwright.model.** {
    <fields>;
}
