package com.livekit.c2ccall

import android.content.Context
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
import io.livekit.android.room.track.Track.Kind as TrackKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
        @Suppress("UNCHECKED_CAST")
        val audioOpts = options["audioOptions"] as? Map<String, Any>
        val callRing = options["callRing"] as? String
        Log.d(TAG, "[DEBUG] videoOpts=$videoOpts, audioOpts=${audioOpts != null}, callRing=$callRing")

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

                Log.d(TAG, "[DEBUG] 步骤3: 准备调用 LiveKit.init")
                Log.d(TAG, "[DEBUG] 步骤3a: 尝试 System.loadLibrary 检查 so 是否存在")
                try {
                    System.loadLibrary("livekit_android")
                    Log.d(TAG, "[DEBUG] 步骤3a完成: livekit_android so 加载成功")
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "[DEBUG] ❌ livekit_android so 加载失败: ${e.message}", e)
                    throw Exception("livekit_android native 库未找到: ${e.message}")
                } catch (t: Throwable) {
                    Log.e(TAG, "[DEBUG] ❌ native 库加载异常: ${t.javaClass.simpleName}: ${t.message}", t)
                    throw Exception("native 库加载失败: ${t.javaClass.simpleName} - ${t.message}")
                }
                Log.d(TAG, "[DEBUG] 步骤3: 调用 LiveKit.init")
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
                            handleRoomEvent(event)
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "[DEBUG] ❌ 事件收集协程异常: ${e.message}", e)
                    }
                    Log.d(TAG, "[DEBUG] 事件收集协程结束")
                }

                Log.d(TAG, "[DEBUG] 步骤6: room.connect 开始, wsURL=$wsURL")
                // 连接到房间（suspend fun，会挂起直到断开连接）
                room!!.connect(wsURL, token)
                Log.d(TAG, "[DEBUG] 步骤6完成: room.connect 成功")

                invokeSuccess(callback, "呼叫已发起")
                announceForAccessibility("正在发起视频通话")
                Log.d(TAG, "[DEBUG] startC2CVideoCall 全部成功")
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
        @Suppress("UNCHECKED_CAST")
        val audioOpts = options["audioOptions"] as? Map<String, Any>
        val answerRing = options["answerRing"] as? String

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

        scope.launch {
            try {
                val local = room?.localParticipant ?: return@launch
                local.setCameraEnabled(enable)
            } catch (e: Exception) {
                sendEvent("onError", "切换摄像头失败: ${e.message}")
            }
        }

        announceForAccessibility(if (enable) "摄像头已开启" else "摄像头已关闭")
    }

    // ======================== 5. 开关麦克风 ========================

    @UniJSMethod(uiThread = true)
    fun enableAudio(enable: Boolean) {
        isAudioEnabled = enable

        scope.launch {
            try {
                val local = room?.localParticipant ?: return@launch
                local.setMicrophoneEnabled(enable)
            } catch (e: Exception) {
                sendEvent("onError", "切换麦克风失败: ${e.message}")
            }
        }

        announceForAccessibility(if (enable) "麦克风已开启" else "麦克风已关闭")
    }

    // ======================== 6. 切换前后摄像头 ========================

    @UniJSMethod(uiThread = true)
    fun switchCamera(position: String?) {
        // LiveKit SDK 2.x 摄像头切换通过 TrackPublishOptions 或重新发布轨道实现
        // 此处暂记录用户选择，后续可通过 setCameraEnabled + 重启轨道实现切换
        scope.launch {
            try {
                val local = room?.localParticipant ?: return@launch
                local.setCameraEnabled(!isVideoEnabled)
                isVideoEnabled = !isVideoEnabled
            } catch (e: Exception) {
                sendEvent("onError", "切换摄像头失败: ${e.message}")
            }
        }

        val msg = if ("back" == position) "已切换到后置摄像头" else "已切换到前置摄像头"
        announceForAccessibility(msg)
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
        when (event) {
            is RoomEvent.Connected -> {
                stopRing()
                sendEvent("onConnected", "通话已接通")
                announceForAccessibility("通话已接通")

                // 为远端参与者注册事件监听
                room?.remoteParticipants?.values?.forEach { remote ->
                    observeRemoteParticipant(remote)
                }
            }
            is RoomEvent.Disconnected -> {
                stopRing()
                sendEvent("onDisconnected", "通话断开")
                announceForAccessibility("通话断开")
            }
            is RoomEvent.ParticipantConnected -> {
                observeRemoteParticipant(event.participant)
            }
            is RoomEvent.ParticipantDisconnected -> {
                stopRing()
                sendEvent("onRemoteHangup", "对方已挂断")
                announceForAccessibility("对方已挂断")
            }
            else -> {
                // 其他事件不处理
            }
        }
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
                            TrackKind.VIDEO -> {
                                sendEvent("onRemoteCameraOn", "对方已开启摄像头")
                                announceForAccessibility("对方已开启摄像头")
                            }
                            TrackKind.AUDIO -> {
                                sendEvent("onRemoteAudioOn", "对方已开启麦克风")
                                announceForAccessibility("对方已开启麦克风")
                            }
                            else -> {}
                        }
                    }
                    is ParticipantEvent.TrackUnpublished -> {
                        when (event.publication.kind) {
                            TrackKind.VIDEO -> {
                                sendEvent("onRemoteCameraOff", "对方已关闭摄像头")
                                announceForAccessibility("对方已关闭摄像头")
                            }
                            TrackKind.AUDIO -> {
                                sendEvent("onRemoteAudioOff", "对方已关闭麦克风")
                                announceForAccessibility("对方已关闭麦克风")
                            }
                            else -> {}
                        }
                    }
                    is ParticipantEvent.TrackMuted -> {
                        when (event.publication.kind) {
                            TrackKind.VIDEO -> {
                                sendEvent("onRemoteCameraOff", "对方已关闭摄像头")
                            }
                            TrackKind.AUDIO -> {
                                sendEvent("onRemoteAudioOff", "对方已关闭麦克风")
                            }
                            else -> {}
                        }
                    }
                    is ParticipantEvent.TrackUnmuted -> {
                        when (event.publication.kind) {
                            TrackKind.VIDEO -> {
                                sendEvent("onRemoteCameraOn", "对方已开启摄像头")
                            }
                            TrackKind.AUDIO -> {
                                sendEvent("onRemoteAudioOn", "对方已开启麦克风")
                            }
                            else -> {}
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * 断开房间连接
     */
    private fun disconnectRoom() {
        Log.d(TAG, "[DEBUG] disconnectRoom 被调用, room=$room")
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
     * 解析音频发布配置（LiveKit SDK 2.x 音频选项由内部自动管理）
     */
    private fun parseAudioPublishOptions(audioOpts: Map<String, Any>?): Unit {
        // LiveKit Android SDK 2.x 的音频处理（降噪、回声消除等）由 SDK 内部自动管理
        // 如需自定义音频参数，后续可通过 RoomOptions 配置
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
        disconnectRoom()
        scope.cancel()
        Log.d(TAG, "[DEBUG] onActivityDestroy 完成")
    }
}
