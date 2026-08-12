package com.example.caravan.block;

import com.example.caravan.CaravanMod;
import com.example.caravan.init.ModBuildings;
import com.minecolonies.api.blocks.AbstractBlockHut;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * The caravan hut block. Placed by the build tool from the {@code caravanleader1-5}
 * blueprints and registered into MineColonies' building registry via its
 * {@link #getBuildingEntry()}.
 */
public class BlockHutCaravanLeader extends AbstractBlockHut<BlockHutCaravanLeader>
{
    public static final String HUT_NAME = "blockhutcaravanleader";

    public BlockHutCaravanLeader()
    {
        super();
    }

    @Override
    public String getHutName()
    {
        return HUT_NAME;
    }

    @Override
    public BuildingEntry getBuildingEntry()
    {
        return ModBuildings.caravanLeader;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(final BlockPos pos, final BlockState state)
    {
        final BlockEntity entity = CaravanMod.TILE_CARAVAN_LEADER.get().create(pos, state);
        if (entity instanceof TileEntityColonyBuilding building)
        {
            building.registryName = getBuildingEntry().getRegistryName();
        }
        return entity;
    }
}
