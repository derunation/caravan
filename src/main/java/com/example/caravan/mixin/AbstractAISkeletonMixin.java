package com.example.caravan.mixin;

import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.CaravanGuardHelper;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.entity.ai.workers.AbstractAISkeleton;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 需求（商队护卫·消失 AI）：卫兵 AI 的 tick 扩展——
 * 商队领袖处于模拟旅行（消失）时：
 * <ul>
 *   <li>卫兵未到达消失点 → 由 decide/follow 寻路到领袖位置；</li>
 *   <li>到达领袖消失位置附近 → 一同隐形（消失状态）；</li>
 *   <li>消失期间冻结 job AI（不索敌、不觅食、不移动）——本 mod 接管；</li>
 *   <li>商队模拟结束 → 解除隐形，恢复 guard/follow。</li>
 * </ul>
 */
@Mixin(AbstractAISkeleton.class)
public abstract class AbstractAISkeletonMixin
{
    @Shadow(remap = false)
    protected AbstractEntityCitizen worker;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void caravan$guardTick(final CallbackInfo ci)
    {
        // 仅处理卫兵（骑士/弓箭手等卫兵职业）。
        final ICitizenData data = worker != null ? worker.getCitizenData() : null;
        if (data == null || !(data.getJob() instanceof AbstractJobGuard))
        {
            return;
        }
        final BuildingCaravanLeader hut = CaravanGuardHelper.caravanHutForGuard(worker);
        if (hut == null)
        {
            return;
        }
        if (CaravanGuardHelper.isLeaderAway(hut))
        {
            if (worker.isInvisible())
            {
                // 已消失：冻结 AI（不索敌、不觅食、不移动）。
                ci.cancel();
                return;
            }
            // 到达领袖消失位置附近（3 格）→ 一同消失（隐形）。
            final BlockPos leaderPos = CaravanGuardHelper.leaderPosition(hut);
            if (leaderPos != null && worker.blockPosition().distSqr(leaderPos) <= 9)
            {
                worker.setInvisible(true);
                ci.cancel();
            }
            // 未到达：由 decide→follow 继续寻路到领袖位置。
        }
        else if (worker.isInvisible())
        {
            // 商队模拟结束：解除消失，恢复驻守/跟随。
            worker.setInvisible(false);
        }
    }
}
