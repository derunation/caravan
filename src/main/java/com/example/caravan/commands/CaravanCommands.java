package com.example.caravan.commands;

import com.example.caravan.debug.DebugFlags;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * 商队 mod 的命令：{@code /caravan debug [on|off]} 开启/关闭调试模式。
 */
public final class CaravanCommands
{
    private CaravanCommands()
    {
    }

    public static void registerCommands(final RegisterCommandsEvent event)
    {
        final CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("caravan")
            .then(Commands.literal("debug")
                .executes(ctx -> setDebug(ctx.getSource().getPlayerOrException(), null))
                .then(Commands.literal("on").executes(ctx -> setDebug(ctx.getSource().getPlayerOrException(), true)))
                .then(Commands.literal("off").executes(ctx -> setDebug(ctx.getSource().getPlayerOrException(), false)))));
    }

    private static int setDebug(final ServerPlayer player, final Boolean value)
    {
        final boolean enabled = value != null ? value : !DebugFlags.isEnabled(player);
        DebugFlags.setEnabled(player, enabled);
        player.displayClientMessage(Component.translatable(
            enabled ? "com.caravan.debug.enabled" : "com.caravan.debug.disabled"), true);
        return 1;
    }
}
