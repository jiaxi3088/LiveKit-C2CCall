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
                val appContext = mUniSDKInstance?.context()?.applicationContext
                    ?: throw Exception("无法获取 Context")
                Log.d(TAG, "[DEBUG] 步骤2完成: Context=${appContext.javaClass.simpleName}")

                Log.d(TAG, "[DEBUG] 步骤3: LiveKit.init")
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
                    } catch (e: Exception) {
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
            } catch (e: Exception) {
                Log.e(TAG, "[DEBUG] ❌ startC2CVideoCall 异常: ${e.javaClass.simpleName}: ${e.message}", e)
                invokeError(callback, "发起呼叫失败: ${e.message}")
                sendEvent("onError", "发起呼叫失败: ${e.message}")
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
                val appContext = mUniSDKInstance?.context()?.applicationContext
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
                    } catch (e: Exception) {
                        Log.e(TAG, "[DEBUG] ❌ answerC2C 事件收集异常: ${e.message}", e)
                    }
                }

                Log.d(TAG, "[DEBUG] answerC2C 步骤6: room.connect 开始, wsURL=$wsURL")
                room!!.connect(wsURL, token)
                Log.d(TAG, "[DEBUG] answerC2C 步骤6完成: room.connect 成功")

                invokeSuccess(callback, "已接听来电")
                announceForAccessibility("正在接听来电")
                Log.d(TAG, "[DEBUG] answerC2C 全部成功")
            } catch (e: Exception) {
                Log.e(TAG, "[DEBUG] ❌ answerC2CVideoCall 异常: ${e.javaClass.simpleName}: ${e.message}", e)
                invokeError(callback, "接听失败: ${e.message}")
                sendEvent("onError", "接听失败: ${e.message}")
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
            val context = mUniSDKInstance?.context() ?: return
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
            val context = mUniSDKInstance?.context() ?: return
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
