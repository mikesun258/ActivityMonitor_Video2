package com.mikesun258.activitymonitor.video

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
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
    // 对外广播Action，MD固定监听这个Action即可
    val BROADCAST_VIDEO_SWITCH = "com.mikesun258.activitymonitor.VIDEO_SWITCH"
    // 广播防抖冷却3秒
    private val COOL_DOWN_MS = 3000L
    private val lastSendTime = AtomicLong(0)
    private lateinit var mainHandler: Handler
    // 防止同一个RecyclerView重复添加监听
    private val hookedRvSet = ConcurrentHashMap.newKeySet<RecyclerView>()
    // 存储每个RV上次滚动dy值，区分上下滑
    private val rvLastDyMap = ConcurrentHashMap<RecyclerView, Int>()

    // 全部目标短剧App包名列表
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
        XposedBridge.log("[$TAG] 进入目标APP: $pkgName")
        if (!::mainHandler.isInitialized) mainHandler = Handler(Looper.getMainLooper())
        hookRecyclerViewSetLayoutManager(lpparam)
    }

    /**
     * 最优Hook入口：Hook RecyclerView.setLayoutManager 只捕获ViewPager内嵌的垂直瀑布流RV
     * 彻底解决之前findAndHookMethod编译报错，全程只用Xposed原生API，无任何编译异常
     */
    private fun hookRecyclerViewSetLayoutManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvCls = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            XposedBridge.hookAllMethods(rvCls, "setLayoutManager", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val rv = param.thisObject as RecyclerView
                    // 已Hook过直接跳过
                    if (hookedRvSet.contains(rv)) return
                    val layoutManager = param.args[0] ?: return
                    // 只捕获垂直LinearLayoutManager，横向标签RV/评论RV全部过滤
                    if (layoutManager !is LinearLayoutManager || layoutManager.orientation != LinearLayoutManager.VERTICAL) return
                    // 只保留ViewPager(番茄老式) / ViewPager2(抖音快手) 内部的RV，首页列表全部过滤
                    if (!isRecyclerViewInsideAnyViewPager(rv)) return

                    hookedRvSet.add(rv)
                    rvLastDyMap[rv] = 0
                    XposedBridge.log("[$TAG] 成功绑定短剧瀑布流RV实例:$rv")

                    // 绑定滚动监听
                    rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            rvLastDyMap[recyclerView] = dy
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            // 仅滚动静止时触发
                            if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                            val scrollDy = rvLastDyMap[recyclerView] ?: 0
                            // dy>0=手指向下滑=切下一集；dy<=0上滑回看不发广播
                            if (scrollDy <= 0) return
                            val lm = recyclerView.layoutManager as LinearLayoutManager
                            val visiblePos = lm.findFirstVisibleItemPosition()
                            // 延迟200ms等待item完全渲染完成再发广播
                            mainHandler.postDelayed({
                                sendVideoSwitchBroadcast(recyclerView, visiblePos, "scroll_switch")
                            }, 200)
                        }
                    })
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("[$TAG] RV Hook异常:${e.stackTraceToString()}")
        }
    }

    /**
     * 遍历父布局，同时兼容 androidx.viewpager.widget.ViewPager(番茄com.kylin.read) 和 ViewPager2(抖音快手)
     */
    private fun isRecyclerViewInsideAnyViewPager(rv: RecyclerView): Boolean {
        var parentView: View? = rv.parent as View
        var traverseDepth = 0
        // 最多向上遍历12层父布局，防止死循环
        while (parentView != null && traverseDepth < 12) {
            val clsName = parentView.javaClass.name
            if (clsName.startsWith("androidx.viewpager.widget.ViewPager")
                || clsName.startsWith("androidx.viewpager2.widget.ViewPager2")
            ) {
                return true
            }
            parentView = parentView.parent as? View
            traverseDepth++
        }
        return false
    }

    /**
     * 发送全局广播，附带包名、item位置、触发类型，MD接收后区分不同APP做不同点击逻辑
     */
    private fun sendVideoSwitchBroadcast(view: View, pos: Int, triggerType: String) {
        val nowTs = System.currentTimeMillis()
        if (nowTs - lastSendTime.get() < COOL_DOWN_MS) return
        lastSendTime.set(nowTs)

        mainHandler.post {
            val intent = Intent(BROADCAST_VIDEO_SWITCH).apply {
                putExtra("pkg_name", view.context.packageName)
                putExtra("item_pos", pos)
                putExtra("trigger_type", triggerType)
                flags = Intent.FLAG_INCLUDE_STOPPED_PACKAGES
            }
            view.context.sendBroadcast(intent)
            XposedBridge.log("[$TAG] 已发送切集广播 | 包名:${view.context.packageName} 条目位置:$pos 触发:$triggerType")
        }
    }
}
