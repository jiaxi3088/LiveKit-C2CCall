# LiveKit-C2CCall uni-app 原生插件使用文档

> 基于 LiveKit SDK 实现 1v1 视频通话，支持 Android + iOS 双端，含无障碍支持。

## 〇、更新代码后推送到 GitHub

修改插件源码后，执行以下命令推送到 GitHub，会自动触发云端编译：

```bash
cd d:\phpstudy_pro\WWW\gitHub推送编译\LiveKit-C2CCall
git add .
git commit -m "fix: 修改说明"
git push origin main
```

推送后前往 [GitHub Actions](https://github.com/jiaxi3088/LiveKit-C2CCall/actions) 页面查看编译状态。

**编译完成后，产物会自动发布到 [Releases](https://github.com/jiaxi3088/LiveKit-C2CCall/releases) 页面，直接下载 `LiveKit-C2CCall-v1.0.0.zip` 即可。**

> 同时 Actions 的 Artifacts 中也会保留 3 个临时包（`android-aar`、`ios-framework`、`livekit-c2c-plugin`），有效期 1 天。

## 一、插件信息

| 项目 | 值 |
|------|-----|
| 插件ID | `LiveKit-C2CCall` |
| 版本 | 1.0.0 |
| 平台 | Android (minSdk 24) + iOS 14.0+ |
| 依赖 | LiveKit Android SDK 2.0.0 / LiveKitClient Swift ~1.8.0 |
| 构建 | GitHub Actions 自动编译（无需本地 Android Studio / Xcode） |

---

## 二、安装步骤

### 2.1 获取插件包 

插件通过 GitHub Actions 自动编译生成。方式有两种：

**方式 A（推荐）**：从 Releases 页面下载

1. 打开仓库 **Releases** 页面：https://github.com/jiaxi3088/LiveKit-C2CCall/releases
2. 下载最新版本的 **`LiveKit-C2CCall-v1.0.0.zip`**（含 Android + iOS 双端产物）

**方式 B**：从 Actions Artifacts 下载

1. 打开 [GitHub Actions](https://github.com/jiaxi3088/LiveKit-C2CCall/actions) 页面
2. 找到最新的成功编译记录（绿色 ✓）
3. 在 **Artifacts** 中下载 `livekit-c2c-plugin`（有效期 1 天）

**方式 C**：自行 clone 源码编译

```bash
git clone https://github.com/你的用户名/LiveKit-C2CCall.git
# 推送到你自己的 GitHub，Actions 会自动编译
```

详细编译流程参见 `DEVELOPMENT.md`。

### 2.2 放入 UniApp 项目

解压 `livekit-c2c-plugin.zip`，将整个 `LiveKit-C2CCall` 文件夹复制到项目的 `nativeplugins/` 下：

```
你的UniApp项目/
├── pages/
├── manifest.json
└── nativeplugins/
    └── LiveKit-C2CCall/
        ├── package.json                  ← DCloud 插件描述文件
        ├── android/
        │   └── livekit-c2c.aar           ← Android 编译产物
        └── ios/
            └── LiveKitC2CCall.framework   ← iOS 编译产物
```

> **注意**：`android` 和 `ios` 目录名必须全小写，否则云端打包会失败。

### 2.3 manifest.json 配置

打开 `manifest.json` → **App原生插件配置** → 勾选 `LiveKit-C2CCall`。

Android 权限会在云端打包时自动添加，iOS 权限已通过 `package.json` 的 `plists` 字段配置。

### 2.4 云打包

使用 HBuilderX **云打包**（自定义基座或正式包），本地标准运行不支持原生插件。

```
HBuilderX 菜单：发行 → 原生 App-云打包 → 选择平台 → 使用公共测试证书 → 打包
```

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
LiveKitC2CCall.hangupCall();
```

### 3.5 enableVideo - 开关摄像头

```javascript
LiveKitC2CCall.enableVideo(true);   // 开启（默认）
LiveKitC2CCall.enableVideo(false);  // 关闭
```

### 3.6 enableAudio - 开关麦克风

```javascript
LiveKitC2CCall.enableAudio(true);   // 开启（默认）
LiveKitC2CCall.enableAudio(false);  // 关闭
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
      console.log("通话已接通");
      break;
    case "onDisconnected":
      console.log("通话断开:", msg);
      break;
    case "onHangup":
      console.log("已挂断");
      break;
    case "onRemoteHangup":
      console.log("对方已挂断");
      break;
    case "onRemoteCameraOn":
      console.log("对方开启了摄像头");
      break;
    case "onRemoteCameraOff":
      console.log("对方关闭了摄像头");
      break;
    case "onRemoteAudioOn":
      console.log("对方开启了麦克风");
      break;
    case "onRemoteAudioOff":
      console.log("对方关闭了麦克风");
      break;
    case "onError":
      console.error("异常:", msg);
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
    <view class="remote-view">
      <text>{{ remoteStatus }}</text>
    </view>
    <view class="local-view"></view>
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
      isFront: true,
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
      this.isFront = !this.isFront;
      CallPlugin.switchCamera(this.isFront ? 'front' : 'back');
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

- 通话状态变化通过 `AccessibilityManager` 自动朗读：
  - 摄像头开关 → "摄像头已开启/已关闭"
  - 麦克风开关 → "麦克风已开启/已关闭"
  - 摄像头切换 → "已切换到前置/后置摄像头"
  - 通话状态 → "正在发起视频通话"、"正在接听来电"、"通话已挂断"

### iOS 无障碍

- 通过 `UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification)` 触发系统朗读
- 支持 VoiceOver 实时朗读通话状态变化

---

## 七、权限配置

### Android（自动配置）

| 权限 | 用途 |
|------|------|
| `CAMERA` | 视频采集 |
| `RECORD_AUDIO` | 麦克风录音 |
| `INTERNET` | WebSocket 连接 LiveKit 服务 |
| `ACCESS_NETWORK_STATE` | 检测网络状态 |
| `MODIFY_AUDIO_SETTINGS` | 音频路由切换 |

### iOS（自动配置）

| Key | Value |
|-----|-------|
| `NSCameraUsageDescription` | 需要摄像头权限进行视频通话 |
| `NSMicrophoneUsageDescription` | 需要麦克风权限进行语音通话 |
| `UIBackgroundModes` | audio, voip |

权限通过 `package.json` 的 `plists` 字段自动注入，无需手动配置。

---

## 八、注意事项

1. **Token 获取**：`token` 字段需从服务端获取，服务端需集成 LiveKit Server SDK 生成 JWT Token
2. **wss 协议**：必须使用 `wss://` 安全连接
3. **生命周期**：在页面 `onUnload` 时务必调用 `hangupCall()` 释放资源
4. **事件监听顺序**：`onCallEvent` 应在 `startC2CVideoCall` / `answerC2CVideoCall` 之前注册
5. **云打包**：本地标准运行不支持原生插件，必须使用自定义基座或云打包
6. **目录命名**：插件包中 `android` 和 `ios` 目录必须全小写
7. **更新插件**：修改源码后 push 到 GitHub，Actions 会自动编译新版本，下载后替换项目中的插件文件夹即可

---

*最后更新时间：2026-05-01（已验证 GitHub Actions 编译产物）*
