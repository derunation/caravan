package com.example.caravan.network;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.network.messages.server.AbstractBuildingServerMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务器：设置某交易条目的【交易数量】（1..该交易最大可用次数）。
 */
public class CaravanTradeQuantityMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "trade_quantity", CaravanTradeQuantityMessage::new);

    private final int offerIndex;
    private final int quantity;

    public CaravanTradeQuantityMessage(final IBuildingView buildingView, final int offerIndex, final int quantity)
    {
        super(TYPE, buildingView);
        this.offerIndex = offerIndex;
        this.quantity = quantity;
    }

    protected CaravanTradeQuantityMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
    {
        super(buffer, type);
        this.offerIndex = buffer.readVarInt();
        this.quantity = buffer.readVarInt();
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buffer)
    {
        super.toBytes(buffer);
        buffer.writeVarInt(offerIndex);
        buffer.writeVarInt(quantity);
    }

    @Override
    protected void onExecute(
        final IPayloadContext context,
        final ServerPlayer player,
        final IColony colony,
        final BuildingCaravanLeader building)
    {
        final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
        if (module != null)
        {
            module.setQuantity(offerIndex, quantity);
        }
    }
}
