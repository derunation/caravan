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
 * 客户端 → 服务器：为小屋中记录的村民设置自定义名称（空白 = 清除名称）。
 */
public class CaravanRenameVillagerMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "rename_villager", CaravanRenameVillagerMessage::new);

    private final UUID villagerId;
    private final String name;

    public CaravanRenameVillagerMessage(final IBuildingView buildingView, final UUID villagerId, final String name)
    {
        super(TYPE, buildingView);
        this.villagerId = villagerId;
        this.name = name;
    }

    protected CaravanRenameVillagerMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
    {
        super(buffer, type);
        this.villagerId = buffer.readUUID();
        this.name = buffer.readUtf(32);
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buffer)
    {
        super.toBytes(buffer);
        buffer.writeUUID(villagerId);
        buffer.writeUtf(name, 32);
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
            module.renameVillager(villagerId, name);
        }
    }
}
