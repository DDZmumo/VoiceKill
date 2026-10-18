package com.example.voicekill.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 极简 JSON 配置。文件位置：.minecraft/config/voicekill.json
 * 字段名就是 JSON 里的键名，改文件即可生效（下次启动 / 下次进服）。
 */
public final class ModConfig {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String FILE_NAME = "voicekill.json";

    /** 自定义危险词（网页上「自定义」分类） */
    public List<String> dangerWords = new ArrayList<>(List.of(
            "苦力怕", "钻石", "岩浆", "末影人", "不死图腾"
    ));

    /** 人名类危险词（网页上「人名」分类，每轮固定塞 namesPerRound 个） */
    public List<String> nameWords = new ArrayList<>();

    /** 每轮固定塞几个「人名」类危险词 */
    public int namesPerRound = 1;

    /** 额外的高频词（追加到内置高频词表后面） */
    public List<String> highFreqExtra = new ArrayList<>();

    /** 从内置高频词表里禁用的词（网页上点 × 会加到这里） */
    public List<String> highFreqDisabled = new ArrayList<>();

    /**
     * 谐音别名：key 是原词，value 是它可能被识别成的变体。
     * 比如 "ad" -> ["哎地","哎弟","阿德"]，说到任一变体都算命中。
     * 英文缩写会**自动**生成一批变体，这里配的是手动补充。
     */
    public Map<String, List<String>> aliases = new LinkedHashMap<>();

    /** 多少秒换一个危险词 */
    public int intervalSeconds = 30;

    /** 换词时是否用 actionbar 提示所有玩家 */
    public boolean enableActionbarHint = true;

    /** 玩家进服时是否把当前危险词推给他 */
    public boolean hintOnJoin = true;

    /** 把每句识别结果打到日志（排查「为什么没触发」时非常有用） */
    public boolean logSpeech = true;

    /**
     * Shriek 使用的 Vosk 模型名（见 https://alphacephei.com/vosk/models）。
     * 默认英文模型只认英文，说中文必须换成中文模型。
     */
    public String voskModel = "vosk-model-small-cn-0.22";

    /**
     * 是否让本模组自动准备语音模型（首次约 40MB，走后台线程，不卡游戏）。
     * 关掉的话 Shriek 会用它自己的默认模型（英文）。
     */
    public boolean autoModelDownload = true;

    /** 客户端是否显示屏幕四角的常驻信息 */
    public boolean hudEnabled = true;

    /** 四角信息里是否显示「言多必失榜」 */
    public boolean hudShowBoard = true;

    /**
     * 指定录音设备（设备名的子串即可，留空 = 系统默认设备）。
     * 启动时会把可用设备列到日志里。
     */
    public String audioDevice = "";

    // ------------------------------------------------------------------
    // 网页控制台
    // ------------------------------------------------------------------

    /** 是否启用网页控制台 */
    public boolean webEnabled = true;

    /** 网页端口（只绑 127.0.0.1，外网访问不到） */
    public int webPort = 38471;

    // ------------------------------------------------------------------
    // 智能选词
    // ------------------------------------------------------------------

    /** 是否用「上周期高频词」来挑下一轮的危险词 */
    public boolean smartPick = true;

    /** 智能选词的命中概率，0~100。70 = 七成概率用高频词，三成随机 */
    public int smartPickChance = 70;

    /** 每轮放几个危险词 */
    public int wordsPerRound = 5;

    /** 每轮固定塞几个「我 / 你 / 他」这种高频地狱词（0 = 不塞） */
    public int highFreqPerRound = 1;

    /** 词库里是否包含内置的游戏词库 */
    public boolean usePresetWords = true;

    /** 网页上展示多少个高频词 */
    public int statsTopN = 12;

    /** 当前生效的配置（全局唯一） */
    private static ModConfig instance;

    public static ModConfig get() {
        return instance;
    }

    public static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 读配置；文件不存在 / 读取失败就用默认值，并立刻写回一份方便手改 */
    public static ModConfig load() {
        Path file = file();
        ModConfig loaded = null;

        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(reader, ModConfig.class);
            } catch (Exception e) {
                com.example.voicekill.VoiceKillMod.LOGGER.error(
                        "[VoiceKill] 配置读取失败，改用默认值：{}", e.toString());
            }
        }

        if (loaded == null) {
            loaded = new ModConfig();
        }
        loaded.sanitize();
        loaded.save();
        instance = loaded;
        return loaded;
    }

    public void save() {
        synchronized (this) {
            try {
                Path file = file();
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(this, writer);
                }
            } catch (Exception e) {
                com.example.voicekill.VoiceKillMod.LOGGER.error(
                        "[VoiceKill] 配置保存失败：{}", e.toString());
            }
        }
    }

    /** 兜底：null / 空列表 / 非法间隔 全部拉回合法值 */
    private void sanitize() {
        Set<String> cleaned = new LinkedHashSet<>();
        if (dangerWords != null) {
            for (String word : dangerWords) {
                if (word == null) continue;
                String trimmed = word.trim();
                if (!trimmed.isEmpty()) cleaned.add(trimmed);
            }
        }
        if (cleaned.isEmpty()) {
            cleaned.add("苦力怕");
            cleaned.add("钻石");
            cleaned.add("岩浆");
        }
        dangerWords = new ArrayList<>(cleaned);

        if (intervalSeconds < 5) intervalSeconds = 5;
        if (intervalSeconds > 3600) intervalSeconds = 3600;

        if (voskModel == null) voskModel = "";

        if (webPort < 1024 || webPort > 65535) webPort = 38471;
        if (smartPickChance < 0) smartPickChance = 0;
        if (smartPickChance > 100) smartPickChance = 100;
        if (statsTopN < 3) statsTopN = 3;
        if (statsTopN > 50) statsTopN = 50;

        if (wordsPerRound < 1) wordsPerRound = 1;
        if (wordsPerRound > 20) wordsPerRound = 20;
        if (highFreqPerRound < 0) highFreqPerRound = 0;
        if (highFreqPerRound > wordsPerRound) highFreqPerRound = wordsPerRound;
        if (namesPerRound < 0) namesPerRound = 0;
        if (namesPerRound > wordsPerRound) namesPerRound = wordsPerRound;

        if (nameWords == null) nameWords = new ArrayList<>();
        nameWords.removeIf(w -> w == null || w.isBlank());
        if (highFreqExtra == null) highFreqExtra = new ArrayList<>();
        highFreqExtra.removeIf(w -> w == null || w.isBlank());
        if (highFreqDisabled == null) highFreqDisabled = new ArrayList<>();
        highFreqDisabled.removeIf(w -> w == null || w.isBlank());
        if (aliases == null) aliases = new LinkedHashMap<>();
    }

    /** 加一个自定义危险词（去重、去空白）。返回是否真的加进去了 */
    public boolean addDangerWord(String word) {
        if (word == null) return false;
        String trimmed = word.trim();
        if (trimmed.isEmpty()) return false;
        for (String existing : dangerWords) {
            if (existing.equalsIgnoreCase(trimmed)) return false;
        }
        dangerWords.add(trimmed);
        save();
        return true;
    }

    /** 删掉一个自定义危险词。返回是否真的删掉了 */
    public boolean removeDangerWord(String word) {
        if (word == null) return false;
        String trimmed = word.trim();
        boolean removed = dangerWords.removeIf(w -> w.equalsIgnoreCase(trimmed));
        if (removed) save();
        return removed;
    }
}
