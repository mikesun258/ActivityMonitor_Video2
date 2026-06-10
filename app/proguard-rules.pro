# Xposed 模块保留入口类不被混淆
-keep class de.robv.android.xposed.** { *; }
-keep class com.mikesun258.activitymonitor.** { *; }
-keep interface de.robv.android.xposed.** { *; }

# 基础默认规则
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-keepattributes *Annotation*,Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable
-dontwarn **

