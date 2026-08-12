package com.example.caravan.network;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.network.messages.server.AbstractBuildingServerMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Sent from the hut's Settings tab: gives the player a Caravan Marker bound to
 * this hut.
 */
public class CaravanGetToolMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "get_tool", CaravanGetToolMessage::new);

    public CaravanGetToolMessage(final IBuildingView buildingView)
    {
        super(TYPE, buildingView);
    }

    protected CaravanGetToolMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
    {
        super(buffer, type);
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buffer)
    {
        super.toBytes(buffer);
    }

    @Override
    protected void onExecute(
        final IPayloadContext context,
        final ServerPlayer player,
        final IColony colony,
        final BuildingCaravanLeader building)
    {
        final ItemStack marker = new ItemStack(CaravanMod.CARAVAN_MARKER.get());
        marker.set(CaravanMod.BOUND_HUT.get(), building.getPosition());
        if (player.getInventory().add(marker))
        {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("item.caravan.caravan_marker.bound",
                    building.getPosition().getX(), building.getPosition().getY(), building.getPosition().getZ()), true);
        }
        else
        {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.translatable("item.caravan.caravan_marker.inventory_full"), true);
        }
    }
}
