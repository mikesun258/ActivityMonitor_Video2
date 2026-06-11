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
    // 防抖冷却 3s
    private val COOL_DOWN_MS = 3000L
    private val lastSendTime = AtomicLong(0)
    private lateinit var mainHandler: Handler
    // 已处理过的RV缓存，防止重复包裹监听
    private val hookedRvSet = ConcurrentHashMap.newKeySet<RecyclerView>()

    // 目标监控App包名列表
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

        // 主线程初始化Handler，规避zygote阶段Looper未就绪NPE
        if (!::mainHandler.isInitialized) {
            mainHandler = Handler(Looper.getMainLooper())
        }

        hookRecyclerViewScroll(lpparam)
        // 番茄小说专属自动下一集监听
        if (pkgName == "com.dragon.read") {
            hookDragonReadAutoNext(lpparam)
        }
    }

    /**
     * Hook RecyclerView.addOnScrollListener
     * 过滤：非RV、VP2内嵌RV、横向RV、GridRV 全部跳过
     * 只捕获上下滑动切item事件，屏蔽VP2左右翻页onPageScrolled
     */
    private fun hookRecyclerViewScroll(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvCls = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            XposedBridge.hookAllMethods(rvCls, "addOnScrollListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // 1. 非RecyclerView实例直接放行，彻底解决ClassCast崩溃
                    val targetObj = param.thisObject
                    if (targetObj !is RecyclerView) return
                    val rv = targetObj

                    // 2. 已经处理过的RV直接退出，防止多层监听嵌套
                    if (hookedRvSet.contains(rv)) return

                    // 3. 过滤ViewPager2内部的RV（VP2的onPageScrolled全部拦截）
                    if (isRvInsideViewPager2(rv)) {
                        XposedBridge.log("$TAG 已过滤ViewPager2内嵌RecyclerView")
                        return
                    }

                    // 4. 取出原始滚动监听，空则跳过
                    val originListener = param.args[0] as? RecyclerView.OnScrollListener ?: return
                    val layoutManager = rv.layoutManager
                    // 只保留垂直线性布局（短视频上下滑列表）
                    if (layoutManager !is LinearLayoutManager || layoutManager.orientation != LinearLayoutManager.VERTICAL) {
                        return
                    }

                    // 包装原始监听，接管滚动空闲事件
                    val wrapScrollListener = object : RecyclerView.OnScrollListener() {
                        private var lastCompletePos = -1
                        private var firstIdleFlag = true

                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            val lm = recyclerView.layoutManager as LinearLayoutManager
                            lastCompletePos = lm.findFirstCompletelyVisibleItemPosition()
                            // 原样传递原始监听回调
                            originListener.onScrolled(recyclerView, dx, dy)
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            // 滚动停止时触发切视频广播
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                if (firstIdleFlag || lastCompletePos != -1) {
                                    firstIdleFlag = false
                                    sendSwitchBroadcast(recyclerView, lastCompletePos, "scroll_switch")
                                }
                            }
                            originListener.onScrollStateChanged(recyclerView, newState)
                        }
                    }
                    // 替换入参为包装后的监听
                    param.args[0] = wrapScrollListener
                    hookedRvSet.add(rv)
                }
            })
        } catch (e: Throwable) {
            val errMsg = e.stackTraceToString()
            XposedBridge.log("$TAG RecyclerView Hook异常: $errMsg")
        }
    }

    /**
     * 递归向上遍历父布局，判断当前RV是否属于ViewPager2内部（用来过滤onPageScrolled）
     * @return true=VP2内嵌RV(跳过) false=普通短视频RV(保留)
     */
    private fun isRvInsideViewPager2(rv: RecyclerView): Boolean {
        var parentView: View? = rv.parent as? View
        var traverseDepth = 0
        // 最多向上遍历10层，避免死循环
        while (parentView != null && traverseDepth < 10) {
            val clsFullName = parentView.javaClass.name
            // VP2特征包名前缀
            if (clsFullName.startsWith("androidx.viewpager2")) {
                return true
            }
            parentView = parentView.parent as? View
            traverseDepth++
        }
        return false
    }

    /**
     * 番茄小说 com.dragon.read 监听新增ImageView判定自动下一集
     */
    private fun hookDragonReadAutoNext(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val viewGroupCls = lpparam.classLoader.loadClass("android.view.ViewGroup")
            XposedBridge.hookAllMethods(viewGroupCls, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val child = param.args[0] as? ImageView ?: return
                    val parentVg = param.thisObject as ViewGroup

                    var currParent: View? = parentVg
                    var frameLayerCount = 0
                    repeat(8) {
                        currParent = currParent?.parent as? View
                        // 遍历到DecorView根布局直接终止，减少遍历开销
                        if (currParent?.javaClass?.name?.contains("DecorView") == true) return
                        if (currParent is FrameLayout) frameLayerCount++
                    }
                    // FrameLayout嵌套≥4判定为新一集视频加载完成
                    if (frameLayerCount >= 4) {
                        sendSwitchBroadcast(child, -1, "auto_next_episode")
                    }
                }
            })
        } catch (e: Throwable) {
            XposedBridge.log("$TAG 番茄小说Hook异常: ${e.stackTraceToString()}")
        }
    }

    /**
     * 发送切视频广播 带防抖、主线程发送
     */
    private fun sendSwitchBroadcast(view: View, pos: Int, triggerType: String) {
        val currentTs = System.currentTimeMillis()
        // 防抖冷却拦截
        if (currentTs - lastSendTime.get() < COOL_DOWN_MS) return
        lastSendTime.set(currentTs)

        mainHandler.post {
            val intent = Intent(BROADCAST_VIDEO_SWITCH).apply {
                putExtra("pkg_name", view.context.packageName)
                putExtra("item_pos", pos)
                putExtra("trigger_type", triggerType)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            view.context.sendBroadcast(intent)
            XposedBridge.log("$TAG 广播已发送 | 包名:${view.context.packageName} 类型:$triggerType 位置:$pos")
        }
    }
}
