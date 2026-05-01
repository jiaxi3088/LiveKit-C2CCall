# LiveKit-C2CCall uni-app 原生插件使用文档

> 基于 LiveKit SDK 实现 1v1 视频通话，支持 Android + iOS 双端，含无障碍支持。

## 一、插件信息

| 项目 | 值 |
|------|-----|
| 插件ID | `LiveKit-C2CCall` |
| 版本 | 1.0.0 |
| 平台 | Android (minSdk 24) + iOS 14.0+ |
| 依赖 | LiveKit Android SDK 2.0.0 / LiveKitClient Swift ~1.8.0 |

---

## 二、安装步骤

### 2.1 HBuilderX 导入

1. 打开 **HBuilderX** → 项目根目录 `nativeplugins/` 下创建 `LiveKit-C2CCall/`
2. 将本插件整个目录复制到该路径下：
   ```
   nativeplugins/LiveKit-C2CCall/
   ├── package.json
   ├── android/
   │   └── livekit-c2c.aar
   ├── ios/
   │   └── LiveKitC2CCall.framework
   └── doc.md
   ```

### 2.2 manifest.json 配置

在 **manifest.json → App原生插件配置** 中勾选 `LiveKit-C2CCall`。

Android 权限会自动添加，iOS 权限已在 Info.plist 中声明。

### 2.3 云打包

使用 HBuilderX **云打包**（自定义基座或正式包），本地标准运行不支持原生插件。

---

## 三、API 参考

### 3.1 引入插件

```javascript
const LiveKitC2CCall = uni.requireNativePlugin('LiveKit-C2CCall');
```

### 3.2 startC2CVideoCall - 发起呼叫

```javascript
LiveKitC2CCall.startC2CVideoCall({
  wsURL: "wss://your-livekit-server",
  token: "user-token-from-your-server",
  userInfo: {
    nickname: "张三",
    avatar: "https://example.com/avatar.png",
    identity: "uid_001"
  },
  callerUserInfo: {
    nickname: "李四",
    avatar: "https://example.com/avatar2.png",
    identity: "uid_002"
  },
  videoOptions: {
    position: "front",       // front(默认) | back
    encoding: "QHD"          // QVGA | VGA | QHD(默认) | HD | FHD
  },
  audioOptions: {
    bitrate: 20000,           // 比特率
    dtx: true,                // 离散传输
    noiseSuppression: true,   // 降噪
    echoCancellation: true,   // 回声消除
    autoGainControl: true,    // 自动增益
    highPassFilter: true,     // 高通滤波
    typingNoiseDetection: true // 键盘噪声检测
  }
}, (res) => {
  if (res.code === 0) {
    console.log('呼叫已发起:', res.msg);
  } else {
    console.error('发起失败:', res.msg);
  }
});
```

### 3.3 answerC2CVideoCall - 接听来电

```javascript
LiveKitC2CCall.answerC2CVideoCall({
  wsURL: "wss://your-livekit-server",
  token: "answer-token",
  userInfo: { ... },
  callerUserInfo: { ... }
}, (res) => {
  console.log(res);
});
```

> 参数结构同 `startC2CVideoCall`

### 3.4 hangupCall - 挂断

```javascript
// 无参调用，挂断当前通话
LiveKitC2CCall.hangupCall();
```

### 3.5 enableVideo - 开关摄像头

```javascript
LiveKitC2CCall.enableVideo(true);   // 开启摄像头（默认）
LiveKitC2CCall.enableVideo(false);  // 关闭摄像头
```

### 3.6 enableAudio - 开关麦克风

```javascript
LiveKitC2CCall.enableAudio(true);   // 开启麦克风（默认）
LiveKitC2CCall.enableAudio(false);  // 关闭麦克风
```

### 3.7 switchCamera - 切换前后置摄像头

```javascript
LiveKitC2CCall.switchCamera("front");  // 前置
LiveKitC2CCall.switchCamera("back");   // 后置
```

### 3.8 onCallEvent - 全局事件监听（核心）

> **必须在发起/接听前注册！** 回调保持长驻（keepAlive），不会自动释放。

```javascript
LiveKitC2CCall.onCallEvent((res) => {
  const { event, msg } = res;

  switch (event) {
    case "onConnected":
      console.log("✅ 通话已接通");
      break;
    case "onDisconnected":
      console.log("🔌 通话断开:", msg);
      break;
    case "onHangup":
      console.log("📞 已挂断");
      break;
    case "onRemoteHangup":
      console.log("❗ 对方已挂断");
      break;
    case "onRemoteCameraOn":
      console.log("📹 对方开启了摄像头");
      break;
    case "onRemoteCameraOff":
      console.log("⬛ 对方关闭了摄像头");
      break;
    case "onRemoteAudioOn":
      console.log("🎤 对方开启了麦克风");
      break;
    case "onRemoteAudioOff":
      console.log("🔇 对方关闭了麦克风");
      break;
    case "onError":
      console.error("❌ 异常:", msg);
      break;
  }
});
```

---

## 四、回调数据结构

### 方法回调（startC2CVideoCall / answerC2CVideoCall）

```json
{
  "code": 0,
  "msg": "操作结果描述"
}
```

| code | 含义 |
|------|------|
| 0 | 成功 |
| -1 | 失败 |

### 事件回调（onCallEvent）

```json
{
  "event": "onConnected",
  "msg": "描述信息"
}
```

| event 值 | 触发时机 |
|-----------|----------|
| `onConnected` | 连接成功，通话接通 |
| `onDisconnected` | 连接断开（网络/异常） |
| `onHangup` | 本地主动挂断后 |
| `onRemoteHangup` | 对方挂断 |
| `onRemoteCameraOn` | 对方开启视频轨道 |
| `onRemoteCameraOff` | 对方关闭视频轨道 |
| `onRemoteAudioOn` | 对方开启音频轨道 |
| `onRemoteAudioOff` | 对方关闭音频轨道 |
| `onError` | 发生错误 |

---

## 五、完整调用示例

```javascript
<template>
  <view class="call-container">
    <!-- 远端画面 -->
    <view class="remote-view">
      <text>{{ remoteStatus }}</text>
    </view>
    <!-- 本地画面 -->
    <view class="local-view"></view>
    <!-- 操作按钮 -->
    <view class="controls">
      <button @click="toggleVideo">{{ isVideoOn ? '关闭摄像头' : '打开摄像头' }}</button>
      <button @click="toggleAudio" style="background:#f00;color:#fff;">{{ isAudioOn ? '静音' : '取消静音' }}</button>
      <button @click="hangup" style="background:#333;color:#fff;">挂断</button>
      <button @click="switchCam">切换摄像头</button>
    </view>
  </view>
</template>

<script>
const CallPlugin = uni.requireNativePlugin('LiveKit-C2CCall');

export default {
  data() {
    return {
      isVideoOn: true,
      isAudioOn: true,
      remoteStatus: '等待连接...'
    };
  },
  onLoad() {
    this.initEventListener();
    this.startCall();
  },
  methods: {
    initEventListener() {
      CallPlugin.onCallEvent((res) => {
        switch (res.event) {
          case 'onConnected':
            this.remoteStatus = '通话中';
            break;
          case 'onRemoteHangup':
          case 'onDisconnected':
            this.remoteStatus = '通话结束';
            break;
        }
        uni.showToast({ title: res.event, icon: 'none' });
      });
    },

    startCall() {
      CallPlugin.startC2CVideoCall({
        wsURL: 'wss://your-server',
        token: 'your-token',
        userInfo: { nickname: '我', identity: 'uid1', avatar: '' },
        callerUserInfo: { nickname: '对方', identity: 'uid2', avatar: '' },
        videoOptions: { position: 'front', encoding: 'QHD' }
      }, (res) => {
        if (res.code !== 0) {
          uni.showModal({ content: res.msg, showCancel: false });
        }
      });
    },

    toggleVideo() {
      this.isVideoOn = !this.isVideoOn;
      CallPlugin.enableVideo(this.isVideoOn);
    },

    toggleAudio() {
      this.isAudioOn = !this.isAudioOn;
      CallPlugin.enableAudio(this.isAudioOn);
    },

    switchCam() {
      CallPlugin.switchCamera(this.isFront ? 'back' : 'front');
      this.isFront = !this.isFront;
    },

    hangup() {
      CallPlugin.hangupCall();
      uni.navigateBack();
    }
  },
  onUnload() {
    CallPlugin.hangupCall();
  }
};
</script>

<style scoped>
.call-container { flex:1; background:#000; display:flex; flex-direction:column; }
.remote-view { flex:1; display:flex; align-items:center; justify-content:center; color:#fff; }
.local-view { position:absolute; top:20rpx; right:20rpx; width:240rpx; height:320rpx; border-radius:12rpx; overflow:hidden; }
.controls { padding:20rpx; display:flex; justify-content:space-around; }
.controls button { font-size:28rpx; padding:16rpx 32rpx; border-radius:50%; width:auto; }
</style>
```

---

## 六、无障碍支持说明

### Android 无障碍

- 所有按钮设置 `contentDescription` 属性
- 通话状态变化通过 `AccessibilityManager` 自动朗读：
  - 摄像头开关 → "摄像头已开启/已关闭"
  - 麦克风开关 → "麦克风已开启/已关闭"
  - 摄像头切换 → "已切换到前置/后置摄像头"
  - 通话状态 → "正在发起视频通话"、"正在接听来电"、"通话已挂断"

### iOS 无障碍

- 所有控件设置 `accessibilityLabel`
- `isAccessibilityElement = true` 确保可被读屏识别
- 通过 `UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification)` 触发系统朗读
- 支持 VoiceOver 实时朗读通话状态变化

---

## 七、权限配置

### Android（自动配置）

| 权限 | 用途 |
|------|------|
| `CAMERA` | 访问摄像头进行视频采集 |
| `RECORD_AUDIO` | 麦克风录音 |
| `INTERNET` | WebSocket 连接 LiveKit 服务 |
| `ACCESS_NETWORK_STATE` | 检测网络状态 |
| `MODIFY_AUDIO_SETTINGS` | 音频路由切换 |

### iOS（Info.plist）

| Key | Value |
|-----|-------|
| `NSCameraUsageDescription` | 需要摄像头权限进行视频通话 |
| `NSMicrophoneUsageDescription` | 需要麦克风权限进行语音通话 |

后台模式：`audio` + `voip`

---

## 八、构建说明

### Android 构建

```bash
cd android
./gradlew assembleRelease
# 输出：build/outputs/aar/livekit-c2c-release.aar → 重命名为 livekit-c2c.aar
```

### iOS 构建

```bash
cd ios
pod install
xcodebuild -workspace LiveKitC2CCall.xcworkspace \
           -scheme LiveKitC2CCall \
           -configuration Release \
           -sdk iphoneos \
           -derivedBuildPath build \
           build
```

---

## 九、注意事项

1. **Token 获取**: `token` 字段需要从你的服务端获取，服务端需集成 LiveKit Server SDK 生成 JWT Token
2. **wss 协议**: 必须使用 `wss://` 安全连接
3. **线程安全**: UI 相关方法标记为 uiThread=true，非 UI 方法为 false
4. **生命周期**: 在页面 `onUnload` 时务必调用 `hangupCall()` 释放资源
5. **事件监听顺序**: `onCallEvent` 应在 `startC2CVideoCall` / `answerC2CVideoCall` 之前注册
6. **云打包**: 本地标准运行不支持原生插件，必须使用自定义基座或云打包
