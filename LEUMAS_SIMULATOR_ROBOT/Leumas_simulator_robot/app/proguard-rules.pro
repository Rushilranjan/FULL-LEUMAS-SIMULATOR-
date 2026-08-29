# kotlinx.serialization keeps generated serializers reachable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.leumas.robot.** {
    *** Companion;
}
-keepclasseswithmembers class com.leumas.robot.** {
    kotlinx.serialization.KSerializer serializer(...);
}
