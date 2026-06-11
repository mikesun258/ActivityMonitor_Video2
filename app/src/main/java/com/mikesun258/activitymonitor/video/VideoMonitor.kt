package com.mikesun258.activitymonitor.video

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class VideoMonitor : IXposedHookLoadPackage {
    private val TAG = "VideoMonitor"
    val BROADCAST_VIDEO_SWITCH = "com.mikesun258.activitymonitor.VIDEO_SWITCH"
    private val COOL_DOWN_MS = 3000L
    private val lastSendTime = AtomicLong(0)
    private lateinit var mainHandler: Handler
    // 已完成Hook的RV实例集合，防止重复添加监听
    private val hookedRvSet = ConcurrentHashMap.newKeySet<RecyclerView>()
    // 存储每个RV上次滑动方向
    private val rvLastDyMap = ConcurrentHashMap<RecyclerView, Int>()

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
        if (pkgName !in targetPackages) return
        XposedBridge.log("$TAG 开始Hook目标包:$pkgName")
        if (!::mainHandler.isInitialized) mainHandler = Handler(Looper.getMainLooper())
        hookRvSetLayoutManager(lpparam)
    }

    // 最优入口：Hook setLayoutManager，精准捕获瀑布流RV
    private fun hookRvSetLayoutManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvCls = XposedHelpers.findAndHookMethod(
                RecyclerView::class.java,
                lpparam.classLoader,
                "setLayoutManager",
                androidx.recyclerview.widget.RecyclerView.LayoutManager::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val rv = param.thisObject as RecyclerView
                        if (hookedRvSet.contains(rv)) return
                        val lm = param.args[0] as? LinearLayoutManager ?: return
                        // 只过滤垂直线性布局
                        if (lm.orientation != LinearLayoutManager.VERTICAL) return
                        // 核心：判断是否嵌套在ViewPager/ViewPager2内（番茄短剧容器）
                        if (!isInAnyViewPager(rv)) return

                        XposedBridge.log("$TAG 捕获短剧瀑布流RV:$rv")
                        hookedRvSet.add(rv)
                        rvLastDyMap[rv] = 0

                        // 添加专属滚动监听
                        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                rvLastDyMap[recyclerView] = dy
                            }

                            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                                val dy = rvLastDyMap[recyclerView] ?: 0
                                // 只向下滑动才触发切集广播
                                if (dy <= 0) return
                                val llm = recyclerView.layoutManager as LinearLayoutManager
                                val currPos = llm.findFirstVisibleItemPosition()
                                // 延迟200ms等待item完全渲染完毕
                                mainHandler.postDelayed({
                                    sendSwitchBroadcast(recyclerView, currPos, "scroll_switch")
                                }, 200)
                            }
                        })
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("$TAG hookRvSetLayoutManager异常:${e.stackTraceToString()}")
        }
    }

    // 兼容两种ViewPager 老式androidx.viewpager.widget + 新版ViewPager2
    private fun isInAnyViewPager(rv: RecyclerView): Boolean {
        var parent: View? = rv.parent as View
        var depth = 0
        while (parent != null && depth < 12) {
            val clsName = parent.javaClass.name
            if (clsName.startsWith("androidx.viewpager.widget.ViewPager")
                || clsName.startsWith("androidx.viewpager2.widget.ViewPager2")
            ) {
                return true
            }
            parent = parent.parent as? View
            depth++
        }
        return false
    }

    // 防抖发送广播
    fun sendSwitchBroadcast(view: View, pos: Int, triggerType: String) {
        val now = System.currentTimeMillis()
        if (now - lastSendTime.get() < COOL_DOWN_MS) return
        lastSendTime.set(now)
        mainHandler.post {
            val intent = Intent(BROADCAST_VIDEO_SWITCH).apply {
                putExtra("pkg_name", view.context.packageName)
                putExtra("item_pos", pos)
                putExtra("trigger_type", triggerType)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            view.context.sendBroadcast(intent)
            XposedBridge.log("$TAG 发送切集广播 pkg:${view.context.packageName} pos:$pos type:$triggerType")
        }
    }
}
