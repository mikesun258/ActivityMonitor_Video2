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

    // 去重缓存：保存已经包装过监听的RecyclerView，防止重复嵌套注册监听
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
        val pkg = lpparam.packageName
        if (pkg !in targetPackages) return

        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
        }

        hookRecyclerView(lpparam)

        if (pkg == "com.dragon.read") {
            hookDragonReadLayoutWatch(lpparam)
        }
    }

    private fun hookRecyclerView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvClass = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            XposedBridge.hookAllMethods(rvClass, "addOnScrollListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 修复崩溃核心1：非RecyclerView实例直接放行，不做任何处理（拦截ViewPager2调用进入）
                    val rvObj = param.thisObject
                    if (rvObj !is RecyclerView) return

                    val recyclerView = rvObj
                    // 修复重复嵌套：已经Hook过的RV直接退出，不再二次包装监听
                    if (hookedRvSet.contains(recyclerView)) return

                    val originListener = param.args[0] as? RecyclerView.OnScrollListener ?: return
                    // 只处理垂直线性布局（短视频上下滑），横向列表直接跳过
                    val layoutManager = recyclerView.layoutManager
                    if (layoutManager !is LinearLayoutManager || !layoutManager.orientation.equals(LinearLayoutManager.VERTICAL)) {
                        return
                    }

                    // 包装原始监听
                    val wrapListener = object : RecyclerView.OnScrollListener() {
                        private var lastVisiblePos = -1
                        private var firstIdle = true

                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            val lm = recyclerView.layoutManager as LinearLayoutManager
                            lastVisiblePos = lm.findFirstCompletelyVisibleItemPosition()
                            originListener.onScrolled(recyclerView, dx, dy)
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                if (firstIdle || lastVisiblePos != -1) {
                                    firstIdle = false
                                    sendBroadcast(recyclerView, lastVisiblePos, "scroll_switch")
                                }
                            }
                            originListener.onScrollStateChanged(recyclerView, newState)
                        }
                    }
                    // 替换入参里的原始监听为包装后的监听
                    param.args[0] = wrapListener
                    hookedRvSet.add(recyclerView)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "RV Hook Error", e)
            XposedBridge.log("$TAG RV Hook Failed: ${e.message}")
        }
    }

    private fun hookDragonReadLayoutWatch(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewGroupCls = lpparam.classLoader.loadClass("android.view.ViewGroup")
            XposedBridge.hookAllMethods(viewGroupCls, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val childView = param.args[0] as? ImageView ?: return
                    val parentView = param.thisObject as ViewGroup

                    // 限定只在Activity根布局下的视图才检测，减少全局无效回调
                    var topParent: View? = parentView
                    var frameCount = 0
                    repeat(8) {
                        topParent = topParent?.parent as? View
                        if (topParent is FrameLayout) frameCount++
                        // 找到DecorView直接终止遍历，避免无限向上查找
                        if (topParent.javaClass.name.contains("DecorView")) return
                    }

                    if (frameCount >= 4) {
                        sendBroadcast(childView, -1, "auto_next_episode")
                    }
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "DragonRead Hook Error", e)
            XposedBridge.log("$TAG DragonRead Hook Failed: ${e.message}")
        }
    }

    private fun sendBroadcast(view: View, position: Int, triggerType: String) {
        val now = System.currentTimeMillis()
        if (now - lastSendTime.get() < COOL_DOWN_MS) return
        lastSendTime.set(now)

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
