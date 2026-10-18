package com.example.voicekill.client;

import com.example.voicekill.VoiceKillMod;
import com.example.voicekill.config.ModConfig;
import com.pryzmm.client.event.EventHandler;
import com.pryzmm.networking.ShriekPackets;
import com.pryzmm.networking.packets.PlayerSpeakPacket;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 自己接管录音（参考 Simple Voice Chat 的做法）。
 *
 * 为什么不用 Shriek 的麦克风管理：
 * - 它没有"换设备"的接口，`resetListener()` 停了就不会再起来；
 * - 自己 `loadVoskModel()` 重建又和老线程打架，会触发 Vosk 原生断言直接崩 JVM；
 * - 用 Mixin 改它取设备那一行，在正式环境里不稳定（时好时坏）。
 *
 * 但它留了三个公开入口，够我们自己搭一条录音管线：
 * - `SpeechRecognizer.getStringMsg(byte[])` 识别
 * - `PlayerSpeakPacket(String)` + `ShriekPackets.sendToServer(...)` 发包
 *
 * 于是：**我们 open 设备、我们读、我们识别、我们发包**，Shriek 只当"识别器 + 通道"。
 * 好处是线程完全归我们管，换设备 = 关自己的线程再开，不存在 join 不到的问题。
 */
public final class MicCapture {

    /** 和 Shriek 内部一致的音频格式 */
    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

    private static Thread thread;
    private static volatile boolean running = false;
    private static TargetDataLine line;

    private static Object recognizer;
    private static Method getStringMsg;

    /** N-best 支持：直接操作 org.vosk.Recognizer，拿回多条候选（全部走反射，不硬依赖 vosk 类） */
    private static Object voskRecognizer;
    private static Method vkAccept;
    private static Method vkResult;

    /** 多候选之间的分隔符（同一个字符串发给服务端，服务端拆开用第一段做统计、全部参与匹配） */
    public static final String SEPARATOR = "\u0001";

    /** 一次识别最多取几条候选 */
    private static final int MAX_ALTERNATIVES = 3;

    private static String currentDevice = "";
    private static String lastText = "";

    private MicCapture() {
    }

    public static boolean isRunning() {
        return running;
    }

    public static String currentDevice() {
        return currentDevice;
    }

    /**
     * 用指定设备开始录音（device 为空 = 系统默认）。
     * 会先停掉当前录音，再重新开始 —— 所以换设备也是调这个。
     */
    public static synchronized boolean start(String device) {
        stop();

        String wanted = device == null ? "" : device.trim();
        if (!ensureRecognizer()) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 拿不到语音识别器，接管录音失败");
            return false;
        }
        prepareNBest();

        try {
            DataLine.Info info = new DataLine.Info(TargetDataLine.class, FORMAT);
            TargetDataLine newLine = openLine(wanted, info);
            newLine.start();
            line = newLine;
            currentDevice = wanted;
            lastText = "";
            running = true;

            // 让 Shriek 自己那套停下来，避免两个麦克风抢设备
            stopShriekMic();

            final Object rec = recognizer;
            thread = new Thread(() -> loop(rec), "VoiceKill-MicCapture");
            thread.setDaemon(true);
            thread.start();

            VoiceKillMod.LOGGER.info("[VoiceKill] 已接管录音，设备：{}",
                    wanted.isBlank() ? "系统默认" : wanted);
            return true;
        } catch (Throwable t) {
            VoiceKillMod.LOGGER.error("[VoiceKill] 打开录音设备失败（{}）：{}",
                    wanted.isBlank() ? "系统默认" : wanted, t.toString());
            closeLine();
            running = false;
            return false;
        }
    }

    /** 停止录音并等自己的线程退出（自己开的线程，join 一定拿得到） */
    public static synchronized void stop() {
        running = false;
        closeLine();
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------

    private static void closeLine() {
        TargetDataLine current = line;
        line = null;
        if (current != null) {
            try {
                current.stop();
                current.flush();
                current.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static TargetDataLine openLine(String deviceName, DataLine.Info info) throws Exception {
        if (!deviceName.isBlank()) {
            String needle = deviceName.toLowerCase();
            for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
                if (!mixerInfo.getName().toLowerCase().contains(needle)) {
                    continue;
                }
                Mixer mixer = AudioSystem.getMixer(mixerInfo);
                if (mixer.isLineSupported(info)) {
                    TargetDataLine l = (TargetDataLine) mixer.getLine(info);
                    l.open(FORMAT);
                    VoiceKillMod.LOGGER.info("[VoiceKill] 使用录音设备：{}", mixerInfo.getName());
                    return l;
                }
            }
            VoiceKillMod.LOGGER.warn("[VoiceKill] 没找到匹配「{}」的设备，回退系统默认", deviceName);
        }
        TargetDataLine l = (TargetDataLine) AudioSystem.getLine(info);
        l.open(FORMAT);
        VoiceKillMod.LOGGER.info("[VoiceKill] 使用系统默认录音设备");
        return l;
    }

    /** 反射从 Shriek 拿识别器；拿不到就先让它加载模型 */
    private static boolean ensureRecognizer() {
        if (recognizer != null && getStringMsg != null) {
            return true;
        }
        try {
            Method m = Class.forName("com.pryzmm.speech.SpeechRecognizer")
                    .getMethod("getStringMsg", byte[].class);
            getStringMsg = m;

            recognizer = readStatic("speechRecognizer");
            if (recognizer == null) {
                // 触发 Shriek 初始化（它会读我们写好的 system property 决定设备）
                ModConfig config = ModConfig.get();
                EventHandler.loadVoskModel(config == null ? "" : config.voskModel);
                for (int i = 0; i < 100 && recognizer == null; i++) {
                    Thread.sleep(100L);
                    recognizer = readStatic("speechRecognizer");
                }
            }
            return recognizer != null;
        } catch (Throwable t) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 获取识别器失败：{}", t.toString());
            return false;
        }
    }

    /**
     * 打开「多候选」模式：让 Vosk 每次识别返回前 3 个候选。
     *
     * 为什么值得：小模型经常把「秒切」听成「秒且」，但正确答案往往还在第二候选里。
     * 把候选一起送去匹配，等于白捡一部分准确率，不用换大模型、不加延迟多少。
     *
     * 全部走反射：编译期不依赖 org.vosk，拿不到就自动退回 Shriek 的单结果接口。
     */
    private static void prepareNBest() {
        if (voskRecognizer != null) {
            return;
        }
        try {
            Field field = recognizer.getClass().getField("recognizer");
            Object vk = field.get(recognizer);
            if (vk == null) {
                VoiceKillMod.LOGGER.info("[VoiceKill] 识别器还没建好，本次用单结果模式");
                return;
            }
            Method alt = vk.getClass().getMethod("setMaxAlternatives", int.class);
            alt.invoke(vk, MAX_ALTERNATIVES);

            vkAccept = vk.getClass().getMethod("acceptWaveForm", byte[].class, int.class);
            vkResult = vk.getClass().getMethod("getResult");
            voskRecognizer = vk;
            VoiceKillMod.LOGGER.info("[VoiceKill] 已开启多候选识别（每次最多 {} 条）", MAX_ALTERNATIVES);
        } catch (Throwable t) {
            voskRecognizer = null;
            VoiceKillMod.LOGGER.info("[VoiceKill] 多候选模式不可用，退回单结果：{}", t.toString());
        }
    }

    /** 解析 Vosk 的 JSON，取所有候选文本（第一条是最优） */
    private static java.util.List<String> parseAlternatives(String json) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (root.has("alternatives") && root.get("alternatives").isJsonArray()) {
                for (com.google.gson.JsonElement element : root.getAsJsonArray("alternatives")) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    com.google.gson.JsonElement textElement = element.getAsJsonObject().get("text");
                    if (textElement == null || textElement.isJsonNull()) {
                        continue;
                    }
                    String text = textElement.getAsString().trim();
                    if (!text.isEmpty() && !out.contains(text)) {
                        out.add(text);
                    }
                    if (out.size() >= MAX_ALTERNATIVES) {
                        break;
                    }
                }
            } else if (root.has("text") && !root.get("text").isJsonNull()) {
                String text = root.get("text").getAsString().trim();
                if (!text.isEmpty()) {
                    out.add(text);
                }
            }
        } catch (Throwable t) {
            VoiceKillMod.LOGGER.warn("[VoiceKill] 解析识别结果失败：{}", t.toString());
        }
        return out;
    }

    private static Object readStatic(String name) {
        try {
            Field field = EventHandler.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 让 Shriek 自己那套麦克风彻底停下。
     *
     * 光调 resetListener() 还不够：它内部有个重试定时器（retryTimer），
     * 每 10 秒会尝试重新初始化麦克风，结果就是跟我们的线程抢设备、刷
     * `Error in listenThreadTask: line with format ... not supported`。
     * 所以顺手把那个 Timer 也取消掉。
     */
    private static void stopShriekMic() {
        try {
            EventHandler.resetListener();
        } catch (Throwable t) {
            // 麦克风本来就没起来时会 NPE，忽略
            VoiceKillMod.LOGGER.info("[VoiceKill] Shriek 原生麦克风已停（{}）", t.toString());
        }
        cancelRetryTimer();
    }

    /** 取消 Shriek 的重试定时器，避免它反复重建麦克风跟我们抢设备 */
    private static void cancelRetryTimer() {
        try {
            Field field = EventHandler.class.getDeclaredField("retryTimer");
            field.setAccessible(true);
            Object timer = field.get(null);
            if (timer instanceof java.util.Timer t) {
                t.cancel();
                field.set(null, null);
                VoiceKillMod.LOGGER.info("[VoiceKill] 已取消 Shriek 的麦克风重试定时器");
            }
        } catch (Throwable t) {
            VoiceKillMod.LOGGER.info("[VoiceKill] 取消失败（可能本来就没有）：{}", t.toString());
        }
    }

    /**
     * 送一段音频去识别，返回「最优结果 + 其它候选」（用 SEPARATOR 拼在一起）。
     * 拿不到 N-best 时退回 Shriek 的单结果接口。
     */
    private static String nbestText(byte[] chunk, Object rec) throws Exception {
        if (voskRecognizer == null) {
            String single = (String) getStringMsg.invoke(rec, (Object) chunk);
            return single == null ? null : single.trim();
        }
        boolean endpoint = (Boolean) vkAccept.invoke(voskRecognizer, chunk, chunk.length);
        if (!endpoint) {
            return null; // 还没说完，等下一段
        }
        String json = (String) vkResult.invoke(voskRecognizer);
        java.util.List<String> texts = parseAlternatives(json);
        if (texts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(texts.get(0));
        for (int i = 1; i < texts.size(); i++) {
            sb.append(SEPARATOR).append(texts.get(i));
        }
        if (texts.size() > 1) {
            VoiceKillMod.LOGGER.debug("[VoiceKill] 候选：{}", texts);
        }
        return sb.toString();
    }

    private static void loop(Object rec) {
        byte[] buffer = new byte[4096];
        while (running) {
            TargetDataLine current = line;
            if (current == null) {
                break;
            }
            int read;
            try {
                read = current.read(buffer, 0, buffer.length);
            } catch (Throwable t) {
                if (running) {
                    VoiceKillMod.LOGGER.warn("[VoiceKill] 读取音频出错：{}", t.toString());
                }
                break;
            }
            if (read <= 0) {
                continue;
            }
            byte[] chunk = Arrays.copyOf(buffer, read);
            try {
                String text = nbestText(chunk, rec);
                if (text == null || text.isBlank()) {
                    continue;
                }
                String primary = text.split(SEPARATOR)[0];
                if (primary.equals(lastText)) {
                    continue;
                }
                lastText = primary;
                ShriekPackets.sendToServer(new PlayerSpeakPacket(text));
            } catch (Throwable t) {
                VoiceKillMod.LOGGER.warn("[VoiceKill] 识别出错：{}", t.toString());
            }
        }
        VoiceKillMod.LOGGER.info("[VoiceKill] 录音线程已退出");
    }
}
