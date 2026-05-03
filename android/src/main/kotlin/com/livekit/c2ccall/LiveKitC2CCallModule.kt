package com.livekit.c2ccall

import android.content.Context
import androidx.core.content.ContextCompat
import android.media.MediaPlayer
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import io.dcloud.feature.uniapp.annotation.UniJSMethod
import io.dcloud.feature.uniapp.bridge.UniJSCallback
import io.dcloud.feature.uniapp.common.UniModule
import io.livekit.android.LiveKit
import io.livekit.android.events.ParticipantEvent
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.participant.LocalParticipant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * LiveKit 1v1 视频通话 uni-app 原生插件
 *
 * 支持：发起呼叫、接听、挂断、音视频开关、摄像头切换、事件回调、无障碍读屏、自定义铃声
 */
class LiveKitC2CCallModule : UniModule() {

    companion object {
        private const val TAG = "LiveKitC2CCall"
    }

    private var room: Room? = null
    private var eventCallback: UniJSCallback? = null
    private var isVideoEnabled = true
    private var isAudioEnabled = true

    // 铃声播放器
    private var ringPlayer: MediaPlayer? = null

    // 视频渲染器（通过 LiveKitVideoView 组件获取）
    // 不再在 Module 中创建渲染器，而是使用 LiveKitVideoView 组件提供的实例
    
    // 协程作用域
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ======================== 1. 发起 1v1 视频呼叫 ========================

    @UniJSMethod(uiThread = true)
    fun startC2CVideoCall(options: Map<String, Any>?, callback: UniJSCallback?) {
        Log.d(TAG, "[DEBUG] startC2CVideoCall 被调用")
        if (options == null) {
            invokeError(callback, "参数不能为空")
            return
        }

        val wsURL = options["wsURL"] as? String ?: ""
        val token = options["token"] as? String ?: ""
        Log.d(TAG, "[DEBUG] wsURL=$wsURL, token长度=${token.length}")

        if (wsURL.isEmpty() || token.isEmpty()) {
            invokeError(callback, "wsURL 和 token 不能为空")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val videoOpts = options["videoOptions"] as? Map<String, Any>
        // 支持 Map ({"enabled": false}) 和直接 Boolean (false) 两种格式
        val rawAudioOpts = options["audioOptions"]
        val audioOpts = when (rawAudioOpts) {
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (rawAudioOpts as Map<String, Any>)
            is Boolean -> mapOf("enabled" to rawAudioOpts)
            null -> null
            else -> null // 不支持的类型，走默认逻辑
        }
        val callRing = options["callRing"] as? String
        Log.d(TAG, "[DEBUG] videoOpts=$videoOpts, audioOpts=$audioOpts (rawType=${rawAudioOpts?.javaClass?.simpleName}), callRing=$callRing")

        parseAudioPublishOptions(audioOpts)

        // 播放呼叫铃声
        playRing(callRing)

        scope.launch {
            Log.d(TAG, "[DEBUG] 协程开始执行")
            try {
                Log.d(TAG, "[DEBUG] 步骤1: disconnectRoom")
                disconnectRoom()

                Log.d(TAG, "[DEBUG] 步骤2: 获取 Context")
                val appContext = getApplicationCompatible()
                    ?: throw Exception("无法获取 Context")
                Log.d(TAG, "[DEBUG] 步骤2完成: Context=${appContext.javaClass.simpleName}")

                Log.d(TAG, "[DEBUG] 步骤3: 调用 LiveKit.init（SDK 内部自动加载 lkjingle_peerconnection_so 原生库）")
                LiveKit.init(appContext)
                Log.d(TAG, "[DEBUG] 步骤3完成: LiveKit.init 成功")

                Log.d(TAG, "[DEBUG] 步骤4: LiveKit.create")
                room = LiveKit.create(appContext)
                Log.d(TAG, "[DEBUG] 步骤4完成: Room 创建成功, room=$room")

                Log.d(TAG, "[DEBUG] 步骤5: 启动事件收集协程")
                // 在单独协程中收集房间事件（通过 EventListenable.events 获取 SharedFlow，使用标准 Flow.collect）
                scope.launch {
                    Log.d(TAG, "[DEBUG] 事件收集协程启动")
                    try {
                        room?.events?.events?.collect { event: RoomEvent ->
                            Log.d(TAG, "[DEBUG] 收到 RoomEvent: ${event::class.simpleName}")
                            try {
                                handleRoomEvent(event)
                            } catch (e: Exception) {
                                Log.e(TAG, "[DEBUG] ❌ handleRoomEvent 异常: event=${event::class.simpleName}, err=${e.javaClass.simpleName}: ${e.message}", e)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "[DEBUG] ❌ 事件收集协程异常: ${e.javaClass.simpleName}: ${e.message}", e)
                    }
                    Log.d(TAG, "[DEBUG] 事件收集协程结束")
                }

                Log.d(TAG, "[DEBUG] 步骤6: room.connect 开始, wsURL=$wsURL")
                // 连接到房间（suspend fun，会挂起直到断开连接）
                room!!.connect(wsURL, token)
                Log.d(TAG, "[DEBUG] 步骤6完成: room.connect 成功")

                // ===== 步骤7: 初始化视频渲染 + 发布本地媒体 + 订阅远端媒体 =====
                Log.d(TAG, "[DEBUG] 步骤6完成后的状态检查: room=$room")
                initMediaAfterConnect()
                
                Log.d(TAG, "[DEBUG] 即将调用 invokeSuccess...")
                invokeSuccess(callback, "呼叫已发起")
                Log.d(TAG, "[DEBUG] invokeSuccess 返回成功")
                announceForAccessibility("正在发起视频通话")
                Log.d(TAG, "[DEBUG] startC2CVideoCall 全部成功 - 等待异步操作完成...")
            } catch (e: Throwable) {
                Log.e(TAG, "[DEBUG] ❌ startC2CVideoCall 崩溃: ${e.javaClass.simpleName}: ${e.message}", e)
                invokeError(callback, "发起呼叫失败: ${e.javaClass.simpleName} - ${e.message}")
                sendEvent("onError", "发起呼叫失败: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    // ======================== 2. 接听 1v1 视频通话 ========================

    @UniJSMethod(uiThread = true)
    fun answerC2CVideoCall(options: Map<String, Any>?, callback: UniJSCallback?) {
        Log.d(TAG, "[DEBUG] answerC2CVideoCall 被调用")
        if (options == null) {
            invokeError(callback, "参数不能为空")
            return
        }

        val wsURL = options["wsURL"] as? String ?: ""
        val token = options["token"] as? String ?: ""
        Log.d(TAG, "[DEBUG] wsURL=$wsURL, token长度=${token.length}")

        if (wsURL.isEmpty() || token.isEmpty()) {
            invokeError(callback, "wsURL 和 token 不能为空")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val videoOpts = options["videoOptions"] as? Map<String, Any>
        // 支持 Map ({"enabled": false}) 和直接 Boolean (false) 两种格式
        val rawAudioOpts = options["audioOptions"]
        val audioOpts = when (rawAudioOpts) {
            is Map<*, *> -> @Suppress("UNCHECKED_CAST") (rawAudioOpts as Map<String, Any>)
            is Boolean -> mapOf("enabled" to rawAudioOpts)
            null -> null
            else -> null
        }
        val answerRing = options["answerRing"] as? String
        Log.d(TAG, "[DEBUG] answerC2C: videoOpts=$videoOpts, audioOpts=$audioOpts (rawType=${rawAudioOpts?.javaClass?.simpleName})")

        parseAudioPublishOptions(audioOpts)

        // 播放接听铃声
        playRing(answerRing)

        scope.launch {
            Log.d(TAG, "[DEBUG] answerC2C 协程开始执行")
            try {
                Log.d(TAG, "[DEBUG] answerC2C 步骤1: disconnectRoom")
                disconnectRoom()

                Log.d(TAG, "[DEBUG] answerC2C 步骤2: 获取 Context")
                val appContext = getApplicationCompatible()
                    ?: throw Exception("无法获取 Context")

                Log.d(TAG, "[DEBUG] answerC2C 步骤3: LiveKit.init")
                LiveKit.init(appContext)

                Log.d(TAG, "[DEBUG] answerC2C 步骤4: LiveKit.create")
                room = LiveKit.create(appContext)
                Log.d(TAG, "[DEBUG] answerC2C 步骤4完成: Room 创建成功")

                Log.d(TAG, "[DEBUG] answerC2C 步骤5: 启动事件收集协程")
                scope.launch {
                    Log.d(TAG, "[DEBUG] answerC2C 事件收集协程启动")
                    try {
                        room?.events?.events?.collect { event: RoomEvent ->
                            Log.d(TAG, "[DEBUG] answerC2C 收到 RoomEvent: ${event::class.simpleName}")
                            handleRoomEvent(event)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "[DEBUG] ❌ answerC2C 事件收集异常: ${e.message}", e)
                    }
                }

                Log.d(TAG, "[DEBUG] answerC2C 步骤6: room.connect 开始, wsURL=$wsURL")
                room!!.connect(wsURL, token)
                Log.d(TAG, "[DEBUG] answerC2C 步骤6完成: room.connect 成功")

                // ===== 步骤7: 初始化视频渲染 + 发布本地媒体 + 订阅远端媒体 =====
                initMediaAfterConnect()

                invokeSuccess(callback, "已接听来电")
                announceForAccessibility("正在接听来电")
                Log.d(TAG, "[DEBUG] answerC2C 全部成功")
            } catch (e: Throwable) {
                Log.e(TAG, "[DEBUG] ❌ answerC2CVideoCall 崩溃: ${e.javaClass.simpleName}: ${e.message}", e)
                invokeError(callback, "接听失败: ${e.javaClass.simpleName} - ${e.message}")
                sendEvent("onError", "接听失败: ${e.javaClass.simpleName} - ${e.message}")
            }
        }
    }

    // ======================== 3. 挂断 ========================

    @UniJSMethod(uiThread = false)
    fun hangupCall() {
        Log.d(TAG, "[DEBUG] hangupCall 被调用")
        stopRing()
        sendEvent("onHangup", "已挂断")
        disconnectRoom()
        announceForAccessibility("通话已挂断")
        Log.d(TAG, "[DEBUG] hangupCall 完成")
    }

    // ======================== 4. 开关摄像头 ========================

    @UniJSMethod(uiThread = true)
    fun enableVideo(enable: Boolean) {
        isVideoEnabled = enable
        Log.d(TAG, "[enableVideo] 被调用: enable=$enable")

        scope.launch(Dispatchers.Main) {
            safeEnableCamera(enable)
        }

        announceForAccessibility(if (enable) "摄像头已开启" else "摄像头已关闭")
    }

    // ======================== 5. 开关麦克风 ========================

    @UniJSMethod(uiThread = true)
    fun enableAudio(enable: Boolean) {
        isAudioEnabled = enable
        Log.d(TAG, "[enableAudio] 被调用: enable=$enable")

        scope.launch(Dispatchers.Main) {
            safeEnableMicrophone(enable)
        }

        announceForAccessibility(if (enable) "麦克风已开启" else "麦克风已关闭")
    }

    // ======================== 6. 切换前后摄像头 ========================

    @UniJSMethod(uiThread = true)
    fun switchCamera(position: String?) {
        Log.d(TAG, "[switchCamera] 被调用: position=$position")
        // LiveKit SDK 2.x 摄像头切换通过 TrackPublishOptions 或重新发布轨道实现
        // 使用安全方法替代直接调用
        val newEnableState = !isVideoEnabled
        isVideoEnabled = newEnableState
        
        scope.launch(Dispatchers.Main) {
            safeEnableCamera(newEnableState)
        }

        val msg = if ("back" == position) "已切换到后置摄像头" else "已切换到前置摄像头"
        announceForAccessibility(msg)
    }

    // ======================== 7. 视频渲染器管理 ========================

    /**
     * JS 调用：确保视频渲染组件就绪并尝试绑定媒体轨道
     * 
     * 使用方式：
     *   1. 在 nvue 页面中放置 <livekit-video-view type="local"> 和 <livekit-video-view type="remote">
     *   2. 在 startC2CVideoCall / answerC2CVideoCall 成功后调用此方法
     *   3. 或在 onConnected 事件触发时调用
     * 
     * 注意：推荐直接在 nvue 中放置 <livekit-video-view> 组件即可，组件会自动注册。
     * 此方法主要用于手动触发轨道重新绑定。
     */
    @UniJSMethod(uiThread = true)
    fun initRenderers(callback: UniJSCallback?) {
        try {
            val webrtcOk = LiveKitVideoView.isWebRtcAvailable()
            Log.d(TAG, "[RENDER] WebRTC available: $webrtcOk")

            val localRenderer = LiveKitVideoView.localViewInstance?.getRenderer()
            val remoteRenderer = LiveKitVideoView.remoteViewInstance?.getRenderer()

            Log.d(TAG, "[RENDER] 渲染组件状态: local=${if (localRenderer != null) "✅" else "⚠️ 未检测到或WebRTC不可用 <livekit-video-view type='local'>"}, remote=${if (remoteRenderer != null) "✅" else "⚠️ 未检测到或WebRTC不可用 <livekit-video-view type='remote'>"}")

            // 绑定已有的媒体轨道到渲染组件
            scope.launch {
                try {
                    val r = room ?: return@launch
                    
                    // === 绑定本地视频轨道到 local renderer ===
                    bindVideoTrackToLocalRenderer(r, localRenderer)
                    
                    // === 绑定远端视频轨道到 remote renderer ===
                    r.remoteParticipants.values.forEach { remote ->
                        bindParticipantVideoToRenderer(remote, remoteRenderer)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[RENDER] bind tracks failed: ${e.message}")
                }
            }

            invokeSuccess(callback, "ok")
        } catch (e: Exception) {
            Log.e(TAG, "[RENDER] ❌ initRenderers 失败: ${e.message}", e)
            invokeError(callback, "initRenderers 失败: ${e.message}")
        }
    }

    // ======================== 7. 全局事件监听 ========================

    @UniJSMethod(uiThread = true)
    fun onCallEvent(callback: UniJSCallback?) {
        Log.d(TAG, "[DEBUG] onCallEvent 注册回调")
        this.eventCallback = callback
    }

    // ======================== 内部方法 ========================

    /**
     * 兼容 uni-app 5.x / HBuilderX 5.x 获取 Application Context
     *
     * 5.x 已移除 mUniSDKInstance 字段，按优先级尝试多种方式获取
     */
    private fun getApplicationCompatible(): android.content.Context? {
        val ctx = detectCompatibleContext()
        return ctx?.applicationContext
    }

    /**
     * 兼容 uni-app 5.x 获取 Context（非 Application 级别）
     */
    private fun getCompatibleContext(): android.content.Context? {
        return detectCompatibleContext()
    }

    /**
     * 核心探测方法：依次尝试所有已知的 uni-app SDK 获取 Context 方式
     */
    private fun detectCompatibleContext(): android.content.Context? {
        // ===== 策略 1: UniModule 自身是否有 getContext() 方法 =====
        try {
            val method = this@LiveKitC2CCallModule.javaClass.getMethod("getContext")
            val result = method.invoke(this@LiveKitC2CCallModule)
            if (result is android.content.Context) {
                Log.d(TAG, "[COMPAT] ✅ 策略1成功: UniModule.getContext()")
                return result
            }
        } catch (e: Exception) {
            Log.d(TAG, "[COMPAT] 策略1失败 (UniModule.getContext()): ${e::class.simpleName}")
        }
        // 也尝试父类
        try {
            val method = this@LiveKitC2CCallModule.javaClass.superclass?.getMethod("getContext")
            if (method != null) {
                val result = method.invoke(this@LiveKitC2CCallModule)
                if (result is android.content.Context) {
                    Log.d(TAG, "[COMPAT] ✅ 策略1b成功: super.getContext()")
                    return result
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "[COMPAT] 策略1b失败 (super.getContext()): ${e::class.simpleName}")
        }

        // ===== 策略 2: UniModule 是否有 mContext 字段 =====
        for (fieldName in listOf("mContext", "context", "mBaseContext")) {
            try {
                val field = findField(this@LiveKitC2CCallModule.javaClass, fieldName)
                field?.isAccessible = true
                val result = field?.get(this@LiveKitC2CCallModule)
                if (result is android.content.Context) {
                    Log.d(TAG, "[COMPAT] ✅ 策略2成功: 字段 $fieldName")
                    return result
                }
            } catch (e: Exception) {
                // continue
            }
        }

        // ===== 策略 3: UniSDKInstanceManager (多种可能的类路径) =====
        val managerClassNames = listOf(
            "io.dcloud.feature.uniapp.common.UniSDKInstanceManager",
            "io.dcloud.feature.uniapp.UniSDKInstanceManager",
            "io.dcloud.common.core.UniSDKInstanceManager"
        )
        val methodNames = listOf("getCurrentInstance", "getInstance", "currentInstance")

        for (className in managerClassNames) {
            try {
                val mgr = Class.forName(className)
                for (methodName in methodNames) {
                    try {
                        val method = mgr.getMethod(methodName)
                        val instance = method.invoke(null)
                        if (instance != null) {
                            for (ctxMethod in listOf("getContext", "context")) {
                                try {
                                    val m = instance.javaClass.getMethod(ctxMethod)
                                    val ctx = m.invoke(instance) as? android.content.Context
                                    if (ctx != null) {
                                        Log.d(TAG, "[COMPAT] ✅ 策略3成功: $className.$methodName().$ctxMethod()")
                                        return ctx
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.d(TAG, "[COMPAT] 策略3: 类 $className 不存在 (${e::class.simpleName})")
            }
        }

        // ===== 策略 4: 反射查找 mUniSDKInstance（旧版兼容，已知在5.x不存在） =====
        try {
            val field = findField(this@LiveKitC2CCallModule.javaClass.superclass!!, "mUniSDKInstance")
            if (field != null) {
                field.isAccessible = true
                val instance = field.get(this@LiveKitC2CCallModule)
                if (instance != null) {
                    for (methodName in listOf("getContext", "context")) {
                        try {
                            val method = instance.javaClass.getMethod(methodName)
                            val ctx = method.invoke(instance) as? android.content.Context
                            if (ctx != null) {
                                Log.d(TAG, "[COMPAT] ✅ 策略4成功: mUniSDKInstance.$methodName()")
                                return ctx
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "[COMPAT] 策略4失败: ${e::class.simpleName}")
        }

        // ===== 策略 5: 列出 UniModule 所有可用方法和字段（诊断） =====
        logAvailableMembers()

        Log.e(TAG, "[COMPAT] ❌ 所有策略均无法获取 Context！")
        return null
    }

    /**
     * 递归查找字段（含父类）
     */
    @Suppress("UNCHECKED_CAST")
    private fun findField(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: Exception) {}
            current = current.superclass
        }
        return null
    }

    /**
     * 诊断：列出 UniModule 上所有可用成员，帮助定位正确的 API
     */
    @Suppress("UNCHECKED_CAST")
    private fun logAvailableMembers() {
        try {
            val clazz = this@LiveKitC2CCallModule.javaClass.superclass ?: return
            Log.w(TAG, "[COMPAT] === 诊断信息 ===")
            Log.w(TAG, "[COMPAT] UniModule 实际类: ${clazz.name}")

            val methods = clazz.declaredMethods.map { "${it.name}(${it.parameterTypes.joinToString(",") { it.simpleName }})" }
            Log.w(TAG, "[COMPAT] UniModule 方法列表 [${methods.size}]:")
            methods.forEach { Log.w(TAG, "[COMPAT]   - $it") }

            val parentMethods = clazz.superclass?.declaredMethods?.map { it.name }?.distinct()
            Log.w(TAG, "[COMPAT] 父类方法 [${parentMethods?.size}]: ${parentMethods?.joinToString(", ")}")

            val fields = clazz.declaredFields.map { "${it.type.simpleName} ${it.name}" }
            Log.w(TAG, "[COMPAT] UniModule 字段列表 [${fields.size}]: ${fields.joinToString(", ")}")

            val parentFields = clazz.superclass?.declaredFields?.map { "${it.type.simpleName} ${it.name}" }
            Log.w(TAG, "[COMPAT] 父类字段: ${parentFields?.joinToString(", ")}")
            Log.w(TAG, "[COMPAT] === 诊断结束 ===")
        } catch (e: Exception) {
            Log.e(TAG, "[COMPAT] 诊断失败: ${e.message}")
        }
    }

    /**
     * 处理房间事件
     */
    private fun handleRoomEvent(event: RoomEvent) {
        Log.d(TAG, "[EVENT] handleRoomEvent: ${event::class.simpleName}")
        when (event) {
            is RoomEvent.Connected -> {
                Log.d(TAG, "[EVENT] RoomEvent.Connected 处理开始")
                stopRing()
                sendEvent("onConnected", "通话已接通")
                announceForAccessibility("通话已接通")

                // 为远端参与者注册事件监听 + 订阅其媒体轨道
                val remoteCount = room?.remoteParticipants?.size ?: 0
                Log.d(TAG, "[EVENT] Connected: 远端参与者数量=$remoteCount")
                room?.remoteParticipants?.values?.forEach { remote ->
                    Log.d(TAG, "[EVENT] Connected: 观察远端参与者 ${remote.identity}")
                    observeRemoteParticipant(remote)
                    subscribeRemoteTracks(remote)
                }
                Log.d(TAG, "[EVENT] RoomEvent.Connected 处理完成")
            }
            is RoomEvent.Disconnected -> {
                stopRing()
                sendEvent("onDisconnected", "通话断开")
                announceForAccessibility("通话断开")
            }
            is RoomEvent.ParticipantConnected -> {
                Log.d(TAG, "[EVENT] ParticipantConnected: ${event.participant.identity}")
                observeRemoteParticipant(event.participant)
                subscribeRemoteTracks(event.participant)
            }
            is RoomEvent.ParticipantDisconnected -> {
                stopRing()
                sendEvent("onRemoteHangup", "对方已挂断")
                announceForAccessibility("对方已挂断")
            }
            else -> {
                Log.d(TAG, "[EVENT] 未处理的事件类型: ${event::class.simpleName}")
            }
        }
    }

    /**
     * 连接成功后：发布本地音视频 + 订阅远端媒体 + 绑定到渲染组件
     */
    private fun initMediaAfterConnect() {
        Log.d(TAG, "[DEBUG] 步骤7: 初始化媒体")
        val r = room ?: run {
            Log.e(TAG, "[DEBUG] ❌ 步骤7失败: room 为 null!")
            return
        }
        Log.d(TAG, "[DEBUG] 步骤7: room OK, localParticipant=${r.localParticipant?.identity}")

        // 7a. 确保共享 EglBase 已初始化（由 LiveKitVideoView 组件管理）
        Log.d(TAG, "[DEBUG] 步骤7a: 检查 WebRTC 可用性...")
        if (LiveKitVideoView.isWebRtcAvailable()) {
            Log.d(TAG, "[DEBUG] 步骤7a: WebRTC available, 创建 EglBase...")
            val eglBase = LiveKitVideoView.getSharedEglBase()
            Log.d(TAG, "[DEBUG] 步骤7a: EglBase created=$eglBase")
        } else {
            Log.w(TAG, "[DEBUG] 步骤7a: WebRTC not available in runtime, video rendering disabled")
        }
        Log.d(TAG, "[DEBUG] 步骤7a: EglBase check done")


        // 7b-0. 启动本地参与者轨道事件监听（用于自动绑定 video track 到 renderer）
        Log.d(TAG, "[DEBUG] 步骤7b-0: 启动本地轨道监听...")
        observeLocalParticipant(r.localParticipant)

        // 7b. 发布本地摄像头 + 麦克风轨道
        // ⚠️ v1.0.2 安全策略（重大变更）:
        //    不再自动启用麦克风/摄像头！原因：setMicrophoneEnabled/setCameraEnabled 在部分设备上触发 Native SIGSEGV
        //    改为：
        //      1. startC2CVideoCall 只负责连接 Room + 初始化渲染器
        //      2. 媒体设备通过独立的 enableMic / enableCamera 方法按需启用
        //      3. 这样即使某个设备崩溃也不会影响连接本身
        Log.d(TAG, "[DEBUG] 步骤7b: ⚠️ 跳过自动媒体启用（v1.0.2 安全策略）")
        Log.d(TAG, "[DEBUG] 步骤7b: isAudioEnabled=$isAudioEnabled, 请使用 enableMic()/enableCamera() 单独启用")
        sendEvent("onMediaReady", "Room已连接，请通过JS调用 enableMic/enableCamera 启用媒体")

        // 7d. 订阅已存在的远端参与者媒体轨道
        Log.d(TAG, "[DEBUG] 步骤7d: 订阅远端参与者...")
        r.remoteParticipants.values.forEach { remote ->
            Log.d(TAG, "[DEBUG] 步骤7d: 发现远端参与者: ${remote.identity}")
            subscribeRemoteTracks(remote)
        }
        
        Log.d(TAG, "[DEBUG] 步骤7完成: 媒体初始化完毕（注意：setCameraEnabled/setMicrophoneEnabled 是异步的）")
    }

    /**
     * 订阅远端参与者的音视频轨道并绑定到渲染组件
     */
    private fun subscribeRemoteTracks(remote: RemoteParticipant) {
        scope.launch {
            try {
                val remoteRenderer = LiveKitVideoView.remoteViewInstance?.getRenderer()
                Log.d(TAG, "[MEDIA] Subscribing remote: ${remote.identity}, renderer=${if (remoteRenderer != null) "ready" else "NOT ready (add <livekit-video-view type='remote'> in nvue)"}")
                
                // 订阅视频轨道 — 从 trackPublications 中筛选 VIDEO kind
                val videoPub = findTrackPublication(remote, Track.Kind.VIDEO)
                if (videoPub != null && remoteRenderer != null) {
                    bindTrackToRenderer(videoPub, remoteRenderer)
                    Log.d(TAG, "[MEDIA] OK remote video bound to renderer")
                    sendEvent("onRemoteVideoReady", "Remote video ready")
                } else if (videoPub == null) {
                    Log.d(TAG, "[MEDIA] No video track yet, waiting...")
                } else if (remoteRenderer == null) {
                    Log.w(TAG, "[MEDIA] Has video but renderer not initialized")
                }
                
                // 检查音频轨道
                val audioPub = findTrackPublication(remote, Track.Kind.AUDIO)
                if (audioPub != null) {
                    Log.d(TAG, "[MEDIA] OK remote audio track ready")
                    sendEvent("onRemoteAudioReady", "Remote audio ready")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[MEDIA] Failed subscribe remote: ${e.message}", e)
            }
        }
    }

    // ==================== 本地轨道监听与自动绑定 ====================
    
    /**
     * 监听本地参与者的轨道发布事件，自动绑定到 local renderer
     * 当 setCameraEnabled(true) 成功后会触发 LocalTrackPublished 事件
     */
    private fun observeLocalParticipant(local: LocalParticipant) {
        Log.d(TAG, "[LOCAL] 开始监听本地参与者轨道事件...")
        scope.launch {
            try {
                local.events.events.collect { event: ParticipantEvent ->
                    Log.d(TAG, "[LOCAL] 收到 LocalParticipantEvent: ${event::class.simpleName}")
                    when (event) {
                        is ParticipantEvent.LocalTrackPublished -> {
                            Log.d(TAG, "[LOCAL] ✅ LocalTrackPublished: kind=${event.publication.kind}, sid=${event.publication.sid}")
                            if (event.publication.kind == Track.Kind.VIDEO) {
                                // 自动绑定本地视频到 local renderer
                                val localRenderer = LiveKitVideoView.localViewInstance?.getRenderer()
                                if (localRenderer != null) {
                                    bindTrackToRenderer(event.publication, localRenderer)
                                    Log.d(TAG, "[LOCAL] ✅ 本地视频已自动绑定到 renderer")
                                    sendEvent("onLocalVideoReady", "Local video ready")
                                } else {
                                    Log.w(TAG, "[LOCAL] ⚠️ 本地视频已发布但 local renderer 未就绪（请在 nvue 中添加 <livekit-video-view type='local'>）")
                                }
                            }
                            if (event.publication.kind == Track.Kind.AUDIO) {
                                Log.d(TAG, "[LOCAL] ✅ 本地音频已发布")
                                sendEvent("onLocalAudioReady", "Local audio ready")
                            }
                        }
                        is ParticipantEvent.TrackUnpublished -> {
                            Log.d(TAG, "[LOCAL] TrackUnpublished: kind=${event.publication.kind}")
                        }
                        else -> {
                            Log.d(TAG, "[LOCAL] 未处理的本地事件: ${event::class.simpleName}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[LOCAL] ❌ 本地参与者事件监听异常: ${e.message}", e)
            }
        }
    }

    // ==================== Track 辅助方法 ====================

    /**
     * 从 Participant 的 trackPublications 中按 kind 查找第一个 TrackPublication
     * 
     * 兼容 LiveKit Android SDK 2.x:
     * - Participant.trackPublications 是 Map<String, TrackPublication>
     * - 通过 .values 遍历并按 kind 过滤
     */
    private fun findTrackPublication(participant: Participant, kind: Track.Kind): io.livekit.android.room.track.TrackPublication? {
        return try {
            participant.trackPublications.values.find { it.kind == kind }
        } catch (e: Exception) {
            Log.w(TAG, "[TRACK] findTrackPublication failed for $kind: ${e.message}")
            null
        }
    }

    // ==================== 反射视频绑定（无需 org.webrtc 编译期依赖）====================

    /**
     * 将 VideoTrack 绑定到渲染器 (SurfaceViewRenderer)
     * 
     * 策略：
     * 1. 尝试直接在 track 上找 addSink(SurfaceViewRenderer) 方法
     * 2. 如果 track 是 LiveKit VideoTrack 包装类，尝试获取其内部 webrtc track
     */
    private fun bindTrackToRenderer(
        publication: io.livekit.android.room.track.TrackPublication,
        renderer: Any
    ) {
        try {
            val track = publication.track ?: run {
                Log.w(TAG, "[TRACK] publication.track is null")
                return
            }
            
            // 策略1: 直接反射调用 addSink
            if (invokeAddSink(track, renderer)) return
            
            // 策略2: LiveKit VideoTrack 可能包装了 WebRTC VideoTrack，尝试获取内部字段/方法
            tryGetInnerTrack(track)?.let { inner ->
                if (invokeAddSink(inner, renderer)) {
                    Log.d(TAG, "[TRACK] OK bound via inner track addSink")
                }
            } ?: run {
                Log.w(TAG, "[TRACK] Cannot bind: no addSink found on ${track.javaClass.name} or inner track")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[TRACK] bindTrackToRenderer error: ${e.message}", e)
        }
    }

    /**
     * 从 VideoTrack 中尝试获取内部 WebRTC VideoTrack
     * 常见模式：LiveKit 的 VideoTrack 可能持有内部 webrtc 字段或通过方法暴露
     */
    private fun tryGetInnerTrack(track: Any): Any? {
        // 尝试常见字段名
        for (fieldName in listOf("videoTrack", "webrtcTrack", "internalTrack", "track")) {
            try {
                val field = findFieldRecursive(track.javaClass, fieldName)
                field?.isAccessible = true
                val value = field?.get(track)
                if (value != null && hasAddSink(value)) return value
            } catch (_: Exception) {}
        }
        
        // 尝试通过 getter 方法
        for (methodName in listOf("getVideoTrack", "getWebrtcTrack", "getInternalTrack")) {
            try {
                val method = track.javaClass.methods.find { it.name == methodName && it.parameterCount == 0 }
                val value = method?.invoke(track)
                if (value != null && hasAddSink(value)) return value
            } catch (_: Exception) {}
        }

        return null
    }

    /** 检查对象是否有 addSink 方法 */
    private fun hasAddSink(obj: Any): Boolean {
        return obj.javaClass.methods.any { it.name == "addSink" && it.parameterCount == 1 }
    }

    /** 反射调用 addSink(renderer) */
    private fun invokeAddSink(track: Any, renderer: Any): Boolean {
        return try {
            // 查找 addSink 方法 — 接受 VideoSink 参数（SurfaceViewRenderer 实现了它）
            // LiveKit Android SDK 2.x 使用 repackaged WebRTC: livekit.org.webrtc.*
            val sinkClass = Class.forName("livekit.org.webrtc.VideoSink")
            val method = track.javaClass.methods.find {
                it.name == "addSink" && it.parameterCount == 1 &&
                        sinkClass.isAssignableFrom(it.parameterTypes[0])
            }
            if (method != null) {
                method.invoke(track, renderer)
                Log.d(TAG, "[TRACK] OK bound via ${track.javaClass.simpleName}.addSink")
                true
            } else false
        } catch (e: Exception) {
            Log.d(TAG, "[TRACK] invokeAddSink failed: ${e.message}")
            false
        }
    }

    /** 反射调用 removeSink(renderer) */
    private fun invokeRemoveSink(track: Any, renderer: Any): Boolean {
        return try {
            val method = track.javaClass.methods.find {
                it.name == "removeSink" && it.parameterCount == 1
            }
            if (method != null) {
                method.invoke(track, renderer)
                true
            } else false
        } catch (_: Exception) { false }
    }

    /**
     * 解绑 Track 的 videoTrack 与渲染器
     */
    private fun unbindTrackFromRenderer(
        publication: io.livekit.android.room.track.TrackPublication,
        renderer: Any?
    ) {
        if (renderer == null) return
        try {
            val track = publication.track ?: return
            invokeRemoveSink(track, renderer)
            
            // 也尝试解绑 inner track
            tryGetInnerTrack(track)?.let { invokeRemoveSink(it, renderer) }
        } catch (_: Exception) {}
    }

    /** 绑定本地参与者的视频轨到 local renderer */
    private fun bindVideoTrackToLocalRenderer(r: Room, localRenderer: Any?) {
        val pub = findTrackPublication(r.localParticipant, Track.Kind.VIDEO)
        if (pub != null && localRenderer != null) {
            bindTrackToRenderer(pub, localRenderer)
            Log.d(TAG, "[RENDER] OK local video bound to renderer")
        }
    }

    /** 绑定参与者的视频轨到指定 renderer */
    private fun bindParticipantVideoToRenderer(participant: Participant, renderer: Any?) {
        val pub = findTrackPublication(participant, Track.Kind.VIDEO)
        if (pub != null && renderer != null) {
            bindTrackToRenderer(pub, renderer)
        }
    }

    /** 解绑参与者的视频轨与指定 renderer */
    private fun unbindParticipantVideo(participant: Participant, renderer: Any?) {
        val pub = findTrackPublication(participant, Track.Kind.VIDEO)
        if (pub != null) {
            unbindTrackFromRenderer(pub, renderer)
        }
    }

    /** 递归查找字段（含父类） */
    @Suppress("UNCHECKED_CAST")
    private fun findFieldRecursive(clazz: Class<*>, fieldName: String): java.lang.reflect.Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                return current.getDeclaredField(fieldName)
            } catch (_: Exception) {}
            current = current.superclass
        }
        return null
    }

    /**
     * 监听远端参与者的轨道变化
     */
    private fun observeRemoteParticipant(remote: RemoteParticipant) {
        scope.launch {
            remote.events.events.collect { event: ParticipantEvent ->
                when (event) {
                    is ParticipantEvent.TrackPublished -> {
                        when (event.publication.kind) {
                            Track.Kind.VIDEO -> {
                                sendEvent("onRemoteCameraOn", "Remote camera on")
                                announceForAccessibility("Remote camera on")
                                scope.launch {
                                    try {
                                        val remoteRenderer = LiveKitVideoView.remoteViewInstance?.getRenderer()
                                        if (remoteRenderer != null) {
                                            bindTrackToRenderer(event.publication, remoteRenderer)
                                            Log.d(TAG, "[MEDIA] New video track bound")
                                            sendEvent("onRemoteVideoReady", "Remote video ready")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "[MEDIA] Bind new video track failed: ${e.message}")
                                    }
                                }
                            }
                            Track.Kind.AUDIO -> {
                                sendEvent("onRemoteAudioOn", "Remote mic on")
                                announceForAccessibility("Remote mic on")
                            }
                            else -> {}
                        }
                    }
                    is ParticipantEvent.TrackUnpublished -> {
                        when (event.publication.kind) {
                            Track.Kind.VIDEO -> {
                                sendEvent("onRemoteCameraOff", "Remote camera off")
                                announceForAccessibility("Remote camera off")
                            }
                            Track.Kind.AUDIO -> {
                                sendEvent("onRemoteAudioOff", "Remote mic off")
                                announceForAccessibility("Remote mic off")
                            }
                            else -> {}
                        }
                    }
                    is ParticipantEvent.TrackMuted -> {
                        when (event.publication.kind) {
                            Track.Kind.VIDEO -> {
                                sendEvent("onRemoteCameraOff", "Remote camera off")
                            }
                            Track.Kind.AUDIO -> {
                                sendEvent("onRemoteAudioOff", "Remote mic off")
                            }
                            else -> {}
                        }
                    }
                    is ParticipantEvent.TrackUnmuted -> {
                        when (event.publication.kind) {
                            Track.Kind.VIDEO -> {
                                sendEvent("onRemoteCameraOn", "Remote camera on")
                            }
                            Track.Kind.AUDIO -> {
                                sendEvent("onRemoteAudioOn", "Remote mic on")
                            }
                            else -> {}
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // ======================== 安全媒体启用方法 ========================
    // ⚠️ v1.0.2: setMicrophoneEnabled/setCameraEnabled 在部分设备触发 Native SIGSEGV
    // 这些方法提供超时保护 + 细粒度日志 + 安全降级

    /**
     * 安全启用/禁用麦克风（带 Native Crash 保护）
     */
    private suspend fun safeEnableMicrophone(enable: Boolean) {
        val local = room?.localParticipant
        Log.d(TAG, "[MIC] safeEnableMicrophone: enable=$enable, lp=${local?.identity}, currentMic=${local?.isMicrophoneEnabled}")
        
        if (local == null) {
            Log.w(TAG, "[MIC] ❌ localParticipant 为 null，跳过")
            sendEvent("onError", "麦克风操作失败: 未连接到房间")
            return
        }
        if (local.isMicrophoneEnabled == enable) {
            Log.d(TAG, "[MIC] ⏭️ 麦克风状态已是 $enable，跳过")
            return
        }

        try {
            // 使用独立线程执行 + 超时保护
            val result = withTimeoutOrNull(8000L) {
                Log.d(TAG, "[MIC] >>> 调用 setMicrophoneEnabled($enable) <<< [thread=${Thread.currentThread().name}]")
                local.setMicrophoneEnabled(enable)
                Log.d(TAG, "[MIC] <<< setMicrophoneEnabled($enable) 已返回 >>>")
                true
            }
            
            when {
                result == true -> {
                    Log.d(TAG, "[MIC] ✅ 麦克风 ${if(enable)"开启" else "关闭"} 成功 (actual=${local.isMicrophoneEnabled})")
                    sendEvent("onAudioStateChanged", if(enable) "mic_on" else "mic_off")
                }
                else -> {
                    Log.e(TAG, "[MIC] ⚠️ 操作超时 (8s)，可能 Native 层卡死或崩溃")
                    sendEvent("onError", "麦克风操作超时(8s)，设备可能不支持")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[MIC] ❌ Java 异常: ${e.javaClass.simpleName}: ${e.message}", e)
            sendEvent("onError", "麦克风异常: ${e.message}")
        } catch (e: Throwable) {
            Log.e(TAG, "[MIC] 💥 致命错误(Native?): ${e.javaClass.simpleName}: ${e.message}")
            sendEvent("onFatalError", "Native层错误(MIC): ${e.javaClass.simpleName}")
        }
    }

    /**
     * 安全启用/禁用摄像头（带 Native Crash 保护）
     */
    private suspend fun safeEnableCamera(enable: Boolean) {
        val local = room?.localParticipant
        Log.d(TAG, "[CAM] safeEnableCamera: enable=$enable, lp=${local?.identity}, currentCam=${local?.isCameraEnabled}")

        if (local == null) {
            Log.w(TAG, "[CAM] ❌ localParticipant 为 null，跳过")
            sendEvent("onError", "摄像头操作失败: 未连接到房间")
            return
        }
        if (local.isCameraEnabled == enable) {
            Log.d(TAG, "[CAM] ⏭️ 摄像头状态已是 $enable，跳过")
            return
        }

        try {
            val result = withTimeoutOrNull(12000L) {
                Log.d(TAG, "[CAM] >>> 调用 setCameraEnabled($enable) <<< [thread=${Thread.currentThread().name}, Looper=${if(android.os.Looper.myLooper() != null) "OK" else "NULL"}]")
                local.setCameraEnabled(enable)
                Log.d(TAG, "[CAM] <<< setCameraEnabled($enable) 已返回 >>>")
                true
            }
            
            when {
                result == true -> {
                    Log.d(TAG, "[CAM] ✅ 摄像头 ${if(enable)"开启" else "关闭"} 成功 (actual=${local.isCameraEnabled})")
                    sendEvent("onVideoStateChanged", if(enable) "cam_on" else "cam_off")
                    // 如果开启了摄像头，尝试绑定到本地渲染器
                    if (enable) bindLocalVideoIfNeeded()
                }
                else -> {
                    Log.e(TAG, "[CAM] ⚠️ 操作超时 (12s)，可能 Native 层卡死或崩溃")
                    sendEvent("onError", "摄像头操作超时(12s)，设备可能不支持")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[CAM] ❌ Java 异常: ${e.javaClass.simpleName}: ${e.message}", e)
            sendEvent("onError", "摄像头异常: ${e.message}")
        } catch (e: Throwable) {
            Log.e(TAG, "[CAM] 💥 致命错误(Native?): ${e.javaClass.simpleName}: ${e.message}")
            sendEvent("onFatalError", "Native层错误(CAM): ${e.javaClass.simpleName}")
        }
    }

    /**
     * 尝试将本地视频轨道绑定到渲染器
     */
    private fun bindLocalVideoIfNeeded() {
        try {
            val r = room ?: return
            val localVideoPub = findTrackPublication(r.localParticipant, Track.Kind.VIDEO)
            val renderer = LiveKitVideoView.localViewInstance?.getRenderer()
            
            if (localVideoPub != null && renderer != null) {
                bindTrackToRenderer(localVideoPub, renderer)
                Log.d(TAG, "[BIND] ✅ 本地视频已绑定到 renderer")
                sendEvent("onLocalVideoReady", "Local video bound to renderer")
            } else {
                Log.d(TAG, "[BIND] videoPub=${if(localVideoPub != null) "OK" else "null"}, renderer=${if(renderer != null) "OK" else "null"}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[BIND] 绑定失败: ${e.message}")
        }
    }

    /**
     * 断开房间连接
     */
    private fun disconnectRoom() {
        Log.d(TAG, "[DEBUG] disconnectRoom 被调用, room=$room")

        // 从渲染组件解绑视频轨道
        val localRenderer = LiveKitVideoView.localViewInstance?.getRenderer()
        val remoteRenderer = LiveKitVideoView.remoteViewInstance?.getRenderer()
        
        scope.launch {
            try {
                val r = room ?: return@launch
                val localRenderer = LiveKitVideoView.localViewInstance?.getRenderer()
                val remoteRenderer = LiveKitVideoView.remoteViewInstance?.getRenderer()
                
                // Unbind local video track
                unbindParticipantVideo(r.localParticipant, localRenderer)
                // Unbind remote video tracks
                r.remoteParticipants.values.forEach { remote ->
                    unbindParticipantVideo(remote, remoteRenderer)
                }
            } catch (e: Exception) {
                // ignore cleanup errors
            }
        }

        room?.let {
            Log.d(TAG, "[DEBUG] disconnectRoom: 启动异步 disconnect")
            scope.launch {
                try {
                    it.disconnect()
                    Log.d(TAG, "[DEBUG] disconnectRoom: disconnect 成功")
                } catch (e: Exception) {
                    Log.e(TAG, "[DEBUG] ❌ disconnectRoom 异常: ${e.message}", e)
                }
            }
        }
        room = null
    }

    /**
     * 解析音频发布配置
     */
    private fun parseAudioPublishOptions(audioOpts: Map<String, Any>?) {
        if (audioOpts == null) {
            isAudioEnabled = true // 默认开启
            return
        }
        // 支持多种格式: "enabled": false / "enable": false / 直接传入 Boolean
        isAudioEnabled = when {
            audioOpts["enabled"] is Boolean -> audioOpts["enabled"] as Boolean
            audioOpts["enable"] is Boolean -> audioOpts["enable"] as Boolean
            audioOpts is Map<*, *> && audioOpts.containsKey("enabled") -> (audioOpts["enabled"] as? Boolean) ?: true
            else -> true // 默认开启
        }
        Log.d(TAG, "[AUDIO] 解析音频选项完成: isAudioEnabled=$isAudioEnabled")
    }

    /**
     * 播放铃声
     */
    private fun playRing(ringPath: String?) {
        stopRing()
        if (ringPath.isNullOrEmpty()) return

        try {
            val context = getCompatibleContext() ?: return
            ringPlayer = MediaPlayer().apply {
                setDataSource(context, android.net.Uri.parse(ringPath))
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            // 铃声播放失败不影响通话
        }
    }

    /**
     * 停止铃声
     */
    private fun stopRing() {
        ringPlayer?.let {
            try {
                it.stop()
                it.release()
            } catch (e: Exception) {
                // ignore
            }
        }
        ringPlayer = null
    }

    /**
     * 发送事件到 JS 回调
     */
    private fun sendEvent(event: String, msg: String) {
        Log.d(TAG, "[DEBUG] sendEvent: $event - $msg")
        eventCallback?.invoke(mapOf("event" to event, "msg" to msg))
    }

    /**
     * 调用成功回调
     */
    private fun invokeSuccess(callback: UniJSCallback?, message: String) {
        callback?.invoke(mapOf("code" to 0, "msg" to message))
    }

    /**
     * 调用错误回调
     */
    private fun invokeError(callback: UniJSCallback?, message: String) {
        callback?.invoke(mapOf("code" to -1, "msg" to message))
    }

    /**
     * 无障碍：通过 AccessibilityManager 朗读状态变化
     */
    private fun announceForAccessibility(message: String) {
        try {
            val context = getCompatibleContext() ?: return
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
            if (am.isEnabled) {
                val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
                event.text.add(message)
                am.sendAccessibilityEvent(event)
            }
        } catch (e: Exception) {
            // 无障碍功能不应阻塞主流程
        }
    }

    // ======================== 生命周期 ========================

    override fun onActivityDestroy() {
        super.onActivityDestroy()
        Log.d(TAG, "[DEBUG] ===== onActivityDestroy =====")
        stopRing()

        // 释放共享 EglBase（由 LiveKitVideoView 组件管理）
        LiveKitVideoView.releaseSharedEglBase()

        disconnectRoom()
        scope.cancel()
        Log.d(TAG, "[DEBUG] onActivityDestroy 完成")
    }
}
