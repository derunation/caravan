package com.example.caravan.debug;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 调试模式开关（内存态）：开启后，商队领袖的每个行动开始时都会向
 * 开启了调试的玩家发送本地化聊天信息。
 */
public final class DebugFlags
{
    private static final Set<UUID> DEBUG_PLAYERS = new HashSet<>();

    private DebugFlags()
    {
    }

    public static boolean isEnabled(final ServerPlayer player)
    {
        return DEBUG_PLAYERS.contains(player.getUUID());
    }

    public static void setEnabled(final ServerPlayer player, final boolean enabled)
    {
        if (enabled)
        {
            DEBUG_PLAYERS.add(player.getUUID());
        }
        else
        {
            DEBUG_PLAYERS.remove(player.getUUID());
        }
    }

    /** 向当前世界中所有开启调试的玩家发送信息。 */
    public static void sendDebug(final ServerLevel level, final Component message)
    {
        for (final ServerPlayer player : level.players())
        {
            if (DEBUG_PLAYERS.contains(player.getUUID()))
            {
                player.displayClientMessage(message, false);
            }
        }
    }
}
