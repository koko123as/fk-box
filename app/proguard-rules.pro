
# Rust + Slint UI (libfkui.so) calls these through JNI.
-keep class io.nekohasekai.sfa.bridge.SlintHost { native <methods>; }
-keepclassmembers class io.nekohasekai.sfa.bridge.BoxActivity { public void slintCall(long, java.lang.String, java.lang.String); }
-keep class com.google.androidgamesdk.** { *; }
-keep class SlintAndroidJavaHelper { *; }
-keep class SlintInputView { *; }
