# SherpaClone

Android 离线中文语音克隆 App（基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)）

## 特性

- 🎯 **零样本语音克隆** — 3-10 秒参考音频即可克隆任意音色
- 🀄 **中文 + 英文**双引擎支持（ZipVoice）
- 📱 **完全离线运行**，无需网络
- 🎚️ **多模型一键切换**（ZipVoice / PocketTTS / Kokoro）
- ✅ **ZipVoice 生成闪退问题已解决**（排查过程详见 [TROUBLESHOOTING.md](TROUBLESHOOTING.md)）
- ⚠️ **仅测试了 ZipVoice**，PocketTTS 和 Kokoro 尚未实测

## 下载 APK

👉 [Releases](https://github.com/julkfc/SherpaClone/releases) 页面有编译好的 APK，可直接安装。

## 前提条件

- Android SDK 34+
- JDK 17+
- Android 手机（API 26+）

## 构建 & 运行

**① 克隆并编译**

```bash
git clone https://github.com/julkfc/SherpaClone
cd SherpaClone
./gradlew assembleDebug
```

APK 生成在 `app/build/outputs/apk/debug/app-debug.apk`

**② 下载模型文件**

模型约 100MB，从 sherpa-onnx 官方 Release 下载：

```bash
# ZipVoice 中文+英文克隆模型
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-zipvoice-distill-int8-zh-en-emilia.tar.bz2
# Vocoder（必需）
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/vocoder-models/vocos_24khz.onnx
```

**③ 部署到手机**

```bash
# 安装 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 将模型文件放到手机（路径见下方说明）
```

手机上的目录结构：

```
内部存储/Android/data/com.example.voicecloner/files/tts_models/
├── vocos_24khz.onnx
└── sherpa-onnx-zipvoice-distill-int8-zh-en-emilia/
    ├── encoder.int8.onnx
    ├── decoder.int8.onnx
    ├── tokens.txt
    └── lexicon.txt
```

## 许可证

Apache 2.0。详见 [LICENSE](LICENSE)。

本项目包含 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0) 的 Java API 源码。

## 致谢

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 端侧推理引擎
- [DeepSeek](https://deepseek.com/) V4 Flash — AI 编程助手

> 本项目代码完全独立编写。项目结构受 [VoxSherpa-TTS](https://github.com/CodeBySonu95/VoxSherpa-TTS) 思路启发，但未使用或复制其任何代码。
