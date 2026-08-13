package com.example.caravan.entity.ai;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.jobs.JobCaravanGuard;
import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IBooleanConditionSupplier;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IStateSupplier;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.colony.buildings.modules.EntityListModule;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 商队卫兵 AI（需求：守卫-跟随-索敌战斗，参照 Minecolonies 本体骑士/卫兵塔 AI）。
 * <ul>
 *   <li>商队未出发：驻守商队小屋（参照本体【驻守】模式，目标=小屋方块）；</li>
 *   <li>商队出发（领袖未消失）：跟随领袖（参照商队成员 AI：6 格跟随、备货时小屋待命），
 *       并主动攻击范围内【敌对】列表中的生物（参照卫兵索敌 AI）；</li>
 *   <li>领袖消失：停止战斗并寻路到领袖消失位置，到达后与领袖一同消失/现身
 *       （参照商队成员 AI）；</li>
 *   <li>装备处理：请求送达的武器/护甲自动穿戴（背包 → 主手/护甲槽），
 *       解决“卫兵不领取装备”问题。</li>
 * </ul>
 */
public class EntityAIWorkCaravanGuard extends AbstractEntityAIBasic<JobCaravanGuard, BuildingCaravanLeader>
{
    /** 跟随领袖的最大距离（格）。 */
    private static final int FOLLOW_DISTANCE = 6;
    /** 小屋附近待命范围平方。 */
    private static final int HUT_RANGE_SQUARED = 100;
    /** 到达领袖消失位置（视为已消失）的判定距离平方。 */
    private static final int VANISH_RANGE_SQUARED = 9;
    /** 索敌范围（格）。 */
    private static final int TARGET_RANGE = 16;
    /** 近战攻击距离平方（3 格）。 */
    private static final int ATTACK_RANGE_SQUARED = 9;
    /** 丢失目标的距离平方（20 格）。 */
    private static final int TARGET_LOSE_RANGE_SQUARED = 400;
    /** 攻击冷却（游戏刻）。 */
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    /** 索敌扫描间隔（游戏刻）。 */
    private static final int SCAN_INTERVAL_TICKS = 40;
    /** 需求（游荡）：小屋范围内游荡的半径（格）。 */
    private static final int WANDER_RADIUS = 5;

    /** 商队卫兵自定义状态。 */
    private enum GuardState implements IAIState
    {
        /** 驻守商队小屋（商队未出发）。 */
        GUARD,
        /** 跟随商队领袖（商队出发）。 */
        FOLLOW_LEADER,
        /** 领袖已消失：走到领袖的消失位置。 */
        VANISH_PREP,
        /** 与领袖一同消失（隐形，等待领袖归来）。 */
        VANISHED;

        @Override
        public boolean isOkayToEat()
        {
            // 消失期间不允许进食（隐形状态）。
            return this != VANISHED;
        }
    }

    /** 是否已与领袖一同消失（隐形）。 */
    private boolean memberVanished;
    /** 需求（游荡）：游荡计时器。 */
    private int wanderTimer;
    /** 攻击冷却计时器。 */
    private int attackCooldown;
    /** 索敌扫描计时器。 */
    private int scanCooldown;
    /** 当前攻击目标。 */
    private LivingEntity target;

    public EntityAIWorkCaravanGuard(final JobCaravanGuard job)
    {
        super(job);

        super.registerTargets(
            new AITarget<IAIState>(AIWorkerState.IDLE,
                (IBooleanConditionSupplier) this::shouldActivate,
                (IStateSupplier<IAIState>) () -> isLeaderAway()
                    ? GuardState.VANISH_PREP
                    : (isCaravanTravelling() ? GuardState.FOLLOW_LEADER : GuardState.GUARD), 20),
            new AITarget<IAIState>(GuardState.GUARD,
                (IStateSupplier<IAIState>) this::guard, 20),
            new AITarget<IAIState>(GuardState.FOLLOW_LEADER,
                (IStateSupplier<IAIState>) this::followLeader, 20),
            new AITarget<IAIState>(GuardState.VANISH_PREP,
                (IStateSupplier<IAIState>) this::vanishPrep, 20),
            new AITarget<IAIState>(GuardState.VANISHED,
                (IStateSupplier<IAIState>) this::vanished, 20));

        // 实体重置/重载后若领袖仍在消失状态，卫兵保持隐形（与领袖一同消失）。
        if (isLeaderAway())
        {
            memberVanished = true;
            worker.setInvisible(true);
        }
    }

    @Override
    public Class<BuildingCaravanLeader> getExpectedBuildingClass()
    {
        return BuildingCaravanLeader.class;
    }

    /** 空闲时进入对应状态；需求（疾病）：生病的卫兵不参与（由本体 AI 管理）。 */
    private boolean shouldActivate()
    {
        final ICitizenData data = worker.getCitizenData();
        return data == null || !data.getCitizenDiseaseHandler().isSick();
    }

    /** 商队是否正在出发（领袖未消失且处于交易行程状态）。 */
    private boolean isCaravanTravelling()
    {
        final JobCaravanLeader leaderJob = findLeaderJob();
        return leaderJob != null
            && !leaderJob.isAway()
            && leaderJob.getStatus() == JobCaravanLeader.CaravanStatus.TRADING;
    }

    /** 驻守：目标=小屋方块；商队出发 → 跟随；同时处理装备与索敌战斗。 */
    private IAIState guard()
    {
        handleEquipment();
        handleCombat();
        if (isLeaderAway())
        {
            return GuardState.VANISH_PREP;
        }
        if (isCaravanTravelling())
        {
            return GuardState.FOLLOW_LEADER;
        }
        if (worker.blockPosition().distSqr(building.getPosition()) > HUT_RANGE_SQUARED)
        {
            walkToBuilding();
        }
        else
        {
            wanderNearHut();
        }
        return GuardState.GUARD;
    }

    /** 跟随：参照商队成员 AI（备货小屋待命、6 格跟随、领袖消失→消失寻路）+ 索敌战斗。 */
    private IAIState followLeader()
    {
        handleEquipment();
        handleCombat();
        final AbstractEntityCitizen leader = findLeader();
        if (isLeaderAway() && (leader == null || leader.isInvisible()))
        {
            return GuardState.VANISH_PREP;
        }
        if (leader == null)
        {
            return AIWorkerState.IDLE;
        }
        // 商队回到备货/待命：与领袖一样回小屋驻守。
        if (!isCaravanTravelling())
        {
            return GuardState.GUARD;
        }
        if (worker.blockPosition().distSqr(leader.blockPosition()) > FOLLOW_DISTANCE * FOLLOW_DISTANCE)
        {
            walkToUnSafePos(leader.blockPosition());
            return GuardState.FOLLOW_LEADER;
        }
        return AIWorkerState.IDLE;
    }

    /** 需求：寻路到领袖消失位置，到达后隐形（与领袖一同消失）。 */
    private IAIState vanishPrep()
    {
        final AbstractEntityCitizen leader = findLeader();
        if (leader == null || !isLeaderAway() || !leader.isInvisible())
        {
            return AIWorkerState.IDLE;
        }
        if (worker.blockPosition().distSqr(leader.blockPosition()) > VANISH_RANGE_SQUARED)
        {
            walkToUnSafePos(leader.blockPosition());
            return GuardState.VANISH_PREP;
        }
        // 到达领袖消失位置：停止战斗并一同消失。
        target = null;
        worker.getNavigation().stop();
        worker.setInvisible(true);
        memberVanished = true;
        return GuardState.VANISHED;
    }

    /** 需求：消失状态——领袖归来时一同现身，否则保持隐形。 */
    private IAIState vanished()
    {
        final AbstractEntityCitizen leader = findLeader();
        if (!isLeaderAway() || (leader != null && !leader.isInvisible()))
        {
            memberVanished = false;
            worker.setInvisible(false);
            return AIWorkerState.IDLE;
        }
        return GuardState.VANISHED;
    }

    /**
     * 需求（装备领取）：公民请求的装备由快递员送达【小屋存储】（本体机制：
     * CitizenData.createRequest 内部即 work building 请求），因此这里先
     * 从小屋存储领取武器/护甲（本体 checkForToolOrWeapon 同款转移），
     * 再把背包中的武器/护甲自动穿戴——
     * 护甲放入对应装备槽，武器（任意可作武器的物品）放入主手。
     */
    private void handleEquipment()
    {
        final InventoryCitizen inventory = worker.getInventoryCitizen();
        // 1. 从小屋存储领取武器/护甲（送达小屋存储的公民请求装备）。
        final IItemHandler hut = building.getItemHandlerCap((Direction) null);
        if (hut != null)
        {
            for (int slot = 0; slot < hut.getSlots(); slot++)
            {
                final ItemStack stack = hut.getStackInSlot(slot);
                if (stack.isEmpty())
                {
                    continue;
                }
                if (stack.getItem() instanceof ArmorItem
                    || ItemStackUtils.doesItemServeAsWeapon(stack))
                {
                    InventoryUtils.transferItemStackIntoNextFreeSlotInItemHandler(hut, slot, inventory);
                }
            }
        }
        // 2. 背包 → 穿戴。
        for (int slot = 0; slot < inventory.getSlots(); slot++)
        {
            final ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty())
            {
                continue;
            }
            if (stack.getItem() instanceof ArmorItem armor)
            {
                final EquipmentSlot armorSlot = armor.getEquipmentSlot();
                if (inventory.getArmorInSlot(armorSlot).isEmpty())
                {
                    inventory.transferArmorToSlot(armorSlot, slot);
                }
            }
            else if (ItemStackUtils.doesItemServeAsWeapon(stack))
            {
                if (worker.getMainHandItem().isEmpty())
                {
                    worker.setItemSlot(EquipmentSlot.MAINHAND, inventory.extractItem(slot, 1, false));
                }
            }
        }
    }

    /** 索敌 + 近战攻击（参照卫兵 AI：范围内【敌对】列表/敌对生物）。 */
    private void handleCombat()
    {
        if (attackCooldown > 0)
        {
            attackCooldown -= 20;
        }
        if (target != null
            && (!target.isAlive()
                || worker.distanceToSqr(target) > TARGET_LOSE_RANGE_SQUARED))
        {
            target = null;
        }
        if (target == null)
        {
            scanCooldown -= 20;
            if (scanCooldown <= 0)
            {
                scanCooldown = SCAN_INTERVAL_TICKS;
                target = scanTarget();
            }
        }
        if (target == null)
        {
            return;
        }
        if (worker.distanceToSqr(target) > ATTACK_RANGE_SQUARED)
        {
            if (worker.distanceToSqr(target) > FOLLOW_DISTANCE * FOLLOW_DISTANCE
                || !worker.getNavigation().isInProgress())
            {
                walkToUnSafePos(target.blockPosition());
            }
        }
        else if (attackCooldown <= 0)
        {
            worker.swing(InteractionHand.MAIN_HAND);
            worker.doHurtTarget(target);
            attackCooldown = ATTACK_COOLDOWN_TICKS;
        }
    }

    /** 扫描范围内的敌对目标：优先【敌对】选项卡列表，其次 MC 敌对生物（Enemy）。 */
    private LivingEntity scanTarget()
    {
        LivingEntity best = null;
        double bestDist = (double) TARGET_RANGE * TARGET_RANGE;
        final AABB box = worker.getBoundingBox().inflate(TARGET_RANGE);
        for (final LivingEntity entity : world.getEntitiesOfClass(
            LivingEntity.class, box, e -> e != worker && isEnemy(e)))
        {
            final double dist = worker.distanceToSqr(entity);
            if (dist < bestDist)
            {
                bestDist = dist;
                best = entity;
            }
        }
        return best;
    }

    /** 敌对判定：【敌对】选项卡列表 + MC 敌对生物接口。 */
    private boolean isEnemy(final LivingEntity entity)
    {
        final var entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        for (final EntityListModule list : building.getModulesByType(EntityListModule.class))
        {
            if (list.isEntityInList(entityId))
            {
                return true;
            }
        }
        return entity instanceof Enemy;
    }

    /** 需求：小屋范围内随机游荡（保持在小屋附近）。 */
    private void wanderNearHut()
    {
        if ((wanderTimer += 20) < 100)
        {
            return;
        }
        wanderTimer = 0;
        final BlockPos targetPos = building.getPosition().offset(
            world.random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS,
            0,
            world.random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS);
        walkToUnSafePos(targetPos);
    }

    /** 商队领袖是否处于消失（去程/回程）状态。 */
    private boolean isLeaderAway()
    {
        final JobCaravanLeader leaderJob = findLeaderJob();
        return leaderJob != null && leaderJob.isAway();
    }

    /** 从商队小屋的工作模块中查找商队领袖的市民数据。 */
    private JobCaravanLeader findLeaderJob()
    {
        for (final WorkerBuildingModule module : building.getModulesByType(WorkerBuildingModule.class))
        {
            if (module.getJobEntry().getKey().equals(CaravanMod.JOB_CARAVAN_LEADER.getKey()))
            {
                for (final ICitizenData data : module.getAssignedCitizen())
                {
                    if (data.getJob() instanceof JobCaravanLeader leaderJob)
                    {
                        return leaderJob;
                    }
                }
            }
        }
        return null;
    }

    /** 商队领袖的实体（可能未加载，返回 null）。 */
    private AbstractEntityCitizen findLeader()
    {
        final JobCaravanLeader leaderJob = findLeaderJob();
        return leaderJob != null
            ? leaderJob.getCitizen().getEntity().orElse(null)
            : null;
    }
}
