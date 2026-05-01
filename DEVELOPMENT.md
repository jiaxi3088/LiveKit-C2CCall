# LiveKit-C2CCall 开发构建指南

> 无需本地安装 Android Studio / Xcode，通过 GitHub Actions 云端编译生成原生插件二进制文件。

---

## 一、整体流程

```
修改源码 → git push → GitHub Actions 自动编译 → 下载 .aar + .framework → 放入项目 → HBuilderX 云打包
```

**核心文件**：`.github/workflows/build.yml`

推送代码到 `main` 或 `master` 分支时自动触发编译，也可在 GitHub Actions 页面手动触发。

---

## 二、项目目录结构

```
LiveKit-C2CCall/
├── .github/workflows/
│   └── build.yml                    ← CI 编译配置（不要手动修改，除非知道自己在做什么）
├── ci-stubs/                        ← 编译用 stub（CI 专用，不会打包进最终插件）
│   ├── android/                     ← uni-app SDK 接口的空壳 Java 类（仅编译时使用）
│   └── ios/                         ← uni-app SDK 接口的空壳头文件（仅编译时使用）
│
├── android/                         ← Android 源码
│   ├── build.gradle
│   ├── settings.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/livekit/c2ccall/
│           └── LiveKitC2CCallModule.java
│
├── ios/                             ← iOS 源码
│   ├── Info.plist
│   ├── Podfile
│   ├── LiveKitC2CCall.podspec
│   └── Classes/
│       ├── LiveKitC2CCallModule.h
│       ├── LiveKitC2CCallModule.m
│       └── LiveKitC2CCallModule-BridgingHeader.h
│
├── package.json                     ← DCloud 标准插件描述文件（已配置好 _dp_nativeplugin）
├── doc.md                           ← API 使用文档
└── DEVELOPMENT.md                   ← 本文档
```

---

## 三、GitHub Actions 编译流程

CI 工作流包含 3 个 Job：

| Job | 运行环境 | 做了什么 | 产出 |
|-----|----------|---------|------|
| `build-android` | ubuntu-latest + JDK 17 | 编译 stub AAR → Gradle 构建插件 AAR | `android-aar` |
| `build-ios` | macos-latest + Xcode | 编译 stub framework → xcodegen + xcodebuild | `ios-framework` |
| `package` | ubuntu-latest | 组装为符合 DCloud 规范的 ZIP 包 | `livekit-c2c-plugin.zip` |

### 编译原理（重要）

插件源码依赖 uni-app SDK（`io.dcloud.feature.uniapp.*`），但 uni-app SDK **不公开下载**。CI 通过以下方式解决：

1. **Android**：在 `ci-stubs/android/` 创建了空壳 Java 类（`UniModule`、`UniJSMethod`、`JSCallback` 等），编译成 stub AAR 放入 `libs/`，让 `build.gradle` 的 `compileOnly` 引用通过编译。**运行时由 HBuilderX 云打包提供真正的 uni-app SDK**。

2. **iOS**：在 `ci-stubs/ios/` 创建了空壳 `DCUniModule.h`，编译成 stub framework 安装到 `/Library/Frameworks/`，让 `#import <DCUniModule/DCUniModule.h>` 通过编译。**运行时同样由 HBuilderX 云打包提供**。

> 简单说：stub 只是为了让编译通过，不影响最终功能。

---

## 四、首次使用

### 4.1 推送代码触发编译

```bash
cd LiveKit-C2CCall

# 如果还没关联远程仓库
git remote add origin https://github.com/你的用户名/LiveKit-C2CCall.git
git branch -M main
git push -u origin main
```

### 4.2 查看编译结果

1. 打开 GitHub 仓库 → **Actions** 标签
2. 查看 **Build LiveKit-C2CCall Native Plugin** workflow
3. 等待编译完成（通常 5-10 分钟）
4. 滚动到底部 **Artifacts** 区域下载

| Artifact | 内容 | 用途 |
|----------|------|------|
| `android-aar` | 编译后的 Android AAR 文件 | 单独下载 AAR |
| `ios-framework` | 编译后的 iOS framework | 单独下载 framework |
| `livekit-c2c-plugin` | **完整的插件 ZIP 包**（推荐） | 直接放入项目使用 |

### 4.3 导入 UniApp 项目

下载 `livekit-c2c-plugin.zip`，解压后放到项目的 `nativeplugins/` 目录：

```
你的UniApp项目/
├── pages/
├── manifest.json
└── nativeplugins/
    └── LiveKit-C2CCall/              ← 解压到这里
        ├── package.json
        ├── android/
        │   └── livekit-c2c.aar
        └── ios/
            └── LiveKitC2CCall.framework
```

然后在 `manifest.json` → **App原生插件配置** → 勾选 `LiveKit-C2CCall` → **云打包**。

---

## 五、日常更新流程

### 5.1 修改插件代码后重新编译

```bash
# 1. 修改 Android 或 iOS 源码
# 2. 如果改了版本号，同步更新 package.json 的 version 字段

# 3. 提交并推送
git add .
git commit -m "fix: 修复xxx问题"
git push origin main

# 4. GitHub Actions 自动编译，等待完成后下载新的 livekit-c2c-plugin.zip
```

### 5.2 手动触发编译（不推代码也能重新编译）

1. 打开 GitHub 仓库 → **Actions**
2. 左侧选择 **Build LiveKit-C2CCall Native Plugin**
3. 右侧点击 **Run workflow** → 选择分支 → **Run workflow**

### 5.3 更新版本号

需要同时修改 2 个文件：

**`package.json`**：
```json
{
    "version": "1.0.1"  // 改这里
}
```

**`android/build.gradle`**：
```groovy
versionName "1.0.1"  // 改这里
```

**`ios/Info.plist`**：
```xml
<key>CFBundleShortVersionString</key>
<string>1.0.1</string>  <!-- 改这里 -->
```

然后 commit + push 即可，CI 会自动生成新版本的插件包。

---

## 六、注意事项

### 6.1 绝对不要做的事

- **不要修改 `ci-stubs/` 目录的内容**，除非你清楚知道自己在做什么。这些 stub 类必须与 `build.gradle` 和源码中的 import 保持一致。
- **不要在 `android/src/` 中同时放 `.java` 和同名 `.kt` 文件**，会导致编译冲突。当前项目保留 `.java`，`.kt` 文件由 CI 自动删除。
- **不要手动创建 `.aar` 或 `.framework`**，这些是编译产物，必须通过 CI 编译生成。
- **不要在 `package.json` 中加注释**，JSON 不支持注释，会导致 DCloud 云端打包失败。

### 6.2 常见编译失败排查

| 错误 | 原因 | 解决 |
|------|------|------|
| `Create uni-app SDK stub AAR` 红色 | Android SDK 缺少 platform 34 | 确保 `sdkmanager --install "platforms;android-34"` 正确执行 |
| Gradle 构建失败 | LiveKit SDK 版本或依赖问题 | 检查 `build.gradle` 中 `dependencies` 版本 |
| iOS xcodegen 失败 | project.yml 格式或路径问题 | 检查 `ios/Classes/` 目录是否完整 |
| iOS xcodebuild 失败 | ObjC 代码语法或头文件缺失 | 检查 `#import` 语句是否正确 |

### 6.3 GitHub Actions 免费额度

| 资源 | 免费额度 | 本项目单次消耗 |
|------|----------|--------------|
| ubuntu-latest | 2000 分钟/月 | 约 2-3 分钟 |
| macos-latest | 2000 分钟/月 | 约 5-8 分钟 |

每次 push 到 main 分支会自动触发编译，建议开发阶段在分支上工作，确定后再合并到 main。

### 6.4 分支开发建议

```bash
# 创建开发分支（不会触发 CI）
git checkout -b dev/fix-something

# 开发完成后合并到 main 触发编译
git checkout main
git merge dev/fix-something
git push origin main
```

> 或者修改 `build.yml` 的 `on.push.branches` 添加你的开发分支名。

---

## 七、快速命令速查

```bash
# 推送代码（自动触发编译）
git add . && git commit -m "update" && git push origin main

# 验证 package.json 格式
python -m json.tool package.json > /dev/null && echo "JSON OK" || echo "JSON ERROR"

# 查看编译状态
# 浏览器打开：https://github.com/你的用户名/LiveKit-C2CCall/actions

# 手动触发编译
# Actions → Build LiveKit-C2CCall Native Plugin → Run workflow
```

---

*最后更新时间：2026-05-01*
