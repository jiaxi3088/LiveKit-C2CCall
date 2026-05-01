package com.livekit.c2ccall;

import android.content.Context;
import android.media.MediaPlayer;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

import io.dcloud.feature.uniapp.annotation.UniJSMethod;
import io.dcloud.feature.uniapp.bridge.JSCallback;
import io.dcloud.feature.uniapp.bridge.UniJSCallback;
import io.dcloud.feature.uniapp.common.UniModule;
import io.livekit.android.LiveKit;
import io.livekit.android.room.Room;
import io.livekit.android.events.RoomEvent;
import io.livekit.android.events.ParticipantEvent;
import io.livekit.android.room.participant.LocalParticipant;
import io.livekit.android.room.participant.RemoteParticipant;
import io.livekit.android.room.track.CameraPosition;
import io.livekit.android.room.track.Track;
import android.os.Handler;
import android.os.Looper;

/**
 * LiveKit 1v1 视频通话 uni-app 原生插件（纯 Java 版本）
 *
 * 功能：发起呼叫 / 接听 / 挂断 / 音视频开关 / 摄像头切换 / 事件回调 / 无障碍读屏 / 自定义铃声
 *
 * 注意：LiveKit Android SDK 2.x 是 Kotlin 编写的，此模块通过 Java 调用 Kotlin API。
 */
public class LiveKitC2CCallModule extends UniModule {

    private Room room;
    private UniJSCallback eventCallback;
    private boolean isVideoEnabled = true;
    private boolean isAudioEnabled = true;
    private MediaPlayer ringPlayer = null;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ======================== 1. 发起 1v1 视频呼叫 ========================

    @UniJSMethod(uiThread = true)
    public void startC2CVideoCall(final JSONObject options, final JSCallback callback) {
        if (options == null) {
            invokeError(callback, "参数不能为空");
            return;
        }

        try {
            String wsURL = options.optString("wsURL", "");
            String token = options.optString("token", "");

            if (wsURL.isEmpty() || token.isEmpty()) {
                invokeError(callback, "wsURL 和 token 不能为空");
                return;
            }

            String callRing = options.optString("callRing", "");

            // 播放呼叫铃声
            playRing(callRing);

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        disconnectRoom();

                        Context appContext = mUniSDKInstance.context().getApplicationContext();
                        room = LiveKit.create(appContext);

                        // 注册房间事件监听
                        room.getEvents().collect(event -> {
                            handleRoomEvent((RoomEvent) event);
                            return null;
                        });

                        // 连接到房间
                        room.connect(wsURL, token);

                        invokeSuccess(callback, "呼叫已发起");
                        announceForAccessibility("正在发起视频通话");

                    } catch (Exception e) {
                        invokeError(callback, "发起呼叫失败: " + e.getMessage());
                        sendEvent("onError", e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            invokeError(callback, "参数解析错误: " + e.getMessage());
        }
    }

    // ======================== 2. 接听 1v1 视频通话 ========================

    @UniJSMethod(uiThread = true)
    public void answerC2CVideoCall(final JSONObject options, final JSCallback callback) {
        if (options == null) {
            invokeError(callback, "参数不能为空");
            return;
        }

        try {
            String wsURL = options.optString("wsURL", "");
            String token = options.optString("token", "");

            if (wsURL.isEmpty() || token.isEmpty()) {
                invokeError(callback, "wsURL 和 token 不能为空");
                return;
            }

            final String answerRing = options.optString("answerRing", "");

            playRing(answerRing);

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        disconnectRoom();

                        Context appContext = mUniSDKInstance.context().getApplicationContext();
                        room = LiveKit.create(appContext);

                        room.getEvents().collect(event -> {
                            handleRoomEvent((RoomEvent) event);
                            return null;
                        });

                        room.connect(wsURL, token);

                        invokeSuccess(callback, "已接听来电");
                        announceForAccessibility("正在接听来电");

                    } catch (Exception e) {
                        invokeError(callback, "接听失败: " + e.getMessage());
                        sendEvent("onError", e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            invokeError(callback, "参数解析错误: " + e.getMessage());
        }
    }

    // ======================== 3. 挂断 ========================

    @UniJSMethod(uiThread = false)
    public void hangupCall() {
        stopRing();
        sendEvent("onHangup", "已挂断");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                disconnectRoom();
            }
        });
        announceForAccessibility("通话已挂断");
    }

    // ======================== 4. 开关摄像头 ========================

    @UniJSMethod(uiThread = true)
    public void enableVideo(final boolean enable) {
        isVideoEnabled = enable;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (room != null && room.getLocalParticipant() != null) {
                        LocalParticipant local = room.getLocalParticipant();
                        local.setCameraEnabled(enable);
                    }
                } catch (Exception e) {
                    sendEvent("onError", "切换摄像头失败: " + e.getMessage());
                }
            }
        });
        announceForAccessibility(enable ? "摄像头已开启" : "摄像头已关闭");
    }

    // ======================== 5. 开关麦克风 ========================

    @UniJSMethod(uiThread = true)
    public void enableAudio(final boolean enable) {
        isAudioEnabled = enable;
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (room != null && room.getLocalParticipant() != null) {
                        LocalParticipant local = room.getLocalParticipant();
                        local.setMicrophoneEnabled(enable);
                    }
                } catch (Exception e) {
                    sendEvent("onError", "切换麦克风失败: " + e.getMessage());
                }
            }
        });
        announceForAccessibility(enable ? "麦克风已开启" : "麦克风已关闭");
    }

    // ======================== 6. 切换前后摄像头 ========================

    @UniJSMethod(uiThread = true)
    public void switchCamera(final String position) {
        final CameraPosition cameraPos = "back".equals(position)
                ? CameraPosition.BACK
                : CameraPosition.FRONT;

        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (room != null && room.getLocalParticipant() != null) {
                        LocalParticipant local = room.getLocalParticipant();
                        local.setCameraPosition(cameraPos);
                    }
                } catch (Exception e) {
                    sendEvent("onError", "切换摄像头失败: " + e.getMessage());
                }
            }
        });

        String msg = "back".equals(position) ? "已切换到后置摄像头" : "已切换到前置摄像头";
        announceForAccessibility(msg);
    }

    // ======================== 7. 全局事件监听 ========================

    @UniJSMethod(uiThread = true)
    public void onCallEvent(JSCallback callback) {
        this.eventCallback = callback;
    }

    // ======================== 内部方法 ========================

    /**
     * 处理房间事件并转发给 JS 层
     */
    private void handleRoomEvent(RoomEvent event) {
        if (event instanceof RoomEvent.Connected) {
            stopRing();
            sendEvent("onConnected", "通话已接通");
            announceForAccessibility("通话已接通");
            // 为远端参与者注册事件监听
            observeRemoteParticipants();
        } else if (event instanceof RoomEvent.Disconnected) {
            stopRing();
            sendEvent("onDisconnected", "通话断开");
            announceForAccessibility("通话断开");
        } else if (event instanceof RoomEvent.ParticipantDisconnected) {
            stopRing();
            sendEvent("onRemoteHangup", "对方已挂断");
            announceForAccessibility("对方已挂断");
        }
    }

    /**
     * 监听远端参与者的轨道变化事件
     */
    private void observeRemoteParticipants() {
        if (room == null) return;
        for (Map.Entry<String, RemoteParticipant> entry : room.getRemoteParticipants().entrySet()) {
            final RemoteParticipant remote = entry.getValue();
            remote.getEvents().collect(participantEvent -> {
                handleParticipantEvent((ParticipantEvent) participantEvent);
                return null;
            });
        }
    }

    /**
     * 处理远端参与者的事件（发布/取消发布/静音/取消静音）
     */
    private void handleParticipantEvent(ParticipantEvent participantEvent) {
        if (participantEvent instanceof ParticipantEvent.TrackPublished) {
            ParticipantEvent.TrackPublished tp = (ParticipantEvent.TrackPublished) participantEvent;
            Track.Kind kind = tp.publication().getKind();
            if (kind == Track.Kind.VIDEO) {
                sendEvent("onRemoteCameraOn", "对方已开启摄像头");
            } else if (kind == Track.Kind.AUDIO) {
                sendEvent("onRemoteAudioOn", "对方已开启麦克风");
            }
        } else if (participantEvent instanceof ParticipantEvent.TrackUnpublished) {
            ParticipantEvent.TrackUnpublished tu = (ParticipantEvent.TrackUnpublished) participantEvent;
            Track.Kind kind = tu.publication().getKind();
            if (kind == Track.Kind.VIDEO) {
                sendEvent("onRemoteCameraOff", "对方已关闭摄像头");
            } else if (kind == Track.Kind.AUDIO) {
                sendEvent("onRemoteAudioOff", "对方已关闭麦克风");
            }
        } else if (participantEvent instanceof ParticipantEvent.TrackMuted) {
            ParticipantEvent.TrackMuted tm = (ParticipantEvent.TrackMuted) participantEvent;
            Track.Kind kind = tm.publication().getKind();
            if (kind == Track.Kind.VIDEO) {
                sendEvent("onRemoteCameraOff", "对方已关闭摄像头");
            } else if (kind == Track.Kind.AUDIO) {
                sendEvent("onRemoteAudioOff", "对方已关闭麦克风");
            }
        } else if (participantEvent instanceof ParticipantEvent.TrackUnmuted) {
            ParticipantEvent.TrackUnmuted tum = (ParticipantEvent.TrackUnmuted) participantEvent;
            Track.Kind kind = tum.publication().getKind();
            if (kind == Track.Kind.VIDEO) {
                sendEvent("onRemoteCameraOn", "对方已开启摄像头");
            } else if (kind == Track.Kind.AUDIO) {
                sendEvent("onRemoteAudioOn", "对方已开启麦克风");
            }
        }
    }

    /**
     * 断开房间连接
     */
    private void disconnectRoom() {
        if (room != null) {
            room.disconnect();
            room = null;
        }
    }

    /**
     * 播放铃声
     */
    private void playRing(String ringPath) {
        stopRing();
        if (ringPath == null || ringPath.isEmpty()) return;
        try {
            Context context = mUniSDKInstance.context();
            ringPlayer = new MediaPlayer();
            ringPlayer.setDataSource(context, android.net.Uri.parse(ringPath));
            ringPlayer.setLooping(true);
            ringPlayer.prepare();
            ringPlayer.start();
        } catch (Exception e) {
            // 铃声播放失败不影响通话
        }
    }

    /**
     * 停止铃声播放
     */
    private void stopRing() {
        if (ringPlayer != null) {
            try {
                ringPlayer.stop();
                ringPlayer.release();
            } catch (Exception e) { /* ignore */ }
            ringPlayer = null;
        }
    }

    /**
     * 发送事件到 JS 回调
     */
    private void sendEvent(String event, String msg) {
        if (eventCallback != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("event", event);
            result.put("msg", msg);
            eventCallback.invoke(result);
        }
    }

    /**
     * 调用成功回调
     */
    private void invokeSuccess(JSCallback callback, String message) {
        if (callback != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", 0);
            result.put("msg", message);
            callback.invoke(result);
        }
    }

    /**
     * 调用错误回调
     */
    private void invokeError(JSCallback callback, String message) {
        if (callback != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("code", -1);
            result.put("msg", message);
            callback.invoke(result);
        }
    }

    /**
     * 无障碍：通过 AccessibilityManager 朗读状态变化
     */
    private void announceForAccessibility(String message) {
        try {
            Context context = mUniSDKInstance.context();
            if (context == null) return;
            AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null && am.isEnabled()) {
                AccessibilityEvent event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT);
                event.getText().add(message);
                am.sendAccessibilityEvent(event);
            }
        } catch (Exception e) {
            // 无障碍功能不应阻塞主流程
        }
    }

    // ======================== 生命周期 ========================

    @Override
    public void destroy() {
        super.destroy();
        stopRing();
        disconnectRoom();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
