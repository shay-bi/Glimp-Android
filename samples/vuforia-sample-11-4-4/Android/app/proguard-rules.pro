# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# Vuforia Engine
-keep class com.vuforia.** { *; }
-keep interface com.vuforia.** { *; }

# JNI
-keepclasseswithmembernames class * {
    native <methods>;
}

# ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-keep class com.google.android.gms.cast.framework.media.WidgetControls { *; }

# ARCore
-keep class com.google.ar.core.** { *; }

# Keep models/POJOs if they are used for JSON serialization/reflection
-keep class com.vuforia.engine.native_sample.CloudConfig$* { *; }
-keep class com.vuforia.engine.native_sample.TargetMetadata { *; }
