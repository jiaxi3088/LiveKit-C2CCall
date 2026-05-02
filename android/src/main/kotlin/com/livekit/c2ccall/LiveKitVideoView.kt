package com.livekit.c2ccall

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import io.dcloud.feature.uniapp.annotation.UniComponent
import io.dcloud.feature.uniapp.common.UniComponent
import org.webrtc.RendererCommon.ScalingType
import org.webrtc.SurfaceViewRenderer

/**
 * LiveKit 视频渲染原生组件
 * 
 * 在 nvue 中使用：
 *   <livekit-video-view type="remote"></livekit-video-view>
 *   <livekit-video-view type="local"></livekit-video-view>
 * 
 * 属性：
 *   - type: "local" | "remote"（默认 "remote"）
 */
@UniComponent(props = ["type"])
class LiveKitVideoView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0
) : UniComponent(context, attributeSet, defStyleAttr) {

    companion object {
        private const val TAG = "LiveKitVideoView"
        private var sharedEglBase: org.webrtc.EglBase? = null
        
        /**
         * 获取共享的 EglBase（所有渲染器共用同一个 EglContext）
         */
        fun getSharedEglBase(): org.webrtc.EglBase? {
            if (sharedEglBase == null) {
                sharedEglBase = org.webrtc.EglBase.create()
                Log.d(TAG, "[VIEW] 共享 EglBase 创建成功")
            }
            return sharedEglBase
        }
        
        /**
         * 释放共享 EglBase（在 Activity 销毁时调用）
         */
        fun releaseSharedEglBase() {
            try {
                sharedEglBase?.release()
                sharedEglBase = null
                Log.d(TAG, "[VIEW] 共享 EglBase 已释放")
            } catch (e: Exception) {
                // ignore
            }
        }
        
        // 持有当前活跃的视图引用，供 Module 绑定轨道
        var localViewInstance: LiveKitVideoView? = null
        var remoteViewInstance: LiveKitVideoView? = null
    }

    private var renderer: SurfaceViewRenderer? = null
    private var viewType: String = "remote"

    init {
        // 创建 FrameLayout 容器
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 创建 SurfaceViewRenderer
        val eglBase = getSharedEglBase()
        renderer = SurfaceViewRenderer(context).apply {
            init(eglBase?.eglBaseContext, null)
            setScalingType(ScalingType.SCALE_ASPECT_FIT)
            setEnableHardwareScaler(true)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(renderer!!)
        setContainerView(container)

        Log.d(TAG, "[VIEW] LiveKitVideoView 初始化完成")
    }

    override fun onCreated() {
        super.onCreated()
        // 读取 type 属性，注册到全局引用
        viewType = getAttr("type") ?: "remote"
        
        when (viewType) {
            "local" -> localViewInstance = this
            "remote" -> remoteViewInstance = this
        }
        Log.d(TAG,("[VIEW] onCreated: type=$viewType"))
    }

    override fun onDestroy() {
        super.onDestroy()
        
        // 从全局引用移除
        when (viewType) {
            "local" -> if (localViewInstance === this) localViewInstance = null
            "remote" -> if (remoteViewInstance === this) remoteViewInstance = null
        }

        // 释放渲染器
        try {
            renderer?.release()
            renderer = null
        } catch (e: Exception) {
            // ignore
        }
        Log.d(TAG, "[VIEW] onDestroy: type=$viewType")
    }

    /**
     * 获取内部的 SurfaceViewRenderer（供 Module 绑定视频流）
     */
    fun getRenderer(): SurfaceViewRenderer? = renderer
    
    /**
     * 获取视图类型
     */
    fun getViewType(): String = viewType
}
