# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.mobilegh.data.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JNA 的 JNI 与反射入口（官方 Android ProGuard 规则）。
# AWT 仅属于 Android 不提供的可选桌面集成；Smart HTTP 与 sealed box 不调用它。
-dontwarn java.awt.*
# native initIDs 按字段名查找 Pointer.peer，R8 不得改名或删除这些入口。
-keep class com.sun.jna.* { *; }
-keep class * extends com.sun.jna.* { *; }
-keepclassmembers class * extends com.sun.jna.* { public *; }

# JGit 的可选桌面能力：Android 不提供 JMX、ProcessHandle 或 Kerberos。
# Smart HTTP 迁移路径不调用这些 API；仅抑制 R8 缺失类检查，不添加 Android stub。
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.ManagementFactory
-dontwarn javax.management.InstanceAlreadyExistsException
-dontwarn javax.management.InstanceNotFoundException
-dontwarn javax.management.JMException
-dontwarn javax.management.MBeanRegistrationException
-dontwarn javax.management.MBeanServer
-dontwarn javax.management.MalformedObjectNameException
-dontwarn javax.management.MXBean
-dontwarn javax.management.NotCompliantMBeanException
-dontwarn javax.management.ObjectInstance
-dontwarn javax.management.ObjectName
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid

# SVG 徽章渲染（AndroidSVG）
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
