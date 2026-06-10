package com.mikesun258.activitymonitor.video

import android.content.Intent
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class VideoMonitor : IXposedHookLoadPackage {
    private val TAG = "VideoMonitor"
    private val BROADCAST_VIDEO_SWITCH = "com.mikesun258.activitymonitor.VIDEO_SWITCH"
    // 颜色码 + 自定义前缀
    private val RED = "\u001B[31m"
    private val RESET = "\u001B[0m"
    private val LOG_PREFIX = "【我要的】"

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
        "com.kylin.read"
    )

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName in targetPackages) {
            hookRecyclerView(lpparam)
            XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] 已挂载: ${lpparam.packageName}${RESET}")
        }
    }

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
                            if (lm is androidx.recyclerview.widget.LinearLayoutManager) {
                                lastPos = lm.findFirstCompletelyVisibleItemPosition()
                            }
                            originListener?.onScrolled(recyclerView, dx, dy)
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                                if (firstIdle || lastPos != -1) {
                                    firstIdle = false
                                    sendBroadcast(recyclerView, lastPos)
                                    XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] 视频切换 -> 位置: $lastPos${RESET}")
                                }
                            }
                            originListener?.onScrollStateChanged(recyclerView, newState)
                        }
                    }
                    recyclerView.addOnScrollListener(newListener)
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "${RED}${LOG_PREFIX}RV Hook Error${RESET}", e)
            XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] 挂载异常: ${e.message}${RESET}")
        }
    }

    private fun sendBroadcast(view: View, position: Int) {
        val intent = Intent(BROADCAST_VIDEO_SWITCH).apply {
            putExtra("pkg_name", view.context.packageName)
            putExtra("video_position", position)
            putExtra("view_id", view.id)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        }
        view.context.sendBroadcast(intent)
    }
}
