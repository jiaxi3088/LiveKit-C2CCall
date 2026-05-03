package com.livekit.c2ccall

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
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
 * ⚠️ 关键修复 (v1.0.1):
 *    SurfaceViewRenderer 必须在 View attach 到 Window 后才能调用 init()，
 *    否则 WebRTC Native 层访问无效 Surface 导致 SIGSEGV 崩溃！
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
                } catch (e: Exception) {
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
         */
        fun createSurfaceViewRenderer(context: Context): Any? {
            return try {
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
         * ⚠️ 只能在 View 已 attach 到 Window 后调用！
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

                Log.d(TAG, "SurfaceViewRenderer initialized OK (safe mode)")
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

    /** 渲染器实例（实际是 org.webrtc.SurfaceViewRenderer） */
    private var renderer: Any? = null
    internal var viewType: String = "remote"
        private set

    /** WebRTC 渲染器是否就绪 */
    var isRendererReady: Boolean = false
        private set

    /** 是否已执行延迟初始化（防止重复） */
    private var isDeferredInitScheduled = false
    private var isDeferredInitDone = false

    init {
        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        if (isWebRtcAvailable()) {
            // ✅ 安全策略：只创建渲染器，不立即 init！
            // init() 必须等到 View attach 到 Window 后才能安全调用
            renderer = createSurfaceViewRenderer(context)
            
            if (renderer != null) {
                val rendererView = renderer as View
                rendererView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
                container.addView(rendererView)
                
                // 标记需要延迟初始化
                isDeferredInitScheduled = true
                Log.d(TAG, "LiveKitVideoView init OK (renderer created, deferred init pending)")
            } else {
                renderer = null
                addPlaceholderView(container, context, "Renderer creation failed")
                Log.w(TAG, "LiveKitVideoView init: renderer creation failed")
            }
        } else {
            addPlaceholderView(container, context, "WebRTC unavailable")
            Log.w(TAG, "LiveKitVideoView init: WebRTC not available")
        }

        setContainerView(container)
    }

    /**
     * 安全延迟初始化：在 View 首次布局完成后调用 renderer.init()
     * 这确保 Surface 已有效且 View 已 attach 到 Window
     */
    private fun performDeferredInitIfNeeded() {
        if (isDeferredInitDone || !isDeferredInitScheduled || renderer == null) return
        
        try {
            isRendererReady = initSurfaceViewRenderer(renderer, context)
            isDeferredInitDone = true
            
            if (isRendererReady) {
                Log.d(TAG, "✅ Deferred init SUCCESS for $viewType renderer (attached to window)")
            } else {
                Log.w(TAG, "⚠️ Deferred init FAILED for $viewType renderer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Deferred init EXCEPTION for $viewType: ${e.message}", e)
            isDeferredInitDone = true // 标记已尝试，避免重试
        }
    }

    override fun onCreated() {
        super.onCreated()
        viewType = getAttr("type")?.toString() ?: "remote"

        when (viewType) {
            "local" -> localViewInstance = this
            "remote" -> remoteViewInstance = this
        }

        // ✅ 在 onCreated 中注册延迟初始化回调
        // 当 UniComponent 完成 layout 并 attach 到 Window 时触发
        if (isDeferredInitScheduled && !isDeferredInitDone && renderer != null) {
            val rendererView = renderer as View
            
            // 方案1: 使用 post 确保在下一个消息循环中执行
            rendererView.post {
                Log.d(TAG, "[DEFERRED] post callback fired for $viewType, attempting safe init...")
                performDeferredInitIfNeeded()
            }
            
            // 方案2: 备用方案 - 监听 GlobalLayout（确保 layout 完成）
            rendererView.viewTreeObserver.addOnGlobalLayoutListener(object : 
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    // 移除监听器防止多次触发
                    try {
                        rendererView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    } catch (_: Exception) {}
                    
                    if (!isDeferredInitDone) {
                        Log.d(TAG, "[DEFERRED] GlobalLayout triggered for $viewType, performing init...")
                        performDeferredInitIfNeeded()
                    }
                }
            })
        }

        Log.d(TAG, "onCreated: type=$viewType, deferredInitScheduled=$isDeferredInitScheduled, rendererReady=$isRendererReady")
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
        isDeferredInitDone = true
        Log.d(TAG, "onDestroy: type=$viewType")
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

    /**
     * 获取内部渲染器实例（org.webrtc.SurfaceViewRenderer，类型为 Any）
     * 用于 Module 通过反射调用 addSink/removeSink
     */
    fun getRenderer(): Any? = if (isRendererReady) renderer else null

    fun getViewType(): String = viewType
}
