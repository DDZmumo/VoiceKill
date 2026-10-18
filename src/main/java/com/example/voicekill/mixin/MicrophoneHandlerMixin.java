package com.example.voicekill.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;

/**
 * 让 Shriek 用指定录音设备（配置项 audioDevice）。
 *
 * ⚠️ 这个类**只允许引用 JDK 和 slf4j**，绝不能引用本项目自己的类。
 * 原因：Mixin 是在「Shriek 的类被加载」那一刻做转换的。如果此时去加载
 * `AudioDevices` → 它引用 `VoiceKillMod`（含大量 MC 引用），很容易在转换期抛
 * NoClassDefFoundError，Fabric 会把它包成
 * 「Mixin transformation of com.pryzmm.speech.MicrophoneHandler failed」，
 * 结果整个麦克风初始化失败、语音全废（1.0.2~1.0.4 就是这么翻的）。
 *
 * 现在设备名通过 system property 传进来，本类不依赖任何项目类。
 */
@Mixin(targets = "com.pryzmm.speech.MicrophoneHandler", remap = false)
public class MicrophoneHandlerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("VoiceKill/Mic");

    /** 由 AudioDevices 在启动/切换时写入 */
    public static final String DEVICE_PROPERTY = "voicekill.audioDevice";

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Ljavax/sound/sampled/AudioSystem;getLine(Ljavax/sound/sampled/Line$Info;)Ljavax/sound/sampled/Line;"),
            require = 0,
            remap = false
    )
    private static Line voicekill$pickDevice(Line.Info info) throws LineUnavailableException {
        String wanted = System.getProperty(DEVICE_PROPERTY, "");
        if (wanted != null && !wanted.isBlank()) {
            String needle = wanted.trim().toLowerCase();
            try {
                for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                    if (!mixerInfo.getName().toLowerCase().contains(needle)) {
                        continue;
                    }
                    Mixer mixer = AudioSystem.getMixer(mixerInfo);
                    if (mixer.isLineSupported(info)) {
                        LOGGER.info("[VoiceKill] 使用录音设备：{}", mixerInfo.getName());
                        return mixer.getLine(info);
                    }
                }
                LOGGER.warn("[VoiceKill] 没找到匹配「{}」的设备，回退系统默认", wanted);
            } catch (Throwable t) {
                LOGGER.warn("[VoiceKill] 选设备时出错，回退系统默认：{}", t.toString());
            }
        }
        return AudioSystem.getLine(info);
    }
}
