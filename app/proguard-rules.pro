-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep our app classes that use reflection
-keep class dev.zanderp.innerdesk.** { *; }
-keep class dev.zanderp.innerdesk.MirrorUserService { *; }
-keep class dev.zanderp.innerdesk.IMirrorService { *; }
-keep class dev.zanderp.innerdesk.IMirrorService$Stub { *; }
-keep class dev.zanderp.innerdesk.IMirrorService$Stub$Proxy { *; }

# Keep AIDL / hidden API reflection targets
-dontwarn android.view.IWindowManager
-dontwarn android.hardware.display.IDisplayManager
-dontwarn android.hardware.input.IInputManager
-dontwarn android.view.SurfaceControl

# Shizuku
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class moe.shizuku.** { *; }
-dontwarn moe.shizuku.**

# AndroidX
-dontwarn androidx.**
