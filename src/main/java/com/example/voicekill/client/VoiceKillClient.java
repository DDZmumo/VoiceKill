package com.example.voicekill.client;

import com.example.voicekill.VoiceKillMod;
import com.example.voicekill.config.ModConfig;
import com.example.voicekill.network.HudStatePayload;
import com.mojang.blaze3d.platform.InputConstants;
import com.pryzmm.client.event.EventHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端：四角 HUD + 模型准备 + 录音设备。
 *
 * ⚠️ 关于 Vosk 模型（踩过的坑）：{@code getOrCreatePath/loadVoskModel} 是**同步下载**，
 * 首次要拉 42MB。放进客户端 tick（= 渲染线程）会让游戏冻住几分钟。
 * 所以：后台线程下载 → 渲染线程只做最后的初始化。
 */
public class VoiceKillClient implements ClientModInitializer {

    /** 启动后等 3 秒再动手，别和原版资源加载抢 */
    private static final int START_DELAY_TICKS = 60;

    private int ticks = 0;
    private boolean started = false;
    private boolean applied = false;
    private String preparingModel = null;

    private volatile boolean pathReady = false;
    private static KeyMapping micKey;

    /** 启动后自动接管录音的计时/状态（配合配置里已保存的设备） */
    private int autoMicTicks = 0;
    private boolean autoMicDone = false;

    @Override
    public void onInitializeClient() {
        if (!FabricLoader.getInstance().isModLoaded("shriek")) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 客户端没检测到 Shriek，语音与 HUD 都不会工作");
            return;
        }

        // 1) 接收服务端 HUD 状态
        ClientPlayNetworking.registerGlobalReceiver(HudStatePayload.TYPE, (payload, context) ->
                context.client().execute(() -> HudOverlay.update(payload)));

        // 2) 注册四角常驻显示
        HudElementRegistry.addLast(
                Identifier.fromNamespaceAndPath("voicekill", "hud"),
                new HudOverlay());

        // 3) 把配置里的设备名同步给 Mixin（必须在 Shriek 初始化麦克风之前）
        AudioDevices.syncDeviceProperty();

        // 4) 列出录音设备（方便排查「怎么不是这个麦」）
        AudioDevices.logAvailable();

        // 5) 快捷键：F8 打开录音设备选择界面
        //    1.21.11 起 KeyMapping 的第四个参数是 Category 对象，不再接受字符串
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath("voicekill", "main"));
        micKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.voicekill.mic",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                category));

        // 6) 退出游戏时把我们自己的录音线程干净地关掉
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> MicCapture.stop());

        // 7) 定时器：准备语音模型
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    private void tick() {
        // 快捷键优先处理，不受模型准备流程影响
        if (micKey != null) {
            while (micKey.consumeClick()) {
                Minecraft.getInstance().setScreen(new MicScreen());
            }
        }

        ModConfig config = ModConfig.get();
        if (config == null) {
            return;
        }

        // ---- 第一步：延迟启动，把「准备模型」丢到后台线程 ----
        if (!started) {
            if (++ticks < START_DELAY_TICKS) {
                return;
            }
            started = true;

            String model = config.voskModel == null ? "" : config.voskModel.trim();
            if (model.isEmpty()) {
                return;
            }
            if (!config.autoModelDownload) {
                VoiceKillMod.LOGGER.info("[VoiceKill] autoModelDownload = false，不自动准备模型；"
                        + "Shriek 将沿用默认模型（英文）");
                return;
            }
            preparingModel = model;
            prepareInBackground(model);
            return;
        }

        // ---- 第二步：后台就绪后，在渲染线程做快速初始化 ----
        if (pathReady && !applied) {
            applied = true;
            try {
                EventHandler.loadVoskModel(preparingModel);
                VoiceKillMod.LOGGER.info("[VoiceKill] 已切换语音模型：{}", preparingModel);
            } catch (Throwable t) {
                VoiceKillMod.LOGGER.error("[VoiceKill] 语音模型初始化失败，将沿用 Shriek 默认模型", t);
            }
        }

        // ---- 第三步：上次选过的设备，这次自动接管，不用再按一次 F8 ----
        // 只处理「保存了具体设备名」的情况；存的是空（系统默认）就交给 Shriek 原生那套。
        if (applied && !autoMicDone) {
            if (++autoMicTicks < 40) {
                return;
            }
            autoMicDone = true;
            String saved = config.audioDevice == null ? "" : config.audioDevice.trim();
            if (saved.isEmpty() || MicCapture.isRunning()) {
                return;
            }
            VoiceKillMod.LOGGER.info("[VoiceKill] 检测到上次保存的录音设备「{}」，启动时自动接管", saved);
            Thread thread = new Thread(() -> {
                boolean ok = MicCapture.start(saved);
                VoiceKillMod.LOGGER.info("[VoiceKill] 启动自动接管录音结果：{}（{}）", ok, saved);
                if (!ok) {
                    VoiceKillMod.LOGGER.warn("[VoiceKill] 自动接管失败，本次沿用 Shriek 原生麦克风；"
                            + "可以按 F8 重新选一次");
                }
            }, "VoiceKill-MicAutoStart");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private void prepareInBackground(String model) {
        Thread thread = new Thread(() -> {
            try {
                VoiceKillMod.LOGGER.info(
                        "[VoiceKill] 开始后台准备语音模型 {}（首次需下载约 40MB，游戏不会卡住）", model);
                String path = EventHandler.getOrCreatePath(model);
                VoiceKillMod.LOGGER.info("[VoiceKill] 模型文件就绪：{}", path);
                pathReady = true;
            } catch (Throwable t) {
                // 失败就放弃，别反复重试把日志刷爆
                VoiceKillMod.LOGGER.error("[VoiceKill] 模型下载/解压失败，将沿用 Shriek 默认模型：{}",
                        t.toString());
            }
        }, "VoiceKill-ModelPrepare");
        thread.setDaemon(true);
        thread.start();
    }
}
