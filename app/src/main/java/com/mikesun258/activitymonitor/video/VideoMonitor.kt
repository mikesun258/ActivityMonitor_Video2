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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class VideoMonitor : IXposedHookLoadPackage {
    private val TAG = "VideoMonitor"
    private val BROADCAST_VIDEO_SWITCH = "com.mikesun258.activitymonitor.VIDEO_SWITCH"
    private val COOL_DOWN_MS = 3000L
    private val lastSendTime = AtomicLong(0)
    private lateinit var mainHandler: Handler
    private val hookedRvSet = ConcurrentHashMap.newKeySet<RecyclerView>()

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
        val pkgName = lpparam.packageName
        val processName = lpparam.processName
        // 标记1：确认模块是否进入handleLoadPackage
        XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG handleLoadPackage 触发 | 包名:$pkgName 进程名:$processName")

        if (pkgName !in targetPackages) {
            XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 非目标包，跳过 | 包名:$pkgName")
            return
        }
        // 标记2：确认进入目标包
        XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 进入目标包: $pkgName 进程:$processName")

        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
            XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 主线程Handler初始化完成")
        }

        hookRecyclerViewScroll(lpparam)

        if (pkgName == "com.dragon.read") {
            hookDragonReadAutoNext(lpparam)
        }
    }

    private fun hookRecyclerViewScroll(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvCls = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG RecyclerView类加载成功: ${rvCls.name}")

            XposedBridge.hookAllMethods(rvCls, "addOnScrollListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val targetObj = param.thisObject
                    if (targetObj !is RecyclerView) {
                        return
                    }
                    val rv = targetObj
                    XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG addOnScrollListener被调用，RV实例: $rv")

                    if (hookedRvSet.contains(rv)) {
                        XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 该RecyclerView已处理过，跳过")
                        return
                    }

                    if (isRvInsideViewPager2(rv)) {
                        XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 已过滤ViewPager2内嵌RecyclerView")
                        return
                    }

                    val originListener = param.args[0] as? RecyclerView.OnScrollListener ?: return
                    val layoutManager = rv.layoutManager
                    if (layoutManager !is LinearLayoutManager || layoutManager.orientation != LinearLayoutManager.VERTICAL) {
                        XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 非垂直LinearLayoutManager，跳过 | 方向:${if (layoutManager is LinearLayoutManager) layoutManager.orientation else "非线性布局"}")
                        return
                    }

                    XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 成功包装RecyclerView监听")
                    val wrapScrollListener = object : RecyclerView.OnScrollListener() {
                        private var lastCompletePos = -1
                        private var firstIdleFlag = true

                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            val lm = recyclerView.layoutManager as LinearLayoutManager
                            lastCompletePos = lm.findFirstCompletelyVisibleItemPosition()
                            originListener.onScrolled(recyclerView, dx, dy)
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                if (firstIdleFlag || lastCompletePos != -1) {
                                    firstIdleFlag = false
                                    sendSwitchBroadcast(recyclerView, lastCompletePos, "scroll_switch")
                                }
                            }
                            originListener.onScrollStateChanged(recyclerView, newState)
                        }
                    }
                    param.args[0] = wrapScrollListener
                    hookedRvSet.add(rv)
                }
            })
        } catch (e: Throwable) {
            val errMsg = e.stackTraceToString()
            XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG RecyclerView Hook异常: $errMsg")
        }
    }

    private fun isRvInsideViewPager2(rv: RecyclerView): Boolean {
        var parentView: View? = rv.parent as? View
        var traverseDepth = 0
        while (parentView != null && traverseDepth < 10) {
            val clsFullName = parentView.javaClass.name
            if (clsFullName.startsWith("androidx.viewpager2")) {
                return true
            }
            parentView = parentView.parent as? View
            traverseDepth++
        }
        return false
    }

    private fun hookDragonReadAutoNext(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewGroupCls = lpparam.classLoader.loadClass("android.view.ViewGroup")
            XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG ViewGroup类加载成功，番茄小说自动下一集Hook已开启")

            XposedBridge.hookAllMethods(viewGroupCls, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val child = param.args[0] as? ImageView ?: return
                    val parentVg = param.thisObject as ViewGroup

                    var currParent: View? = parentVg
                    var frameLayerCount = 0
                    repeat(8) {
                        currParent = currParent?.parent as? View
                        if (currParent?.javaClass?.name?.contains("DecorView") == true) return
                        if (currParent is FrameLayout) frameLayerCount++
                    }
                    if (frameLayerCount >= 4) {
                        sendSwitchBroadcast(child, -1, "auto_next_episode")
                    }
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 番茄小说Hook异常: ${e.stackTraceToString()}")
        }
    }

    private fun sendSwitchBroadcast(view: View, pos: Int, triggerType: String) {
        val currentTs = System.currentTimeMillis()
        if (currentTs - lastSendTime.get() < COOL_DOWN_MS) {
            XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 防抖冷却中，跳过广播发送")
            return
        }
        lastSendTime.set(currentTs)

        mainHandler.post {
            val intent = Intent(BROADCAST_VIDEO_SWITCH).apply {
                putExtra("pkg_name", view.context.packageName)
                putExtra("item_pos", pos)
                putExtra("trigger_type", triggerType)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            view.context.sendBroadcast(intent)
            XposedBridge.log("【¥¥¥¥¥¥¥¥¥¥¥¥】$TAG 广播已发送 | 包名:${view.context.packageName} 类型:$triggerType 位置:$pos")
        }
    }
}
