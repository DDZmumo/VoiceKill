package com.example.voicekill.util;

/** 文本归一化工具。语音匹配和词频统计必须用同一套规则，否则两边对不上。 */
public final class TextUtil {

    private TextUtil() {
    }

    /**
     * 转小写 + 去掉一切空白字符。
     * Vosk 中文输出经常是「苦 力 怕」这种单字带空格的形式，不归一化永远匹配不上。
     */
    public static String normalize(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
