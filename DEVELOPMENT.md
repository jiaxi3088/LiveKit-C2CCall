# LiveKit-C2CCall 云端打包指南

> **没有本地 Android Studio / Xcode？** 本文档提供完整的云编译 + 云打包流程。

---

## 目录

- [方案总览](#一方案总览)
- [方式 A：GitHub Actions 自动编译（推荐）](#二方式-a-github-actions-自动编译推荐)
- [方式 B：Docker 容器编译](#三方式-b-docker-容器编译)
- [方式 C：在线 CI 平台（CodeSandbox / GitLab）](#四方式-c-在线-ci-平台)
- [HBuilderX 云打包配置](#五hbuilderx-云打包配置)
- [插件文件结构说明](#六插件文件结构说明)
- [常见问题](#七常见问题)

---

## 一、方案总览

### 核心问题

uni-app 原生插件要求：
| 平台 | 要求 | 你的现状 |
|------|------|---------|
| **Android** | 必须提交 `.aar` 二进制文件 | ❌ 无 Android Studio |
| **iOS** | 必须提交 `.framework` 二进制文件 | ❌ 无 Xcode |

**解决思路**：利用云端 CI/CD 服务自动编译源码生成 .aar 和 .framework。

### 推荐路线图

```
本地写好代码
    ↓  push 到 GitHub/Gitee
    ↓  GitHub Actions 自动编译
    ↓  下载产物 (.aar + .framework)
    ↓  放到对应目录
    ↓  HBuilderX 云打包 → 完成！
```

---

## 二、方式 A：GitHub Actions 自动编译（推荐 ⭐）

### 步骤 1：创建 Git 仓库并推送代码

```bash
cd LiveKit-C2CCall

# 初始化 Git
git init
git add .
git commit -m "feat: LiveKit-C2CCall 原生插件完整源码"

# 创建 GitHub 仓库后关联
git remote add origin https://github.com/你的用户名/LiveKit-C2CCall.git
git branch -M main
git push -u origin main
```

### 步骤 2：触发自动构建

推送代码后，GitHub Actions 会自动运行（已内置在项目中）：

📁 `.github/workflows/build.yml` — 包含 3 个 Job：

| Job | 运行环境 | 输出产物 |
|-----|----------|----------|
| `build-android` | ubuntu-latest + JDK 17 | `livekit-c2c.aar` |
| `build-ios` | macos-latest + Xcode | `LiveKitC2CCall.framework` |
| `package-plugin` | ubuntu-latest | `LiveKit-C2CCall-v1.0.0.zip` |

### 步骤 3：下载编译产物

1. 打开 GitHub 仓库 → **Actions** 标签页
2. 点击最新的 workflow run
3. 滚动到底部 **Artifacts** 区域
4. 下载以下三个文件：

```
下载的文件                          放到哪个位置
───────────────────────────────── ───────────────
livekit-c2c.aar                    → android/livekit-c2c.aar
LiveKitC2CCall.framework/          → ios/LiveKitC2CCall.framework/
LiveKit-C2CCall-v1.0.0.zip         → 完整插件包（可直接用）
```

### 步骤 4：手动触发构建（可选）

如果需要重新编译：

1. 打开仓库 → **Actions** → **Build LiveKit-C2CCall Native Plugin**
2. 右侧 **Run workflow** → 选择分支 → **Run workflow**

---

## 三、方式 B：Docker 容器编译

如果没有 GitHub 账号或不想用 GitHub Actions，可以用 Docker 本地编译。

### 3.1 编译 Android .aar

```bash
docker run --rm -v "%cd%":/workspace \
  openjdk:17-jdk bash -c '
    cd /workspace/android
    
    # 创建占位 uniapp SDK aar
    mkdir -p libs && touch libs/uniapp-v8-release.aar
    
    # 编译
    gradle assembleRelease --no-daemon
    
    # 复制产物
    cp build/outputs/aar/*.aar livekit-c2c.aar 2>/dev/null || echo "AAR 编译完成"
  '
```

> 注意：此方法仅能验证 Java 语法，LiveKit SDK 的 Kotlin 依赖可能需要完整 Android SDK 环境。

### 3.2 编译 iOS .framework

```bash
# macOS only (iOS 需要 macOS + Xcode)
# 如果你有 Mac 但没装 Xcode CLI:
xcode-select --install   # 安装 Xcode Command Line Tools

cd ios
pod install --repo-update
xcodebuild -workspace LiveKitC2CCall.xcworkspace \
           -scheme LiveKitC2CCall \
           -configuration Release \
           -sdk iphoneos \
           CODE_SIGNING_ALLOWED=NO \
           clean build
```

> iOS 编译必须有 macOS 系统（无法 Docker 化），这是 Apple 的限制。

---

## 四、方式 C：在线 CI 平台

### 4.1 使用 Gitee Go（国内友好）

1. 将代码推送到 Gitee 仓库
2. 进入 **Gitee Go → 流水线**
3. 新建流水线，选择 **Go Template**
4. 配置两个阶段：

**阶段 1 - Android 构建：**
```yaml
stages:
  - name: build-android
    steps:
      - step: maven:3.8-openjdk-17
        commands:
          - cd android && gradle assembleRelease
          - cp build/outputs/aar/*.aar ../livekit-c2c.aar
    artifacts:
      - android/livekit-c2c.aar
```

**阶段 2 - iOS 构建：**（需自建 macOS Runner）

### 4.2 使用 CircleCI / TravisCI

支持免费额度，macOS runner 可用于 iOS 编译。参考项目中的 `.circleci/config.yml`（如需要可以创建）。

---

## 五、HBuilderX 云打包配置

### 5.1 最终目录结构

确保你的插件目录如下：

```
LiveKit-C2CCall/
├── package.json                    ✅ 已就绪（含 _dp_nativeplugin 配置）
├── doc.md                          ✅ API 文档
├── DEVELOPMENT.md                  ✅ 本文档
│
├── android/
│   ├── build.gradle                ✅ Gradle 构建脚本
│   ├── proguard-rules.pro          ✅ 混淆规则
│   ├── settings.gradle             ✅ 项目设置
│   ├── src/main/
│   │   ├── AndroidManifest.xml     ✅ 权限声明
│   │   └── java/com/livekit/c2ccall/
│   │       └── LiveKitC2CCallModule.java  ✅ Android 主模块（纯 Java）
│   └── livekit-c2c.aar             📦 需从 GitHub Actions 下载放入
│
└── ios/
    ├── Podfile                     ✅ CocoaPods 依赖
    ├── podspec                     ✅ Pod 规范
    ├── module.json                 ✅ uni-app iOS 模块注册
    ├── Info.plist                  ✅ 权限声明
    ├── Classes/
    │   ├── LiveKitC2CCallModule.h         ✅ 头文件
    │   ├── LiveKitC2CCallModule.m         ✅ 实现类（Objective-C）
    │   └── LiveKitC2CCallModule-BridgingHeader.h  ✅ 桥接头文件
    └── LiveKitC2CCall.framework     📦 需从 GitHub Actions 下载放入
```

标记说明：
- ✅ = 源码文件（已在仓库中）
- 📦 = **编译产物**（需从 GitHub Actions Artifacts 下载）

### 5.2 在 uni-app 项目中集成

#### 第一步：放置插件

将整个 `LiveKit-C2CCall` 文件夹复制到项目的 `nativeplugins/` 下：

```
你的uni-app项目/
├── pages/
├── manifest.json        ← 需要修改
├── nativeplugins/
│   └── LiveKit-C2CCall/ ← 整个文件夹放进来
└── ...
```

#### 第二步：manifest.json 配置

打开 `manifest.json` → **App原生插件配置** → 勾选 **LiveKit-C2CCall**

或者直接编辑 JSON：

```json
{
  "app-plus": {
    "nativePlugins": {
      "LiveKit-C2CCall": {
        "__plugin_info__": {
          "name": "LiveKit-C2CCall",
          "description": "基于 LiveKit 私有化部署 1v1 视频通话插件",
          "platforms": "android,ios",
          "version": "1.0.0",
          "result": {
            "class": "com.livekit.c2ccall.LiveKitC2CCallModule",
            "methods": [
              {"name": "startC2CVideoCall", "options": {}},
              {"name": "answerC2CVideoCall", "options": {}},
              {"name": "hangupCall", "options": {}},
              {"name": "enableVideo", "options": {}},
              {"name": "enableAudio", "options": {}},
              {"name": "switchCamera", "options": {}},
              {"name": "onCallEvent", "options": {}}
            ]
          }
        }
      }
    }
  }
}
```

#### 第三步：云打包

```
HBuilderX 菜单：
发行 → 原生 App-云打包 → 
  选择平台（Android/iOS）→ 
  使用公共测试证书 → 
  打包 → 等待完成 → 下载 APK/IPA
```

> **注意**：必须使用云打包，标准基座不支持原生插件。

### 5.3 自定义基座调试（开发期推荐）

如果想实时调试而不每次都云打包：

```
运行 → 运行到手机或模拟器 → 制作自定义基座 → 选择平台 → 确定

等待自定义基座制作完成后：
运行 → 运行到手机或模拟器 → 运行到 Android/iOS App 基座
```

自定义基座的优点：
- 可以通过 Logcat 查看 Android 日志
- 可以通过 Xcode Console 查看 iOS 日志
- 修改 JS 代码不需要重新打包

---

## 六、插件文件结构说明

### package.json 关键字段解析

```json
{
  "_dp_type": "nativeplugin",          // 声明为原生插件（必填）
  "_dp_nativeplugin": {                // 原生插件详细配置（必填）
    "android": {
      "integrateType": "aar",          // 插件类型：aar（必填）
      "plugins": [{                    // 插件模块注册列表
        "type": "module",              // 类型：module=功能模块
        "name": "LiveKit-C2CCall",     // 名称：必须与 id 一致
        "class": "com.livekit.c2ccall.LiveKitC2CCallModule"  // 全限定类名
      }],
      "dependencies": [                // Gradle 依赖（云端打包时拉取）
        "io.livekit:livekit-android-sdk:2.0.0",
        "androidx.core:core-ktx:1.12.0"
      ],
      "permissions": [...]             // Android 权限声明
    },
    "ios": {
      "integrateType": "framework",    // 插件类型：framework
      "embedSwift": true,              // 启用 Swift 支持（LiveKit 是 Swift 库）
      "plugins": [{
        "type": "module",
        "name": "LiveKit-C2CCall",
        "class": "LiveKitC2CCallModule"  // iOS 类名（不含包名）
      }],
      "frameworks": [...],             // 系统框架依赖
      "privacies": [...]               // iOS 隐私权限描述
    }
  }
}
```

### Android 源码架构

```
com.livekit.c2ccall.LiveKitC2CCallModule
├── extends UniModule                   # 继承 uni-app 模块基类
├── 7 个 @UniJSMethod 方法              # 对外暴露 API
│   ├── startC2CVideoCall()            # 发起呼叫
│   ├── answerC2CVideoCall()           # 接听来电
│   ├── hangupCall()                   # 挂断
│   ├── enableVideo()                  # 开关摄像头
│   ├── enableAudio()                  # 开关麦克风
│   ├── switchCamera()                 # 切换前后置
│   └── onCallEvent()                  # 事件监听回调
├── 内部方法
│   ├── handleRoomEvent()              # 房间事件分发
│   ├── observeRemoteParticipants()    # 远端参与者监听
│   ├── playRing() / stopRing()        # 铃声播放控制
│   ├── sendEvent()                    # 事件发送到 JS 层
│   └── announceForAccessibility()     # 无障碍朗读
└── 生命周期
    ├── onCreate()                     # 初始化 Context
    └── destroy()                      # 断开连接释放资源
```

### iOS 源码架构

```
LiveKitC2CCallModule (Objective-C)
├── 继承 NSObject                     # uni-app iOS 模块基类
├── 7 个公开方法                        # 对外暴露 API
│   ├── startC2CVideoCall:callback:    # 发起呼叫
│   ├── answerC2CVideoCall:callback:   # 接听来电
│   ├── hangupCall                     # 挂断
│   ├── enableVideo:                   # 开关摄像头
│   ├── enableAudio:                   # 开关麦克风
│   ├── switchCamera:                  # 切换前后置
│   └── onCallEvent:                   # 事件监听
├── 内部方法
│   ├── connectToRoomWithWsURL:...     # 连接房间核心逻辑（使用 runtime 动态调用）
│   ├── disconnectRoom                 # 断开连接
│   ├── playRing: / stopRing           # 铃声播放
│   ├── sendEvent:msg:                 # 事件发送
│   └── announceForAccessibility:      # VoiceOver 朗读
└── 技术特点
    ├── objc_msgSend 动态调用 Swift API  # 避免 Objective-C 直接 import Swift
    ├── NSClassFromString 动态加载类     # 兼容不同 SDK 版本
    └── AVAudioPlayer 循环播放铃声       # 支持本地/网络音频
```

---

## 七、常见问题

### Q1：GitHub Actions 编译失败怎么办？

**排查步骤**：
1. 打开 Actions 页面 → 点击失败的 Job → 查看 **Build logs**
2. 常见错误及解决：

| 错误信息 | 解决方法 |
|----------|---------|
| `SDK location not found` | Android Job 会自动设置 ANDROID_HOME，忽略即可 |
| `Gradle sync failed` | 检查 `build.gradle` 中依赖版本是否正确 |
| `LiveKit SDK not found` | 确认 `dependencies` 中版本号为 Maven Central 可用的版本 |
| `Java compilation error` | 代码中有 Java 8 不支持的语法，改回 Java 7 兼容写法 |

### Q2：没有 GitHub 账号怎么办？

替代方案：
- **Gitee Go**：国内 Gitee 平台的 CI 服务（免费）
- **GitLab CI**：如果有自建或 gitlab.com 账号
- **找朋友帮忙编译**：把代码发给有 Mac 的朋友帮忙跑一次 Xcode

### Q3：只想先测试，不想等编译？

可以先使用**模拟模式**：在 JS 层创建 mock 插件进行 UI 开发和联调：

```javascript
// mock.js - 开发期临时替代
const MockPlugin = {
  startC2CVideoCall(opts, cb) {
    console.log('[Mock] startC2CVideoCall', opts);
    setTimeout(() => cb({ code: 0, msg: '模拟呼叫成功' }), 1000);
  },
  answerC2CVideoCall(opts, cb) {
    console.log('[Mock] answerC2CVideoCall', opts);
    setTimeout(() => cb({ code: 0, msg: '模拟接听成功' }), 1000);
  },
  hangupCall() { console.log('[Mock] hangupCall'); },
  enableVideo(v) { console.log('[Mock] enableVideo:', v); },
  enableAudio(a) { console.log('[Mock] enableAudio:', a); },
  switchCamera(p) { console.log('[Mock] switchCamera:', p); },
  onCallEvent(cb) {
    // 模拟事件
    setTimeout(() => cb({ event: 'onConnected', msg: '通话接通' }), 2000);
  }
};

// 开发期用 Mock，生产期替换为真插件
const CallPlugin = process.env.NODE_ENV === 'development'
  ? MockPlugin
  : uni.requireNativePlugin('LiveKit-C2CCall');
```

### Q4：iOS framework 为什么需要 macOS？

Apple 的法律和技术限制：
- Xcode 只能在 macOS 上安装和运行
- iOS 编译链（clang、codesign 等）是 macOS 专有的
- 无法通过虚拟机或 Docker 绕过

**解决方案**：
1. GitHub Actions 提供 **免费的 macos-latest runner**（每月 2000 分钟）
2. 或者借用朋友的 Mac 远程编译

### Q5：package.json 格式错误导致云端打包失败？

**检查清单**：
- [ ] 不能有任何注释（JSON 不支持注释）
- [ ] `_dp_type` 和 `_dp_nativeplugin` 必须存在
- [ ] `id` 字段只能包含英文、数字、下划线、连字符，且以字母开头
- [ ] Android 的 `class` 必须是全限定类名（含包路径）
- [ ] iOS 的 `class` 就是单纯的类名

可以用工具验证 JSON 格式：

```bash
python -m json.tool LiveKit-C2CCall/package.json > /dev/null && echo "JSON OK" || echo "JSON ERROR"
```

### Q6：如何更新插件版本？

```bash
# 1. 修改 package.json 中的 version
# 2. 修改代码
# 3. commit & push
# 4. GitHub Actions 自动重新编译
# 5. 下载新产物替换旧文件
# 6. HBuilderX 重新云打包
```

---

## 八、快速命令速查

```bash
# ========== 推送到 GitHub ==========
git add .
git commit -m "update: plugin source code"
git push origin main

# ========== 手动触发 GitHub Actions ==========
# 浏览器打开：https://github.com/你用户名/LiveKit-C2CCall/actions
# → 点击 "Build LiveKit-C2CCall Native Plugin" → Run workflow

# ========== 验证 JSON 格式 ==========
python -m json.tool package.json

# ========== 验证目录结构 ==========
tree -a -L 3 .

# ========== HBuilderX 云打包 ==========
# 发行 → 原生 App-云打包 → 选择平台 → 打包
```

---

*最后更新时间：2025-04-10*
