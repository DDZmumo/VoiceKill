package com.example.voicekill.util;

/**
 * 模糊匹配：允许「念得像」就算命中。
 *
 * 场景：有人叫「你好呀装装」，Vosk 有时会识别成「你好呀中中」，一个字一个字都不差地匹配就漏了。
 *
 * 做法是最朴素的编辑距离 + 严苛的门槛，**宁可漏判也不误杀**：
 * - 只对长度 ≥4 的词启用（「小白」「吃的」这种短词一律走精确匹配，不然「小黑」都能被算成「小白」）
 * - 长度 4 → 最多差 1 个字；长度 ≥5 → 最多差 2 个字
 * - 用等长滑窗逐段比较，避免长句整体距离被稀释
 *
 * 注意：这是**字形**距离，不是拼音。同音字（装/中）距离是 1，正好能覆盖；
 * 但「装」和「庄」也算 1，所以必须靠长度门槛把短词的误伤压住。
 */
public final class FuzzyMatch {

    /** 少于这个长度不做模糊匹配 */
    private static final int MIN_FUZZY_LENGTH = 4;

    /** 编辑距离上限（再长也不放宽，避免误杀） */
    private static final int MAX_ALLOWED_DISTANCE = 2;

    private FuzzyMatch() {
    }

    /**
     * @param target 危险词（已归一化）
     * @param text   识别文本（已归一化）
     */
    public static boolean isClose(String target, String text) {
        if (target == null || text == null) {
            return false;
        }
        int length = target.length();
        if (length < MIN_FUZZY_LENGTH || text.length() < length - 1) {
            return false;
        }
        // 精确包含先走一遍，快
        if (text.contains(target)) {
            return true;
        }

        int allowed = length >= 5 ? 2 : 1;
        if (allowed > MAX_ALLOWED_DISTANCE) {
            allowed = MAX_ALLOWED_DISTANCE;
        }

        // 等长滑窗
        for (int i = 0; i + length <= text.length(); i++) {
            if (distanceWithin(target, text.substring(i, i + length), allowed)) {
                return true;
            }
        }
        // 少说/多说一个字也算：窗口取 length-1
        if (length >= MIN_FUZZY_LENGTH && length - 1 >= MIN_FUZZY_LENGTH) {
            for (int i = 0; i + (length - 1) <= text.length(); i++) {
                if (distanceWithin(target, text.substring(i, i + length - 1), 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 编辑距离是否 ≤ allowed（带早停，避免算满整张表） */
    private static boolean distanceWithin(String a, String b, int allowed) {
        if (Math.abs(a.length() - b.length()) > allowed) {
            return false;
        }
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            int rowMin = current[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
                if (current[j] < rowMin) {
                    rowMin = current[j];
                }
            }
            if (rowMin > allowed) {
                return false;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()] <= allowed;
    }
}
