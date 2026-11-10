# ---- Kotlin / Compose ----
-dontwarn org.jetbrains.annotations.**
-keepclassmembers class kotlin.Metadata { public <methods>; }

# ---- Hilt ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper

# ---- Room ----
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# ---- ZXing（二维码生成/识别走反射路径，必须保留）----
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---- 本项目协议栈：服务器/编解码用到的反射与序列化点 ----
-keep class com.pocketserver.net.mdns.** { *; }
-keep class com.pocketserver.data.model.** { *; }

# ---- 协程 ----
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
