package com.example.voicekill.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.File;
import java.util.List;
import java.util.Set;

/**
 * 决定要不要应用本模组的 Mixin。
 *
 * 存在这个类的原因：1.0.2~1.0.4 的 Mixin 在正式环境里会让 Shriek 的
 * `MicrophoneHandler` 类加载失败，直接导致**语音识别全废**。
 * 那次没法在开发环境复现（dev 一切正常），所以现在留一个「逃生门」：
 *
 * 只要游戏目录下存在 `config/voicekill-mic-mixin.off` 文件，就不应用 Mixin，
 * Shriek 回到原样、语音恢复正常。网页上有一键开关。
 *
 * ⚠️ 本类在 mixin 加载早期运行，**不要引用任何项目类**（配置、MC 类都不行）。
 */
public class VoiceKillMixinPlugin implements IMixinConfigPlugin {

    private static final String OFF_FLAG = "config/voicekill-mic-mixin.off";

    private boolean enabled = true;

    @Override
    public void onLoad(String mixinPackage) {
        try {
            enabled = !new File(OFF_FLAG).isFile();
        } catch (Throwable ignored) {
            enabled = true;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return enabled;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {
    }
}
