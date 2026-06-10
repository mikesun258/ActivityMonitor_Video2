package com.mikesun258.activitymonitor.video

import android.content.Intent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class VideoMonitor : IXposedHookLoadPackage {

    // 目标应用包名
    private val targetPackages = listOf("com.kylin.read")
    // 广播 Action 定义
    private const val ACTION_SCROLL_STATE_CHANGE = "com.mikesun258.monitor.SCROLL_STATE"
    private const val ACTION_ADAPTER_SET = "com.mikesun258.monitor.ADAPTER_SET"

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in targetPackages) return

        try {
            hookRecyclerViewScroll(lpparam)
            hookRecyclerViewAdapter(lpparam)
        } catch (e: Throwable) {
            // 捕获顶层异常，防止模块加载失败
        }
    }

    /**
     * 监听 RecyclerView 滚动状态
     * 兼容 androidx / support.v7 两种 RecyclerView
     */
    private fun hookRecyclerViewScroll(lpparam: XC_LoadPackage.LoadPackageParam) {
        val rvClassList = mutableListOf<Class<*>>()

        // 加载 androidx RecyclerView
        runCatching {
            XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", lpparam.classLoader)
        }.onSuccess { rvClassList.add(it) }

        // 加载 旧版 support.v7 RecyclerView
        runCatching {
            XposedHelpers.findClass("android.support.v7.widget.RecyclerView", lpparam.classLoader)
        }.onSuccess { rvClassList.add(it) }

        rvClassList.forEach { rvCls ->
            XposedHelpers.findAndHookMethod(
                rvCls,
                "addOnScrollListener",
                RecyclerView.OnScrollListener::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            // 安全类型转换，过滤非法调用
                            val rv = param.thisObject as? RecyclerView ?: return
                            val originListener = param.args[0] as? RecyclerView.OnScrollListener ?: return

                            // 包装原有监听器，拦截滚动状态回调
                            val wrapListener = object : RecyclerView.OnScrollListener() {
                                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                                    super.onScrollStateChanged(recyclerView, newState)
                                    // 发送滚动状态广播
                                    sendScrollBroadcast(recyclerView.context, newState)
                                }

                                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                                    super.onScrolled(recyclerView, dx, dy)
                                }
                            }

                            // 移除原监听，替换为包装监听（避免重复监听）
                            rv.removeOnScrollListener(originListener)
                            rv.addOnScrollListener(wrapListener)

                        } catch (_: Throwable) {
                            // 静默异常，不影响宿主
                        }
                    }
                }
            )
        }
    }

    /**
     * 监听 setAdapter 动作
     */
    private fun hookRecyclerViewAdapter(lpparam: XC_LoadPackage.LoadPackageParam) {
        val rvClassList = mutableListOf<Class<*>>()

        runCatching {
            XposedHelpers.findClass("androidx.recyclerview.widget.RecyclerView", lpparam.classLoader)
        }.onSuccess { rvClassList.add(it) }

        runCatching {
            XposedHelpers.findClass("android.support.v7.widget.RecyclerView", lpparam.classLoader)
        }.onSuccess { rvClassList.add(it) }

        rvClassList.forEach { rvCls ->
            XposedHelpers.findAndHookMethod(
                rvCls,
                "setAdapter",
                RecyclerView.Adapter::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val rv = param.thisObject as? RecyclerView ?: return
                            val adapter = param.args[0] as? RecyclerView.Adapter<*> ?: return

                            // 发送 Adapter 变更广播
                            val intent = Intent(ACTION_ADAPTER_SET)
                            intent.putExtra("hasAdapter", adapter != null)
                            rv.context.sendBroadcast(intent)

                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        }
    }

    /**
     * 发送滚动状态广播
     * @param state 0:停止滚动 1:手指拖拽 2:惯性滚动
     */
    private fun sendScrollBroadcast(context: android.content.Context, state: Int) {
        val intent = Intent(ACTION_SCROLL_STATE_CHANGE)
        intent.putExtra("scroll_state", state)
        context.sendBroadcast(intent)
    }
}
