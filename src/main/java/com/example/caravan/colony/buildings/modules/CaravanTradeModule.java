package com.example.caravan.colony.buildings.modules;

import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.example.caravan.colony.jobs.JobCaravanMember;
import com.example.caravan.waystone.WaystoneHelper;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IAltersRequiredItems;
import com.minecolonies.api.colony.buildings.modules.ICreatesResolversModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.management.IProviderHandler;
import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.AbstractDeliverymanRequestable;
import com.minecolonies.api.colony.requestsystem.requestable.deliveryman.Delivery;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.player.IPlayerRequestResolver;
import com.minecolonies.api.colony.requestsystem.resolver.retrying.IRetryingRequestResolver;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.constant.TypeConstants;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.colony.requestsystem.management.IStandardRequestManager;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.entity.npc.VillagerTrades.ItemListing;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.neoforge.items.IItemHandler;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 商队小屋的交易列表模块（服务端）。
 *
 * <p>保存：</p>
 * <ul>
 *   <li>已记录村民及其全部交易（{@link VillagerTradeEntry}，含模拟经验）；</li>
 *   <li>每个交易条目的执行模式（禁用/单次/重复）与交易数量（1..次数上限），
 *       设置按“村民 UUID + 条目序号”存储——村民重录/升级后，旧设置按条目位置保留，
 *       【重复】条目不会被重置为【禁用】。</li>
 * </ul>
 */
public class CaravanTradeModule extends AbstractBuildingModule
    implements IPersistentModule, ITickingModule, IAltersRequiredItems, ICreatesResolversModule
{
    /** 交易条目的执行模式。 */
    public enum TradeMode
    {
        /** 禁用：AI 完全忽略该交易。 */
        DISABLED,
        /** 单次：完成一次后自动转为禁用。 */
        SINGLE,
        /** 重复：每次出行前重新请求，可反复执行（次数 = 交易数量设置）。 */
        REPEAT,
        /** 按需：响应请求系统对该交易结果物品的缺口，每次出发按缺口执行数次
         *  （村民单次交易上限以下；多个村民出售同一物品时按距离顺序补差价）。 */
        ON_DEMAND
    }

    private static final String TAG_VILLAGERS = "villagers";
    private static final String TAG_MODES = "modes";
    private static final String TAG_QUANTITIES = "quantities";
    private static final String TAG_CUSTOM_NAMES = "customNames";
    private static final String TAG_OFFER_ORDER = "offerOrder";
    /** 需求（非递归重构）：持久化“已下放”主请求 token 列表。 */
    private static final String TAG_RELEASED = "released";

    private final List<VillagerTradeEntry> villagers = new ArrayList<>();
    /** 设置键：村民 UUID + ":" + 条目序号。 */
    private final Map<String, TradeMode> modes = new HashMap<>();
    private final Map<String, Integer> quantities = new HashMap<>();
    /** 需求：村民自定义名称（按村民 UUID 存储，重录交易后仍保留）。 */
    private final Map<UUID, String> customNames = new HashMap<>();
    /** 需求：Waystone 名称周期性刷新的节拍计数（每 200 刻刷新一次）。 */
    private int waystoneRefreshTicks;
    /** 需求（GUI 打开触发）：分批刷新 Waystone 名称——当前批次处理到的村民下标（-1 = 空闲）。 */
    private int waystoneBatchIndex = -1;
    /** 需求：分批刷新的节拍计数（每 20 刻处理一批）。 */
    private int waystoneBatchTicks;
    /** 需求：每批处理的村民数量（防止一次同步过多造成卡顿）。 */
    private static final int WAYSTONE_BATCH_SIZE = 1;
    /** 需求（新增机制）：商队成员与领袖的距离上限平方（100 格），
     *  超过后每殖民地刻传送回领袖旁边。 */
    private static final int MEMBER_TELEPORT_DISTANCE_SQ = 100 * 100;
    /** 需求（请求系统接入）：由自定义 resolver 接单的按需请求（token → 物品与缺口）。
     *  使用 LinkedHashMap 保持插入序（外部请求先、后接入的请求后），
     *  供当前交易的行程选择。 */
    private final Map<IToken<?>, OnDemandEntry> onDemandRequests = new LinkedHashMap<>();
    /** 需求（请求系统接入）：是否已确认本建筑的 resolver 注册（每次启动检查一次）。 */
    private boolean resolverRegistered;
    /** 需求（GUI 退出生效）：玩家在 GUI 中调整的交易模式（key = 村民UUID:条目序号）。 */
    private final Map<String, TradeMode> pendingModes = new HashMap<>();
    /** 需求（GUI 退出生效）：玩家在 GUI 中调整的交易数量。 */
    private final Map<String, Integer> pendingQuantities = new HashMap<>();
    /** 需求（总览）：激活交易的执行顺序（平铺索引列表，空 = 默认距离顺序）。
     *  玩家可在【交易列表】→【总览】中调整；【按需】交易按此顺序分配给请求。 */
    private final List<Integer> offerOrder = new ArrayList<>();
    /** 需求（通报）：已通报“下次出发时交易”的请求 token（每个请求只通报一次）。 */
    private final Set<IToken<?>> preparedNotified = new HashSet<>();
    /** 需求（通报）：已完成通报的按需请求 token（每个请求只通报一次）。 */
    private final Set<IToken<?>> completedNotified = new HashSet<>();
    /** 需求（调试）：已通报“售出物运送完成”的售出物请求 token（去重）。 */
    private final Set<IToken<?>> sellableCompletedLogged = new HashSet<>();
    /** 需求（重复交易）：重复交易执行计划——flatIndex → 售出物请求与数量。
     *  每次出行后重新创建售出物请求并试图执行（任务：标记为【重复】的交易持续工作）。 */
    private final Map<Integer, RepeatPlan> repeatPlans = new HashMap<>();
    /** 需求（成果运送）：外部请求的成果运送任务——请求 token → Delivery token。
     *  物品满足后创建本体 Delivery 子请求，快递员送达完成后再完成主请求，
     *  避免“主请求直接注销、货物被取货机制收走”。 */
    private final Map<IToken<?>, IToken<?>> finalDeliveries = new HashMap<>();
    /** 需求（非递归重构）：已尝试接入但殖民地无法提供售出品、被下放的主请求 token——
     *  在下一次商队交易归来前不再重复接入（避免“接入→下放”每殖民地刻死循环）。 */
    private final Set<IToken<?>> releasedRequests = new HashSet<>();
    /** 需求（bug 修复）：商队当前是否有活跃行程（AI 在创建/结束行程时通知模块）——
     *  行程进行中售出物可能已被提取到领袖/成员物品栏或成为模拟成果，
     *  此时“售出物不足”是正常状态，模块不得补建运送请求。 */
    private boolean tripActive;
    /** 需求（工匠接管）：接管冷却时长（殖民地刻，600 = 30 秒游戏时间）。 */
    private static final int TAKE_OVER_COOLDOWN_TICKS = 600;
    /** 需求（请求监听·下放检测）：所有曾下放到玩家/重试解析方的请求 token
     *  （请求结束前持续跟踪，用于识别“新下放”的精确时刻）。 */
    private final Set<IToken<?>> downleveledSeen = new HashSet<>();
    /** 需求（工匠接管）：已尝试接管（取消工匠子请求链）的主请求冷却——
     *  token → 剩余冷却殖民地刻；冷却期内不再尝试（防止工匠抢回后每殖民地刻死循环），
     *  冷却结束后允许再次尝试。 */
    private final Map<IToken<?>, Integer> takeOverCooldowns = new HashMap<>();

    /**
     * 单个按需请求的需求记录（公开供 resolver 生成后续送达请求）。
     *
     * @param item           该请求的物品与缺口（如 雕纹石砖 x64 / 绿宝石 x16）
     * @param count          请求缺口数量
     * @param sellable       该请求对应交易的售出物（成本物品，如 绿宝石 / 木棍）
     * @param sellableCount  售出物总量上限（Σ 各分摊交易 单次成本 × 分配次数）
     * @param sellableRequests 分摊售出物子请求 token 列表（每个分摊交易一个 S 节点）
     * @param tradeMarker    商队交易标记子请求 token（M 节点）
     * @param tradeCount     将要执行的总交易次数（M 节点显示）
     * @param tradeIndices   分摊交易在平铺列表中的索引列表
     * @param tradeCopies    每个分摊交易的副本数（与 tradeIndices 一一对应）
     */
    public record OnDemandEntry(
        ItemStorage item,
        int count,
        ItemStorage sellable,
        int sellableCount,
        List<IToken<?>> sellableRequests,
        IToken<?> tradeMarker,
        int tradeCount,
        List<Integer> tradeIndices,
        List<Integer> tradeCopies)
    {
    }

    /** 重复交易执行计划：售出物请求 token（null = 已在仓库/背包，无需请求）、售出物与数量、副本数。 */
    public record RepeatPlan(IToken<?> sellableRequest, ItemStorage sellable, int sellableCount, int copies)
    {
    }

    public List<VillagerTradeEntry> getVillagers()
    {
        return villagers;
    }

    /** 按 UUID 查找已记录的村民（无则返回 null）。 */
    public VillagerTradeEntry findVillager(final UUID villagerId)
    {
        for (final VillagerTradeEntry entry : villagers)
        {
            if (entry.villagerId().equals(villagerId))
            {
                return entry;
            }
        }
        return null;
    }

    /**
     * 需求：【删除】按钮——从小屋中删除指定村民及其全部交易，
     * 同时清除该村民的模式/数量设置并标记建筑为脏（同步给客户端）。
     */
    public void removeVillager(final UUID villagerId)
    {
        villagers.removeIf(entry -> entry.villagerId().equals(villagerId));
        modes.keySet().removeIf(key -> key.startsWith(villagerId + ":"));
        quantities.keySet().removeIf(key -> key.startsWith(villagerId + ":"));
        customNames.remove(villagerId);
        markDirty();
    }

    /** 需求：重命名村民——空白名称视为清除自定义名称（恢复显示职业）。 */
    public void renameVillager(final UUID villagerId, final String name)
    {
        if (findVillager(villagerId) == null)
        {
            return;
        }
        final String trimmed = name != null ? name.trim() : "";
        if (trimmed.isEmpty())
        {
            customNames.remove(villagerId);
        }
        else
        {
            customNames.put(villagerId, trimmed.length() > 32 ? trimmed.substring(0, 32) : trimmed);
        }
        markDirty();
    }

    /** 需求：查询村民的自定义名称（无则返回 null，GUI 显示职业）。 */
    public String getCustomName(final UUID villagerId)
    {
        return customNames.get(villagerId);
    }

    /**
     * 需求（机制确认/取货保留）：快递员创建取货请求从小屋取走物品时，
     * minecolonies 会调用本方法获取“需要保留”的物品——
     * 这里把当前所有已选（非禁用）交易的交易品按总量聚合为保留量，
     * 确保快递员取货后仍留下足够下次商队出发交易的量
     * （玩家也可在【最低存量】标签页额外设置保留量）。
     */
    @Override
    public void alterItemsToBeKept(final TriConsumer<Predicate<ItemStack>, Integer, Boolean> consumer)
    {
        try
        {
            // 需求（bug 修复，双保险）：最低绿宝石存量必须在小屋存储中保留——
            // 即使 CaravanStockModule 的 alterItemsToBeKept 因模块未加载等原因
            // 未被取货机制调用，这里也会保留若干组绿宝石，防止清仓请求把
            // 最后一组绿宝石取走（送达的绿宝石被清走后交易永远无法创建）。
            final CaravanStockModule stockModule = getBuilding() != null
                ? getBuilding().getFirstModuleOccurance(CaravanStockModule.class) : null;
            if (stockModule != null && stockModule.getMinEmeraldStock() > 0)
            {
                final ItemStack emerald = new ItemStack(Items.EMERALD);
                final ItemStorage emeraldKey = new ItemStorage(emerald);
                final int keep = stockModule.getMinEmeraldStock() * CaravanStockModule.EMERALD_PER_STACK;
                consumer.accept(
                    stack -> !stack.isEmpty() && new ItemStorage(stack).equals(emeraldKey),
                    keep,
                    false);
            }
            final Map<ItemStorage, Integer> needed = new HashMap<>();
            for (int flat = 0; flat < getTotalOfferCount(); flat++)
            {
                if (!isAvailable(flat))
                {
                    continue;
                }
                final TradeOfferData offer = getOffer(flat);
                if (offer == null)
                {
                    continue;
                }
                final int copies = getCopiesFor(flat);
                for (final ItemStack cost : offer.costs())
                {
                    needed.merge(new ItemStorage(cost), cost.getCount() * copies, Integer::sum);
                }
            }
            for (final Map.Entry<ItemStorage, Integer> entry : needed.entrySet())
            {
                final ItemStorage storage = entry.getKey();
                consumer.accept(
                    stack -> !stack.isEmpty() && new ItemStorage(stack).equals(storage),
                    entry.getValue(),
                    false);
            }
            // 需求（机制5确认）：被请求的物品（按需交易成果，如书架）在请求完成前
            // 必须保留在小屋存储中——否则快递员取货会把已带回的部分成果收走，
            // 导致请求永远凑不齐（如请求 5 个书架、一次只带回 2 个，需保留到 5 个后
            // 一起提交给请求系统）。
            final Map<ItemStorage, Integer> demand = getOnDemandDemand();
            for (final Map.Entry<ItemStorage, Integer> entry : demand.entrySet())
            {
                final ItemStorage storage = entry.getKey();
                consumer.accept(
                    stack -> !stack.isEmpty() && new ItemStorage(stack).equals(storage),
                    entry.getValue(),
                    false);
            }
        }
        catch (final Exception ignored)
        {
            // 数据未就绪时不保留额外物品。
        }
    }

    /** 所有村民的交易条目总数（平铺后的全局索引范围）。 */
    public int getTotalOfferCount()
    {
        int count = 0;
        for (final VillagerTradeEntry entry : villagers)
        {
            count += entry.offers().size();
        }
        return count;
    }

    /** 平铺索引 → {村民列表下标, 条目序号}。 */
    private int[] resolveOffer(final int flatIndex)
    {
        int remaining = flatIndex;
        for (int vi = 0; vi < villagers.size(); vi++)
        {
            final int size = villagers.get(vi).offers().size();
            if (remaining < size)
            {
                return new int[] {vi, remaining};
            }
            remaining -= size;
        }
        return null;
    }

    private static String settingKey(final UUID villagerId, final int offerIndex)
    {
        return villagerId + ":" + offerIndex;
    }

    public VillagerTradeEntry getVillagerForOffer(final int flatIndex)
    {
        final int[] resolved = resolveOffer(flatIndex);
        return resolved != null ? villagers.get(resolved[0]) : null;
    }

    public TradeOfferData getOffer(final int flatIndex)
    {
        final int[] resolved = resolveOffer(flatIndex);
        return resolved != null ? villagers.get(resolved[0]).offers().get(resolved[1]) : null;
    }

    public BlockPos getOfferWorkstation(final int flatIndex)
    {
        final VillagerTradeEntry entry = getVillagerForOffer(flatIndex);
        return entry != null ? entry.workstationPos() : null;
    }

    /** 查询某交易条目的执行模式（缺省禁用）。 */
    public TradeMode getMode(final int flatIndex)
    {
        final int[] resolved = resolveOffer(flatIndex);
        if (resolved == null)
        {
            return TradeMode.DISABLED;
        }
        return modes.getOrDefault(
            settingKey(villagers.get(resolved[0]).villagerId(), resolved[1]), TradeMode.DISABLED);
    }

    /** 查询某交易条目的交易数量（缺省 1）。 */
    public int getQuantity(final int flatIndex)
    {
        final int[] resolved = resolveOffer(flatIndex);
        if (resolved == null)
        {
            return 1;
        }
        return quantities.getOrDefault(
            settingKey(villagers.get(resolved[0]).villagerId(), resolved[1]), 1);
    }

    /** 设置交易数量（钳制在 1..该交易最大可用次数；GUI 退出生效，先进入待应用队列）。 */
    public void setQuantity(final int flatIndex, final int quantity)
    {
        final int[] resolved = resolveOffer(flatIndex);
        if (resolved == null)
        {
            return;
        }
        final VillagerTradeEntry entry = villagers.get(resolved[0]);
        final int max = Math.max(1, entry.offers().get(resolved[1]).maxUses());
        pendingQuantities.put(settingKey(entry.villagerId(), resolved[1]),
            Math.max(1, Math.min(quantity, max)));
    }

    /**
     * 需求（GUI 退出生效）：玩家关闭商队小屋 GUI 时调用——
     * 一次性应用待调整的交易模式与数量（超过“小屋等级×2”上限的激活变更被拒绝），
     * 之后商队 AI 才会按新列表创建订单。
     */
    public void applyPendingChanges()
    {
        boolean changed = false;
        for (final Map.Entry<String, TradeMode> entry : pendingModes.entrySet())
        {
            final TradeMode next = entry.getValue();
            if (next != TradeMode.DISABLED && getNonDisabledCount() >= getMaxSelection())
            {
                continue; // 超过上限的激活变更拒绝。
            }
            modes.put(entry.getKey(), next);
            changed = true;
        }
        for (final Map.Entry<String, Integer> entry : pendingQuantities.entrySet())
        {
            quantities.put(entry.getKey(), entry.getValue());
            changed = true;
        }
        pendingModes.clear();
        pendingQuantities.clear();
        if (changed)
        {
            markDirty();
        }
    }

    /** 是否为可用（非禁用）交易。 */
    public boolean isAvailable(final int flatIndex)
    {
        return getMode(flatIndex) != TradeMode.DISABLED;
    }

    /** 循环切换模式：禁用 → 单次 → 按需 → 重复 → 禁用（GUI 退出生效，先进入待应用队列）。 */
    public TradeMode cycleMode(final int flatIndex)
    {
        final int[] resolved = resolveOffer(flatIndex);
        if (resolved == null)
        {
            return TradeMode.DISABLED;
        }
        final String key = settingKey(villagers.get(resolved[0]).villagerId(), resolved[1]);
        // 需求（GUI 退出生效）：调整先进入待应用队列，退出 GUI 时统一生效，
        // 避免调整过程中商队反复创建/取消订单。
        final TradeMode current = pendingModes.getOrDefault(key, modes.getOrDefault(key, TradeMode.DISABLED));
        final TradeMode next = switch (current)
        {
            case DISABLED -> TradeMode.SINGLE;
            case SINGLE -> TradeMode.ON_DEMAND;
            case ON_DEMAND -> TradeMode.REPEAT;
            case REPEAT -> TradeMode.DISABLED;
        };
        pendingModes.put(key, next);
        return next;
    }

    /** 最大可选（非禁用）交易数：小屋等级 × 2。 */
    public int getMaxSelection()
    {
        return getBuilding().getBuildingLevel() * 2;
    }

    /** 当前非禁用（已选择）的交易条目数。 */
    public int getNonDisabledCount()
    {
        return getAvailableOffers().size();
    }

    /** 所有可用（非禁用）交易条目的索引，升序排列。 */
    public List<Integer> getAvailableOffers()
    {
        final List<Integer> result = new ArrayList<>();
        int flat = 0;
        for (final VillagerTradeEntry entry : villagers)
        {
            for (int i = 0; i < entry.offers().size(); i++)
            {
                if (isAvailable(flat))
                {
                    result.add(flat);
                }
                flat++;
            }
        }
        return result;
    }

    /** 需求（总览）：激活交易按玩家调整后的顺序返回（默认按距离排序）。 */
    public List<Integer> getAvailableOffersInOrder()
    {
        if (offerOrder.isEmpty())
        {
            return getAvailableOffers();
        }
        final List<Integer> result = new ArrayList<>();
        final Set<Integer> available = new HashSet<>(getAvailableOffers());
        for (final int idx : offerOrder)
        {
            if (available.contains(idx) && !result.contains(idx))
            {
                result.add(idx);
            }
        }
        for (final int idx : getAvailableOffers())
        {
            if (!result.contains(idx))
            {
                result.add(idx);
            }
        }
        return result;
    }

    /** 需求（总览）：确保 offerOrder 包含全部当前激活交易（补齐新增、移除禁用）。 */
    private void ensureOfferOrder()
    {
        final List<Integer> defaultOrder = getAvailableOffers();
        if (offerOrder.isEmpty())
        {
            offerOrder.addAll(defaultOrder);
            return;
        }
        offerOrder.removeIf(idx -> !defaultOrder.contains(idx));
        for (final int idx : defaultOrder)
        {
            if (!offerOrder.contains(idx))
            {
                offerOrder.add(idx);
            }
        }
    }

    /** 需求（总览）：上移/下移某笔激活交易在顺序列表中的位置。 */
    public void moveOffer(final int flatIndex, final boolean up)
    {
        ensureOfferOrder();
        final int index = offerOrder.indexOf(flatIndex);
        if (index < 0)
        {
            return;
        }
        final int target = up ? index - 1 : index + 1;
        if (target < 0 || target >= offerOrder.size())
        {
            return;
        }
        Collections.swap(offerOrder, index, target);
        markDirty();
    }

    /** 查询当前激活交易顺序列表（供客户端【总览】窗口显示）。 */
    public List<Integer> getOfferOrder()
    {
        ensureOfferOrder();
        return offerOrder;
    }

    /**
     * 需求（请求系统接入）：是否存在“可执行”的工作——
     * 有非按需的激活交易，或按需交易存在实际请求缺口。
     * 避免按需交易在无缺口时让商队空转。
     */
    public boolean hasWorkableOffers()
    {
        // 需求（bug 修复）：绿宝石最低存量缺口单独存在时也应视为“有事可做”——
        // 否则只设置最低存量（未开启任何交易、也没有外部请求）时，
        // 商队 AI 永远不会进入备货流程，存量补充交易无法创建。
        if (emeraldStockGap() > 0)
        {
            return true;
        }
        final Map<ItemStorage, Integer> demand = getOnDemandDemand();
        for (int flat = 0; flat < getTotalOfferCount(); flat++)
        {
            final TradeMode mode = getMode(flat);
            if (mode == TradeMode.DISABLED)
            {
                continue;
            }
            if (mode != TradeMode.ON_DEMAND)
            {
                return true;
            }
            final TradeOfferData offer = getOffer(flat);
            if (offer != null && demand.getOrDefault(new ItemStorage(offer.result()), 0) > 0)
            {
                return true;
            }
        }
        return false;
    }

    /** 行程副本数：非禁用交易按【交易数量】设置执行（1..次数上限）。 */
    public int getCopiesFor(final int flatIndex)
    {
        // 需求（按需交易）：数量由请求系统缺口决定，在生成行程时跨村民分配，
        // 此处返回 0（避免备货/保留逻辑按固定数量处理）。
        if (getMode(flatIndex) == TradeMode.ON_DEMAND)
        {
            return 0;
        }
        final TradeOfferData offer = getOffer(flatIndex);
        final int max = offer != null ? Math.max(1, offer.maxUses()) : 1;
        return Math.max(1, Math.min(getQuantity(flatIndex), max));
    }

    /**
     * 需求（按需交易）：扫描殖民地请求系统中所有未完成的 {@link IDeliverable} 请求，
     * 按结果物品聚合缺口数量——只有标记为【按需】的交易会响应这些缺口。
     */
    public Map<ItemStorage, Integer> getOnDemandDemand()
    {
        final Map<ItemStorage, Integer> demand = new HashMap<>();
        // 优先：由自定义 resolver 已接单的请求（请求系统接入）。
        for (final Map.Entry<IToken<?>, OnDemandEntry> entry : onDemandRequests.entrySet())
        {
            // 需求（bug 修复）：已进入完成流程的请求不再计入缺口（防止 AI 空转）。
            if (completedNotified.contains(entry.getKey()))
            {
                continue;
            }
            demand.merge(entry.getValue().item(), entry.getValue().count(), Integer::sum);
        }
        try
        {
            final IColony colony = getBuilding() != null ? getBuilding().getColony() : null;
            if (colony == null)
            {
                return demand;
            }
            final IRequestManager manager = colony.getRequestManager();
            if (manager == null)
            {
                return demand;
            }
            final Set<IToken<?>> tokens = new HashSet<>();
            tokens.addAll(manager.getPlayerResolver().getAllAssignedRequests());
            tokens.addAll(manager.getRetryingRequestResolver().getAllAssignedRequests());
            for (final IToken<?> token : tokens)
            {
                // 已由 resolver 接单并记录的请求不再扫描（避免重复计数）。
                if (onDemandRequests.containsKey(token))
                {
                    continue;
                }
                // 需求（非递归重构）：已下放的主请求不再重复接入——
                // 等到下一次商队交易归来后由 recheckReleasedRequestsAfterReturn 重新检查。
                if (releasedRequests.contains(token))
                {
                    continue;
                }
                final IRequest<?> request = manager.getRequestForToken(token);
                if (request == null)
                {
                    continue;
                }
                final RequestState state = request.getState();
                if (state != RequestState.CREATED && state != RequestState.REPORTED
                    && state != RequestState.ASSIGNED && state != RequestState.IN_PROGRESS
                    && state != RequestState.RESOLVED)
                {
                    continue;
                }
                if (!(request.getRequest() instanceof IDeliverable deliverable))
                {
                    continue;
                }
                if (!hasOnDemandOfferFor(deliverable))
                {
                    continue;
                }
                final ItemStack matched = findOnDemandResultFor(deliverable);
                demand.merge(new ItemStorage(matched), Math.max(1, deliverable.getCount()), Integer::sum);
            }
        }
        catch (final Exception ignored)
        {
            // 请求系统未就绪时返回空缺口。
        }
        return demand;
    }

    /**
     * 需求（请求系统接入）：本模块作为建筑的请求解析方——
     * 返回由商队小屋提供的自定义 resolver（处理建筑发出的、按需交易可产出的物品请求）。
     */
    @Override
    public List<IRequestResolver<?>> createResolvers()
    {
        try
        {
            final ILocation location = getBuilding().getRequester().getLocation();
            final IToken<?> token = getBuilding().getColony().getRequestManager()
                .getFactoryController().getNewInstance(TypeConstants.ITOKEN);
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 注册按需交易请求解析方 at {}", getBuilding().getPosition());
            return List.of(new CaravanTradeRequestResolver(location, token));
        }
        catch (final Exception ignored)
        {
            // 请求系统未就绪时不注册解析方。
            return List.of();
        }
    }

    /**
     * 需求（请求系统接入）：商队小屋是否有激活的【按需】交易能产出该请求匹配的物品。
     * 注意：请求可能是 {@link RequestTag}（按 tag 请求，getResult 为空），
     * 因此必须用 deliverable.matches(offer.result()) 而非比较 result。
     */
    public boolean hasOnDemandOfferFor(final IDeliverable deliverable)
    {
        for (int flat = 0; flat < getTotalOfferCount(); flat++)
        {
            if (getMode(flat) != TradeMode.ON_DEMAND)
            {
                continue;
            }
            final TradeOfferData offer = getOffer(flat);
            if (offer != null && deliverable.matches(offer.result()))
            {
                return true;
            }
        }
        return false;
    }

    /** 需求（请求系统接入）：返回匹配该请求的第一个【按需】交易的结果物品（用于记录交付物）。 */
    public ItemStack findOnDemandResultFor(final IDeliverable deliverable)
    {
        for (int flat = 0; flat < getTotalOfferCount(); flat++)
        {
            if (getMode(flat) != TradeMode.ON_DEMAND)
            {
                continue;
            }
            final TradeOfferData offer = getOffer(flat);
            if (offer != null && deliverable.matches(offer.result()))
            {
                return offer.result();
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 需求（0.4.18 回退）：计算满足某按需请求所需执行的【按需】交易次数——
     * 按交易分配规则模拟（每次交易获得 result.getCount() 个成果，受单次交易上限约束，
     * 多个村民出售同一物品时按距离顺序依次分配）。
     */
    public int tradeCountForResult(final ItemStack result, final int count)
    {
        int remaining = count;
        int trades = 0;
        for (int flat = 0; flat < getTotalOfferCount() && remaining > 0; flat++)
        {
            if (getMode(flat) != TradeMode.ON_DEMAND)
            {
                continue;
            }
            final TradeOfferData offer = getOffer(flat);
            if (offer == null || !ItemStackUtils.compareItemStacksIgnoreStackSize(offer.result(), result))
            {
                continue;
            }
            final int perTrade = Math.max(1, offer.result().getCount());
            final int neededTrades = (remaining + perTrade - 1) / perTrade;
            // 需求（GUI 修复）：总执行次数不扣单次交易上限（村民交易次数会在多次
            // 出行之间刷新），例如请求 256 雕纹石砖、每次交易 4 个 → 显示 64 次
            // （旧实现受 maxUses=16 截断，错误显示 16 次）。
            trades += neededTrades;
            remaining -= neededTrades * perTrade;
        }
        return Math.max(1, trades);
    }

    /** 需求（请求系统接入）：resolver 接单后记录需求缺口。 */
    public void recordOnDemandRequest(
        final IToken<?> token,
        final ItemStack result,
        final int count,
        final ItemStorage sellable,
        final int sellableCount,
        final List<IToken<?>> sellableRequests,
        final IToken<?> tradeMarker,
        final int tradeCount,
        final List<Integer> tradeIndices,
        final List<Integer> tradeCopies)
    {
        if (result == null || result.isEmpty() || count <= 0)
        {
            return;
        }
        // 需求（防重复接单）：同一请求已记录时不再覆盖。
        if (onDemandRequests.containsKey(token))
        {
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 请求 {} 已在处理中，忽略重复接单", String.valueOf(token));
            return;
        }
        onDemandRequests.put(token, new OnDemandEntry(
            new ItemStorage(result), count,
            sellable, sellableCount,
            sellableRequests, tradeMarker,
            tradeCount, tradeIndices, tradeCopies));
        markDirty();
        // 需求（通报）：商队接管该请求时广播一次。
        try
        {
            final IRequestManager manager = getBuilding() != null && getBuilding().getColony() != null
                ? getBuilding().getColony().getRequestManager() : null;
            if (manager != null)
            {
                broadcastAccepted(manager, token);
            }
        }
        catch (final Exception ignored)
        {
            // 通报失败不影响核心功能。
        }
    }

    /**
     * 需求（请求链重构）：商队接管某请求时，创建两个子请求（如同工匠接单后创建
     * “A×配方”与“B×原料”）：
     * <ul>
     *   <li>M 节点：{@link CaravanTradeRequestable}（A×商队交易，A=交易次数），显示节点；</li>
     *   <li>S 节点：{@link com.minecolonies.api.colony.requestsystem.requestable.Stack}
     *       （B×售出物，B=缺口数量），requester=商队小屋，提交给请求系统，
     *       殖民地存储/工匠可满足则由其满足。</li>
     * </ul>
     */
    public void adoptRequest(
        final IRequestManager manager,
        final IRequest<?> request,
        final IDeliverable deliverable)
    {
        try
        {
            // 需求（诊断·断点）：adoptRequest 入口——确认请求来源与类型。
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: adoptRequest 入口 {}（类型 {}，请求方 {}）",
                String.valueOf(request.getId()),
                deliverable.getClass().getSimpleName(),
                request.getRequester() != null
                    ? request.getRequester().getLocation().getInDimensionLocation() : "?");
            // 需求（多交易分摊）：同一物品可由多个村民交易产出（如绿宝石），
            // 按【总览】优先级顺序分摊——单个交易达到村民单次交易上限（maxUses）
            // 后，由下一个交易继续满足请求。
            final List<Integer> tradeIndices = new ArrayList<>();
            final List<Integer> tradeCopies = new ArrayList<>();
            final List<IToken<?>> sellableRequests = new ArrayList<>();
            final List<ItemStorage> sellables = new ArrayList<>();
            int remaining = Math.max(1, deliverable.getCount());
            int totalTrades = 0;
            // 需求（bug 修复）：按“售出物种类”分别统计已有量与总需求——
            // 旧实现用一个标量记录第一种售出物的已有量并跨交易扣减，
            // 导致后续【不同种类】售出物的运送请求被错误减少（甚至完全不创建），
            // 表现为“领袖/小屋已有部分售出品（数量不足）时不触发运送请求补充到足量”，
            // 请求流卡死。此处每种售出物独立统计：已有量 < 总需求时必定创建运送请求。
            final Map<ItemStorage, Integer> alreadyHaveByItem = new HashMap<>();
            final Map<ItemStorage, Integer> totalDemandByItem = new HashMap<>();
            for (final int flat : getAvailableOffersInOrder())
            {
                if (remaining <= 0)
                {
                    break;
                }
                final TradeMode mode = getMode(flat);
                if (mode != TradeMode.ON_DEMAND && mode != TradeMode.REPEAT)
                {
                    continue;
                }
                final TradeOfferData offer = getOffer(flat);
                if (offer == null || !deliverable.matches(offer.result()))
                {
                    continue;
                }
                final int perTradeOut = Math.max(1, offer.result().getCount());
                final int neededTrades = (remaining + perTradeOut - 1) / perTradeOut;
                // 需求（0.4.18 机制）：【按需】交易以村民单次交易上限（maxUses）
                // 为硬上限；【重复】交易以玩家手动设置的交易数量为上限。
                final int cap = mode == TradeMode.REPEAT
                    ? Math.max(1, getCopiesFor(flat))
                    : Math.max(1, offer.maxUses());
                final int alloc = Math.min(neededTrades, cap);
                if (alloc <= 0)
                {
                    continue;
                }
                final ItemStack sellable = offer.costs().isEmpty() ? ItemStack.EMPTY : offer.costs().get(0);
                if (!sellable.isEmpty())
                {
                    final ItemStorage key = new ItemStorage(sellable);
                    alreadyHaveByItem.computeIfAbsent(key, k -> countAvailableAnywhere(sellable));
                    final int perTrade = Math.max(1, sellable.getCount());
                    totalDemandByItem.merge(key, perTrade * alloc, Integer::sum);
                }
                tradeIndices.add(flat);
                tradeCopies.add(alloc);
                totalTrades += alloc;
                remaining -= alloc * perTradeOut;
            }
            if (tradeIndices.isEmpty())
            {
                return;
            }
            final ItemStack matched = findProductionResultFor(deliverable);

            // M 节点：商队交易标记（显示“A×商队交易”，A = 总交易次数）。
            final IToken<?> markerToken = manager.createAndAssignRequest(
                getBuilding().getRequester(),
                new CaravanTradeRequestable(matched, Math.max(1, totalTrades)));
            // 需求（请求链重构）：挂父子采用“主请求 → M（商队交易）→ S（售出物）”
            // 结构（与工匠的 主请求 → 配方 → 原料 一致）——只有 M 是主请求的子请求，
            // 全部 S 挂在 M 下。这样 S 完成/取消时 minecolonies 只会遍历 M 的
            // children（S 自己），主请求 children 始终保持干净（仅 M），
            // 不会出现“S 送达后级联清理主请求其它 children / 残留失效 token 导致
            // 快递员 NPE、手动取消失败”的问题。
            try
            {
                final IRequest<?> marker = manager.getRequestForToken(markerToken);
                if (marker != null)
                {
                    marker.setParent(request.getId());
                    request.addChild(markerToken);
                }
            }
            catch (final Exception ignored)
            {
                // 父子关系设置失败不影响请求本身。
            }
            // 需求（bug 修复）：按分摊交易逐笔创建【缺口精确】售出物请求——
            // min = max = 分摊需求 - 已有量（用户确认：应正确减去商队小屋已有的部分）；
            // 已有量足够时直接可执行（不创建请求）。缺口请求可避免 minecolonies
            // 因“已有量 ≥ 下限”而自动完成请求（不送货）导致的卡死循环。
            final Map<ItemStorage, Integer> allocatedHave = new HashMap<>();
            for (int i = 0; i < tradeIndices.size(); i++)
            {
                final int flat = tradeIndices.get(i);
                final int alloc = tradeCopies.get(i);
                final TradeOfferData offer = getOffer(flat);
                if (offer == null || offer.costs().isEmpty())
                {
                    continue;
                }
                final ItemStack sellable = offer.costs().get(0);
                final ItemStorage key = new ItemStorage(sellable);
                if (alreadyHaveByItem.getOrDefault(key, 0) >= totalDemandByItem.getOrDefault(key, 0))
                {
                    // 已有量足够：记录售出物供“物品栏齐备”判定，不创建请求。
                    if (!sellables.contains(key))
                    {
                        sellables.add(key);
                    }
                    continue;
                }
                final int perTrade = Math.max(1, sellable.getCount());
                final int gross = perTrade * alloc;
                // 需求（多分摊交易）：该售出物的已有量先抵扣前面的分摊交易，
                // 剩余缺口由后续分摊交易承担。
                final int remainingHave = Math.max(0,
                    alreadyHaveByItem.getOrDefault(key, 0) - allocatedHave.getOrDefault(key, 0));
                final int shortfall = Math.max(1, gross - remainingHave);
                // 需求（请求链重构）：S 挂到 M 节点下（主请求 → M → S）。
                sellableRequests.add(createSellableRequestForTrade(
                    manager, markerToken, flat, alloc, sellable, remainingHave));
                allocatedHave.merge(key, Math.min(gross, remainingHave), Integer::sum);
                sellables.add(key);
                // 需求（调试）：创建售出品运送请求（S 节点，分摊）。
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 创建售出品运送请求 {}（{} x{}..{}，交易 {}，M 节点 {}，已有 {}）",
                    String.valueOf(sellableRequests.get(sellableRequests.size() - 1)),
                    sellable.getHoverName().getString(),
                    shortfall, shortfall, flat, String.valueOf(markerToken),
                    remainingHave);
            }
            // 需求（bug 修复）：firstSellable / sellableCount 必须在售出物请求
            // （S 节点）创建循环【之后】计算——旧实现把它们放在循环之前，
            // 此时 sellables 列表仍为空，导致记录的 sellable=null、sellableCount=0，
            // getCurrentTradeIndices 的链层判定（sellable==null || sellableCount<=0）
            // 永远跳过，交易任务永不创建。
            final ItemStack firstSellable = sellables.isEmpty() ? ItemStack.EMPTY : sellables.get(0).getItemStack();
            final int sellableCount = firstSellable.isEmpty() ? 0 : firstSellable.getCount() * totalTrades;
            recordOnDemandRequest(
                request.getId(), matched, deliverable.getCount(),
                firstSellable.isEmpty() ? null : new ItemStorage(firstSellable), sellableCount,
                sellableRequests, markerToken, totalTrades, tradeIndices, tradeCopies);
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 商队接管 {} 并创建请求链：{}x商队交易（{}） + 售出物请求 {} 个（分摊 {} 笔交易）",
                String.valueOf(request.getId()), totalTrades, matched.getHoverName().getString(),
                sellableRequests.size(), tradeIndices.size());
        }
        catch (final Exception ex)
        {
            // 链创建失败不阻塞核心功能（请求仍可由其它途径处理）。
            com.example.caravan.CaravanMod.LOGGER.warn(
                "Caravan: 创建请求链失败（请求 {}）", String.valueOf(request.getId()), ex);
        }
    }

    /**
     * 需求（非递归重构）：为单个分摊交易创建弹性售出物请求（S 节点）——
     * 下限 = 单次成本（至少满足一次交易），上限 = 分摊需求 - 该售出物已有量
     * （用户确认：请求售出品时应正确减去商队小屋已有的部分），
     * 并挂到“商队交易”标记（M 节点）下（请求树显示 主请求 → M → S 流程链）。
     * 注：仓库不足时请求会被自动判定“完成”（已有量 ≥ 下限），此时“至少一次
     * 交易”就绪判定会按已有量创建交易，交易消耗后再由 rebuild 按新缺口补货。
     */
    private IToken<?> createSellableRequestForTrade(
        final IRequestManager manager,
        final IToken<?> parentToken,
        final int flat,
        final int alloc,
        final ItemStack sellable,
        final int alreadyHave)
    {
        final int perTrade = Math.max(1, sellable.getCount());
        final int maxCount = Math.max(perTrade, perTrade * alloc - alreadyHave);
        final IToken<?> sellableToken = manager.createAndAssignRequest(
            getBuilding().getRequester(),
            new com.minecolonies.api.colony.requestsystem.requestable.Stack(
                sellable.copyWithCount(1), maxCount, perTrade));
        try
        {
            final IRequest<?> sellableRequest = manager.getRequestForToken(sellableToken);
            final IRequest<?> parent = manager.getRequestForToken(parentToken);
            if (sellableRequest != null && parent != null)
            {
                sellableRequest.setParent(parentToken);
                parent.addChild(sellableToken);
            }
        }
        catch (final Exception ignored)
        {
            // 父子关系设置失败不影响请求本身。
        }
        return sellableToken;
    }

    /**
     * 需求（弹性 + 多交易分摊）：当前应执行的交易集合——
     * <ul>
     *   <li>主请求（非递归）：全部售出物子请求（S）送达完成才可执行，
     *       多个主请求同时就绪时全部纳入行程；</li>
     *   <li>重复交易计划中售出物已齐备的交易；</li>
     *   <li>均无可用时返回绿宝石最低存量补充交易。</li>
     * </ul>
     */
    public List<Integer> getCurrentTradeIndices()
    {
        final List<Integer> result = new ArrayList<>();
        // 1. 主请求（非递归重构）：全部售出物子请求送达完成才可执行；
        //    多个主请求同时就绪时全部纳入本次行程。
        for (final Map.Entry<IToken<?>, OnDemandEntry> mapEntry : onDemandRequests.entrySet())
        {
            final OnDemandEntry entry = mapEntry.getValue();
            // 需求（bug 修复）：已进入完成流程（小屋到货、等待成品运送）的请求
            // 不再规划交易——快递员取走小屋中的成果后数量会短暂不足，
            // 仅靠数量判定无法识别“已完成”，否则商队会重复交易同一请求。
            if (completedNotified.contains(mapEntry.getKey()))
            {
                continue;
            }
            if (entry.sellable() == null || entry.sellableCount() <= 0)
            {
                continue;
            }
            // 需求（bug 修复）：物品已满足（等待成果运送/完成流程）时不再重复规划
            // 同一交易——否则商队会在等待 Delivery 期间再次领取订单、重复交易浪费。
            if (countAvailableAnywhere(entry.item().getItemStack()) >= entry.count())
            {
                continue;
            }
            if (allSellableRequestsDone(entry))
            {
                // 需求（bug 修复）：仅当交易产出物与该记录请求物品一致时才纳入行程，
                // 防止平铺索引（flatIndex）因村民重录/顺序变化发生偏移后误匹配到
                // 其它交易（如将重复交易“绿宝石→玻璃”误当作链交易执行 16 次）。
                for (final int idx : entry.tradeIndices())
                {
                    final TradeOfferData offer = getOffer(idx);
                    if (offer != null && ItemStackUtils.compareItemStacksIgnoreStackSize(
                        offer.result(), entry.item().getItemStack()))
                    {
                        if (!result.contains(idx))
                        {
                            result.add(idx);
                        }
                    }
                }
            }
        }
        // 2. 重复/单次交易计划：售出物已齐备时可执行。
        // 需求（请求优先·优化）：当有请求需要完成时（onDemandRequests 非空），
        // 玩家手动设置的【单次】/【重复】交易默认不执行；但【产出物与请求售出物
        // 相同】的重复交易仍保留执行——例如请求书架需要绿宝石时，纸→绿宝石的
        // 重复交易继续补充绿宝石（否则请求与存量补充会被同时取消、流程变慢）。
        for (final Map.Entry<Integer, RepeatPlan> entry : new ArrayList<>(repeatPlans.entrySet()))
        {
            if (onDemandRequests.isEmpty() || repeatPlanProducesRequestSellable(entry.getKey()))
            {
                final RepeatPlan plan = entry.getValue();
                if (plan.sellable() != null
                    && countAvailableAnywhere(plan.sellable().getItemStack()) >= plan.sellableCount())
                {
                    if (!result.contains(entry.getKey()))
                    {
                        result.add(entry.getKey());
                    }
                }
            }
        }
        // 3. 存量补充：小屋绿宝石低于最低存量时，产出绿宝石的交易按【总览】优先级
        //    分摊补充——单个交易达到村民单次交易上限（maxUses）后由下一个交易继续。
        int emeraldRemaining = emeraldStockGap();
        if (emeraldRemaining > 0)
        {
            for (final int flat : getAvailableOffersInOrder())
            {
                if (emeraldRemaining <= 0)
                {
                    break;
                }
                final TradeOfferData offer = getOffer(flat);
                if (offer == null || offer.result().getItem() != Items.EMERALD)
                {
                    continue;
                }
                final int perTrade = Math.max(1, offer.result().getCount());
                final int needed = (emeraldRemaining + perTrade - 1) / perTrade;
                final int alloc = Math.min(needed, Math.max(1, offer.maxUses()));
                if (alloc <= 0)
                {
                    continue;
                }
                result.add(flat);
                emeraldRemaining -= alloc * perTrade;
            }
        }
        return result;
    }

    /** 需求（请求优先·优化）：该重复交易的结果物品是否为某个接入请求的售出物。 */
    private boolean repeatPlanProducesRequestSellable(final int flatIndex)
    {
        if (onDemandRequests.isEmpty())
        {
            return true;
        }
        final TradeOfferData offer = getOffer(flatIndex);
        if (offer == null)
        {
            return false;
        }
        for (final OnDemandEntry demand : onDemandRequests.values())
        {
            if (demand.sellable() != null
                && ItemStackUtils.compareItemStacksIgnoreStackSize(
                    demand.sellable().getItemStack(), offer.result()))
            {
                return true;
            }
        }
        return false;
    }

    /** 需求（非递归重构）：当前交易的副本数——主请求返回接入时分配的副本数；
     *  重复交易返回计划副本数（受 maxUses 硬上限）；存量补充按总览顺序分摊。 */
    public int getCurrentTradeCopies(final int flatIndex)
    {
        for (final OnDemandEntry entry : onDemandRequests.values())
        {
            final int at = entry.tradeIndices().indexOf(flatIndex);
            if (at >= 0)
            {
                // 需求（bug 修复）：校验产出物匹配，防止平铺索引偏移误命中其它交易。
                final TradeOfferData offer = getOffer(flatIndex);
                if (offer != null && ItemStackUtils.compareItemStacksIgnoreStackSize(
                    offer.result(), entry.item().getItemStack()))
                {
                    final int allocated = entry.tradeCopies().get(at);
                    // 需求（bug 修复·大量请求分批）：按当前售出物已有量动态计算
                    // 本次可执行的副本数——部分送达（如需求 108 绿宝石只到 39）时
                    // 先执行 floor(已有/单次成本) 次交易，消耗后由健康检查补货。
                    if (entry.sellable() == null)
                    {
                        return Math.max(1, allocated);
                    }
                    final int perTradeCost = Math.max(1, entry.sellable().getItemStack().getCount());
                    final int have = countAvailableAnywhere(entry.sellable().getItemStack());
                    final int feasible = have / perTradeCost;
                    // 需求（bug 修复）：可行副本为 0（售出物不足以完成一次交易）时
                    // 返回 0，让行程规划等待补货（避免生成无法执行的空副本）。
                    return Math.min(allocated, Math.max(0, feasible));
                }
            }
        }
        final RepeatPlan plan = repeatPlans.get(flatIndex);
        if (plan != null)
        {
            // 需求（maxUses 硬上限）：重复交易副本数不超过村民单次交易上限，
            // 否则一次出行执行次数远超村民交易可用次数（如 4 组绿宝石 = 256 次交易）。
            final TradeOfferData offer = getOffer(flatIndex);
            final int cap = offer != null ? Math.max(1, offer.maxUses()) : Integer.MAX_VALUE;
            return Math.max(1, Math.min(plan.copies(), cap));
        }
        final int emeraldGap = emeraldStockGap();
        if (emeraldGap > 0)
        {
            // 需求（存量补充分摊 + maxUses 硬上限）：按【总览】优先级模拟分摊，
            // 返回该交易本次分配到的次数（单个交易达到村民单次交易上限后由下一个继续）。
            int remaining = emeraldGap;
            for (final int flat : getAvailableOffersInOrder())
            {
                if (remaining <= 0)
                {
                    break;
                }
                final TradeOfferData offer = getOffer(flat);
                if (offer == null || offer.result().getItem() != Items.EMERALD)
                {
                    continue;
                }
                final int perTrade = Math.max(1, offer.result().getCount());
                final int needed = (remaining + perTrade - 1) / perTrade;
                final int alloc = Math.min(needed, Math.max(1, offer.maxUses()));
                if (flat == flatIndex)
                {
                    return Math.max(1, alloc);
                }
                remaining -= alloc * perTrade;
            }
        }
        return 1;
    }

    /** 需求：绿宝石最低存量缺口（目标 − 小屋/背包已有）。 */
    private int emeraldStockGap()
    {
        final CaravanStockModule stockModule = getBuilding() != null
            ? getBuilding().getFirstModuleOccurance(CaravanStockModule.class) : null;
        if (stockModule == null || stockModule.getMinEmeraldStock() <= 0)
        {
            return 0;
        }
        final ItemStack emerald = new ItemStack(Items.EMERALD);
        final int target = stockModule.getMinEmeraldStock() * CaravanStockModule.EMERALD_PER_STACK;
        return Math.max(0, target - countAvailableAnywhere(emerald));
    }

    /**
     * 需求（0.4.18 回退）：为标记为【重复】的交易维护售出物请求——
     * 每次出行后重新创建请求并试图执行；若小屋/商队物品栏已有相应售出物，
     * 则不创建请求，直接进入可执行状态。
     */
    private void ensureRepeatTradePlans()
    {
        final IRequestManager manager = getBuilding() != null && getBuilding().getColony() != null
            ? getBuilding().getColony().getRequestManager() : null;
        if (manager == null)
        {
            return;
        }
        for (final int flat : getAvailableOffersInOrder())
        {
            if (getMode(flat) != TradeMode.REPEAT)
            {
                continue;
            }
            final TradeOfferData offer = getOffer(flat);
            if (offer == null || offer.costs().isEmpty())
            {
                continue;
            }
            final int copies = Math.max(1, getCopiesFor(flat));
            final ItemStack sellable = offer.costs().get(0);
            // 需求（弹性）：售出物请求下限 = 单次成本，上限 = min(村民单次交易上限,
            // 手动设置交易次数) × 单次成本。
            final int perTrade = Math.max(1, sellable.getCount());
            final int maxTrades = Math.min(Math.max(1, offer.maxUses()), copies);
            final int sellableCount = perTrade * maxTrades;
            final int minCount = Math.max(1, perTrade);
            final RepeatPlan existing = repeatPlans.get(flat);
            if (existing != null && existing.sellableRequest() != null)
            {
                final IRequest<?> request = manager.getRequestForToken(existing.sellableRequest());
                if (request != null && isRequestOpen(request.getState()))
                {
                    continue; // 售出物运送中。
                }
                repeatPlans.remove(flat); // 请求已结束（送达/取消）→ 重新评估。
            }
            // 需求（任务2）：发起请求前检查小屋及商队（领袖+成员）物品栏，
            // 已有相应物品时不创建请求，直接可执行。
            if (countAvailableAnywhere(sellable) >= sellableCount)
            {
                repeatPlans.put(flat, new RepeatPlan(null, new ItemStorage(sellable), sellableCount, copies));
                continue;
            }
            final IToken<?> token = manager.createAndAssignRequest(getBuilding().getRequester(),
                new com.minecolonies.api.colony.requestsystem.requestable.Stack(
                    sellable.copyWithCount(1), sellableCount, minCount));
            repeatPlans.put(flat, new RepeatPlan(token, new ItemStorage(sellable), sellableCount, copies));
            // 需求（调试）：创建售出品运送请求。
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 创建售出品运送请求 {}（{} x{}..{}，重复交易 {}）",
                String.valueOf(token), sellable.getHoverName().getString(), minCount, sellableCount, flat);
        }
        repeatPlans.keySet().removeIf(flat -> getMode(flat) != TradeMode.REPEAT);
    }

    private static boolean isRequestOpen(final RequestState state)
    {
        return state != RequestState.COMPLETED
            && state != RequestState.CANCELLED
            && state != RequestState.OVERRULED
            && state != RequestState.FAILED;
    }

    /**
     * 需求（通报时机）：商队小屋检测到某请求的物品数量已满足要求时通报“请求已完成”
     * （而非等到交易标记完成），并【直接完成该请求】（解除子请求关系后置 RESOLVED，
     * 本体自动转 COMPLETED）——否则请求链依赖“子请求全部完成”的级联，
     * 而部分售出物子请求（如殖民地无法生产的木棍）永远无人完成，父请求将卡死。
     * 同时输出“售出物运送完成”的调试信息。
     */
    private void checkCompletedBroadcasts()
    {
        final IRequestManager manager = getBuilding() != null && getBuilding().getColony() != null
            ? getBuilding().getColony().getRequestManager() : null;
        if (manager == null)
        {
            return;
        }
        // 需求（通报时机）：每殖民地刻检查售出品是否已齐备并通报“将在下次出发时交易”。
        checkPreparedBroadcasts();
        for (final Map.Entry<IToken<?>, OnDemandEntry> entry : new ArrayList<>(onDemandRequests.entrySet()))
        {
            final OnDemandEntry demand = entry.getValue();
            // 需求（调试）：售出物运送完成（各分摊 S 请求 COMPLETED）。
            for (final IToken<?> sellableToken : demand.sellableRequests())
            {
                if (sellableCompletedLogged.add(sellableToken))
                {
                    final IRequest<?> sellable = manager.getRequestForToken(sellableToken);
                    if (sellable != null && sellable.getState() == RequestState.COMPLETED)
                    {
                        com.example.caravan.CaravanMod.LOGGER.info(
                            "Caravan: 售出品运送完成 {}（{} x{}）",
                            String.valueOf(sellableToken),
                            demand.sellable() != null ? demand.sellable().getItemStack().getHoverName().getString() : "?",
                            demand.sellableCount());
                    }
                }
            }
            // 需求（多交易分摊）：全部售出物子请求完成且【小屋】物品满足时，
            // 完成“商队交易”标记（M）——以小屋到货为准，物品还在领袖背包时不提前完成。
            if (allSellableRequestsDone(demand)
                && countInHut(demand.item().getItemStack()) >= demand.count())
            {
                completeTradeMarker(manager, demand.tradeMarker());
            }
            // 需求（通报时机）：【小屋】检测到物品数量满足要求时通报（不包含物品栏）。
            if (!completedNotified.contains(entry.getKey())
                && countInHut(demand.item().getItemStack()) >= demand.count())
            {
                completedNotified.add(entry.getKey());
                // 需求（诊断·断点）：物品满足时刻（交易成果放回小屋后）。
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 检测到物品满足（{} x{}，请求 {}），进入完成流程",
                    demand.item().getItemStack().getHoverName().getString(),
                    demand.count(), String.valueOf(entry.getKey()));
                broadcastCompleted(manager, entry.getKey());
            }
            // 需求（成果运送修复）：【小屋】物品已满足 或 已有成果运送（Delivery）在途——
            // 外部请求：创建/检测本体 Delivery（商队小屋 → 请求方），快递员送达完成
            //          后再完成主请求；注意成品被快递员取走后小屋计数会短暂不足，
            //          此时【必须】继续检测 Delivery 完成状态，否则主请求永不注销。
            // 商队小屋自身的内部链请求：无需送达，直接完成。
            final boolean itemReady = countInHut(demand.item().getItemStack()) >= demand.count();
            if (itemReady || finalDeliveries.containsKey(entry.getKey()))
            {
                final IRequest<?> request = manager.getRequestForToken(entry.getKey());
                if (request != null && request.getRequester() != null
                    && !request.getRequester().getLocation().getInDimensionLocation()
                        .equals(getBuilding().getPosition()))
                {
                    ensureFinalDelivery(manager, entry.getKey(), demand, request);
                }
                else if (itemReady)
                {
                    completeRequestDirectly(manager, entry.getKey());
                }
            }
        }
        sellableCompletedLogged.removeIf(token -> {
            final IRequest<?> request = manager.getRequestForToken(token);
            return request == null || request.getState() != RequestState.COMPLETED;
        });
    }

    /** 需求（多交易分摊）：该记录的全部分摊售出物请求（S）是否都已送达完成。 */
    private boolean allSellableRequestsDone(final OnDemandEntry demand)
    {
        // 需求（bug 修复）：以“售出物实际数量”为送达信号——minecolonies 在
        // 快递员送达（RESOLVED）或级联取消后会把 S 请求对象清理出请求系统
        // （cleanRequestData），仅检查 S 请求状态会导致“运送到达后永远检测
        // 不到完成、商队交易任务不创建”（getRequestForToken 返回 null）。
        if (demand.sellable() == null)
        {
            return true; // 无售出物（交易无成本）→ 直接可执行。
        }
        // 需求（bug 修复）：没有售出物子请求（S 为空 = 接入时已有量足够或交易无成本）
        // 时直接视为“已齐备”——否则交易完成后售出物被消耗（如 9 绿宝石支付掉），
        // 数量判定恒 false，导致“商队交易”标记（M）永远无法完成、只能被注销。
        if (demand.sellableRequests().isEmpty())
        {
            return true;
        }
        // 需求（bug 修复·大量请求分批）：仓库不足导致部分送达（如需求 108 绿宝石、
        // 只送达 39）时，只要满足【至少一次交易】的售出物量就视为“可执行”——
        // 商队先按已有量创建交易（能交易几次做几次），消耗后再由健康检查按新缺口
        // 补建售出物请求，避免“部分送达后永远无法创建交易”的卡死。
        final int perTradeReady = Math.max(1, demand.sellableCount() / Math.max(1, demand.tradeCount()));
        return countAvailableAnywhere(demand.sellable().getItemStack()) >= perTradeReady;
    }

    /**
     * 需求（非递归重构）：每殖民地刻检查已接入主请求的售出物子请求（S）——
     * 若殖民地无法提供售出品（请求停留在重试/玩家解析方、被取消/失败或已消失），
     * 则注销 S 与“商队交易”标记（M）并把主请求【下放】回请求系统
     * （主请求仍留在重试/玩家解析方，由玩家处理；下次商队归来后再检查是否重新接入）。
     */
    private void checkAdoptedRequestHealth()
    {
        final IRequestManager manager = getBuilding() != null && getBuilding().getColony() != null
            ? getBuilding().getColony().getRequestManager() : null;
        if (manager == null)
        {
            return;
        }
        // 需求（诊断·断点）：收集售出物子请求（S）状态明细（每 100 殖民地刻输出）。
        final StringBuilder sDetail = new StringBuilder();
        for (final Map.Entry<IToken<?>, OnDemandEntry> entry : new ArrayList<>(onDemandRequests.entrySet()))
        {
            // 需求（bug 修复）：主请求已结束（玩家手动取消/系统完成/失败）时
            // 清理接入记录并级联注销残留子请求，避免记录悬挂导致重复规划交易。
            final IRequest<?> mainRequest = manager.getRequestForToken(entry.getKey());
            if (mainRequest == null)
            {
                // 主请求对象已消失：直接清理记录并级联注销残留子请求。
                removeOnDemandRequest(manager, entry.getKey());
                continue;
            }
            final RequestState mainState = mainRequest.getState();
            if (mainState == RequestState.CANCELLED || mainState == RequestState.FAILED
                || mainState == RequestState.OVERRULED || mainState == RequestState.COMPLETED
                || mainState == RequestState.RESOLVED)
            {
                removeOnDemandRequest(manager, entry.getKey());
                continue;
            }
            // 需求（bug 修复）：已进入完成流程（小屋到货、等待成品运送）的请求
            // 不再做任何售出物检查/补建——成品可能已被快递员取走（小屋计数短暂
            // 不足），此时补建售出物请求会引发“额外交易→再送售出物”循环，
            // 并导致主请求无法结单。
            if (completedNotified.contains(entry.getKey()))
            {
                continue;
            }
            final OnDemandEntry demand = entry.getValue();
            if (demand.sellable() == null || demand.sellableRequests().isEmpty())
            {
                // 需求（bug 修复）：物品已满足（等待成果运送/完成流程）时
                // 不再补建售出物请求——否则交易完成后会再次创建 S 子请求
                // （表现为“请求链里多出一个 9 绿宝石 S 节点”）。
                if (countAvailableAnywhere(demand.item().getItemStack()) >= demand.count())
                {
                    continue;
                }
                // 需求（防御，bug 修复）：接入时已有量足够（未创建 S 请求），
                // 但之后售出物可能被消耗/取走——存量再次不足时补建售出物运送请求，
                // 保证“小屋存储/物品栏有部分售出品时仍会触发运送请求补充到足量”。
                // 需求（bug 修复）：行程进行中不补建——售出物已被提取到领袖/成员
                // 物品栏或成为模拟成果，补建会导致“出发后快递员再次运送售出物”。
                if (!tripActive
                    && demand.sellable() != null
                    && countAvailableAnywhere(demand.sellable().getItemStack())
                        < Math.max(1, demand.sellableCount() / Math.max(1, demand.tradeCount())))
                {
                    final List<IToken<?>> created = new ArrayList<>();
                    final Map<ItemStorage, Integer> allocatedHave = new HashMap<>();
                    for (int i = 0; i < demand.tradeIndices().size(); i++)
                    {
                        final int flat = demand.tradeIndices().get(i);
                        final int alloc = demand.tradeCopies().get(i);
                        final TradeOfferData offer = getOffer(flat);
                        if (offer == null || offer.costs().isEmpty())
                        {
                            continue;
                        }
                        // 需求（请求链重构）：补建的 S 同样挂到 M 节点下。
                        final ItemStack sellable = offer.costs().get(0);
                        final ItemStorage key = new ItemStorage(sellable);
                        final int remainingHave = Math.max(0,
                            countAvailableAnywhere(sellable) - allocatedHave.getOrDefault(key, 0));
                        final int gross = Math.max(1, sellable.getCount()) * alloc;
                        created.add(createSellableRequestForTrade(
                            manager, demand.tradeMarker(), flat, alloc, sellable, remainingHave));
                        allocatedHave.merge(key, Math.min(gross, remainingHave), Integer::sum);
                    }
                    if (!created.isEmpty())
                    {
                        onDemandRequests.put(entry.getKey(), new OnDemandEntry(
                            demand.item(), demand.count(), demand.sellable(), demand.sellableCount(),
                            created, demand.tradeMarker(), demand.tradeCount(),
                            demand.tradeIndices(), demand.tradeCopies()));
                        markDirty();
                        com.example.caravan.CaravanMod.LOGGER.info(
                            "Caravan: 主请求 {} 售出物存量再次不足，补建运送请求 {} 个",
                            String.valueOf(entry.getKey()), created.size());
                    }
                }
                continue; // 无需售出物运送（或已有量足够）→ 等待交易执行。
            }
            // 需求（bug 修复·大量请求分批）：售出物已有量满足【至少一次交易】时
            // 视为可执行——商队按已有量分批交易（动态副本），消耗后再补货，
            // 不再等待“全部售出物到齐”才创建交易。
            final int perTradeReady = Math.max(1, demand.sellableCount() / Math.max(1, demand.tradeCount()));
            if (countAvailableAnywhere(demand.sellable().getItemStack()) >= perTradeReady)
            {
                continue;
            }
            // 需求（bug 修复）：物品已满足（交易完成、等待成果运送/完成流程）时
            // 不再检查售出物——交易成本（售出物）已作为交易消耗，S 请求对象也
            // 可能在送达后被系统清理；旧实现此时误判“殖民地无法提供售出品”
            // 而释放主请求，随后重新接入（主请求落到重试/玩家解析方）导致
            // 完成流程异常、重复“已接到请求”通报、主请求无法注销。
            if (countAvailableAnywhere(demand.item().getItemStack()) >= demand.count())
            {
                continue;
            }
            boolean unfulfillable = false;
            boolean anyActive = false;   // 仍有分配中/运送中的 S 请求。
            boolean anyEnded = false;    // 存在已结束（完成/取消/已清理）的 S 请求。
            for (final IToken<?> sellableToken : demand.sellableRequests())
            {
                final IRequest<?> sellable = manager.getRequestForToken(sellableToken);
                if (sellable == null)
                {
                    sDetail.append('[').append(String.valueOf(sellableToken)).append(" 已清理]");
                    // S 请求对象已被系统清理（快递员送达 RESOLVED / 级联取消后
                    // cleanRequestData）——视为“已结束”，由循环后的数量判定决定
                    // 补建还是下放。
                    anyEnded = true;
                    continue;
                }
                final RequestState state = sellable.getState();
                sDetail.append('[').append(String.valueOf(sellableToken)).append(' ')
                    .append(state).append(']');
                if (state == RequestState.COMPLETED || state == RequestState.RESOLVED
                    || state == RequestState.CANCELLED || state == RequestState.FAILED
                    || state == RequestState.OVERRULED)
                {
                    anyEnded = true; // 已结束（送达/取消/失败）。
                    continue;
                }
                if (state == RequestState.IN_PROGRESS)
                {
                    anyActive = true; // 快递员运送中 → 继续等待。
                    continue;
                }
                if (state == RequestState.ASSIGNED && isHeldByRetryingOrPlayer(manager, sellableToken))
                {
                    unfulfillable = true;
                    break;
                }
                anyActive = true; // 仍处于分配流程（CREATED/REPORTED）或被真实解析方持有。
            }
            if (unfulfillable)
            {
                releaseRequest(manager, entry.getKey(), demand);
            }
            else if (!tripActive && !anyActive && anyEnded)
            {
                // 需求（bug 修复）：全部 S 请求已结束（送达/取消/被系统清理）但
                // 售出物数量仍不足——弹性请求（min=单次成本）在部分满足时会被
                // 请求系统判定 COMPLETED（如需求 81 绿宝石、小屋已有 62 就完成），
                // 这里按分摊交易补建运送请求，保证“已有 + 补送 ≥ 总需求”。
                rebuildSellableRequests(manager, entry.getKey(), demand);
            }
        }
    }

    /**
     * 需求（bug 修复）：全部售出物子请求（S）已结束但售出物数量仍不足时，
     * 按分摊交易重新创建 S 运送请求（挂到 M 节点下），并更新接入记录。
     * 重建前先清理 M 节点 children 中的失效 token，避免后续子请求完成时
     * minecolonies 遍历到已清理的请求触发 NPE。
     */
    private void rebuildSellableRequests(
        final IRequestManager manager,
        final IToken<?> token,
        final OnDemandEntry demand)
    {
        // 清空 M 节点 children 中的旧 S 请求（无论是否已清理）——
        // 旧 S 已完成/取消后若仍挂在 M 下，新 S 完成时 minecolonies 会遍历
        // M 的全部 children 并级联取消，通知 requester（商队小屋）时
        // Map.remove(旧 S) 返回 null 触发 NPE。
        try
        {
            final IRequest<?> marker = manager.getRequestForToken(demand.tradeMarker());
            if (marker != null)
            {
                for (final IToken<?> child : new ArrayList<>(marker.getChildren()))
                {
                    try
                    {
                        final IRequest<?> childRequest = manager.getRequestForToken(child);
                        if (childRequest != null)
                        {
                            childRequest.setParent(null);
                        }
                        marker.removeChild(child);
                    }
                    catch (final Exception ignored)
                    {
                        // 单个子请求清理失败不影响整体。
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 标记可能已被清理。
        }
        final List<IToken<?>> created = new ArrayList<>();
        final Map<ItemStorage, Integer> allocatedHave = new HashMap<>();
        for (int i = 0; i < demand.tradeIndices().size(); i++)
        {
            final int flat = demand.tradeIndices().get(i);
            final int alloc = demand.tradeCopies().get(i);
            final TradeOfferData offer = getOffer(flat);
            if (offer == null || offer.costs().isEmpty())
            {
                continue;
            }
            // 需求（bug 修复）：重建的售出物请求同样减去当前已有量（缺口精确），
            // 保证每轮重建缺口递减、最终补齐，不再出现“部分送达后立即自动完成”死循环。
            final ItemStack sellable = offer.costs().get(0);
            final ItemStorage key = new ItemStorage(sellable);
            final int remainingHave = Math.max(0,
                countAvailableAnywhere(sellable) - allocatedHave.getOrDefault(key, 0));
            final int gross = Math.max(1, sellable.getCount()) * alloc;
            created.add(createSellableRequestForTrade(
                manager, demand.tradeMarker(), flat, alloc, sellable, remainingHave));
            allocatedHave.merge(key, Math.min(gross, remainingHave), Integer::sum);
        }
        if (!created.isEmpty())
        {
            onDemandRequests.put(token, new OnDemandEntry(
                demand.item(), demand.count(), demand.sellable(), demand.sellableCount(),
                created, demand.tradeMarker(), demand.tradeCount(),
                demand.tradeIndices(), demand.tradeCopies()));
            markDirty();
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 主请求 {} 售出物不足（已有 {} / 需要 {}），重建运送请求 {} 个",
                String.valueOf(token),
                demand.sellable() != null ? countAvailableAnywhere(demand.sellable().getItemStack()) : 0,
                demand.sellableCount(), created.size());
        }
    }

    /** 需求（非递归重构）：该请求是否已停留在“重试/玩家”解析方（殖民地无人可完成）。 */
    private boolean isHeldByRetryingOrPlayer(final IRequestManager manager, final IToken<?> token)
    {
        try
        {
            final IRequestResolver<?> resolver = manager.getResolverForRequest(token);
            return resolver instanceof IRetryingRequestResolver
                || resolver instanceof IPlayerRequestResolver;
        }
        catch (final Exception ignored)
        {
            return false;
        }
    }

    /**
     * 需求（非递归重构）：注销主请求的售出物子请求（S）与“商队交易”标记（M），
     * 移除接入记录并把主请求加入“已下放”集合——主请求本身保留在请求系统
     * （重试/玩家解析方），直到下一次商队归来后重新检查。
     */
    private void releaseRequest(
        final IRequestManager manager,
        final IToken<?> token,
        final OnDemandEntry demand)
    {
        for (final IToken<?> sellableToken : demand.sellableRequests())
        {
            cancelChainRequest(manager, sellableToken);
        }
        if (demand.tradeMarker() != null)
        {
            cancelChainRequest(manager, demand.tradeMarker());
        }
        onDemandRequests.remove(token);
        finalDeliveries.remove(token);
        releasedRequests.add(token);
        markDirty();
        com.example.caravan.CaravanMod.LOGGER.info(
            "Caravan: 殖民地无法提供售出品，注销售出物子请求并将主请求 {} 下放",
            String.valueOf(token));
    }

    /**
     * 需求（非递归重构）：商队每次交易归来后调用——对每一个“已下放”且仍未完成的
     * 主请求重新检查：有匹配的【按需】交易则再次接入（创建售出物子请求），
     * 否则继续保持下放状态。
     */
    public void recheckReleasedRequestsAfterReturn()
    {
        final IRequestManager manager = getBuilding() != null && getBuilding().getColony() != null
            ? getBuilding().getColony().getRequestManager() : null;
        if (manager == null)
        {
            return;
        }
        final BlockPos hutPos = getBuilding() != null ? getBuilding().getPosition() : null;
        for (final IToken<?> token : new ArrayList<>(releasedRequests))
        {
            final IRequest<?> request = manager.getRequestForToken(token);
            if (request == null || request.getRequester() == null)
            {
                releasedRequests.remove(token); // 请求已消失。
                continue;
            }
            final RequestState state = request.getState();
            if (state == RequestState.COMPLETED || state == RequestState.CANCELLED
                || state == RequestState.OVERRULED || state == RequestState.FAILED
                || state == RequestState.RESOLVED)
            {
                releasedRequests.remove(token); // 请求已结束。
                continue;
            }
            if (!(request.getRequest() instanceof IDeliverable deliverable))
            {
                releasedRequests.remove(token);
                continue;
            }
            // 需求（bug 修复）：已进入完成流程的请求不再重新接入。
            if (completedNotified.contains(token))
            {
                releasedRequests.remove(token);
                continue;
            }
            // 商队小屋自身请求不接入（递归已删除）。
            try
            {
                if (hutPos != null && request.getRequester().getLocation()
                    .getInDimensionLocation().equals(hutPos))
                {
                    releasedRequests.remove(token);
                    continue;
                }
            }
            catch (final Exception ignored)
            {
                // 位置不可用时继续判断。
            }
            if (hasOnDemandOfferFor(deliverable))
            {
                releasedRequests.remove(token);
                adoptRequest(manager, request, deliverable);
            }
        }
        markDirty();
    }

    /** 完成“商队交易”标记子请求（M 节点）——售出物齐备且物品满足时调用。 */
    private void completeTradeMarker(final IRequestManager manager, final IToken<?> markerToken)
    {
        if (markerToken == null)
        {
            return;
        }
        try
        {
            final IRequest<?> marker = manager.getRequestForToken(markerToken);
            if (marker != null)
            {
                // 需求（请求链重构）：先解除 M 与全部 S 子请求的父子关系——
                // M 完成后可能被系统清理（cleanRequestData），若 S 仍挂在 M 下，
                // S 之后完成时 onChildRequestCancelled 会因父请求（M）已消失而 NPE。
                for (final IToken<?> child : new ArrayList<>(marker.getChildren()))
                {
                    try
                    {
                        final IRequest<?> childRequest = manager.getRequestForToken(child);
                        if (childRequest != null)
                        {
                            childRequest.setParent(null);
                        }
                        marker.removeChild(child);
                    }
                    catch (final Exception ignored)
                    {
                        // 子请求可能已清理。
                    }
                }
                // 需求（bug 修复）：先解除与主请求的父子关系——minecolonies 的
                // 完成/取消流程会遍历父请求 children，提前移除可避免失效 token 残留。
                try
                {
                    if (marker.hasParent())
                    {
                        final IRequest<?> parent = manager.getRequestForToken(marker.getParent());
                        if (parent != null && parent.getChildren().contains(markerToken))
                        {
                            parent.removeChild(markerToken);
                        }
                        marker.setParent(null);
                    }
                }
                catch (final Exception ignored)
                {
                    // 父子关系维护失败不影响标记完成。
                }
                final RequestState state = marker.getState();
                if (state == RequestState.CREATED || state == RequestState.ASSIGNED
                    || state == RequestState.IN_PROGRESS)
                {
                    manager.updateRequestState(markerToken, RequestState.RESOLVED);
                    com.example.caravan.CaravanMod.LOGGER.info(
                        "Caravan: 商队交易标记 {} 完成（售出物已齐备）",
                        String.valueOf(markerToken));
                }
            }
        }
        catch (final Exception ex)
        {
            // 标记可能已被清理。
            com.example.caravan.CaravanMod.LOGGER.warn(
                "Caravan: 完成商队交易标记 {} 失败", String.valueOf(markerToken), ex);
        }
    }

    /**
     * 需求（成果运送修复）：为外部请求创建本体 Delivery 子请求（成果从商队小屋送达
     * 请求方建筑），快递员送达（COMPLETED）后再完成主请求。
     */
    private void ensureFinalDelivery(
        final IRequestManager manager,
        final IToken<?> token,
        final OnDemandEntry demand,
        final IRequest<?> request)
    {
        try
        {
            final IToken<?> existing = finalDeliveries.get(token);
            if (existing != null)
            {
                final IRequest<?> delivery = manager.getRequestForToken(existing);
                if (delivery == null)
                {
                    finalDeliveries.remove(token);
                    // 需求（bug 修复）：Delivery 对象已被系统清理（快递员完成 RESOLVED
                    // 或取消后 cleanRequestData 移除）——此时无论小屋中物品是否还在
                    // （快递员取货后小屋可能已空），都直接完成主请求；成果已由
                    // 快递员送达请求方。避免“送货到达后主请求永不注销”。
                    com.example.caravan.CaravanMod.LOGGER.info(
                        "Caravan: 成果运送 {} 已完成并被系统清理，直接完成主请求 {}",
                        String.valueOf(existing), String.valueOf(token));
                    detachDeliveryFromRequest(manager, token, existing);
                    completeRequestDirectly(manager, token);
                }
                else if (delivery.getState() == RequestState.RESOLVED
                    || delivery.getState() == RequestState.COMPLETED)
                {
                    // 需求（0.4.20 参考）：本体快递员送达后 Delivery 状态为 RESOLVED
                    // （随后被系统清理），必须同时接受 RESOLVED 与 COMPLETED，
                    // 否则检测不到送达、主请求永不注销。
                    // 需求（调试）：成果运送完成。
                    com.example.caravan.CaravanMod.LOGGER.info(
                        "Caravan: 成果运送完成 {}（{} x{} → 请求方）",
                        String.valueOf(existing), demand.item().getItemStack().getHoverName().getString(),
                        demand.count());
                    finalDeliveries.remove(token);
                    detachDeliveryFromRequest(manager, token, existing);
                    completeRequestDirectly(manager, token);
                }
                else
                {
                    // 仍在运送中——等待送达（不输出周期日志）。
                }
                return;
            }
            final ItemStack stack = demand.item().getItemStack().copy();
            stack.setCount(demand.count());
            final ILocation start = getBuilding().getRequester().getLocation();
            final ILocation target = request.getRequester().getLocation();
            // 需求（bug 修复）：Delivery 请求的 requester 必须是【商队小屋】——
            // 旧实现误用原请求方（如信箱 PostBox），快递员送达后请求系统通知
            // requester 的 onRequestedRequestCancelled，而信箱的记录中没有该 token，
            // AbstractBuilding.onRequestedRequestCancelled 里 Map.remove() 返回 null
            // 触发 NPE，快递员 AI 被暂停（Pausing Citizen ... because of error）。
            final IToken<?> deliveryToken = manager.createAndAssignRequest(getBuilding().getRequester(),
                new Delivery(start, target, stack,
                    AbstractDeliverymanRequestable.getMaxBuildingPriority(false)));
            // 需求（稳定性）：Delivery 不挂为主请求的子请求——minecolonies 在
            // 子请求完成/取消时会遍历父请求全部 children 并 reassign 父请求，
            // 挂父子后（即使 children 干净）Delivery 完成也会触发主请求重新分配，
            // 导致商队再次接单、请求链无法结单。送达完成由回调
            // （BuildingCaravanLeader.onRequestedRequestCancelled）与 finalDeliveries
            // 轮询双重检测。
            finalDeliveries.put(token, deliveryToken);
            // 需求（调试）：创建成果运送任务。
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 创建成果运送任务 {}（{} x{} → 请求方）",
                String.valueOf(deliveryToken), stack.getHoverName().getString(), stack.getCount());
        }
        catch (final Exception ex)
        {
            // 创建失败时下轮重试。
            com.example.caravan.CaravanMod.LOGGER.warn(
                "Caravan: 创建成果运送任务失败（请求 {}）", String.valueOf(token), ex);
        }
    }

    /** 需求（GUI 请求链）：从主请求 children 移除已完成的 Delivery，保持父子关系干净。 */
    private void detachDeliveryFromRequest(
        final IRequestManager manager,
        final IToken<?> mainToken,
        final IToken<?> deliveryToken)
    {
        try
        {
            final IRequest<?> main = manager.getRequestForToken(mainToken);
            if (main != null && main.getChildren().contains(deliveryToken))
            {
                main.removeChild(deliveryToken);
            }
            final IRequest<?> delivery = manager.getRequestForToken(deliveryToken);
            if (delivery != null)
            {
                delivery.setParent(null);
            }
        }
        catch (final Exception ignored)
        {
            // 清理失败不影响主请求完成。
        }
    }

    /**
     * 需求（成果运送完成）：由 {@link BuildingCaravanLeader} 在收到“请求取消/完成”
     * 通知时调用——若通知的请求是本模块创建的成果运送（Delivery），
     * 立即完成主请求（不依赖轮询），并从主请求 children 移除 Delivery。
     *
     * @return true 表示该请求是商队成果运送并已处理。
     */
    public boolean handleDeliveryRequestFinished(
        final IRequestManager manager,
        final IRequest<?> deliveryRequest)
    {
        IToken<?> mainToken = null;
        for (final Map.Entry<IToken<?>, IToken<?>> entry : finalDeliveries.entrySet())
        {
            if (entry.getValue().equals(deliveryRequest.getId()))
            {
                mainToken = entry.getKey();
                break;
            }
        }
        if (mainToken == null)
        {
            return false;
        }
        finalDeliveries.remove(mainToken);
        com.example.caravan.CaravanMod.LOGGER.info(
            "Caravan: 成果运送回调完成 {}（主请求 {}）",
            String.valueOf(deliveryRequest.getId()), String.valueOf(mainToken));
        detachDeliveryFromRequest(manager, mainToken, deliveryRequest.getId());
        completeRequestDirectly(manager, mainToken);
        return true;
    }

    /**
     * 需求（链注销修复）：直接完成某请求——解除其全部子请求关系后置 RESOLVED，
     * 由本体 onRequestResolved 自动转 COMPLETED（无子请求时）；随后 requester
     * 收到完成通知：外部建筑创建 Delivery 送货，商队小屋自身的内部链请求跳过送达。
     */
    private void completeRequestDirectly(final IRequestManager manager, final IToken<?> token)
    {
        try
        {
            final IRequest<?> request = manager.getRequestForToken(token);
            if (request == null)
            {
                // 需求（诊断·断点）：主请求对象已消失（可能已被系统清理）。
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 完成请求 {}：对象已消失（直接清理记录）",
                    String.valueOf(token));
                onDemandRequests.remove(token);
                releasedRequests.remove(token);
                takeOverCooldowns.remove(token);
                markDirty();
                return;
            }
            // 需求（诊断·断点）：完成请求入口——输出当前状态与解析方。
            String resolverName = "?";
            try
            {
                final IRequestResolver<?> resolver = manager.getResolverForRequest(token);
                resolverName = resolver != null ? resolver.getClass().getSimpleName() : "null";
            }
            catch (final Exception ignored)
            {
                // 解析方查询失败不影响完成。
            }
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 完成请求 {}（状态 {}，解析方 {}）",
                String.valueOf(token), request.getState(), resolverName);
            // 解除子请求关系（子请求各自独立，不再阻塞父请求完成）；
            // 子请求对象可能已被清理（getRequestForToken 返回 null），
            // 此时也必须从父请求子列表中移除，否则 hasChildren 恒为 true，
            // 置 RESOLVED 后会被本体 onRequestResolved 卡在 FOLLOWUP_IN_PROGRESS。
            for (final IToken<?> child : new ArrayList<>(request.getChildren()))
            {
                // 需求（工匠接管）：主请求可能残留工匠的“配方/原料”子请求链——
                // 完成主请求时级联取消并解除全部残留子请求（cancelChainRequest
                // 会先从父请求 children 移除，不会触发父请求 reassign），
                // 避免孤儿请求一直挂在请求系统中。
                cancelChainRequest(manager, child);
            }
            final RequestState state = request.getState();
            if (state == RequestState.RESOLVED || state == RequestState.COMPLETED)
            {
                onDemandRequests.remove(token);
                releasedRequests.remove(token);
                takeOverCooldowns.remove(token);
                markDirty();
                return;
            }
            // 需求（状态机）：ASSIGNED → RESOLVED 可能不是合法跳转，
            // 先经 IN_PROGRESS（本体“快递员执行中”状态）再 RESOLVED，
            // 确保 updateRequestState 不抛 IllegalStateTransition。
            try
            {
                manager.updateRequestState(token, RequestState.IN_PROGRESS);
            }
            catch (final Exception ignored)
            {
                // 已是 IN_PROGRESS 或其它状态时忽略。
            }
            manager.updateRequestState(token, RequestState.RESOLVED);
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 小屋物品已满足，直接完成请求 {}（{} x{}）",
                String.valueOf(token),
                request.getRequest() instanceof IDeliverable deliverable
                    ? deliverable.getResult().getHoverName().getString() : "?",
                request.getRequest() instanceof IDeliverable deliverable2 ? deliverable2.getCount() : 0);
            // 记录由 requester/resolver 回调清理，此处兜底移除。
            onDemandRequests.remove(token);
            releasedRequests.remove(token);
            takeOverCooldowns.remove(token);
            markDirty();
        }
        catch (final Exception ex)
        {
            // 状态流转失败时保持现状（下轮重试）。
            com.example.caravan.CaravanMod.LOGGER.warn(
                "Caravan: 直接完成请求 {} 失败", String.valueOf(token), ex);
        }
    }

    /** 需求（防重复接单）：该请求是否已被商队小屋处理（记录中）。 */
    public boolean isOnDemandRequestHandled(final IToken<?> token)
    {
        return onDemandRequests.containsKey(token);
    }

    /** 需求（非递归重构）：该请求是否已被商队下放（下次商队归来前不再接入）。 */
    public boolean isReleasedRequest(final IToken<?> token)
    {
        return releasedRequests.contains(token);
    }

    /** 需求（诊断·断点）：当前接入的主请求数量。 */
    public int getOnDemandEntryCount()
    {
        return onDemandRequests.size();
    }

    /** 需求（诊断·断点）：当前重复交易计划数量。 */
    public int getRepeatPlanCount()
    {
        return repeatPlans.size();
    }

    /** 需求（诊断·断点）：绿宝石最低存量缺口（调试用）。 */
    public int getEmeraldStockGapForDebug()
    {
        return emeraldStockGap();
    }

    /** 需求（bug 修复）：AI 创建行程时通知模块（抑制行程中的售出物补建）。 */
    public void setTripActive(final boolean active)
    {
        tripActive = active;
    }

    /** 需求（bug 修复）：查询该请求是否已进入完成流程（物品满足、等待成品运送）。 */
    public boolean isOnDemandRequestCompleted(final IToken<?> token)
    {
        return completedNotified.contains(token);
    }

    /** 需求（取货保护）：是否仍有未完成的按需请求缺口。 */
    public boolean hasPendingOnDemandRequests()
    {
        return !onDemandRequests.isEmpty();
    }

    /** 需求（请求系统接入）：返回匹配该请求的第一个【重复】/【按需】交易的结果物品。 */
    public ItemStack findProductionResultFor(final IDeliverable deliverable)
    {
        for (int flat = 0; flat < getTotalOfferCount(); flat++)
        {
            final TradeMode mode = getMode(flat);
            if (mode != TradeMode.REPEAT && mode != TradeMode.ON_DEMAND)
            {
                continue;
            }
            final TradeOfferData offer = getOffer(flat);
            if (offer != null && deliverable.matches(offer.result()))
            {
                return offer.result();
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 需求（通报）：商队接管特定请求时广播——
     * “【商队】已接到来自[XXX]的[YYY×Z]请求。”
     */
    public void broadcastAccepted(final IRequestManager manager, final IToken<?> originalToken)
    {
        try
        {
            final IRequest<?> request = manager.getRequestForToken(originalToken);
            final OnDemandEntry demand = onDemandRequests.get(originalToken);
            if (request == null || demand == null)
            {
                return;
            }
            final String requester = requesterName(manager, request);
            final String item = demand.item().getItemStack().getHoverName().getString();
            broadcast(Component.translatable("com.caravan.chat.accepted",
                requester, item, demand.count()));
        }
        catch (final Exception ignored)
        {
            // 通报失败不影响核心功能。
        }
    }

    /**
     * 需求（通报）：商队领袖取得请求售出品时广播（每个请求仅一次）——
     * “【商队】来自[XXX]的[YYY×Z]请求将在下次出发时进行交易。”
     */
    public void broadcastPrepared(final IRequestManager manager, final ItemStack result, final int count)
    {
        try
        {
            for (final Map.Entry<IToken<?>, OnDemandEntry> entry : onDemandRequests.entrySet())
            {
                if (preparedNotified.contains(entry.getKey()))
                {
                    continue;
                }
                if (!ItemStackUtils.compareItemStacksIgnoreStackSize(
                    entry.getValue().item().getItemStack(), result))
                {
                    continue;
                }
                final IRequest<?> request = manager.getRequestForToken(entry.getKey());
                if (request == null)
                {
                    continue;
                }
                preparedNotified.add(entry.getKey());
                broadcast(Component.translatable("com.caravan.chat.prepared",
                    requesterName(manager, request),
                    result.getHoverName().getString(), count));
            }
        }
        catch (final Exception ignored)
        {
            // 通报失败不影响核心功能。
        }
    }

    /** 需求（通报）：商队领袖将请求交易成果放回小屋时广播——
     *  “【商队】来自[XXX]的[YYY×Z]请求已完成。” */
    public void broadcastCompleted(final IRequestManager manager, final IToken<?> originalToken)
    {
        try
        {
            final IRequest<?> request = manager.getRequestForToken(originalToken);
            final OnDemandEntry demand = onDemandRequests.get(originalToken);
            if (request == null || demand == null)
            {
                return;
            }
            broadcast(Component.translatable("com.caravan.chat.completed",
                requesterName(manager, request),
                demand.item().getItemStack().getHoverName().getString(), demand.count()));
        }
        catch (final Exception ignored)
        {
            // 通报失败不影响核心功能。
        }
    }

    /** 请求来源建筑名称（取不到时返回“未知建筑”）。 */
    private static String requesterName(final IRequestManager manager, final IRequest<?> request)
    {
        try
        {
            final BlockPos pos = request.getRequester().getLocation().getInDimensionLocation();
            final IBuilding building = manager.getColony().getServerBuildingManager()
                .getBuilding(pos, IBuilding.class);
            if (building != null && building.getBuildingType() != null)
            {
                return Component.translatable(building.getBuildingType().getTranslationKey()).getString();
            }
        }
        catch (final Exception ignored)
        {
            // 名称获取失败时回退。
        }
        return "?";
    }

    /** 广播一条系统消息给殖民地当前在线玩家。 */
    private void broadcast(final Component component)
    {
        try
        {
            if (getBuilding() == null || getBuilding().getColony() == null)
            {
                return;
            }
            final Level world = getBuilding().getColony().getWorld();
            if (world instanceof ServerLevel serverLevel)
            {
                for (final ServerPlayer player : serverLevel.players())
                {
                    player.displayClientMessage(component, false);
                }
            }
        }
        catch (final Exception ignored)
        {
            // 广播失败不影响核心功能。
        }
    }

    /**
     * 需求（通报）：商队领袖已取得某按需请求的“售出品”（对应按需交易的交易品齐备）时广播一次。
     * 由商队领袖 AI 在备货阶段周期性调用；每个请求只通报一次。
     */
    /**
     * 需求（通报时机）：每殖民地刻检测“售出品已齐备”的按需请求并通报一次
     * “将在下次出发时进行交易”——不依赖 AI 的 80 刻节拍，避免商队立即出发时漏通报。
     */
    public void checkPreparedBroadcasts()
    {
        try
        {
            if (onDemandRequests.isEmpty())
            {
                return;
            }
            final IRequestManager manager = getBuilding() != null ? getBuilding().getColony().getRequestManager() : null;
            if (manager == null)
            {
                return;
            }
            for (final Map.Entry<IToken<?>, OnDemandEntry> entry : new ArrayList<>(onDemandRequests.entrySet()))
            {
                if (preparedNotified.contains(entry.getKey()))
                {
                    continue;
                }
                final OnDemandEntry demand = entry.getValue();
                final ItemStack wanted = demand.item().getItemStack();
                // 需求（弹性 + 多交易分摊）：全部售出物子请求送达完成（子请求未完成
                // 时不通报、不创建交易）才通报“将在下次出发时交易”。
                if (wanted.isEmpty() || !allSellableRequestsDone(demand))
                {
                    continue;
                }
                broadcastPrepared(manager, wanted, demand.count());
            }
        }
        catch (final Exception ignored)
        {
            // 通报失败不影响核心功能。
        }
    }

    /** 统计某物品在小屋存储与商队全部在编市民背包中的总数量。 */
    private int countAvailableAnywhere(final ItemStack item)
    {
        int count = 0;
        try
        {
            final IItemHandler hut = getBuilding() != null ? getBuilding().getItemHandlerCap((net.minecraft.core.Direction) null) : null;
            if (hut != null)
            {
                count += countInHandler(hut, new ItemStorage(item));
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

    /**
     * 需求（通报时机）：只统计【商队小屋存储】中的物品数量——
     * “请求完成”通报与完成流程必须以小屋实际到货为准，不能包含领袖/成员
     * 物品栏（交易归来、物品仍在背包时不应提前通报）。
     */
    private int countInHut(final ItemStack item)
    {
        try
        {
            final IItemHandler hut = getBuilding() != null
                ? getBuilding().getItemHandlerCap((net.minecraft.core.Direction) null) : null;
            return hut != null ? countInHandler(hut, new ItemStorage(item)) : 0;
        }
        catch (final Exception ignored)
        {
            // 数据未就绪时按 0 处理。
        }
        return 0;
    }

    /** 需求（请求系统接入）：请求被取消时移除记录。 */
    public void removeOnDemandRequest(final IToken<?> token)
    {
        releasedRequests.remove(token);
        takeOverCooldowns.remove(token);
        if (onDemandRequests.remove(token) != null)
        {
            finalDeliveries.remove(token);
            markDirty();
        }
    }

    /**
     * 需求（0.4.19 参考）：请求被取消时移除记录，并【级联取消】其请求链子请求
     * （售出物请求 S 与商队交易标记 M）——否则手动取消请求后，因请求创建的交易
     * 不会同时取消（备货阶段取消请求时同步取消交易）。
     */
    public void removeOnDemandRequest(final IRequestManager manager, final IToken<?> token)
    {
        releasedRequests.remove(token);
        takeOverCooldowns.remove(token);
        final OnDemandEntry entry = onDemandRequests.remove(token);
        if (entry != null)
        {
            for (final IToken<?> sellableToken : entry.sellableRequests())
            {
                cancelChainRequest(manager, sellableToken);
            }
            if (entry.tradeMarker() != null)
            {
                cancelChainRequest(manager, entry.tradeMarker());
            }
            finalDeliveries.remove(token);
            markDirty();
        }
    }

    /**
     * 取消请求链上的单个子请求（已结束/已清理的忽略），并【级联取消】其全部子请求——
     * 手动取消主请求时从最底端子请求开始向上逐级注销（售出物 S、交易标记 M），
     * 无需逐层取消；每个被注销的 S 请求对应的交易由 AI 备货同步移除。
     */
    private void cancelChainRequest(final IRequestManager manager, final IToken<?> token)
    {
        try
        {
            final IRequest<?> request = manager.getRequestForToken(token);
            if (request == null)
            {
                return;
            }
            // 需求（bug 修复）：先从父请求的 children 中移除本请求并清空 parent——
            // minecolonies 的取消/完成路径（RequestHandler.onChildRequestCancelled）
            // 会遍历父请求的全部 children 并对每个 child 调用 onRequestCancelledDirectly；
            // 若 children 里残留已被 cleanRequestData 清理的失效 token，
            // 会在 onRequestCancelledDirectly 中 NPE（getRequestForToken 返回 null），
            // 导致快递员 AI 卡死（Pausing Citizen ... because of error）与
            // 玩家手动取消请求失败。
            try
            {
                if (request.hasParent())
                {
                    final IRequest<?> parent = manager.getRequestForToken(request.getParent());
                    if (parent != null && parent.getChildren().contains(token))
                    {
                        parent.removeChild(token);
                    }
                    request.setParent(null);
                }
            }
            catch (final Exception ignored)
            {
                // 父子关系维护失败不影响取消本身。
            }
            // 需求（级联取消重构）：从最低端的子请求开始向上逐级注销。
            for (final IToken<?> child : new ArrayList<>(request.getChildren()))
            {
                cancelChainRequest(manager, child);
            }
            final RequestState state = request.getState();
            if (state != RequestState.COMPLETED
                && state != RequestState.CANCELLED
                && state != RequestState.OVERRULED
                && state != RequestState.FAILED
                && state != RequestState.RESOLVED)
            {
                // 需求（级联取消重构）：注销 S 请求时，其对应交易由 AI 备货同步取消
                // （链记录移除后该层级不再进入可执行集合）；输出调试便于确认。
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 注销请求链节点 {}（类型 {}）",
                    String.valueOf(token),
                    request.getRequest() instanceof IDeliverable deliverable
                        ? deliverable.getClass().getSimpleName() : "?");
                manager.updateRequestState(token, RequestState.CANCELLED);
            }
        }
        catch (final Exception ignored)
        {
            // 令牌可能已失效。
        }
    }

    /** 统计容器内匹配物品的总数量。 */
    private static int countInHandler(final IItemHandler handler, final ItemStorage item)
    {
        int count = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++)
        {
            final ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty() && new ItemStorage(stack).equals(item))
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 交易完成后调用：【单次】转为【禁用】，【重复】保持不变。 */
    public void markCompleted(final int flatIndex)
    {
        if (getMode(flatIndex) == TradeMode.SINGLE)
        {
            final int[] resolved = resolveOffer(flatIndex);
            if (resolved != null)
            {
                modes.put(settingKey(villagers.get(resolved[0]).villagerId(), resolved[1]), TradeMode.DISABLED);
                markDirty();
            }
        }
    }

    /**
     * 将村民的全部交易写入小屋（同一村民覆盖旧记录）。
     * 修复：设置按“村民UUID+条目序号”保存，重录后旧交易的模式/数量
     * 按条目位置保留（新增交易默认禁用），【重复】不会被重置为【禁用】。
     */
    public void addVillagerTrades(final Villager villager, final ServerLevel level)
    {
        // 若该村民还带着未结算的模拟经验（此前未加载时累计），先一并授予实体。
        for (final VillagerTradeEntry old : villagers)
        {
            if (old.villagerId().equals(villager.getUUID()) && old.pendingXp() > 0)
            {
                applyXpToLoadedVillager(villager, old.pendingXp());
                break;
            }
        }
        final VillagerTradeEntry entry = VillagerTradeEntry.fromVillager(villager, level);
        if (entry.offers().isEmpty())
        {
            return;
        }
        // 修复：直接以村民实体的当前状态重录——xpEarned = 村民当前总经验，
        // pendingXp = 0，GUI 经验显示不再归零。
        villagers.removeIf(v -> v.villagerId().equals(entry.villagerId()));
        villagers.add(entry);
        // 需求（GUI）：村民按工作站到小屋的距离升序排列。
        sortVillagersByDistance();
        markDirty();
    }

    /** 需求（GUI）：村民按工作站到小屋的距离升序排列（两端共享同一顺序）。 */
    private void sortVillagersByDistance()
    {
        final BlockPos hut = getBuilding() != null ? getBuilding().getPosition() : null;
        if (hut == null)
        {
            return;
        }
        villagers.sort(Comparator.comparingDouble(v -> v.workstationPos().distSqr(hut)));
    }

    /** 为某位村民累计模拟经验（村民实体未加载时计入；加载后统一补升级）。 */
    public void addXpToVillager(final UUID villagerId, final int xp)
    {
        for (int i = 0; i < villagers.size(); i++)
        {
            final VillagerTradeEntry entry = villagers.get(i);
            if (entry.villagerId().equals(villagerId))
            {
                villagers.set(i, new VillagerTradeEntry(
                    entry.villagerId(), entry.profession(), entry.level(),
                    entry.workstationPos(), entry.offers(), entry.xpEarned(),
                    entry.pendingXp() + xp, entry.waystoneUid(), entry.waystoneName()));
                markDirty();
                return;
            }
        }
    }

    /**
     * 殖民地每 tick：若已记录村民带着模拟经验且实体已加载，则补升级、
     * 生成新等级交易并重新记录，使小屋 GUI 显示其最新交易。
     */
    @Override
    public void onColonyTick(final IColony colony)
    {
        final Level level = colony.getWorld();
        if (!(level instanceof ServerLevel serverLevel))
        {
            return;
        }
        // 需求（请求系统接入）：minecolonies 只在建筑“新放置”时注册 resolver，
        // 存档中已有的商队小屋需要主动补注册一次，否则请求永远无人接单。
        ensureResolverRegistered(colony);
        // 需求（请求系统接入·兜底）：无论 resolver 是否成功接单，
        // 每殖民地刻主动扫描开放请求，把“按需交易可产出”的请求记录为待执行需求。
        scanOpenRequestsForOnDemand(serverLevel);
        // 需求（0.4.18 回退）：维护重复交易的售出物请求（每次出行后重建）。
        ensureRepeatTradePlans();
        // 需求（非递归重构）：检查已接入主请求的售出物子请求是否可完成——
        // 殖民地无法提供售出品时注销子请求并把主请求下放。
        checkAdoptedRequestHealth();
        // 需求（工匠接管·残留清理）：商队接管工匠请求后，工匠可能因主请求
        // 重新分配而再次创建“配方/原料”子请求——这些非商队子请求若残留，
        // 商队 M 完成时 minecolonies 会遍历主请求 children 并 reassign 主请求
        // （children 非空 → IllegalArgumentException），导致完成流程中断、
        // 主请求无法注销。这里每殖民地刻取消并解除全部非商队创建的子请求。
        cleanForeignChildren(colony.getRequestManager());
        // 需求（工匠接管）：扫描全部请求，接管“工匠已接单但卡在原料”的请求。
        scanCraftingTakeOvers(colony.getRequestManager());
        // 需求（工匠接管）：递减接管冷却（冷却结束后允许重新尝试接管）。
        if (!takeOverCooldowns.isEmpty())
        {
            takeOverCooldowns.entrySet().removeIf(entry -> entry.getValue() <= 1);
            takeOverCooldowns.replaceAll((key, value) -> value - 1);
        }
        // 需求（通报时机）：小屋检测到物品满足数量要求时通报“请求已完成”。
        checkCompletedBroadcasts();
        // 需求（新增机制）：每殖民地刻（= 20 游戏刻 = 1 秒）检查一次——
        // 商队成员距离领袖超过 100 格时，将其传送到领袖旁边。
        teleportStrayMembers(serverLevel);
        // 需求（GUI 打开触发）：分批刷新 Waystone 名称——每 20 刻处理一批，
        // 避免在打开 GUI 时一次性扫描所有村民造成卡顿。
        if (waystoneBatchIndex >= 0)
        {
            if (++waystoneBatchTicks >= 20)
            {
                waystoneBatchTicks = 0;
                processWaystoneBatch(serverLevel);
            }
        }
        // 需求：修复——村民记录早于 Waystone 放置/命名时，名称会缺失或过期；
        // 每 200 刻（约 10 秒）为所有记录重新计算 100 格内的 Waystone 名称。
        if (++waystoneRefreshTicks >= 200)
        {
            waystoneRefreshTicks = 0;
            refreshWaystoneNames(serverLevel);
        }
        for (final VillagerTradeEntry entry : new ArrayList<>(villagers))
        {
            final Entity entity = serverLevel.getEntity(entry.villagerId());
            if (!(entity instanceof Villager villager))
            {
                continue;
            }
            // 需求（修复）：玩家手动与已保存村民交易使其升级时，
            // 实体的经验/等级/交易列表与记录不一致——以实体当前状态重录，
            // 新解锁的交易与升级后的等级/经验同步到商队小屋 GUI。
            if (entry.pendingXp() > 0)
            {
                applyXpToLoadedVillager(villager, entry.pendingXp());
            }
            if (entry.pendingXp() > 0
                || entry.xpEarned() != villager.getVillagerXp()
                || entry.level() != villager.getVillagerData().getLevel()
                || entry.offers().size() != villager.getOffers().size())
            {
                villagers.remove(entry);
                villagers.add(VillagerTradeEntry.fromVillager(villager, serverLevel));
                sortVillagersByDistance();
                markDirty();
            }
        }
    }

    /** 需求（请求系统接入）：确认本建筑的请求解析方已注册到殖民地请求系统。 */
    private void ensureResolverRegistered(final IColony colony)
    {
        if (resolverRegistered)
        {
            return;
        }
        try
        {
            final IRequestManager manager = colony.getRequestManager();
            if (manager instanceof IStandardRequestManager standard)
            {
                final IProviderHandler handler = standard.getProviderHandler();
                if (handler.getRegisteredResolvers(getBuilding()).isEmpty())
                {
                    manager.onProviderAddedToColony(getBuilding());
                    com.example.caravan.CaravanMod.LOGGER.info("Caravan: 补注册按需请求解析方");
                }
                else
                {
                    com.example.caravan.CaravanMod.LOGGER.info("Caravan: 按需请求解析方已在注册表中");
                }
                resolverRegistered = true;
            }
        }
        catch (final Exception ignored)
        {
            // 请求系统未就绪时下个殖民地刻重试。
        }
    }

    /**
     * 需求（请求系统接入·兜底）：主动扫描殖民地请求系统中所有未完成的
     * {@link IDeliverable} 请求，若商队小屋有激活【按需】交易可产出该物品，
     * 则记录为待执行需求（与 resolver 接单共用同一记录，交付时统一完成）。
     */
    private void scanOpenRequestsForOnDemand(final ServerLevel level)
    {
        try
        {
            final IColony colony = getBuilding() != null ? getBuilding().getColony() : null;
            if (colony == null)
            {
                return;
            }
            final IRequestManager manager = colony.getRequestManager();
            if (manager == null)
            {
                return;
            }
            final BlockPos hutPos = getBuilding().getPosition();
            final Set<IToken<?>> tokens = new HashSet<>();
            tokens.addAll(manager.getPlayerResolver().getAllAssignedRequests());
            tokens.addAll(manager.getRetryingRequestResolver().getAllAssignedRequests());
            // 需求（请求监听·下放检测）：minecolonies 请求系统没有公开的“请求下放”
            // 事件；这里用“快照对比”近似事件监听——每殖民地刻识别【新下放到玩家/
            // 重试解析方】的请求（≤1 秒内捕获“工匠因缺乏材料把请求下放”的时刻），
            // 输出日志并立即进入接管判定。
            final Set<IToken<?>> newlyDownleveled = new HashSet<>();
            for (final IToken<?> token : tokens)
            {
                if (downleveledSeen.add(token))
                {
                    newlyDownleveled.add(token);
                }
            }
            // 清理已结束请求的跟踪（请求对象消失或已终止）。
            downleveledSeen.removeIf(t -> {
                final IRequest<?> r = manager.getRequestForToken(t);
                return r == null
                    || r.getState() == RequestState.COMPLETED
                    || r.getState() == RequestState.CANCELLED
                    || r.getState() == RequestState.FAILED
                    || r.getState() == RequestState.OVERRULED
                    || r.getState() == RequestState.RESOLVED;
            });
            boolean changed = false;
            for (final IToken<?> token : tokens)
            {
                if (newlyDownleveled.contains(token))
                {
                    // 需求（诊断·断点）：请求下放到玩家/重试解析方的精确时刻。
                    final IRequest<?> downRequest = manager.getRequestForToken(token);
                    String downDetail = "?";
                    if (downRequest != null && downRequest.getRequester() != null)
                    {
                        final Object req = downRequest.getRequest();
                        final String itemName = req instanceof IDeliverable d
                            && !d.getResult().isEmpty() ? d.getResult().getHoverName().getString() : req != null
                                ? req.getClass().getSimpleName() : "?";
                        downDetail = itemName + " x" + (req instanceof IDeliverable d2 ? d2.getCount() : 0)
                            + " requester=" + downRequest.getRequester().getLocation().getInDimensionLocation()
                            + " state=" + downRequest.getState();
                    }
                    com.example.caravan.CaravanMod.LOGGER.info(
                        "Caravan: 检测到请求下放 {}（{}）",
                        String.valueOf(token), downDetail);
                }
                if (onDemandRequests.containsKey(token))
                {
                    continue;
                }
                // 需求（bug 修复）：已进入完成流程的请求不再重新接入。
                if (completedNotified.contains(token))
                {
                    continue;
                }
                if (takeOverCooldowns.containsKey(token))
                {
                    continue; // 接管冷却期内（工匠可能已抢回）→ 暂不重复尝试。
                }
                // 需求（非递归重构）：已下放的主请求不计入需求缺口（等待归来后重检）。
                if (releasedRequests.contains(token))
                {
                    continue;
                }
                final IRequest<?> request = manager.getRequestForToken(token);
                if (request == null || request.getRequester() == null)
                {
                    continue;
                }
                final RequestState state = request.getState();
                // 需求（优先级修复）：只处理“已下放到玩家/重试解析方”的请求
                // （更早优先级的 resolver 均已尝试且未接单）。刚创建（CREATED/REPORTED）
                // 的请求仍处于分配流程中，若被主动扫描接管，会抢在工匠（优先级 125）、
                // 仓库调配（200）等 resolver 之前。
                // 需求（bug 修复）：下放的请求状态可能是 ASSIGNED 或 IN_PROGRESS——
                // 工匠原料请求（如 书/白桦木板）在快递员配送后下放到玩家/重试解析方时
                // 状态保持 IN_PROGRESS 未重置，仅检查 ASSIGNED 会导致永远跳过、
                // 工匠接管失效。
                if (state != RequestState.ASSIGNED && state != RequestState.IN_PROGRESS)
                {
                    continue;
                }
                if (!(request.getRequest() instanceof IDeliverable deliverable))
                {
                    continue;
                }
                // 排除商队小屋自己发出的请求（备货交易品等），避免自己接自己的单。
                try
                {
                    if (request.getRequester().getLocation().getInDimensionLocation().equals(hutPos))
                    {
                        continue;
                    }
                }
                catch (final Exception ignored)
                {
                    // 位置不可用时继续判断。
                }
                if (!hasOnDemandOfferFor(deliverable))
                {
                    // 需求（工匠接管·追溯链）：该请求（如工匠的原料“书/白桦木板”）
                    // 无匹配按需交易且已下放到玩家/重试解析方——向上追溯父链找到
                    // 主请求（如书架，被工匠接单），取消工匠的配方/原料子请求链并
                    // 立即由商队记录（工匠即使再次接单，商队交易照常执行）。
                    final IToken<?> mainToken = findTakeOverMainRequest(manager, request);
                    if (mainToken != null)
                    {
                        takeOverCooldowns.put(mainToken, TAKE_OVER_COOLDOWN_TICKS);
                        cancelCraftingChain(manager, mainToken);
                        // 需求（bug 修复）：当前下放的原料请求（如 书/白桦木板）可能
                        // 已从主请求 children 分离（下放时解除父子）——单独取消它，
                        // 否则剪贴板/请求系统中会残留该请求。
                        cancelChainRequest(manager, request.getId());
                        final IRequest<?> main = manager.getRequestForToken(mainToken);
                        if (main != null && main.getRequest() instanceof IDeliverable mainDeliverable)
                        {
                            adoptRequest(manager, main, mainDeliverable);
                        }
                        changed = true;
                    }
                    continue;
                }
            // 需求（请求链重构）：与 resolver 接单一致——创建“A×商队交易”与
            // “B×售出物”两个子请求，形成可显示的请求链。
            adoptRequest(manager, request, deliverable);
            changed = true;
            }
            if (changed)
            {
                markDirty();
            }
        }
        catch (final Exception ignored)
        {
            // 请求系统未就绪时下个殖民地刻重试。
        }
    }

    /**
    /**
     * 需求（工匠接管）：每殖民地刻扫描请求系统中【全部】请求——
     * 若某请求满足以下条件，则商队接管（取消工匠子请求链并立即创建自己的 M/S 链）：
     * <ul>
     *   <li>状态为 ASSIGNED（工匠已接单、等待材料，而非制作中）；</li>
     *   <li>requester 不是商队小屋；</li>
     *   <li>尚未被商队处理/下放/完成/处于接管冷却；</li>
     *   <li>物品可由商队【按需】交易产出；</li>
     *   <li>当前由工匠解析方（Public/PrivateWorkerCraftingRequestResolver）持有。</li>
     * </ul>
     */
    private void scanCraftingTakeOvers(final IRequestManager manager)
    {
        try
        {
            if (!(manager instanceof IStandardRequestManager standard))
            {
                return;
            }
            final BlockPos hutPos = getBuilding() != null ? getBuilding().getPosition() : null;
            final com.google.common.collect.BiMap<IToken<?>, IRequest<?>> identities =
                standard.getRequestIdentitiesDataStore().getIdentities();
            int totalRequests = 0;
            int craftingResolverRequests = 0;
            for (final IRequest<?> request : new ArrayList<>(identities.values()))
            {
                totalRequests++;
                if (request.getRequester() == null)
                {
                    continue;
                }
                if (hutPos != null
                    && request.getRequester().getLocation().getInDimensionLocation().equals(hutPos))
                {
                    continue; // 商队小屋自身请求。
                }
                final IRequestResolver<?> resolver = manager.getResolverForRequest(request.getId());
                // 需求（兼容）：匹配工匠解析方——本体 Public/PrivateWorkerCraftingRequestResolver，
                // 或类名包含 “CraftingRequestResolver”（兼容 MineColonies_Tweaks 等附加 mod）。
                final String resolverName = resolver != null ? resolver.getClass().getSimpleName() : "null";
                if (!(resolver instanceof com.minecolonies.core.colony.requestsystem.resolvers.PublicWorkerCraftingRequestResolver)
                    && !(resolver instanceof com.minecolonies.core.colony.requestsystem.resolvers.PrivateWorkerCraftingRequestResolver)
                    && !resolverName.contains("CraftingRequestResolver"))
                {
                    continue; // 非工匠解析方（仓库/其它）→ 不接管。
                }
                craftingResolverRequests++;
                if (request.getState() != RequestState.ASSIGNED)
                {
                    continue;
                }
                if (!(request.getRequest() instanceof IDeliverable deliverable))
                {
                    continue;
                }
                if (onDemandRequests.containsKey(request.getId())
                    || releasedRequests.contains(request.getId())
                    || completedNotified.contains(request.getId())
                    || takeOverCooldowns.containsKey(request.getId()))
                {
                    continue;
                }
                if (!hasOnDemandOfferFor(deliverable))
                {
                    continue;
                }
                // 命中：工匠已接单但卡在原料 → 商队接管。
                takeOverCooldowns.put(request.getId(), TAKE_OVER_COOLDOWN_TICKS);
                cancelCraftingChain(manager, request.getId());
                adoptRequest(manager, request, deliverable);
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 扫描发现工匠等待材料的请求 {}（{} x{}，解析方 {}），商队接管",
                    String.valueOf(request.getId()),
                    deliverable.getResult().isEmpty() ? "(tag)" : deliverable.getResult().getHoverName().getString(),
                    deliverable.getCount(),
                    resolverName);
            }
        }
        catch (final Exception ignored)
        {
            // 扫描失败时下个殖民地刻重试。
        }
    }
/**
     * 需求（工匠接管）：取消主请求的全部子请求（工匠的“配方/原料”链）——
     * cancelChainRequest 会先解除子请求与主请求的父子关系再取消，
     * 主请求 children 清空后由 minecolonies 自动重新分配；
     * 工匠因缺少材料会拒绝，商队 resolver 随后接单完成交易。
     */
    private void cancelCraftingChain(final IRequestManager manager, final IToken<?> mainToken)
    {
        try
        {
            final IRequest<?> main = manager.getRequestForToken(mainToken);
            if (main == null)
            {
                return;
            }
            for (final IToken<?> child : new ArrayList<>(main.getChildren()))
            {
                cancelChainRequest(manager, child);
            }
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 已取消工匠子请求链（主请求 {}），等待重新分配由商队接管",
                String.valueOf(mainToken));
        }
        catch (final Exception ignored)
        {
            // 取消失败时保持现状（下轮不再尝试）。
        }
    }

    /**
     * 需求（新增机制）：商队成员距离商队领袖超过 100 格时，传送回领袖旁边。
     * 此检测每殖民地刻执行一次（minecolonies 殖民地刻 = 20 游戏刻 = 1 秒）。
     */
    private void teleportStrayMembers(final ServerLevel level)
    {
        try
        {
            AbstractEntityCitizen leader = null;
            final List<AbstractEntityCitizen> members = new ArrayList<>();
            for (final WorkerBuildingModule workerModule : getBuilding().getModulesByType(WorkerBuildingModule.class))
            {
                for (final ICitizenData citizen : workerModule.getAssignedCitizen())
                {
                    final var entity = citizen.getEntity().orElse(null);
                    if (entity == null)
                    {
                        continue;
                    }
                    if (citizen.getJob() instanceof JobCaravanLeader)
                    {
                        leader = entity;
                    }
                    else if (citizen.getJob() instanceof JobCaravanMember)
                    {
                        members.add(entity);
                    }
                }
            }
            if (leader == null || members.isEmpty())
            {
                return;
            }
            final BlockPos leaderPos = leader.blockPosition();
            for (final AbstractEntityCitizen member : members)
            {
                if (member.blockPosition().distSqr(leaderPos) > MEMBER_TELEPORT_DISTANCE_SQ)
                {
                    member.teleportTo(
                        leaderPos.getX() + 0.5 + level.random.nextInt(3) - 1,
                        leaderPos.getY(),
                        leaderPos.getZ() + 0.5 + level.random.nextInt(3) - 1);
                    member.getNavigation().stop();
                }
            }
        }
        catch (final Exception ignored)
        {
            // 殖民地/建筑数据未就绪时静默跳过。
        }
    }

    /** 需求（GUI 打开触发）：开始分批刷新 Waystone 名称。 */
    public void startWaystoneRefresh()
    {
        waystoneBatchIndex = 0;
        waystoneBatchTicks = 0;
    }

    /** 需求：处理下一批村民的 Waystone 名称刷新；全部完成后标记建筑为脏。 */
    private void processWaystoneBatch(final ServerLevel level)
    {
        boolean changed = false;
        int processed = 0;
        while (waystoneBatchIndex >= 0 && waystoneBatchIndex < villagers.size() && processed < WAYSTONE_BATCH_SIZE)
        {
            final int index = waystoneBatchIndex++;
            final VillagerTradeEntry entry = villagers.get(index);
            final WaystoneHelper.WaystoneInfo info =
                VillagerTradeEntry.refreshWaystoneInfo(level, entry.workstationPos());
            final UUID newUid = info != null ? info.waystoneUid() : null;
            final String newName = info != null ? info.waystoneName() : null;
            if (!Objects.equals(newUid, entry.waystoneUid()) || !Objects.equals(newName, entry.waystoneName()))
            {
                villagers.set(index, new VillagerTradeEntry(
                    entry.villagerId(), entry.profession(), entry.level(),
                    entry.workstationPos(), entry.offers(), entry.xpEarned(),
                    entry.pendingXp(), newUid, newName));
                changed = true;
            }
            processed++;
        }
        if (waystoneBatchIndex >= villagers.size())
        {
            waystoneBatchIndex = -1;
        }
        if (changed)
        {
            markDirty();
        }
    }

    /** 需求：为所有已记录村民重新计算 Waystone 名称；变化时标记建筑为脏以同步 GUI。 */
    private void refreshWaystoneNames(final ServerLevel level)
    {
        boolean changed = false;
        for (int i = 0; i < villagers.size(); i++)
        {
            final VillagerTradeEntry entry = villagers.get(i);
            final WaystoneHelper.WaystoneInfo info =
                VillagerTradeEntry.refreshWaystoneInfo(level, entry.workstationPos());
            final UUID newUid = info != null ? info.waystoneUid() : null;
            final String newName = info != null ? info.waystoneName() : null;
            if (!Objects.equals(newUid, entry.waystoneUid()) || !Objects.equals(newName, entry.waystoneName()))
            {
                villagers.set(i, new VillagerTradeEntry(
                    entry.villagerId(), entry.profession(), entry.level(),
                    entry.workstationPos(), entry.offers(), entry.xpEarned(),
                    entry.pendingXp(), newUid, newName));
                changed = true;
            }
        }
        if (changed)
        {
            markDirty();
        }
    }

    /** 把经验应用到已加载的村民：写经验、按阈值升级、按原版交易池补充新交易。 */
    public static void applyXpToLoadedVillager(final Villager villager, final int xp)
    {
        // 修复：1.21.1 中 AbstractVillager.overrideXp 是空实现（no-op），
        // 必须用 setVillagerXp 才能真正写入经验值。
        villager.setVillagerXp(villager.getVillagerXp() + xp);
        final VillagerData data = villager.getVillagerData();
        final int baseLevel = data.getLevel();
        int level = baseLevel;
        int remaining = villager.getVillagerXp();
        while (VillagerData.canLevelUp(level) && remaining >= VillagerData.getMaxXpPerLevel(level))
        {
            remaining -= VillagerData.getMaxXpPerLevel(level);
            level++;
        }
        if (level > baseLevel)
        {
            villager.setVillagerData(new VillagerData(data.getType(), data.getProfession(), level));
            for (int l = baseLevel + 1; l <= level; l++)
            {
                final ItemListing[] listings = tradePoolFor(villager, l);
                if (listings != null)
                {
                    addOffersFromListings(villager, villager.getOffers(), listings, 2);
                }
            }
        }
    }

    /**
     * 需求（工匠接管·追溯链）：从已下放到玩家/重试解析方的请求（如工匠原料
     * “书/白桦木板”）向上追溯父链找到主请求（如“书架”）——
     * 条件：主请求 requester 非商队小屋、尚未被商队处理/下放/完成/冷却、
     * 物品可由商队【按需】交易产出，且主请求存在【非商队创建】的子请求
     * （工匠的配方/原料链——证明工匠已接单但卡在材料）。
     * 不依赖工匠 resolver 类名（部分环境类名不匹配导致无法识别）。
     */
    private IToken<?> findTakeOverMainRequest(
        final IRequestManager manager,
        final IRequest<?> leafRequest)
    {
        try
        {
            final BlockPos hutPos = getBuilding() != null ? getBuilding().getPosition() : null;
            IRequest<?> current = leafRequest;
            final StringBuilder trace = new StringBuilder();
            trace.append('[').append(String.valueOf(leafRequest.getId())).append(']');
            int depth = 0;
            while (current.hasParent() && depth < 10)
            {
                final IRequest<?> parent = manager.getRequestForToken(current.getParent());
                if (parent == null)
                {
                    trace.append("->?");
                    break;
                }
                current = parent;
                depth++;
                trace.append("->[").append(String.valueOf(current.getId())).append(']');
            }
            if (current == null || current.getRequester() == null)
            {
                return null;
            }
            if (!(current.getRequest() instanceof IDeliverable deliverable))
            {
                return null;
            }
            if (hutPos != null
                && current.getRequester().getLocation().getInDimensionLocation().equals(hutPos))
            {
                return null; // 商队小屋自身请求。
            }
            if (onDemandRequests.containsKey(current.getId())
                || releasedRequests.contains(current.getId())
                || completedNotified.contains(current.getId())
                || takeOverCooldowns.containsKey(current.getId()))
            {
                return null; // 已处理/下放/完成/接管冷却中。
            }
            if (!hasOnDemandOfferFor(deliverable))
            {
                return null; // 商队无法产出。
            }
            // 主请求必须存在“非商队创建”的子请求（工匠配方/原料链）——证明工匠正在处理。
            boolean hasCraftingChildren = false;
            for (final IToken<?> child : current.getChildren())
            {
                final IRequest<?> childRequest = manager.getRequestForToken(child);
                if (childRequest != null && childRequest.getRequester() != null)
                {
                    try
                    {
                        if (hutPos == null
                            || !childRequest.getRequester().getLocation()
                                .getInDimensionLocation().equals(hutPos))
                        {
                            hasCraftingChildren = true;
                            break;
                        }
                    }
                    catch (final Exception ignored)
                    {
                        // 位置不可用时视为非商队子请求。
                        hasCraftingChildren = true;
                        break;
                    }
                }
            }
            if (!hasCraftingChildren)
            {
                return null;
            }
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 追溯找到工匠接单但卡在材料的主请求 {}（{} x{}），商队准备接管",
                String.valueOf(current.getId()),
                deliverable.getResult().isEmpty() ? "(tag)" : deliverable.getResult().getHoverName().getString(),
                deliverable.getCount());
            return current.getId();
        }
        catch (final Exception ignored)
        {
            return null;
        }
    }

    /**
     * 需求（工匠接管·残留清理）：每殖民地刻清理“已接入请求”children 中所有
     * 【非商队小屋创建】的子请求（工匠重新接单生成的配方/原料残留）——
     * cancelChainRequest 会先解除父子关系再取消（不触发父请求 reassign），
     * 确保主请求 children 只保留商队自己的 M/S，完成流程不会因残留子请求
     * 触发 reassign 异常而卡死。
     */
    private void cleanForeignChildren(final IRequestManager manager)
    {
        try
        {
            final BlockPos hutPos = getBuilding() != null ? getBuilding().getPosition() : null;
            if (hutPos == null || onDemandRequests.isEmpty())
            {
                return;
            }
            for (final IToken<?> token : new ArrayList<>(onDemandRequests.keySet()))
            {
                final IRequest<?> main = manager.getRequestForToken(token);
                if (main == null)
                {
                    continue;
                }
                for (final IToken<?> child : new ArrayList<>(main.getChildren()))
                {
                    final IRequest<?> childRequest = manager.getRequestForToken(child);
                    if (childRequest == null)
                    {
                        continue;
                    }
                    try
                    {
                        if (childRequest.getRequester() == null
                            || !childRequest.getRequester().getLocation()
                                .getInDimensionLocation().equals(hutPos))
                        {
                            com.example.caravan.CaravanMod.LOGGER.info(
                                "Caravan: 清理主请求 {} 的非商队子请求 {}（{}）",
                                String.valueOf(token), String.valueOf(child),
                                childRequest.getRequest().getClass().getSimpleName());
                            cancelChainRequest(manager, child);
                        }
                    }
                    catch (final Exception ignored)
                    {
                        // 位置不可用时视为非商队子请求清理。
                        com.example.caravan.CaravanMod.LOGGER.info(
                            "Caravan: 清理主请求 {} 的子请求 {}（请求方不可用）",
                            String.valueOf(token), String.valueOf(child));
                        cancelChainRequest(manager, child);
                    }
                }
            }
            // 需求（bug 修复）：已接入主请求的“下放残留”子请求——工匠的配方/原料
            // 请求被下放到玩家/重试解析方时可能已从主请求 children 分离，上面遍历
            // children 清理不到；这里对玩家/重试解析方的每个请求追溯父链，若顶端
            // 主请求已被商队接入（onDemandRequests），则取消该残留请求。
            final Set<IToken<?>> downTokens = new HashSet<>();
            downTokens.addAll(manager.getPlayerResolver().getAllAssignedRequests());
            downTokens.addAll(manager.getRetryingRequestResolver().getAllAssignedRequests());
            for (final IToken<?> downToken : downTokens)
            {
                final IRequest<?> down = manager.getRequestForToken(downToken);
                if (down == null)
                {
                    continue;
                }
                final IToken<?> ancestor = findAncestorMain(manager, down);
                if (ancestor != null && onDemandRequests.containsKey(ancestor))
                {
                    com.example.caravan.CaravanMod.LOGGER.info(
                        "Caravan: 清理已接入请求 {} 的下放残留子请求 {}",
                        String.valueOf(ancestor), String.valueOf(downToken));
                    cancelChainRequest(manager, downToken);
                }
            }
        }
        catch (final Exception ignored)
        {
            // 清理失败时下个殖民地刻重试。
        }
    }

    /** 需求（残留清理）：沿父链上溯到顶端请求（主请求）的 token。 */
    private IToken<?> findAncestorMain(final IRequestManager manager, final IRequest<?> leaf)
    {
        IRequest<?> current = leaf;
        int depth = 0;
        while (current.hasParent() && depth < 10)
        {
            final IRequest<?> parent = manager.getRequestForToken(current.getParent());
            if (parent == null)
            {
                break;
            }
            current = parent;
            depth++;
        }
        return current != null ? current.getId() : null;
    }

    /** 获取某职业指定等级的原版交易池（兼容 trade_rebalance 数据包）。 */
    public static ItemListing[] tradePoolFor(final Villager villager, final int level)
    {
        final boolean experimental = villager.level().enabledFeatures().contains(FeatureFlags.TRADE_REBALANCE);
        final Map<VillagerProfession, Int2ObjectMap<ItemListing[]>> trades =
            experimental ? VillagerTrades.EXPERIMENTAL_TRADES : VillagerTrades.TRADES;
        final Int2ObjectMap<ItemListing[]> pool = trades.get(villager.getVillagerData().getProfession());
        return pool != null ? pool.get(level) : null;
    }

    /** 复刻原版 addOffersFromItemListings：随机抽取至多 count 个交易加入列表。 */
    public static void addOffersFromListings(
        final Villager villager,
        final MerchantOffers offers,
        final ItemListing[] listings,
        final int count)
    {
        final List<ItemListing> pool = new ArrayList<>(List.of(listings));
        Collections.shuffle(pool);
        final int amount = Math.min(count, pool.size());
        for (int i = 0; i < amount; i++)
        {
            final MerchantOffer offer = pool.remove(0).getOffer(villager, villager.getRandom());
            if (offer != null)
            {
                offers.add(offer);
            }
        }
    }

    @Override
    public void serializeNBT(final HolderLookup.Provider provider, final CompoundTag tag)
    {
        final ListTag villagerList = new ListTag();
        for (final VillagerTradeEntry entry : villagers)
        {
            villagerList.add(entry.save(provider));
        }
        tag.put(TAG_VILLAGERS, villagerList);

        tag.put(TAG_MODES, writeSettingList(modes, TradeMode::ordinal));
        tag.put(TAG_QUANTITIES, writeSettingList(quantities, value -> value));

        final ListTag nameList = new ListTag();
        for (final Map.Entry<UUID, String> entry : customNames.entrySet())
        {
            final CompoundTag nameTag = new CompoundTag();
            nameTag.putUUID("u", entry.getKey());
            nameTag.putString("n", entry.getValue());
            nameList.add(nameTag);
        }
        tag.put(TAG_CUSTOM_NAMES, nameList);

        // 需求（总览）：持久化激活交易顺序（平铺索引列表）。
        final ListTag orderList = new ListTag();
        for (final int idx : offerOrder)
        {
            final CompoundTag orderTag = new CompoundTag();
            orderTag.putInt("i", idx);
            orderList.add(orderTag);
        }
        tag.put(TAG_OFFER_ORDER, orderList);

        // 需求（非递归重构）：持久化“已下放”主请求 token——重启后不再重复接入，
        // 避免每次加载世界都重复“接入→下放”并刷屏通报。
        final ListTag releasedList = new ListTag();
        for (final IToken<?> token : releasedRequests)
        {
            try
            {
                final IFactoryController controller = getBuilding().getColony()
                    .getRequestManager().getFactoryController();
                final CompoundTag tokenTag = new CompoundTag();
                tokenTag.put("t", controller.serializeTag(provider, token));
                releasedList.add(tokenTag);
            }
            catch (final Exception ignored)
            {
                // 请求系统未就绪时跳过（重启后重新评估）。
            }
        }
        tag.put(TAG_RELEASED, releasedList);
    }

    private static <V> ListTag writeSettingList(final Map<String, V> settings, final java.util.function.ToIntFunction<V> valueOf)
    {
        final ListTag list = new ListTag();
        for (final Map.Entry<String, V> entry : settings.entrySet())
        {
            final String[] parts = entry.getKey().split(":", 2);
            if (parts.length != 2)
            {
                continue;
            }
            final CompoundTag tag = new CompoundTag();
            tag.putString("u", parts[0]);
            tag.putInt("i", Integer.parseInt(parts[1]));
            tag.putInt("v", valueOf.applyAsInt(entry.getValue()));
            list.add(tag);
        }
        return list;
    }

    @Override
    public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag tag)
    {
        villagers.clear();
        for (final Tag element : tag.getList(TAG_VILLAGERS, Tag.TAG_COMPOUND))
        {
            villagers.add(VillagerTradeEntry.load(provider, (CompoundTag) element));
        }
        // 需求（GUI）：加载存档后也按距离排序，保证客户端与服务端顺序一致。
        sortVillagersByDistance();
        modes.clear();
        readSettingList(tag.getList(TAG_MODES, Tag.TAG_COMPOUND), modes,
            ordinal -> ordinal >= 0 && ordinal < TradeMode.values().length ? TradeMode.values()[ordinal] : TradeMode.DISABLED);
        quantities.clear();
        readSettingList(tag.getList(TAG_QUANTITIES, Tag.TAG_COMPOUND), quantities, value -> value);

        customNames.clear();
        for (final Tag element : tag.getList(TAG_CUSTOM_NAMES, Tag.TAG_COMPOUND))
        {
            final CompoundTag nameTag = (CompoundTag) element;
            customNames.put(nameTag.getUUID("u"), nameTag.getString("n"));
        }
        offerOrder.clear();
        for (final Tag element : tag.getList(TAG_OFFER_ORDER, Tag.TAG_COMPOUND))
        {
            offerOrder.add(((CompoundTag) element).getInt("i"));
        }
        releasedRequests.clear();
        for (final Tag element : tag.getList(TAG_RELEASED, Tag.TAG_COMPOUND))
        {
            try
            {
                final IFactoryController controller = getBuilding().getColony()
                    .getRequestManager().getFactoryController();
                final IToken<?> token = (IToken<?>) controller.deserializeTag(
                    provider, ((CompoundTag) element).getCompound("t"));
                if (token != null)
                {
                    releasedRequests.add(token);
                }
            }
            catch (final Exception ignored)
            {
                // 令牌失效/请求系统未就绪时跳过。
            }
        }
    }

    private static <V> void readSettingList(
        final ListTag list,
        final Map<String, V> settings,
        final java.util.function.IntFunction<V> valueOf)
    {
        for (final Tag element : list)
        {
            final CompoundTag tag = (CompoundTag) element;
            settings.put(tag.getString("u") + ":" + tag.getInt("i"), valueOf.apply(tag.getInt("v")));
        }
    }

    @Override
    public void serializeToView(final RegistryFriendlyByteBuf buffer)
    {
        // 需求（完全重构）：每次向客户端同步建筑数据前，都重新计算 Waystone 名称——
        // 确保 GUI 打开/刷新时拿到的始终是最新结果（石碑命名、摧毁、新增即时生效，
        // 不再依赖存储的旧名称）。
        final Level world = getBuilding() != null ? getBuilding().getColony().getWorld() : null;
        if (world instanceof ServerLevel serverLevel)
        {
            refreshWaystoneNames(serverLevel);
        }

        buffer.writeVarInt(villagers.size());
        for (final VillagerTradeEntry entry : villagers)
        {
            entry.toBuffer(buffer);
        }

        // 设置：村民UUID + 条目序号 + 模式 + 数量
        buffer.writeVarInt(modes.size());
        for (final Map.Entry<String, TradeMode> entry : modes.entrySet())
        {
            writeSettingToBuffer(buffer, entry.getKey(), entry.getValue().ordinal());
        }
        buffer.writeVarInt(quantities.size());
        for (final Map.Entry<String, Integer> entry : quantities.entrySet())
        {
            writeSettingToBuffer(buffer, entry.getKey(), entry.getValue());
        }
        // 需求（GUI 跳变修复）：把“待应用”（GUI 打开期间玩家已点击但未退出生效）的模式
        // 与数量一并同步给客户端，客户端显示时优先采用待应用值——
        // 否则点击按钮后 0.5 秒内服务器同步会把旧值推回客户端，界面跳回原状态。
        buffer.writeVarInt(pendingModes.size());
        for (final Map.Entry<String, TradeMode> entry : pendingModes.entrySet())
        {
            writeSettingToBuffer(buffer, entry.getKey(), entry.getValue().ordinal());
        }
        buffer.writeVarInt(pendingQuantities.size());
        for (final Map.Entry<String, Integer> entry : pendingQuantities.entrySet())
        {
            writeSettingToBuffer(buffer, entry.getKey(), entry.getValue());
        }
        // 需求：村民自定义名称（UUID + 名称），GUI 主页面与村民信息页显示。
        buffer.writeVarInt(customNames.size());
        for (final Map.Entry<UUID, String> entry : customNames.entrySet())
        {
            buffer.writeUUID(entry.getKey());
            buffer.writeUtf(entry.getValue());
        }
        // 需求（总览）：同步激活交易顺序，客户端【总览】窗口据此显示与调整。
        buffer.writeVarInt(offerOrder.size());
        for (final int idx : offerOrder)
        {
            buffer.writeVarInt(idx);
        }
        buffer.writeVarInt(getMaxSelection());
    }

    private static void writeSettingToBuffer(final RegistryFriendlyByteBuf buffer, final String key, final int value)
    {
        final String[] parts = key.split(":", 2);
        buffer.writeUtf(parts[0]);
        buffer.writeVarInt(Integer.parseInt(parts[1]));
        buffer.writeVarInt(value);
    }
}
