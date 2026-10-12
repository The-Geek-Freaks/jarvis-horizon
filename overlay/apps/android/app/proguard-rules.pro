-dontwarn org.bouncycastle.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn com.sun.jna.**
-dontwarn java.awt.*
# JNA resolves interface methods and native dispatch symbols by their original names.
-keep class com.sun.jna.* { *; }
-keep class * extends com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }
-keep interface ai.openclaw.app.gateway.CloudflareSodiumLibrary { *; }
# The pinned Sherpa AAR has an empty consumer rules file. Its JNI implementation
# resolves configuration classes, fields and constructors by their original names.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# This bridge exports Java_ai_openclaw_app_voice_SherpaWakeKeywordSpotter_* symbols.
-keepclasseswithmembernames,includedescriptorclasses class ai.openclaw.app.voice.SherpaWakeKeywordSpotter {
    native <methods>;
}
-dontwarn javax.naming.**
-dontwarn lombok.Generated
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn sun.net.spi.nameservice.NameServiceDescriptor
