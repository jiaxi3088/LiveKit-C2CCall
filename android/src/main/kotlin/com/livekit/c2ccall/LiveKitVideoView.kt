package com.livekit.c2ccall

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import io.dcloud.feature.uniapp.annotation.UniComponent
import io.dcloud.feature.uniapp.common.UniComponent as UniComponentBase
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * LiveKit 视频渲染原生 nvue 组件
 *
 * 使用方式（nvue 页面）：
 *   <livekit-video-view type="remote" style="flex:1"></livekit-video-view>
 *   <livekit-video-view type="local" style="width:120;height:160"></livekit-video-view>
 *
 * 属性：
 *   - type: "local" | "remote"（默认 "remote"）
 */
@UniComponent(props = ["type"])
class LiveKitVideoView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0
) : UniComponentBase(context, attributeSet, defStyleAttr) {

    companion object {
        private const val TAG = "LKVideoView"

        /** 共享 EglBase：所有 SurfaceViewRenderer 共享同一个 EGL 上下文 */
        private var sharedEglBase: EglBase? = null

        @Synchronized
        fun getSharedEglBase(): EglBase {
            if (sharedEglBase == null) {
                sharedEglBase = EglBase.create()
                Log.i(TAG, "Shared EglBase created")
            }
            return sharedEglBase!!
        }

        @Synchronized
        fun releaseSharedEglBase() {
            try {
                sharedEglBase?.release()
                Log.i(TAG, "Shared EglBase released")
            } catch (_: Exception) {
            }
            sharedEglBase = null
        }

        /** 全局引用：供 Module 查找并绑定视频流 */
        @JvmStatic var localViewInstance: LiveKitVideoView? = null
            private set

        @JvmStatic var remoteViewInstance: LiveKitVideoView? = null
            private set
    }

    private var renderer: SurfaceViewRenderer? = null
    internal var viewType: String = "remote"
        private set

    // ==================== 初始化 ====================

    init {
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        renderer = SurfaceViewRenderer(context).apply {
            init(getSharedEglBase().eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            isEnableHardwareScaler = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(renderer!!)
        setContainerView(container)

        Log.d(TAG, "LiveKitVideoView init OK")
    }

    // ==================== 生命周期回调 ====================

    override fun onCreated() {
        super.onCreated()
        viewType = getAttr("type")?.toString() ?: "remote"

        when (viewType) {
            "local" -> localViewInstance = this
            "remote" -> remoteViewInstance = this
        }
        Log.d(TAG, "onCreated: type=$viewType")
    }

    override fun onDestroy() {
        super.onDestroy()

        when (viewType) {
            "local" -> if (localViewInstance === this) localViewInstance = null
            "remote" -> if (remoteViewInstance === this) remoteViewInstance = null
        }

        try {
            renderer?.release()
        } catch (_: Exception) {
        }
        renderer = null
        Log.d(TAG, "onDestroy: type=$viewType")
    }

    // ==================== 公共 API ====================

    /**
     * 获取内部 SurfaceViewRenderer，用于 Module 绑定 VideoTrack
     */
    fun getRenderer(): SurfaceViewRenderer? = renderer
}
