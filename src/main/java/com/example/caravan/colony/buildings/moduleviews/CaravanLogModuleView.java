package com.example.caravan.colony.buildings.moduleviews;

import com.example.caravan.CaravanMod;
import com.example.caravan.client.gui.modules.WindowCaravanLog;
import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 商队小屋【日志】标签页的客户端视图。
 */
public class CaravanLogModuleView extends AbstractBuildingModuleView
{
    /** 一次行程中的单笔交易摘要（日志展示用，按同一交易聚合）。 */
    /** 需求（备货供应比例）：costs 中每个交易成本的“已有供应量”（截断到需要量）。 */
    public record LogTradeEntry(ItemStack result, List<ItemStack> costs, List<Integer> supplied, int completed, int total)
    {
    }

    private boolean hasLeader;
    private boolean away;
    /** 需求（扎营）：模拟旅行中是否处于原地休息（扎营）状态。 */
    private boolean resting;
    /** 需求（模拟旅行状态机）：商队当前模拟状态（服务端权威，旅行地图/日志显示）。 */
    private JobCaravanLeader.CampStatus campStatus = JobCaravanLeader.CampStatus.TRAVEL;
    /** 需求（旅行地图）：当前游戏时间是否处于殖民地睡眠时间窗口
     *  （服务端直接按时间判定，不依赖 AI 是否 tick 到扎营分支）。 */
    private boolean sleepTimeNow;
    private JobCaravanLeader.CaravanStatus status = JobCaravanLeader.CaravanStatus.WAITING_ITEMS;
    private JobCaravanLeader.AwayPhase awayPhase = JobCaravanLeader.AwayPhase.OUTBOUND;
    private int awayDistance;
    private int awayTradeTicks;
    /** 需求（旅行地图联动）：当前段初始距离（计算移动进度用）。 */
    private int awayMaxDistance;
    /** 需求（旅行地图联动）：消失时的出发点。 */
    private BlockPos awayOriginPos;
    /** 需求（旅行地图联动）：当前段的起点与终点（插值移动用）。 */
    private BlockPos awayLegStart;
    private BlockPos awayLegEnd;
    /** 需求（旅行地图联动）：商队领袖的 NPC 贴图路径（用于头像图标）。 */
    private String leaderTexture = "";
    /** 需求（旅行地图联动）：剩余路线停靠点（出发点 + 按访问顺序排列的剩余目标）。 */
    private final List<BlockPos> awayRoute = new ArrayList<>();
    private final List<LogTradeEntry> trades = new ArrayList<>();
    /** 需求（旅行地图联动）：下一个目的地将要进行的第一笔交易的结果物品。 */
    private ItemStack nextTradeIcon = ItemStack.EMPTY;
    /** 需求（旅行地图联动）：领袖实体的当前实际位置（未消失时有效，可为 null）。 */
    private BlockPos leaderPos;
    /** 需求（穿越殖民地）：商队是否正在穿越殖民地（实体现身步行中）。 */
    private boolean walkingThroughColony;
    /** 需求（消耗品）：商队携带的每顶商队帐篷（独立栈，各含耐久信息；无则空列表）。 */
    private final List<ItemStack> tentStacks = new ArrayList<>();
    /** 需求（消耗品）：商队携带的菜单食物堆叠（带数量角标；无则空列表）。 */
    private final List<ItemStack> foodStacks = new ArrayList<>();
    /** 需求（消耗品）：商队携带的火把堆叠（带数量角标；无则空列表）。 */
    private final List<ItemStack> torchStacks = new ArrayList<>();
    /** 需求（饥饿）：商队中饱食度为 0 的人数（客户端显示饱食度图标用）。 */
    private int hungryCount;

    @Override
    public void deserialize(final RegistryFriendlyByteBuf buffer)
    {
        hasLeader = buffer.readBoolean();
        if (!hasLeader)
        {
            trades.clear();
            walkingThroughColony = false;
            awayOriginPos = null;
            awayLegStart = null;
            awayLegEnd = null;
            awayRoute.clear();
            return;
        }
        away = buffer.readBoolean();
        resting = buffer.readBoolean();
        final int campOrdinal = buffer.readVarInt();
        campStatus = campOrdinal >= 0 && campOrdinal < JobCaravanLeader.CampStatus.values().length
            ? JobCaravanLeader.CampStatus.values()[campOrdinal]
            : JobCaravanLeader.CampStatus.TRAVEL;
        sleepTimeNow = buffer.readBoolean();
        walkingThroughColony = buffer.readBoolean();
        leaderTexture = buffer.readUtf();
        final int statusOrdinal = buffer.readVarInt();
        status = statusOrdinal >= 0 && statusOrdinal < JobCaravanLeader.CaravanStatus.values().length
            ? JobCaravanLeader.CaravanStatus.values()[statusOrdinal]
            : JobCaravanLeader.CaravanStatus.WAITING_ITEMS;
        // 需求（文本显示）：展示相位（未消失时也有效），用于 旅行中/交易中/返回中 文本。
        final int phaseOrdinal = buffer.readVarInt();
        awayPhase = phaseOrdinal >= 0 && phaseOrdinal < JobCaravanLeader.AwayPhase.values().length
            ? JobCaravanLeader.AwayPhase.values()[phaseOrdinal]
            : JobCaravanLeader.AwayPhase.OUTBOUND;
        leaderPos = readPos(buffer);
        if (away)
        {
            awayMaxDistance = buffer.readVarInt();
            if (awayPhase == JobCaravanLeader.AwayPhase.TRADING)
            {
                awayTradeTicks = buffer.readVarInt();
            }
            else
            {
                awayDistance = buffer.readVarInt();
            }
            awayOriginPos = readPos(buffer);
            awayLegStart = readPos(buffer);
            awayLegEnd = readPos(buffer);
            awayRoute.clear();
            final int routeCount = buffer.readVarInt();
            for (int i = 0; i < routeCount; i++)
            {
                final BlockPos pos = readPos(buffer);
                if (pos != null)
                {
                    awayRoute.add(pos);
                }
            }
        }
        else
        {
            awayOriginPos = null;
            awayLegStart = null;
            awayLegEnd = null;
            awayRoute.clear();
        }

        trades.clear();
        final int count = buffer.readVarInt();
        for (int i = 0; i < count; i++)
        {
            final ItemStack result = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            final int costCount = buffer.readVarInt();
            final List<ItemStack> costs = new ArrayList<>(costCount);
            for (int c = 0; c < costCount; c++)
            {
                costs.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            }
            final List<Integer> supplied = new ArrayList<>(costCount);
            for (int c = 0; c < costCount; c++)
            {
                supplied.add(buffer.readVarInt());
            }
            trades.add(new LogTradeEntry(
                result, costs, supplied, buffer.readVarInt(), buffer.readVarInt()));
        }
        nextTradeIcon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
        tentStacks.clear();
        final int tentCount = buffer.readVarInt();
        for (int i = 0; i < tentCount; i++)
        {
            final ItemStack tent = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            if (!tent.isEmpty())
            {
                tentStacks.add(tent);
            }
        }
        foodStacks.clear();
        final int foodCount = buffer.readVarInt();
        for (int i = 0; i < foodCount; i++)
        {
            final ItemStack food = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            if (!food.isEmpty())
            {
                foodStacks.add(food);
            }
        }
        torchStacks.clear();
        final int torchCount = buffer.readVarInt();
        for (int i = 0; i < torchCount; i++)
        {
            final ItemStack torch = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            if (!torch.isEmpty())
            {
                torchStacks.add(torch);
            }
        }
        hungryCount = buffer.readVarInt();
    }

    private static BlockPos readPos(final RegistryFriendlyByteBuf buffer)
    {
        return buffer.readBoolean()
            ? new BlockPos(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt())
            : null;
    }

    @Override
    public BOWindow getWindow()
    {
        return new WindowCaravanLog(getBuildingView(), this);
    }

    @Override
    public Component getDesc()
    {
        return Component.translatable("com.caravan.gui.log");
    }

    @Override
    public ResourceLocation getIconResourceLocation()
    {
        // 需求：使用【快递员小屋】-【任务】标签页图标（RequestTaskModuleView 的 info.png）。
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/info.png");
    }

    public boolean hasLeader()
    {
        return hasLeader;
    }

    public boolean isAway()
    {
        return away;
    }

    /** 需求（扎营）：商队是否处于模拟旅行中的原地休息（扎营）状态。 */
    public boolean isResting()
    {
        return resting;
    }

    /** 需求（模拟旅行状态机）：商队当前模拟状态（旅行中/夜行中/扎营中/露宿中/交易中）。 */
    public JobCaravanLeader.CampStatus getCampStatus()
    {
        return campStatus;
    }

    /** 需求（旅行地图）：当前是否处于殖民地睡眠时间窗口（服务端按当前时间判定）。 */
    public boolean isSleepTimeNow()
    {
        return sleepTimeNow;
    }

    public JobCaravanLeader.CaravanStatus getStatus()
    {
        return status;
    }

    public JobCaravanLeader.AwayPhase getAwayPhase()
    {
        return awayPhase;
    }

    public int getAwayDistance()
    {
        return awayDistance;
    }

    /** “交易中”阶段剩余停留时间（游戏刻）。 */
    public int getAwayTradeTicks()
    {
        return awayTradeTicks;
    }

    public int getAwayMaxDistance()
    {
        return awayMaxDistance;
    }

    public BlockPos getAwayLegStart()
    {
        return awayLegStart;
    }

    public BlockPos getAwayLegEnd()
    {
        return awayLegEnd;
    }

    public List<LogTradeEntry> getTrades()
    {
        return trades;
    }

    /** 下一个目的地第一笔交易的结果物品（空物品 = 无下一目的地，如回程阶段）。 */
    public ItemStack getNextTradeIcon()
    {
        return nextTradeIcon;
    }

    /** 领袖实体的当前实际位置（未消失时每 20 刻同步；未加载时为 null）。 */
    public BlockPos getLeaderPos()
    {
        return leaderPos;
    }

    /** 需求（穿越殖民地）：商队正在穿越殖民地（实体现身步行中）。 */
    public boolean isWalkingThroughColony()
    {
        return walkingThroughColony;
    }

    /** 需求（消耗品）：商队携带的每顶商队帐篷（无则空列表）。 */
    public List<ItemStack> getTentStacks()
    {
        return tentStacks;
    }

    /** 需求（消耗品）：商队携带的菜单食物堆叠（无则空列表）。 */
    public List<ItemStack> getFoodStacks()
    {
        return foodStacks;
    }

    /** 需求（消耗品）：商队携带的火把堆叠（无则空列表）。 */
    public List<ItemStack> getTorchStacks()
    {
        return torchStacks;
    }

    /** 需求（饥饿）：商队中饱食度为 0（饥饿）的人数。 */
    public int getHungryCount()
    {
        return hungryCount;
    }
}
