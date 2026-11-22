
# Rust + Slint UI (libfkui.so) calls these through JNI.
-keep class io.nekohasekai.sfa.bridge.SlintHost { native <methods>; }
# The client store (apps/android/native/src/store.rs) is reached through these natives.
-keepclasseswithmembernames class io.nekohasekai.sfa.database.CoreStore { native <methods>; }
-keepclassmembers class io.nekohasekai.sfa.bridge.BoxActivity { public void slintCall(long, java.lang.String, java.lang.String); }
-keep class com.google.androidgamesdk.** { *; }
-keep class dev.slint.android.** { *; }
