package com.example.caravan.colony.buildings.modules;

import com.example.caravan.colony.buildings.CaravanGuardHelper;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import com.minecolonies.core.colony.buildings.modules.GuardBuildingModule;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.HashSet;
import java.util.Set;

/**
 * 需求（商队护卫）：商队小屋【护卫】页的服务端数据源——
 * 持久化“已被选中开始护卫商队”的卫兵塔位置列表（塔级指派：塔内商队护卫模式的
 * 卫兵全部护卫）；同步殖民地所有【商队护卫】工作模式的卫兵塔给客户端。
 */
public class CaravanGuardModule extends AbstractBuildingModule implements IPersistentModule
{
    private static final String TAG_ASSIGNED = "assignedTowers";

    /** 已选中护卫商队的卫兵塔位置。 */
    private final Set<BlockPos> assignedTowers = new HashSet<>();

    /** 该卫兵塔是否已被选中护卫商队。 */
    public boolean isTowerAssigned(final BlockPos towerPos)
    {
        return assignedTowers.contains(towerPos);
    }

    /** 设置/取消该卫兵塔的商队护卫指派。 */
    public void setTowerAssigned(final BlockPos towerPos, final boolean assigned)
    {
        final boolean changed = assigned ? assignedTowers.add(towerPos) : assignedTowers.remove(towerPos);
        if (changed)
        {
            getBuilding().markDirty();
        }
    }

    @Override
    public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag compound)
    {
        assignedTowers.clear();
        final ListTag list = compound.getList(TAG_ASSIGNED, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++)
        {
            final CompoundTag tag = list.getCompound(i);
            assignedTowers.add(new BlockPos(
                tag.getInt("x"), tag.getInt("y"), tag.getInt("z")));
        }
    }

    @Override
    public void serializeNBT(final HolderLookup.Provider provider, final CompoundTag compound)
    {
        final ListTag list = new ListTag();
        for (final BlockPos pos : assignedTowers)
        {
            final CompoundTag tag = new CompoundTag();
            tag.putInt("x", pos.getX());
            tag.putInt("y", pos.getY());
            tag.putInt("z", pos.getZ());
            list.add(tag);
        }
        compound.put(TAG_ASSIGNED, list);
    }

    /** 同步：殖民地所有【商队护卫】模式卫兵（名字/所属塔/是否已指派）。 */
    @Override
    public void serializeToView(final RegistryFriendlyByteBuf buffer)
    {
        final IColony colony = getBuilding().getColony();
        int count = 0;
        if (colony != null && colony.getServerBuildingManager() != null)
        {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            {
                if (CaravanGuardHelper.isCaravanTower(building))
                {
                    for (final GuardBuildingModule module : building.getModulesByType(GuardBuildingModule.class))
                    {
                        count += module.getAssignedCitizen().size();
                    }
                }
            }
        }
        buffer.writeVarInt(count);
        if (colony != null && colony.getServerBuildingManager() != null)
        {
            for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
            {
                if (!CaravanGuardHelper.isCaravanTower(building))
                {
                    continue;
                }
                for (final GuardBuildingModule module : building.getModulesByType(GuardBuildingModule.class))
                {
                    for (final ICitizenData guard : module.getAssignedCitizen())
                    {
                        buffer.writeVarInt(guard.getId());
                        buffer.writeUtf(guard.getName());
                        buffer.writeVarInt(building.getPosition().getX());
                        buffer.writeVarInt(building.getPosition().getY());
                        buffer.writeVarInt(building.getPosition().getZ());
                        buffer.writeBoolean(isTowerAssigned(building.getPosition()));
                    }
                }
            }
        }
    }
}
