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
 * 客户端 → 服务器：【总览】窗口中上移/下移某笔激活交易在顺序列表中的位置。
 * 【按需】交易按此顺序分配给请求系统。
 */
public class CaravanTradeOrderMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "trade_order", CaravanTradeOrderMessage::new);

    private final int offerIndex;
    private final boolean up;

    public CaravanTradeOrderMessage(final IBuildingView buildingView, final int offerIndex, final boolean up)
    {
        super(TYPE, buildingView);
        this.offerIndex = offerIndex;
        this.up = up;
    }

    protected CaravanTradeOrderMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
    {
        super(buffer, type);
        this.offerIndex = buffer.readVarInt();
        this.up = buffer.readBoolean();
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buffer)
    {
        super.toBytes(buffer);
        buffer.writeVarInt(offerIndex);
        buffer.writeBoolean(up);
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
            module.moveOffer(offerIndex, up);
        }
    }
}
