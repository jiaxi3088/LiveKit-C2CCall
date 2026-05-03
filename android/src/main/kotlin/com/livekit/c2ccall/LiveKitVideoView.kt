package com.livekit.c2ccall

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import io.dcloud.feature.uniapp.annotation.UniComponentAnnotation
import io.dcloud.feature.uniapp.ui.component.UniComponent as UniComponentBase

/**
 * LiveKit 视频渲染原生 nvue 组件
 *
 * 使用方式（nvue 页面）：
 *   <livekit-video-view type="remote" style="flex:1"></livekit-video-view>
 *   <livekit-video-view type="local" style="width:120;height:160"></livekit-video-view>
 *
 * 属性：
 *   - type: "local" | "remote"（默认 "remote"）
 *
 * 内部实现：通过反射创建 org.webrtc.SurfaceViewRenderer，避免编译期依赖 WebRTC 库。
 * 若 WebRTC 不可用，显示占位视图，不影响音频通话功能。
 */
@UniComponentAnnotation(props = ["type"])
class LiveKitVideoView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0
) : UniComponentBase(context, attributeSet, defStyleAttr) {

    companion object {
        private const val TAG = "LKVideoView"

        /** 共享 EglBase 实例（通过反射创建），null 表示 WebRTC 不可用 */
        private var sharedEglBase: Any? = null

        /** WebRTC 类是否可用（缓存检测结果） */
        private var webrtcAvailable: Boolean? = null

        /**
         * 检测 WebRTC 运行时是否可用（只检测一次并缓存结果）
         */
        @JvmStatic
        fun isWebRtcAvailable(): Boolean {
            if (webrtcAvailable == null) {
                webrtcAvailable = try {
                    // LiveKit Android SDK 2.x 使用 repackaged WebRTC: livekit.org.webrtc.*
                    Class.forName("livekit.org.webrtc.EglBase")
                    Log.i(TAG, "WebRTC runtime check: AVAILABLE")
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "WebRTC runtime check: NOT AVAILABLE - ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
            }
            return webrtcAvailable!!
        }

        /**
         * 获取/创建共享 EglBase（所有 SurfaceViewRenderer 共享同一 EGL 上下文）
         * @return EglBase 实例，WebRTC 不可用时返回 null
         */
        @JvmStatic
        fun getSharedEglBase(): Any? {
            if (!isWebRtcAvailable()) return null
            if (sharedEglBase == null) {
                try {
                    val eglBaseClass = Class.forName("livekit.org.webrtc.EglBase")
                    val createMethod = eglBaseClass.getMethod("create")
                    sharedEglBase = createMethod.invoke(null)
                    Log.i(TAG, "Shared EglBase created via reflection")
                } catch (e:Exception){
                    Log.e(TAG, "Failed to create EglBase: ${e.message}", e)
                    webrtcAvailable = false
                }
            }
            return sharedEglBase
        }

        /**
         * 释放共享 EglBase（在 Activity 销毁时调用）
         */
        @JvmStatic
        fun releaseSharedEglBase() {
            try {
                sharedEglBase?.let { eglBase ->
                    val releaseMethod = eglBase.javaClass.getMethod("release")
                    releaseMethod.invoke(eglBase)
                    Log.i(TAG, "Shared EglBase released")
                }
            } catch (_: Exception) {
            }
            sharedEglBase = null
        }

        /** 全局引用：供 Module 查找并绑定视频流 */
        @JvmStatic var localViewInstance: LiveKitVideoView? = null
            private set

        @JvmStatic var remoteViewInstance: LiveKitVideoView? = null
            private set

        /**
         * 通过反射创建 SurfaceViewRenderer 实例
         * @return SurfaceViewRenderer 实例，失败时返回 null
         */
        fun createSurfaceViewRenderer(context: Context): Any? {
            return try {
                // LiveKit Android SDK 2.x 使用 repackaged WebRTC: livekit.org.webrtc.*
                val svrClass = Class.forName("livekit.org.webrtc.SurfaceViewRenderer")
                val constructor = svrClass.getConstructor(Context::class.java)
                constructor.newInstance(context)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create SurfaceViewRenderer: ${e.message}")
                null
            }
        }

        /**
         * 初始化 SurfaceViewRenderer（init + scalingType + hardwareScaler）
         * @return 是否初始化成功
         */
        fun initSurfaceViewRenderer(renderer: Any?, context: Context): Boolean {
            if (renderer == null) return false
            try {
                val eglBase = getSharedEglBase() ?: run {
                    Log.w(TAG, "Cannot init renderer: EglBase unavailable")
                    return false
                }
                val eglContextField = eglBase.javaClass.getField("eglBaseContext")
                val eglContext = eglContextField.get(eglBase)

                // renderer.init(eglBase.eglBaseContext, null)
                // LiveKit Android SDK 2.x 使用 repackaged WebRTC: livekit.org.webrtc.*
                val rendererEventsClsName = "livekit.org.webrtc.RendererCommon\$RendererEvents"
                val initMethod = renderer.javaClass.getMethod("init", eglContext.javaClass, Class.forName(rendererEventsClsName))
                initMethod.invoke(renderer, eglContext, null)

                // setScalingType(SCALE_ASPECT_FIT)
                val rcClass = Class.forName("livekit.org.webrtc.RendererCommon")
                val stClass = Class.forName("livekit.org.webrtc.RendererCommon\$ScalingType")
                val scaleFit = stClass.getField("SCALE_ASPECT_FIT").get(null)
                val setScalingMethod = renderer.javaClass.getMethod("setScalingType", stClass)
                setScalingMethod.invoke(renderer, scaleFit)

                // setEnableHardwareScaler(true)
                val setHwMethod = renderer.javaClass.getMethod("setEnableHardwareScaler", java.lang.Boolean.TYPE)
                setHwMethod.invoke(renderer, true)

                Log.d(TAG, "SurfaceViewRenderer initialized OK")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init SurfaceViewRenderer: ${e.message}", e)
                return false
            }
        }

        /**
         * 释放 SurfaceViewRenderer
         */
        fun releaseSurfaceViewRenderer(renderer: Any?) {
            try {
                renderer?.let {
                    val releaseMethod = it.javaClass.getMethod("release")
                    releaseMethod.invoke(it)
                }
            } catch (_: Exception) {}
        }
    }

    /** 渲染器实例（实际是 org.webrtc.SurfaceViewRenderer，用 Any 类型持有以避免 import） */
    private var renderer: Any? = null
    internal var viewType: String = "remote"
        private set

    /** WebRTC 渲染器是否就绪（可用于绑定视频轨道） */
    var isRendererReady: Boolean = false
        private set

    init {
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        if (isWebRtcAvailable()) {
            // 尝试通过反射创建 SurfaceViewRenderer 并初始化
            renderer = createSurfaceViewRenderer(context)
            isRendererReady = initSurfaceViewRenderer(renderer, context)

            if (isRendererReady && renderer != null) {
                val rendererView = renderer as View
                rendererView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                container.addView(rendererView)
                Log.d(TAG, "LiveKitVideoView init OK (WebRTC renderer ready)")
            } else {
                // WebRTC 可但渲染器创建/初始化失败 → 显示占位
                renderer = null
                addPlaceholderView(container, context, "Video init failed")
                Log.w(TAG, "LiveKitVideoView init: renderer creation failed, using placeholder")
            }
        } else {
            // WebRTC 不可用 → 显示占位视图（音频通话仍可用）
            addPlaceholderView(container, context, "WebRTC unavailable")
            Log.w(TAG, "LiveKitVideoView init: WebRTC not available in runtime, video disabled (audio-only mode)")
        }

        setContainerView(container)
    }

    /**
     * 添加占位视图（当 WebRTC 或渲染器不可用时）
     */
    private fun addPlaceholderView(container: FrameLayout, context: Context, reason: String) {
        val placeholder = TextView(context).apply {
            text = ""
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(placeholder)
        Log.d(TAG, "Placeholder view added: $reason")
    }

    override fun onCreated() {
        super.onCreated()
        viewType = getAttr("type")?.toString() ?: "remote"

        when (viewType) {
            "local" -> localViewInstance = this
            "remote" -> remoteViewInstance = this
        }
        Log.d(TAG, "onCreated: type=$viewType, rendererReady=$isRendererReady")
    }

    override fun onDestroy() {
        super.onDestroy()

        when (viewType) {
            "local" -> if (localViewInstance === this) localViewInstance = null
            "remote" -> if (remoteViewInstance === this) remoteViewInstance = null
        }

        releaseSurfaceViewRenderer(renderer)
        renderer = null
        isRendererReady = false
        Log.d(TAG, "onDestroy: type=$viewType")
    }

    /**
     * 获取内部渲染器实例（org.webrtc.SurfaceViewRenderer，类型为 Any）
     * 用于 Module 通过反射调用 addSink/removeSink
     * @return 渲染器实例，未就绪时返回 null
     */
    fun getRenderer(): Any? = if (isRendererReady) renderer else null

    fun getViewType(): String = viewType
}
