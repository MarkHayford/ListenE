# ListenE release R8 / ProGuard rules
#
# 本应用栈：Kotlin + Jetpack Compose + OkHttp + kotlinx.serialization(JsonElement DSL) 解析。
# 当前仅用 JsonElement DSL（不含 @Serializable 反射模型），运行时库自带 consumer 规则即可；
# Compose、OkHttp、desugar 等依赖也自带 consumer 规则，R8 会自动合并。
# 下方 kotlinx.serialization 段为官方标准 keep 规则，作为未来引入 @Serializable 模型时的安全兜底。

# ---- 崩溃栈可读性：保留行号、隐藏源文件名（release 崩溃可定位到行） ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- 网络栈兜底（库自带规则之外的 dontwarn，避免可选依赖缺失告警） ----
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ---- 运行时读取的 BuildConfig 字段（API_BASE_URL 等） ----
-keep class com.c0d3c.listene.BuildConfig { *; }

# ---- kotlinx.serialization 官方标准 keep 规则（@Serializable 模型的安全兜底） ----
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
