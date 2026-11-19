# ============================================================================
# R8 / ProGuard 规则
#
# 🔴 本项目最大的风险点是 **Gson 反射**：所有接口/网页数据都是直接
#    `gson.fromJson(json, Model::class.java)`，字段名与 JSON key 一一对应。
#    R8 一旦把字段名混淆成 a/b/c，反序列化就会**全部得到 null**（不报错、不崩溃，
#    只是列表变成空 / 全空字段）—— 这种故障最难查，所以这里一律 keep 整个 data 包。
#
#    代价是 data 包里的类不会被混淆（收益略少一点），换的是确定性。
# ============================================================================

# ---------- 数据模型：字段名就是 JSON key，一个都不能改 ----------
-keep class com.example.jingcai.data.model.** { *; }
-keepclassmembers class com.example.jingcai.data.model.** { <fields>; }

# ---------- 本地快照：Payload 同样靠字段名反序列化 ----------
-keep class com.example.jingcai.data.repository.LiveSnapshotStore$Payload { *; }
-keepclassmembers class com.example.jingcai.data.repository.LiveSnapshotStore$Payload { <fields>; }

# ---------- Gson 泛型 ----------
# TypeToken 的匿名子类靠泛型签名拿类型，R8 默认会把 Signature 属性删掉。
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ---------- 枚举 ----------
# Gson 用 Enum.valueOf 按名字取值；混淆名字会让旧存档 / 旧接口值解析失败。
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- 序列化（部分模型实现了 Serializable） ----------
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ---------- 其余依赖 ----------
# OkHttp / jsoup / Compose 自带的 consumer rules 已经足够，不再重复声明。
# 需要排查「某个类被删了/被改名了」时，可临时打开下面这行：
# -printusage build/outputs/mapping/usage.txt
