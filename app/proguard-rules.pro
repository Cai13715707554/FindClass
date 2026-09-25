# 楼栋数据用 kotlinx.serialization 生成的序列化器通过反射查找，
# 需要保留 @Serializable 类的合成 Companion 与 serializer()。
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.school.nav.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.school.nav.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.school.nav.core.model.**$$serializer { *; }

# 高德 SDK 是完全反射调用（见 AmapLocationSource），必须整体保留。
# 未接入 SDK 时这些规则不会匹配任何类，无副作用。
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.api.**

# 定位相关的回调接口由动态代理实现
-keep interface com.amap.api.location.AMapLocationListener { *; }

# 泛型签名信息用于 kotlinx.serialization 与反射
-keepattributes Signature, Exceptions
