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

import java.util.UUID;

/**
 * 客户端 → 服务器：从小屋中删除指定村民及其全部交易记录。
 */
public class CaravanDeleteVillagerMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "delete_villager", CaravanDeleteVillagerMessage::new);

    private final UUID villagerId;

    public CaravanDeleteVillagerMessage(final IBuildingView buildingView, final UUID villagerId)
    {
        super(TYPE, buildingView);
        this.villagerId = villagerId;
    }

    protected CaravanDeleteVillagerMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
    {
        super(buffer, type);
        this.villagerId = buffer.readUUID();
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buffer)
    {
        super.toBytes(buffer);
        buffer.writeUUID(villagerId);
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
            module.removeVillager(villagerId);
        }
    }
}
