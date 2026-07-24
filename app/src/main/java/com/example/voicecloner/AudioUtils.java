package com.example.voicecloner;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;

/**
 * 音频工具：播放/录音/WAV读写（float[] PCM）
 */
public class AudioUtils {
    private static final String TAG = "AudioUtils";
    private static AudioTrack audioTrack;
    private static MediaRecorder mediaRecorder;
    private static String currentRecordingPath;

    /** 播放 float[] PCM */
    public static void playPcm(float[] samples, int sampleRate) {
        stopPlayback();
        // float[] 转 short[] 用于 AudioTrack
        short[] buf = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float s = Math.max(-1f, Math.min(1f, samples[i]));
            buf[i] = (short) (s * 32767);
        }
        int bufSize = Math.max(
            AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
            buf.length * 2);

        audioTrack = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufSize).setTransferMode(AudioTrack.MODE_STATIC).build();

        audioTrack.write(buf, 0, buf.length);
        audioTrack.play();
    }

    public static void stopPlayback() {
        if (audioTrack != null) {
            try { audioTrack.stop(); audioTrack.release(); } catch (Exception e) { Log.w(TAG, "stop err", e); }
            audioTrack = null;
        }
    }

    public static boolean startRecording(String outputPath) {
        stopRecording();
        try {
            currentRecordingPath = outputPath;
            mediaRecorder = new MediaRecorder();
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mediaRecorder.setAudioSamplingRate(16000);
            mediaRecorder.setOutputFile(outputPath);
            mediaRecorder.prepare();
            mediaRecorder.start();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            return false;
        }
    }

    public static String stopRecording() {
        if (mediaRecorder != null) {
            try { mediaRecorder.stop(); mediaRecorder.release(); } catch (Exception e) { Log.w(TAG, "stopRecording err", e); }
            mediaRecorder = null;
        }
        return currentRecordingPath;
    }

    public static boolean isRecording() { return mediaRecorder != null; }

    /** 读取 WAV 为 float[]（支持扩展头、16bit/32bit float、多通道自动转单声道） */
    public static float[] readWavAsFloats(String path) {
        try (java.io.DataInputStream dis = new java.io.DataInputStream(new FileInputStream(path))) {
            // RIFF 头
            byte[] riff = new byte[4];
            dis.readFully(riff);
            if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F') return null;
            dis.readInt(); // file size
            byte[] wave = new byte[4];
            dis.readFully(wave);
            if (wave[0] != 'W' || wave[1] != 'A' || wave[2] != 'V' || wave[3] != 'E') return null;

            int channels = 1;
            int sampleRate = 16000;
            int bitsPerSample = 16;
            byte[] dataBytes = null;

            // 遍历 chunks 找 "fmt " 和 "data"
            while (true) {
                byte[] chunkId = new byte[4];
                int read = dis.read(chunkId);
                if (read < 4) break;
                int chunkSize = Integer.reverseBytes(dis.readInt());

                if (chunkId[0] == 'f' && chunkId[1] == 'm' && chunkId[2] == 't' && chunkId[3] == ' ') {
                    if (chunkSize >= 16) {
                        /* short audioFormat = */ dis.readShort();
                        channels = Short.reverseBytes(dis.readShort());
                        sampleRate = Integer.reverseBytes(dis.readInt());
                        dis.readInt(); // byte rate
                        dis.readShort(); // block align
                        bitsPerSample = Short.reverseBytes(dis.readShort());
                        dis.skipBytes(chunkSize - 16);
                    } else {
                        dis.skipBytes(chunkSize);
                    }
                } else if (chunkId[0] == 'd' && chunkId[1] == 'a' && chunkId[2] == 't' && chunkId[3] == 'a') {
                    dataBytes = new byte[chunkSize];
                    dis.readFully(dataBytes);
                    break;
                } else {
                    dis.skipBytes(chunkSize);
                }
            }

            if (dataBytes == null || dataBytes.length == 0) return null;

            int frameBytes = (bitsPerSample / 8) * channels;
            if (frameBytes == 0) return null;
            int sampleCount = dataBytes.length / frameBytes;

            float[] out = new float[sampleCount];
            int idx = 0;
            for (int i = 0; i + frameBytes <= dataBytes.length && idx < out.length; i += frameBytes) {
                float val = 0;
                if (bitsPerSample == 16) {
                    short s = (short) ((dataBytes[i + 1] << 8) | (dataBytes[i] & 0xFF));
                    val = s / 32768.0f;
                } else if (bitsPerSample == 32) {
                    int intVal = (dataBytes[i + 3] << 24) | ((dataBytes[i + 2] & 0xFF) << 16) |
                                 ((dataBytes[i + 1] & 0xFF) << 8) | (dataBytes[i] & 0xFF);
                    val = intVal / 2147483648.0f;
                } else if (bitsPerSample == 8) {
                    val = (dataBytes[i] - 128) / 128.0f;
                }
                out[idx++] = Math.max(-1.0f, Math.min(1.0f, val));
            }

            // 裁剪掉末尾静音
            int lastNonZero = out.length - 1;
            while (lastNonZero > 0 && Math.abs(out[lastNonZero]) < 0.001f) lastNonZero--;
            if (lastNonZero < out.length / 2) {
                Log.w(TAG, "WAV mostly silent, may be corrupted");
            }

            Log.d(TAG, String.format("WAV: %d samples, %dHz, %d-bit, %dch -> %d floats",
                dataBytes.length, sampleRate, bitsPerSample, channels, out.length));
            return out;
        } catch (Exception e) {
            Log.e(TAG, "readWav err: " + e.getMessage(), e);
            return null;
        }
    }

    /**
     * 将 float[] PCM 音频重采样到 16000Hz（线性插值）
     * 如果已经是 16000Hz 则返回原数组
     * @param samples 输入音频 float[] (范围 [-1, 1])
     * @param inputSampleRate 输入采样率
     * @return 重采样后的 float[] (16000Hz)
     */
    public static float[] resampleTo16000Hz(float[] samples, int inputSampleRate) {
        if (samples == null || samples.length == 0 || inputSampleRate <= 0) {
            return samples;
        }
        if (inputSampleRate == 16000) {
            return samples;
        }

        int outputLength = (int) ((long) samples.length * 16000 / inputSampleRate);
        if (outputLength <= 0) return new float[0];

        float[] output = new float[outputLength];
        double ratio = (double) samples.length / outputLength;

        for (int i = 0; i < outputLength; i++) {
            double srcPos = i * ratio;
            int srcIndex = (int) srcPos;
            double frac = srcPos - srcIndex;

            if (srcIndex >= samples.length - 1) {
                output[i] = samples[samples.length - 1];
            } else {
                // 线性插值
                float s0 = Float.isFinite(samples[srcIndex]) ? samples[srcIndex] : 0f;
                float s1 = Float.isFinite(samples[srcIndex + 1]) ? samples[srcIndex + 1] : 0f;
                output[i] = (float) (s0 + (s1 - s0) * frac);
            }
        }
        return output;
    }

    /**
     * 裁剪音频到最大持续时间（秒）
     */
    public static float[] clampDuration(float[] samples, int sampleRate, float maxSeconds) {
        if (samples == null || sampleRate <= 0) return samples;
        int maxSamples = (int) (sampleRate * maxSeconds);
        if (samples.length <= maxSamples) return samples;
        float[] trimmed = new float[maxSamples];
        System.arraycopy(samples, 0, trimmed, 0, maxSamples);
        return trimmed;
    }

    public static int getWavSampleRate(String path) {
        try (java.io.DataInputStream dis = new java.io.DataInputStream(new FileInputStream(path))) {
            byte[] riff = new byte[4];
            dis.readFully(riff);
            if (riff[0] != 'R' || riff[1] != 'I' || riff[2] != 'F' || riff[3] != 'F') return 16000;
            dis.readInt();
            dis.readInt(); // WAVE
            while (true) {
                byte[] cid = new byte[4];
                if (dis.read(cid) < 4) break;
                int sz = Integer.reverseBytes(dis.readInt());
                if (cid[0] == 'f' && cid[1] == 'm' && cid[2] == 't' && cid[3] == ' ') {
                    dis.readShort(); // format
                    dis.readShort(); // channels
                    int sr = Integer.reverseBytes(dis.readInt());
                    return sr;
                }
                dis.skip(sz);
            }
        } catch (Exception ignored) {}
        return 16000;
    }
}
