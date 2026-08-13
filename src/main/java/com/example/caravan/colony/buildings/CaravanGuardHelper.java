package com.example.caravan.colony.buildings;

import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.IGuardBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

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

    /** 商队是否正在出行（未进入模拟旅行，领袖在场且处于行程状态）。 */
    public static boolean isCaravanTravelling(final BuildingCaravanLeader hut)
    {
        final JobCaravanLeader leaderJob = findLeaderJob(hut);
        return leaderJob != null
            && !leaderJob.isAway()
            && leaderJob.getStatus() == JobCaravanLeader.CaravanStatus.TRADING;
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

    /** 商队领袖实体（可能未加载，返回 null）。 */
    public static AbstractEntityCitizen leaderEntity(final BuildingCaravanLeader hut)
    {
        final JobCaravanLeader leaderJob = findLeaderJob(hut);
        return leaderJob != null
            ? leaderJob.getCitizen().getEntity().orElse(null)
            : null;
    }

    /** 该建筑是否为“商队护卫”工作模式的卫兵塔。 */
    public static boolean isCaravanTower(final IBuilding building)
    {
        return building instanceof AbstractBuildingGuards
            && CARAVAN_TASK_KEY.equals(((IGuardBuilding) building).getTask());
    }

    /** 该卫兵塔是否已被商队小屋【护卫】页选中（塔级指派）。 */
    public static boolean isTowerAssigned(final IBuilding tower, final BuildingCaravanLeader hut)
    {
        if (hut == null || tower == null)
        {
            return false;
        }
        final com.example.caravan.colony.buildings.modules.CaravanGuardModule module =
            hut.getFirstModuleOccurance(com.example.caravan.colony.buildings.modules.CaravanGuardModule.class);
        return module != null && module.isTowerAssigned(tower.getPosition());
    }

    /** 该卫兵（市民）是否处于“商队护卫”模式且其卫兵塔已被选中（否则返回 null）。 */
    public static BuildingCaravanLeader caravanHutForGuard(final AbstractEntityCitizen worker)
    {
        try
        {
            final ICitizenData data = worker != null ? worker.getCitizenData() : null;
            final IBuilding tower = data != null ? data.getWorkBuilding() : null;
            if (!isCaravanTower(tower))
            {
                return null;
            }
            final IColony colony = data.getColony();
            final BuildingCaravanLeader hut = findCaravanHut(colony);
            if (hut == null || !isTowerAssigned(tower, hut))
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

    /** 殖民地中所有“商队护卫”模式且其卫兵塔已被选中的卫兵实体（未加载的跳过）。 */
    public static List<AbstractEntityCitizen> caravanGuardCitizens(final IColony colony)
    {
        final List<AbstractEntityCitizen> result = new ArrayList<>();
        if (colony == null || colony.getServerBuildingManager() == null)
        {
            return result;
        }
        final BuildingCaravanLeader hut = findCaravanHut(colony);
        if (hut == null)
        {
            return result;
        }
        for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
        {
            if (!isCaravanTower(building) || !isTowerAssigned(building, hut))
            {
                continue;
            }
            for (final WorkerBuildingModule module : building.getModulesByType(WorkerBuildingModule.class))
            {
                for (final ICitizenData data : module.getAssignedCitizen())
                {
                    data.getEntity().ifPresent(result::add);
                }
            }
        }
        return result;
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
