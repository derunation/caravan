package com.example.caravan.colony.buildings;

import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import net.minecraft.core.BlockPos;

/** 需求（商队护卫）：商队小屋与卫兵 AI（mixin）共用的护卫状态查询工具。 */
public final class CaravanGuardHelper
{
    /** 卫兵塔【工作模式】新增的“商队护卫”选项键（GuardTaskSettingMixin 追加）。 */
    public static final String CARAVAN_TASK_KEY = "com.caravan.guard.setting.caravan";

    private CaravanGuardHelper()
    {
    }

    /** 殖民地中的商队小屋（无则 null）。 */
    public static BuildingCaravanLeader findCaravanHut(final IColony colony)
    {
        if (colony == null || colony.getServerBuildingManager() == null)
        {
            return null;
        }
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (building instanceof BuildingCaravanLeader hut)
            {
                return hut;
            }
        }
        return null;
    }

    /** 商队领袖是否处于消失（去程/回程）状态。 */
    public static boolean isLeaderAway(final BuildingCaravanLeader hut)
    {
        final JobCaravanLeader leaderJob = findLeaderJob(hut);
        return leaderJob != null && leaderJob.isAway();
    }

    /** 商队领袖实体位置（未加载时回退其家庭位置）。 */
    public static BlockPos leaderPosition(final BuildingCaravanLeader hut)
    {
        final JobCaravanLeader leaderJob = findLeaderJob(hut);
        if (leaderJob == null)
        {
            return hut != null ? hut.getPosition() : null;
        }
        return leaderJob.getCitizen().getEntity()
            .map(entity -> entity.blockPosition())
            .orElse(leaderJob.getCitizen().getHomePosition());
    }

    /** 从商队小屋工作模块中查找商队领袖的 job。 */
    private static JobCaravanLeader findLeaderJob(final BuildingCaravanLeader hut)
    {
        if (hut == null)
        {
            return null;
        }
        for (final WorkerBuildingModule module : hut.getModulesByType(WorkerBuildingModule.class))
        {
            if (module.getJobEntry().getKey().equals(
                com.example.caravan.CaravanMod.JOB_CARAVAN_LEADER.getKey()))
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
}
