package com.livekit.c2ccall

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.FrameLayout
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
 */
@UniComponentAnnotation(props = ["type"])
class LiveKitVideoView @JvmOverloads constructor(
    context: Context,
    attributeSet: AttributeSet? = null,
    defStyleAttr: Int = 0
) : UniComponentBase(context, attributeSet, defStyleAttr) {

    companion object {
        private const val TAG = "LKVideoView"

        /** 共享 EglBase 实例（通过反射创建） */
        private var sharedEglBase: Any? = null

        /**
         * 获取/创建共享 EglBase（所有 SurfaceViewRenderer 共享同一 EGL 上下文）
         */
        @JvmStatic
        fun getSharedEglBase(): Any {
            if (sharedEglBase == null) {
                try {
                    val eglBaseClass = Class.forName("org.webrtc.EglBase")
                    val createMethod = eglBaseClass.getMethod("create")
                    sharedEglBase = createMethod.invoke(null)
                    Log.i(TAG, "Shared EglBase created via reflection")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create EglBase: ${e.message}", e)
                    throw RuntimeException("WebRTC not available. Ensure livekit-android dependency is correctly resolved.", e)
                }
            }
            return sharedEglBase!!
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
         */
        fun createSurfaceViewRenderer(context: Context): Any {
            try {
                val svrClass = Class.forName("org.webrtc.SurfaceViewRenderer")
                val constructor = svrClass.getConstructor(Context::class.java)
                return constructor.newInstance(context)
            } catch (e: Exception) {
                throw RuntimeException("Failed to create SurfaceViewRenderer: ${e.message}", e)
            }
        }

        /**
         * 初始化 SurfaceViewRenderer（init + scalingType + hardwareScaler）
         */
        fun initSurfaceViewRenderer(renderer: Any, context: Context) {
            try {
                val eglBase = getSharedEglBase()
                val eglContextField = eglBase.javaClass.getField("eglBaseContext")
                val eglContext = eglContextField.get(eglBase)

                // renderer.init(eglBase.eglBaseContext, null)
                val rendererEventsClsName = "org.webrtc.RendererCommon\$RendererEvents"
                val initMethod = renderer.javaClass.getMethod("init", eglContext.javaClass, Class.forName(rendererEventsClsName))
                initMethod.invoke(renderer, eglContext, null)

                // setScalingType(SCALE_ASPECT_FIT)
                val rcClass = Class.forName("org.webrtc.RendererCommon")
                val stClass = Class.forName("org.webrtc.RendererCommon\$ScalingType")
                val scaleFit = stClass.getField("SCALE_ASPECT_FIT").get(null)
                val setScalingMethod = renderer.javaClass.getMethod("setScalingType", stClass)
                setScalingMethod.invoke(renderer, scaleFit)

                // setEnableHardwareScaler(true)
                val setHwMethod = renderer.javaClass.getMethod("setEnableHardwareScaler", java.lang.Boolean.TYPE)
                setHwMethod.invoke(renderer, true)

                Log.d(TAG, "SurfaceViewRenderer initialized OK")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to init SurfaceViewRenderer: ${e.message}", e)
                throw RuntimeException("Init renderer failed", e)
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

    init {
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 通过反射创建 SurfaceViewRenderer 并初始化
        renderer = createSurfaceViewRenderer(context)
        initSurfaceViewRenderer(renderer!!, context)

        val rendererView = renderer as View
        rendererView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        container.addView(rendererView)
        setContainerView(container)

        Log.d(TAG, "LiveKitVideoView init OK (renderer created via reflection)")
    }

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

        releaseSurfaceViewRenderer(renderer)
        renderer = null
        Log.d(TAG, "onDestroy: type=$viewType")
    }

    /**
     * 获取内部渲染器实例（org.webrtc.SurfaceViewRenderer，类型为 Any）
     * 用于 Module 通过反射调用 addSink/removeSink
     */
    fun getRenderer(): Any? = renderer

    fun getViewType(): String = viewType
}
