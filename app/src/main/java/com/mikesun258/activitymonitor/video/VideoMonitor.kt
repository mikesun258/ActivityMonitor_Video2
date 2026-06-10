package com.mikesun258.activitymonitor.video;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class VideoMonitor implements IXposedHookLoadPackage {
    // 广播Action常量，需和MacroDroid内配置完全一致
    private static final String ACTION_VIDEO_SCROLL = "com.mikesun258.activitymonitor.video.EVENT_SCROLL";
    private static final String EXTRA_DIRECTION = "direction";
    private static final String EXTRA_TIMESTAMP = "timestamp";
    // 全局日志前缀标识
    private static final String LOG_TAG = "【$$$】";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 仅作用于目标包名
        if (!"com.kylin.read".equals(lpparam.packageName)) {
            return;
        }
        XposedBridge.log(LOG_TAG + "目标应用进程已加载，进入handleLoadPackage | 包名：" + lpparam.packageName);

        try {
            // 查找RecyclerView类（通过类名动态加载，不依赖编译期导入）
            Class<?> recyclerViewClass = XposedHelpers.findClass(
                    "androidx.recyclerview.widget.RecyclerView",
                    lpparam.classLoader
            );
            XposedBridge.log(LOG_TAG + "RecyclerView类加载成功");
            // 挂载滚动Hook
            hookRecyclerViewOnScrolled(recyclerViewClass);
            // 挂载控件创建监听Hook
            hookRecyclerViewAttach(recyclerViewClass);
        } catch (Throwable e) {
            XposedBridge.log(LOG_TAG + "模块初始化整体异常 | 异常信息：" + e.getMessage());
            XposedBridge.log(LOG_TAG + "异常堆栈：" + XposedBridge.getStackTraceString(e));
        }
    }

    /** Hook RecyclerView滚动回调 onScrolled */
    private void hookRecyclerViewOnScrolled(Class<?> recyclerViewClass) {
        XposedBridge.log(LOG_TAG + "开始执行 RecyclerView onScrolled 方法Hook");
        try {
            XposedHelpers.findAndHookMethod(recyclerViewClass, "onScrolled", int.class, int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    int dx = (int) param.args[0];
                    int dy = (int) param.args[1];
                    XposedBridge.log(LOG_TAG + "onScrolled触发 | dx=" + dx + " dy=" + dy);

                    // 过滤微小抖动滚动
                    if (Math.abs(dy) < 5) {
                        return;
                    }
                    String direction = dy > 0 ? "down" : "up";
                    XposedBridge.log(LOG_TAG + "有效滑动判定 | 方向：" + direction);

                    // 动态获取Context，避免编译期类型依赖
                    Object recyclerViewObj = param.thisObject;
                    Context ctx = (Context) XposedHelpers.callMethod(recyclerViewObj, "getContext");
                    sendScrollBroadcast(ctx, direction);
                }
            });
            XposedBridge.log(LOG_TAG + "RecyclerView onScrolled Hook 挂载完成");
        } catch (Throwable e) {
            XposedBridge.log(LOG_TAG + "Hook onScrolled失败 | 原因：" + e.getMessage());
        }
    }

    /** Hook RecyclerView挂载到窗口回调，确认控件是否被创建 */
    private void hookRecyclerViewAttach(Class<?> recyclerViewClass) {
        XposedBridge.log(LOG_TAG + "开始执行 RecyclerView onAttachedToWindow 方法Hook");
        try {
            XposedHelpers.findAndHookMethod(recyclerViewClass, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object recyclerViewObj = param.thisObject;
                    String className = recyclerViewObj.getClass().getName();
                    XposedBridge.log(LOG_TAG + "RecyclerView实例创建完成 | 全类名：" + className);
                }
            });
            XposedBridge.log(LOG_TAG + "RecyclerView onAttachedToWindow Hook 挂载完成");
        } catch (Throwable e) {
            XposedBridge.log(LOG_TAG + "Hook onAttachedToWindow失败 | 原因：" + e.getMessage());
        }
    }

    /** 发送滑动广播至MacroDroid */
    private void sendScrollBroadcast(Context context, String direction) {
        if (context == null) {
            XposedBridge.log(LOG_TAG + "发送广播失败：Context为空");
            return;
        }
        try {
            Intent intent = new Intent(ACTION_VIDEO_SCROLL);
            intent.putExtra(EXTRA_DIRECTION, direction);
            intent.putExtra(EXTRA_TIMESTAMP, System.currentTimeMillis());
            intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            context.sendBroadcast(intent);
            XposedBridge.log(LOG_TAG + "广播发送成功 | Action=" + ACTION_VIDEO_SCROLL + " 滑动方向=" + direction);
        } catch (Throwable e) {
            XposedBridge.log(LOG_TAG + "广播发送异常 | 异常信息：" + e.getMessage());
        }
    }
}
