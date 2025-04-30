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

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# BLE és kritikus osztályok megtartása
-keep class com.jzolee.vibrationmonitor.ble.** { *; }
-keep class no.nordicsemi.android.ble.** { *; }

# Timber megtartása
-keep class timber.log.** { *; }

# MPAndroidChart
-keep class com.github.mikephil.charting.** { *; }
-dontwarn com.github.mikephil.charting.**

# CSV kezelés
-keep class org.apache.commons.io.** { *; }

# Application osztály megtartása
-keep public class com.jzolee.vibrationmonitor.VibrationMonitorApp { *; }