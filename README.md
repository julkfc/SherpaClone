# SherpaClone

Android 离线中文语音克隆 App（基于 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)）

## 特性

- 🎯 **零样本语音克隆** — 3-10 秒参考音频即可克隆任意音色
- 🀄 **中文 + 英文**双引擎支持（ZipVoice）
- 📱 **完全离线运行**，无需网络
- 🎚️ **多模型一键切换**（ZipVoice / PocketTTS / Kokoro）
- ✅ **ZipVoice 生成闪退问题已解决**（详见 `VoiceCloner 闪退排查与修复记录.md`）

## 快速开始

```bash
git clone https://github.com/你的用户名/SherpaClone
cd SherpaClone
# 设置 Android SDK 路径后编译
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 模型部署

从 [sherpa-onnx TTS Models Release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models) 下载模型文件，放入手机：

```
内部存储/Android/data/com.example.voicecloner/files/tts_models/
├── vocos_24khz.onnx
└── sherpa-onnx-zipvoice-distill-int8-zh-en-emilia/
    ├── encoder.int8.onnx
    ├── decoder.int8.onnx
    ├── tokens.txt
    └── lexicon.txt
```

## 构建

```bash
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 许可证

Apache 2.0。详见 [LICENSE](LICENSE)。

本项目包含 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0) 的 Java API 源码。

## 致谢

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 端侧推理引擎
- [VoxSherpa-TTS](https://github.com/CodeBySonu95/VoxSherpa-TTS) — 参考实现
