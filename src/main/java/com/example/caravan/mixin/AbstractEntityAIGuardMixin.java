package com.example.caravan.mixin;

import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.CaravanGuardHelper;
import com.example.caravan.colony.buildings.modules.CaravanGuardModule;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IGuardBuilding;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.equipment.registry.EquipmentTypeEntry;
import com.minecolonies.api.util.InventoryUtils;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.Tool;
import com.minecolonies.core.colony.CitizenData;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIFight;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
import com.google.common.collect.ImmutableList;
import com.google.common.reflect.TypeToken;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 需求（商队护卫）：卫兵塔卫兵 AI 的工作模式扩展——
 * 当卫兵塔【工作模式】=【商队护卫】且已被商队小屋【护卫】页选中时：
 * <ul>
 *   <li>卫兵塔被商队小屋【护卫】页选中（塔级指派）且领袖消失（去程/回程）
 *       → 跟随到领袖位置（复用 follow()，目标改为领袖）；</li>
 *   <li>商队未出发 → 驻守商队小屋（复用 guard()，驻守点改为商队小屋）；</li>
 *   <li>索敌战斗沿用卫兵自身框架。</li>
 * </ul>
 */
@Mixin(AbstractEntityAIGuard.class)
public abstract class AbstractEntityAIGuardMixin
{
    /** 需求（诊断）：商队护卫模式诊断是否已输出过（防刷屏）。 */
    private static boolean caravan$diagnosed;
    /** 需求（诊断）：最近一次输出跟随/驻守诊断的时刻（节流 200 刻）。 */
    private static long caravan$lastActionLog;
    /** 需求（武器请求）：当前 AI 对应的卫兵实体（由 redirect 处理器缓存，避免
     *  @Shadow 父类字段导致 mixin 崩溃）。 */
    private AbstractEntityCitizen caravan$worker;

    @Shadow(remap = false)
    protected IGuardBuilding buildingGuards;

    @Shadow(remap = false)
    public boolean hasTool()
    {
        throw new AbstractMethodError();
    }

    @Shadow
    private IAIState guard()
    {
        throw new AbstractMethodError();
    }

    @Shadow
    private IAIState follow()
    {
        throw new AbstractMethodError();
    }

    /** 商队护卫模式且已指派：按商队状态返回 跟随/驻守；否则不干预。 */
    @Inject(method = "decide", at = @At("HEAD"), cancellable = true, remap = false)
    private void caravan$decide(final CallbackInfoReturnable<IAIState> cir)
    {
        // 需求（诊断）：工作模式为【商队护卫】时输出一次判定信息（是否被商队小屋选中）。
        if (!caravan$diagnosed
            && CaravanGuardHelper.CARAVAN_TASK_KEY.equals(buildingGuards.getTask()))
        {
            caravan$diagnosed = true;
            final BuildingCaravanLeader diagHut = activeHut();
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 卫兵塔 {} 为【商队护卫】模式（被选中={}，away={}，出行中={}）",
                buildingGuards.getPosition(),
                diagHut != null,
                diagHut != null && CaravanGuardHelper.isLeaderAway(diagHut),
                diagHut != null && CaravanGuardHelper.isCaravanTravelling(diagHut));
        }
        final BuildingCaravanLeader hut = activeHut();
        if (hut == null)
        {
            return;
        }
        // 需求（bug 修复）：我们在此处提前返回，绕过了原版 decide() 的 5% 概率
        // equipInventoryArmor()——补上该调用，确保护甲/武器能被穿上。
        try
        {
            if (buildingGuards.getColony().getWorld().random.nextDouble() < 0.05)
            {
                ((AbstractEntityAIFight<?, ?>) (Object) this).equipInventoryArmor();
            }
        }
        catch (final Exception ignored)
        {
            // 装备穿戴失败不影响逻辑。
        }
        if (CaravanGuardHelper.isLeaderAway(hut) || CaravanGuardHelper.isCaravanTravelling(hut))
        {
            // 领袖消失（模拟旅行）或出行未模拟：跟随到领袖位置。
            long time = 0;
            try
            {
                time = buildingGuards.getColony().getWorld().getGameTime();
            }
            catch (final Exception ignored)
            {
                // 诊断失败不影响逻辑。
            }
            if (time - caravan$lastActionLog >= 200)
            {
                caravan$lastActionLog = time;
                final BlockPos leaderPos = CaravanGuardHelper.leaderPosition(hut);
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 卫兵塔 {} 跟随（away={}，出行中={}，距领袖 {}）",
                    buildingGuards.getPosition(),
                    CaravanGuardHelper.isLeaderAway(hut),
                    CaravanGuardHelper.isCaravanTravelling(hut),
                    leaderPos != null
                        ? (int) Math.sqrt(buildingGuards.getPosition().distSqr(leaderPos))
                        : -1);
            }
            cir.setReturnValue(follow());
        }
        else
        {
            // 商队未出发：驻守商队小屋。
            long time = 0;
            try
            {
                time = buildingGuards.getColony().getWorld().getGameTime();
            }
            catch (final Exception ignored)
            {
                // 诊断失败不影响逻辑。
            }
            if (time - caravan$lastActionLog >= 200)
            {
                caravan$lastActionLog = time;
                com.example.caravan.CaravanMod.LOGGER.info(
                    "Caravan: 卫兵塔 {} 驻守商队小屋", buildingGuards.getPosition());
            }
            cir.setReturnValue(guard());
        }
    }

    /** 需求（bug 修复）：商队护卫不随机睡觉——否则跟随/驻守途中入睡会
     *  卡住 2~3 分钟（睡眠期间不索敌、不跟随），表现为“跟随中无法索敌”。 */
    @Inject(method = "shouldSleep", at = @At("HEAD"), cancellable = true, remap = false)
    private void caravan$shouldSleep(final CallbackInfoReturnable<Boolean> cir)
    {
        if (activeHut() != null)
        {
            cir.setReturnValue(false);
        }
    }

    /** 需求（bug 修复）：商队模式下缺少武器时不再进入 PREPARING 卡死——
     *  保持 ATTACKING 追击敌人，同时以异步方式请求缺失武器
     *  （公民请求，送达公民背包后 KnightCombatAI.canAttack 即可生效）。 */
    @Inject(method = "inCombat", at = @At("HEAD"), cancellable = true, remap = false)
    private void caravan$inCombat(final CallbackInfoReturnable<IAIState> cir)
    {
        final BuildingCaravanLeader hut = activeHut();
        if (hut == null || hasTool())
        {
            return;
        }
        requestMissingWeaponsAsync();
        cir.setReturnValue(null);
    }

    /** 需求（bug 修复）：以卫兵自身位置作为“追击参考点”——
     *  否则追击范围以卫兵塔为圆心，卫兵跟随商队远离塔后无法索敌
     *  （KnightCombatAI.isWithinPersecutionDistance 会拒绝所有塔外的敌人）。 */
    @Redirect(
        method = "getTaskReferencePoint",
        at = @At(
            value = "INVOKE",
            target = "Lcom/minecolonies/api/colony/buildings/IGuardBuilding;getGuardPos(Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;)Lnet/minecraft/core/BlockPos;"),
        remap = false)
    private BlockPos caravan$taskReferencePoint(final IGuardBuilding guardBuilding, final AbstractEntityCitizen citizen)
    {
        caravan$worker = citizen;
        final BuildingCaravanLeader hut = activeHut();
        if (hut != null)
        {
            return citizen.blockPosition();
        }
        return guardBuilding.getGuardPos(citizen);
    }

    /** 商队模式下缺失武器的异步请求（按 toolsNeeded 逐项请求，已有打开请求自动复用）。 */
    private void requestMissingWeaponsAsync()
    {
        final AbstractEntityCitizen citizen = caravan$worker != null ? caravan$worker : findGuardCitizen();
        if (citizen == null || !(citizen.getCitizenData() instanceof CitizenData data))
        {
            return;
        }
        try
        {
            final int maxLevel = buildingGuards.getMaxEquipmentLevel();
            for (final EquipmentTypeEntry tool : ((AbstractEntityAIFight<?, ?>) (Object) this).toolsNeeded)
            {
                if (!InventoryUtils.hasItemHandlerEquipmentWithLevel(
                    citizen.getInventoryCitizen(), tool, 0, maxLevel))
                {
                    final ImmutableList<IRequest<? extends Tool>> open = buildingGuards.getOpenRequestsOfTypeFiltered(
                        citizen.getCitizenData(), TypeToken.of(Tool.class),
                        r -> r.getRequest().getEquipmentType().equals(tool) && r.getRequest().getMinLevel() >= 0);
                    if (open.isEmpty())
                    {
                        data.createRequestAsync(new Tool(tool, 0, maxLevel));
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 请求失败不影响逻辑。
        }
    }

    /** 从卫兵塔工作模块中查找当前卫兵的市民数据（缓存缺失时的兜底）。 */
    private AbstractEntityCitizen findGuardCitizen()
    {
        try
        {
            for (final com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule module :
                buildingGuards.getModulesByType(com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule.class))
            {
                for (final ICitizenData assigned : module.getAssignedCitizen())
                {
                    if (assigned.getJob() instanceof com.minecolonies.core.colony.jobs.AbstractJobGuard)
                    {
                        return assigned.getEntity().orElse(null);
                    }
                }
            }
        }
        catch (final Exception ignored)
        {
            // 查找失败不影响逻辑。
        }
        return null;
    }

    /** 驻守点改为商队小屋。 */
    @Redirect(
        method = "guardMovement",
        at = @At(
            value = "INVOKE",
            target = "Lcom/minecolonies/api/colony/buildings/IGuardBuilding;getGuardPos(Lcom/minecolonies/api/entity/citizen/AbstractEntityCitizen;)Lnet/minecraft/core/BlockPos;"),
        remap = false)
    private BlockPos caravan$guardPos(final IGuardBuilding guardBuilding, final AbstractEntityCitizen citizen)
    {
        caravan$worker = citizen;
        final BuildingCaravanLeader hut = activeHut();
        if (hut != null)
        {
            return hut.getPosition();
        }
        return guardBuilding.getGuardPos(citizen);
    }

    /** 跟随目标改为商队领袖位置。 */
    @Redirect(
        method = "follow",
        at = @At(
            value = "INVOKE",
            target = "Lcom/minecolonies/api/colony/buildings/IGuardBuilding;getPositionToFollow()Lnet/minecraft/core/BlockPos;"),
        remap = false)
    private BlockPos caravan$followPos(final IGuardBuilding guardBuilding)
    {
        final BuildingCaravanLeader hut = activeHut();
        if (hut != null)
        {
            final BlockPos leaderPos = CaravanGuardHelper.leaderPosition(hut);
            if (leaderPos != null)
            {
                return leaderPos;
            }
        }
        return guardBuilding.getPositionToFollow();
    }

    /** 当前卫兵是否为“商队护卫”模式且已被商队小屋选中（否则返回 null）。 */
    private BuildingCaravanLeader activeHut()
    {
        try
        {
            if (!CaravanGuardHelper.CARAVAN_TASK_KEY.equals(buildingGuards.getTask()))
            {
                return null;
            }
            final IColony colony = buildingGuards.getColony();
            final BuildingCaravanLeader hut = CaravanGuardHelper.findCaravanHut(colony);
            if (hut == null)
            {
                return null;
            }
            final CaravanGuardModule module = hut.getFirstModuleOccurance(CaravanGuardModule.class);
            if (module == null || !module.isTowerAssigned(buildingGuards.getPosition()))
            {
                return null;
            }
            return hut;
        }
        catch (final Exception ignored)
        {
            return null;
        }
    }
}
