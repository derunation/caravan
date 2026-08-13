package com.example.caravan.network;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.modules.CaravanGuardModule;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.network.messages.server.AbstractBuildingServerMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** 客户端 → 服务器：选中/取消商队小屋【护卫】页中的某名卫兵。 */
public class CaravanGuardAssignMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "guard_assign", CaravanGuardAssignMessage::new);

    private final int citizenId;
    private final boolean assign;

    public CaravanGuardAssignMessage(final IBuildingView buildingView, final int citizenId, final boolean assign)
    {
        super(TYPE, buildingView);
        this.citizenId = citizenId;
        this.assign = assign;
    }

    protected CaravanGuardAssignMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
    {
        super(buffer, type);
        this.citizenId = buffer.readVarInt();
        this.assign = buffer.readBoolean();
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buffer)
    {
        super.toBytes(buffer);
        buffer.writeVarInt(citizenId);
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
            module.setGuardAssigned(citizenId, assign);
        }
    }
}
