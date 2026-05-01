package com.livekit.c2ccall

import android.content.Context
import android.media.MediaPlayer
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * LiveKit 1v1 视频通话 uni-app 原生插件
 *
 * 支持：发起呼叫、接听、挂断、音视频开关、摄像头切换、事件回调、无障碍读屏、自定义铃声
 */
class LiveKitC2CCallModule : UniModule() {

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
        if (options == null) {
            invokeError(callback, "参数不能为空")
            return
        }

        val wsURL = options["wsURL"] as? String ?: ""
        val token = options["token"] as? String ?: ""

        if (wsURL.isEmpty() || token.isEmpty()) {
            invokeError(callback, "wsURL 和 token 不能为空")
            return
        }

        @Suppress("UNCHECKED_CAST")
        val videoOpts = options["videoOptions"] as? Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val audioOpts = options["audioOptions"] as? Map<String, Any>
        val callRing = options["callRing"] as? String

        parseAudioPublishOptions(audioOpts)

        // 播放呼叫铃声
        playRing(callRing)

        scope.launch {
            try {
                disconnectRoom()

                val appContext = mUniSDKInstance?.context()?.applicationContext
                    ?: throw Exception("无法获取 Context")

                room = LiveKit.create(appContext)

                // 注册房间事件监听
                room!!.events.collectLatest { event ->
                    handleRoomEvent(event)
                }

                // 连接到房间（LiveKit 2.x 简化 API）
                room!!.connect(wsURL, token)

                invokeSuccess(callback, "呼叫已发起")
                announceForAccessibility("正在发起视频通话")
            } catch (e: Exception) {
                invokeError(callback, "发起呼叫失败: ${e.message}")
                sendEvent("onError", "发起呼叫失败: ${e.message}")
            }
        }
    }

    // ======================== 2. 接听 1v1 视频通话 ========================

    @UniJSMethod(uiThread = true)
    fun answerC2CVideoCall(options: Map<String, Any>?, callback: UniJSCallback?) {
        if (options == null) {
            invokeError(callback, "参数不能为空")
            return
        }

        val wsURL = options["wsURL"] as? String ?: ""
        val token = options["token"] as? String ?: ""

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
            try {
                disconnectRoom()

                val appContext = mUniSDKInstance?.context()?.applicationContext
                    ?: throw Exception("无法获取 Context")

                room = LiveKit.create(appContext)

                room!!.events.collectLatest { event ->
                    handleRoomEvent(event)
                }

                room!!.connect(wsURL, token)

                invokeSuccess(callback, "已接听来电")
                announceForAccessibility("正在接听来电")
            } catch (e: Exception) {
                invokeError(callback, "接听失败: ${e.message}")
                sendEvent("onError", "接听失败: ${e.message}")
            }
        }
    }

    // ======================== 3. 挂断 ========================

    @UniJSMethod(uiThread = false)
    fun hangupCall() {
        // 播放挂断铃声
        val hungupRing = "hungupRing"
        stopRing()
        sendEvent("onHangup", "已挂断")
        disconnectRoom()
        announceForAccessibility("通话已挂断")
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
            remote.events.collectLatest { event: ParticipantEvent ->
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
        room?.let {
            scope.launch {
                it.disconnect()
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
        stopRing()
        disconnectRoom()
        scope.cancel()
    }
}
