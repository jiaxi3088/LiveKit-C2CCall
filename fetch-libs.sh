#!/bin/bash
# fetch-libs.sh — 从 Maven Central 下载 LiveKit SDK + WebRTC 的 .so 文件到 jniLibs
# 用法: ./fetch-libs.sh [版本号]
# 默认版本: 2.25.1

set -e

LIVEKIT_VERSION="${1:-2.25.1}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
JNI_DIR="$SCRIPT_DIR/android/src/main/jniLibs"
WORK_DIR=$(mktemp -d)

echo "============================================="
echo "  Fetching LiveKit Android SDK native libs"
echo "  Version: $LIVEKIT_VERSION"
echo "  Target:  $JNI_DIR"
echo "============================================="

cleanup() {
    rm -rf "$WORK_DIR"
}
trap cleanup EXIT

# ---- Step 1: 下载 livekit-android AAR ----
echo ""
echo "[Step 1] Downloading livekit-android:$LIVEKIT_VERSION AAR..."

LIVEKIT_URL="https://repo1.maven.org/maven2/io/livekit/livekit-android/$LIVEKIT_VERSION/livekit-android-$LIVEKIT_VERSION.aar"
LIVEKIT_AAR="$WORK_DIR/livekit-android.aar"

if command -v curl &>/dev/null; then
    curl -sL -o "$LIVEKIT_AAR" "$LIVEKIT_URL" || { echo "ERROR: Failed to download livekit-android AAR"; exit 1; }
elif command -v wget &>/dev/null; then
    wget -q -O "$LIVEKIT_AAR" "$LIVEKIT_URL" || { echo "ERROR: Failed to download livekit-android AAR"; exit 1; }
else
    echo "ERROR: Neither curl nor wget available"
    exit 1
fi

echo "  Downloaded: $(du -h "$LIVEKIT_AAR" | cut -f1)"

# ---- Step 2: 解压并提取 .so ----
echo ""
echo "[Step 2] Extracting .so files..."

EXTRACT_DIR="$WORK_DIR/extracted"
unzip -qo "$LIVEKIT_AAR" -d "$EXTRACT_DIR"

TARGET_ABIS="arm64-v8a armeabi-v7a"
SO_COUNT=0

for abi in $TARGET_ABIS; do
    DEST_DIR="$JNI_DIR/$abi"
    mkdir -p "$DEST_DIR"

    if [ -d "$EXTRACT_DIR/jni/$abi" ]; then
        cp "$EXTRACT_DIR/jni/$abi/"*.so "$DEST_DIR/" 2>/dev/null || true
        count=$(ls "$DEST_DIR/"*.so 2>/dev/null | wc -l)
        SO_COUNT=$((SO_COUNT + count))
        if [ "$count" -gt 0 ]; then
            echo "  ✓ $abi: $count .so files copied"
            ls -lh "$DEST_DIR/"*.so 2>/dev/null | awk '{print "      " $NF " (" $5 ")"}'
        else
            echo "  ⚠ $abi: no .so files in livekit-android AAR jni/"
        fi
    else
        echo "  ⚠ $abi: no jni directory in livekit-android AAR"
    fi
done

# ---- Step 3: 尝试从 WebRTC AAR 补充（livekit-android 可能不含直接 .so）----
# WebRTC 是 livekit-android 的传递依赖，通常单独分发
echo ""
echo "[Step 3] Checking for additional WebRTC .so files..."

# 搜索 Gradle 缓存中的 WebRTC（如果有）
GRADLE_CACHE="$HOME/.gradle/caches"
WEBRTC_FOUND=0

for search_path in \
    "$GRADLE_CACHE/modules-2/files-2.1/org/webrtc" \
    "$GRADLE_CACHE/modules-2/files-2.1/com/google/android/webrtc"; do
    [ -d "$search_path" ] || continue
    while IFS= read -r webrtc_aar; do
        [ -f "$webrtc_aar" ] || continue
        WEBRTC_TMP="$WORK_DIR/webrtc-extract"
        rm -rf "$WEBRTC_TMP" && mkdir -p "$WEBRTC_TMP"
        unzip -qo "$webrtc_aar" -d "$WEBRTC_TMP" 2>/dev/null || true
        
        for abi in $TARGET_ABIS; do
            if [ -d "$WEBRTC_TMP/jni/$abi" ] && [ "$(ls $WEBRTC_TMP/jni/$abi/*.so 2>/dev/null)" != "" ]; then
                DEST_DIR="$JNI_DIR/$abi"
                while IFS= read -r so_file; do
                    base=$(basename "$so_file")
                    # 避免覆盖已有的同名文件
                    [ -f "$DEST_DIR/$base" ] && continue
                    cp "$so_file" "$DEST_DIR/" 2>/dev/null || true
                    WEBRTC_FOUND=$((WEBRTC_FOUND + 1))
                done < <(find "$WEBRTC_TMP/jni/$abi" -name "*.so" 2>/dev/null)
            fi
        done
        rm -rf "$WEBRTC_TMP"
    done < <(find "$search_path" -name "*.aar" ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | head -3)
done

if [ "$WEBRTC_FOUND" -gt 0 ]; then
    echo "  ✓ Found +$WEBRTC_FOUND extra .so files from WebRTC in Gradle cache"
else
    echo "  ℹ No Gradle cache found (expected on CI/local without prior build)"
    echo "  Tip: Run 'gradle dependencies' first to populate cache, or use CI build"
fi

# ---- 汇总 ----
echo ""
echo "============================================="
TOTAL_SO=0
for abi in $TARGET_ABIS; do
    count=$(ls "$JNI_DIR/$abi/"*.so 2>/dev/null | wc -l)
    TOTAL_SO=$((TOTAL_SO + count))
done
echo "  Done! Total .so files in jniLibs: $TOTAL_SO"
echo ""

if [ "$TOTAL_SO" -eq 0 ]; then
    echo "  ⚠ WARNING: No .so files were extracted!"
    echo "  This may mean:"
    echo "    1. LiveKit $LIVEKIT_VERSION distributes .so via transitive deps only"
    echo "    2. The .so files come from webrtc-android dependency (not livekit-android directly)"
    echo ""
    echo "  Alternative: Use the CI workflow which handles Fat AAR assembly"
    echo "  with full dependency resolution from Gradle cache."
else
    echo "  Files per ABI:"
    for abi in $TARGET_ABIS; do
        echo "    $abi/:"
        ls "$JNI_DIR/$abi/"*.so 2>/dev/null | while read f; do
            size=$(du -h "$f" | cut -f1)
            name=$(basename "$f")
            echo "      $name ($size)"
        done
    done
fi

echo "============================================="
