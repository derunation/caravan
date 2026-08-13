package com.example.caravan.entity.ai;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.CaravanGuardHelper;
import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.example.caravan.colony.buildings.modules.TradeOfferData;
import com.example.caravan.colony.buildings.modules.VillagerTradeEntry;
import com.example.caravan.colony.jobs.JobCaravanMember;
import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.example.caravan.colony.jobs.JobCaravanLeader.TripStatus;
import com.example.caravan.colony.jobs.JobCaravanLeader.TripTrade;
import com.example.caravan.colony.jobs.JobCaravanLeader.CaravanStatus;
import com.example.caravan.debug.DebugFlags;
import com.example.caravan.entity.CaravanExperienceHandler;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.crafting.ItemStorage;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.research.util.ResearchConstants;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IBooleanConditionSupplier;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IStateSupplier;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.api.util.StatsUtil;
import com.minecolonies.api.util.constant.CitizenConstants;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.util.AttributeModifierUtils;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 商队领袖 AI。
 *
 * <p>状态流：</p>
 * <pre>
 *   IDLE --存在可用订单--> START_WORKING --decide--> PREPARE(备货+400刻等待)
 *     -> DEPART(就近选择目标) -> [目标在殖民地内] TRADE(工作方块旁停留80刻完成交易)
 *                            -> [目标在殖民地外] 走到边界 -> AWAY(去程/回程模拟)
 *     -> RETURN(回小屋入库) -> IDLE
 * </pre>
 *
 * <p>核心规则：</p>
 * <ul>
 *   <li>一次出行尝试交易列表中所有可用（非禁用）交易，按“最近目标优先”逐一执行；</li>
 *   <li>【单次】交易完成后自动转为禁用；【重复】交易每次出行前重新请求物品；</li>
 *   <li>目标在殖民地边界内：在工作方块附近停留 80 游戏刻后完成交易，不进入消失状态；</li>
 *   <li>目标在边界外：走到离目标最近的边界点后消失，进入“去程/回程”模拟：
 *       记录当前位置到最远剩余目标的距离（格），每 20 游戏刻减 10 格；
 *       去程归零转为回程并重置距离，回程再归零时归来；</li>
 *   <li>背包满或订单完成时返回小屋入库。</li>
 *   <li>备货采用“尽力而为”模式（参考卫兵：会请求护甲，但没有护甲不影响工作）：
 *       请求按精确缺额创建，长期未送达时（硬上限 1200 刻）清空请求并带着现有
 *       物品出发，能完成的交易完成、不能的标记失败，绝不无限期等待。</li>
 *   <li>每个游戏日白天开始时再做一次“尽力而为”检查：只要满足任意一个交易的
 *       最低要求，直接出发交易。</li>
 * </ul>
 */
public class EntityAIWorkCaravanLeader extends AbstractEntityAIBasic<JobCaravanLeader, BuildingCaravanLeader>
{
    /** 殖民地半径（格）：离开该范围视为“外出”。 */
    private static final int COLONY_EXIT_RANGE = 100;
    /** 需求（取货机制）：从小屋存储取货/入库所需的“容器旁”距离平方（6 格）。 */
    private static final int STORAGE_RANGE_SQUARED = 36;
    /** 需求（游荡）：小屋范围内游荡的半径（格）。 */
    private static final int WANDER_RADIUS = 5;
    /** 备货完成后的等待时间（游戏刻）。 */
    private static final int PREPARE_WAIT_TICKS = 400;
    /** 在目标工作方块附近的停留时间（游戏刻），之后完成交易。 */
    private static final int TRADE_WAIT_TICKS = 80;
    /** 自定义状态处理器的 tick 间隔（即“每 20 游戏刻减 10 格”的节拍）。 */
    private static final int STATE_TICK = 20;

    /**
     * 商队领袖自定义状态。
     */
    private enum CaravanState implements IAIState
    {
        /** 备货：请求物品，任一交易满足后等待 400 刻出发。 */
        PREPARE,
        /** 赶路：就近选择目标；边界外目标先走到边界点。 */
        DEPART,
        /** 交易：在目标工作方块旁停留 80 刻后完成该笔交易。 */
        TRADE,
        /** 每日白天开始时回小屋方块（若不在附近）。 */
        GOTO_HUT,
        /** 消失：去程/回程距离模拟。 */
        AWAY,
        /** 回归：回小屋并入库，必要时继续剩余订单。 */
        RETURN,
        /** 需求：空闲/备货时在小屋范围内游荡。 */
        WANDER;

        @Override
        public boolean isOkayToEat()
        {
            // 消失期间不允许进食（隐形状态）。
            return this != AWAY;
        }
    }

    /** 备货等待计时器（400 刻）。 */
    private int prepareWait;
    /** 交易停留计时器（80 刻）。 */
    private int tradeWait;
    /** “物品栏已满足至少一项交易”的检测计时器（每 80 刻检测一次）。 */
    private int detectTicks;
    /** 备货总计时（硬上限）：请求系统长期未送达时，强制出发（参考卫兵：请求护甲但不影响工作）。 */
    private int totalPrepareTicks;
    /** 上次执行“每日尽力而为检查”的游戏日。 */
    private long lastDailyCheckDay = -1;
    /** 已创建的请求令牌（按物品去重，防止同一物品被反复请求）。 */
    private final Map<Item, IToken<?>> trackedRequests = new HashMap<>();
    /** 需求（帐篷修复）：当前帐篷差额请求的令牌——使用【建筑请求】而非公民请求，
     *  避免市民因待处理的公民请求进入 minecolonies 的 NEEDS_ITEM（“正在等待所需物品”）
     *  状态后本 AI 停止执行、无法再提取/请求帐篷。 */
    private IToken<?> tentRequestToken;
    /** 备货硬上限（游戏刻）：超过后不再等待请求，带着现有物品出发。 */
    private static final int MAX_PREPARE_TICKS = 1200;
    /** 当前正在执行的目标订单（用于 TRADE 状态）。 */
    private TripTrade currentTrade;
    /** 上次应用速度加成的敏捷等级（变化时才刷新，避免每 tick 重复加修饰符）。 */
    private int lastAgilityLevel = -1;
    /** 需求：本次行程是否已发送“与X名村民进行Y项交易”汇总消息（每个行程只发一次）。 */
    private boolean tradeSummarySent;
    /** 需求（游荡）：游荡计时器与当前游荡目标。 */
    private int wanderTimer;
    private BlockPos wanderTarget;
    /** 需求（防卡死）：穿越殖民地步行的计时器（游戏刻）。 */
    private int colonyWalkTicks;
    /** 需求（防卡死）：穿越殖民地步行的超时（1200 刻 = 60 秒）——
     *  出口点不可达/寻路卡住时强制继续模拟，避免距离永久冻结。 */
    private static final int COLONY_WALK_TIMEOUT = 1200;
    /** 需求（商队帐篷）：本次行程是否已扣除过出发耐久（每次出行只扣一次）。 */
    private boolean tripTentDeducted;
    /** 需求（疾病）：上次无帐篷过夜累计患病概率的游戏日（防同夜重复累计）。 */
    private long lastIllnessNightDay = -1;
    /** 需求（回归机制）：回归后延迟解除隐形的倒计时（1 殖民地刻 = STATE_TICK），
     *  防止在回归瞬间立即现形。 */
    private int revealDelayTicks;
    /** 需求（商队护卫）：护卫卫兵是否正处于战斗（战斗结束后等待其回到 6 格内）。 */
    private boolean guardsWereInCombat;
    /** 需求（消耗品）：当前火把差额请求的令牌（建筑请求，送达小屋存储）。 */
    private IToken<?> torchRequestToken;
    /** 需求（统计）：本次行程累计消耗的火把/食物/帐篷数量（回归通报用）。 */
    private int torchConsumedTotal;
    private int foodConsumedTotal;
    private int tentConsumedTotal;
    /** 需求（统计）：当天消耗的食物/火把数量（每日扎营总结日志用）。 */
    private int foodConsumedToday;
    private int torchConsumedToday;
    /** 需求（bug 修复）：上次扎营扣除帐篷耐久的游戏日（防重，替代 sleeping 标志）。 */
    private long lastTentDeductDay = -1;
    /** 需求（模拟状态机）：上次“露宿中醒来”提升患病概率的游戏日（防重）。 */
    private long lastRoughWakeDay = -1;

    public EntityAIWorkCaravanLeader(final JobCaravanLeader job)
    {
        super(job);

        // 参照 AbstractEntityAICrafting 的接线：IDLE 且 hasWorkToDo() → START_WORKING → decide()。
        super.registerTargets(
            new AITarget<IAIState>(AIWorkerState.IDLE,
                (IBooleanConditionSupplier) this::hasWorkToDo,
                (IStateSupplier<IAIState>) () -> AIWorkerState.START_WORKING, 20),
            // 需求（游荡修复）：无工作可做时也离开 IDLE——进入小屋范围内的游荡状态
            // （原实现只在有活干时才会进入 START_WORKING → decide，空闲时永远停在 IDLE）。
            new AITarget<IAIState>(AIWorkerState.IDLE,
                (IBooleanConditionSupplier) () -> !hasWorkToDo(),
                (IStateSupplier<IAIState>) () -> CaravanState.WANDER, 20),
            new AITarget<IAIState>(AIWorkerState.START_WORKING, (IStateSupplier<IAIState>) this::decide, 5),
            new AITarget<IAIState>(CaravanState.PREPARE, (IStateSupplier<IAIState>) this::prepareForTrip, STATE_TICK),
            new AITarget<IAIState>(CaravanState.DEPART, (IStateSupplier<IAIState>) this::depart, STATE_TICK),
            new AITarget<IAIState>(CaravanState.TRADE, (IStateSupplier<IAIState>) this::tradeAtWorkstation, STATE_TICK),
            new AITarget<IAIState>(CaravanState.GOTO_HUT, (IStateSupplier<IAIState>) this::goToHut, STATE_TICK),
            new AITarget<IAIState>(CaravanState.AWAY, (IStateSupplier<IAIState>) this::stayAway, STATE_TICK),
            new AITarget<IAIState>(CaravanState.RETURN, (IStateSupplier<IAIState>) this::returnFromTrip, STATE_TICK),
            new AITarget<IAIState>(CaravanState.WANDER, (IStateSupplier<IAIState>) this::wander, STATE_TICK));

        worker.setCanPickUpLoot(true);
        // 需求：敏捷 → 移动速度（与快递员一致：每级敏捷 +0.003）。
        refreshSpeedBonus();
        // 需求：商队领袖自定义属性经验分配（敏捷100%/运动10%/魔力-10%/智力50%/力量5%/专注-5%）。
        worker.setCitizenExperienceHandler(
            new CaravanExperienceHandler(worker, worker.getCitizenExperienceHandler()));
    }

    @Override
    public Class<BuildingCaravanLeader> getExpectedBuildingClass()
    {
        return BuildingCaravanLeader.class;
    }

    /**
     * 是否有事可做：消失中、已有行程、或交易列表中存在可用（非禁用）订单。
     * 空闲时若为 false，IDLE → START_WORKING 不会触发，decide() 接单逻辑不会执行。
     */
    public boolean hasWorkToDo()
    {
        if (job.isAway() || job.hasActiveTrip())
        {
            return true;
        }
        // 需求2：每天白天开始时（若不在小屋附近）也要回小屋方块。
        if (isDayStart() && !canAccessHutStorage())
        {
            return true;
        }
        final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
        // 需求（请求系统接入）：按需交易无实际请求缺口时不视为有事可做，避免商队空转。
        return module != null && module.hasWorkableOffers();
    }

    /** 消失期间不允许被打断（如被召回）。 */
    @Override
    public boolean canBeInterrupted()
    {
        return !job.isAway();
    }

    /**
     * 主决策点：领取全部可用订单 → PREPARE。背包满时先 RETURN 入库。
     */
    protected IAIState decide()
    {
        // 需求：敏捷等级可能因升级变化，每次决策前刷新速度加成。
        refreshSpeedBonus();
        if (job.isAway())
        {
            updateLeaderStatus(CaravanStatus.TRADING);
            return CaravanState.AWAY;
        }

        if (!job.hasActiveTrip())
        {
            acquireOrderFromBuilding();
            if (!job.hasActiveTrip())
            {
                // 需求2：空闲且白天开始时，先回小屋方块（每日例行）。
                if (isDayStart() && !canAccessHutStorage())
                {
                    return CaravanState.GOTO_HUT;
                }
                // 需求：空闲时在小屋范围内游荡（而非原地站立）。
                return CaravanState.WANDER;
            }
        }

        if (!hasAnyCaravanSpace())
        {
            return CaravanState.RETURN;
        }
        return CaravanState.PREPARE;
    }

    /** 需求：空闲时在小屋范围内游荡——贴近目标或超时后选新目标。 */
    private IAIState wander()
    {
        // 需求（帐篷修复）：空闲/等待阶段也执行帐篷检查——否则“没有帐篷 → 无可用
        // 交易 → 不进入备货”时永远不会请求/提取帐篷，只能靠解雇-重新雇佣重置。
        checkTentAndPrepare();
        if (hasWorkToDo())
        {
            wanderTarget = null;
            return AIWorkerState.IDLE;
        }
        if (wanderTarget == null
            || worker.blockPosition().distSqr(wanderTarget) <= 4
            || (wanderTimer += STATE_TICK) >= 80)
        {
            wanderTimer = 0;
            wanderTarget = randomHutPoint();
            walkToUnSafePos(wanderTarget);
        }
        return CaravanState.WANDER;
    }

    /** 需求：备货等待期间也在小屋范围内游荡（保持在小屋存储范围内）。 */
    private void wanderNearHut()
    {
        if ((wanderTimer += STATE_TICK) < 100)
        {
            return;
        }
        wanderTimer = 0;
        final BlockPos target = randomHutPoint();
        walkToUnSafePos(target);
    }

    /** 小屋附近随机点（水平方向 ±WANDER_RADIUS，保持在小屋存储范围内）。 */
    private BlockPos randomHutPoint()
    {
        return building.getPosition().offset(
            world.random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS,
            0,
            world.random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS);
    }

    /** 读取市民某项属性的等级（无数据时返回 0）。 */
    private int getSkillLevel(final Skill skill)
    {
        final ICitizenData data = job.getCitizen();
        return data != null ? data.getCitizenSkillHandler().getLevel(skill) : 0;
    }

    /**
     * 需求：敏捷影响移动速度，程度与快递员相同。
     * 参考 JobDeliveryman.onLevelUp：向 MOVEMENT_SPEED 添加 ADD_VALUE 修饰符，
     * 数值 = 敏捷等级 × 0.003（基础速度 0.3，EntityCitizen.getSpeed 上限 0.5）。
     */
    private void refreshSpeedBonus()
    {
        final int agility = getSkillLevel(Skill.Agility);
        if (agility == lastAgilityLevel)
        {
            return;
        }
        lastAgilityLevel = agility;
        // 与 minecolonies 快递员共用的修饰符 ID：重复添加时自动替换旧值。
        final AttributeModifier modifier = new AttributeModifier(
            CitizenConstants.SKILL_BONUS_ADD_NAME,
            agility * 0.003D,
            AttributeModifier.Operation.ADD_VALUE);
        AttributeModifierUtils.addModifier(worker, modifier, Attributes.MOVEMENT_SPEED);
    }

    /** 每日例行回小屋：走到小屋方块附近后回到待机。 */
    private IAIState goToHut()
    {
        // 需求（文本显示）：向小屋移动 → 展示“返回中”。
        job.setDisplayPhase(JobCaravanLeader.AwayPhase.RETURNING);
        if (walkToBuilding())
        {
            return AIWorkerState.IDLE;
        }
        return CaravanState.GOTO_HUT;
    }

    /** 是否为“白天开始”（dayTime 0~99，即日出后约 5 秒内）。 */
    private boolean isDayStart()
    {
        // 修复bug：昼夜判定必须用真实 dayTime——玩家睡觉跳过夜晚时 dayTime 跳变、
        // gameTime 不跳，二者偏差会永久累积，导致商队作息错乱。
        return world.getDayTime() % 24000L < 100L;
    }

    /** 是否已在小屋“存储容器”旁（半径 6 格内，只有此处才允许存取小屋存储）。 */
    private boolean canAccessHutStorage()
    {
        return worker.blockPosition().distSqr(building.getPosition()) <= STORAGE_RANGE_SQUARED;
    }

    /**
     * 领取订单（请求链重构）：只领取“当前层级”的交易——
     * 由模块 {@code getCurrentTradeIndex} 决定：
     * 深层优先选择“售出物已齐备”的请求链交易（如 木棍已到 → 执行 木棍→绿宝石；
     * 绿宝石已到 → 执行 绿宝石→雕纹石砖），无链交易时执行绿宝石存量补充交易；
     * 每次出行只带当前层级，避免残留上一轮交易任务。
     */
    private void acquireOrderFromBuilding()
    {
        final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
        if (module == null)
        {
            return;
        }

        final List<Integer> tradeIndices = module.getCurrentTradeIndices();
        if (tradeIndices.isEmpty())
        {
            // 需求（请求链）：暂无可用交易（售出物未送达）——清空行程，等待请求系统。
            job.getTrip().clear();
            return;
        }
        final List<TripTrade> offers = new ArrayList<>();
        for (final int tradeIndex : tradeIndices)
        {
            final int copies = module.getCurrentTradeCopies(tradeIndex);
            final var offer = module.getOffer(tradeIndex);
            final BlockPos target = module.getOfferWorkstation(tradeIndex);
            final VillagerTradeEntry villager = module.getVillagerForOffer(tradeIndex);
            if (offer == null || target == null)
            {
                continue;
            }
            for (int copy = 0; copy < copies; copy++)
            {
                offers.add(new TripTrade(
                    villager != null ? villager.villagerId() : null,
                    tradeIndex,
                    new com.example.caravan.item.TradeRecord(target, new ArrayList<>(offer.costs()), offer.result()),
                    TripStatus.PENDING));
            }
        }
        if (!offers.isEmpty())
        {
            job.startTrip(offers);
            // 需求（bug 修复）：通知模块行程已开始（抑制行程中的售出物补建）。
            module.setTripActive(true);
            tripTentDeducted = false; // 每轮行程重置出发扣耐久标记。
            tradeSummarySent = false;
            // 需求（统计）：每轮行程重置消耗统计（回归通报用）。
            torchConsumedTotal = 0;
            foodConsumedTotal = 0;
            tentConsumedTotal = 0;
            torchConsumedToday = 0;
            foodConsumedToday = 0;
            // 需求（诊断·断点）：输出本次行程的交易摘要（索引+副本数）。
            final StringBuilder summary = new StringBuilder();
            for (final int idx : tradeIndices)
            {
                final var offer = module.getOffer(idx);
                summary.append('[').append(idx).append('x')
                    .append(module.getCurrentTradeCopies(idx))
                    .append(offer != null ? offer.result().getHoverName().getString() : "?")
                    .append(']');
            }
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 领取订单：行程 {} 笔，{}",
                offers.size(), summary);
            debugLog("com.caravan.debug.action.acquire");
        }
        else
        {
            // 需求（非递归重构）：当前无可执行交易时清空行程，避免残留上一轮任务。
            job.getTrip().clear();
            module.setTripActive(false);
        }
    }

    /** 调试模式：向开启调试的玩家输出行动信息（本地化）。 */
    private void debugLog(final String key, final Object... args)
    {
        DebugFlags.sendDebug(world, Component.translatable(key, args));
    }

    /**
     * 需求（商队帐篷）：是否为“殖民地规定的睡眠时间”——
     * 参照 minecolonies 的 CitizenSleepHandler.shouldGoSleep 时间窗口：
     * 默认 dayTime ≥ 12600（19:00）进入睡眠，研究 WORK_LONGER（工作更久）
     * 每级把入睡时间延后 1000 刻；dayTime = 0（6:00）醒来。
     */
    /** 殖民地工作时间结束（入睡）时刻：默认 12600，WORK_LONGER 研究每级延后 1000 刻。 */
    private long sleepStartTime()
    {
        double sleepStart = 12600.0;
        try
        {
            final var effects = job.getColony().getResearchManager().getResearchEffects();
            final double longer = effects.getEffectStrength(ResearchConstants.WORK_LONGER);
            if (longer > 0)
            {
                sleepStart += longer * 1000.0;
            }
        }
        catch (final Exception ignored)
        {
            // 研究系统未就绪时使用默认入睡时间。
        }
        return (long) sleepStart;
    }

    /**
     * 需求（火把）：日落时刻（游戏刻）——安装 EclipticSeasons 时使用其按季节
     * 动态的白天长度计算（getNightTime = 6000 + 白天长度/2，夏季日落更晚、
     * 冬季更早）；未安装时回退原版日落 12000。
     */
    private long sunsetTime()
    {
        try
        {
            return com.teamtea.eclipticseasons.api.util.EclipticUtil.getNightTime(world);
        }
        catch (final Throwable ignored)
        {
            return 12000L;
        }
    }

    /**
     * 需求（商队帐篷·新流程）：等待物品阶段每殖民地刻执行——
     * <ol>
     *   <li>领袖背包已有帐篷 → 由外层按“帐篷 + 售出品就绪”判定出发条件；</li>
     *   <li>否则小屋存储有帐篷 → 移入领袖背包（领袖背包满时先把一个物品移到
     *       商队成员背包；所有成员满/无成员 → 移到小屋存储腾位）；</li>
     *   <li>小屋无帐篷 → 检查请求系统（checkIfRequestForItemExistOrCreate 自带
     *       防重：已有帐篷请求则等待送达，无则发起请求）。</li>
     * </ol>
     */
    private void checkTentAndPrepare()
    {
        // 需求（消耗品）：与帐篷检查同时检查食物与火把差额（只为火把创建差额请求）。
        checkFoodAndTorch();
        // 需求（设置）：帐篷携带量（默认 1）。
        final int target = targetTentCount();
        final int inCaravan = countTentsInCaravan();
        if (inCaravan >= target)
        {
            // 需求（帐篷修复）：已满足时清理残留的差额请求。
            cancelTentRequestIfOpen();
            extractAllForTrip();
            return;
        }
        // 从小屋存储按差额提取到领袖背包（满则腾位；成员/小屋兜底）。
        final int extractedCount = extractTentsFromHut();
        // 需求（诊断·断点）：成功从小屋提取时立即记录一次（便于确认提取动作发生）。
        if (extractedCount > 0)
        {
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 从小屋提取帐篷 {} 个（目标={} 提取后背包={} 小屋剩余={}）",
                extractedCount, target, countTentsInCaravan(), countTentsInHut());
        }
        // 需求（帐篷修复）：领取帐篷后重新按最新差额创建/复用请求——
        // 不再使用基类 checkIfRequestForItemExistOrCreate（公民请求会让市民进入
        // NEEDS_ITEM“正在等待所需物品”状态，且其“已完成请求不再创建”的防重会导致
        // 差额永远补不上）；改为建筑请求，快递员送达小屋存储后由本 AI 提取。
        requestTentByDifference();
        // 每殖民地刻按差额从小屋提取交易品（出发等待期间持续补齐）。
        extractAllForTrip();
    }

    /**
     * 需求（帐篷修复）：按最新差额创建/复用帐篷请求。
     * <ul>
     *   <li>请求挂在建筑（requester = 小屋）上，快递员把帐篷送到小屋存储，
     *       不会让市民进入 NEEDS_ITEM 状态，本 AI 可持续执行提取；</li>
     *   <li>弹性数量 1..差额（满耐久帐篷才匹配，仓库部分送达后按新差额重建）；</li>
     *   <li>已有打开请求则等待送达；请求结束后允许按新差额重新创建。</li>
     * </ul>
     */
    private void requestTentByDifference()
    {
        final int missing = targetTentCount() - countTentsInCaravan();
        final IRequestManager manager = job.getColony().getRequestManager();
        if (missing <= 0)
        {
            cancelTentRequestIfOpen();
            return;
        }
        if (manager == null)
        {
            return;
        }
        if (tentRequestToken != null && isOpenRequest(manager, tentRequestToken))
        {
            return; // 已有打开请求，等待送达。
        }
        tentRequestToken = null;
        try
        {
            final ItemStack tent = new ItemStack(com.example.caravan.CaravanMod.CARAVAN_TENT.get());
            final IToken<?> token = manager.createAndAssignRequest(
                building.getRequester(),
                new com.minecolonies.api.colony.requestsystem.requestable.Stack(
                    tent, missing, 1));
            tentRequestToken = token;
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 创建帐篷请求 商队帐篷 x1..{}（建筑请求，送达小屋存储）", missing);
            debugLog("com.caravan.debug.action.request",
                tent.getHoverName().getString(), missing);
        }
        catch (final Exception ignored)
        {
            debugLog("com.caravan.debug.action.request_failed",
                com.example.caravan.CaravanMod.CARAVAN_TENT.get().getDescription().getString(), missing);
        }
    }

    /** 需求（帐篷修复）：帐篷已满足时取消残留的差额请求（异常安全）。 */
    private void cancelTentRequestIfOpen()
    {
        final IRequestManager manager = job.getColony().getRequestManager();
        if (tentRequestToken == null)
        {
            return;
        }
        try
        {
            if (manager != null && isOpenRequest(manager, tentRequestToken))
            {
                manager.updateRequestState(tentRequestToken, RequestState.CANCELLED);
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 帐篷已满足，取消残留的帐篷请求 {}", String.valueOf(tentRequestToken));
            }
        }
        catch (final Exception ignored)
        {
            // 令牌可能已失效。
        }
        tentRequestToken = null;
    }

    /**
     * 需求（消耗品）：食物与火把的差额检查——
     * <ul>
     *   <li>食物：统计商队物品栏中菜单食物的组数差额，不创建请求
     *       ——仿照帐篷/火把，先从小屋存储按差额提取到物品栏
     *       （菜单模块 RestaurantMenuModule 的 onColonyTick 会按最低存量自动补货）；</li>
     *   <li>火把：按目标组数 × 64 计算差额，从商队小屋提取差额，仍不足时创建差额请求。</li>
     * </ul>
     */
    private void checkFoodAndTorch()
    {
        extractFoodsFromHut();
        extractTorchesFromHut();
        requestTorchByDifference();
    }

    /** 需求（设置）：商队食物携带组数（菜单页选择的食物按此组数保留/携带）。 */
    private int targetFoodStacks()
    {
        try
        {
            final var stock = building.getFirstModuleOccurance(
                com.example.caravan.colony.buildings.modules.CaravanStockModule.class);
            return stock != null ? Math.max(0, stock.getFoodCarryCount()) : 2;
        }
        catch (final Exception ignored)
        {
            return 2;
        }
    }

    /** 需求（设置）：商队火把携带组数。 */
    private int targetTorchStacks()
    {
        try
        {
            final var stock = building.getFirstModuleOccurance(
                com.example.caravan.colony.buildings.modules.CaravanStockModule.class);
            return stock != null ? Math.max(0, stock.getTorchCarryCount()) : 2;
        }
        catch (final Exception ignored)
        {
            return 2;
        }
    }

    /** 需求（消耗品）：商队物品栏中【菜单】选中食物的总数（个）。 */
    private int countFoodInCaravan()
    {
        try
        {
            final var menu = building.getFirstModuleOccurance(
                com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule.class);
            if (menu == null || menu.getMenu().isEmpty())
            {
                return 0;
            }
            int total = 0;
            for (final InventoryCitizen inventory : allCaravanInventories())
            {
                for (int slot = 0; slot < inventory.getSlots(); slot++)
                {
                    final ItemStack stack = inventory.getStackInSlot(slot);
                    for (final com.minecolonies.api.crafting.ItemStorage food : menu.getMenu())
                    {
                        if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, food.getItemStack()))
                        {
                            total += stack.getCount();
                            break;
                        }
                    }
                }
            }
            return total;
        }
        catch (final Exception ignored)
        {
            return 0;
        }
    }

    /** 需求（消耗品）：小屋存储 + 商队物品栏中【菜单】选中食物的总数（个）。 */
    private int countFoodAvailable()
    {
        int count = countFoodInCaravan();
        try
        {
            final var menu = building.getFirstModuleOccurance(
                com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule.class);
            if (menu == null || menu.getMenu().isEmpty())
            {
                return count;
            }
            final IItemHandler hut = building.getItemHandlerCap((Direction) null);
            if (hut != null)
            {
                for (int slot = 0; slot < hut.getSlots(); slot++)
                {
                    final ItemStack stack = hut.getStackInSlot(slot);
                    for (final com.minecolonies.api.crafting.ItemStorage food : menu.getMenu())
                    {
                        if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, food.getItemStack()))
                        {
                            count += stack.getCount();
                            break;
                        }
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 数据未就绪时只统计背包。
        }
        return count;
    }

    /**
     * 需求（消耗品）：仿照帐篷/火把——从小屋存储按差额提取【菜单】选中的食物
     * 到领袖/成员背包（优先领袖，放不下依次转成员，仍放不下放回小屋）。
     */
    private void extractFoodsFromHut()
    {
        if (!canAccessHutStorage())
        {
            return;
        }
        final int target = targetFoodStacks() * 64;
        int need = target - countFoodInCaravan();
        if (need <= 0)
        {
            return;
        }
        final IItemHandler hut = building.getItemHandlerCap((Direction) null);
        if (hut == null)
        {
            return;
        }
        final var menu = building.getFirstModuleOccurance(
            com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule.class);
        if (menu == null || menu.getMenu().isEmpty())
        {
            return;
        }
        for (int slot = 0; slot < hut.getSlots() && need > 0; slot++)
        {
            final ItemStack stack = hut.getStackInSlot(slot);
            boolean isMenuFood = false;
            for (final com.minecolonies.api.crafting.ItemStorage food : menu.getMenu())
            {
                if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, food.getItemStack()))
                {
                    isMenuFood = true;
                    break;
                }
            }
            if (!isMenuFood)
            {
                continue;
            }
            if (!worker.getInventoryCitizen().hasSpace())
            {
                freeLeaderSlot();
            }
            final ItemStack extracted = hut.extractItem(slot, Math.min(need, stack.getCount()), false);
            if (extracted.isEmpty())
            {
                continue;
            }
            ItemStack remaining = insertIntoInventory(worker.getInventoryCitizen(), extracted);
            for (final AbstractEntityCitizen member : caravanMembers())
            {
                if (remaining.isEmpty())
                {
                    break;
                }
                remaining = insertIntoInventory(member.getInventoryCitizen(), remaining);
            }
            if (!remaining.isEmpty())
            {
                hut.insertItem(slot, remaining, false);
            }
            need -= extracted.getCount() - remaining.getCount();
        }
    }

    /** 需求（消耗品）：从小屋存储按差额提取火把到领袖/成员背包（任意槽位）。 */
    private void extractTorchesFromHut()
    {
        if (!canAccessHutStorage())
        {
            return;
        }
        final int target = targetTorchStacks() * 64;
        int need = target - countTorchesInCaravan();
        if (need <= 0)
        {
            return;
        }
        final IItemHandler hut = building.getItemHandlerCap((Direction) null);
        if (hut == null)
        {
            return;
        }
        for (int slot = 0; slot < hut.getSlots() && need > 0; slot++)
        {
            final ItemStack stack = hut.getStackInSlot(slot);
            if (stack.getItem() != Items.TORCH)
            {
                continue;
            }
            if (!worker.getInventoryCitizen().hasSpace())
            {
                freeLeaderSlot();
            }
            final ItemStack extracted = hut.extractItem(slot, Math.min(need, stack.getCount()), false);
            if (extracted.isEmpty())
            {
                continue;
            }
            ItemStack remaining = insertIntoInventory(worker.getInventoryCitizen(), extracted);
            for (final AbstractEntityCitizen member : caravanMembers())
            {
                if (remaining.isEmpty())
                {
                    break;
                }
                remaining = insertIntoInventory(member.getInventoryCitizen(), remaining);
            }
            if (!remaining.isEmpty())
            {
                hut.insertItem(slot, remaining, false);
            }
            need -= extracted.getCount() - remaining.getCount();
        }
    }

    /** 需求（消耗品）：火把差额请求——目标组数 × 64 与（商队物品栏 + 小屋）已有量之差。 */
    private void requestTorchByDifference()
    {
        final int target = targetTorchStacks() * 64;
        final int missing = target - countTorchesInCaravan() - countInHut(new ItemStack(Items.TORCH));
        final IRequestManager manager = job.getColony().getRequestManager();
        if (missing <= 0)
        {
            cancelTorchRequestIfOpen();
            return;
        }
        if (manager == null)
        {
            return;
        }
        if (torchRequestToken != null && isOpenRequest(manager, torchRequestToken))
        {
            return; // 已有打开请求，等待送达。
        }
        torchRequestToken = null;
        try
        {
            final ItemStack torch = new ItemStack(Items.TORCH);
            final IToken<?> token = manager.createAndAssignRequest(
                building.getRequester(),
                new com.minecolonies.api.colony.requestsystem.requestable.Stack(
                    torch, missing, 1));
            torchRequestToken = token;
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 创建火把请求 x1..{}（建筑请求，送达小屋存储）", missing);
        }
        catch (final Exception ignored)
        {
            // 请求失败不阻塞备货。
        }
    }

    /** 需求（消耗品）：火把已满足时取消残留的差额请求（异常安全）。 */
    private void cancelTorchRequestIfOpen()
    {
        final IRequestManager manager = job.getColony().getRequestManager();
        if (torchRequestToken == null)
        {
            return;
        }
        try
        {
            if (manager != null && isOpenRequest(manager, torchRequestToken))
            {
                manager.updateRequestState(torchRequestToken, RequestState.CANCELLED);
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 火把已满足，取消残留的火把请求 {}", String.valueOf(torchRequestToken));
            }
        }
        catch (final Exception ignored)
        {
            // 令牌可能已失效。
        }
        torchRequestToken = null;
    }

    /** 商队物品栏（领袖 + 健康成员）中的火把总数。 */
    private int countTorchesInCaravan()
    {
        int count = 0;
        for (final InventoryCitizen inventory : allCaravanInventories())
        {
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                final ItemStack stack = inventory.getStackInSlot(slot);
                if (stack.getItem() == Items.TORCH)
                {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    /**
     * 需求（帐篷提取修复）：从小屋存储按差额提取商队帐篷（任意耐久度均可），
     * 优先放入领袖背包，放不下则依次放入商队成员背包，仍放不下则放回小屋。
     * 返回实际提取到背包中的帐篷数量。
     */
    private int extractTentsFromHut()
    {
        if (!canAccessHutStorage())
        {
            return 0;
        }
        final int need = targetTentCount() - countTentsInCaravan();
        if (need <= 0)
        {
            return 0;
        }
        final IItemHandler hut = building.getItemHandlerCap((Direction) null);
        if (hut == null)
        {
            return 0;
        }
        int extractedCount = 0;
        for (int slot = 0; slot < hut.getSlots() && extractedCount < need; slot++)
        {
            final ItemStack stack = hut.getStackInSlot(slot);
            if (stack.getItem() != com.example.caravan.CaravanMod.CARAVAN_TENT.get())
            {
                continue;
            }
            if (!worker.getInventoryCitizen().hasSpace())
            {
                freeLeaderSlot();
            }
            final ItemStack extracted = hut.extractItem(
                slot, Math.min(need - extractedCount, stack.getCount()), false);
            if (extracted.isEmpty())
            {
                continue;
            }
            ItemStack remaining = insertIntoInventory(worker.getInventoryCitizen(), extracted);
            for (final AbstractEntityCitizen member : caravanMembers())
            {
                if (remaining.isEmpty())
                {
                    break;
                }
                remaining = insertIntoInventory(member.getInventoryCitizen(), remaining);
            }
            if (!remaining.isEmpty())
            {
                // 领袖与所有成员均放不下 → 放回小屋存储。
                hut.insertItem(slot, remaining, false);
            }
            extractedCount += extracted.getCount() - remaining.getCount();
        }
        return extractedCount;
    }

    /** 小屋存储中商队帐篷的总数（任意耐久度），供诊断与出发判定使用。 */
    private int countTentsInHut()
    {
        final IItemHandler hut = building.getItemHandlerCap((Direction) null);
        if (hut == null)
        {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < hut.getSlots(); slot++)
        {
            if (hut.getStackInSlot(slot).getItem() == com.example.caravan.CaravanMod.CARAVAN_TENT.get())
            {
                count += hut.getStackInSlot(slot).getCount();
            }
        }
        return count;
    }

    /** 商队领袖背包中是否已携带商队帐篷。 */
    private boolean hasTentInLeaderInventory()
    {
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            if (inventory.getStackInSlot(slot).getItem()
                == com.example.caravan.CaravanMod.CARAVAN_TENT.get())
            {
                return true;
            }
        }
        return false;
    }

    /** 需求（设置）：商队帐篷携带量（小屋【设置】页配置，0..32，0 = 允许无帐篷出行）。 */
    private int targetTentCount()
    {
        try
        {
            final var settings = building.getFirstModuleOccurance(
                com.example.caravan.colony.buildings.modules.CaravanStockModule.class);
            return settings != null ? Math.max(0, settings.getTentCarryCount()) : 1;
        }
        catch (final Exception ignored)
        {
            return 1;
        }
    }

    /** 领袖 + 成员背包中的帐篷总数。 */
    private int countTentsInCaravan()
    {
        int count = 0;
        for (final InventoryCitizen inventory : allCaravanInventories())
        {
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                if (inventory.getStackInSlot(slot).getItem()
                    == com.example.caravan.CaravanMod.CARAVAN_TENT.get())
                {
                    count += inventory.getStackInSlot(slot).getCount();
                }
            }
        }
        return count;
    }

    /** 需求（商队帐篷·出发条件）：小屋存储或领袖/成员背包中的帐篷总数（≥ 携带量即就绪）。 */
    private int countTentsAvailable()
    {
        int count = countTentsInCaravan();
        // 需求（bug 修复）：商队小屋存储中的帐篷也计入“帐篷就绪”判定——
        // 读取小屋容器不需要靠近（只有提取才需要），确保出发条件检测
        // 覆盖 领袖背包 + 成员背包 + 小屋存储 三处。
        final IItemHandler hut = building.getItemHandlerCap((Direction) null);
        if (hut != null)
        {
            for (int slot = 0; slot < hut.getSlots(); slot++)
            {
                if (hut.getStackInSlot(slot).getItem()
                    == com.example.caravan.CaravanMod.CARAVAN_TENT.get())
                {
                    count += hut.getStackInSlot(slot).getCount();
                }
            }
        }
        return count;
    }

    /**
     * 需求（商队帐篷·腾位）：领袖背包已满时为帐篷腾出一个位置——
     * 把一个非帐篷物品移到商队成员背包（按成员顺序，第一个有空间的成员）；
     * 所有成员背包均满或没有成员 → 把该物品移到小屋存储。
     */
    private void freeLeaderSlot()
    {
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty() || stack.getItem() == com.example.caravan.CaravanMod.CARAVAN_TENT.get())
            {
                continue;
            }
            // 优先移入商队成员背包（扩展物品栏）。
            for (final AbstractEntityCitizen member : caravanMembers())
            {
                final ItemStack remaining = insertIntoInventory(member.getInventoryCitizen(), stack);
                if (remaining.isEmpty())
                {
                    inventory.setStackInSlot(slot, ItemStack.EMPTY);
                    inventory.markDirty();
                    return;
                }
            }
            // 无成员 / 成员背包均满 → 移到小屋存储。
            if (canAccessHutStorage())
            {
                final IItemHandler hut = building.getItemHandlerCap((Direction) null);
                if (hut != null)
                {
                    final ItemStack moved = inventory.extractItem(slot, stack.getCount(), false);
                    if (!moved.isEmpty())
                    {
                        final ItemStack rest = insertIntoHandler(hut, moved);
                        if (!rest.isEmpty())
                        {
                            inventory.insertItem(slot, rest, false);
                        }
                    }
                }
            }
            return;
        }
    }

    /** 把物品放入指定物品栏（handler），返回未能放下的剩余部分（空栈 = 全部放入）。 */
    private static ItemStack insertIntoHandler(final IItemHandler handler, final ItemStack stack)
    {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++)
        {
            remaining = handler.insertItem(slot, remaining, false);
        }
        return remaining;
    }

    /**
     * 需求（商队帐篷）：帐篷耐久处理——
     * <ul>
     *   <li>扎营（sleepRest 入睡）时（actuallyDeduct=true）按商队人数实际扣除耐久
     *       （耐久归零时帐篷消失）；</li>
     *   <li>出发时（actuallyDeduct=false）仅播报 debug 信息，不扣除耐久
     *       （机制修改：取消出发时的耐久损耗）。</li>
     * </ul>
     */
    private void deductTentDurability(final boolean actuallyDeduct)
    {
        // 需求（商队护卫）：模拟旅行中护卫卫兵视同商队成员，一并计入帐篷耐久消耗人数。
        final int x = 1 + caravanMembers().size()
            + CaravanGuardHelper.caravanGuardCitizens(job.getColony()).size();
        for (final InventoryCitizen inventory : allCaravanInventories())
        {
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                final ItemStack stack = inventory.getStackInSlot(slot);
                if (stack.getItem() == com.example.caravan.CaravanMod.CARAVAN_TENT.get())
                {
                    if (actuallyDeduct)
                    {
                        stack.setDamageValue(stack.getDamageValue() + x);
                        if (stack.getDamageValue() >= stack.getMaxDamage())
                        {
                            stack.setCount(0);
                            // 需求（统计）：耐久归零消失的帐篷计入回归通报。
                            tentConsumedTotal++;
                        }
                        // 修复bug：直接修改 ItemStack 不会触发市民数据持久化——
                        // 实体卸载/重载后帐篷耐久会回退到旧值，必须标记背包 dirty。
                        inventory.markDirty();
                    }
                    return;
                }
            }
        }
    }

    /**
     * 需求（疾病/日志）：模拟旅行中每日扎营总结——
     * 无帐篷过夜时每名（未生病）商队人员 +5% 患病概率（按游戏日防重，返程结算）；
     * 日志改为总结当天消耗食物量、火把量以及有/无帐篷扎营
     * （有帐篷记录帐篷耐久度；无帐篷记录当前累计患病几率）。
     */
    private void accumulateNightIllness()
    {
        if (!job.isAway())
        {
            return;
        }
        final long day = world.getGameTime() / 24000L;
        if (day == lastIllnessNightDay)
        {
            return; // 同一天只累计一次（防重复）。
        }
        lastIllnessNightDay = day;
        final boolean hasTent = countTentsInCaravan() > 0;
        // 需求（日志）：每天输出扎营总结（世界游戏日 + 当天消耗 + 帐篷/患病情况）。
        // 注：帐篷耐久扣除与患病概率提升已由模拟状态机在“扎营/露宿醒来”时执行。
        if (hasTent)
        {
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: [商队{}] 游戏日 {} 扎营总结：消耗食物 {}，火把 {}，有帐篷（帐篷耐久 {}）",
                caravanIndex(), day, foodConsumedToday, torchConsumedToday, tentDurabilityForLog());
        }
        else
        {
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: [商队{}] 游戏日 {} 扎营总结：消耗食物 {}，火把 {}，无帐篷（当前累计患病几率最高 {}%）",
                caravanIndex(), day, foodConsumedToday, torchConsumedToday,
                job.getMaxNightIllnessChance());
        }
        foodConsumedToday = 0;
        torchConsumedToday = 0;
    }

    /**
     * 需求（模拟旅行状态机·重构）：每殖民地刻按“当前时间 + 商队物品”决策模拟状态。
     * <ol>
     *   <li>时间 ≥ 殖民地工作时间 或 时间 < 100 刻（凌晨）：停止移动——
     *       有帐篷=扎营中，无帐篷=露宿中；</li>
     *   <li>100 ≤ 时间 < 工作时间且 ≥ 日落：状态为移动中（旅行/夜行）且有火把 → 夜行中
     *       （每殖民地刻消耗 1 火把）；无火把 → 按第 1 条扎营/露宿（停止移动）；</li>
     *   <li>100 ≤ 时间 < 日落：恢复移动（旅行中）——扎营中醒来扣帐篷耐久，
     *       露宿中醒来提升患病概率。</li>
     * </ol>
     * 状态变化时输出“当前时间XXX刻，商队状态[YYY]”并同步客户端。
     */
    private JobCaravanLeader.CampStatus decideCampStatus()
    {
        // 修复bug：昼夜判定改用真实 dayTime（玩家睡觉/时间命令会使 dayTime 跳变而
        // gameTime 不变，用 gameTime 判定会导致扎营/醒来时刻随偏差漂移）。
        final long dayTime = world.getDayTime() % 24000L;
        final JobCaravanLeader.CampStatus previous = job.getCampStatus();
        final boolean hasTent = countTentsInCaravan() > 0;
        final boolean hasTorch = hasAnyTorchInCaravan();
        final JobCaravanLeader.CampStatus status;
        // 第 1 条：殖民地工作时间结束，或凌晨（< 100 刻，尚未“天亮”）——
        // 停止移动（扎营/露宿）。
        if (dayTime >= sleepStartTime() || dayTime < 100)
        {
            status = hasTent ? JobCaravanLeader.CampStatus.CAMP : JobCaravanLeader.CampStatus.ROUGH;
        }
        else if (dayTime >= sunsetTime())
        {
            // 第 2 条：夜行窗口（日落 ~ 工作时间结束）。
            if (hasTorch
                && (previous == JobCaravanLeader.CampStatus.TRAVEL
                    || previous == JobCaravanLeader.CampStatus.NIGHT_TRAVEL))
            {
                // 有火把且商队在移动：夜行中，每殖民地刻消耗 1 火把。
                if (consumeOneTorch())
                {
                    torchConsumedTotal++;
                    torchConsumedToday++;
                }
                status = JobCaravanLeader.CampStatus.NIGHT_TRAVEL;
            }
            else
            {
                // 无火把（或已扎营/露宿）→ 停止移动，按帐篷扎营/露宿。
                status = hasTent ? JobCaravanLeader.CampStatus.CAMP : JobCaravanLeader.CampStatus.ROUGH;
            }
        }
        else
        {
            // 第 3 条：白天（100 刻 ~ 日落）→ 恢复移动。
            status = JobCaravanLeader.CampStatus.TRAVEL;
            final long day = world.getGameTime() / 24000L;
            if (previous == JobCaravanLeader.CampStatus.CAMP)
            {
                // 扎营醒来：执行帐篷耐久降低机制（每天一次）。
                if (lastTentDeductDay != day)
                {
                    lastTentDeductDay = day;
                    deductTentDurability(true);
                }
            }
            else if (previous == JobCaravanLeader.CampStatus.ROUGH)
            {
                // 露宿醒来：执行疾病概率提升机制（每天一次，每人 +5%）。
                raiseIllnessChanceForAll();
            }
        }
        if (status != previous)
        {
            job.setCampStatus(status);
            building.markDirty();
        }
        return status;
    }

    /** 需求（模拟状态机）：露宿醒来时每名商队人员 +5% 患病概率（每天一次）。 */
    private void raiseIllnessChanceForAll()
    {
        final long day = world.getGameTime() / 24000L;
        if (day == lastRoughWakeDay)
        {
            return; // 同一天只提升一次。
        }
        lastRoughWakeDay = day;
        addIllnessChance(worker.getCitizenData());
        for (final AbstractEntityCitizen member : caravanMembers())
        {
            addIllnessChance(member.getCitizenData());
        }
    }

    /** 需求（日志）：商队物品栏中第一顶帐篷的剩余耐久（无帐篷返回 0）。 */
    private int tentDurabilityForLog()
    {
        for (final InventoryCitizen inventory : allCaravanInventories())
        {
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                final ItemStack stack = inventory.getStackInSlot(slot);
                if (stack.getItem() == com.example.caravan.CaravanMod.CARAVAN_TENT.get())
                {
                    return stack.getMaxDamage() - stack.getDamageValue();
                }
            }
        }
        return 0;
    }

    /**
     * 需求（食物）：商队人员饱食度下降到需要进食时（对应本体
     * EntityAIEatTask.GET_YOURSELF_SATURATION = 30），直接从商队任意一人的
     * 物品栏中食用【菜单】页选定的食物（接入本体 FoodUtils 进食代码）。
     */
    private void feedCaravanFromSupplies()
    {
        try
        {
            final var menu = building.getFirstModuleOccurance(
                com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule.class);
            if (menu == null || menu.getMenu().isEmpty())
            {
                return;
            }
            final List<AbstractEntityCitizen> caravan = new ArrayList<>();
            caravan.add(worker);
            caravan.addAll(caravanMembers());
            // 需求（商队护卫）：模拟旅行中护卫卫兵视同商队成员，一并喂食。
            caravan.addAll(CaravanGuardHelper.caravanGuardCitizens(job.getColony()));
            for (final AbstractEntityCitizen member : caravan)
            {
                final ICitizenData data = member.getCitizenData();
                if (data == null)
                {
                    continue;
                }
                // 需求（进食阈值）：饱食度上限为 20；仅在低于 6 时开始进食。
                // 需求（机制）：一旦开始进食，连续食用【菜单】食物直到饱食度 ≥ 18
                // 或商队物品栏无菜单食物（每次吃 1 个，防止单次进食后仍饥饿）。
                final double saturation;
                try
                {
                    saturation = ((com.minecolonies.core.colony.CitizenData) data).getSaturation();
                }
                catch (final Exception ignored)
                {
                    continue;
                }
                if (saturation >= 6.0D)
                {
                    continue; // 饱食度足够，无需进食。
                }
                int eaten = 0;
                double current = saturation;
                while (current < 18.0D && eaten < 32)
                {
                    boolean fedOne = false;
                    for (final InventoryCitizen inventory : allCaravanInventories())
                    {
                        final int foodSlot = findMenuFoodSlot(inventory, menu.getMenu());
                        if (foodSlot < 0)
                        {
                            continue;
                        }
                        final ItemStack food = inventory.getStackInSlot(foodSlot).copy();
                        food.setCount(1);
                        // 进食前保存食物名（consumeFood 会消耗该栈，之后再读会显示“空气”）。
                        final String foodName = food.getHoverName().getString();
                        inventory.extractItem(foodSlot, 1, false);
                        // 修复bug：确保食物消耗在实体卸载/重载后仍然保留。
                        inventory.markDirty();
                        // 必须用 ItemStackUtils.consumeFood（内部先按 minecolonies
                        // FoodUtils.getFoodValue 增加市民饱食度，再消耗食物）。
                        final double beforeSaturation = current;
                        com.minecolonies.api.util.ItemStackUtils.consumeFood(food, member, null);
                        eaten++;
                        fedOne = true;
                        break;
                    }
                    if (!fedOne)
                    {
                        break; // 商队物品栏无菜单食物。
                    }
                    try
                    {
                        current = ((com.minecolonies.core.colony.CitizenData) data).getSaturation();
                    }
                    catch (final Exception ignored3)
                    {
                        current = 18.0D; // 读取失败视为已吃饱，结束本轮。
                    }
                }
                foodConsumedTotal += eaten;
                foodConsumedToday += eaten;
            }
        }
        catch (final Exception ignored)
        {
            // 进食失败不阻塞模拟。
        }
    }

    /** 需求（食物）：在指定物品栏中查找【菜单】选中食物的第一个槽位（-1 = 无）。 */
    private int findMenuFoodSlot(
        final InventoryCitizen inventory,
        final java.util.Set<com.minecolonies.api.crafting.ItemStorage> menu)
    {
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty())
            {
                continue;
            }
            for (final com.minecolonies.api.crafting.ItemStorage food : menu)
            {
                if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, food.getItemStack()))
                {
                    return slot;
                }
            }
        }
        return -1;
    }

    /** 需求（火把）：从商队物品栏（领袖优先）消耗 1 个火把；成功返回 true。 */
    private boolean consumeOneTorch()
    {
        for (final InventoryCitizen inventory : allCaravanInventories())
        {
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                if (inventory.getStackInSlot(slot).getItem() == Items.TORCH)
                {
                    inventory.extractItem(slot, 1, false);
                    // 修复bug：确保火把消耗在实体卸载/重载后仍然保留。
                    inventory.markDirty();
                    return true;
                }
            }
        }
        return false;
    }

    /** 商队物品栏中是否有火把。 */
    private boolean hasAnyTorchInCaravan()
    {
        return countTorchesInCaravan() > 0;
    }

    /** 需求（疾病）：为一名商队人员累积 +5% 患病概率（已生病者跳过）。 */
    private void addIllnessChance(final ICitizenData citizen)
    {
        if (citizen == null || citizen.getCitizenDiseaseHandler().isSick())
        {
            return;
        }
        job.addNightIllnessChance(citizen.getUUID(), 5);
    }

    /**
     * 需求（疾病）：返程后统一结算——按每名商队人员累积的概率判定是否患病，
     * 患病者获得一种 minecolonies 当前数据包中可出现的随机疾病
     * （DiseasesListener.getRandomDisease 按稀有度加权）。
     */
    /** 需求（疾病）：返程结算患病人数（回归通报用）。 */
    private int settleNightIllness()
    {
        final Map<UUID, Integer> chances = job.takeNightIllnessChances();
        if (chances.isEmpty())
        {
            return 0;
        }
        int sickCount = 0;
        for (final Map.Entry<UUID, Integer> entry : chances.entrySet())
        {
            final ICitizenData citizen = findCitizenByUUID(entry.getKey());
            if (citizen == null || citizen.getCitizenDiseaseHandler().isSick())
            {
                continue;
            }
            if (world.random.nextInt(100) >= entry.getValue())
            {
                continue; // 未触发。
            }
            try
            {
                final com.minecolonies.core.datalistener.model.Disease disease =
                    com.minecolonies.core.datalistener.DiseasesListener.getRandomDisease(world.random);
                if (disease != null && citizen.getCitizenDiseaseHandler().setDisease(disease))
                {
                    com.example.caravan.CaravanMod.LOGGER.info(
                        "Caravan: 市民 {} 因无帐篷过夜患病（概率 {}%）：{}",
                        citizen.getName(), entry.getValue(), disease.name().getString());
                    sickCount++;
                }
            }
            catch (final Exception ignored)
            {
                // 疾病系统未就绪时跳过（不影响行程结算）。
            }
        }
        return sickCount;
    }

    /** 需求（通报）：商队回归时向殖民地玩家通报——商队编号、本次出行天数、
     *  售出/获得物品件数、消耗火把/食物/帐篷数量、因无帐篷野营而患病人数。 */
    private void sendTripSummary(final int sickCount)
    {
        try
        {
            int sold = 0;
            int received = 0;
            for (final TripTrade offer : job.getTrip())
            {
                for (final ItemStack cost : offer.trade().costs())
                {
                    sold += cost.getCount();
                }
                if (offer.status() == TripStatus.COMPLETED)
                {
                    received += offer.trade().result().getCount();
                }
            }
            final Component message = Component.translatable("com.caravan.trip_summary",
                caravanIndex(), sold, received,
                torchConsumedTotal, foodConsumedTotal, tentConsumedTotal, sickCount,
                job.hungryCount());
            for (final var player : world.players())
            {
                player.sendSystemMessage(message);
            }
        }
        catch (final Exception ignored)
        {
            // 通报失败不影响回归流程。
        }
    }

    /**
     * 需求（商队编号）：本商队小屋在所有商队小屋中的序号（从 1 开始）——
     * 按商队小屋建立顺序排序（colony 建筑管理器按注册顺序保存）。
     */
    private int caravanIndex()
    {
        try
        {
            int index = 1;
            for (final com.minecolonies.api.colony.buildings.IBuilding hut :
                job.getColony().getServerBuildingManager().getBuildings().values())
            {
                if (hut == building)
                {
                    return index;
                }
                if (hut instanceof BuildingCaravanLeader)
                {
                    index++;
                }
            }
        }
        catch (final Exception ignored)
        {
            // 数据未就绪时按 1 处理。
        }
        return 1;
    }

    /** 按 UUID 在殖民地市民中查找市民数据（未找到返回 null）。 */
    private ICitizenData findCitizenByUUID(final UUID uuid)
    {
        try
        {
            for (final ICitizenData citizen : job.getColony().getCitizenManager().getCitizens())
            {
                if (citizen.getUUID().equals(uuid))
                {
                    return citizen;
                }
            }
        }
        catch (final Exception ignored)
        {
            // 殖民地数据未就绪时返回 null。
        }
        return null;
    }

    /**
     * 备货（机制更改）：对行程中每个订单的缺失物品发起不定量请求（1-XX）；
     * 备货阶段【不再拿取物品】——所有需求计算只统计商队小屋存储内的数量，
     * 直到出发冷却完成（或本次交易所有交易品均已备齐）且满足上午出发要求时，
     * 一次性从小屋提取全部交易品到领袖+成员物品栏（放不下的保留在小屋），
     * 再按真实物品栏计算可行交易并出发。
     */
    private IAIState prepareForTrip()
    {
        // 需求1：每次进入备货都重新计算需求——与交易列表模块当前选择保持同步
        // （玩家改动选择后，立即增删本行程要执行的订单）。
        resyncTripFromModule();
        if (job.getTrip().isEmpty())
        {
            prepareWait = 0;
            totalPrepareTicks = 0;
            final CaravanTradeModule prepareModule = building.getFirstModuleOccurance(CaravanTradeModule.class);
            if (prepareModule != null)
            {
                prepareModule.setTripActive(false);
            }
            return AIWorkerState.IDLE;
        }

        // 需求2：存取小屋存储前必须先走到小屋方块附近；否则先寻路过去。
        if (!canAccessHutStorage())
        {
            // 需求：参考快递员——先寻路到存储物品的容器旁边，再取货。
            walkToBuilding();
            return CaravanState.PREPARE;
        }

        // 需求3（机制）：每个游戏日白天开始时做一次“尽力而为”检查——
        // 只要满足任意一个交易的最低要求，就直接出发，不再等待倒计时。
        dailyBestEffortCheck();

        // 需求（商队帐篷）：等待物品阶段每殖民地刻检查——领袖背包帐篷 + 售出品就绪
        // 判定；小屋有帐篷则移入领袖背包（满则腾位），否则检查/发起请求。
        checkTentAndPrepare();

        // 需求（机制更改）：每次备货都为行程中所有未满足交易一次性请求缺失的交易品
        // （按物品聚合，弹性 1-XX，trackedRequests 防重）。
        // 需求（bug 修复）：不再因“任一交易已满足”而跳过——否则重复交易满足后，
        // 绿宝石补缺交易的售出品请求永远不会创建，商队永远等不到货（日志表现为
        // “[8x1红砖][0x10绿宝石]”只请求红砖、不请求绿宝石交易的售出品）。
        requestAllMissing();
        // 需求1（修复）：物品已满足时主动清除其残留请求（并输出调试信息）。
        cancelSatisfiedRequests();

        totalPrepareTicks += STATE_TICK;
        detectTicks += STATE_TICK;
        // 需求1（修复）：每 80 刻检测一次——物品栏满足至少一项交易 → 取消剩余请求。
        if (detectTicks >= 80)
        {
            detectTicks = 0;
            // 需求（问题3修复）：按需请求被取消后，其售出品请求不再被行程需要，
            // 主动取消，避免残留请求占据请求系统并干扰快递员。
            cancelUnneededRequests();
            // 需求（通报时机）：售出品齐备通报已移至模块每殖民地刻检测
            // （checkCompletedBroadcasts），不再依赖本 80 刻节拍。
        }

        final boolean hardCapReached = totalPrepareTicks >= MAX_PREPARE_TICKS;
        // 需求（商队消耗品·出发条件）：帐篷、食物、火把分别达到【携带量】
        // （携带量为 0 时该项恒满足），且至少一项交易的售出品满足（小屋+背包）
        // 时才进入出发等待阶段（400 刻）。
        // 需求（bug 修复）：消耗品条件是【硬性】——即使备货硬上限到达，
        // 没有帐篷/食物/火把也绝不出发（硬上限只豁免“售出品不足”）。
        final boolean tentReady = targetTentCount() <= 0 || countTentsAvailable() >= targetTentCount();
        final boolean foodReady = targetFoodStacks() <= 0 || countFoodAvailable() >= targetFoodStacks() * 64;
        final boolean torchReady = targetTorchStacks() <= 0
            || countTorchesInCaravan() + countInHut(new ItemStack(Items.TORCH)) >= targetTorchStacks() * 64;
        final boolean tradeReady = anyTradeSatisfied();
        if (!tentReady || !foodReady || !torchReady || (!tradeReady && !hardCapReached))
        {
            updateLeaderStatus(CaravanStatus.WAITING_ITEMS);
            prepareWait = 0;
            // 需求：备货等待期间在小屋范围内游荡。
            wanderNearHut();
            return CaravanState.PREPARE;
        }

        // 硬上限到达但没有任何交易满足：清空请求，带着现有物品出发
        // （能完成的交易完成，不能的标记失败，绝不无限期等待）。
        if (hardCapReached)
        {
            cancelMissingRequests();
        }

        updateLeaderStatus(CaravanStatus.READY_TO_DEPART);
        prepareWait = Math.min(prepareWait + STATE_TICK, PREPARE_WAIT_TICKS);
        // 需求4（机制）：只在“上午”（日出~正午）出发；
        // 交易品在下午/夜间送达时保持【等待出发】，次日日出后再走。
        // 需求（修改机制）：等待期内若所有将进行的交易售出品均已收到且处于上午，
        // 则立刻出发，无需等满 400 刻。
        final boolean allSatisfied = allTradeSatisfied();
        if ((prepareWait < PREPARE_WAIT_TICKS && !(allSatisfied && isDepartureTime())) || !isDepartureTime())
        {
            return CaravanState.PREPARE;
        }

        // 需求（机制更改）：出发前一次性从小屋提取全部交易品（领袖放不下的
        // 转商队成员物品栏，仍放不下的保留在小屋存储）。
        extractAllForTrip();
        // 需求（消耗品提取修复）：出发前必须真正携带帐篷/食物/火把
        // （仅统计领袖/成员背包；携带量为 0 的项跳过），
        // 防止“小屋有货但领袖未提取”时缺消耗品出发。
        final int missingTents = targetTentCount() - countTentsInCaravan();
        final int missingFood = targetFoodStacks() * 64 - countFoodInCaravan();
        final int missingTorches = targetTorchStacks() * 64 - countTorchesInCaravan();
        if (missingTents > 0 || missingFood > 0 || missingTorches > 0)
        {
            updateLeaderStatus(CaravanStatus.WAITING_ITEMS);
            prepareWait = 0;
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 消耗品仍未装入背包（帐篷缺 {}、食物缺 {}、火把缺 {}），暂不出发",
                Math.max(0, missingTents), Math.max(0, missingFood), Math.max(0, missingTorches));
            return CaravanState.PREPARE;
        }
        // 需求（商队帐篷）：出发时仅播报 debug 信息，不扣除帐篷耐久
        // （机制修改：取消出发时的耐久损耗；扎营时才实际扣除）。
        if (!tripTentDeducted)
        {
            tripTentDeducted = true;
            deductTentDurability(false);
        }
        // 等待结束：按领袖+成员物品栏真实售出品计算可行的交易并出发
        // （其余交易留在列表中，下次出行再试）。
        pruneTripToSatisfiable();
        if (job.getTrip().isEmpty())
        {
            prepareWait = 0;
            totalPrepareTicks = 0;
            final CaravanTradeModule pruneModule = building.getFirstModuleOccurance(CaravanTradeModule.class);
            if (pruneModule != null)
            {
                pruneModule.setTripActive(false);
            }
            return CaravanState.PREPARE;
        }
        // 需求3（debug）：出发时发送一次信息（目标坐标）。
        final TripTrade nearest = nearestTripTrade();
        if (nearest != null)
        {
            debugLog("com.caravan.debug.action.depart",
                nearest.trade().villagePos().getX(),
                nearest.trade().villagePos().getY(),
                nearest.trade().villagePos().getZ());
        }
        prepareWait = 0;
        totalPrepareTicks = 0;
        return CaravanState.DEPART;
    }

    /**
     * 赶路：从当前位置选择“最近”的剩余目标。
     * <ul>
     *   <li>目标在殖民地内：走到工作方块 → TRADE（停留 80 刻后完成）；</li>
     *   <li>目标在殖民地外：走到离目标最近的殖民地边界点 → 消失（AWAY）。</li>
     * </ul>
     */
    private IAIState depart()
    {
        updateLeaderStatus(CaravanStatus.TRADING);
        // 需求（文本显示）：向交易目标移动 → 展示“旅行中”。
        job.setDisplayPhase(JobCaravanLeader.AwayPhase.OUTBOUND);
        // 需求（商队护卫）：护卫卫兵战斗时领袖停等（出发步行阶段同样生效，
        // 不再仅限于 AWAY 模拟阶段）。
        if (guardsBlockMovement())
        {
            waitForGuardsIdle();
            return CaravanState.DEPART;
        }
        if (!job.hasPendingTripTrades())
        {
            return CaravanState.RETURN;
        }
        if (!hasAnyCaravanSpace())
        {
            return CaravanState.RETURN;
        }
        currentTrade = nearestTripTrade();
        if (currentTrade == null)
        {
            return CaravanState.RETURN;
        }

        final IColony colony = job.getColony();
        final BlockPos target = currentTrade.trade().villagePos();

        // 需求（真实领地）：使用 minecolonies 的区块归属判定（isCoordInColony），
        // 不再使用以殖民地为圆心的 100 格圆形。
        if (colony.isCoordInColony(world, target))
        {
            // 目标在殖民地内：不消失，走到工作方块旁完成交易。
            if (!walkToUnSafePos(target))
            {
                return CaravanState.DEPART;
            }
        tradeWait = 0;
        return CaravanState.TRADE;
        }

        // 目标在殖民地外：先走到离目标最近的殖民地边界点。
        final BlockPos border = borderPointTowards(target);
        if (!walkToUnSafePos(border))
        {
            return CaravanState.DEPART;
        }
        vanish();
        return CaravanState.AWAY;
    }

    /**
     * 需求1：把行程订单与交易列表模块的当前选择重新对齐：
     * 已禁用/已完成的条目从行程中移除，新勾选的条目加入行程。
     */
    private void resyncTripFromModule()
    {
        final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
        if (module == null)
        {
            return;
        }

        // 需求（多交易分摊）：行程只保留“当前可执行交易集合”的待执行副本。
        final List<Integer> tradeIndices = module.getCurrentTradeIndices();
        final Map<Integer, Integer> expectedCopies = new HashMap<>();
        for (final int index : tradeIndices)
        {
            expectedCopies.put(index, module.getCurrentTradeCopies(index));
        }
        final Map<Integer, Integer> current = new HashMap<>();
        final java.util.Iterator<TripTrade> iterator = job.getTrip().iterator();
        while (iterator.hasNext())
        {
            final TripTrade entry = iterator.next();
            if (entry.status() != TripStatus.PENDING)
            {
                continue; // 已结算的副本保留（日志显示）。
            }
            if (!expectedCopies.containsKey(entry.offerIndex()))
            {
                iterator.remove(); // 其它层级的待执行任务不再保留（修复残留任务）。
                continue;
            }
            final int seen = current.getOrDefault(entry.offerIndex(), 0);
            if (seen >= expectedCopies.get(entry.offerIndex()))
            {
                iterator.remove(); // 超出当前层级副本数。
                continue;
            }
            current.put(entry.offerIndex(), seen + 1);
        }
        if (tradeIndices.isEmpty())
        {
            return;
        }
        for (final int currentTrade : tradeIndices)
        {
            final TradeOfferData offer = module.getOffer(currentTrade);
            final BlockPos target = module.getOfferWorkstation(currentTrade);
            if (offer == null || target == null)
            {
                continue;
            }
            final int existingCopies = current.getOrDefault(currentTrade, 0);
            for (int copy = existingCopies; copy < expectedCopies.get(currentTrade); copy++)
            {
                final VillagerTradeEntry villager = module.getVillagerForOffer(currentTrade);
                job.getTrip().add(new TripTrade(
                    villager != null ? villager.villagerId() : null,
                    currentTrade,
                    new com.example.caravan.item.TradeRecord(target, new ArrayList<>(offer.costs()), offer.result()),
                    TripStatus.PENDING));
            }
        }
    }

    /**
     * 需求1/2：取消请求系统中针对“当前仍缺失”物品的打开请求。
     * 遍历小屋的全部打开请求（不分市民），按请求物品匹配缺失项后逐个取消。
     */
    private void cancelMissingRequests()
    {
        final IRequestManager manager = job.getColony().getRequestManager();
        if (manager == null)
        {
            return;
        }
        // 修复：取消请求会修改“打开请求”集合，必须先复制再迭代，
        // 否则 ConcurrentModificationException 会导致取消不完整。
        try
        {
            for (final Collection<IToken<?>> tokens :
                new ArrayList<>(building.getOpenRequestsByRequestableType().values()))
            {
                for (final IToken<?> token : new ArrayList<>(tokens))
                {
                    cancelIfMissing(manager, token);
                }
            }
        }
        catch (final Exception ignored)
        {
            // 遍历异常不影响整体流程。
        }
        // 异步请求兜底。
        for (final IToken<?> token : job.getAsyncRequests())
        {
            cancelIfMissing(manager, token);
        }
    }

    /** 单个请求的异常安全取消（过期/已完成的请求直接忽略）。 */
    private void cancelIfMissing(final IRequestManager manager, final IToken<?> token)
    {
        try
        {
            final IRequest<?> request = manager.getRequestForToken(token);
            // 需求（修复）：只取消“本 AI 创建的备货售出品请求”——
            // 以 trackedRequests 记录为准（本 AI 通过 requestElastic 创建的 Stack 请求）。
            // 旧实现误取消两类请求导致卡死：
            // 1) Delivery/Pickup 等交付请求（货物在领袖背包中必然缺失）；
            // 2) 请求链的售出物子请求（S 节点，Stack 类型）——当领袖物品栏内已有
            //    部分售出品（即使数量不够）时，S 请求被当作“缺失”取消，
            //    运送请求被清掉，不再补充售出品到足量，请求流卡死。
            if (request == null
                || !(request.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.Stack))
            {
                return;
            }
            if (!trackedRequests.containsValue(token))
            {
                return; // 非本 AI 创建的请求（如模块请求链的 S 节点）不取消。
            }
            if (request.getRequest() instanceof IDeliverable deliverable
                && isMissing(deliverable.getResult()))
            {
                trackedRequests.remove(deliverable.getResult().getItem());
                manager.updateRequestState(token, RequestState.CANCELLED);
                // 需求1：清除请求时输出调试信息。
                debugLog("com.caravan.debug.action.cancel",
                    deliverable.getResult().getHoverName().getString());
            }
        }
        catch (final Exception ignored)
        {
            // ignore
        }
    }

    /**
     * 需求1（修复）：物品已送达并满足需求时，清除其残留的打开请求
     * （请求系统可能未自动关闭这些请求，这里主动清理并输出调试信息）。
     */
    private void cancelSatisfiedRequests()
    {
        final IRequestManager manager = job.getColony().getRequestManager();
        if (manager == null)
        {
            return;
        }
        for (final Map.Entry<Item, IToken<?>> entry : new ArrayList<>(trackedRequests.entrySet()))
        {
            final Item item = entry.getKey();
            final IToken<?> token = entry.getValue();
            final ItemStack probe = new ItemStack(item);
            // 需求（机制更改）：满足判定统计小屋存储 + 领袖/成员物品栏。
            if (countInHut(probe) + countInInventory(probe) >= totalNeededFor(probe))
            {
                trackedRequests.remove(item);
                try
                {
                    final IRequest<?> request = manager.getRequestForToken(token);
                    if (request != null && isOpenRequest(manager, token))
                    {
                        manager.updateRequestState(token, RequestState.CANCELLED);
                        debugLog("com.caravan.debug.action.cancel_satisfied", probe.getHoverName().getString());
                    }
                }
                catch (final Exception ignored)
                {
                    // 忽略过期令牌。
                }
            }
        }
    }

    /** 本次行程（未完成订单）对该物品的总需求量（按物品种类汇总）。 */
    private int totalNeededForItem(final Item item)
    {
        return totalNeededFor(new ItemStack(item));
    }

    /** 是否为允许出发的时间窗（需求3）：每日 1000 刻起，至正午 6000 刻止。 */
    private boolean isDepartureTime()
    {
        // 修复bug：出发时间窗同样基于真实 dayTime（避免睡觉后窗口偏移）。
        final long dayTime = world.getDayTime() % 24000L;
        return dayTime >= 1000L && dayTime < 6000L;
    }

    /** 该物品是否仍然缺失（小屋存储 + 领袖/成员物品栏）。 */
    private boolean isMissing(final ItemStack cost)
    {
        return countInHut(cost) + countInInventory(cost) < cost.getCount();
    }

    /**
     * 物品栏是否已满足至少一项（未完成）交易。
     * 使用“可执行”判定（备货阶段以小屋存储为准，出发后以物品栏为准）。
     */
    private boolean anyTradeSatisfied()
    {
        for (final TripTrade offer : job.getTrip())
        {
            if (offer.status() != TripStatus.PENDING)
            {
                continue;
            }
            if (isTradeSatisfiable(offer))
            {
                return true;
            }
        }
        return false;
    }

    /** 需求（立即出发）：所有进行中的交易是否已全部可执行。 */
    private boolean allTradeSatisfied()
    {
        for (final TripTrade offer : job.getTrip())
        {
            if (offer.status() != TripStatus.PENDING)
            {
                continue;
            }
            if (!isTradeSatisfiable(offer))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 需求（机制更改）：某笔交易当前是否“可执行”——
     * 检测同时统计【商队小屋存储 + 领袖/成员物品栏】内的数量；
     * 满足后进入出发等待，等待期间每殖民地刻按差额从小屋提取。
     */
    private boolean isTradeSatisfiable(final TripTrade offer)
    {
        for (final ItemStack cost : offer.trade().costs())
        {
            if (countInHut(cost) + countInInventory(cost) >= cost.getCount())
            {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * 需求（问题3修复）：行程已不再需要某物品（如按需请求被取消、相关交易已移除）时，
     * 取消其已创建的售出品请求，避免请求残留并干扰快递员任务队列。
     */
    private void cancelUnneededRequests()
    {
        final IRequestManager manager = job.getColony().getRequestManager();
        if (manager == null)
        {
            return;
        }
        for (final Map.Entry<Item, IToken<?>> entry : new ArrayList<>(trackedRequests.entrySet()))
        {
            final Item item = entry.getKey();
            final ItemStack probe = new ItemStack(item);
            if (totalNeededFor(probe) > 0)
            {
                continue; // 行程仍需要该物品。
            }
            final IToken<?> token = entry.getValue();
            trackedRequests.remove(item);
            try
            {
                final IRequest<?> request = manager.getRequestForToken(token);
                if (request != null && isOpenRequest(manager, token))
                {
                    manager.updateRequestState(token, RequestState.CANCELLED);
                }
            }
            catch (final Exception ignored)
            {
                // 令牌可能已失效。
            }
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 取消不再需要的售出品请求 {}", probe.getHoverName().getString());
            debugLog("com.caravan.debug.action.cancel", probe.getHoverName().getString());
        }
    }

    /**
     * 需求2（修复）：一次性为“全部被选交易”缺失的交易品创建弹性请求（1-XX）。
     * <ul>
     *   <li>按物品聚合本次行程所有未完成交易的缺额（同一物品跨多个交易/副本合并）；</li>
     *   <li>每个缺失物品直接通过请求管理器创建 1..缺额 的弹性请求（min=1），
     *       不受 checkIfRequestForItemExistOrCreate 的 min 抑制逻辑影响；</li>
     *   <li>请求挂在建筑（requester = 小屋）上，可被取消逻辑找到；已有打开请求则跳过。</li>
     * </ul>
     */
    private void requestAllMissing()
    {
        final IRequestManager manager = job.getColony().getRequestManager();
        if (manager == null)
        {
            return;
        }
        // 需求（修复）：先聚合全部待执行交易对每种物品的总需求量，再一次性减去
        // 背包+小屋已有量得到总缺额——旧实现“每笔交易都减一次已有量”，
        // 多笔交易需要同一物品时缺额被严重低估（如 5 笔书架各需 9 绿宝石、已有 5，
        // 正确缺额 40，旧实现只算出 5×(9-5)=20），导致售出品请求数量不足。
        final Map<ItemStorage, Integer> totalDemand = new HashMap<>();
        for (final TripTrade offer : job.getTrip())
        {
            if (offer.status() != TripStatus.PENDING)
            {
                continue;
            }
            for (final ItemStack cost : offer.trade().costs())
            {
                totalDemand.merge(new ItemStorage(cost), cost.getCount(), Integer::sum);
            }
        }
        final List<ItemStack> missingItems = new ArrayList<>();
        final List<Integer> missingCounts = new ArrayList<>();
        for (final Map.Entry<ItemStorage, Integer> entry : totalDemand.entrySet())
        {
            final ItemStack cost = entry.getKey().getItemStack();
            // 需求（机制更改）：需求计算统计小屋存储 + 领袖/成员物品栏。
            final int deficit = entry.getValue() - countInHut(cost) - countInInventory(cost);
            if (deficit <= 0)
            {
                continue;
            }
            missingItems.add(cost);
            missingCounts.add(deficit);
        }
        for (int i = 0; i < missingItems.size(); i++)
        {
            requestElastic(manager, missingItems.get(i), missingCounts.get(i));
        }
    }

    /**
     * 弹性请求：1..totalMissing。
     * 需求1（修复）：用“令牌跟踪”去重——记录每个物品已创建的请求令牌，
     * 只有该请求已结束（完成/取消/失败）后才允许重新创建，避免持续重复请求。
     */
    private void requestElastic(final IRequestManager manager, final ItemStack item, final int totalMissing)
    {
        final IToken<?> existing = trackedRequests.get(item.getItem());
        if (existing != null && isOpenRequest(manager, existing))
        {
            return;
        }
        trackedRequests.remove(item.getItem());
        // 需求（非递归重构）：备货售出品请求一律提交给请求系统（优先仓库/工匠），
        // 商队不再自产递归；请求无人能完成时由模块把主请求下放。
        try
        {
            // 需求（售出品请求机制）：把请求的最小值设为“至少满足一次交易”的量——
            // 单次交易成本减去小屋已有数量；例如需求 2 个书架共 18 绿宝石、
            // 小屋已有 3，则请求 6-15 个（3+6=9 满足一次交易，3+15=18 满足全部交易）。
            final int singleCost = singleTradeCost(item);
            // 需求（机制更改）：已有量统计小屋存储 + 领袖/成员物品栏。
            final int alreadyHave = countInHut(item) + countInInventory(item);
            final int minCount = Math.max(1, singleCost - alreadyHave);
            final int maxCount = Math.max(minCount, totalMissing);
            final IToken<?> token = manager.createAndAssignRequest(building.getRequester(),
                new com.minecolonies.api.colony.requestsystem.requestable.Stack(item.copyWithCount(1), maxCount, minCount));
            trackedRequests.put(item.getItem(), token);
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 创建售出品请求 {} x{}..{}（总缺口 {}，已有 {}）",
                item.getHoverName().getString(), minCount, maxCount, totalMissing, alreadyHave);
            // 需求（取货机制确认）：请求创建时输出调试信息，便于确认取货链路是否工作。
            debugLog("com.caravan.debug.action.request",
                item.getHoverName().getString(), totalMissing);
        }
        catch (final Exception ignored)
        {
            // 请求失败不阻塞备货流程（失败原因见日志/调试输出）。
            debugLog("com.caravan.debug.action.request_failed",
                item.getHoverName().getString(), totalMissing);
        }
    }

    /** 需求（售出品请求机制）：行程中该物品的“单次交易成本”最小值。 */
    private int singleTradeCost(final ItemStack item)
    {
        int min = Integer.MAX_VALUE;
        for (final TripTrade offer : job.getTrip())
        {
            if (offer.status() != TripStatus.PENDING)
            {
                continue;
            }
            for (final ItemStack cost : offer.trade().costs())
            {
                if (ItemStackUtils.compareItemStacksIgnoreStackSize(cost, item))
                {
                    min = Math.min(min, cost.getCount());
                }
            }
        }
        return min == Integer.MAX_VALUE ? 1 : min;
    }

    /** 小屋存储中该物品的数量（取货机制确认用）。 */
    private int countInHut(final ItemStack cost)
    {
        final IItemHandler hut = building.getItemHandlerCap((Direction) null);
        if (hut == null)
        {
            return 0;
        }
        int count = 0;
        for (int slot = 0; slot < hut.getSlots(); slot++)
        {
            final ItemStack stack = hut.getStackInSlot(slot);
            if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, cost))
            {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 该请求令牌对应的请求是否仍在进行中（未结束）。 */
    private static boolean isOpenRequest(final IRequestManager manager, final IToken<?> token)
    {
        try
        {
            final IRequest<?> request = manager.getRequestForToken(token);
            if (request == null)
            {
                return false;
            }
            final RequestState state = request.getState();
            return state != RequestState.COMPLETED
                && state != RequestState.CANCELLED
                && state != RequestState.OVERRULED
                && state != RequestState.FAILED;
        }
        catch (final Exception ignored)
        {
            return false;
        }
    }

    /**
     * 需求3（机制修正）：每个游戏日白天开始时做一次检查——
     * 只有“所有将进行的交易售出品均已齐备”时才跳过等待直接出发；
     * 仅部分售出品送达时保持正常 400 刻等待倒计时（任意一项满足 → 进入等待；
     * 等待期间全部满足 → 立即出发；否则等待结束出发，且需满足上午时间限制）。
     */
    private void dailyBestEffortCheck()
    {
        final long day = world.getGameTime() / 24000L;
        if (day == lastDailyCheckDay)
        {
            return;
        }
        lastDailyCheckDay = day;
        // 需求3：只在出发时间窗（1000 刻后）内、且全部售出品齐备时才跳过等待。
        if (isDepartureTime() && allTradeSatisfied())
        {
            cancelMissingRequests();
            prepareWait = PREPARE_WAIT_TICKS;
        }
    }

    /** 本次行程（未完成订单）对该物品的总需求量。 */
    private int totalNeededFor(final ItemStack cost)
    {
        int total = 0;
        for (final TripTrade offer : job.getTrip())
        {
            if (offer.status() != TripStatus.PENDING)
            {
                continue;
            }
            for (final ItemStack candidate : offer.trade().costs())
            {
                if (ItemStackUtils.compareItemStacksIgnoreStackSize(candidate, cost))
                {
                    total += candidate.getCount();
                }
            }
        }
        return total;
    }

    /**
     * 更新商队领袖的日志状态；状态变化时把建筑标记为脏，
     * 使【日志】选项卡能较快收到同步（打开 GUI 时另有强制刷新消息）。
     */
    private void updateLeaderStatus(final CaravanStatus status)
    {
        if (job.getStatus() != status)
        {
            job.setStatus(status);
            building.markDirty();
        }
    }

    /**
     * 在目标工作方块旁停留 80 游戏刻后完成当前交易，然后继续下一个目标。
     * 需求：智力每 10 级降低 5% 的单笔交易延迟（最低保留 50%，即 40 刻）。
     */
    private IAIState tradeAtWorkstation()
    {
        // 需求（文本显示）：到达交易目标等待交易 → 展示“交易中”。
        job.setDisplayPhase(JobCaravanLeader.AwayPhase.TRADING);
        if (currentTrade == null)
        {
            return CaravanState.DEPART;
        }

        // 需求：交易开始时发送一次汇总消息（与X名村民进行Y项交易），不再逐笔刷屏。
        sendTradeSummaryOnce();

        tradeWait += STATE_TICK;
        if (tradeWait < tradeDelayTicks())
        {
            return CaravanState.TRADE;
        }

        completeTrade(currentTrade);
        currentTrade = null;
        return CaravanState.DEPART;
    }

    /** 需求：每个行程只发送一次“与X名村民进行Y项交易”的汇总消息。 */
    private void sendTradeSummaryOnce()
    {
        if (tradeSummarySent)
        {
            return;
        }
        tradeSummarySent = true;
        final boolean away = job.isAway();
        final BlockPos center = away
            ? job.getAwayPos()
            : (currentTrade != null ? currentTrade.trade().villagePos() : null);
        if (center == null)
        {
            return;
        }
        // 需求（debug改动）：殖民地加载范围内交易时只统计“当前交易目标”的交易——
        // X 恒为 1（当前村民），Y 为该工作方块位置的交易数量（含副本）；
        // 模拟交易（消失中）仍按 100 格打包统计。
        final long radiusSq = (long) JobCaravanLeader.TRADE_PACK_RADIUS * JobCaravanLeader.TRADE_PACK_RADIUS;
        final java.util.Set<java.util.UUID> villagers = new java.util.HashSet<>();
        int total = 0;
        for (final TripTrade offer : job.getTrip())
        {
            if (offer.status() != TripStatus.PENDING)
            {
                continue;
            }
            if (away)
            {
                if (offer.trade().villagePos().distSqr(center) > radiusSq)
                {
                    continue;
                }
            }
            else if (!offer.trade().villagePos().equals(center))
            {
                continue;
            }
            total++;
            if (offer.villagerId() != null)
            {
                villagers.add(offer.villagerId());
            }
        }
        debugLog("com.caravan.debug.action.trade_summary", away ? villagers.size() : 1, total);
    }
    private int tradeDelayTicks()
    {
        return job.tradeDelayTicks();
    }

    /**
     * 确认并完成一笔交易（需求3）：
     * 背包持有对应交易品 → 支付成本、生成成果并标记【已完成】；
     * 背包缺失交易品 → 不进行交易，标记【失败】。
     * 修复：该交易的全部副本均已结算（无论成功或失败）时，【单次】转为【禁用】，
     * 保证商队领袖归来后单次交易不再残留为“单次”状态。
     */
    private void completeTrade(final TripTrade offer)
    {
        boolean hasAllCosts = true;
        for (final ItemStack cost : offer.trade().costs())
        {
            if (!hasInInventory(cost))
            {
                hasAllCosts = false;
                break;
            }
        }
        if (hasAllCosts)
        {
            payCosts(offer.trade().costs());
            // 需求：建筑统计——售出（成本）与获得（成果）物品数量。
            trackTradeStats(offer);
            if (!offer.trade().result().isEmpty())
            {
                final ItemStack result = offer.trade().result().copy();
                job.getResults().add(result);
                // 消失期间只记录成果，归来时由 restoreSuppliesAndResults 统一放入背包，
                // 避免重复入包。
                if (!job.isAway())
                {
                    // 需求：领袖背包放不下时，成果溢出放入商队成员背包。
                    ItemStack remaining = insertIntoInventory(worker.getInventoryCitizen(), result);
                    for (final AbstractEntityCitizen member : caravanMembers())
                    {
                        if (remaining.isEmpty())
                        {
                            break;
                        }
                        remaining = insertIntoInventory(member.getInventoryCitizen(), remaining);
                    }
                }
            }
        }
        // 先标记本笔状态，再判断是否已全部结算。
        job.markTripStatus(offer, hasAllCosts ? TripStatus.COMPLETED : TripStatus.FAILED);

        final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
        if (module != null)
        {
            // 仅当该订单的全部副本均已结算（成功或失败）时，才把模块标记为完成：
            // 【单次】转禁用、【重复】保持；数量大于 1 时全部副本结算后才禁用。
            final boolean allCopiesSettled = job.getTrip().stream()
                .noneMatch(o -> o.offerIndex() == offer.offerIndex() && o.status() == TripStatus.PENDING);
            if (allCopiesSettled)
            {
                module.markCompleted(offer.offerIndex());
            }
            // 需求3（可行性）：交易成功时为村民授予经验并升级（村民实体已加载时）。
            if (hasAllCosts)
            {
                grantVillagerXp(module, offer);
            }
        }
    }

    /**
     * 需求：建筑统计模块——按“单个物品”分别统计售出（支付的成本）与获得（成果）数量。
     * 使用 StatsUtil.trackStatByStack：统计键格式为 "类别;物品名"，
     * 统计窗口会把它渲染为“类别翻译 + 数量 + 物品名翻译”。
     */
    private void trackTradeStats(final TripTrade offer)
    {
        for (final ItemStack cost : offer.trade().costs())
        {
            StatsUtil.trackStatByStack(building, "caravan_sold", cost, cost.getCount());
        }
        if (!offer.trade().result().isEmpty())
        {
            StatsUtil.trackStatByStack(building, "caravan_received", offer.trade().result(), offer.trade().result().getCount());
        }
    }

    /**
     * 需求3：像玩家交易一样为村民授予经验并使其升级。
     * <ul>
     *   <li>村民实体已加载：立即应用经验/升级，并按原版交易池生成新等级的交易，
     *       随后重新记录到小屋 → GUI 显示新增交易；</li>
     *   <li>村民未加载：把经验累计到记录（pendingXp），待村民加载后由
     *       CaravanTradeModule.onColonyTick 统一补升级、补交易并刷新 GUI。</li>
     * </ul>
     */
    private void grantVillagerXp(final CaravanTradeModule module, final TripTrade offer)
    {
        // 需求2（修复）：村民 UUID 在行程规划时已写入 TripTrade，
        // 不再依赖模块索引解析（避免重录后索引偏移导致经验发放到错误村民）。
        if (offer.villagerId() == null)
        {
            return;
        }
        final TradeOfferData offerData = module.getOffer(offer.offerIndex());
        final int xp = offerData != null ? offerData.xp() : 5;

        final Entity entity = world.getEntity(offer.villagerId());
        if (entity instanceof Villager villager)
        {
            CaravanTradeModule.applyXpToLoadedVillager(villager, xp);
            module.addVillagerTrades(villager, world);
        }
        else
        {
            module.addXpToVillager(offer.villagerId(), xp);
        }
    }

    /** 计算某物品在商队领袖背包中的数量。 */
    private int countInInventory(final ItemStack cost)
    {
        int count = 0;
        // 需求：消失期间，本次模拟交易成果（job.getResults()）视为虚拟背包——
        // 后置交易可直接消耗前置交易产出的物品。
        if (job.isAway())
        {
            for (final ItemStack result : job.getResults())
            {
                if (ItemStackUtils.compareItemStacksIgnoreStackSize(result, cost))
                {
                    count += result.getCount();
                }
            }
        }
        // 需求：商队成员物品栏视为领袖的扩展背包——统计时一并计入。
        for (final InventoryCitizen inventory : allCaravanInventories())
        {
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                final ItemStack stack = inventory.getStackInSlot(slot);
                if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, cost))
                {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    /** 需求：领袖 + 所有在编商队成员的物品栏（成员实体未加载时自动跳过）。 */
    private List<InventoryCitizen> allCaravanInventories()
    {
        final List<InventoryCitizen> inventories = new ArrayList<>();
        inventories.add(worker.getInventoryCitizen());
        for (final AbstractEntityCitizen member : caravanMembers())
        {
            inventories.add(member.getInventoryCitizen());
        }
        return inventories;
    }

    /** 需求：商队小屋中所有已雇佣且在线的商队成员实体（生病的成员除外——不参与商队出行）。 */
    private List<AbstractEntityCitizen> caravanMembers()
    {
        final List<AbstractEntityCitizen> members = new ArrayList<>();
        for (final WorkerBuildingModule module : building.getModulesByType(WorkerBuildingModule.class))
        {
            if (!module.getJobEntry().getKey().equals(CaravanMod.JOB_CARAVAN_MEMBER.getKey()))
            {
                continue;
            }
            for (final ICitizenData data : module.getAssignedCitizen())
            {
                // 需求（疾病）：生病的商队成员物品栏不计入商队物品栏，且不跟随领袖。
                if (data.getCitizenDiseaseHandler().isSick())
                {
                    continue;
                }
                data.getEntity().ifPresent(members::add);
            }
        }
        return members;
    }

    /** 需求：领袖或任意商队成员背包有空间即可继续工作（成员 = 扩展背包）。 */
    private boolean hasAnyCaravanSpace()
    {
        for (final InventoryCitizen inventory : allCaravanInventories())
        {
            if (inventory.hasSpace())
            {
                return true;
            }
        }
        return false;
    }

    private boolean hasInInventory(final ItemStack cost)
    {
        return countInInventory(cost) >= cost.getCount();
    }

    /**
     * 需求（机制更改）：出发前一次性从小屋存储提取本次行程所需的全部交易品——
     * 领袖背包放不下的物品转入商队成员物品栏；仍放不下的保留在小屋存储。
     * 提取完成后由 pruneTripToSatisfiable 按真实物品栏计算可行的交易。
     */
    private void extractAllForTrip()
    {
        // 只有在小屋方块附近才允许从小屋存储中取物。
        if (!canAccessHutStorage())
        {
            return;
        }
        // 需求（帐篷提取修复）：出发前按差额把商队帐篷从小屋装入领袖/成员背包，
        // 与交易品一样要求先到达小屋方块附近。
        extractTentsFromHut();
        final IItemHandler hut = building.getItemHandlerCap((Direction) null);
        if (hut == null)
        {
            return;
        }
        // 按物品种类聚合本次行程的总需求量，逐种提取。
        final Map<com.minecolonies.api.crafting.ItemStorage, Integer> totalDemand = new HashMap<>();
        for (final TripTrade offer : job.getTrip())
        {
            if (offer.status() != TripStatus.PENDING)
            {
                continue;
            }
            for (final ItemStack cost : offer.trade().costs())
            {
                totalDemand.merge(new com.minecolonies.api.crafting.ItemStorage(cost), cost.getCount(), Integer::sum);
            }
        }
        for (final Map.Entry<com.minecolonies.api.crafting.ItemStorage, Integer> entry : totalDemand.entrySet())
        {
            final ItemStack cost = entry.getKey().getItemStack();
            int needed = entry.getValue() - countInInventory(cost);
            if (needed <= 0)
            {
                continue;
            }
            for (int slot = 0; slot < hut.getSlots() && needed > 0; slot++)
            {
                final ItemStack stack = hut.getStackInSlot(slot);
                if (!ItemStackUtils.compareItemStacksIgnoreStackSize(stack, cost))
                {
                    continue;
                }
                final ItemStack extracted = hut.extractItem(slot, Math.min(needed, stack.getCount()), false);
                if (extracted.isEmpty())
                {
                    continue;
                }
                // 领袖背包放不下 → 商队成员物品栏（扩展物品栏）→ 仍放不下则留在小屋。
                ItemStack remaining = insertIntoInventory(worker.getInventoryCitizen(), extracted);
                for (final AbstractEntityCitizen member : caravanMembers())
                {
                    if (remaining.isEmpty())
                    {
                        break;
                    }
                    remaining = insertIntoInventory(member.getInventoryCitizen(), remaining);
                }
                needed -= extracted.getCount() - remaining.getCount();
            }
        }
    }

    /**
     * 需求3（非递归重构）：出发前把行程修剪为“当前可执行”的交易——
     * 按领袖+成员物品栏中的真实售出品数量计算每个交易可行的副本数：
     * 同一售出品被多笔交易共享时按行程顺序贪心分配（先到先得）；
     * 超出可行副本数的交易从本次行程移除。行程的【日志】与每个交易点的
     * 等待时间（交易数 × 每笔延迟）随之按真实可行的交易数量计算。
     */
    private void pruneTripToSatisfiable()
    {
        // 汇总领袖与全部商队成员物品栏中的可用售出品数量。
        final Map<com.minecolonies.api.crafting.ItemStorage, Integer> available = new HashMap<>();
        for (final InventoryCitizen inventory : allCaravanInventories())
        {
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                final ItemStack stack = inventory.getStackInSlot(slot);
                if (!stack.isEmpty())
                {
                    available.merge(new com.minecolonies.api.crafting.ItemStorage(stack), stack.getCount(), Integer::sum);
                }
            }
        }
        // 按行程顺序贪心保留可支付的副本：支付后从可用量中扣除。
        final java.util.Iterator<TripTrade> iterator = job.getTrip().iterator();
        while (iterator.hasNext())
        {
            final TripTrade offer = iterator.next();
            boolean canPay = true;
            for (final ItemStack cost : offer.trade().costs())
            {
                final int have = available.getOrDefault(
                    new com.minecolonies.api.crafting.ItemStorage(cost), 0);
                if (have < cost.getCount())
                {
                    canPay = false;
                    break;
                }
            }
            if (!canPay)
            {
                iterator.remove(); // 真实物品栏不足 → 本次行程不执行该副本。
                continue;
            }
            for (final ItemStack cost : offer.trade().costs())
            {
                final com.minecolonies.api.crafting.ItemStorage key =
                    new com.minecolonies.api.crafting.ItemStorage(cost);
                available.put(key, available.get(key) - cost.getCount());
            }
        }
    }

    /** 该订单的产出物是否被行程中其它待执行订单用作交易品（决定目标选择优先级）。 */
    private boolean isProducerOrder(final TripTrade offer)
    {
        final ItemStack result = offer.trade().result();
        if (result.isEmpty())
        {
            return false;
        }
        return job.getTrip().stream()
            .filter(o -> o != offer && o.status() == TripStatus.PENDING)
            .anyMatch(o -> {
                for (final ItemStack cost : o.trade().costs())
                {
                    if (ItemStackUtils.compareItemStacksIgnoreStackSize(cost, result))
                    {
                        return true;
                    }
                }
                return false;
            });
    }

    /**
     * 进入消失状态（目标在殖民地外）：
     * <ul>
     *   <li>保存随身补给；</li>
     *   <li>记录“当前位置 → 最远剩余目标”的距离，作为去程/回程的初始值；</li>
     *   <li>市民隐形，等待去程 → 交易中 → 回程 的阶段模拟结束。</li>
     * </ul>
     */
    private void vanish()
    {
        worker.getNavigation().stop();

        // 需求（多段模拟）：首段去程距离 = 当前位置到最近剩余目标的距离；
        // 之后每一段由 job.afterTradingSettled() 依次推进到下一个最近目标。
        final JobCaravanLeader.TripTrade nearest = nearestTripTrade();
        final int firstDistance = nearest != null
            ? (int) Math.round(Math.sqrt(Math.max(1.0,
                worker.blockPosition().distSqr(nearest.trade().villagePos()))))
            : 0;

        // 保存随身补给（防止消失期间实体被重置导致丢失）。
        job.getSupplies().clear();
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty())
            {
                job.getSupplies().add(stack.copy());
            }
        }

        job.vanish(firstDistance, worker.blockPosition());
        worker.setInvisible(true);
        setSimulationInvulnerable(true);
    }

    /** 需求（保护机制）：模拟旅行（隐形）期间无敌并清空威胁表——
     *  防止 mob 在商队消失/模拟阶段攻击商队人员。 */
    private void setSimulationInvulnerable(final boolean invulnerable)
    {
        try
        {
            worker.setInvulnerable(invulnerable);
            if (worker instanceof com.minecolonies.api.entity.ai.combat.threat.IThreatTableEntity threat)
            {
                threat.getThreatTable().resetTable();
            }
            worker.setLastHurtByMob(null);
            worker.setTarget(null);
        }
        catch (final Exception ignored)
        {
            // 保护设置失败不影响逻辑。
        }
    }

    /**
     * 消失期间（需求：多段模拟）：阶段流 去程 → 交易中 →（仍有剩余交易 ? 去程 : 回程）。
     * <ul>
     *   <li>去程：距离每 20 刻减 10 格；归零 → 进入【交易中】；</li>
     *   <li>交易中：停留 本段 100 格内可执行交易数 × 每笔延迟；结束时结算本段交易的
     *       成果与模拟经验；若仍有未完成交易，模拟前往下一个最近交易地点（新一段去程）；</li>
     *   <li>回程：距离每 20 刻减 10 格；归零 → 归来（现身、恢复补给与成果），进入 RETURN。</li>
     * </ul>
     */
    private IAIState stayAway()
    {
        updateLeaderStatus(CaravanStatus.TRADING);
        // 修复bug：AWAY 模拟期间保持隐形——实体重载后 invisible 标志不持久化，
        // 丢失后商队领袖会被 minecolonies 睡眠机制在夜间接管（job AI 暂停，
        // 导致夜间不扎营、清晨才补 CAMP）。穿越殖民地（步行现身）期间除外。
        if (!job.isWalkingThroughColony() && !worker.isInvisible())
        {
            worker.setInvisible(true);
            setSimulationInvulnerable(true);
        }
        // 每日扎营总结（消耗统计）。
        accumulateNightIllness();
        // 食物：商队人员饱食度不足时食用菜单选定的食物。
        feedCaravanFromSupplies();

        // 交易中阶段：不触发状态机——保持“交易中”，交易倒计时结束后
        // 再判断是否应当夜行/扎营/露宿（第 4 条）。
        if (job.getAwayPhase() == JobCaravanLeader.AwayPhase.TRADING)
        {
            job.setCampStatus(JobCaravanLeader.CampStatus.TRADING);
        }
        else
        {
            // 模拟状态机（第 1-3 条）：扎营/露宿 → 停止移动（不推进模拟）。
            final JobCaravanLeader.CampStatus status = decideCampStatus();
            if (status == JobCaravanLeader.CampStatus.CAMP
                || status == JobCaravanLeader.CampStatus.ROUGH)
            {
                worker.getNavigation().stop();
                return CaravanState.AWAY;
            }
        }

        // 需求（穿越殖民地）：模拟位置到达入口 A 后，实体现身并步行至出口 B，
        // 期间冻结距离倒数；到达后再次消失并继续模拟。
        if (job.isWalkingThroughColony())
        {
            return walkThroughColony();
        }
        // 需求（商队护卫）：护卫卫兵战斗时领袖停等——战斗结束后卫兵回到
        // 领袖 6 格范围内才继续移动（模拟旅行中的隐形卫兵不参与判定）。
        if (guardsBlockMovement())
        {
            // 需求（bug 修复）：AWAY 阶段取消旧寻路目标，防止隐形实体沿旧路径移动。
            worker.getNavigation().stop();
            return CaravanState.AWAY;
        }
        final JobCaravanLeader.AwayPhase before = job.getAwayPhase();
        if (job.tickAway())
        {
            // 需求（回程机制）：在“小屋与最后目标连线与殖民地边界交点”处重新出现，
            // 而非旧的消失位置；随行的商队成员一并传送到该处。
            // 修复bug：reappear() 会清空 awayReturnPos，必须先读取再重置。
            final BlockPos returnPos = job.getAwayReturnPos();
            job.reappear();
            if (returnPos != null)
            {
                worker.teleportTo(
                    returnPos.getX() + 0.5,
                    worker.blockPosition().getY(),
                    returnPos.getZ() + 0.5);
                worker.getNavigation().stop();
                for (final AbstractEntityCitizen member : caravanMembers())
                {
                    if (member.isInvisible())
                    {
                        member.teleportTo(
                            returnPos.getX() + 0.5 + world.random.nextInt(3) - 1,
                            worker.blockPosition().getY(),
                            returnPos.getZ() + 0.5 + world.random.nextInt(3) - 1);
                        member.getNavigation().stop();
                    }
                }
            }
            // 需求（回归机制）：解除隐形后延 1 殖民地刻——先保持隐形完成
            // 回程结算/传送，由 RETURN 状态的第一次 tick 再解除。
            revealDelayTicks = STATE_TICK;
            restoreSuppliesAndResults();
            // 需求（bug修复）：重新出现时立即把建筑标记为脏，让客户端尽快同步
            // “消失状态=false”，使旅行地图标记在出现瞬间就消失（而非到达小屋后）。
            building.markDirty();
            return CaravanState.RETURN;
        }
        final JobCaravanLeader.AwayPhase after = job.getAwayPhase();
        if (before == JobCaravanLeader.AwayPhase.OUTBOUND
            && after == JobCaravanLeader.AwayPhase.TRADING)
        {
            // 到达目标区域：进入“交易中”阶段（停留时间已在 job.tickAway 内计算）。
            job.setCampStatus(JobCaravanLeader.CampStatus.TRADING);
            // 需求：交易开始时发送一次汇总消息（与X名村民进行Y项交易）。
            sendTradeSummaryOnce();
        }
        else if (before == JobCaravanLeader.AwayPhase.TRADING
            && after == JobCaravanLeader.AwayPhase.RETURNING)
        {
            // 交易结束：结算本段 100 格内交易的成果与模拟经验，
            // 再由 job.afterTradingSettled() 决定下一段去程或回程。
            settlePendingTrades();
            job.afterTradingSettled();
            // 第 4 条：交易倒计时结束后，单独做一次 1-3 条的检查（按当前时间
            // 与物品重新决定状态，不等待下一殖民地刻）。
            decideCampStatus();
            if (job.getAwayPhase() == JobCaravanLeader.AwayPhase.OUTBOUND)
            {
                // 需求：仍有剩余交易——提示下一个目的地，并重置汇总标记，
                // 到达下一目的地后再发送“与X名村民进行Y项交易”。
                final JobCaravanLeader.TripTrade next = job.nearestPendingTarget(job.getAwayPos());
                if (next != null)
                {
                    debugLog("com.caravan.debug.action.next_destination",
                        next.trade().villagePos().getX(),
                        next.trade().villagePos().getY(),
                        next.trade().villagePos().getZ());
                }
                tradeSummarySent = false;
            }
            else
            {
                // 需求：全部交易完成——提示“返回”。
                debugLog("com.caravan.debug.action.returning");
            }
        }
        // 距离每 20 刻变化一次，标记建筑脏以便日志页签尽快同步。
        building.markDirty();
        return CaravanState.AWAY;
    }

    /**
     * 需求（穿越殖民地）：实体现身于入口 A，寻路至出口 B，到达后再次消失。
     * <ul>
     *   <li>首次进入：传送到入口 A 并取消隐形；</li>
     *   <li>步行中：寻路到出口 B；</li>
     *   <li>到达 B：保存补给、重新隐形，调用 job.finishColonyWalk() 续接去程倒数。</li>
     * </ul>
     */
    private IAIState walkThroughColony()
    {
        final BlockPos exit = job.getAwayColonyExit();
        if (exit == null)
        {
            job.finishColonyWalk();
            return CaravanState.AWAY;
        }
        if (worker.isInvisible())
        {
            // 首次：在入口 A 现身（传送过去，A 位于殖民地内）。
            final BlockPos entry = job.getAwayColonyEntry();
            if (entry != null)
            {
                worker.teleportTo(
                    entry.getX() + 0.5,
                    worker.blockPosition().getY(),
                    entry.getZ() + 0.5);
                // 需求（穿越殖民地）：整个商队一同在入口 A 重新出现并步行至出口 B。
                for (final AbstractEntityCitizen member : caravanMembers())
                {
                    if (member.isInvisible())
                    {
                        member.teleportTo(
                            entry.getX() + 0.5 + world.random.nextInt(3) - 1,
                            worker.blockPosition().getY(),
                            entry.getZ() + 0.5 + world.random.nextInt(3) - 1);
                        member.getNavigation().stop();
                        member.setInvisible(false);
                        member.setInvulnerable(false);
                        if (member instanceof com.minecolonies.api.entity.ai.combat.threat.IThreatTableEntity threat)
                        {
                            threat.getThreatTable().resetTable();
                        }
                    }
                }
            }
            worker.getNavigation().stop();
            colonyWalkTicks = 0;
            worker.setInvisible(false);
            setSimulationInvulnerable(false);
            return CaravanState.AWAY;
        }
        // 需求（防卡死）：步行超时后强制继续模拟，防止寻路到不可达的出口点
        // 导致全员卡在殖民地边界、GUI 去程距离停止减少。
        colonyWalkTicks += STATE_TICK;
        if (colonyWalkTicks < COLONY_WALK_TIMEOUT && !walkToUnSafePos(exit))
        {
            return CaravanState.AWAY;
        }
        // 到达出口 B：重新消失并继续模拟。
        revanish();
        return CaravanState.AWAY;
    }

    /**
     * 需求（穿越殖民地）：与 vanish() 相同但不重置行程/距离——
     * 仅保存随身补给并隐形，随后调用 job.finishColonyWalk() 续接去程倒数。
     */
    private void revanish()
    {
        worker.getNavigation().stop();
        job.getSupplies().clear();
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (!stack.isEmpty())
            {
                job.getSupplies().add(stack.copy());
            }
        }
        worker.setInvisible(true);
        setSimulationInvulnerable(true);
        job.finishColonyWalk();
    }

    /** 交易阶段结束：结算本段模拟位置 100 格内仍未结算的交易（成功者完成，缺货者标记失败）。 */
    private void settlePendingTrades()
    {
        final BlockPos stop = job.getAwayPos();
        final long radiusSq = (long) JobCaravanLeader.TRADE_PACK_RADIUS * JobCaravanLeader.TRADE_PACK_RADIUS;
        int settled = 0;
        for (final TripTrade offer : new ArrayList<>(job.getTrip()))
        {
            if (offer.status() != TripStatus.PENDING)
            {
                continue;
            }
            // 需求（多段模拟）：只结算本段 100 格以内的交易，其余保留到下一段。
            if (stop != null && offer.trade().villagePos().distSqr(stop) > radiusSq)
            {
                continue;
            }
            completeTrade(offer);
            settled++;
        }
        // 需求（经验）：参照 minecolonies 快递员——送达物品后获得经验
        // （EntityAIWorkDeliveryman 每次送达动作结算 addExperience(1.5)）。
        // 商队每完成一笔交易，领袖与每名（健康）商队成员获得对应经验奖励，
        // 由 CaravanExperienceHandler 按自定义属性比例分配（敏捷100%/智力50%等）。
        if (settled > 0)
        {
            grantTradeExperience(settled);
        }
    }

    /** 需求（商队护卫）：是否存在需要等待的护卫卫兵（战斗中或战斗后未归队）。 */
    private boolean guardsBlockMovement()
    {
        try
        {
            final java.util.List<AbstractEntityCitizen> guards =
                CaravanGuardHelper.caravanGuardCitizens(job.getColony());
            boolean anyVisible = false;
            boolean anyCombat = false;
            for (final AbstractEntityCitizen guard : guards)
            {
                if (guard.isInvisible())
                {
                    continue; // 模拟旅行中（消失）不参与判定。
                }
                anyVisible = true;
                if (guardInCombat(guard))
                {
                    anyCombat = true;
                }
            }
            if (anyCombat)
            {
                guardsWereInCombat = true;
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 商队领袖等待——护卫卫兵战斗中（ATTACKING）");
                return true;
            }
            if (guardsWereInCombat)
            {
                if (!anyVisible)
                {
                    guardsWereInCombat = false; // 无可见卫兵（死亡/解散）→ 继续。
                    return false;
                }
                for (final AbstractEntityCitizen guard : guards)
                {
                    if (!guard.isInvisible() && guard.distanceToSqr(worker) > 36)
                    {
                        com.example.caravan.CaravanMod.LOGGER.info(
                            "Caravan: 商队领袖等待——护卫卫兵归队中（距离 {}）",
                            (int) Math.sqrt(guard.distanceToSqr(worker)));
                        return true; // 战斗结束，等待卫兵回到 6 格内。
                    }
                }
                guardsWereInCombat = false; // 全部归队 → 继续移动。
            }
            return false;
        }
        catch (final Exception ignored)
        {
            return false;
        }
    }

    /** 战斗判定：卫兵 AI 处于 ATTACKING（原版战斗状态机），或在最近 3 秒内
     *  攻击过/受到攻击（捕捉状态机切换间隙的短暂战斗，避免漏检）。
     *  注意：不能用“威胁表非空”判定——豁免名单怪物会被扫描加入威胁表但不战斗。 */
    private boolean guardInCombat(final AbstractEntityCitizen guard)
    {
        try
        {
            final com.minecolonies.api.entity.ai.ITickingStateAI guardAI = guard.getCitizenJobHandler().getWorkAI();
            if (guardAI != null
                && guardAI.getState() == com.minecolonies.api.entity.ai.combat.CombatAIStates.ATTACKING)
            {
                return true;
            }
            final long now = world.getGameTime();
            return (guard.getLastHurtMob() != null && now - guard.getLastHurtMobTimestamp() < 60)
                || (guard.getLastAttacker() != null && now - guard.getLastHurtByMobTimestamp() < 60);
        }
        catch (final Exception ignored)
        {
            return false;
        }
    }

    /** 需求（商队护卫·bug 修复）：等待卫兵战斗时原地活动——
     *  取消当前寻路目标，改为附近 3 格内的随机点，避免领袖继续沿旧目标移动。 */
    private void waitForGuardsIdle()
    {
        worker.getNavigation().stop();
        final BlockPos waitTarget = worker.blockPosition().offset(
            world.random.nextInt(7) - 3,
            0,
            world.random.nextInt(7) - 3);
        walkToUnSafePos(waitTarget);
    }

    /** 需求（经验）：商队完成交易后结算经验奖励（领袖 + 健康成员）。 */
    private void grantTradeExperience(final int trades)
    {
        final double xp = 1.5D * trades;
        try
        {
            worker.getCitizenExperienceHandler().addExperience(xp);
            for (final AbstractEntityCitizen member : caravanMembers())
            {
                member.getCitizenExperienceHandler().addExperience(xp);
            }
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: [商队{}] 结算交易经验 {} XP（{} 笔交易，领袖 + {} 名成员）",
                caravanIndex(), xp, trades, caravanMembers().size());
        }
        catch (final Exception ignored)
        {
            // 经验结算失败不影响交易流程。
        }
    }

    /**
     * 若消失期间背包被清空则恢复保存的补给，并把交易成果放入背包。
     * 修复bug：如果本次行程已结算过交易（支付了成本），绝不能把消失前保存的
     * 补给整体倒回——那会让“本应消耗掉的交易品”在回程时重新回到物品栏。
     * 只有“背包为空且一笔交易都未结算”时才恢复补给（此时背包为空只可能是
     * 实体被重置导致，恢复补给才安全）。
     */
    private void restoreSuppliesAndResults()
    {
        final InventoryCitizen inventory = worker.getInventoryCitizen();

        boolean empty = true;
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            if (!inventory.getStackInSlot(slot).isEmpty())
            {
                empty = false;
                break;
            }
        }
        final boolean anyTradeSettled = job.getTrip().stream()
            .anyMatch(offer -> offer.status() != TripStatus.PENDING);
        if (empty && !anyTradeSettled)
        {
            for (final ItemStack supply : job.getSupplies())
            {
                insertIntoInventory(inventory, supply);
            }
        }
        for (final ItemStack result : job.getResults())
        {
            // 需求：成果放不下时溢出给商队成员。
            ItemStack remaining = insertIntoInventory(inventory, result);
            for (final AbstractEntityCitizen member : caravanMembers())
            {
                if (remaining.isEmpty())
                {
                    break;
                }
                remaining = insertIntoInventory(member.getInventoryCitizen(), remaining);
            }
        }
    }

    /**
     * 回归：回小屋并把交易成果放入小屋存储；若还有剩余订单且背包有空间，
     * 继续下一轮出行；否则结束本次行程回到待机。
     */
    private IAIState returnFromTrip()
    {
        updateLeaderStatus(CaravanStatus.TRADING);
        // 需求（回归机制）：回归后第 1 个殖民地刻解除领袖隐形（倒计时由回归分支设置）。
        if (revealDelayTicks > 0)
        {
            revealDelayTicks -= STATE_TICK;
            if (revealDelayTicks <= 0)
            {
                worker.setInvisible(false);
                setSimulationInvulnerable(false);
            }
        }
        // 需求（文本显示）：向小屋移动 → 展示“返回中”。
        job.setDisplayPhase(JobCaravanLeader.AwayPhase.RETURNING);
        // 需求（商队护卫）：回归步行阶段同样等待护卫卫兵结束战斗并归队。
        if (guardsBlockMovement())
        {
            waitForGuardsIdle();
            return CaravanState.RETURN;
        }
        if (!walkToBuilding())
        {
            return CaravanState.RETURN;
        }

        // 需求（机制更改）：归来时把领袖与全部成员的【整个物品栏】清空到小屋存储
        // （参考快递员在仓库卸货：逐槽转移，放不下的保留原处），
        // 之后备货阶段不再从物品栏计算需求。
        dumpAllCaravanInventoriesIntoHut();
        // 需求（疾病）：返程后统一结算无帐篷过夜的患病概率（返回患病人数）。
        final int sickCount = settleNightIllness();
        // 需求（通报）：商队回归时通报本次行程统计。
        sendTripSummary(sickCount);
        job.getResults().clear();
        final CaravanTradeModule tradeModule = building.getFirstModuleOccurance(CaravanTradeModule.class);
        // 需求（非递归重构）：每次交易归来后，对“已下放”的主请求重新检查——
        // 有对应【按需】交易则重新接入（创建售出物子请求），否则保持下放。
        if (tradeModule != null)
        {
            tradeModule.recheckReleasedRequestsAfterReturn();
        }
        // 需求（取货保护）：若仍有未完成的按需请求缺口/待送达任务，不创建“取货送仓库”
        // 请求——否则快递员会把小屋里的按需成果取走，导致小屋目标物永远凑不齐。
        if (tradeModule == null || !tradeModule.hasPendingOnDemandRequests())
        {
            try
            {
                building.createPickupRequest(
                    com.minecolonies.api.colony.requestsystem.requestable.deliveryman.AbstractDeliverymanRequestable
                        .getMaxBuildingPriority(false));
            }
            catch (final Exception ignored)
            {
                // 取货请求失败不阻塞回归流程（如无快递员/仓库等）。
            }
        }

        if (job.hasPendingTripTrades() && hasAnyCaravanSpace())
        {
            return CaravanState.DEPART;
        }
        job.finishTrip();
        // 需求（bug 修复）：行程结束，通知模块恢复售出物补建/健康检查。
        final CaravanTradeModule finishModule = building.getFirstModuleOccurance(CaravanTradeModule.class);
        if (finishModule != null)
        {
            finishModule.setTripActive(false);
        }
        return AIWorkerState.IDLE;
    }

    /** 选择下一个订单目标：优先“背包齐备可立即交易”→ 其次“产出被其它订单消耗”的订单 → 最近。 */
    private TripTrade nearestTripTrade()
    {
        final List<TripTrade> pending = job.getTrip().stream()
            .filter(offer -> offer.status() == TripStatus.PENDING)
            .toList();
        if (pending.isEmpty())
        {
            return null;
        }
        final TripTrade satisfiable = pending.stream()
            .filter(this::hasAllCostsInInventory)
            .min(Comparator.comparingDouble(o -> worker.blockPosition().distSqr(o.trade().villagePos())))
            .orElse(null);
        if (satisfiable != null)
        {
            return satisfiable;
        }
        final TripTrade producer = pending.stream()
            .filter(this::isProducerOrder)
            .min(Comparator.comparingDouble(o -> worker.blockPosition().distSqr(o.trade().villagePos())))
            .orElse(null);
        return producer != null ? producer
            : pending.stream()
                .min(Comparator.comparingDouble(o -> worker.blockPosition().distSqr(o.trade().villagePos())))
                .orElse(null);
    }
    private boolean hasAllCostsInInventory(final TripTrade offer)
    {
        for (final ItemStack cost : offer.trade().costs())
        {
            if (!hasInInventory(cost))
            {
                return false;
            }
        }
        return true;
    }

    /**
     * 需求（真实领地）：计算朝向目标、位于殖民地真实边界上的点——
     * 从殖民地中心沿目标方向逐步向外扫描，返回最后一个仍属于殖民地领地的位置。
     * 领地为区块归属集合，边界变化时此处每次按需重新计算。
     */
    private BlockPos borderPointTowards(final BlockPos target)
    {
        final BlockPos center = job.getColony().getCenter();
        final double dx = target.getX() - center.getX();
        final double dz = target.getZ() - center.getZ();
        final double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0)
        {
            return center;
        }
        final double nx = dx / length;
        final double nz = dz / length;
        BlockPos inside = center;
        // 逐步向外扫描（步长 2 格），直到离开殖民地领地为止（上限 512 格）。
        for (int step = 1; step <= 512; step += 2)
        {
            final BlockPos probe = new BlockPos(
                center.getX() + (int) Math.round(nx * step),
                worker.blockPosition().getY(),
                center.getZ() + (int) Math.round(nz * step));
            if (!job.getColony().isCoordInColony(world, probe))
            {
                break;
            }
            inside = probe;
        }
        return inside;
    }

    /** 从背包扣除交易成本（模拟支付）。 */
    private void payCosts(final List<ItemStack> costs)
    {
        // 需求：消失期间先从模拟成果中抵扣（产物被后续交易消耗），
        // 再扣实体背包；避免“已消耗的绿宝石在归来时重新回到背包”。
        if (job.isAway())
        {
            final List<ItemStack> results = job.getResults();
            for (final ItemStack cost : costs)
            {
                int remaining = cost.getCount();
                for (final java.util.Iterator<ItemStack> it = results.iterator(); it.hasNext() && remaining > 0;)
                {
                    final ItemStack stack = it.next();
                    if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, cost))
                    {
                        final int toTake = Math.min(remaining, stack.getCount());
                        stack.shrink(toTake);
                        remaining -= toTake;
                        if (stack.isEmpty())
                        {
                            it.remove();
                        }
                    }
                }
            }
        }
        // 需求：支付时先扣领袖背包，不足部分从商队成员背包扣除。
        final List<InventoryCitizen> inventories = allCaravanInventories();
        for (final ItemStack cost : costs)
        {
            int remaining = cost.getCount();
            for (final InventoryCitizen inventory : inventories)
            {
                for (int slot = 0; slot < inventory.getSlots() && remaining > 0; slot++)
                {
                    final ItemStack stack = inventory.getStackInSlot(slot);
                    if (ItemStackUtils.compareItemStacksIgnoreStackSize(stack, cost))
                    {
                        final int toTake = Math.min(remaining, stack.getCount());
                        stack.shrink(toTake);
                        remaining -= toTake;
                    }
                }
                inventory.markDirty();
            }
        }
    }

    /**
     * 需求（机制更改）：归来时把领袖与全部商队成员的【整个物品栏】清空到小屋存储——
     * 参考 minecolonies 快递员在仓库卸货（逐槽 transferItemStackIntoNextFreeSlotInItemHandler），
     * 小屋放不下的物品保留在原物品栏（不丢失）。
     */
    private void dumpAllCaravanInventoriesIntoHut()
    {
        // 需求2：只有在小屋方块附近才允许向小屋存储中放物。
        if (!canAccessHutStorage())
        {
            return;
        }
        final IItemHandler hutHandler = building.getItemHandlerCap((Direction) null);
        if (hutHandler == null)
        {
            return;
        }

        // 需求（bug 修复）：领袖与所有商队成员物品栏的【全部物品】转入小屋存储，
        // 但【商队帐篷】保留在背包中（帐篷是出行装备，不应被当作交易成果卸下）。
        for (final InventoryCitizen inventory : allCaravanInventories())
        {
            final List<Integer> slots = new ArrayList<>();
            for (int slot = 0; slot < inventory.getSlots(); slot++)
            {
                final ItemStack stack = inventory.getStackInSlot(slot);
                if (!stack.isEmpty()
                    && stack.getItem() != com.example.caravan.CaravanMod.CARAVAN_TENT.get())
                {
                    slots.add(slot);
                }
            }
            // 从高槽位开始转移，避免低槽位索引失效。
            Collections.reverse(slots);
            for (final int slot : slots)
            {
                InventoryUtils.transferItemStackIntoNextFreeSlotInItemHandler(inventory, slot, hutHandler);
            }
            inventory.markDirty();
        }
    }

    /** 把物品放入指定物品栏，返回未能放下的剩余部分（空栈 = 全部放入）。 */
    private static ItemStack insertIntoInventory(final InventoryCitizen inventory, final ItemStack stack)
    {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < inventory.getSlots() && !remaining.isEmpty(); slot++)
        {
            remaining = inventory.insertItem(slot, remaining, false);
        }
        return remaining;
    }
}
