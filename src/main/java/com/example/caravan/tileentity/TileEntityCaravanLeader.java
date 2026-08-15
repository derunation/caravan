package com.example.caravan.tileentity;

import com.example.caravan.CaravanMod;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the caravan hut.
 *
 * <p>MineColonies' stock {@link TileEntityColonyBuilding} hardcodes the
 * {@code minecolonies:colonybuilding} block entity type in its two-argument
 * constructor, which is invalid for blocks outside MineColonies and crashes with
 * "Invalid block entity ... got Block{caravan:blockhutcaravanleader}". This
 * subclass passes our own registered type instead.</p>
 */
public class TileEntityCaravanLeader extends TileEntityColonyBuilding
{
    public TileEntityCaravanLeader(final BlockPos pos, final BlockState state)
    {
        super(CaravanMod.TILE_CARAVAN_LEADER.get(), pos, state);
    }
}
