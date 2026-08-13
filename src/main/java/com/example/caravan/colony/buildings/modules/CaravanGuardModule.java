package com.example.caravan.colony.buildings.modules;

import com.example.caravan.colony.buildings.CaravanGuardHelper;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.IGuardBuilding;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.core.colony.buildings.AbstractBuildingGuards;
import com.minecolonies.core.colony.buildings.modules.GuardBuildingModule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.HashSet;
import java.util.Set;

/**
 * 需求（商队护卫）：商队小屋【护卫】页的服务端数据源——
 * 持久化“已被选中开始护卫商队”的卫兵 ID 列表；同步殖民地所有
 * 【商队护卫】工作模式的卫兵（ID/名字/是否已指派）给客户端。
 */
public class CaravanGuardModule extends AbstractBuildingModule implements IPersistentModule
{
    private static final String TAG_ASSIGNED = "assignedGuards";

    /** 已选中护卫商队的卫兵市民 ID。 */
    private final Set<Integer> guardCitizenIds = new HashSet<>();

    /** 该卫兵是否已被选中护卫商队。 */
    public boolean isGuardAssigned(final int citizenId)
    {
        return guardCitizenIds.contains(citizenId);
    }

    /** 设置/取消该卫兵的商队护卫指派。 */
    public void setGuardAssigned(final int citizenId, final boolean assigned)
    {
        final boolean changed = assigned ? guardCitizenIds.add(citizenId) : guardCitizenIds.remove(citizenId);
        if (changed)
        {
            getBuilding().markDirty();
        }
    }

    @Override
    public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag compound)
    {
        guardCitizenIds.clear();
        final ListTag list = compound.getList(TAG_ASSIGNED, Tag.TAG_INT);
        for (int i = 0; i < list.size(); i++)
        {
            guardCitizenIds.add(list.getInt(i));
        }
    }

    @Override
    public void serializeNBT(final HolderLookup.Provider provider, final CompoundTag compound)
    {
        final ListTag list = new ListTag();
        for (final int id : guardCitizenIds)
        {
            list.add(net.minecraft.nbt.IntTag.valueOf(id));
        }
        compound.put(TAG_ASSIGNED, list);
    }

    /** 同步：殖民地所有【商队护卫】模式卫兵（ID/名字/是否已指派）。 */
    @Override
    public void serializeToView(final RegistryFriendlyByteBuf buffer)
    {
        final IColony colony = getBuilding().getColony();
        int count = 0;
        if (colony != null && colony.getServerBuildingManager() != null)
        {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            {
                if (building instanceof AbstractBuildingGuards guards
                    && CaravanGuardHelper.CARAVAN_TASK_KEY.equals(((IGuardBuilding) guards).getTask()))
                {
                    for (final GuardBuildingModule module : guards.getModulesByType(GuardBuildingModule.class))
                    {
                        for (final ICitizenData guard : module.getAssignedCitizen())
                        {
                            count++;
                        }
                    }
                }
            }
        }
        buffer.writeVarInt(count);
        if (colony != null && colony.getServerBuildingManager() != null)
        {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            {
                if (building instanceof AbstractBuildingGuards guards
                    && CaravanGuardHelper.CARAVAN_TASK_KEY.equals(((IGuardBuilding) guards).getTask()))
                {
                    for (final GuardBuildingModule module : guards.getModulesByType(GuardBuildingModule.class))
                    {
                        for (final ICitizenData guard : module.getAssignedCitizen())
                        {
                            buffer.writeVarInt(guard.getId());
                            buffer.writeUtf(guard.getName());
                            buffer.writeBoolean(isGuardAssigned(guard.getId()));
                        }
                    }
                }
            }
        }
    }
}
