package com.example.caravan.colony.jobs;

import com.example.caravan.CaravanMod;
import com.example.caravan.entity.ai.EntityAIWorkCaravanLeader;
import com.example.caravan.item.TradeRecord;
import com.minecolonies.api.client.render.modeltype.ModModelTypes;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.constant.CitizenConstants;
import com.minecolonies.core.colony.jobs.AbstractJob;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.util.AttributeModifierUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商队领袖职业（对应小屋工作模块，参考下界矿工 JobNetherWorker）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>生成并持有 AI（{@link #generateAI()}）；</li>
 *   <li>保存本次行程要尝试的全部订单（{@link #getTrip()}，一次出行执行列表中
 *       所有可用交易）；</li>
 *   <li>持久化“消失中”状态：{@link #isAway()}、{@link #getAwayPhase()}
 *       （false=去程 / true=回程）、{@link #getAwayDistance()}（剩余格数）与
 *       {@link #getAwayMaxDistance()}（重置用初始格数）；</li>
 *   <li>消失期间保存随身补给（{@link #getSupplies()}）与模拟交易成果
 *       （{@link #getResults()}），世界重载不丢失；</li>
 *   <li>市民模型使用信使（COURIER）外观（{@link #getModel()}）。</li>
 * </ul>
 */
public class JobCaravanLeader extends AbstractJob<EntityAIWorkCaravanLeader, JobCaravanLeader>
{
    /** 行程中单笔交易的状态（用于日志展示与交易确认机制）。 */
    public enum TripStatus
    {
        /** 进行中：尚未执行。 */
        PENDING,
        /** 已完成：物品齐全并完成交易。 */
        COMPLETED,
        /** 失败：物品不全，未进行交易。 */
        FAILED
    }

    /**
     * 一次行程中的单个交易订单：交易列表中的索引 + 交易内容（成本/成果/目标位置）
     * + 村民 UUID + 当前状态。同一 offerIndex 可出现多次（按交易数量复制）。
     */
    public record TripTrade(UUID villagerId, int offerIndex, TradeRecord trade, TripStatus status)
    {
        private static final String TAG_VILLAGER = "villagerId";
        private static final String TAG_INDEX = "index";
        private static final String TAG_TRADE = "trade";
        private static final String TAG_STATUS = "status";

        public CompoundTag save(final HolderLookup.Provider provider)
        {
            final CompoundTag tag = new CompoundTag();
            tag.putUUID(TAG_VILLAGER, villagerId);
            tag.putInt(TAG_INDEX, offerIndex);
            tag.put(TAG_TRADE, TradeRecord.CODEC.encodeStart(NbtOps.INSTANCE, trade).getOrThrow());
            tag.putInt(TAG_STATUS, status.ordinal());
            return tag;
        }

        public static TripTrade load(final HolderLookup.Provider provider, final CompoundTag tag)
        {
            return new TripTrade(
                tag.getUUID(TAG_VILLAGER),
                tag.getInt(TAG_INDEX),
                TradeRecord.CODEC.parse(NbtOps.INSTANCE, tag.get(TAG_TRADE)).result().orElse(null),
                statusFromOrdinal(tag.getInt(TAG_STATUS)));
        }

        public void toBuffer(final RegistryFriendlyByteBuf buffer)
        {
            buffer.writeUUID(villagerId);
            buffer.writeVarInt(offerIndex);
            TradeRecord.STREAM_CODEC.encode(buffer, trade);
            buffer.writeVarInt(status.ordinal());
        }

        public static TripTrade fromBuffer(final RegistryFriendlyByteBuf buffer)
        {
            return new TripTrade(
                buffer.readUUID(),
                buffer.readVarInt(),
                TradeRecord.STREAM_CODEC.decode(buffer),
                statusFromOrdinal(buffer.readVarInt()));
        }

        private static TripStatus statusFromOrdinal(final int ordinal)
        {
            return ordinal >= 0 && ordinal < TripStatus.values().length
                ? TripStatus.values()[ordinal]
                : TripStatus.PENDING;
        }
    }

    /** 商队领袖当前状态（日志选项卡展示）。 */
    public enum CaravanStatus
    {
        /** 等待物品：备货阶段，还没有任何交易被满足。 */
        WAITING_ITEMS,
        /** 准备出发：已有交易被满足，处于 400 刻倒计时。 */
        READY_TO_DEPART,
        /** 交易中：赶路/交易/消失/回归。 */
        TRADING
    }

    public enum AwayPhase
    {
        /** 去程：距离倒数中。 */
        OUTBOUND,
        /** 交易中：在目标区域停留（剩余可执行交易数 × 80 刻）。 */
        TRADING,
        /** 回程：距离倒数中。 */
        RETURNING
    }

    public enum CampStatus
    {
        /** 白天移动（旅行中）。 */
        TRAVEL,
        /** 夜间（日落 ~ 殖民地工作时间结束）持火把移动（夜行中）。 */
        NIGHT_TRAVEL,
        /** 停止移动，有帐篷扎营（扎营中）。 */
        CAMP,
        /** 停止移动，无帐篷露宿（露宿中）。 */
        ROUGH,
        /** 交易中（到达交易目的地，停留交易）。 */
        TRADING
    }

    private static final String TAG_AWAY = "away";
    private static final String TAG_AWAY_PHASE = "awayPhase";
    private static final String TAG_AWAY_DISTANCE = "awayDistance";
    private static final String TAG_AWAY_MAX_DISTANCE = "awayMaxDistance";
    private static final String TAG_AWAY_TRADE_TICKS = "awayTradeTicks";
    private static final String TAG_AWAY_POS = "awayPos";
    private static final String TAG_AWAY_ORIGIN_POS = "awayOriginPos";
    private static final String TAG_AWAY_RETURN_POS = "awayReturnPos";
    private static final String TAG_AWAY_COLONY_ENTRY = "awayColonyEntry";
    private static final String TAG_AWAY_COLONY_EXIT = "awayColonyExit";
    private static final String TAG_AWAY_COLONY_ENTRY_REACHED = "awayColonyEntryReached";
    private static final String TAG_TRIP = "trip";
    private static final String TAG_SUPPLIES = "supplies";
    private static final String TAG_RESULTS = "results";
    private static final String TAG_NIGHT_ILLNESS = "nightIllness";

    /** 交易阶段每笔交易停留的游戏刻（与 AI 的 TRADE_WAIT_TICKS 保持一致）。 */
    private static final int AWAY_TRADE_TICKS_PER_TRADE = 80;
    private static final int MIN_TRADE_DELAY_TICKS = 20;
    private static final double TRADE_DELAY_REDUCTION_PER_MEMBER = 0.05;
    private static final double BASE_MOVEMENT_SPEED = 0.3;
    private static final double BONUS_SPEED_PER_AGILITY_LEVEL = 0.003D;

    /** 是否已消失（隐形，等待去程/回程模拟结束）。 */
    private boolean away;
    /** 消失期间所处阶段：去程/交易中/回程。 */
    private AwayPhase awayPhase = AwayPhase.OUTBOUND;
    /** 剩余距离（格），每 20 游戏刻减少 10 格。 */
    private int awayDistance;
    /** 去程开始时记录的初始距离，回程时重置回该值。 */
    private int awayMaxDistance;
    /** “交易中”阶段剩余停留时间（游戏刻）。 */
    private int awayTradeTicks;
    private BlockPos awayPos;
    private BlockPos awayOriginPos;
    private BlockPos awayReturnPos;
    private BlockPos awayColonyEntry;
    private BlockPos awayColonyExit;
    private boolean awayColonyEntryReached;
    public static final int TRADE_PACK_RADIUS = 100;
    /** 本次行程剩余待执行的订单。 */
    private final List<TripTrade> trip = new ArrayList<>();
    /** 消失期间保存的随身补给。 */
    private final List<ItemStack> supplies = new ArrayList<>();
    /** 模拟的交易成果（去程到达时生成，回归后放入小屋）。 */
    private final List<ItemStack> results = new ArrayList<>();
    /** 当前状态（日志展示用，不持久化）。 */
    private CaravanStatus status = CaravanStatus.WAITING_ITEMS;
    /**
     *  （走向交易目标=去程、等待交易=交易中、回小屋=回程），消失时用 awayPhase。 */
    private AwayPhase displayPhase = AwayPhase.OUTBOUND;
    private boolean resting;
    private CampStatus campStatus = CampStatus.TRAVEL;
    /**
     *  （key = 市民 UUID，值 = 0..100，返程后统一结算）。 */
    private final Map<UUID, Integer> nightIllnessChance = new HashMap<>();

    public JobCaravanLeader(final ICitizenData citizen)
    {
        super(citizen);
    }

    @Override
    public EntityAIWorkCaravanLeader generateAI()
    {
        return new EntityAIWorkCaravanLeader(this);
    }

    /**
     * 参考快递员 JobDeliveryman.onLevelUp：向 MOVEMENT_SPEED 添加
     * ADD_VALUE 修饰符，数值 = 敏捷等级 × 0.003。
     */
    @Override
    public void onLevelUp()
    {
        applySpeedBonus();
    }

    public void applySpeedBonus()
    {
        getCitizen().getEntity().ifPresent(entity ->
        {
            final int agility = getCitizen().getCitizenSkillHandler().getLevel(Skill.Agility);
            final AttributeModifier modifier = new AttributeModifier(
                CitizenConstants.SKILL_BONUS_ADD_NAME,
                agility * BONUS_SPEED_PER_AGILITY_LEVEL,
                AttributeModifier.Operation.ADD_VALUE);
            AttributeModifierUtils.addModifier(entity, modifier, Attributes.MOVEMENT_SPEED);
        });
    }

    @Override
    public ResourceLocation getModel()
    {
        return ModModelTypes.COURIER_ID;
    }

    public boolean isAway()
    {
        return away;
    }

    public AwayPhase getAwayPhase()
    {
        return awayPhase;
    }

    public int getAwayDistance()
    {
        return awayDistance;
    }

    public int getAwayMaxDistance()
    {
        return awayMaxDistance;
    }

    /** “交易中”阶段剩余停留时间（游戏刻）。 */
    public int getAwayTradeTicks()
    {
        return awayTradeTicks;
    }

    public List<TripTrade> getTrip()
    {
        return trip;
    }

    public List<ItemStack> getSupplies()
    {
        return supplies;
    }

    public List<ItemStack> getResults()
    {
        return results;
    }

    public CaravanStatus getStatus()
    {
        return status;
    }

    public void setStatus(final CaravanStatus status)
    {
        this.status = status;
    }

    public AwayPhase getDisplayPhase()
    {
        return away ? awayPhase : displayPhase;
    }

    public void setDisplayPhase(final AwayPhase displayPhase)
    {
        this.displayPhase = displayPhase;
    }

    public boolean isResting()
    {
        return resting;
    }

    public void setResting(final boolean resting)
    {
        this.resting = resting;
    }

    public CampStatus getCampStatus()
    {
        return campStatus;
    }

    public void setCampStatus(final CampStatus status)
    {
        campStatus = status;
        resting = status == CampStatus.CAMP || status == CampStatus.ROUGH;
    }

    public int getNightIllnessChance(final UUID citizenId)
    {
        return nightIllnessChance.getOrDefault(citizenId, 0);
    }

    public void addNightIllnessChance(final UUID citizenId, final int delta)
    {
        nightIllnessChance.put(
            citizenId,
            Math.min(100, getNightIllnessChance(citizenId) + delta));
    }

    public Map<UUID, Integer> takeNightIllnessChances()
    {
        final Map<UUID, Integer> copy = new HashMap<>(nightIllnessChance);
        nightIllnessChance.clear();
        return copy;
    }

    public int getMaxNightIllnessChance()
    {
        int max = 0;
        for (final int value : nightIllnessChance.values())
        {
            max = Math.max(max, value);
        }
        return max;
    }

    /** 是否有进行中的行程（含消失中）。 */
    public boolean hasActiveTrip()
    {
        return away || !trip.isEmpty();
    }

    /** 开始新行程：一次尝试列表中的全部订单。 */
    public void startTrip(final List<TripTrade> offers)
    {
        trip.clear();
        trip.addAll(offers);
        away = false;
        awayPhase = AwayPhase.OUTBOUND;
        awayDistance = 0;
        awayMaxDistance = 0;
        awayTradeTicks = 0;
        awayColonyEntry = null;
        awayColonyExit = null;
        awayColonyEntryReached = false;
        displayPhase = AwayPhase.OUTBOUND;
    }

    /** 更新一笔交易的行程状态（替换为新记录，保持列表用于日志展示）。 */
    public void markTripStatus(final TripTrade offer, final TripStatus status)
    {
        final int index = trip.indexOf(offer);
        if (index >= 0)
        {
            trip.set(index, new TripTrade(offer.villagerId(), offer.offerIndex(), offer.trade(), status));
        }
    }

    /** 行程中是否还有进行中（PENDING）的交易。 */
    public boolean hasPendingTripTrades()
    {
        return trip.stream().anyMatch(offer -> offer.status() == TripStatus.PENDING);
    }

    /**
     * 进入消失状态：
     * 记录出发位置与模拟起点，首段去程距离 = 当前位置到最近剩余目标的距离；
     * 之后每完成一段交易，由 {@link #afterTradingSettled()} 决定下一段去程或回程。
     */
    public void vanish(final int distance, final BlockPos originPos)
    {
        away = true;
        awayPhase = AwayPhase.OUTBOUND;
        displayPhase = AwayPhase.OUTBOUND;
        awayDistance = distance;
        awayMaxDistance = distance;
        awayTradeTicks = 0;
        awayPos = originPos;
        awayOriginPos = originPos;
        awayReturnPos = null;
        final TripTrade first = nearestPendingTarget(awayPos);
        if (first != null)
        {
            setupColonyCrossing(first.trade().villagePos());
        }
        else
        {
            awayColonyEntry = null;
            awayColonyExit = null;
            awayColonyEntryReached = false;
        }
    }

    /**
     * 每 20 游戏刻调用一次：
     * 去程：距离减 10 格，归零 → 转为【交易中】，停留 = 本段 100 格内可执行交易数 × 每笔延迟；
     * 交易中：剩余刻数减 20，归零 → 标记为【回程】（AI 结算后调用
     *         {@link #afterTradingSettled()} 决定下一段去程或真正的回程）；
     * 回程：距离减 10 格，归零 → 返回 true（归来）。
     */
    public boolean tickAway()
    {
        if (!away)
        {
            return false;
        }
        switch (awayPhase)
        {
            case OUTBOUND:
                // 则停在 A 点（实体现身步行至出口 B 后继续倒数，见 finishColonyWalk）。
                if (!awayColonyEntryReached && awayColonyEntry != null && awayColonyExit != null)
                {
                    final TripTrade legEndTrade = nearestPendingTarget(awayPos);
                    if (legEndTrade != null)
                    {
                        final int entryRemaining = blockDistance(awayColonyEntry, legEndTrade.trade().villagePos());
                        if (awayDistance <= entryRemaining)
                        {
                            awayPos = awayColonyEntry;
                            awayDistance = entryRemaining;
                            awayColonyEntryReached = true;
                            return false;
                        }
                    }
                }
                // 与殖民地内移动的速度公式（基础 0.3 + 敏捷×0.003，上限 0.5）一致。
                awayDistance = Math.max(0, awayDistance - simulatedDistancePerStep());
                if (awayDistance <= 0)
                {
                    // 到达目标区域：进入“交易中”，停留时间 = 本段 100 格内交易数 × 每笔延迟。
                    awayPhase = AwayPhase.TRADING;
                    // 更新模拟位置为本段到达的目标（用于结算 100 格内交易与计算下一段距离）。
                    final TripTrade reached = nearestPendingTarget(awayPos);
                    if (reached != null)
                    {
                        awayPos = reached.trade().villagePos();
                    }
                    awayTradeTicks = pendingCountNear(awayPos, TRADE_PACK_RADIUS) * awayTradeDelayTicks();
                }
                return false;
            case TRADING:
                awayTradeTicks = Math.max(0, awayTradeTicks - 20);
                if (awayTradeTicks <= 0)
                {
                    // 先标记为回程（占位）；AI 结算本段交易后调用 afterTradingSettled()
                    // 决定是进入下一段去程还是真正的回程。
                    awayPhase = AwayPhase.RETURNING;
                    awayDistance = 0;
                }
                return false;
            case RETURNING:
                awayDistance = Math.max(0, awayDistance - simulatedDistancePerStep());
                if (awayDistance <= 0)
                {
                    return true;
                }
                return false;
        }
        return false;
    }

    /**
     * 若仍有未完成交易，则模拟前往下一个最近的交易地点（新一段去程，
     * 显示“去程 剩余XX格”）；否则模拟返回（末段位置 → 消失时的原始位置）。
     */
    public void afterTradingSettled()
    {
        final TripTrade next = nearestPendingTarget(awayPos);
        if (next != null)
        {
            awayPhase = AwayPhase.OUTBOUND;
            awayMaxDistance = blockDistance(awayPos, next.trade().villagePos());
            awayDistance = awayMaxDistance;
            setupColonyCrossing(next.trade().villagePos());
        }
        else
        {
            awayPhase = AwayPhase.RETURNING;
            // 最后目标 → 小屋直线路径首次进入殖民地领地的入口点 A；
            // 计算失败时回退到旧的“连线与边界交点”算法。
            awayReturnPos = findColonyEntry(awayPos, hutPosition());
            if (awayReturnPos == null)
            {
                awayReturnPos = boundaryIntersectionTowardsHut(awayPos);
            }
            final BlockPos returnTarget = awayReturnPos != null ? awayReturnPos : awayOriginPos;
            awayMaxDistance = returnTarget != null ? blockDistance(awayPos, returnTarget) : 0;
            awayDistance = awayMaxDistance;
            // 回程终点在殖民地边界上（终点本身在殖民地内），无需穿越机制。
            awayColonyEntry = null;
            awayColonyExit = null;
            awayColonyEntryReached = false;
        }
    }

    /** 当前模拟位置（多段模拟用；未消失时为 null）。 */
    public BlockPos getAwayPos()
    {
        return awayPos;
    }

    /** 消失时的出发点（回程终点；未消失时为 null）。 */
    public BlockPos getAwayOriginPos()
    {
        return awayOriginPos;
    }

    public BlockPos getAwayReturnPos()
    {
        return awayReturnPos;
    }

    public BlockPos getAwayColonyEntry()
    {
        return awayColonyEntry;
    }

    public BlockPos getAwayColonyExit()
    {
        return awayColonyExit;
    }

    public boolean isWalkingThroughColony()
    {
        return awayColonyEntryReached && awayColonyEntry != null && awayColonyExit != null;
    }

    public BlockPos getAwayLegStart()
    {
        return awayPos;
    }

    public BlockPos getAwayLegEnd()
    {
        if (awayPhase == AwayPhase.RETURNING)
        {
            return awayReturnPos != null ? awayReturnPos : awayOriginPos;
        }
        final TripTrade next = nearestPendingTarget(awayPos);
        return next != null ? next.trade().villagePos() : awayPos;
    }

    public List<BlockPos> getAwayRoute()
    {
        final List<BlockPos> route = new ArrayList<>();
        if (awayOriginPos != null)
        {
            route.add(awayOriginPos);
        }
        final List<TripTrade> remaining = new ArrayList<>();
        for (final TripTrade offer : trip)
        {
            if (offer.status() == TripStatus.PENDING)
            {
                remaining.add(offer);
            }
        }
        BlockPos from = awayPos != null ? awayPos : awayOriginPos;
        while (!remaining.isEmpty())
        {
            final BlockPos cursor = from;
            TripTrade nearest = null;
            double best = Double.MAX_VALUE;
            for (final TripTrade offer : remaining)
            {
                final double d = offer.trade().villagePos().distSqr(cursor);
                if (d < best)
                {
                    best = d;
                    nearest = offer;
                }
            }
            if (nearest == null)
            {
                break;
            }
            remaining.remove(nearest);
            route.add(nearest.trade().villagePos());
            from = nearest.trade().villagePos();
        }
        return route;
    }

    /** 从指定位置出发，统计本段 100 格内仍未完成的交易数。 */
    public int pendingCountNear(final BlockPos pos, final int radius)
    {
        if (pos == null)
        {
            return 0;
        }
        final long radiusSq = (long) radius * radius;
        return (int) trip.stream()
            .filter(offer -> offer.status() == TripStatus.PENDING)
            .filter(offer -> offer.trade().villagePos().distSqr(pos) <= radiusSq)
            .count();
    }

    /** 从指定位置出发，距离最近的未完成交易目标（无则 null）。 */
    public TripTrade nearestPendingTarget(final BlockPos from)
    {
        if (from == null)
        {
            return null;
        }
        return trip.stream()
            .filter(offer -> offer.status() == TripStatus.PENDING)
            .min(Comparator.comparingDouble(o -> o.trade().villagePos().distSqr(from)))
            .orElse(null);
    }

    private static int blockDistance(final BlockPos a, final BlockPos b)
    {
        return (int) Math.round(Math.sqrt(Math.max(1.0, a.distSqr(b))));
    }

    /**
     * 殖民地真实领地的边界位置——从小屋沿目标方向逐步向外扫描，
     * 返回最后一个仍属于殖民地领地（区块归属）的位置。
     * 领地边界变化时，这里每次按需重新计算。
     */
    private BlockPos boundaryIntersectionTowardsHut(final BlockPos lastTarget)
    {
        try
        {
            final BlockPos hut = hutPosition();
            if (hut == null)
            {
                return null;
            }
            final double dx = lastTarget.getX() - hut.getX();
            final double dz = lastTarget.getZ() - hut.getZ();
            final double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0)
            {
                return null;
            }
            final double nx = dx / length;
            final double nz = dz / length;
            final Level world = getColony().getWorld();
            if (!(world instanceof net.minecraft.server.level.ServerLevel))
            {
                return null;
            }
            BlockPos inside = hut;
            // 从小屋沿目标方向逐步向外扫描（步长 2 格，上限 512 格），
            // 直到离开殖民地领地为止——该点即回程目的地。
            for (int step = 1; step <= 512; step += 2)
            {
                final BlockPos probe = new BlockPos(
                    hut.getX() + (int) Math.round(nx * step),
                    lastTarget.getY(),
                    hut.getZ() + (int) Math.round(nz * step));
                if (!getColony().isCoordInColony(world, probe))
                {
                    break;
                }
                inside = probe;
            }
            return inside;
        }
        catch (final Exception ignored)
        {
            return null;
        }
    }

    /** 商队小屋方块位置（未找到工作建筑时回退殖民地中心）。 */
    private BlockPos hutPosition()
    {
        return getCitizen().getWorkBuilding() != null
            ? getCitizen().getWorkBuilding().getPosition()
            : getColony().getCenter();
    }

    /**
     * 以 16 格步长向外扫描降低开销；确认进入殖民地后，再以 2 格步长
     * 反向精化出边界点（A = 进入点，B = 离开点）。
     */
    private void setupColonyCrossing(final BlockPos to)
    {
        awayColonyEntry = null;
        awayColonyExit = null;
        awayColonyEntryReached = false;
        if (to == null || awayPos == null)
        {
            return;
        }
        final ColonyCrossing crossing = findColonyCrossing(awayPos, to);
        if (crossing != null)
        {
            awayColonyEntry = crossing.entry();
            awayColonyExit = crossing.exit();
        }
    }

    /** 本段路径的殖民地穿越点（A = 进入点，B = 离开点）。 */
    private record ColonyCrossing(BlockPos entry, BlockPos exit)
    {
    }

    /**
     * 沿 from→to 直线检测殖民地穿越：
     * <ul>
     *   <li>从起点起以 16 格步长扫描，找到第一个在殖民地内的探针；</li>
     *   <li>从该探针以 2 格步长反向精化，得到进入点 A；</li>
     *   <li>继续以 16 格步长前进，找到第一个在殖民地外的探针，再以 2 格
     *       步长反向精化得到离开点 B；</li>
     *   <li>目的地本身在殖民地内（如回程终点）时视为不穿越。</li>
     * </ul>
     */
    /**
     * 以 16 格步长扫描降低开销，确认进入后以 2 格步长反向精化边界。
     */
    private BlockPos findColonyEntry(final BlockPos from, final BlockPos to)
    {
        try
        {
            final Level world = getColony().getWorld();
            if (!(world instanceof ServerLevel))
            {
                return null;
            }
            final double dx = to.getX() - from.getX();
            final double dz = to.getZ() - from.getZ();
            final double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0)
            {
                return null;
            }
            final double nx = dx / length;
            final double nz = dz / length;
            final int y = from.getY();

            // 16 格步长：跳过起点本身（起点可能位于边界上），向外扫描进入点。
            BlockPos firstInside = null;
            for (double step = 16; step <= length; step += 16)
            {
                final BlockPos probe = probeAt(from, nx, nz, y, step);
                if (getColony().isCoordInColony(world, probe))
                {
                    firstInside = probe;
                    break;
                }
            }
            if (firstInside == null)
            {
                return null; // 路径未进入殖民地
            }
            return refineEntry(world, from, firstInside, nx, nz, y);
        }
        catch (final Exception ignored)
        {
            return null;
        }
    }

    /** 沿 from→to 直线检测殖民地穿越（进入点 A + 离开点 B）。 */
    private ColonyCrossing findColonyCrossing(final BlockPos from, final BlockPos to)
    {
        try
        {
            final Level world = getColony().getWorld();
            if (!(world instanceof ServerLevel))
            {
                return null;
            }
            final double dx = to.getX() - from.getX();
            final double dz = to.getZ() - from.getZ();
            final double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0)
            {
                return null;
            }
            final double nx = dx / length;
            final double nz = dz / length;
            final int y = from.getY();
            final BlockPos entry = findColonyEntry(from, to);
            if (entry == null)
            {
                return null;
            }
            final double firstInsideDist = Math.sqrt(entry.distSqr(from));

            // 继续向前寻找离开点。
            BlockPos firstOutside = null;
            for (double step = firstInsideDist + 16; ; step += 16)
            {
                final double clamped = Math.min(step, length);
                final BlockPos probe = probeAt(from, nx, nz, y, clamped);
                if (!getColony().isCoordInColony(world, probe))
                {
                    firstOutside = probe;
                    break;
                }
                if (clamped >= length)
                {
                    break; // 到达目的地仍全部在殖民地内：无离开点，不算穿越
                }
            }
            if (firstOutside == null)
            {
                return null;
            }
            final BlockPos exit = refineExit(world, from, firstOutside, nx, nz, y);
            return new ColonyCrossing(entry, exit);
        }
        catch (final Exception ignored)
        {
            return null;
        }
    }

    /** 从首个“殖民地内”探针以 2 格步长反向搜寻，返回最后一个仍在殖民地内的点（入口 A）。 */
    private BlockPos refineEntry(
        final Level world,
        final BlockPos from,
        final BlockPos firstInside,
        final double nx,
        final double nz,
        final int y)
    {
        BlockPos lastInside = firstInside;
        final double anchorDist = Math.sqrt(firstInside.distSqr(from));
        for (double back = 2; back <= anchorDist; back += 2)
        {
            final BlockPos probe = probeAt(from, nx, nz, y, anchorDist - back);
            if (!getColony().isCoordInColony(world, probe))
            {
                break;
            }
            lastInside = probe;
        }
        return lastInside;
    }

    /** 从首个“殖民地外”探针以 2 格步长反向搜寻，返回遇到的第一个在殖民地内的点（出口 B）。 */
    private BlockPos refineExit(
        final Level world,
        final BlockPos from,
        final BlockPos firstOutside,
        final double nx,
        final double nz,
        final int y)
    {
        final double anchorDist = Math.sqrt(firstOutside.distSqr(from));
        for (double back = 2; back <= anchorDist; back += 2)
        {
            final BlockPos probe = probeAt(from, nx, nz, y, anchorDist - back);
            if (getColony().isCoordInColony(world, probe))
            {
                return probe;
            }
        }
        return firstOutside;
    }

    /** 沿 from 方向距 dist 格处的探针坐标（y 取起点高度）。 */
    private static BlockPos probeAt(
        final BlockPos from,
        final double nx,
        final double nz,
        final int y,
        final double dist)
    {
        return new BlockPos(
            from.getX() + (int) Math.round(nx * dist),
            y,
            from.getZ() + (int) Math.round(nz * dist));
    }

    private int awayTradeDelayTicks()
    {
        return tradeDelayTicks();
    }

    /**
     * 领袖智力每 10 级降低 5%；每名商队成员再降低 5%（5 名时 -25%）；
     * 两者相乘，下限为原设计（80 刻）的 25% = 20 刻。
     */
    public int tradeDelayTicks()
    {
        final int intelligence = getCitizen().getCitizenSkillHandler().getLevel(Skill.Intelligence);
        final double intelligenceFactor = Math.max(0.5, 1.0 - intelligence * 0.005D);
        final double memberFactor = Math.max(0.0, 1.0 - TRADE_DELAY_REDUCTION_PER_MEMBER * caravanMemberCount());
        return (int) Math.max(MIN_TRADE_DELAY_TICKS,
            Math.round(AWAY_TRADE_TICKS_PER_TRADE * intelligenceFactor * memberFactor));
    }

    public int caravanMemberCount()
    {
        int count = 0;
        try
        {
            final IBuilding work = getCitizen().getWorkBuilding();
            if (work != null)
            {
                for (final WorkerBuildingModule module : work.getModulesByType(WorkerBuildingModule.class))
                {
                    if (module.getJobEntry().getKey().equals(CaravanMod.JOB_CARAVAN_MEMBER.getKey()))
                    {
                        count = module.getAssignedCitizen().size();
                        break;
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 建筑/模块未就绪时按 0 名成员处理。
        }
        return count;
    }

    public int lowestAgility()
    {
        int lowest = getCitizen().getCitizenSkillHandler().getLevel(Skill.Agility);
        try
        {
            final IBuilding work = getCitizen().getWorkBuilding();
            if (work != null)
            {
                for (final WorkerBuildingModule module : work.getModulesByType(WorkerBuildingModule.class))
                {
                    if (module.getJobEntry().getKey().equals(CaravanMod.JOB_CARAVAN_MEMBER.getKey()))
                    {
                        for (final ICitizenData member : module.getAssignedCitizen())
                        {
                            lowest = Math.min(lowest, member.getCitizenSkillHandler().getLevel(Skill.Agility));
                        }
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 忽略，使用领袖自身的敏捷。
        }
        return lowest;
    }

    public int hungryCount()
    {
        int count = isHungry(getCitizen()) ? 1 : 0;
        try
        {
            final IBuilding work = getCitizen().getWorkBuilding();
            if (work != null)
            {
                for (final WorkerBuildingModule module : work.getModulesByType(WorkerBuildingModule.class))
                {
                    if (module.getJobEntry().getKey().equals(CaravanMod.JOB_CARAVAN_MEMBER.getKey()))
                    {
                        for (final ICitizenData member : module.getAssignedCitizen())
                        {
                            if (isHungry(member))
                            {
                                count++;
                            }
                        }
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 忽略，按当前已统计人数处理。
        }
        return count;
    }

    private static boolean isHungry(final ICitizenData data)
    {
        try
        {
            return ((com.minecolonies.core.colony.CitizenData) data).getSaturation() <= 0.0D;
        }
        catch (final Exception ignored)
        {
            return false;
        }
    }

    /**
     * 基准 10 格 ×（最低敏捷对应的速度 / 基础速度），速度 = min(0.5, 0.3 + 敏捷×0.003)。
     */
    public int simulatedDistancePerStep()
    {
        double speed = Math.min(0.5, BASE_MOVEMENT_SPEED + lowestAgility() * 0.003D);
        speed *= Math.max(0.0D, 1.0D - hungryCount() * 0.05D);
        speed *= 1.0D + 0.1D * com.example.caravan.colony.buildings.CaravanGuardHelper.caravanGuardCount(getColony());
        return Math.max(1, (int) Math.round(10.0 * speed / BASE_MOVEMENT_SPEED));
    }

    /** 商队领袖从消失位置归来。 */
    public void reappear()
    {
        away = false;
        awayPhase = AwayPhase.OUTBOUND;
        // 而非“旅行中”——直到下一次出发才切回“旅行中”。
        displayPhase = AwayPhase.RETURNING;
        awayDistance = 0;
        awayMaxDistance = 0;
        awayTradeTicks = 0;
        awayPos = null;
        awayOriginPos = null;
        awayReturnPos = null;
        awayColonyEntry = null;
        awayColonyExit = null;
        awayColonyEntryReached = false;
    }

    /**
     * 从剩余距离中扣除步行段（A→B），模拟位置推进到 B，继续去程倒数。
     */
    public void finishColonyWalk()
    {
        if (awayColonyEntry != null && awayColonyExit != null)
        {
            awayDistance = Math.max(0, awayDistance - blockDistance(awayColonyEntry, awayColonyExit));
        }
        if (awayColonyExit != null)
        {
            awayPos = awayColonyExit;
        }
        awayColonyEntry = null;
        awayColonyExit = null;
        awayColonyEntryReached = false;
    }

    /** 清除已完成的行程数据。 */
    public void finishTrip()
    {
        trip.clear();
        supplies.clear();
        results.clear();
        // 空闲/睡觉/备货阶段不显示。
        status = CaravanStatus.WAITING_ITEMS;
    }

    /** 序列化到 NBT：随市民数据保存，重载世界后 AI 能恢复到正确状态。 */
    @Override
    public CompoundTag serializeNBT(final HolderLookup.Provider provider)
    {
        final CompoundTag tag = super.serializeNBT(provider);
        tag.putBoolean(TAG_AWAY, away);
        tag.putInt(TAG_AWAY_PHASE, awayPhase.ordinal());
        tag.putInt(TAG_AWAY_DISTANCE, awayDistance);
        tag.putInt(TAG_AWAY_MAX_DISTANCE, awayMaxDistance);
        tag.putInt(TAG_AWAY_TRADE_TICKS, awayTradeTicks);
        if (awayPos != null)
        {
            tag.putLong(TAG_AWAY_POS, awayPos.asLong());
        }
        if (awayOriginPos != null)
        {
            tag.putLong(TAG_AWAY_ORIGIN_POS, awayOriginPos.asLong());
        }
        if (awayReturnPos != null)
        {
            tag.putLong(TAG_AWAY_RETURN_POS, awayReturnPos.asLong());
        }
        if (awayColonyEntry != null)
        {
            tag.putLong(TAG_AWAY_COLONY_ENTRY, awayColonyEntry.asLong());
        }
        if (awayColonyExit != null)
        {
            tag.putLong(TAG_AWAY_COLONY_EXIT, awayColonyExit.asLong());
        }
        tag.putBoolean(TAG_AWAY_COLONY_ENTRY_REACHED, awayColonyEntryReached);

        final ListTag tripList = new ListTag();
        for (final TripTrade offer : trip)
        {
            tripList.add(offer.save(provider));
        }
        tag.put(TAG_TRIP, tripList);
        tag.put(TAG_SUPPLIES, serializeStacks(provider, supplies));
        tag.put(TAG_RESULTS, serializeStacks(provider, results));
        final CompoundTag illness = new CompoundTag();
        for (final Map.Entry<UUID, Integer> entry : nightIllnessChance.entrySet())
        {
            illness.putInt(entry.getKey().toString(), entry.getValue());
        }
        tag.put(TAG_NIGHT_ILLNESS, illness);
        return tag;
    }

    /** 从 NBT 恢复状态。 */
    @Override
    public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag tag)
    {
        super.deserializeNBT(provider, tag);
        away = tag.getBoolean(TAG_AWAY);
        final int phaseOrdinal = tag.getInt(TAG_AWAY_PHASE);
        awayPhase = phaseOrdinal >= 0 && phaseOrdinal < AwayPhase.values().length
            ? AwayPhase.values()[phaseOrdinal]
            : AwayPhase.OUTBOUND;
        awayDistance = tag.getInt(TAG_AWAY_DISTANCE);
        awayMaxDistance = tag.getInt(TAG_AWAY_MAX_DISTANCE);
        awayTradeTicks = tag.getInt(TAG_AWAY_TRADE_TICKS);
        awayPos = tag.contains(TAG_AWAY_POS) ? BlockPos.of(tag.getLong(TAG_AWAY_POS)) : null;
        awayOriginPos = tag.contains(TAG_AWAY_ORIGIN_POS) ? BlockPos.of(tag.getLong(TAG_AWAY_ORIGIN_POS)) : null;
        awayReturnPos = tag.contains(TAG_AWAY_RETURN_POS) ? BlockPos.of(tag.getLong(TAG_AWAY_RETURN_POS)) : null;
        awayColonyEntry = tag.contains(TAG_AWAY_COLONY_ENTRY) ? BlockPos.of(tag.getLong(TAG_AWAY_COLONY_ENTRY)) : null;
        awayColonyExit = tag.contains(TAG_AWAY_COLONY_EXIT) ? BlockPos.of(tag.getLong(TAG_AWAY_COLONY_EXIT)) : null;
        awayColonyEntryReached = tag.getBoolean(TAG_AWAY_COLONY_ENTRY_REACHED);

        trip.clear();
        for (final Tag element : tag.getList(TAG_TRIP, Tag.TAG_COMPOUND))
        {
            final TripTrade offer = TripTrade.load(provider, (CompoundTag) element);
            if (offer != null && offer.trade() != null)
            {
                trip.add(offer);
            }
        }
        supplies.clear();
        supplies.addAll(deserializeStacks(provider, tag.getList(TAG_SUPPLIES, Tag.TAG_COMPOUND)));
        results.clear();
        results.addAll(deserializeStacks(provider, tag.getList(TAG_RESULTS, Tag.TAG_COMPOUND)));
        nightIllnessChance.clear();
        final CompoundTag illness = tag.getCompound(TAG_NIGHT_ILLNESS);
        for (final String key : illness.getAllKeys())
        {
            try
            {
                nightIllnessChance.put(UUID.fromString(key), illness.getInt(key));
            }
            catch (final Exception ignored)
            {
                // 非法 UUID 键直接忽略。
            }
        }
    }

    private static ListTag serializeStacks(final HolderLookup.Provider provider, final List<ItemStack> stacks)
    {
        final ListTag list = new ListTag();
        for (final ItemStack stack : stacks)
        {
            list.add(stack.saveOptional(provider));
        }
        return list;
    }

    private static List<ItemStack> deserializeStacks(final HolderLookup.Provider provider, final ListTag list)
    {
        final List<ItemStack> stacks = new ArrayList<>();
        for (final Tag tag : list)
        {
            if (tag instanceof CompoundTag compound)
            {
                final ItemStack stack = ItemStack.parseOptional(provider, compound);
                if (!stack.isEmpty())
                {
                    stacks.add(stack);
                }
            }
        }
        return stacks;
    }
}
