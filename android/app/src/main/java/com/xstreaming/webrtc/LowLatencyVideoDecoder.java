package com.xstreaming.webrtc;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.xstreaming.LowLatencyDecoderConfig;

import java.io.IOException;
import java.nio.ByteBuffer;

public class LowLatencyVideoDecoder {
    private static final String TAG = "LowLatencyVideoDecoder";
    private MediaCodec codec;
    private boolean isRunning = false;

    /**
     * Aplica otimizações agressivas de tempo real no MediaFormat para Google TV (TCL 55P755)
     */
    public static void applyLowLatencyFlags(MediaFormat format) {
        if (format == null) return;

        try {
            // 1. Flag oficial do Android 11+ (API 30+) - Suportada nativamente na TCL 55P755 (Android 12)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
            }

            // 2. Prioridade máxima de Tempo Real (0 = Realtime) e Taxa de Operação Máxima
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                format.setInteger(MediaFormat.KEY_PRIORITY, 0);
                format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE);
            }

            // 3. Flags específicas de fabricantes de SoCs de Smart TV (MediaTek, Realtek, Qualcomm, Amlogic)
            // As chaves não reconhecidas pelo driver atual são simplesmente ignoradas pelo MediaCodec sem erro.
            format.setInteger("vendor.mtk.ext.dec.low_latency.enable", 1);
            format.setInteger("vendor.rtk.low_latency", 1);
            format.setInteger("vendor.qti-ext-dec-low-latency.enable", 1);
            format.setInteger("vendor.rtc-ext-dec-low-latency.enable", 1);
            format.setInteger("vendor.hisi-ext-low-latency-video-dec.video-scene-for-low-latency-req", 1);

            // 4. Reduzir fila de buffers internos ao mínimo para eliminar input lag visual
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
            }

            Log.i(TAG, "Flags de Ultra-Baixa Latência aplicadas com sucesso para o MediaCodec.");
        } catch (Exception e) {
            Log.w(TAG, "Aviso ao aplicar flags de baixa latência: " + e.getMessage());
        }
    }

    /**
     * Inicializa o MediaCodec garantindo o caminho de Hardware (Zero-Copy) direto para a Surface
     */
    public boolean initDecode(String mimeType, int width, int height, Surface surface) {
        try {
            codec = MediaCodec.createDecoderByType(mimeType);
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);

            if (LowLatencyDecoderConfig.isEnabled()) {
                applyLowLatencyFlags(format);
            }

            // Configura o codec diretamente na Surface da TV (ativa o chip de upscaling TCL AiPQ)
            codec.configure(format, surface, null, 0);
            codec.start();
            isRunning = true;
            return true;
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Falha ao inicializar LowLatencyVideoDecoder: " + e.getMessage());
            release();
            return false;
        }
    }

    /**
     * Processa um pacote de vídeo H.264/HEVC recebido e renderiza imediatamente
     */
    public boolean decodeFrame(ByteBuffer encodedData, int size, long presentationTimeUs) {
        if (!isRunning || codec == null) return false;

        try {
            // Timeout curto (2ms) para não bloquear a thread de rede WebRTC/Nano
            int inputBufferIndex = codec.dequeueInputBuffer(2000);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = codec.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    inputBuffer.put(encodedData);
                    codec.queueInputBuffer(inputBufferIndex, 0, size, presentationTimeUs, 0);
                }
            }

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0);

            while (outputBufferIndex >= 0) {
                // Renderiza imediatamente na Surface da TV sem esperar pelo timestamp de sincronia (render = true)
                codec.releaseOutputBuffer(outputBufferIndex, true);
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0);
            }
            return true;
        } catch (IllegalStateException e) {
            Log.e(TAG, "Erro durante decodeFrame: " + e.getMessage());
            return false;
        }
    }

    public void release() {
        isRunning = false;
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {}
            try {
                codec.release();
            } catch (Exception ignored) {}
            codec = null;
        }
    }
}
