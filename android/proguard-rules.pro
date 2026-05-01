# LiveKit SDK
-dontwarn io.livekit.**
-keep class io.livekit.** { *; }

# WebRTC
-dontwarn org.webrtc.**
-keep class org.webrtc.** { *; }

# Keep our module
-keep class com.livekit.c2ccall.** { *; }

# Kotlin coroutines
-dontwarn kotlinx.coroutines.**
