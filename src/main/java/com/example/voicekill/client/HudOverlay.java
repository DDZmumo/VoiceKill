package com.example.voicekill.client;

import com.example.voicekill.config.ModConfig;
import com.example.voicekill.network.HudStatePayload;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 屏幕常驻信息。
 *
 * 左上：本轮危险词（词多会自动换行）
 * 右上：倒计时
 * 右侧中间：言多必失榜
 *
 * 数据全部来自服务端的 {@link HudStatePayload} —— 客户端只负责画，
 * 不做任何判定（不然改客户端就能免死）。
 */
public final class HudOverlay implements HudElement {

    private static final int PANEL_BG = 0x66000000;
    private static final int COLOR_LABEL = 0xFF8B98A5;
    private static final int COLOR_WORD = 0xFFFF5A5F;
    private static final int COLOR_TEXT = 0xFFE6EDF3;
    private static final int COLOR_ACCENT = 0xFF3FBEEF;
    private static final int COLOR_BOARD = 0xFFFFD479;

    // ---- 服务端同步过来的状态 ----
    private static volatile boolean running = false;
    private static volatile List<String> words = List.of();
    private static volatile int remainingSeconds = 0;
    private static volatile int intervalSeconds = 30;
    private static volatile List<String> boardNames = List.of();
    private static volatile List<Integer> boardCounts = List.of();
    private static volatile int totalDeaths = 0;

    public static void update(HudStatePayload payload) {
        running = payload.running();
        words = payload.words();
        remainingSeconds = payload.remainingSeconds();
        intervalSeconds = payload.intervalSeconds();
        boardNames = payload.boardNames();
        boardCounts = payload.boardCounts();
        totalDeaths = payload.totalDeaths();
    }

    @Override
    public void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || client.options.hideGui) {
            return;
        }
        ModConfig config = ModConfig.get();
        if (config == null || !config.hudEnabled) {
            return;
        }

        Font font = client.font;
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();

        renderWords(graphics, font, 6, 6, width);
        renderTimer(graphics, font, width - 6, 6);
        renderBoard(graphics, font, width - 6, height / 2, config.hudShowBoard);
    }

    // ------------------------------------------------------------------

    private void renderWords(GuiGraphics graphics, Font font, int x, int y, int screenWidth) {
        if (!running) {
            String hint = "未开始 · 输入 /voicekill start";
            int panelWidth = font.width(hint) + 12;
            graphics.fill(x, y, x + panelWidth, y + 18, PANEL_BG);
            graphics.drawString(font, Component.literal(hint), x + 4, y + 5, COLOR_ACCENT, false);
            return;
        }
        if (words.isEmpty()) {
            return;
        }

        // 词多（比如 12 个）就自动折行，最多占半屏宽
        int maxWidth = Math.max(160, screenWidth / 2 - 12);
        List<String> lines = wrap(font, words, " · ", maxWidth);

        int panelWidth = 0;
        for (String line : lines) {
            panelWidth = Math.max(panelWidth, font.width(line));
        }
        panelWidth += 12;
        int panelHeight = 13 + lines.size() * 10;

        graphics.fill(x, y, x + panelWidth, y + panelHeight, PANEL_BG);
        graphics.drawString(font, Component.literal("本轮危险词"), x + 4, y + 2, COLOR_LABEL, false);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, Component.literal(lines.get(i)),
                    x + 4, y + 12 + i * 10, COLOR_WORD, true);
        }
    }

    /** 按宽度把词贪心折行 */
    private static List<String> wrap(Font font, List<String> words, String separator, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : words) {
            String candidate = current.isEmpty() ? word : current + separator + word;
            if (!current.isEmpty() && font.width(candidate) > maxWidth) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private void renderTimer(GuiGraphics graphics, Font font, int right, int y) {
        if (!running) {
            return;
        }
        String label = remainingSeconds + "s";
        String hint = "后换词";
        int labelWidth = font.width(label);
        int hintWidth = font.width(hint);
        int panelWidth = labelWidth + hintWidth + 14;

        int left = right - panelWidth;
        graphics.fill(left, y, right, y + 26, PANEL_BG);
        graphics.drawString(font, Component.literal(label), left + 4, y + 6, COLOR_ACCENT, true);
        graphics.drawString(font, Component.literal(hint), left + 8 + labelWidth, y + 6, COLOR_LABEL, false);
    }

    /** 言多必失榜：贴屏幕右侧、垂直居中 */
    private void renderBoard(GuiGraphics graphics, Font font, int right, int centerY, boolean show) {
        if (!show || boardNames.isEmpty()) {
            return;
        }
        int lines = Math.min(boardNames.size(), 5);
        String footer = totalDeaths > 0 ? ("共 " + totalDeaths + " 次") : null;

        // 标题 12 + 每行 11，底部再留一行给「共 N 次」—— 少算这一行就会和最后一行叠在一起
        int titleHeight = 12;
        int rowHeight = 11;
        int footerHeight = footer == null ? 0 : 12;
        int panelHeight = titleHeight + lines * rowHeight + footerHeight + 4;

        // 宽度按实际内容算，别写死 120（名字长一点就挤成一团）
        int contentWidth = font.width("言多必失榜");
        for (int i = 0; i < lines; i++) {
            String name = boardNames.get(i);
            int count = i < boardCounts.size() ? boardCounts.get(i) : 0;
            contentWidth = Math.max(contentWidth, font.width((i + 1) + ". " + name + "  " + count));
        }
        if (footer != null) {
            contentWidth = Math.max(contentWidth, font.width(footer));
        }
        int panelWidth = contentWidth + 12;

        int left = right - panelWidth;
        int top = centerY - panelHeight / 2;
        graphics.fill(left, top, right, top + panelHeight, PANEL_BG);
        graphics.drawString(font, Component.literal("言多必失榜"), left + 4, top + 2, COLOR_LABEL, false);

        for (int i = 0; i < lines; i++) {
            String name = boardNames.get(i);
            int count = i < boardCounts.size() ? boardCounts.get(i) : 0;
            String text = (i + 1) + ". " + name + "  " + count;
            int color = i == 0 ? COLOR_BOARD : COLOR_TEXT;
            graphics.drawString(font, Component.literal(text),
                    left + 4, top + titleHeight + i * rowHeight, color, false);
        }
        if (footer != null) {
            graphics.drawString(font, Component.literal(footer),
                    left + 4, top + titleHeight + lines * rowHeight, COLOR_LABEL, false);
        }
    }

    /** 给网页/日志用：当前这组词 */
    public static List<String> currentWords() {
        return words;
    }
}
