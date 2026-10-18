package com.example.voicekill.client;

import com.example.voicekill.VoiceKillMod;
import com.example.voicekill.config.ModConfig;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * 录音设备枚举与选择。
 *
 * Shriek 内部是 `AudioSystem.getLine(Line.Info)` —— 拿的是**系统默认设备**。
 * 我们用 Mixin 把那一行重定向到这里，就能让配置里的 {@code audioDevice} 生效。
 * 找不到匹配设备时原样回退到默认，不会把麦克风搞坏。
 */
public final class AudioDevices {

    private AudioDevices() {
    }

    private static AudioFormat probeFormat() {
        // 和 Shriek 内部用的格式一致：16kHz / 16bit / 单声道
        return new AudioFormat(16000f, 16, 1, true, false);
    }

    /** 列出所有能录 16kHz 单声道的设备 */
    public static List<String> list() {
        List<String> result = new ArrayList<>();
        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, probeFormat());
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(info)) {
                    result.add(mixerInfo.getName());
                }
            }
        } catch (Throwable t) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 枚举录音设备失败：{}", t.toString());
        }
        return result;
    }

    /** 启动时把设备列表打到日志，方便用户照着填配置 */
    public static void logAvailable() {
        List<String> devices = list();
        if (devices.isEmpty()) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 没找到可用的录音设备（麦克风没插？）");
            return;
        }
        VoiceKillMod.LOGGER.info("[VoiceKill] 可用录音设备（{} 个）：", devices.size());
        for (int i = 0; i < devices.size(); i++) {
            VoiceKillMod.LOGGER.info("[VoiceKill]   {}. {}", i + 1, devices.get(i));
        }
        String configured = ModConfig.get() == null ? "" : ModConfig.get().audioDevice;
        if (configured != null && !configured.isBlank()) {
            VoiceKillMod.LOGGER.info("[VoiceKill] 配置指定的设备：{}", configured);
        }
    }

    /**
     * 切到指定设备：**只保存配置，不动正在跑的麦克风**。
     *
     * 为什么不做"热切换"（这是踩了三次坑之后的结论）：
     *
     * 1. Shriek 没有"重启麦克风"的公开 API。
     * 2. 调 `resetListener()` 之后它**不会自动重连** —— 实测切一次，麦克风就永久停掉，
     *    日志里 `listenThread` 一直是 null，再切多少次都不会起来（1.0.7 就是这么哑的）。
     * 3. 自己调 `loadVoskModel()` 重建识别器又会和还没退出的旧监听线程打架，
     *    触发 Vosk 原生断言 `ASSERTION_FAILED`，直接把 JVM 干掉（1.0.3 崩过）。
     *
     * 所以选择最笨但唯一可靠的做法：**改配置 → 让玩家重启游戏**。
     * 设备是在 Shriek 初始化麦克风那一刻读取的（我们的 Mixin 从 system property 取）。
     *
     * @return 是否保存成功
     */
    public static boolean switchTo(String device) {
        ModConfig config = ModConfig.get();
        if (config == null) {
            return false;
        }
        config.audioDevice = device == null ? "" : device;
        config.save();
        syncDeviceProperty();
        VoiceKillMod.LOGGER.info("[VoiceKill] 录音设备已保存：{}（重启游戏后生效）",
                config.audioDevice.isBlank() ? "系统默认" : config.audioDevice);
        return true;
    }

    /** 把配置里的设备名同步给 Mixin（Mixin 读 system property，不引用本项目类） */
    public static void syncDeviceProperty() {
        ModConfig config = ModConfig.get();
        String device = config == null || config.audioDevice == null ? "" : config.audioDevice;
        System.setProperty("voicekill.audioDevice", device);
        if (!device.isBlank()) {
            VoiceKillMod.LOGGER.info("[VoiceKill] 目标录音设备：{}", device);
        }
    }
}
