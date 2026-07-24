package com.example.voicecloner;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 模型下载管理器
 * 负责从 HuggingFace / GitHub 下载预训练模型
 */
public class ModelManager {
    private static final String TAG = "ModelManager";
    private static final String BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";

    private final Context context;
    private final File modelsDir;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private DownloadCallback downloadCallback;

    public interface DownloadCallback {
        void onProgress(String modelName, int percent);
        void onComplete(String modelName, File modelDir);
        void onError(String modelName, String error);
    }

    public ModelManager(Context context) {
        this.context = context;
        this.modelsDir = new File(context.getExternalFilesDir(null), "tts_models");
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
    }

    public File getModelsDir() {
        return modelsDir;
    }

    /** 检查模型是否已下载 */
    public boolean isModelDownloaded(String modelDirName) {
        return new File(modelsDir, modelDirName).exists();
    }

    // ================================================================
    //  可用的预训练模型列表
    // ================================================================

    /**
     * ZipVoice 中文+英文 零样本克隆模型
     * 约 100MB (int8 量化版)，速度最快
     */
    public static final String ZIPVOICE_ZH_EN = "sherpa-onnx-zipvoice-distill-int8-zh-en-emilia";

    /**
     * PocketTTS 英文 零样本克隆模型
     * 约 150MB
     */
    public static final String POCKET_TTS_EN = "sherpa-onnx-pocket-tts-int8-2026-01-26";

    /**
     * Kokoro 英文 多说话人 TTS 模型
     * 约 300MB，82M 参数，50+ 种声音
     */
    public static final String KOKORO_EN = "kokoro-en-v0_19";

    /**
     * Kokoro 中文+英文 多说话人 TTS 模型
     * 约 350MB
     */
    public static final String KOKORO_ZH_EN = "kokoro-multi-lang-v1_0";

    /** 下载模型 */
    public void downloadModel(String modelName, DownloadCallback callback) {
        this.downloadCallback = callback;
        executor.execute(() -> doDownload(modelName));
    }

    private void doDownload(String modelName) {
        try {
            final String url = BASE_URL + modelName + ".tar.bz2";
            final File targetDir = new File(modelsDir, modelName);

            if (targetDir.exists()) {
                Log.d(TAG, modelName + " already exists, skipping");
                postComplete(modelName, targetDir);
                return;
            }

            Log.d(TAG, "Downloading " + modelName + " from " + url);
            postProgress(modelName, 0);

            // 注：Android 需要自行处理 tar.bz2 解压
            // 这里简化处理：直接下载到临时文件后通知用户手动解压
            // 实际应用中建议使用 Apache Commons Compress 或类似库

            File tempFile = new File(modelsDir, modelName + ".tar.bz2");
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(60000);

            int fileLength = conn.getContentLength();
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(tempFile)) {

                byte[] buffer = new byte[8192];
                int len;
                long totalRead = 0;
                int lastPercent = 0;

                while ((len = is.read(buffer)) != -1) {
                    fos.write(buffer, 0, len);
                    totalRead += len;
                    if (fileLength > 0) {
                        int percent = (int) (totalRead * 100 / fileLength);
                        if (percent != lastPercent) {
                            postProgress(modelName, percent);
                            lastPercent = percent;
                        }
                    }
                }
            }
            conn.disconnect();

            // 通知下载完成（需要用户手动解压或后续增加解压逻辑）
            postProgress(modelName, 100);
            postComplete(modelName, tempFile);

        } catch (Exception e) {
            Log.e(TAG, "Download failed for " + modelName, e);
            postError(modelName, "下载失败: " + e.getMessage());
        }
    }

    private void postProgress(String name, int percent) {
        if (downloadCallback != null) {
            mainHandler.post(() -> downloadCallback.onProgress(name, percent));
        }
    }

    private void postComplete(String name, File file) {
        if (downloadCallback != null) {
            mainHandler.post(() -> downloadCallback.onComplete(name, file));
        }
    }

    private void postError(String name, String error) {
        if (downloadCallback != null) {
            mainHandler.post(() -> downloadCallback.onError(name, error));
        }
    }

    /** 获取模型目录下所有文件列表 */
    public static String[] listModelFiles(File modelDir) {
        if (modelDir == null || !modelDir.isDirectory()) return new String[0];
        return modelDir.list();
    }

    /**
     * 在 assets 目录部署 espeak-ng-data
     * 某些模型（Piper/VITS）需要 espeak-ng 数据做音素转换
     */
    public static String extractEspeakData(Context context) {
        File destDir = new File(context.getFilesDir(), "espeak-ng-data");
        if (destDir.exists()) return destDir.getAbsolutePath();

        destDir.mkdirs();
        // 实际应用中需要从 assets 复制 espeak-ng-data.zip 并解压
        // 这里示意性返回
        return destDir.getAbsolutePath();
    }
}
