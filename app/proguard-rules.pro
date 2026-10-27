# Keep all app classes (models, serializers, services, receivers).
-keep class com.taskpilot.app.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlinx.serialization.**
