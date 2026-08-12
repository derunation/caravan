package com.example.caravan.colony.buildings.modules;

import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.example.caravan.colony.jobs.JobCaravanLeader.TripStatus;
import com.example.caravan.colony.jobs.JobCaravanLeader.TripTrade;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.List;
import java.util.Objects;

/**
 * 商队小屋【日志】标签页的服务端数据源：
 * 同步商队领袖的目前状态（等待物品/准备出发/交易中）、是否消失
 * （去程/回程 + 剩余距离），以及本次行程的交易内容与完成情况。
 */
public class CaravanLogModule extends AbstractBuildingModule implements IPersistentModule, ITickingModule
{
    /** 需求（旅行地图）：上一殖民地刻同步给客户端的睡眠窗口状态。 */
    private Boolean lastSleepSync;
    /** 需求（旅行地图）：上一殖民地刻同步给客户端的夜行窗口状态。 */
    private Boolean lastNightSync;

    /**
     * 需求（旅行地图）：每殖民地刻检测时间状态（睡眠窗口/夜行窗口）变化——
     * 商队 AI 可能在夜晚被加速/跳过时从未 tick 到窗口，无法触发 resting 变化，
     * 因此由本模块直接按当前时间判定，并在窗口边界变化时 markDirty，
     * 确保客户端视图的 sleepTimeNow/resting 及时更新（与 AI 是否运行无关）。
     */
    @Override
    public void onColonyTick(final IColony colony)
    {
        try
        {
            final boolean sleepNow = isSleepTimeNow();
            final boolean nightNow = isNightTravelNow();
            if (lastSleepSync == null || sleepNow != lastSleepSync)
            {
                lastSleepSync = sleepNow;
                // 睡眠窗口边界变化：标记建筑脏，让客户端视图尽快刷新
                // （模拟状态由商队 AI 每殖民地刻决定并同步，此处仅兜底刷新）。
                getBuilding().markDirty();
            }
            if (lastNightSync == null || nightNow != lastNightSync)
            {
                lastNightSync = nightNow;
                getBuilding().markDirty();
            }
        }
        catch (final Exception ignored)
        {
            // 检测失败不影响殖民地 tick。
        }
    }

    /** 需求（旅行地图）：当前是否处于夜间赶路窗口（日落 ~ 睡眠时间之间）。 */
    private boolean isNightTravelNow()
    {
        if (isSleepTimeNow())
        {
            return false;
        }
        try
        {
            final var world = getBuilding().getColony().getWorld();
            if (world == null)
            {
                return false;
            }
            // 修复bug：昼夜判定用真实 dayTime（与商队 AI 一致，避免睡觉跳夜晚后偏移）。
            final long dayTime = world.getDayTime() % 24000L;
            long sunset = 12000L;
            try
            {
                sunset = com.teamtea.eclipticseasons.api.util.EclipticUtil.getNightTime(world);
            }
            catch (final Throwable ignored)
            {
                // 未安装 EclipticSeasons 时使用原版日落。
            }
            return dayTime >= sunset;
        }
        catch (final Exception ignored)
        {
            return false;
        }
    }

    @Override
    public void serializeToView(final RegistryFriendlyByteBuf buffer)
    {
        final JobCaravanLeader job = findLeaderJob();
        if (job == null)
        {
            buffer.writeBoolean(false);
            return;
        }

        buffer.writeBoolean(true);
        buffer.writeBoolean(job.isAway());
        // 需求（扎营）：模拟旅行中是否处于原地休息（扎营）状态。
        buffer.writeBoolean(job.isResting());
        // 需求（模拟旅行状态机）：商队当前模拟状态（旅行中/夜行中/扎营中/露宿中/交易中）。
        buffer.writeVarInt(job.getCampStatus().ordinal());
        // 需求（旅行地图）：同步“当前是否处于殖民地睡眠时间窗口”——
        // resting 依赖商队 AI tick 到扎营分支；玩家睡觉跳过夜晚时 AI 可能从未
        // tick 到睡眠窗口，地图/日志无法显示“扎营中/露宿中”。此处用当前游戏时间
        // 直接判定（与白天长度/日落无关，仅取决于原扎营时间 + 研究加成）。
        buffer.writeBoolean(isSleepTimeNow());
        // 需求（穿越殖民地）：同步“正在穿越殖民地”（实体现身步行中）状态，
        // 供旅行地图标记在穿越期间跟随领袖实体位置。
        buffer.writeBoolean(job.isWalkingThroughColony());
        // 需求（旅行地图联动）：商队领袖的 NPC 贴图路径（用于头像图标）。
        // 修复崩溃：服务端 getTexture() 可能为 null（贴图在服务端不解析），
        // 为 null 时传空串，客户端自动回退到默认徽章图标。
        buffer.writeUtf(job.getCitizen().getEntity()
            .map(entity -> entity.getTexture())
            .filter(Objects::nonNull)
            .map(ResourceLocation::toString)
            .orElse(""));
        buffer.writeVarInt(job.getStatus().ordinal());
        // 需求（文本显示）：同步“展示相位”（未消失时由 AI 设置；消失时=awayPhase），
        // 客户端据此显示 旅行中/交易中/返回中。
        buffer.writeVarInt(job.getDisplayPhase().ordinal());
        // 需求（旅行地图联动）：同步领袖实体的当前实际位置——
        // 未消失时大地图标记跟随领袖走动；消失时该位置无意义（用 away 插值）。
        writePos(buffer, job.getCitizen().getEntity()
            .map(entity -> entity.blockPosition())
            .orElse(null));
        if (job.isAway())
        {
            // 需求（旅行地图联动）：初始距离用于计算移动进度。
            buffer.writeVarInt(Math.max(0, job.getAwayMaxDistance()));
            if (job.getAwayPhase() == JobCaravanLeader.AwayPhase.TRADING)
            {
                buffer.writeVarInt(Math.max(0, job.getAwayTradeTicks()));
            }
            else
            {
                buffer.writeVarInt(Math.max(0, job.getAwayDistance()));
            }
            // 需求（旅行地图联动）：同步模拟路线数据——出发点、当前段起点/终点、
            // 以及剩余停靠点列表（出发点 + 按访问顺序排列的剩余目标）。
            writePos(buffer, job.getAwayOriginPos());
            writePos(buffer, job.getAwayLegStart());
            writePos(buffer, job.getAwayLegEnd());
            final java.util.List<BlockPos> route = job.getAwayRoute();
            buffer.writeVarInt(route.size());
            for (final BlockPos pos : route)
            {
                writePos(buffer, pos);
            }
        }

        // 本次行程的交易内容：同一村民的同一交易（offerIndex）聚合为一个条目，
        // 记录购入（成本）与售出（成果）物品，以及“已完成份数 / 总份数”。
        final java.util.List<TripTrade> trip = job.getTrip();
        final java.util.LinkedHashMap<Integer, net.minecraft.world.item.ItemStack> results = new java.util.LinkedHashMap<>();
        final java.util.LinkedHashMap<Integer, java.util.List<net.minecraft.world.item.ItemStack>> costs = new java.util.LinkedHashMap<>();
        final java.util.Map<Integer, Integer> completed = new java.util.HashMap<>();
        final java.util.Map<Integer, Integer> totals = new java.util.HashMap<>();
        for (final TripTrade offer : trip)
        {
            results.putIfAbsent(offer.offerIndex(), offer.trade().result());
            costs.putIfAbsent(offer.offerIndex(), new java.util.ArrayList<>(offer.trade().costs()));
            completed.merge(offer.offerIndex(), offer.status() == TripStatus.COMPLETED ? 1 : 0, Integer::sum);
            totals.merge(offer.offerIndex(), 1, Integer::sum);
        }
        buffer.writeVarInt(results.size());
        // 需求（减值修复）：多条交易需要同一种售出品时，按行程顺序分配已有量——
        // 例如 3 笔交易各需 9 绿宝石、小屋+背包共 5 个，则依次显示 5/9、0/9、0/9，
        // 而不是每笔都重复显示 5/9（旧实现重复计算）。
        final java.util.Map<net.minecraft.world.item.Item, Integer> allocated = new java.util.HashMap<>();
        for (final java.util.Map.Entry<Integer, net.minecraft.world.item.ItemStack> entry : results.entrySet())
        {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, entry.getValue());
            final java.util.List<net.minecraft.world.item.ItemStack> offerCosts = costs.getOrDefault(entry.getKey(), java.util.List.of());
            final int totalCopies = totals.getOrDefault(entry.getKey(), 0);
            buffer.writeVarInt(offerCosts.size());
            for (final ItemStack cost : offerCosts)
            {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, cost);
                // 需求（备货供应比例）：同步每个成本的“已有供应量”（小屋+背包，
                // 截断到该交易的需要量；同一种售出品按行程顺序减值分配）。
                final int needed = cost.getCount() * totalCopies;
                final int already = allocated.getOrDefault(cost.getItem(), 0);
                final int supplied = Math.max(0, Math.min(needed, countAvailable(cost) - already));
                allocated.put(cost.getItem(), already + supplied);
                buffer.writeVarInt(supplied);
            }
            buffer.writeVarInt(completed.getOrDefault(entry.getKey(), 0));
            buffer.writeVarInt(totalCopies);
        }
        // 需求：同步“下一个目的地将要进行的第一条交易”的结果物品图标，
        // 供旅行地图标记使用；回程阶段无下一目的地时传空物品。
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, nextDestinationTradeIcon(job));
        // 需求（消耗品）：商队领袖/成员背包中的每顶帐篷独立图标（各含耐久度条），
        // 显示上限 3 顶（不与饥饿人数挂钩）。
        final java.util.List<ItemStack> tents = caravanTents(job);
        buffer.writeVarInt(tents.size());
        for (final ItemStack tent : tents)
        {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, tent);
        }
        // 需求（消耗品）：菜单选定的食物——每个堆叠一个图标（带数量角标）。
        final java.util.List<ItemStack> foods = caravanFoods(job);
        buffer.writeVarInt(foods.size());
        for (final ItemStack food : foods)
        {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, food);
        }
        // 需求（消耗品）：火把——每个堆叠一个图标（带数量角标）。
        final java.util.List<ItemStack> torches = caravanTorches(job);
        buffer.writeVarInt(torches.size());
        for (final ItemStack torch : torches)
        {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, torch);
        }
        // 需求（饥饿）：同步饥饿人数，客户端据此显示饱食度图标（满/空）与 Tooltip。
        buffer.writeVarInt(job.hungryCount());
    }

    /** 需求（旅行地图）：当前游戏时间是否处于“殖民地睡眠时间”窗口。 */
    private boolean isSleepTimeNow()
    {
        try
        {
            final var world = getBuilding().getColony().getWorld();
            if (world == null)
            {
                return false;
            }
            // 修复bug：昼夜判定用真实 dayTime（与商队 AI 一致）。
            final long dayTime = world.getDayTime() % 24000L;
            double sleepStart = 12600.0;
            try
            {
                final double longer = getBuilding().getColony().getResearchManager()
                    .getResearchEffects()
                    .getEffectStrength(com.minecolonies.api.research.util.ResearchConstants.WORK_LONGER);
                if (longer > 0)
                {
                    sleepStart += longer * 1000.0;
                }
            }
            catch (final Exception ignored)
            {
                // 研究未就绪时使用默认入睡时间。
            }
            return dayTime >= (long) sleepStart;
        }
        catch (final Exception ignored)
        {
            return false;
        }
    }

    /**
     * 需求（消耗品）：收集领袖/成员背包中的每顶商队帐篷——
     * 每顶帐篷是一个独立的单格栈（count = 1，保留各自耐久度），
     * 客户端逐顶显示独立图标与耐久度条；最多返回 9 顶（携带量上限）。
     */
    private static java.util.List<ItemStack> caravanTents(final JobCaravanLeader job)
    {
        final java.util.List<ItemStack> tents = new java.util.ArrayList<>();
        final java.util.function.Consumer<com.minecolonies.api.entity.citizen.AbstractEntityCitizen> collector = entity ->
        {
            for (int slot = 0; slot < entity.getInventoryCitizen().getSlots(); slot++)
            {
                final ItemStack stack = entity.getInventoryCitizen().getStackInSlot(slot);
                if (stack.getItem() == com.example.caravan.CaravanMod.CARAVAN_TENT.get())
                {
                    // 同耐久度的帐篷可能堆叠（count > 1），按数量拆成独立单格栈（上限 3 顶）。
                    for (int i = 0; i < stack.getCount() && tents.size() < 3; i++)
                    {
                        final ItemStack single = stack.copy();
                        single.setCount(1);
                        tents.add(single);
                    }
                }
            }
        };
        job.getCitizen().getEntity().ifPresent(collector::accept);
        // 商队成员背包。
        try
        {
            if (job.getCitizen().getWorkBuilding() != null)
            {
                for (final WorkerBuildingModule workerModule : job.getCitizen().getWorkBuilding()
                    .getModulesByType(WorkerBuildingModule.class))
                {
                    if (!workerModule.getJobEntry().getKey()
                        .equals(com.example.caravan.CaravanMod.JOB_CARAVAN_MEMBER.getKey()))
                    {
                        continue;
                    }
                    for (final ICitizenData member : workerModule.getAssignedCitizen())
                    {
                        final var entity = member.getEntity().orElse(null);
                        if (entity == null)
                        {
                            continue;
                        }
                        for (int slot = 0; slot < entity.getInventoryCitizen().getSlots(); slot++)
                        {
                            final ItemStack stack = entity.getInventoryCitizen().getStackInSlot(slot);
                            if (stack.getItem() == com.example.caravan.CaravanMod.CARAVAN_TENT.get())
                            {
                                for (int i = 0; i < stack.getCount() && tents.size() < 4; i++)
                                {
                                    final ItemStack single = stack.copy();
                                    single.setCount(1);
                                    tents.add(single);
                                }
                            }
                        }
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 数据未就绪时返回空。
        }
        return tents;
    }

    /**
     * 需求（消耗品）：收集领袖/成员背包中【菜单】页选定的食物——
     * 每个堆叠一个图标（客户端显示数量角标），最多返回 3 个堆叠。
     */
    private static java.util.List<ItemStack> caravanFoods(final JobCaravanLeader job)
    {
        final java.util.List<ItemStack> foods = new java.util.ArrayList<>();
        try
        {
            if (job.getCitizen().getWorkBuilding() == null)
            {
                return foods;
            }
            final var menu = job.getCitizen().getWorkBuilding()
                .getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule.class);
            if (menu == null || menu.getMenu().isEmpty())
            {
                return foods;
            }
            final java.util.function.Consumer<com.minecolonies.api.entity.citizen.AbstractEntityCitizen> collector = entity ->
            {
                for (int slot = 0; slot < entity.getInventoryCitizen().getSlots(); slot++)
                {
                    final ItemStack stack = entity.getInventoryCitizen().getStackInSlot(slot);
                    if (stack.isEmpty())
                    {
                        continue;
                    }
                    for (final com.minecolonies.api.crafting.ItemStorage food : menu.getMenu())
                    {
                        if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, food.getItemStack()))
                        {
                            foods.add(stack.copy());
                            break;
                        }
                    }
                }
            };
            job.getCitizen().getEntity().ifPresent(collector::accept);
            for (final WorkerBuildingModule workerModule : job.getCitizen().getWorkBuilding()
                .getModulesByType(WorkerBuildingModule.class))
            {
                if (!workerModule.getJobEntry().getKey()
                    .equals(com.example.caravan.CaravanMod.JOB_CARAVAN_MEMBER.getKey()))
                {
                    continue;
                }
                for (final ICitizenData member : workerModule.getAssignedCitizen())
                {
                    final var entity = member.getEntity().orElse(null);
                    if (entity != null)
                    {
                        collector.accept(entity);
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 数据未就绪时返回空。
        }
        while (foods.size() > 3)
        {
            foods.remove(foods.size() - 1);
        }
        return foods;
    }

    /**
     * 需求（消耗品）：收集领袖/成员背包中的火把——每个堆叠一个图标
     * （客户端显示数量角标），最多返回 3 个堆叠。
     */
    private static java.util.List<ItemStack> caravanTorches(final JobCaravanLeader job)
    {
        final java.util.List<ItemStack> torches = new java.util.ArrayList<>();
        final java.util.function.Consumer<com.minecolonies.api.entity.citizen.AbstractEntityCitizen> collector = entity ->
        {
            for (int slot = 0; slot < entity.getInventoryCitizen().getSlots(); slot++)
            {
                final ItemStack stack = entity.getInventoryCitizen().getStackInSlot(slot);
                if (stack.getItem() == Items.TORCH)
                {
                    torches.add(stack.copy());
                }
            }
        };
        job.getCitizen().getEntity().ifPresent(collector::accept);
        try
        {
            if (job.getCitizen().getWorkBuilding() != null)
            {
                for (final WorkerBuildingModule workerModule : job.getCitizen().getWorkBuilding()
                    .getModulesByType(WorkerBuildingModule.class))
                {
                    if (!workerModule.getJobEntry().getKey()
                        .equals(com.example.caravan.CaravanMod.JOB_CARAVAN_MEMBER.getKey()))
                    {
                        continue;
                    }
                    for (final ICitizenData member : workerModule.getAssignedCitizen())
                    {
                        final var entity = member.getEntity().orElse(null);
                        if (entity != null)
                        {
                            collector.accept(entity);
                        }
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 数据未就绪时返回空。
        }
        while (torches.size() > 3)
        {
            torches.remove(torches.size() - 1);
        }
        return torches;
    }

    /** 计算下一个目的地（最近未完成交易目标）的第一笔交易的结果物品。 */
    private static ItemStack nextDestinationTradeIcon(final JobCaravanLeader job)
    {
        if (!job.isAway() || job.getAwayPos() == null)
        {
            return ItemStack.EMPTY;
        }
        final JobCaravanLeader.TripTrade nearest = job.nearestPendingTarget(job.getAwayPos());
        if (nearest == null)
        {
            return ItemStack.EMPTY;
        }
        final BlockPos target = nearest.trade().villagePos();
        final long radiusSq = (long) JobCaravanLeader.TRADE_PACK_RADIUS * JobCaravanLeader.TRADE_PACK_RADIUS;
        // 按行程列表顺序取该目的地 100 格内的第一笔未完成交易的结果。
        for (final JobCaravanLeader.TripTrade offer : job.getTrip())
        {
            if (offer.status() == JobCaravanLeader.TripStatus.PENDING
                && offer.trade().villagePos().distSqr(target) <= radiusSq)
            {
                return offer.trade().result();
            }
        }
        return nearest.trade().result();
    }

    private static void writePos(final RegistryFriendlyByteBuf buffer, final BlockPos pos)
    {
        buffer.writeBoolean(pos != null);
        if (pos != null)
        {
            buffer.writeVarInt(pos.getX());
            buffer.writeVarInt(pos.getY());
            buffer.writeVarInt(pos.getZ());
        }
    }

    /** 统计某物品在小屋存储与商队全部在编市民背包中的总数量。 */
    private int countAvailable(final ItemStack item)
    {
        int count = 0;
        try
        {
            final IItemHandler hut = getBuilding().getItemHandlerCap((Direction) null);
            if (hut != null)
            {
                for (int slot = 0; slot < hut.getSlots(); slot++)
                {
                    final ItemStack stack = hut.getStackInSlot(slot);
                    if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, item))
                    {
                        count += stack.getCount();
                    }
                }
            }
            for (final WorkerBuildingModule workerModule : getBuilding().getModulesByType(WorkerBuildingModule.class))
            {
                for (final ICitizenData citizen : workerModule.getAssignedCitizen())
                {
                    final var entity = citizen.getEntity().orElse(null);
                    if (entity == null)
                    {
                        continue;
                    }
                    for (int slot = 0; slot < entity.getInventoryCitizen().getSlots(); slot++)
                    {
                        final ItemStack stack = entity.getInventoryCitizen().getStackInSlot(slot);
                        if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, item))
                        {
                            count += stack.getCount();
                        }
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 数据未就绪时按 0 处理。
        }
        return count;
    }

    /** 找到指派到本小屋的商队领袖职业实例（若有）。 */
    private JobCaravanLeader findLeaderJob()
    {
        // 小屋有两个工作模块（领袖/成员），逐一查找商队领袖职业。
        for (final WorkerBuildingModule workerModule : getBuilding().getModulesByType(WorkerBuildingModule.class))
        {
            for (final ICitizenData citizen : workerModule.getAssignedCitizen())
            {
                if (citizen.getJob() instanceof JobCaravanLeader job)
                {
                    return job;
                }
            }
        }
        return null;
    }
}
