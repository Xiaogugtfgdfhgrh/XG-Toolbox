# 小骨工具箱 R8 规则
# 序列化模型
-keepattributes Signature, *Annotation*, InnerClasses
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *; }
-keep,includedescriptorclasses class com.xg.toolbox.**$$serializer { *; }
-keepclassmembers class com.xg.toolbox.** {
    *** Companion;
}
-keepclasseswithmembers class com.xg.toolbox.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.**

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**

# 协程
-dontwarn kotlinx.coroutines.**

# OEM startup bridge (invoked by app_process as system uid)
-keep class com.xg.toolbox.core.rootops.OplusStartupDbHelper { public static void main(java.lang.String[]); }
# Reflection bridge used by core:device SerialChangeJournal into Hilt-generated component.
-keep class com.xg.toolbox.DaggerXgApp_HiltComponents_SingletonC { *; }
-keep class com.xg.toolbox.DaggerXgApp_HiltComponents_SingletonC$SingletonCImpl { *; }
-keep class com.xg.toolbox.core.data.SnapshotRepository { *; }
-keep class com.xg.toolbox.core.data.AuditRepository { *; }

# WorkManager instantiates persisted worker class names.
-keep class com.xg.toolbox.work.* extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Startup recovery is addressed by an explicit class-name contract.
-keep class com.xg.toolbox.startup.StartupRestoreReceiver { *; }
