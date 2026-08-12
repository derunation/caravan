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
 * 客户端 → 服务器：循环切换某个交易条目的执行模式（禁用/单次/重复）。
 */
public class CaravanTradeModeMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "trade_mode", CaravanTradeModeMessage::new);

    private final int offerIndex;

    public CaravanTradeModeMessage(final IBuildingView buildingView, final int offerIndex)
    {
        super(TYPE, buildingView);
        this.offerIndex = offerIndex;
    }

    protected CaravanTradeModeMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
    {
        super(buffer, type);
        this.offerIndex = buffer.readVarInt();
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buffer)
    {
        super.toBytes(buffer);
        buffer.writeVarInt(offerIndex);
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
            module.cycleMode(offerIndex);
        }
    }
}
