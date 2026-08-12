package com.example.caravan.network;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.modules.CaravanStockModule;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.network.messages.server.AbstractBuildingServerMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端 → 服务器：设置商队小屋的最小绿宝石库存。
 */
public class CaravanStockMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "set_stock", CaravanStockMessage::new);

    private final int minEmeraldStock;
    private final int tentCarryCount;
    private final int foodCarryCount;
    private final int torchCarryCount;

    public CaravanStockMessage(
        final IBuildingView buildingView,
        final int minEmeraldStock,
        final int tentCarryCount,
        final int foodCarryCount,
        final int torchCarryCount)
    {
        super(TYPE, buildingView);
        this.minEmeraldStock = minEmeraldStock;
        this.tentCarryCount = tentCarryCount;
        this.foodCarryCount = foodCarryCount;
        this.torchCarryCount = torchCarryCount;
    }

    protected CaravanStockMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
    {
        super(buffer, type);
        this.minEmeraldStock = buffer.readVarInt();
        this.tentCarryCount = buffer.readVarInt();
        this.foodCarryCount = buffer.readVarInt();
        this.torchCarryCount = buffer.readVarInt();
    }

    @Override
    protected void toBytes(final RegistryFriendlyByteBuf buffer)
    {
        super.toBytes(buffer);
        buffer.writeVarInt(minEmeraldStock);
        buffer.writeVarInt(tentCarryCount);
        buffer.writeVarInt(foodCarryCount);
        buffer.writeVarInt(torchCarryCount);
    }

    @Override
    protected void onExecute(
        final IPayloadContext context,
        final ServerPlayer player,
        final IColony colony,
        final BuildingCaravanLeader building)
    {
        final CaravanStockModule module = building.getFirstModuleOccurance(CaravanStockModule.class);
        if (module != null)
        {
            module.setMinEmeraldStock(minEmeraldStock);
            module.setTentCarryCount(tentCarryCount);
            module.setFoodCarryCount(foodCarryCount);
            module.setTorchCarryCount(torchCarryCount);
        }
    }
}
