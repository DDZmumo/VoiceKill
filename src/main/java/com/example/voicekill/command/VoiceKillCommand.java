package com.example.voicekill.command;

import com.example.voicekill.DangerWordManager;
import com.example.voicekill.VoiceKillMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * /voicekill start | stop | status
 *
 * 默认**不启用** —— 必须手动 start 才开始判定，避免一进世界就被自己的话干掉。
 * 需要 OP 权限（等级 2）；单人游戏自己就是 OP。
 */
public final class VoiceKillCommand {

    private VoiceKillCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("voicekill")
                .then(Commands.literal("start")
                        .executes(context -> {
                            DangerWordManager manager = VoiceKillMod.manager();
                            if (manager == null) {
                                context.getSource().sendFailure(Component.literal("VoiceKill 还没初始化"));
                                return 0;
                            }
                            MinecraftServer server = context.getSource().getServer();
                            if (manager.isRunning()) {
                                context.getSource().sendSuccess(
                                        () -> Component.literal("游戏已经在进行中"), false);
                                return 0;
                            }
                            manager.start(server);
                            context.getSource().sendSuccess(
                                    () -> Component.literal("VoiceKill 已开始！屏幕上会显示本轮危险词"), true);
                            return 1;
                        }))
                .then(Commands.literal("stop")
                        .executes(context -> {
                            DangerWordManager manager = VoiceKillMod.manager();
                            if (manager == null) {
                                return 0;
                            }
                            manager.stop(context.getSource().getServer());
                            context.getSource().sendSuccess(
                                    () -> Component.literal("VoiceKill 已停止"), true);
                            return 1;
                        }))
                .then(Commands.literal("status")
                        .executes(context -> {
                            DangerWordManager manager = VoiceKillMod.manager();
                            if (manager == null) {
                                context.getSource().sendFailure(Component.literal("还没初始化"));
                                return 0;
                            }
                            String state = manager.isRunning() ? "§a进行中" : "§7未开始";
                            context.getSource().sendSuccess(
                                    () -> Component.literal("VoiceKill：" + state
                                            + " §7（/voicekill start 开始，stop 停止）"), false);
                            return 1;
                        }));

        LiteralCommandNode<CommandSourceStack> node = dispatcher.register(root);
        // 顺手给个短别名：/vk start
        dispatcher.register(Commands.literal("vk").redirect(node));
    }
}
