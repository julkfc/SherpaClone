package com.example.voicecloner;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.voicecloner.databinding.ActivityMainBinding;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.Locale;

/**
 * VoiceCloner 主界面
 *
 * 支持功能：
 * 1. ZipVoice — 中文/英文零样本语音克隆
 * 2. PocketTTS — 英文零样本语音克隆
 * 3. Kokoro — 多说话人 TTS（50+ 预置声音）
 *
 * 使用前请先下载模型：
 *   ZipVoice:   sherpa-onnx-zipvoice-distill-int8-zh-en-emilia
 *   PocketTTS:  sherpa-onnx-pocket-tts-int8-2026-01-26
 *   Kokoro:     kokoro-en-v0_19
 *
 * 下载地址：https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    private ActivityMainBinding binding;

    private TtsEngineManager engineManager;
    private ModelManager modelManager;

    // 当前模型类型
    private TtsEngineManager.ModelType currentModelType =
        TtsEngineManager.ModelType.ZIPVOICE;

    // 参考音频路径（用于克隆）
    private String referenceAudioPath;
    private float[] referenceAudioData;
    private int referenceSampleRate = 16000;
    private String referenceText = "";

    // 权限请求
    private final ActivityResultLauncher<String> requestRecordPermission =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                startRecording();
            } else {
                showToast("需要录音权限才能录制参考音频");
            }
        });

    private final ActivityResultLauncher<String> requestReadPermission =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (granted) {
                selectAudioFile();
            } else {
                showToast("需要存储权限才能选择音频文件");
            }
        });

    // 文件选择器
    private final ActivityResultLauncher<String> filePicker =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                try {
                    String fileName = "reference_audio.wav";
                    File destFile = new File(getExternalFilesDir(null), fileName);
                    java.io.InputStream is = getContentResolver().openInputStream(uri);
                    if (is != null) {
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(destFile);
                        byte[] buffer = new byte[8192];
                        int len;
                        while ((len = is.read(buffer)) != -1) fos.write(buffer, 0, len);
                        fos.close();
                        is.close();

                        referenceAudioPath = destFile.getAbsolutePath();
                        referenceAudioData = AudioUtils.readWavAsFloats(referenceAudioPath);
                        referenceSampleRate = AudioUtils.getWavSampleRate(referenceAudioPath);

                        if (referenceAudioData == null || referenceAudioData.length < 100) {
                            showToast("无法解析音频文件，请使用16-bit PCM WAV格式");
                            referenceAudioData = null;
                            return;
                        }

                        // 重采样到 16000Hz（ZipVoice 模型期望的采样率）
                        if (referenceSampleRate != 16000) {
                            Log.d(TAG, "Resampling from " + referenceSampleRate + "Hz to 16000Hz (original length: " + referenceAudioData.length + ")");
                            referenceAudioData = AudioUtils.resampleTo16000Hz(referenceAudioData, referenceSampleRate);
                            referenceSampleRate = 16000;
                        }

                        // 裁剪音频到最大 10 秒（避免过长的参考音频导致内存问题）
                        referenceAudioData = AudioUtils.clampDuration(referenceAudioData, referenceSampleRate, 10f);

                        // 诊断信息
                        float maxVal = 0, sumAbs = 0;
                        for (int i = 0; i < Math.min(referenceAudioData.length, 50000); i++) {
                            float v = Math.abs(referenceAudioData[i]);
                            if (Float.isFinite(v)) { maxVal = Math.max(maxVal, v); sumAbs += v; }
                        }
                        float avg = sumAbs / Math.min(referenceAudioData.length, 50000);
                        String fileInfo = String.format("文件: %d采样, %.1f秒, %dHz, 最大振幅:%.4f, 平均振幅:%.4f",
                            referenceAudioData.length,
                            referenceAudioData.length / (float)referenceSampleRate,
                            referenceSampleRate, maxVal, avg);
                        Log.d(TAG, fileInfo);

                        if (maxVal < 0.001f) {
                            showToast("文件全静音，请选择有声音的音频");
                            referenceAudioData = null;
                            return;
                        }
                        if (maxVal < 0.02f) {
                            Log.w(TAG, "音频音量极低，可能有问题: " + fileInfo);
                            showToast("⚠️ 音频音量极低，克隆效果可能不佳");
                            // 仍然接受
                        }

                        binding.tvRefAudio.setText("已选择: " + destFile.getName() +
                            " (" + referenceSampleRate + "Hz, " + 
                            (referenceAudioData.length / (float)referenceSampleRate) + "s, 最大振幅:" + String.format("%.3f", maxVal) + ")");
                        updateStatus("✅ 参考音频已加载\n" + fileInfo);
                        showToast("参考音频已加载");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to load audio file", e);
                    showToast("加载音频失败: " + e.getMessage());
                }
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 初始化引擎
        engineManager = TtsEngineManager.getInstance();
        engineManager.init(this);

        modelManager = new ModelManager(this);

        // 设置模型切换
        setupModelToggle();

        // 设置按钮监听
        setupButtons();

        // 检查是否已有模型
        checkModels();
    }

    private void setupModelToggle() {
        binding.modelToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;

            if (checkedId == R.id.btnZipVoice) {
                currentModelType = TtsEngineManager.ModelType.ZIPVOICE;
                binding.referenceCard.setVisibility(View.VISIBLE);
                binding.btnGenerate.setText("🎧 克隆并合成");
            } else if (checkedId == R.id.btnPocketTts) {
                currentModelType = TtsEngineManager.ModelType.POCKET_TTS;
                binding.referenceCard.setVisibility(View.VISIBLE);
                binding.btnGenerate.setText("🎧 克隆并合成");
            } else if (checkedId == R.id.btnKokoro) {
                currentModelType = TtsEngineManager.ModelType.KOKORO;
                binding.referenceCard.setVisibility(View.GONE);
                binding.btnGenerate.setText("🎧 生成语音");
            }

            updateStatus("切换模型类型: " + currentModelType);
        });

        // 默认选中 ZipVoice
        binding.btnZipVoice.setChecked(true);
    }

    private void setupButtons() {
        // 生成语音
        binding.btnGenerate.setOnClickListener(v -> generateSpeech());

        // 录音
        binding.btnRecord.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestRecordPermission.launch(Manifest.permission.RECORD_AUDIO);
            } else {
                startRecording();
            }
        });

        // 选择音频文件
        binding.btnSelectFile.setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                // Android 13+ 使用新的媒体权限
                filePicker.launch("audio/*");
            } else if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestReadPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            } else {
                selectAudioFile();
            }
        });
    }

    private void startRecording() {
        if (AudioUtils.isRecording()) {
            String path = AudioUtils.stopRecording();
            if (path != null) {
                referenceAudioPath = path;
                referenceAudioData = AudioUtils.readWavAsFloats(path);
                referenceSampleRate = 16000;
                binding.tvRefAudio.setText("已录制参考音频 (16kHz)");
                binding.btnRecord.setText("开始录音");
                showToast("录音完成");
            }
            return;
        }

        String outputPath = new File(getExternalFilesDir(null),
            "recording_" + System.currentTimeMillis() + ".3gp").getAbsolutePath();
        if (AudioUtils.startRecording(outputPath)) {
            binding.btnRecord.setText("⏹ 停止录音");
            updateStatus("正在录音...");
        } else {
            showToast("录音启动失败");
        }
    }

    private void selectAudioFile() {
        filePicker.launch("audio/*");
    }

    private void generateSpeech() {
        String text = binding.etInputText.getText().toString().trim();
        if (text.isEmpty()) {
            showToast("请输入文本");
            return;
        }

        // 克隆类模型需要参考音频
        if ((currentModelType == TtsEngineManager.ModelType.ZIPVOICE ||
             currentModelType == TtsEngineManager.ModelType.POCKET_TTS) &&
            referenceAudioData == null) {
            showToast("请先录制或选择参考音频");
            return;
        }

        // 获取参考文本（克隆类模型需要）
        if (currentModelType == TtsEngineManager.ModelType.ZIPVOICE ||
            currentModelType == TtsEngineManager.ModelType.POCKET_TTS) {
            referenceText = binding.etRefText.getText().toString().trim();
            if (referenceText.isEmpty()) {
                showToast("请输入参考音频的文字内容");
                binding.etRefText.requestFocus();
                binding.btnGenerate.setEnabled(true);
                return;
            }
        }

        binding.layoutProgress.setVisibility(View.VISIBLE);
        binding.progressBar.setProgress(0);
        binding.btnGenerate.setEnabled(false);
        updateStatus("正在生成...");

        TtsEngineManager.ProgressCallback callback = new TtsEngineManager.ProgressCallback() {
            @Override
            public void onProgress(float progress) {
                binding.progressBar.setProgress((int) (progress * 100));
                binding.tvProgress.setText("生成中: " + (int) (progress * 100) + "%");
            }

            @Override
            public void onComplete(float[] samples, int sampleRate) {
                binding.btnGenerate.setEnabled(true);
                binding.layoutProgress.setVisibility(View.GONE);

                // 防御检查：空音频或无效采样率
                if (samples == null || samples.length == 0 || sampleRate <= 0) {
                    updateStatus("❌ 生成结果为空，请检查参考音频和文本是否正确");
                    showToast("生成失败：音频为空");
                    return;
                }

                updateStatus("✅ 生成完成! (" + (samples.length / (float) sampleRate) + "s 音频)");

                // 播放
                AudioUtils.playPcm(samples, sampleRate);

                // 保存
                String fileName = "output_" + System.currentTimeMillis() + ".wav";
                File outputFile = new File(getExternalFilesDir(null), fileName);
                TtsEngineManager.saveAsWav(samples, sampleRate, outputFile.getAbsolutePath());
                String currentText = binding.tvStatus.getText().toString();
                updateStatus(currentText + "\n已保存: " + outputFile.getName());
            }

            @Override
            public void onError(String message) {
                binding.btnGenerate.setEnabled(true);
                binding.layoutProgress.setVisibility(View.GONE);
                updateStatus("❌ 错误: " + message);
                showToast(message);
            }
        };

        switch (currentModelType) {
            case ZIPVOICE: {
                String loadErr = tryLoadZipVoice();
                if (loadErr != null) {
                    binding.btnGenerate.setEnabled(true);
                    binding.layoutProgress.setVisibility(View.GONE);
                    showToast("模型加载失败");
                    break;
                }
                engineManager.generateWithZipVoice(text, referenceAudioData,
                    referenceSampleRate, referenceText, 6, callback);
                break;
            }
            case POCKET_TTS: {
                String loadErr = tryLoadPocketTts();
                if (loadErr != null) {
                    binding.btnGenerate.setEnabled(true);
                    binding.layoutProgress.setVisibility(View.GONE);
                    showToast("模型加载失败");
                    break;
                }
                engineManager.generateWithPocketTts(text, referenceAudioData,
                    referenceSampleRate, referenceText, callback);
                break;
            }
            case KOKORO: {
                String loadErr = tryLoadKokoro();
                if (loadErr != null) {
                    binding.btnGenerate.setEnabled(true);
                    binding.layoutProgress.setVisibility(View.GONE);
                    showToast("模型加载失败");
                    break;
                }
                engineManager.generateWithKokoro(text, 0, 1.0f, callback);
                break;
            }
            default:
                showToast("请先选择模型类型");
                binding.btnGenerate.setEnabled(true);
                binding.layoutProgress.setVisibility(View.GONE);
                break;
        }
    }

    /** 尝试加载 ZipVoice 模型。返回 null 成功，否则返回错误信息 */
    private String tryLoadZipVoice() {
        if (engineManager.isLoaded() &&
            engineManager.getCurrentModelType() == TtsEngineManager.ModelType.ZIPVOICE) {
            return null;
        }

        File modelDir = new File(modelManager.getModelsDir(),
            ModelManager.ZIPVOICE_ZH_EN);
        if (!modelDir.exists()) {
            String err = "⚠️ 未找到模型目录:\n" + modelDir.getAbsolutePath();
            updateStatus(err);
            return err;
        }

        String[] files = modelDir.list();
        if (files == null || files.length == 0) {
            String err = "⚠️ 模型目录为空:\n" + modelDir.getAbsolutePath();
            updateStatus(err);
            return err;
        }

        StringBuilder info = new StringBuilder("📁 找到目录，文件:\n");
        for (String f : files) info.append("  • ").append(f).append("\n");

        String[] tokens = matchFile(modelDir, files, "tokens.txt");
        String[] encoder = matchFile(modelDir, files, "encoder.int8.onnx", "encoder.onnx");
        String[] decoder = matchFile(modelDir, files, "decoder.int8.onnx", "decoder.onnx");
        String[] lexicon = matchFile(modelDir, files, "lexicon.txt");
        String[] vocoder = matchFile(modelDir, files, "vocos_24khz.onnx", "vocos.onnx", "hifigan.onnx");

        // vocoder 可能在上级目录
        if (!new File(vocoder[0]).exists()) {
            File pv = new File(modelManager.getModelsDir(), "vocos_24khz.onnx");
            if (pv.exists()) { vocoder[0] = pv.getAbsolutePath(); vocoder[1] = "✅"; }
        }

        info.append("\n🔍 文件状态:\n");
        info.append("  tokens:  ").append(tokens[1]).append(" ").append(new File(tokens[0]).getName()).append("\n");
        info.append("  encoder: ").append(encoder[1]).append(" ").append(new File(encoder[0]).getName()).append("\n");
        info.append("  decoder: ").append(decoder[1]).append(" ").append(new File(decoder[0]).getName()).append("\n");
        info.append("  vocoder: ").append(vocoder[1]).append(" ").append(new File(vocoder[0]).getName()).append("\n");
        info.append("  lexicon: ").append(lexicon[1]).append(" ").append(new File(lexicon[0]).getName()).append("\n");

        // dataDir 指向 espeak-ng-data 子目录（含 phontab/phonindex/phondata/intonations）
        // 验证要求检查 data_dir/phontab，所以 dataDir 必须是 espeak-ng-data 路径
        File espeakDir = new File(modelDir, "espeak-ng-data");
        String dataDir = espeakDir.exists() ? espeakDir.getAbsolutePath() : "";

        String loadResult = engineManager.loadZipVoice(
            tokens[0], encoder[0], decoder[0], vocoder[0],
            dataDir, lexicon[0]);
        if (loadResult == null) {
            info.append("\n✅ 加载成功！");
            updateStatus(info.toString());
            return null;
        } else {
            info.append("\n❌ ").append(loadResult);
            updateStatus(info.toString());
            return loadResult;
        }
    }

    /** 文件模糊匹配辅助方法 */
    private String[] matchFile(File dir, String[] files, String... candidates) {
        for (String name : candidates) {
            File f = new File(dir, name);
            if (f.exists()) return new String[]{f.getAbsolutePath(), "✅ "};
            for (String fn : files) {
                if (fn.equalsIgnoreCase(name))
                    return new String[]{new File(dir, fn).getAbsolutePath(), "✅ "};
            }
            // 模糊：忽略int8/fp32/fp16后缀差异
            String base = name.replace(".int8", "").replace(".fp16", "").replace(".fp32", "");
            for (String fn : files) {
                String fnBase = fn.replace(".int8", "").replace(".fp16", "").replace(".fp32", "");
                if (fnBase.equalsIgnoreCase(base))
                    return new String[]{new File(dir, fn).getAbsolutePath(), "✅ "};
            }
        }
        return new String[]{new File(dir, candidates[0]).getAbsolutePath(), "❌ "};
    }

    /** 尝试加载 PocketTTS 模型。返回 null 成功，否则返回错误信息 */
    private String tryLoadPocketTts() {
        if (engineManager.isLoaded() &&
            engineManager.getCurrentModelType() == TtsEngineManager.ModelType.POCKET_TTS) {
            return null;
        }

        File modelDir = new File(modelManager.getModelsDir(),
            ModelManager.POCKET_TTS_EN);
        if (!modelDir.exists()) {
            String err = "⚠️ PocketTTS 模型未下载";
            updateStatus(err); return err;
        }

        String dataDir = ModelManager.extractEspeakData(this);
        boolean ok = engineManager.loadPocketTts(
            new File(modelDir, "lm_flow.int8.onnx").getAbsolutePath(),
            new File(modelDir, "lm_main.int8.onnx").getAbsolutePath(),
            new File(modelDir, "encoder.onnx").getAbsolutePath(),
            new File(modelDir, "decoder.int8.onnx").getAbsolutePath(),
            new File(modelDir, "text_conditioner.onnx").getAbsolutePath(),
            new File(modelDir, "vocab.json").getAbsolutePath(),
            new File(modelDir, "token_scores.json").getAbsolutePath(),
            dataDir
        );

        updateStatus(ok ? "✅ PocketTTS 模型已加载" : "❌ PocketTTS 模型加载失败");
        return ok ? null : "PocketTTS 加载失败";
    }

    /** 尝试加载 Kokoro 模型。返回 null 成功，否则返回错误信息 */
    private String tryLoadKokoro() {
        if (engineManager.isLoaded() &&
            engineManager.getCurrentModelType() == TtsEngineManager.ModelType.KOKORO) {
            return null;
        }

        File modelDir = new File(modelManager.getModelsDir(),
            ModelManager.KOKORO_EN);
        if (!modelDir.exists()) {
            String err = "⚠️ Kokoro 模型未下载";
            updateStatus(err); return err;
        }

        boolean ok = engineManager.loadKokoro(
            new File(modelDir, "model.onnx").getAbsolutePath(),
            new File(modelDir, "voices.bin").getAbsolutePath(),
            new File(modelDir, "tokens.txt").getAbsolutePath(),
            new File(modelDir, "espeak-ng-data").getAbsolutePath(),
            ""
        );

        updateStatus(ok ? "✅ Kokoro 模型已加载" : "❌ Kokoro 模型加载失败");
        return ok ? null : "Kokoro 加载失败";
    }

    /** 检查已有模型 */
    private void checkModels() {
        StringBuilder sb = new StringBuilder("📁 已下载的模型:\n");
        File dir = modelManager.getModelsDir();

        if (dir.exists()) {
            File[] files = dir.listFiles(File::isDirectory);
            if (files != null && files.length > 0) {
                for (File f : files) {
                    sb.append("  • ").append(f.getName()).append("\n");
                }
            } else {
                sb.append("  (无)\n");
            }
        } else {
            sb.append("  (无)\n");
        }

        sb.append("\n💡 请先从 GitHub 下载模型放入:\n");
        sb.append(dir.getAbsolutePath());

        updateStatus(sb.toString());
    }

    private void updateStatus(String text) {
        binding.tvStatus.setText(text);
        Log.d(TAG, text);
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        AudioUtils.stopPlayback();
        if (AudioUtils.isRecording()) {
            AudioUtils.stopRecording();
        }
        engineManager.destroy();
    }
}
