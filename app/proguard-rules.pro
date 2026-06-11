# Xposed模块混淆保留规则
-keep class de.robv.android.xposed.** {*;}
-keep interface de.robv.android.xposed.** {*;}
-keep class com.mikesun258.activitymonitor.video.VideoMonitor {*;}
-keepnames class * implements de.robv.android.xposed.IXposedHookLoadPackage
-keepattributes *Annotation*
-keepattributes Signature
