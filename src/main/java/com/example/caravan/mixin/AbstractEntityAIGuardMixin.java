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

@Mixin(AbstractEntityAIGuard.class)
public abstract class AbstractEntityAIGuardMixin
{
    /** 当前 AI 对应的卫兵实体（由 redirect 处理器缓存）。 */
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
        final BuildingCaravanLeader hut = activeHut();
        if (hut == null)
        {
            return;
        }
        // decide() 提前返回时绕过原版的 5% 概率 equipInventoryArmor()，此处补上。
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
            cir.setReturnValue(follow());
        }
        else
        {
            // 商队未出发：驻守商队小屋。
            cir.setReturnValue(guard());
        }
    }

    /** 商队护卫不随机睡觉——睡眠期间不索敌、不跟随。 */
    @Inject(method = "shouldSleep", at = @At("HEAD"), cancellable = true, remap = false)
    private void caravan$shouldSleep(final CallbackInfoReturnable<Boolean> cir)
    {
        if (activeHut() != null)
        {
            cir.setReturnValue(false);
        }
    }

    /** 商队模式下缺少武器时不再进入 PREPARING 卡死，异步请求缺失武器。 */
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

    /** 以卫兵自身位置作为追击参考点——否则远离卫兵塔后无法索敌。 */
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
