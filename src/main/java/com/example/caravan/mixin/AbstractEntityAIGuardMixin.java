package com.example.caravan.mixin;

import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.CaravanGuardHelper;
import com.example.caravan.colony.buildings.modules.CaravanGuardModule;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IGuardBuilding;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.entity.ai.workers.guard.AbstractEntityAIGuard;
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
    @Shadow(remap = false)
    protected IGuardBuilding buildingGuards;

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
        if (CaravanGuardHelper.isLeaderAway(hut))
        {
            // 领袖消失（模拟旅行）：跟随到领袖位置。
            cir.setReturnValue(follow());
        }
        else
        {
            // 商队未出发：驻守商队小屋。
            cir.setReturnValue(guard());
        }
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
