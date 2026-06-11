package com.mikesun258.activitymonitor.video

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
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
    // 沿用你原有广播Action，MD直接接收这个Action即可
    private val BROADCAST_VIDEO_SWITCH = "com.mikesun258.activitymonitor.VIDEO_SWITCH"
    // 防抖冷却间隔 3000ms，避免短时间多次发送广播
    private val COOL_DOWN_MS = 3000L
    private val lastSendTime = AtomicLong(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    // 原有短视频包 + 新增番茄小说com.dragon.read
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
        "com.dragon.read" // 番茄小说（你的目标APP）
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg !in targetPackages) return

        // 原有逻辑：所有目标包都保留RecyclerView滑动切视频监听（抖音/快手/列表式短剧）
        hookRecyclerView(lpparam)

        // 番茄小说专属：监听布局树变化，识别【下一集自动加载完成】触发广播
        if (pkg == "com.dragon.read") {
            hookDragonReadLayoutWatch(lpparam)
        }
    }

    // 原有RecyclerView滑动监听（上下滑动切视频触发广播，完全保留不动）
    private fun hookRecyclerView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvClass = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            XposedBridge.hookAllMethods(rvClass, "addOnScrollListener", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val originListener = param.args[0] as RecyclerView.OnScrollListener?
                    val recyclerView = param.thisObject as RecyclerView

                    val newListener = object : RecyclerView.OnScrollListener() {
                        private var lastPos = -1
                        private var firstIdle = true

                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            val lm = recyclerView.layoutManager
                            if (lm is LinearLayoutManager) {
                                lastPos = lm.findFirstCompletelyVisibleItemPosition()
                            }
                            originListener?.onScrolled(recyclerView, dx, dy)
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                if (firstIdle || lastPos != -1) {
                                    firstIdle = false
                                    sendBroadcast(
                                        view = recyclerView,
                                        position = lastPos,
                                        triggerType = "scroll_switch" // 滑动切换视频标记
                                    )
                                }
                            }
                            originListener?.onScrollStateChanged(recyclerView, newState)
                        }
                    }
                    recyclerView.addOnScrollListener(newListener)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "RV Hook Error", e)
        }
    }

    /**
     * 番茄小说专属：监听全局View树，短剧播放页下一集ImageView新增时触发（一集播完自动切下一集）
     * 原理：你截图里视频载体是多层FrameLayout包裹的ImageView，新一集加载时会新增ImageView节点
     */
    private fun hookDragonReadLayoutWatch(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook ViewGroup添加View，监控全局布局新增View
            val viewGroupCls = lpparam.classLoader.loadClass("android.view.ViewGroup")
            XposedBridge.hookAllMethods(viewGroupCls, "addView", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val parentView = param.thisObject as View
                    val addChild = param.args[0] as View

                    // 条件1：新增的是ImageView（短剧视频画面载体）
                    if (addChild !is ImageView) return
                    // 条件2：父布局是多层FrameLayout嵌套（匹配你截图的层级结构）
                    var tmpParent: View? = parentView
                    var frameLayerCount = 0
                    repeat(8) { // 最多向上遍历8层父布局（匹配你截图里多层FrameLayout嵌套）
                        tmpParent = tmpParent?.parent as? View
                        if (tmpParent is FrameLayout) frameLayerCount++
                    }
                    // 层级>=4层FrameLayout = 判定为短剧视频容器的ImageView = 新一集开始播放
                    if (frameLayerCount >= 4) {
                        sendBroadcast(
                            view = parentView,
                            position = -2, // -2代表番茄自动切下一集
                            triggerType = "auto_next_episode" // 自动下一集标记
                        )
                    }
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "DragonRead Layout Watch Hook Err", e)
        }
    }

    /**
     * 统一发送广播方法，新增防抖+携带触发类型
     * @param triggerType scroll_switch=手动滑动切视频  auto_next_episode=番茄自动播放下一集
     */
    private fun sendBroadcast(view: View, position: Int, triggerType: String) {
        val now = System.currentTimeMillis()
        // 防抖冷却判断，短时间重复触发直接拦截
        if (now - lastSendTime.get() < COOL_DOWN_MS) return
        lastSendTime.set(now)

        mainHandler.post {
            val intent = Intent(BROADCAST_VIDEO_SWITCH).apply {
                putExtra("pkg_name", view.context.packageName)
                putExtra("video_position", position)
                putExtra("view_id", view.id)
                putExtra("trigger_type", triggerType) // 区分是滑动还是自动下一集
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                setPackage("com.arlosoft.macrodroid") // 定向只发给MacroDroid，避免系统广播泛滥
            }
            view.context.sendBroadcast(intent)
            Log.d(TAG, "发送视频切换广播 | pkg:${view.context.packageName} pos:$position type:$triggerType")
        }
    }
}
