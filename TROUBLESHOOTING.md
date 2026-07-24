# VoiceCloner 闪退排查与修复记录

> **日期**：2026-07-24
> **项目**：VoiceCloner（Android 离线语音克隆 App）
> **引擎**：sherpa-onnx v1.13.4 + ONNX Runtime
> **核心问题**：ZipVoice 模型选好音频后点击「克隆并合成」→ App 闪退

---

## 目录

1. [环境信息](#1-环境信息)
2. [排查路线图](#2-排查路线图)
3. [问题一：WAV 解析导致 Native Crash](#3-问题一wav-解析导致-native-crash)
4. [问题二：文件权限 EACCES（Error 13）](#4-问题二文件权限-eacceserror-13)
5. [问题三：referenceText 为空](#5-问题三referencetext-为空)
6. [问题四：espeak-ng-data 路径配置错误](#6-问题四espeak-ng-data-路径配置错误)
7. [问题五：MatchaTtsLexicon data_dir 为空→exit()](#7-问题五matchattslexicon-data_dir-为空exit)
8. [最终根因与修复总结](#8-最终根因与修复总结)
9. [给其他项目的排查建议](#9-给其他项目的排查建议)

---

## 1. 环境信息

| 项目 | 值 |
|------|-----|
| 设备 | Android 手机 |
| 系统 | Android 11+（API 30+） |
| TTS 引擎 | sherpa-onnx v1.13.4 |
| 模型 | ZipVoice（sherpa-onnx-zipvoice-distill-int8-zh-en-emilia） |
| 参考音频 | WAV 16-bit, 44100Hz, 单声道 |
| 输入文本 | 中文 + 英文混合 |

---

## 2. 排查路线图

```
用户点击「克隆并合成」
  ↓
① WAV 文件解析 → 44字节简单解析遇到扩展chunk → 乱码数据 → Native Crash
    ↓ 修复：重写WAV解析器，遍历chunk
  ↓
② 文件权限 EACCES → ONNX Runtime 用 mmap 读文件失败
    ↓ 修复：复制到App缓存目录
  ↓
③ 模型加载成功 → ZipVoice要求 referenceText 非空 → 返回空音频 → AudioTrack异常 → Crash
    ↓ 修复：添加 referenceText UI输入 + 空音频防御检查
  ↓
④ espeak-ng-data 索引文件缺失 → 找不到 phontab → 验证失败
    ↓ 修复：确认文件存在，修正 dataDir 路径指向
  ↓
⑤ MatchaTtsLexicon 构造: data_dir 为空 → exit(-1) 杀进程
    ↓ 最终修复：dataDir 指向 espeak-ng-data 子目录
  ↓
✅ 生成成功！
```

---

## 3. 问题一：WAV 解析导致 Native Crash

### 现象

点击「克隆并合成」后 App 闪退，无 Java 异常日志（干净的闪退）。

### 排查方向

- 原始 WAV 解析只读 44 字节固定头
- 遇到带扩展 chunk 的 WAV 文件（如 `list`、`fact` 等）解析出乱码数据
- 乱码数据传给 ONNX Runtime → **Native Crash**（Java try-catch 无法捕获）

### 修复方案

```java
// 重写 WAV 解析器，遍历所有 chunk 找 "fmt " 和 "data"
while (true) {
    byte[] chunkId = new byte[4];
    int read = dis.read(chunkId);
    if (read < 4) break;
    int chunkSize = Integer.reverseBytes(dis.readInt());
    if (chunkId == "fmt ") { /* 读取格式信息 */ }
    else if (chunkId == "data") { /* 读取音频数据 */ break; }
    else { dis.skipBytes(chunkSize); }  // 跳过不认识chunk
}
```

### 经验教训

- **Native Crash = 大概率数据问题**，不是代码逻辑问题
- **WAV 文件格式多样**，不要假设固定 44 字节头
- 必须遍历 chunk，支持 8/16/32-bit、多通道转单声道

---

## 4. 问题二：文件权限 EACCES（Error 13）

### 现象

```
Load model from .../vocos_24khz.onnx failed: system error number 13
```

错误码 13 = `EACCES`（Permission denied）。

### 排查方向

- 文件存在且 Java 的 `FileInputStream` 能读 1 字节
- 但 ONNX Runtime 底层用 `mmap` 读文件，对外部存储有更严格的权限要求
- **Java 能读 ≠ native 能读**

### 修复方案

```java
private String ensureReadable(String path) {
    // 如果文件不在缓存目录，强制复制到 App 私有缓存
    if (!path.startsWith(appContext.getCacheDir().getAbsolutePath())) {
        // 复制文件到 cache/tts_models/
        // 返回缓存路径
    }
    return path;
}
```

### 经验教训

- **Java 文件可读 ≠ JNI native 可读**（mmap 权限要求不同）
- **外部存储文件权限不可靠**，应复制到 App 私有目录
- 缓存路径 `getCacheDir()` 是安全的，无权限问题

---

## 5. 问题三：referenceText 为空

### 现象

模型加载成功（文件 ✅），选好音频后点生成 → 闪退。

### 日志

```
// 无 Java 错误日志，干净的闪退
```

### 排查方向

通过分析 sherpa-onnx C++ 源码发现：

```cpp
// sherpa-onnx/csrc/offline-tts-zipvoice-impl.h:83-102
if (config.reference_text.empty()) {
    SHERPA_ONNX_LOGE("reference_text is empty.");
    return {};  // ← 返回空音频 (sampleRate=0)
}
```

然后 Java 回调：

```java
// zipvoice C++ 返回空音频 → Java 回调收到 sampleRate=0
AudioUtils.playPcm(samples, 0);
// → AudioTrack.getMinBufferSize(0, ...) → 抛出异常 → 闪退
```

### 修复方案

1. **UI 添加参考文本输入框**
2. **生成前检查 referenceText 非空**
3. **增加防御性空音频检查**

```java
// 生成前检查 referenceText
referenceText = binding.etRefText.getText().toString().trim();
if (referenceText.isEmpty()) {
    showToast("请输入参考音频的文字内容");
    return;
}

// 回调中检查空音频
if (samples == null || samples.length == 0 || sampleRate <= 0) {
    updateStatus("❌ 生成结果为空");
    return;
}
```

### 经验教训

- **ZipVoice 强制要求 `reference_text`**（与参考音频对应的文字内容）
- **Native 返回空对象不一定 Crash，但下游代码可能触发异常**
- 阅读 sherpa-onnx 示例代码（Node.js/Python/C API）发现所有 ZipVoice 用法都传了 `referenceText`
- **仔细阅读官方示例**可以发现必填参数

---

## 6. 问题四：espeak-ng-data 路径配置错误

### 现象

```
config: ..., data_dir="", ...
matcha-tts-lexicon.cc:136: Please provide data dir for this model
```

### 排查方向

设置 `dataDir = modelDir`（模型目录）后：

```
Validate:90: '...emilia/phontab' does not exist. Please check zipvoice-data-dir
```

通过 adb 检查发现 `phontab` 文件在 `espeak-ng-data/` 子目录下，不在模型根目录。

### 修复方案

```java
// ❌ 错误：dataDir 指向模型目录
dataDir = modelDir.getAbsolutePath();
// 验证检查 data_dir/phontab → 找到的是 ...emilia/phontab（不存在）

// ✅ 正确：dataDir 指向 espeak-ng-data 子目录
dataDir = new File(modelDir, "espeak-ng-data").getAbsolutePath();
// 验证检查 data_dir/phontab → 找到的是 ...emilia/espeak-ng-data/phontab（存在）
```

### 经验教训

- **sherpa-onnx 各版本的 dataDir 语义不同**：
  - 有些版本要求 `data_dir` 指向包含 `espeak-ng-data/` 的父目录
  - 本版本（v1.13.4）要求 `data_dir` **直接指向 `espeak-ng-data/` 本身**
- **验证逻辑**：`if (!FileExists(data_dir + "/phontab"))` → 所以 `data_dir/phontab` 必须存在
- 参考 sherpa-onnx 的 Python/C API 示例确认用法

---

## 7. 问题五：MatchaTtsLexicon data_dir 为空 → exit()

### 现象

配置 `data_dir=""` 后，模型**加载成功**，但生成时闪退。

### 日志

```
// 模型加载成功！
ZipVoice loaded. SR: 24000

// 但 MatchaTtsLexicon 构造时：
matcha-tts-lexicon.cc:136: Please provide data dir for this model
// 随后调用 SHERPA_ONNX_EXIT(-1) → exit(-1) → 进程被杀死
```

### 根因代码

```cpp
// sherpa-onnx/csrc/matcha-tts-lexicon.cc:119-141
class MatchaTtsLexicon::Impl {
  Impl(...) {
    if (lexicon.empty()) {
      SHERPA_ONNX_LOGE("Please provide lexicon.txt for this model");
      SHERPA_ONNX_EXIT(-1);    // ← exit() 直接杀进程！
    }
    InitLexicon(lexicon);
    
    if (data_dir.empty()) {
      SHERPA_ONNX_LOGE("Please provide data dir for this model");
      SHERPA_ONNX_EXIT(-1);    // ← 致命！exit(-1) 无法被 Java 捕获
    }
    
    InitEspeak(data_dir);  // 初始化 espeak-ng
  }
};
```

而且 `InitEspeak` 内部也会验证：

```cpp
void InitEspeak(const std::string &data_dir) {
  int32_t result = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, data_dir.c_str(), 0);
  if (result != 22050) {
    SHERPA_ONNX_LOGE("Failed to initialize espeak-ng with data dir: %s", data_dir.c_str());
    SHERPA_ONNX_EXIT(-1);  // 再杀一次
  }
}
```

### 经验教训

- **`SHERPA_ONNX_EXIT()` 是致命武器**——它在 C++ 中调用 `exit()`，直接杀进程
- Java 的 `try-catch` 无法捕获 `exit()`，App 表现为"干净的闪退"
- **模型加载成功 ≠ 生成能成功**——`OfflineTts` 构造成功但 `InitFrontend()` 在生成时才调用
- **排查 C++ 源码**是找到这类问题的唯一途径

---

## 8. 最终根因与修复总结

### 最终根因

| 层级 | 问题 | 表现 |
|------|------|------|
| 业务层 | `referenceText` 未设置 | ZipVoice 返回空音频 |
| 数据层 | WAV 解析不支持扩展 chunk | 乱码数据 → Native Crash |
| 权限层 | 外部存储 mmap 权限不足 | EACCES error 13 |
| 配置层 | `dataDir` 指向模型目录而非 espeak-ng-data | 找不到 phontab → 验证失败 |
| 引擎层 | `dataDir=""` 时 `exit(-1)` | 进程被杀死 |

### 最终修复

```java
// 1. WAV 解析支持扩展 chunk（遍历所有 chunk）
// 2. 所有模型文件强制复制到缓存目录
// 3. UI 添加参考文本输入框
// 4. 数据防御检查（空音频、NaN/Infinity）
// 5. dataDir 指向 espeak-ng-data 子目录
// 6. 参考音频重采样到 16000Hz

String dataDir = new File(modelDir, "espeak-ng-data").getAbsolutePath();
```

### 验证结果

```
ZipVoice loaded. SR: 24000
ZipVoice gen success: 126720 samples @ 24000Hz
✅ 生成完成! (5.28s 音频)
已保存: output_xxx.wav
```

中文、英文、中英混合全部正常工作。

---

## 9. 给其他项目的排查建议

### 9.1 闪退排查流程

```
App 闪退
  ↓
有 Java 异常日志？ → 修复 Java 层问题
  ↓
无日志（干净闪退）？
  ↓
大概率是 Native Crash 或 exit()
  ↓
查看 logcat 中:
  • "DEBUG" 标签 → Native Crash SIGSEGV/SIGABRT 堆栈
  • "sherpa-onnx" 标签 → C++ 日志（WARNING级别）
  • "AndroidRuntime" 标签 → Java 未捕获异常
  ↓
搜索 sherpa-onnx GitHub 对应错误的 C++ 源码
```

### 9.2 sherpa-onnx 集成检查清单

- [ ] 模型文件完整（所有 .onnx + tokens.txt + lexicon.txt + espeak-ng-data）
- [ ] `espeak-ng-data` 包含 `phontab`、`phonindex`、`phondata`、`intonations`
- [ ] `dataDir` 指向正确的目录（各版本语义不同，参考官方示例）
- [ ] `referenceText` 对 ZipVoice 是**必填**的
- [ ] 参考音频采样率与模型匹配（ZipVoice 内部重采样但最好送 16000Hz）
- [ ] 模型文件在 App 私有目录（避免 EACCES）
- [ ] 所有 JNI 调用放在 try-catch 中（虽然不能捕获 native crash）

### 9.3 调试技巧

| 技巧 | 说明 |
|------|------|
| `adb logcat -s "sherpa-onnx"` | 查看 C++ 层日志（WARNING 级别） |
| `adb logcat -s "DEBUG"` | 查看 Native Crash 堆栈 |
| 搜索 GitHub 源码 | 根据 logcat 中的文件名+行号找到对应 C++ 代码 |
| 参考官方示例 | Python/Node.js/C API 示例会暴露必填参数 |
| 检查 `config.ToString()` | JNI 层会打印完整配置，确认所有字段值 |

---

## 附录：修改的文件清单

| 文件 | 修改内容 |
|------|---------|
| `AudioUtils.java` | 新增 `resampleTo16000Hz()`、`clampDuration()` |
| `MainActivity.java` | 参考文本 UI、重采样、dataDir 指向 espeak-ng-data、防御检查 |
| `TtsEngineManager.java` | `ensureReadable()` 强制复制到缓存、`UnsatisfiedLinkError` 捕获、严格空音频检测 |
| `activity_main.xml` | 新增参考文本输入框、默认文本改为纯中文 |
| `app/build.gradle` | 合并重复 `aaptOptions` 块 |
