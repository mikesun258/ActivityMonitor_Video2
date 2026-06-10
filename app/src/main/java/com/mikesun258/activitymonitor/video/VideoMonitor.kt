package com.mikesun258.activitymonitor.video

import android.content.Intent
import android.util.Log
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class VideoMonitor : IXposedHookLoadPackage {
    private val TAG = "VideoMonitor"
    private val BROADCAST_VIDEO_SWITCH = "com.mikesun258.activitymonitor.VIDEO_SWITCH"

    // 颜色与前缀配置
    private val RED = "\u001B[31m"
    private val RESET = "\u001B[0m"
    private val LOG_PREFIX = "【我要的】"

    // 目标应用列表
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
            XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] 已挂载: ${lpparam.packageName}${RESET}")
            hookRecyclerView(lpparam)
        }
    }

    private fun hookRecyclerView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val rvClass = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")

            // Hook addOnScrollListener
            XposedBridge.hookAllMethods(rvClass, "addOnScrollListener", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val originListener = param.args[0] as RecyclerView.OnScrollListener?
                    val recyclerView = param.thisObject as RecyclerView

                    val newListener = object : RecyclerView.OnScrollListener() {
                        private var lastReportedPos = -1

                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            originListener?.onScrolled(recyclerView, dx, dy)

                            // 改用 findFirstVisibleItemPosition 兼容更多LayoutManager
                            val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                            val currentPos = lm.findFirstVisibleItemPosition()

                            // 滑动过程中，只要位置变化就触发（避免IDLE漏触发）
                            if (currentPos != RecyclerView.NO_POSITION && currentPos != lastReportedPos) {
                                lastReportedPos = currentPos
                                sendBroadcast(recyclerView, currentPos)
                                XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] 滑动切换 -> 位置: $currentPos${RESET}")
                            }
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            originListener?.onScrollStateChanged(recyclerView, newState)
                        }
                    }

                    // 给RecyclerView添加我们的监听
                    recyclerView.addOnScrollListener(newListener)
                    XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] 成功为RecyclerView添加监听${RESET}")
                }
            })
        } catch (e: Throwable) {
            Log.e(TAG, "${RED}${LOG_PREFIX}RV Hook Error${RESET}", e)
            XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] 挂载异常: ${e.message}${RESET}")
        }
    }

    private fun sendBroadcast(view: View, position: Int) {
        val context = view.context
        val intent = Intent(BROADCAST_VIDEO_SWITCH).apply {
            putExtra("pkg_name", context.packageName)
            putExtra("video_position", position)
            putExtra("view_id", view.id)
            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
        }
        context.sendBroadcast(intent)
        XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] 已发送广播 -> 位置: $position${RESET}")
    }
}
