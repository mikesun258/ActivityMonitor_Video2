package com.mikesun258.activitymonitor.video

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicLong

class VideoMonitor : IXposedHookLoadPackage {
    private val TAG = "VideoMonitor"
    // MacroDroid 接收的广播 Action
    private val BROADCAST_VIDEO_SWITCH = "com.mikesun258.activitymonitor.VIDEO_SWITCH"
    // 防抖冷却时间（3秒）
    private val COOL_DOWN_MS = 3000L
    private val lastSendTime = AtomicLong(0)
    // 核心修复：延迟初始化 Handler，避免 Zygote 预加载阶段 Looper 为空
    private lateinit var mainHandler: Handler

    // 目标监控包列表
    private val targetPackages = listOf(
        "com.bytedance.douyin",
        "com.bytedance.douyin.lite",
        "com.bytedance.douyin.extreme",
        "com.bytedance.douyin3",
        "com.bytedance.douyin2",
        "com.bytedance.douyinselected",
        "com.ik.mang",
        "com.ik.shortdrama",
        "com.hippo.drama",
        "com.kuaishou.nebula",
        "com.huolong.mangju",
        "com.kylin.read",
        "com.dragon.read"
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg !in targetPackages) return

        // 进程加载后初始化 Handler，此时主线程 Looper 已就绪，彻底解决 NPE
        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
        }

        // 通用 RecyclerView 滑动监听（全目标APP生效）
        hookRecyclerView(lpparam)

        // 番茄小说专属：监控布局新增ImageView，捕获自动下一集切换
        if (pkg == "com.dragon.read") {
            hookDragonReadLayoutWatch(lpparam)
        }
    }

    // RecyclerView 上下滑动切视频监听逻辑
    private fun hookRecyclerView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvClass = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            XposedBridge.hookAllMethods(rvClass, "addOnScrollListener", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val originListener = param.args[0] as RecyclerView.OnScrollListener?
                    val recyclerView = param.thisObject as RecyclerView

                    val wrapListener = object : RecyclerView.OnScrollListener() {
                        private var lastVisiblePos = -1
                        private var firstIdle = true

                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            val lm = recyclerView.layoutManager
                            if (lm is LinearLayoutManager) {
                                lastVisiblePos = lm.findFirstCompletelyVisibleItemPosition()
                            }
                            originListener?.onScrolled(recyclerView, dx, dy)
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                if (firstIdle || lastVisiblePos != -1) {
                                    firstIdle = false
                                    sendBroadcast(
                                        view = recyclerView,
                                        position = lastVisiblePos,
                                        triggerType = "scroll_switch"
                                    )
                                }
                            }
                            originListener?.onScrollStateChanged(recyclerView, newState)
                        }
                    }
                    recyclerView.addOnScrollListener(wrapListener)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "RV Hook Error", e)
            XposedBridge.log("$TAG RV Hook Failed: ${e.message}")
        }
    }

    // 番茄小说 布局树监听（补全版）
    private fun hookDragonReadLayoutWatch(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewGroupCls = lpparam.classLoader.loadClass("android.view.ViewGroup")
            XposedBridge.hookAllMethods(viewGroupCls, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val parentView = param.thisObject as View
                    val childView = param.args[0] as View

                    // 仅拦截新增的 ImageView（短剧视频载体）
                    if (childView !is ImageView) return

                    // 向上遍历父布局，统计 FrameLayout 嵌套层数
                    var currentParent: View? = parentView
                    var frameCount = 0
                    repeat(8) {
                        currentParent = currentParent?.parent as? View
                        if (currentParent is FrameLayout) frameCount++
                    }

                    // 嵌套 FrameLayout ≥ 4 层判定为新一集加载完成
                    if (frameCount >= 4) {
                        sendBroadcast(
                            view = childView,
                            position = -1,
                            triggerType = "auto_next_episode"
                        )
                    }
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "DragonRead Hook Error", e)
            XposedBridge.log("$TAG DragonRead Hook Failed: ${e.message}")
        }
    }

    // 发送广播（带防抖，主线程执行）
    private fun sendBroadcast(view: View, position: Int, triggerType: String) {
        val now = System.currentTimeMillis()
        // 防抖：冷却时间内直接返回
        if (now - lastSendTime.get() < COOL_DOWN_MS) return
        lastSendTime.set(now)

        // 切到主线程发送广播，避免子线程发送异常
        mainHandler.post {
            val intent = Intent(BROADCAST_VIDEO_SWITCH).apply {
                putExtra("pkg_name", view.context.packageName)
                putExtra("item_pos", position)
                putExtra("trigger_type", triggerType)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            view.context.sendBroadcast(intent)
            XposedBridge.log("$TAG Broadcast Sent | pkg:${view.context.packageName} type:$triggerType pos:$position")
        }
    }
}
