package com.mikesun258.activitymonitor.video;

import android.content.Context;
import android.content.Intent;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class VideoMonitor implements IXposedHookLoadPackage {
    private static final String LOG_TAG = "【$$$】";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.kylin.read".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(LOG_TAG + "目标应用进程已加载，模块启动 | 包名：" + lpparam.packageName);

        try {
            Class<?> recyclerViewClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView",
                    lpparam.classLoader
            );
            XposedBridge.log(LOG_TAG + "RecyclerView类加载成功");

            hookRecyclerViewScroll(recyclerViewClass);
            hookRecyclerViewAttach(recyclerViewClass);

        } catch (Throwable e) {
            XposedBridge.log(LOG_TAG + "模块初始化异常 | 信息：" + e.getMessage());
            XposedBridge.log(LOG_TAG + "异常堆栈：" + XposedBridge.getStackTraceString(e));
        }
    }

    private void hookRecyclerViewScroll(Class<?> recyclerViewClass) {
        XposedBridge.log(LOG_TAG + "开始Hook RecyclerView#onScrolled");
        try {
            XposedHelpers.findAndHookMethod(
                    recyclerViewClass,
                    "onScrolled",
                    int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int dx = (int) param.args[0];
                            int dy = (int) param.args[1];
                            XposedBridge.log(LOG_TAG + "onScrolled触发 | dx=" + dx + " dy=" + dy);

                            if (Math.abs(dy) < 5) {
                                return;
                            }
                            String direction = dy > 0 ? "down" : "up";
                            XposedBridge.log(LOG_TAG + "有效滑动 | 方向：" + direction);

                            Object recyclerView = param.thisObject;
                            Context context = (Context) XposedHelpers.callMethod(recyclerView, "getContext");
                            sendScrollBroadcast(context, direction);
                        }
                    }
            );
            XposedBridge.log(LOG_TAG + "RecyclerView#onScrolled Hook完成");
        } catch (Throwable e) {
            XposedBridge.log(LOG_TAG + "Hook onScrolled失败 | 原因：" + e.getMessage());
        }
    }

    private void hookRecyclerViewAttach(Class<?> recyclerViewClass) {
        XposedBridge.log(LOG_TAG + "开始Hook RecyclerView#onAttachedToWindow");
        try {
            XposedHelpers.findAndHookMethod(
                    recyclerViewClass,
                    "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object recyclerView = param.thisObject;
                            String className = recyclerView.getClass().getName();
                            XposedBridge.log(LOG_TAG + "RecyclerView已创建 | 全类名：" + className);
                        }
                    }
            );
            XposedBridge.log(LOG_TAG + "RecyclerView#onAttachedToWindow Hook完成");
        } catch (Throwable e) {
            XposedBridge.log(LOG_TAG + "Hook onAttachedToWindow失败 | 原因：" + e.getMessage());
        }
    }

    private void sendScrollBroadcast(Context context, String direction) {
        if (context == null) {
            XposedBridge.log(LOG_TAG + "发送广播失败：Context为空");
            return;
        }
        try {
            // 直接使用字符串常量，避免引用错误
            Intent intent = new Intent("com.mikesun258.activitymonitor.video.EVENT_SCROLL");
            intent.putExtra("direction", direction);
            intent.putExtra("timestamp", System.currentTimeMillis());
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            XposedBridge.log(LOG_TAG + "广播发送成功 | 方向=" + direction);
        } catch (Throwable e) {
            XposedBridge.log(LOG_TAG + "广播发送异常 | 信息：" + e.getMessage());
        }
    }
}
