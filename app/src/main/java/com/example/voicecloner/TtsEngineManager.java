package com.example.voicecloner;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsCallback;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig;

/**
 * 核心 TTS 引擎管理器
 * 统一管理多种 TTS 模型：ZipVoice、PocketTTS、Kokoro、VITS
 */
public class TtsEngineManager {
    private static final String TAG = "TtsEngineManager";

    private static volatile TtsEngineManager instance;

    private OfflineTts currentTts;
    private ModelType currentModelType = ModelType.NONE;
    private Context appContext;

    private ProgressCallback progressCallback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public enum ModelType {
        NONE, ZIPVOICE, POCKET_TTS, KOKORO, VITS
    }

    public interface ProgressCallback {
        void onProgress(float progress);
        void onComplete(float[] samples, int sampleRate);
        void onError(String message);
    }

    private TtsEngineManager() {}

    public static TtsEngineManager getInstance() {
        if (instance == null) {
            synchronized (TtsEngineManager.class) {
                if (instance == null) {
                    instance = new TtsEngineManager();
                }
            }
        }
        return instance;
    }

    public void init(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 
     * 修复文件权限：将模型文件复制到 App 私有缓存目录
     * 
     * native (ONNX Runtime) 使用 mmap 读取文件，
     * 对外部存储上的文件可能有权限限制（EACCES error 13），
     * 复制到缓存目录可确保 native 层能正常读取。
     */
    private String ensureReadable(String path) {
        if (path == null || path.isEmpty()) return path;
        java.io.File f = new java.io.File(path);
        if (!f.exists()) return path;
        
        // 如果文件已经在缓存目录中，直接使用
        String cacheDirPath = appContext.getCacheDir().getAbsolutePath();
        if (path.startsWith(cacheDirPath)) return path;
        
        // 复制到缓存目录（使用子目录保持结构清晰）
        java.io.File cacheSubDir = new java.io.File(appContext.getCacheDir(), "tts_models");
        cacheSubDir.mkdirs();
        java.io.File cacheFile = new java.io.File(cacheSubDir, f.getName());
        
        // 如果缓存中已有同名文件且大小一致，跳过复制
        if (cacheFile.exists() && cacheFile.length() == f.length()) {
            Log.d(TAG, "Cache hit: " + cacheFile.getAbsolutePath());
            return cacheFile.getAbsolutePath();
        }
        
        Log.d(TAG, "Copying to cache: " + f.getName() + " (" + (f.length() / 1024) + "KB)");
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.FileOutputStream out = new java.io.FileOutputStream(cacheFile);
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            in.close();
            out.close();
            cacheFile.setReadable(true, false);
            Log.d(TAG, "Copied to cache: " + cacheFile.getAbsolutePath());
            return cacheFile.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy file '" + f.getName() + "' to cache", e);
            return path; // 返回原路径，让 native 层报错
        }
    }

    // ================================================================
    //  1. ZipVoice
    // ================================================================
    /** 返回 null 表示成功，否则返回错误信息 */
    public String loadZipVoice(String tokensPath, String encoderPath,
                               String decoderPath, String vocoderPath,
                               String dataDir, String lexiconPath) {
        release();
        Log.d(TAG, "Loading ZipVoice model...");
        
        // 预检查文件存在性
        StringBuilder missing = new StringBuilder();
        if (!new java.io.File(tokensPath).exists()) missing.append("\n  ❌ tokens: ").append(tokensPath);
        if (!new java.io.File(encoderPath).exists()) missing.append("\n  ❌ encoder: ").append(encoderPath);
        if (!new java.io.File(decoderPath).exists()) missing.append("\n  ❌ decoder: ").append(decoderPath);
        if (!new java.io.File(vocoderPath).exists()) missing.append("\n  ❌ vocoder: ").append(vocoderPath);
        if (missing.length() > 0) {
            String err = "缺少模型文件:" + missing.toString();
            Log.e(TAG, err);
            currentModelType = ModelType.NONE;
            return err;
        }
        
        // 修复权限问题：将所有模型文件复制到 App 缓存目录
        // （native ONNX Runtime 使用 mmap，对外部存储文件可能 EACCES）
        String safeVocoder = ensureReadable(vocoderPath);
        String safeTokens = ensureReadable(tokensPath);
        String safeEncoder = ensureReadable(encoderPath);
        String safeDecoder = ensureReadable(decoderPath);
        String safeLexicon = (lexiconPath != null && new java.io.File(lexiconPath).exists())
            ? ensureReadable(lexiconPath) : "";

        try {
            OfflineTtsZipVoiceModelConfig zv = OfflineTtsZipVoiceModelConfig.builder()
                .setTokens(safeTokens).setEncoder(safeEncoder)
                .setDecoder(safeDecoder).setVocoder(safeVocoder)
                .setDataDir(dataDir != null ? dataDir : "")
                .setLexicon(safeLexicon).build();

            OfflineTtsModelConfig mc = OfflineTtsModelConfig.builder()
                .setZipvoice(zv).setNumThreads(getOptimalThreadCount())
                .setDebug(false).setProvider("cpu").build();

            OfflineTtsConfig c = OfflineTtsConfig.builder().setModel(mc).setMaxNumSentences(2).build();
            currentTts = new OfflineTts(c);
            currentModelType = ModelType.ZIPVOICE;
            Log.d(TAG, "ZipVoice loaded. SR: " + currentTts.getSampleRate());
            return null; // 成功
        } catch (Exception e) {
            String err = "模型加载异常: " + e.getMessage();
            Log.e(TAG, err, e);
            currentTts = null;
            currentModelType = ModelType.NONE;
            return err;
        }
    }

    public void generateWithZipVoice(String text, float[] refAudio,
                                     int refSampleRate, String refText,
                                     int numSteps, ProgressCallback cb) {
        this.progressCallback = cb;
        if (currentTts == null || currentModelType != ModelType.ZIPVOICE) {
            postError("ZipVoice not loaded"); return;
        }
        // 验证参考音频
        if (refAudio == null || refAudio.length < 100) {
            postError("参考音频无效（太短或为空）"); return;
        }
        // 检查有无NaN/Infinity
        for (int i = 0; i < refAudio.length; i++) {
            if (!Float.isFinite(refAudio[i])) {
                refAudio[i] = 0f; // 修复无效值
            }
        }
        final float[] safeAudio = refAudio;
        final int safeSampleRate = (refSampleRate > 0) ? refSampleRate : 16000;

        // Log generation params for debugging
        Log.d(TAG, String.format("ZipVoice gen: text='%s', refAudioLen=%d, refSR=%d, numSteps=%d",
            text.length() > 50 ? text.substring(0, 50) + "..." : text,
            safeAudio.length, safeSampleRate, numSteps));

        new Thread(() -> {
            try {
                GenerationConfig gc = new GenerationConfig();
                gc.setSpeed(1.0f);
                gc.setNumSteps(numSteps > 0 ? numSteps : 6);
                gc.setReferenceAudio(safeAudio);
                gc.setReferenceSampleRate(safeSampleRate);
                if (refText != null && !refText.isEmpty()) gc.setReferenceText(refText);

                // 尝试捕获任何 JNI 调用的异常
                GeneratedAudio audio = currentTts.generateWithConfigAndCallback(
                    text, gc, (float[] chunk) -> { return 1; });

                if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0 && audio.getSampleRate() > 0) {
                    Log.d(TAG, "ZipVoice gen success: " + audio.getSamples().length + " samples @ " + audio.getSampleRate() + "Hz");
                    postComplete(audio.getSamples(), audio.getSampleRate());
                } else {
                    String reason = (audio == null) ? "audio is null" :
                        (audio.getSamples() == null) ? "samples is null" :
                        (audio.getSamples().length == 0) ? "samples is empty" :
                        "sampleRate=" + audio.getSampleRate();
                    Log.e(TAG, "生成音频无效: " + reason);
                    postError("生成音频为空或无效 (" + reason + ")");
                }
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "ZipVoice native lib not found", e);
                postError("Native库加载失败: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "ZipVoice gen failed", e);
                postError("生成失败: " + e.getMessage());
            }
        }).start();
    }

    // ================================================================
    //  2. PocketTTS
    // ================================================================
    public boolean loadPocketTts(String lmFlow, String lmMain, String enc,
                                 String dec, String textCond, String vocabJson,
                                 String tokenScores, String dataDir) {
        release();
        Log.d(TAG, "Loading PocketTTS...");
        try {
            OfflineTtsPocketModelConfig p = OfflineTtsPocketModelConfig.builder()
                .setLmFlow(lmFlow).setLmMain(lmMain).setEncoder(enc)
                .setDecoder(dec).setTextConditioner(textCond)
                .setVocabJson(vocabJson).setTokenScoresJson(tokenScores).build();

            OfflineTtsModelConfig mc = OfflineTtsModelConfig.builder()
                .setPocket(p).setNumThreads(getOptimalThreadCount())
                .setDebug(false).setProvider("cpu").build();

            currentTts = new OfflineTts(OfflineTtsConfig.builder().setModel(mc).setMaxNumSentences(2).build());
            currentModelType = ModelType.POCKET_TTS;
            Log.d(TAG, "PocketTTS loaded. SR: " + currentTts.getSampleRate());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed PocketTTS", e);
            currentModelType = ModelType.NONE;
            return false;
        }
    }

    public void generateWithPocketTts(String text, float[] refAudio,
                                      int refSampleRate, String refText,
                                      ProgressCallback cb) {
        this.progressCallback = cb;
        if (currentTts == null || currentModelType != ModelType.POCKET_TTS) {
            postError("PocketTTS not loaded"); return;
        }
        // 验证参考音频
        if (refAudio == null || refAudio.length < 100) {
            postError("参考音频无效（太短或为空）"); return;
        }
        // 检查有无NaN/Infinity
        for (int i = 0; i < refAudio.length; i++) {
            if (!Float.isFinite(refAudio[i])) {
                refAudio[i] = 0f;
            }
        }
        final float[] safeAudio = refAudio;
        final int safeSampleRate = (refSampleRate > 0) ? refSampleRate : 16000;
        final String safeRefText = (refText != null) ? refText : "";

        new Thread(() -> {
            try {
                GenerationConfig gc = new GenerationConfig();
                gc.setSpeed(1.0f);
                gc.setReferenceAudio(safeAudio);
                gc.setReferenceSampleRate(safeSampleRate);
                if (!safeRefText.isEmpty()) gc.setReferenceText(safeRefText);

                GeneratedAudio audio = currentTts.generateWithConfigAndCallback(
                    text, gc, (float[] chunk) -> { return 1; });

                if (audio != null && audio.getSamples() != null && audio.getSamples().length > 0 && audio.getSampleRate() > 0)
                    postComplete(audio.getSamples(), audio.getSampleRate());
                else postError("生成音频为空");
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "PocketTTS native lib not found", e);
                postError("Native库加载失败: " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "PocketTTS gen failed", e);
                postError("生成失败: " + e.getMessage());
            }
        }).start();
    }

    // ================================================================
    //  3. Kokoro
    // ================================================================
    public boolean loadKokoro(String modelPath, String voicesPath,
                              String tokensPath, String dataDir, String lexicon) {
        release();
        Log.d(TAG, "Loading Kokoro...");
        try {
            OfflineTtsKokoroModelConfig k = OfflineTtsKokoroModelConfig.builder()
                .setModel(modelPath).setVoices(voicesPath).setTokens(tokensPath)
                .setDataDir(dataDir != null ? dataDir : "")
                .setLexicon(lexicon != null ? lexicon : "").setLengthScale(1.0f).build();

            OfflineTtsModelConfig mc = OfflineTtsModelConfig.builder()
                .setKokoro(k).setNumThreads(getOptimalThreadCount())
                .setDebug(false).setProvider("cpu").build();

            currentTts = new OfflineTts(OfflineTtsConfig.builder().setModel(mc).setMaxNumSentences(3).build());
            currentModelType = ModelType.KOKORO;
            Log.d(TAG, "Kokoro loaded. SR: " + currentTts.getSampleRate() + " SPK: " + currentTts.getNumSpeakers());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed Kokoro", e);
            currentModelType = ModelType.NONE;
            return false;
        }
    }

    public void generateWithKokoro(String text, int speakerId, float speed, ProgressCallback cb) {
        this.progressCallback = cb;
        if (currentTts == null || currentModelType != ModelType.KOKORO) {
            postError("Kokoro not loaded"); return;
        }
        new Thread(() -> {
            try {
                GeneratedAudio a = currentTts.generate(text, speakerId, speed);
                if (a != null && a.getSamples() != null)
                    postComplete(a.getSamples(), a.getSampleRate());
                else postError("生成音频为空");
            } catch (Exception e) {
                Log.e(TAG, "Kokoro gen failed", e);
                postError("生成失败: " + e.getMessage());
            }
        }).start();
    }

    // ================================================================
    //  4. VITS
    // ================================================================
    public boolean loadVits(String modelPath, String tokensPath, String dataDir, String lexicon) {
        release();
        try {
            OfflineTtsVitsModelConfig v = OfflineTtsVitsModelConfig.builder()
                .setModel(modelPath).setTokens(tokensPath)
                .setDataDir(dataDir != null ? dataDir : "")
                .setLexicon(lexicon != null ? lexicon : "")
                .setNoiseScale(0.667f).setNoiseScaleW(0.8f).setLengthScale(1.0f).build();

            OfflineTtsModelConfig mc = OfflineTtsModelConfig.builder().setVits(v)
                .setNumThreads(getOptimalThreadCount()).setDebug(false).setProvider("cpu").build();

            currentTts = new OfflineTts(OfflineTtsConfig.builder().setModel(mc).setMaxNumSentences(3).build());
            currentModelType = ModelType.VITS;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed VITS", e);
            currentModelType = ModelType.NONE;
            return false;
        }
    }

    public void generateWithVits(String text, int speakerId, float speed, ProgressCallback cb) {
        this.progressCallback = cb;
        if (currentTts == null || currentModelType != ModelType.VITS) {
            postError("VITS not loaded"); return;
        }
        new Thread(() -> {
            try {
                GeneratedAudio a = currentTts.generate(text, speakerId, speed);
                if (a != null && a.getSamples() != null)
                    postComplete(a.getSamples(), a.getSampleRate());
                else postError("生成音频为空");
            } catch (Exception e) {
                Log.e(TAG, "VITS gen failed", e); postError("生成失败: " + e.getMessage());
            }
        }).start();
    }

    // ================================================================
    //  Utilities
    // ================================================================
    public int getSampleRate() { return currentTts != null ? currentTts.getSampleRate() : 0; }
    public ModelType getCurrentModelType() { return currentModelType; }
    public boolean isLoaded() { return currentTts != null && currentModelType != ModelType.NONE; }

    public void release() {
        if (currentTts != null) { try { currentTts.release(); } catch (Exception ignored) {} currentTts = null; }
        currentModelType = ModelType.NONE;
    }

    public void destroy() { release(); appContext = null; }

    private int getOptimalThreadCount() {
        int c = Runtime.getRuntime().availableProcessors();
        return c >= 8 ? 4 : c >= 6 ? 3 : c >= 4 ? 2 : 1;
    }

    private void postProgress(float p) { if (progressCallback != null) mainHandler.post(() -> progressCallback.onProgress(p)); }
    private void postComplete(float[] s, int sr) { if (progressCallback != null) mainHandler.post(() -> progressCallback.onComplete(s, sr)); }
    private void postError(String m) { if (progressCallback != null) mainHandler.post(() -> progressCallback.onError(m)); }

    public static boolean saveAsWav(float[] s, int sr, String path) {
        try {
            java.io.DataOutputStream d = new java.io.DataOutputStream(new java.io.FileOutputStream(path));
            int ds = s.length * 2, ch = 1, bps = 16;
            w(d, "RIFF"); d.writeInt(Integer.reverseBytes(36 + ds));
            w(d, "WAVE"); w(d, "fmt "); d.writeInt(Integer.reverseBytes(16));
            d.writeShort(Short.reverseBytes((short) 1));
            d.writeShort(Short.reverseBytes((short) ch));
            d.writeInt(Integer.reverseBytes(sr));
            d.writeInt(Integer.reverseBytes(sr * ch * bps / 8));
            d.writeShort(Short.reverseBytes((short) (ch * bps / 8)));
            d.writeShort(Short.reverseBytes((short) bps));
            w(d, "data"); d.writeInt(Integer.reverseBytes(ds));
            for (float v : s) d.writeShort(Short.reverseBytes((short) (Math.max(-1f, Math.min(1f, v)) * 32767)));
            d.close(); return true;
        } catch (Exception e) { return false; }
    }
    private static void w(java.io.DataOutputStream d, String s) throws Exception { for (int i = 0; i < s.length(); i++) d.writeByte(s.charAt(i)); }
}
