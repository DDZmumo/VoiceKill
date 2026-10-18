package com.example.voicekill.wordbank;

import com.example.voicekill.config.ModConfig;
import com.example.voicekill.util.TextUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 词库。
 *
 * 设计原则（被实测教育过）：**危险词必须是玩家真的会从嘴里说出来的词**。
 * 没人会在跑图时喊「生猪肉」「骷髅」，但一定会喊「吃的」「小白」。
 * 所以主力是口语俗称（SLANG），正式名（FORMAL）只留少量真的会被说的。
 *
 * 分层：
 * - SLANG      口语俗称，主力
 * - FORMAL     正式名，少
 * - HIGH_FREQ  你我他这种虚词，定额投放（见 highFreqPerRound）
 * - 人名       配置里的 nameWords + 在线玩家名，定额投放（见 namesPerRound）
 * - 自定义     配置里的 dangerWords
 */
public final class WordBank {

    /** 口语俗称 —— 玩家边跑边喊的就是这些 */
    private static final List<String> SLANG = List.of(
            // ---- 生物 ----
            "小白", "小黑", "苦力怕", "姜丝", "僵尸", "毒蜘蛛", "蜘蛛",
            "末影龙", "凋灵", "恶魂", "烈焰人", "岩浆怪", "小僵尸", "村民",
            "铁傀儡", "雪人", "羊驼", "熊猫", "狐狸", "兔子", "鱿鱼",
            "海豚", "海龟", "蜜蜂", "史莱姆", "幻翼", "掠夺者", "卫道士",
            "大法师", "猪灵", "疣猪", "盲僧", "嗅探兽", "蝌蚪", "青蛙",
            "山羊", "六角恐龙", "骷髅马", "僵尸猪人", "北极熊", "驴", "鹦鹉",

            // ---- 吃的喝的 ----
            "吃的", "喝的", "面包", "苹果", "金苹果", "土豆", "胡萝卜",
            "西瓜", "南瓜", "蛋糕", "饼干", "牛排", "猪排", "鸡肉",
            "烤鱼", "蘑菇煲", "炖菜", "蜂蜜", "牛奶", "药水", "南瓜派",

            // ---- 装备工具 ----
            "盾牌", "弓箭", "钻石剑", "铁剑", "镐子", "斧头", "铲子",
            "锄头", "火把", "水桶", "岩浆桶", "打火石", "珍珠", "眼睛",
            "图腾", "翅膀", "三叉戟", "鱼竿", "剪刀", "打火机",

            // ---- 方块物品 ----
            "钻石", "绿宝石", "青金石", "红石", "煤炭", "铁锭", "金锭",
            "铜锭", "合金", "黑曜石", "基岩", "海绵", "海带", "甘蔗",
            "粘液球", "火药", "骨头", "羽毛", "皮革", "线", "腐肉",
            "蜘蛛眼", "荧石", "灵魂沙", "末地石", "紫水晶", "深板岩",
            "玻璃", "羊毛", "告示牌", "灯笼", "篝火", "讲台", "南瓜灯",
            "潮涌核心", "信标", "附魔书", "刷怪笼", "重锤", "龙蛋",
            "床", "箱子", "熔炉", "工作台", "附魔台", "书架", "铁砧",
            "漏斗", "活塞", "炸药", "船", "矿车", "铁轨", "梯子", "门",

            // ---- 地图 / 行为 ----
            "挖矿", "下矿", "打怪", "刷怪", "虚空", "摔死", "淹死",
            "饿死", "重生", "传送", "回城", "存档", "读档", "联机",
            "开图", "跑图", "出生点", "末地", "下界", "地狱", "主世界",
            "要塞", "村庄", "矿洞", "洞穴", "森林", "沙漠", "雪地",
            "海底", "刷铁机", "刷怪塔", "农场", "附魔", "召唤"
    );

    /** 正式名：确实也有人这么说，但频率低。留一部分足够 */
    private static final List<String> FORMAL = List.of(
            "骷髅", "末影人", "僵尸猪灵", "远古守卫者", "守卫者", "潜影贝",
            "劫掠兽", "唤魔者", "蠹虫", "监守者", "流浪商人", "炽足兽",
            "骆驼", "美西螈", "不死图腾", "鞘翅", "末影珍珠", "末影之眼",
            "粘液块", "蜂蜜块", "海晶灯", "菌光体", "凝灰岩", "紫颂果",
            "恶魂之泪", "烈焰棒", "甜菜根", "毒马铃薯", "发酵蜘蛛眼",
            "命名牌", "拴绳", "侦测器", "红石中继器", "红石比较器",
            "阳光探测器", "下界之星", "海洋之心", "迷之炖菜", "兔肉煲",

            // 英文（用英文模型时才有意义）
            "creeper", "enderman", "netherite", "elytra", "totem", "beacon",
            "obsidian", "ghast", "warden", "trident", "shulker", "potion",
            "bedrock", "anvil", "hopper", "piston", "redstone", "diamond",
            "emerald", "lapis", "blaze", "phantom", "ravager", "skeleton",
            "zombie", "spider", "villager", "golem"
    );

    /**
     * 高频地狱词：日常口语里几乎每句都会出现，说出来必死。
     * 单独一组、定额投放，不混进随机池。
     *
     * 这些只是**内置默认值**，网页上可以禁用某个、也可以自己加，见 {@link #highFreqFor}。
     *
     * 注意：这些词同样在 SpeechStats 的停用词表里（统计时忽略），
     * 但作为「危险词」匹配时完全不受影响。
     */
    private static final List<String> HIGH_FREQ = List.of(
            "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "咱们",
            "大家", "自己", "别人", "人家", "各位", "是", "不是", "不", "没", "没有",
            "有", "就", "这", "那", "的", "得", "了", "着", "过", "也",
            "都", "还", "又", "再", "才", "很", "太", "最", "更", "挺",
            "特别", "比较", "这个", "那个", "这些", "那些", "这里", "那里", "这边", "那边",
            "这样", "那样", "这种", "那种", "这么", "那么", "一个", "一下", "一点", "一次",
            "一遍", "一会", "一趟", "一堆", "一群", "一半", "一天", "什么", "怎么", "为什么",
            "怎么办", "怎么样", "咋办", "多少", "多久", "几个", "哪个", "谁", "哪里", "哪儿",
            "干啥", "干嘛", "咋", "但是", "就是", "因为", "所以", "然后", "而且", "不过",
            "如果", "虽然", "要是", "于是", "结果", "反正", "总之", "或者", "还有", "接着",
            "可以", "不行", "不要", "不用", "不能", "不会", "知道", "不知道", "觉得", "感觉",
            "真的", "确实", "其实", "应该", "可能", "一定", "必须", "好像", "大概", "有点",
            "肯定", "估计", "差不多", "对吧", "是吧", "是不是", "有没有", "能不能", "要不要", "好不好",
            "行不行", "对不对", "会不会", "可不可以", "喂", "诶", "哎", "嗯", "啊", "吧",
            "吗", "呀", "哦", "唉", "哈", "嘿", "噢", "哇", "嘛", "呢",
            "啦", "咯", "完了", "算了", "行了", "好了", "快走", "快来", "等一下", "等等",
            "别动", "别急", "没事", "麻烦", "帮忙", "东西", "事情", "时间", "地方", "问题",
            "意思", "现在", "已经", "刚才", "马上", "一会儿", "待会", "回头", "先", "卧槽",
            "我靠", "天哪", "我去", "离谱", "绝了", "牛", "服了", "麻了", "崩了", "寄了",
            "凉了", "完了完了", "救命", "搞什么", "什么鬼", "太过分", "受不了", "走吧", "过来", "上去",
            "下去", "这边走", "快跑", "快撤", "小心", "当心", "注意", "看看", "等一下我", "给我",
            "帮我", "别管", "先走", "跟上", "等我", "来", "去", "说", "看", "听",
            "要", "想", "用", "弄", "搞", "整", "玩", "打", "死", "活",
            "掉", "丢", "捡", "进", "出", "开", "关", "真", "假", "对",
            "错", "行", "好的", "可以啊", "是啊", "对啊", "是啊是啊", "是这样", "这样的话", "然后呢",
            "所以呢", "你说", "我说", "他说"
    );

    private WordBank() {
    }

    public static List<String> slang() {
        return SLANG;
    }

    public static List<String> formal() {
        return FORMAL;
    }

    public static List<String> highFreq() {
        return HIGH_FREQ;
    }

    /**
     * 实际生效的高频词 = 内置 − 被禁用的 + 用户自己加的。
     * 网页上「高频」分类里增删，就是改这两个列表。
     */
    public static List<String> highFreqFor(ModConfig config) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (config == null) {
            return new ArrayList<>(HIGH_FREQ);
        }
        Set<String> disabled = new LinkedHashSet<>();
        for (String word : config.highFreqDisabled) {
            disabled.add(TextUtil.normalize(word));
        }
        for (String word : HIGH_FREQ) {
            if (!disabled.contains(TextUtil.normalize(word))) {
                set.add(word);
            }
        }
        for (String word : config.highFreqExtra) {
            if (!word.isBlank()) {
                set.add(word.trim());
            }
        }
        return new ArrayList<>(set);
    }

    /** 当前实际可用的内置词（用中文模型时会剔除纯英文词） */
    public static List<String> presetFor(ModConfig config) {
        boolean chinese = usesChineseModel(config);
        List<String> merged = new ArrayList<>(SLANG.size() + FORMAL.size());
        for (String word : SLANG) {
            if (!chinese || !isAsciiOnly(word)) {
                merged.add(word);
            }
        }
        for (String word : FORMAL) {
            if (!chinese || !isAsciiOnly(word)) {
                merged.add(word);
            }
        }
        return merged;
    }

    public static boolean usesChineseModel(ModConfig config) {
        return config != null
                && config.voskModel != null
                && config.voskModel.toLowerCase().contains("cn");
    }

    /** 在线玩家名字。这是「别人」的名字，只在内存和本地网页里用，不会外发 */
    public static List<String> playerNames(MinecraftServer server) {
        if (server == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String name = player.getName().getString();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 人名池 = 你自己在网页「人名」分类里加的词（不再自动收集在线玩家 ID） */
    public static List<String> namePool(MinecraftServer server, ModConfig config) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (config != null) {
            names.addAll(config.nameWords);
        }
        names.removeIf(w -> w == null || w.isBlank());
        return new ArrayList<>(names);
    }

    public static List<String> custom(ModConfig config) {
        return config == null ? List.of() : List.copyOf(config.dangerWords);
    }

    /** 随机补位用的合并池（自定义 → 口语 → 正式 → 玩家名） */
    public static List<String> all(MinecraftServer server, ModConfig config) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (config != null) {
            set.addAll(config.dangerWords);
        }
        if (config == null || config.usePresetWords) {
            set.addAll(presetFor(config));
        }
        set.removeIf(w -> w == null || w.isBlank());
        return new ArrayList<>(set);
    }

    /**
     * 词库里「认识」的所有词（归一化后）。
     * 词频排序时用这个当先验：认识的词排前面，避免「天又被苦」这种跨词碎片挤掉真词。
     */
    public static Set<String> knownWords(MinecraftServer server, ModConfig config) {
        Set<String> known = new LinkedHashSet<>();
        for (String word : all(server, config)) {
            known.add(TextUtil.normalize(word));
        }
        for (String word : namePool(server, config)) {
            known.add(TextUtil.normalize(word));
        }
        for (String word : highFreqFor(config)) {
            known.add(TextUtil.normalize(word));
        }
        return known;
    }

    private static boolean isAsciiOnly(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (word.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
