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
    public record LogTradeEntry(ItemStack result, List<ItemStack> costs, List<Integer> supplied, int completed, int total)
    {
    }

    private boolean hasLeader;
    private boolean away;
    private boolean resting;
    private JobCaravanLeader.CampStatus campStatus = JobCaravanLeader.CampStatus.TRAVEL;
    /**
     *  （服务端直接按时间判定，不依赖 AI 是否 tick 到扎营分支）。 */
    private boolean sleepTimeNow;
    private JobCaravanLeader.CaravanStatus status = JobCaravanLeader.CaravanStatus.WAITING_ITEMS;
    private JobCaravanLeader.AwayPhase awayPhase = JobCaravanLeader.AwayPhase.OUTBOUND;
    private int awayDistance;
    private int awayTradeTicks;
    private int awayMaxDistance;
    private BlockPos awayOriginPos;
    private BlockPos awayLegStart;
    private BlockPos awayLegEnd;
    private String leaderTexture = "";
    private final List<BlockPos> awayRoute = new ArrayList<>();
    private final List<LogTradeEntry> trades = new ArrayList<>();
    private ItemStack nextTradeIcon = ItemStack.EMPTY;
    private BlockPos leaderPos;
    private boolean walkingThroughColony;
    private final List<ItemStack> tentStacks = new ArrayList<>();
    private final List<ItemStack> foodStacks = new ArrayList<>();
    private final List<ItemStack> torchStacks = new ArrayList<>();
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

    public boolean isResting()
    {
        return resting;
    }

    public JobCaravanLeader.CampStatus getCampStatus()
    {
        return campStatus;
    }

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

    public boolean isWalkingThroughColony()
    {
        return walkingThroughColony;
    }

    public List<ItemStack> getTentStacks()
    {
        return tentStacks;
    }

    public List<ItemStack> getFoodStacks()
    {
        return foodStacks;
    }

    public List<ItemStack> getTorchStacks()
    {
        return torchStacks;
    }

    public int getHungryCount()
    {
        return hungryCount;
    }
}
