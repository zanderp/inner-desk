-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# app_process launches PrivDaemon by class name; AIDL + reflection stay as-is.
-keep class dev.zanderp.innerdesk.** { *; }
-keep class dev.zanderp.innerdesk.IMirrorService$Stub { *; }
-keep class dev.zanderp.innerdesk.IMirrorService$Stub$Proxy { *; }

# Hidden API reflection (framework, not in our DEX).
-dontwarn android.view.IWindowManager
-dontwarn android.hardware.display.IDisplayManager
-dontwarn android.hardware.input.IInputManager
-dontwarn android.view.SurfaceControl
-dontwarn android.hardware.display.IVirtualDisplayCallback
-dontwarn android.hardware.display.VirtualDisplayConfig
-dontwarn android.media.projection.IMediaProjection
-dontwarn android.app.IActivityManager
-dontwarn com.android.internal.os.BinderInternal
-dontwarn android.os.ServiceManager
-dontwarn android.os.SystemProperties

# Shizuku
-keep class rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-keep class moe.shizuku.** { *; }
-dontwarn moe.shizuku.**

# Wireless ADB pairing (reflection + JNI + JCE providers).
-keep class io.github.muntashirakon.adb.** { *; }
-dontwarn io.github.muntashirakon.adb.**
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class * extends java.security.Provider { *; }

-dontwarn androidx.**
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**
