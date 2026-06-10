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
    private val GREEN = "\u001B[32m"
    private val YELLOW = "\u001B[33m"
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
        // 1. 模块入口日志
        XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] handleLoadPackage 进入: ${lpparam.packageName}${RESET}")

        if (lpparam.packageName in targetPackages) {
            XposedBridge.log("${GREEN}${LOG_PREFIX}[${TAG}] 匹配到目标包: ${lpparam.packageName}，开始Hook流程${RESET}")
            hookRecyclerView(lpparam)
        } else {
            XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] 非目标包，跳过: ${lpparam.packageName}${RESET}")
        }
    }

    private fun hookRecyclerView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] 尝试加载 RecyclerView 类...${RESET}")
            val rvClass = lpparam.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
            XposedBridge.log("${GREEN}${LOG_PREFIX}[${TAG}] RecyclerView 类加载成功${RESET}")

            // Hook addOnScrollListener
            XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] 开始Hook addOnScrollListener 方法...${RESET}")
            XposedBridge.hookAllMethods(rvClass, "addOnScrollListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    super.beforeHookedMethod(param)
                    val recyclerView = param.thisObject as? RecyclerView
                    val listener = param.args[0] as? RecyclerView.OnScrollListener
                    XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] addOnScrollListener 被调用 -> RV实例: $recyclerView, 原监听: $listener${RESET}")
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)
                    val originListener = param.args[0] as RecyclerView.OnScrollListener?
                    val recyclerView = param.thisObject as RecyclerView

                    XposedBridge.log("${GREEN}${LOG_PREFIX}[${TAG}] 成功拦截 addOnScrollListener，准备注入自定义监听 -> RV实例: $recyclerView${RESET}")

                    val newListener = object : RecyclerView.OnScrollListener() {
                        private var lastReportedPos = -1
                        private var reportCount = 0

                        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(recyclerView, dx, dy)
                            originListener?.onScrolled(recyclerView, dx, dy)

                            reportCount++
                            XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] onScrolled 触发 #$reportCount -> dx=$dx, dy=$dy${RESET}")

                            val lm = recyclerView.layoutManager as? LinearLayoutManager
                            if (lm == null) {
                                XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] LayoutManager 不是 LinearLayoutManager，跳过位置检测${RESET}")
                                return
                            }

                            val currentPos = lm.findFirstVisibleItemPosition()
                            val currentCompletelyPos = lm.findFirstCompletelyVisibleItemPosition()

                            XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] 位置检测结果 -> firstVisible=$currentPos, firstCompletely=$currentCompletelyPos${RESET}")

                            if (currentPos != RecyclerView.NO_POSITION && currentPos != lastReportedPos) {
                                lastReportedPos = currentPos
                                XposedBridge.log("${GREEN}${LOG_PREFIX}[${TAG}] 位置变化 -> lastReported=$lastReportedPos, current=$currentPos，准备发送广播${RESET}")
                                sendBroadcast(recyclerView, currentPos)
                            } else {
                                XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] 位置未变化或无效 -> lastReported=$lastReportedPos, current=$currentPos${RESET}")
                            }
                        }

                        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                            super.onScrollStateChanged(recyclerView, newState)
                            originListener?.onScrollStateChanged(recyclerView, newState)
                            val stateStr = when (newState) {
                                RecyclerView.SCROLL_STATE_IDLE -> "IDLE"
                                RecyclerView.SCROLL_STATE_DRAGGING -> "DRAGGING"
                                RecyclerView.SCROLL_STATE_SETTLING -> "SETTLING"
                                else -> "UNKNOWN($newState)"
                            }
                            XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] onScrollStateChanged -> state=$stateStr${RESET}")
                        }
                    }

                    // 注入自定义监听
                    recyclerView.addOnScrollListener(newListener)
                    XposedBridge.log("${GREEN}${LOG_PREFIX}[${TAG}] 自定义 OnScrollListener 已成功添加到 RecyclerView${RESET}")
                }
            })
            XposedBridge.log("${GREEN}${LOG_PREFIX}[${TAG}] Hook addOnScrollListener 完成${RESET}")

            // 额外Hook setAdapter，确认列表被实例化
            XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] 额外Hook setAdapter 方法...${RESET}")
            XposedBridge.hookAllMethods(rvClass, "setAdapter", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)
                    val recyclerView = param.thisObject as RecyclerView
                    val adapter = param.args[0]
                    XposedBridge.log("${GREEN}${LOG_PREFIX}[${TAG}] RecyclerView.setAdapter 被调用 -> RV实例: $recyclerView, Adapter: $adapter${RESET}")
                }
            })
            XposedBridge.log("${GREEN}${LOG_PREFIX}[${TAG}] Hook setAdapter 完成${RESET}")

        } catch (e: Throwable) {
            val stackTrace = Log.getStackTraceString(e)
            XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] hookRecyclerView 发生异常: ${e.message}\n$stackTrace${RESET}")
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

        XposedBridge.log("${YELLOW}${LOG_PREFIX}[${TAG}] 构建广播Intent -> action=$BROADCAST_VIDEO_SWITCH, pkg=${context.packageName}, pos=$position, viewId=${view.id}${RESET}")
        try {
            context.sendBroadcast(intent)
            XposedBridge.log("${GREEN}${LOG_PREFIX}[${TAG}] 广播发送成功 -> pos=$position${RESET}")
        } catch (e: Throwable) {
            val stackTrace = Log.getStackTraceString(e)
            XposedBridge.log("${RED}${LOG_PREFIX}[${TAG}] 广播发送失败: ${e.message}\n$stackTrace${RESET}")
        }
    }
}
