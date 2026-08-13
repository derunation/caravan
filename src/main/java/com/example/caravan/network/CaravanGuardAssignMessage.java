package com.example.caravan.network;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.modules.CaravanGuardModule;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.network.messages.server.AbstractBuildingServerMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 客户端 → 服务器：选中/取消商队小屋【护卫】页中的某座卫兵塔（塔级指派）。 */
public class CaravanGuardAssignMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "guard_assign", CaravanGuardAssignMessage::new);

    private final BlockPos towerPos;
    private final boolean assign;

    public CaravanGuardAssignMessage(final IBuildingView buildingView, final BlockPos towerPos, final boolean assign)
    {
        super(TYPE, buildingView);
        this.towerPos = towerPos;
        this.assign = assign;
    }

    protected CaravanGuardAssignMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
    {
        super(buffer, type);
        this.towerPos = new BlockPos(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt());
        this.assign = buffer.readBoolean();
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buffer)
    {
        super.toBytes(buffer);
        buffer.writeVarInt(towerPos.getX());
        buffer.writeVarInt(towerPos.getY());
        buffer.writeVarInt(towerPos.getZ());
        buffer.writeBoolean(assign);
    }

    @Override
    protected void onExecute(
        final IPayloadContext context,
        final ServerPlayer player,
        final IColony colony,
        final BuildingCaravanLeader building)
    {
        final CaravanGuardModule module = building.getFirstModuleOccurance(CaravanGuardModule.class);
        if (module != null)
        {
            module.setTowerAssigned(towerPos, assign);
        }
    }
}
