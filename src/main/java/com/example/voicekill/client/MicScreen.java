package com.example.voicekill.client;

import com.example.voicekill.VoiceKillMod;
import com.example.voicekill.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 游戏内选择录音设备（F8），**选完立即生效**。
 *
 * 生效方式不是去改 Shriek 的麦克风，而是由本模组接管录音
 * （自己 open 设备 → 自己读 → 用 Shriek 的识别器识别 → 用 Shriek 的通道发包）。
 * 这样线程归我们管，换设备就是关掉自己的线程再开，不存在"停不下来"的问题。
 *
 * @see MicCapture
 */
public class MicScreen extends Screen {

    public MicScreen() {
        super(Component.literal("录音设备"));
    }

    @Override
    protected void init() {
        ModConfig config = ModConfig.get();
        String current = config == null ? "" : config.audioDevice;
        List<String> devices = AudioDevices.list();

        int width = Math.min(420, this.width - 60);
        int x = (this.width - width) / 2;
        int y = 40;

        addRenderableWidget(Button.builder(
                        label("系统默认（跟随 Windows 设置）",
                                MicCapture.isRunning() && current != null && current.isBlank(),
                                current == null || current.isBlank()),
                        b -> apply(""))
                .bounds(x, y, width, 20)
                .build());
        y += 24;

        for (String device : devices) {
            final String name = device;
            boolean selected = current != null && current.equals(device);
            addRenderableWidget(Button.builder(label(name, MicCapture.isRunning() && selected, selected),
                            b -> apply(name))
                    .bounds(x, y, width, 20)
                    .build());
            y += 24;
            if (y > this.height - 46) {
                break;
            }
        }

        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(x, this.height - 30, width, 20)
                .build());
    }

    private static Component label(String text, boolean active, boolean saved) {
        String shown = text.length() > 40 ? text.substring(0, 38) + "…" : text;
        if (active) {
            return Component.literal("▶ " + shown).withStyle(s -> s.withColor(0x3FD68A));
        }
        return Component.literal((saved ? "✔ " : "   ") + shown);
    }

    private void apply(String device) {
        String shown = (device == null || device.isBlank()) ? "系统默认" : device;
        Minecraft client = Minecraft.getInstance();
        onClose();

        Thread thread = new Thread(() -> {
            boolean ok = MicCapture.start(device);
            if (ok) {
                ModConfig config = ModConfig.get();
                if (config != null) {
                    config.audioDevice = device == null ? "" : device;
                    config.save();
                }
            }
            if (client.player != null) {
                client.execute(() -> client.player.displayClientMessage(
                        Component.literal(ok
                                ? ("录音设备已切换：" + shown + "（已生效，对着麦说话试试）")
                                : ("切换失败：" + shown + "，设备可能被占用")), false));
            }
            VoiceKillMod.LOGGER.info("[VoiceKill] 设备切换结果：{} → {}", shown, ok);
        }, "VoiceKill-MicSwitch");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 别手动调 renderBackground —— 1.21.11 的 super.render() 已经做了，
        // 同一帧模糊两次会抛 "Can only blur once per frame" 把游戏崩掉。
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.literal("选完立即生效（本模组接管录音）· 失效就重启游戏恢复"),
                this.width / 2, this.height - 44, 0xFF8B98A5);
    }
}
