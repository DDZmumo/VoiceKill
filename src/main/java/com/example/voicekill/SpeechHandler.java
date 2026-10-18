package com.example.voicekill;

import com.example.voicekill.config.ModConfig;
import com.pryzmm.api.ShriekApi;
import com.pryzmm.api.events.ServerPlayerTalkEvent;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Shriek 语音事件 → 危险词判定 → kill。
 *
 * 说明：Shriek 的语音转文字在客户端完成（Vosk），识别结果通过 C2S 数据包发到服务端，
 * 服务端再触发 ServerPlayerTalkEvent。所以单人游戏的「集成服务端」也会走到这里，
 * 不需要我们自己做网络包。
 */
public final class SpeechHandler {

    private static boolean registered = false;

    /** 去重：同玩家 800ms 内的相同文本只算一次（Shriek 原生线程 + 本模组接管线程可能都在发包） */
    private static final java.util.Map<String, String> LAST_TEXT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> LAST_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /** 乱码提示节流：每个玩家 60 秒最多提醒一次 */
    private static final java.util.Map<String, Long> LAST_MOJIBAKE_WARN = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 每个玩家「刚说完的尾巴」。
     *
     * 为什么要它：Vosk 遇到短停顿就断句，`秒切` 会被切成 `秒` + `切` 两个独立结果，
     * 分两个包发过来。单独匹配任何一个都中不了。所以把上一句的尾巴拼到这一句前面再匹配。
     */
    private static final java.util.Map<String, String> TAIL_TEXT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> TAIL_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /** 两句间隔超过这个时间就不拼了（不然会出现「隔了十秒说的两个字」被凑成词） */
    private static final long TAIL_WINDOW_MS = 5000L;

    /** 尾巴最多保留几个字 */
    private static final int TAIL_KEEP = 8;

    private SpeechHandler() {
    }

    public static void register(DangerWordManager manager) {
        if (registered) {
            return;
        }
        if (!FabricLoader.getInstance().isModLoaded("shriek")) {
            VoiceKillMod.LOGGER.error("[VoiceKill] 没装 Shriek，语音监听未启用（这个模组必须依赖它）");
            return;
        }
        try {
            ShriekApi.registerServerPlayerSpeechListener(event -> handle(manager, event));
            registered = true;
            VoiceKillMod.LOGGER.info("[VoiceKill] 已挂上 Shriek 语音监听");
        } catch (Throwable t) {
            // 用 Throwable 兜住：Shriek 内部版本不兼容时不要连累整个服务端起不来
            VoiceKillMod.LOGGER.error("[VoiceKill] 挂载 Shriek 监听失败，语音功能不可用", t);
        }
    }

    private static void handle(DangerWordManager manager, ServerPlayerTalkEvent event) {
        if (event == null) {
            return;
        }
        handleText(manager, event.getPlayer(), event.getText());
    }

    /**
     * 一段（被认为是某玩家说出的）文本进来之后的完整处理：统计 → 判定 → 处决。
     *
     * @param player 说话的人；null 表示只统计不做判定（例如网页模拟、没人跑图时）
     */
    public static void handleText(DangerWordManager manager, ServerPlayer player, String rawPacketText) {
        if (rawPacketText == null || rawPacketText.isBlank()) {
            return;
        }

        // 客户端可能把「多候选」用 SEPARATOR 拼在一个包里：
        // 第一段是最优结果（进统计、进日志），后面几段只参与匹配
        String[] parts = rawPacketText.split(com.example.voicekill.client.MicCapture.SEPARATOR);
        String text = parts[0];
        String[] alternatives = parts.length > 1
                ? java.util.Arrays.copyOfRange(parts, 1, parts.length)
                : new String[0];
        if (text.isBlank()) {
            return;
        }

        // 没 /voicekill start 之前什么都不做（不统计、不判定）
        if (!manager.isRunning()) {
            return;
        }

        // 同一玩家 800ms 内的重复文本直接丢掉；顺带检测乱码（UTF-8 被当 GBK）
        String speaker = player == null ? "（模拟）" : player.getName().getString();
        if (isDuplicate(speaker, text)) {
            return;
        }
        warnIfMojibake(speaker, text);

        // 每句话都进词频统计（不管有没有命中危险词）——这是智能选词的原料
        manager.stats().record(text);

        ModConfig config = ModConfig.get();
        if (config != null && config.logSpeech) {
            if (player == null) {
                VoiceKillMod.LOGGER.info("[VoiceKill] （模拟输入）：{}", text);
            } else {
                VoiceKillMod.LOGGER.info("[VoiceKill] 识别到 {} 说：{}",
                        player.getName().getString(), text);
            }
        }

        // 把上一句的尾巴拼进来再匹配，解决「秒切」被识别成「秒」+「切」两段的问题
        String joined = joinTail(speaker, text);
        String matched = manager.match(joined);
        if (matched != null && !joined.equals(com.example.voicekill.util.TextUtil.normalize(text))) {
            VoiceKillMod.LOGGER.info("[VoiceKill] 跨句拼接后命中：{} → 危险词「{}」", joined, matched);
        }

        // 第二、第三候选也拿去匹配（小模型听错时，正确答案常在候选里）
        if (matched == null) {
            for (String alternative : alternatives) {
                if (alternative == null || alternative.isBlank()) {
                    continue;
                }
                String candidate = manager.match(joinTail(speaker, alternative));
                if (candidate != null) {
                    matched = candidate;
                    VoiceKillMod.LOGGER.info("[VoiceKill] 第 {}/{} 候选「{}」命中危险词「{}」（最优结果是「{}」）",
                            java.util.Arrays.asList(alternatives).indexOf(alternative) + 2,
                            alternatives.length + 1, alternative, candidate, text);
                    break;
                }
            }
        }
        if (matched == null) {
            return;
        }
        if (player == null) {
            VoiceKillMod.LOGGER.info("[VoiceKill] 命中危险词「{}」，但当前没有玩家可处决（只做匹配验证）", matched);
            return;
        }

        final String hit = matched;
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            punish(manager, player, hit, text);
            return;
        }
        // Shriek 的事件可能来自网络线程；改动玩家状态必须回到服务端逻辑线程做
        if (server.isSameThread()) {
            punish(manager, player, hit, text);
        } else {
            server.execute(() -> punish(manager, player, hit, text));
        }
    }

    /**
     * 把「这句」和「上一句的尾巴」拼起来（都做归一化）。
     *
     * 只保留上一句最后 TAIL_KEEP 个字：够接上一个被切开的词，
     * 又不至于把整句话都拖进来造成误杀。
     */
    private static String joinTail(String who, String text) {
        long now = System.currentTimeMillis();
        String fresh = com.example.voicekill.util.TextUtil.normalize(text);

        String previous = TAIL_TEXT.get(who);
        Long previousAt = TAIL_AT.get(who);
        boolean continuous = previous != null && previousAt != null && now - previousAt < TAIL_WINDOW_MS;

        String joined = continuous ? previous + fresh : fresh;
        TAIL_TEXT.put(who, joined.length() > TAIL_KEEP
                ? joined.substring(joined.length() - TAIL_KEEP) : joined);
        TAIL_AT.put(who, now);
        return joined;
    }

    /** 同一玩家 800ms 内的同一句话视为重复 */
    private static boolean isDuplicate(String who, String text) {
        long now = System.currentTimeMillis();
        String previous = LAST_TEXT.get(who);
        Long previousAt = LAST_AT.get(who);
        LAST_TEXT.put(who, text);
        LAST_AT.put(who, now);
        return text.equals(previous) && previousAt != null && now - previousAt < 800L;
    }

    /**
     * 检测「UTF-8 被当 GBK 解码」造成的乱码并提醒。
     *
     * 这种乱码里已经有 U+FFFD（替换符），字节是真的丢了，服务端救不回来 ——
     * 必须让那个玩家的客户端用 UTF-8 跑：JVM 参数加 -Dfile.encoding=UTF-8。
     */
    private static void warnIfMojibake(String who, String text) {
        int broken = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\uFFFD') {
                broken++;
            }
        }
        if (broken < 3 || broken * 5 < text.length()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = LAST_MOJIBAKE_WARN.get(who);
        if (last != null && now - last < 60_000L) {
            return;
        }
        LAST_MOJIBAKE_WARN.put(who, now);
        VoiceKillMod.LOGGER.warn("[VoiceKill] {} 的语音是乱码（UTF-8 被当成 GBK 解码），"
                + "让他给 JVM 参数加 -Dfile.encoding=UTF-8 再启动游戏", who);
    }

    private static void punish(DangerWordManager manager, ServerPlayer player, String matched, String rawText) {
        if (player.isDeadOrDying()) {
            return;
        }

        VoiceKillMod.LOGGER.info("[VoiceKill] {} 说出了危险词「{}」→ 执行 kill（识别原文：{}）",
                player.getName().getString(), matched, rawText);

        announceKill(matched, player, rawText);

        // 记一笔言多必失
        manager.recordDeath(player.getName().getString());

        // 死完清掉尾巴，避免复活瞬间被上一句拼出来的词再带走一次
        String name = player.getName().getString();
        TAIL_TEXT.remove(name);
        TAIL_AT.remove(name);

        // 这就是 /kill：直接抹掉，不走命令、不发数据包。
        // 注意 1.21.11 的签名是 kill(ServerLevel)，不是无参的 kill()
        player.kill(player.level());
    }

    /** 在聊天栏告诉所有人谁踩雷了 */
    private static void announceKill(String matched, ServerPlayer victim, String rawText) {
        MinecraftServer server = victim.level().getServer();
        if (server == null) {
            return;
        }
        Component message = Component.literal("[VoiceKill] ")
                .withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)
                .append(Component.literal(victim.getName().getString())
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" 说出了危险词「")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(matched)
                        .withStyle(ChatFormatting.RED))
                .append(Component.literal("」（识别：")
                        .withStyle(ChatFormatting.GRAY))
                .append(Component.literal(rawText)
                        .withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("）")
                        .withStyle(ChatFormatting.GRAY));

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.displayClientMessage(message, false);
        }
    }
}
