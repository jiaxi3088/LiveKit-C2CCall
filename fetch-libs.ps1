# fetch-libs.ps1 — 从 Maven Central 下载 LiveKit SDK + WebRTC 的 .so 文件到 jniLibs
# 用法: .\fetch-libs.ps1 [-Version "2.25.1"]
param(
    [string]$Version = "2.25.1"
)

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$JniDir = Join-Path $ScriptDir "android\src\main\jniLibs"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "  Fetching LiveKit Android SDK native libs" -ForegroundColor Cyan
Write-Host "  Version: $Version" -ForegroundColor Cyan
Write-Host "  Target:  $JniDir" -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

$WorkDir = Join-Path $env:TEMP "livekit-fetch-libs-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null

try {
    # ---- Step 1: 下载 livekit-android AAR ----
    Write-Host ""
    Write-Host "[Step 1] Downloading livekit-android:$Version AAR..." -ForegroundColor Yellow

    $LiveKitUrl = "https://repo1.maven.org/maven2/io/livekit/livekit-android/$Version/livekit-android-$Version.aar"
    $LiveKitAar = Join-Path $WorkDir "livekit-android.aar"

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $LiveKitUrl -OutFile $LiveKitAar -UseBasicParsing
    $size = (Get-Item $LiveKitAar).Length / 1MB
    Write-Host "  Downloaded: $($size.ToString('F2')) MB"

    # ---- Step 2: 解压并提取 .so ----
    Write-Host ""
    Write-Host "[Step 2] Extracting .so files..." -ForegroundColor Yellow

    $ExtractDir = Join-Path $WorkDir "extracted"
    New-Item -ItemType Directory -Force -Path $ExtractDir | Out-Null

    # 使用 Expand-Archive (PowerShell 5+) 或 .NET 解压
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($LiveKitAar, $ExtractDir)

    $targetAbis = @("arm64-v8a", "armeabi-v7a")
    $soCount = 0

    foreach ($abi in $targetAbis) {
        $destDir = Join-Path $JniDir $abi
        New-Item -ItemType Directory -Force -Path $destDir | Out-Null

        $jniAbiDir = Join-Path $ExtractDir "jni\$abi"
        if (Test-Path $jniAbiDir) {
            $soFiles = Get-ChildItem -Path $jniAbiDir -Filter "*.so" -ErrorAction SilentlyContinue
            foreach ($so in $soFiles) {
                Copy-Item $so.FullName -Destination $destDir -Force
                $soSizeKB = [math]::Round($so.Length / 1KB, 1)
                Write-Host "    ✓ $abi : $($so.Name) ($soSizeKB KB)" -ForegroundColor Green
                $soCount++
            }
            if ($soFiles.Count -eq 0) {
                Write-Host "    ⚠ $abi : no .so files in livekit-android jni/" -ForegroundColor DarkYellow
            }
        } else {
            Write-Host "    ⚠ $abi : no jni directory in livekit-android AAR" -ForegroundColor DarkYellow
        }
    }

    # ---- Step 3: 尝试从本地 Gradle 缓存找 WebRTC .so ----
    Write-Host ""
    Write-Host "[Step 3] Checking Gradle cache for WebRTC .so..." -ForegroundColor Yellow

    $gradleCache = Join-Path $env:USERPROFILE ".gradle\caches"
    $webRtcFound = 0

    if (Test-Path $gradleCache) {
        # 搜索 webrtc 相关的 AAR
        $webrtcDirs = @(
            (Join-Path $gradleCache "modules-2\files-2.1\org\webrtc"),
            (Join-Path $gradleCache "modules-2\files-2.1\com\google\android\webrtc")
        )

        foreach ($searchDir in $webrtcDirs) {
            if (-not (Test-Path $searchDir)) { continue }
            
            $aarFiles = Get-ChildItem -Path $searchDir -Recurse -Filter "*.aar" -ErrorAction SilentlyContinue | 
                        Where-Object { $_.Name -notmatch "(sources|javadoc)" } |
                        Select-Object -First 3
            
            foreach ($aar in $aarFiles) {
                $webRtcTmp = Join-Path $WorkDir "webrtc-extract"
                if (Test-Path $webRtcTmp) { Remove-Item $webRtcTmp -Recurse -Force }
                New-Item -ItemType Directory -Force -Path $webRtcTmp | Out-Null
                
                try {
                    [System.IO.Compression.ZipFile]::ExtractToDirectory($aar.FullName, $webRtcTmp)
                    
                    foreach ($abi in $targetAbis) {
                        $abiSoDir = Join-Path $webRtcTmp "jni\$abi"
                        if (Test-Path $abiSoDir) {
                            $extraSos = Get-ChildItem -Path $abiSoDir -Filter "*.so" -ErrorAction SilentlyContinue
                            foreach ($eso in $extraSos) {
                                $destDir = Join-Path $JniDir $abi
                                $destFile = Join-Path $destDir $eso.Name
                                if (-not (Test-Path $destFile)) {
                                    Copy-Item $eso.FullName -Destination $destDir -Force
                                    $webRtcFound++
                                }
                            }
                        }
                    }
                } catch {}
                
                if (Test-Path $webRtcTmp) { Remove-Item $webRtcTmp -Recurse -Force }
            }
        }
        
        if ($webRtcFound -gt 0) {
            Write-Host "    ✓ Found +$webRtcFound extra .so from WebRTC cache" -ForegroundColor Green
        }
    }
    
    Write-Host "    ℹ No local Gradle cache or no extra WebRTC .so found (normal on first run)" -ForegroundColor DarkGray
    
    # ---- 汇总 ----
    Write-Host ""
    Write-Host "=============================================" -ForegroundColor Cyan
    $totalSo = 0
    foreach ($abi in $targetAbis) {
        $abiDir = Join-Path $JniDir $abi
        if (Test-Path $abiDir) {
            $count = @(Get-ChildItem -Path $abiDir -Filter "*.so").Count
            $totalSo += $count
        }
    }
    
    Write-Host "  Done! Total .so files in jniLibs: $totalSo" -ForegroundColor White
    
    if ($totalSo -eq 0) {
        Write-Host ""
        Write-Host "  ⚠ WARNING: No .so files were extracted!" -ForegroundColor Red
        Write-Host "  The livekit-android AAR may not bundle .so directly." -ForegroundColor Red
        Write-Host "  Native libs likely come from transitive dependency:" -ForegroundColor Red
        Write-Host "    org.webrtc:webrtc-android (pulled automatically by Gradle)" -ForegroundColor Red
        Write-Host ""
        Write-Host "  → Use CI workflow for Fat AAR assembly instead," -ForegroundColor Yellow
        Write-Host "    which resolves ALL dependencies from Gradle cache." -ForegroundColor Yellow
    } else {
        Write-Host ""
        Write-Host "  Files per ABI:" -ForegroundColor White
        foreach ($abi in $targetAbis) {
            $abiDir = Join-Path $JniDir $abi
            Write-Host "    $abi/ :" -ForegroundColor White
            Get-ChildItem -Path $abiDir -Filter "*.so" -ErrorAction SilentlyContinue | ForEach-Object {
                $sz = [math]::Round($_.Length / 1KB, 1)
                Write-Host "      $($_.Name) ($sz KB)" -ForegroundColor Gray
            }
        }
    }
    Write-Host "=============================================" -ForegroundColor Cyan
} finally {
    # 清理临时目录
    if (Test-Path $WorkDir) {
        Remove-Item $WorkDir -Recurse -Force
    }
}
