# VoiceCloner 编译脚本 (PowerShell)
# 使用前确保已安装 Android SDK

Write-Host "🔨 VoiceCloner 编译脚本" -ForegroundColor Cyan
Write-Host "================================"

# 检查 gradle wrapper
if (-not (Test-Path "gradlew")) {
    Write-Host "📋 正在复制 Gradle Wrapper..." -ForegroundColor Yellow
    if (Test-Path "../android/gradlew") {
        Copy-Item "../android/gradlew" "gradlew"
        Copy-Item "../android/gradlew.bat" "gradlew.bat"
        Copy-Item -Recurse "../android/gradle" "gradle"
        Write-Host "✅ Gradle Wrapper 已复制" -ForegroundColor Green
    } else {
        Write-Host "❌ 找不到 Gradle Wrapper，请先安装或从 Android Studio 同步" -ForegroundColor Red
        exit 1
    }
}

# 编译
Write-Host "🔧 正在编译 Debug APK..." -ForegroundColor Cyan
$result = ./gradlew assembleDebug 2>&1

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n✅ 编译成功！" -ForegroundColor Green
    # 查找 APK
    $apk = Get-ChildItem -Path "app/build/outputs/apk/debug" -Filter "*.apk" | Select-Object -First 1
    if ($apk) {
        Write-Host "📦 APK 位置: $($apk.FullName)" -ForegroundColor Green
        Write-Host "📏 大小: $('{0:N2}' -f ($apk.Length / 1MB)) MB" -ForegroundColor Green
    }
} else {
    Write-Host "`n❌ 编译失败" -ForegroundColor Red
    Write-Host $result
}
