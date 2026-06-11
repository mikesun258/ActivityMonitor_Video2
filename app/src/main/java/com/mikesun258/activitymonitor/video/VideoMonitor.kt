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
    // MD接收的广播Action
    private val BROADCAST_VIDEO_SWITCH = "com.mikesun258.activitymonitor.VIDEO_SWITCH"
    // 防抖冷却 3秒
    private val COOL_DOWN_MS = 3000L
    private val lastSendTime = AtomicLong(0)
    // 修复NPE核心：延迟初始化Handler，不在类构造阶段实例化
    private lateinit var mainHandler: Handler

    // 目标监控APP包名列表
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

        // 进程加载后再初始化主线程Handler，此时主线程Looper已就绪，彻底解决空指针崩溃
        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
        }

        // 通用RecyclerView上下滑动切视频监听（全目标APP生效）
        hookRecyclerView(lpparam)

        // 番茄小说专属：监控布局新增ImageView，捕获自动下一集切换事件
        if (pkg == "com.dragon.read") {
            hookDragonReadLayoutWatch(lpparam)
        }
    }

    // RecyclerView滚动监听 完整逻辑
    private fun hookRecyclerView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvClass = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            XposedBridge.hookAllMethods(rvClass, "addOnScrollListener", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val originListener = param.args[0] as RecyclerView.OnScrollListener?
                    val recyclerView = param.thisObject as RecyclerView

                    val wrapScrollListener = object : RecyclerView.OnScrollListener() {
                        private var lastVisiblePos = -1
                        private var firstIdleFlag = true

                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            val layoutManager = recyclerView.layoutManager
                            if (layoutManager is LinearLayoutManager) {
                                lastVisiblePos = layoutManager.findFirstCompletelyVisibleItemPosition()
                            }
                            originListener?.onScrolled(recyclerView, dx, dy)
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            // 滚动停止时触发广播
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                if (firstIdleFlag || lastVisiblePos != -1) {
                                    firstIdleFlag = false
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
                    recyclerView.addOnScrollListener(wrapScrollListener)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "RecyclerView Hook Failed", e)
            XposedBridge.log("$TAG RV Hook Err: ${e.message}")
        }
    }

    // 番茄小说 布局树监听 完整补全版
    private fun hookDragonReadLayoutWatch(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewGroupCls = lpparam.classLoader.loadClass("android.view.ViewGroup")
            XposedBridge.hookAllMethods(viewGroupCls, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val parentView = param.thisObject as View
                    val childView = param.args[0] as View

                    // 仅拦截新增ImageView（短剧视频画面载体）
                    if (childView !is ImageView) return

                    // 向上遍历父布局，统计FrameLayout嵌套层数
                    var currentParent: View? = parentView
                    var frameLayoutCount = 0
                    repeat(8) {
                        currentParent = currentParent?.parent as? View
                        if (currentParent is FrameLayout) frameLayoutCount++
                    }

                    // 嵌套FrameLayout≥4层 判定为新一集视频加载完成
                    if (frameLayoutCount >= 4) {
                        sendBroadcast(
                            view = childView,
                            position = -1,
                            triggerType = "auto_next_episode"
                        )
                    }
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "DragonRead Layout Hook Failed", e)
            XposedBridge.log("$TAG DragonRead Hook Err: ${e.message}")
        }
    }

    /**
     * 发送全局广播核心方法（带防抖，防止频繁触发）
     * @param view 触发源View
     * @param position RV当前可见位置
     * @param triggerType 触发类型 scroll_switch / auto_next_episode
     */
    private fun sendBroadcast(view: View, position: Int, triggerType: String) {
        val now = System.currentTimeMillis()
        // 防抖拦截：冷却时间内直接返回
        if (now - lastSendTime.get() < COOL_DOWN_MS) return
        lastSendTime.set(now)

        // 切到主线程发送广播（子线程发送广播存在稳定性问题）
        mainHandler.post {
            val intent = Intent(BROADCAST_VIDEO_SWITCH).apply {
                putExtra("pkg_name", view.context.packageName)
                putExtra("item_pos", position)
                putExtra("trigger_type", triggerType)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            view.context.sendBroadcast(intent)
            XposedBridge.log("$TAG Send Switch Broadcast | pkg:${view.context.packageName} type:$triggerType pos:$position")
        }
    }
}
