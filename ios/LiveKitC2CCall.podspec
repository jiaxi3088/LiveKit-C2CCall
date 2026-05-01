Pod::Spec.new do |s|
  s.name         = "LiveKitC2CCall"
  s.version      = "1.0.0"
  s.summary      = "LiveKit 1v1 video call plugin for uni-app, with accessibility support"
  s.description  = <<-DESC
    基于 LiveKit SDK 的 uni-app 原生插件，实现一对一视频通话。
    支持功能：
    - 发起/接听/挂断视频通话
    - 开关摄像头和麦克风
    - 切换前后置摄像头
    - 全事件回调（接通、挂断、对方挂断、远端音视频变化）
    - 无障碍读屏支持（VoiceOver）
    - 自定义铃声
    DESC

  s.homepage     = "https://github.com/your-org/LiveKit-C2CCall"
  s.license      = { :type => 'MIT', :file => 'LICENSE' }
  s.authors      = { "YourName" => "your@email.com" }
  s.source       = { :git => "https://github.com/your-org/LiveKit-C2CCall.git", :tag => "#{s.version}" }

  # 平台要求
  s.platform     = :ios, '14.0'
  s.swift_versions = ['5.0']

  # 源文件
  s.source_files = 'Classes/*.{h,m}'

  # 资源文件
  s.resource_bundles = {
    'LiveKitC2CCall' => ['BundleResources/**/*']
  }

  # 系统框架依赖
  s.frameworks = 'AVFoundation', 'CoreMedia', 'VideoToolbox', 'AudioToolbox', 'CoreAudio', 'CoreVideo'
  s.libraries = 'c++', 'resolv'

  # CPU 架构
  s.vendored_frameworks = nil   # 不预编译，由宿主工程编译
  s.valid_archs = ['arm64']

  # LiveKit Client SDK 依赖
  s.dependency 'LiveKitClient', '~> 1.8.0'
  
  # 项目配置
  s.pod_target_xcconfig = {
    'IPHONEOS_DEPLOYMENT_TARGET' => '14.0',
    'VALID_ARCHS' => 'arm64',
    'GCC_PREPROCESSOR_DEFINITIONS' => '$(inherited) COCOAPODS=1',
    'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'arm64'
  }

  # 静态库模式
  s.static_framework = true
end
