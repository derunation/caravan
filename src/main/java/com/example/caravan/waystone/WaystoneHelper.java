package com.example.caravan.waystone;

import com.example.caravan.colony.buildings.modules.VillagerTradeEntry;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.blay09.mods.waystones.block.entity.WaystoneBlockEntityBase;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.Comparator;
import java.util.UUID;

/**
 * 传送石碑名称查询（完全重构版）。
 *
 * <p>设计要点（针对历史问题：名称不更新、摧毁后不恢复距离、偶尔取到已摧毁石碑名）：</p>
 * <ul>
 *   <li>候选来源：Waystones 数据库（{@link WaystonesAPI#getAllWaystones}）——
 *       支持目标区块未加载的情况（远方村庄也能显示）；</li>
 *   <li>候选校验：按距离排序后逐一处理，区块已加载时必须在该位置找到
 *       Waystone 方块实体（被摧毁/被替换的石碑立即排除 → 恢复距离显示；
 *       若有其它石碑在范围内，则自动切换到下一个）；区块未加载时信任数据库条目；</li>
 *   <li>名称来源：区块已加载时优先取方块实体上的最新名称（重命名后立即生效），
 *       未加载时取数据库名称；未命名的石碑返回占位标记（GUI 本地化显示）。</li>
 * </ul>
 */
public final class WaystoneHelper
{
    /** 检测半径（格）。 */
    public static final int SEARCH_RADIUS = 100;

    /** 查询结果：Waystone 的 UUID 与显示名称（未命名时名称为占位标记）。 */
    public record WaystoneInfo(UUID waystoneUid, String waystoneName)
    {
    }

    private WaystoneHelper()
    {
    }

    /**
     * 查找目标位置 SEARCH_RADIUS 格内最近的“真实存在”的 Waystone 显示名称。
     *
     * @return 石碑信息（UUID + 名称）；范围内无有效石碑或 Waystones 未安装时返回 null。
     */
    public static WaystoneInfo findWaystoneNear(final ServerLevel level, final BlockPos pos)
    {
        try
        {
            return WaystonesAPI.getAllWaystones(level.getServer())
                .filter(waystone -> waystone.getDimension().equals(level.dimension()))
                .filter(waystone -> waystone.getPos().distSqr(pos) <= (long) SEARCH_RADIUS * SEARCH_RADIUS)
                .sorted(Comparator.comparingDouble(waystone -> waystone.getPos().distSqr(pos)))
                .map(waystone -> resolveInfo(level, waystone))
                .filter(info -> info != null)
                .findFirst()
                .orElse(null);
        }
        catch (final Throwable ignored)
        {
            // Waystones 未安装或查询失败时静默降级（GUI 显示距离）。
            return null;
        }
    }

    /**
     * 校验单个 Waystone 并返回其显示名称：
     * 区块已加载 → 必须存在 Waystone 方块实体（否则视为已摧毁，返回 null）；
     * 区块未加载 → 信任数据库条目。
     */
    private static WaystoneInfo resolveInfo(final ServerLevel level, final Waystone waystone)
    {
        final BlockPos pos = waystone.getPos();
        if (level.isLoaded(pos))
        {
            if (!(level.getBlockEntity(pos) instanceof WaystoneBlockEntityBase blockEntity))
            {
                // 被摧毁或被替换：排除该候选（恢复距离显示或切换至下一个石碑）。
                return null;
            }
            // 区块已加载：取方块实体上的最新信息（重命名/激活状态即时生效）。
            final Waystone fresh = blockEntity.getWaystone();
            return new WaystoneInfo(fresh.getWaystoneUid(), displayName(fresh.getEffectiveName()));
        }
        return new WaystoneInfo(waystone.getWaystoneUid(), displayName(waystone.getEffectiveName()));
    }

    /** 空白名称 → 占位标记；否则返回名称字符串。 */
    private static String displayName(final net.minecraft.network.chat.Component name)
    {
        final String text = name.getString();
        return text == null || text.isBlank() ? VillagerTradeEntry.WAYSTONE_UNNAMED : text;
    }
}
