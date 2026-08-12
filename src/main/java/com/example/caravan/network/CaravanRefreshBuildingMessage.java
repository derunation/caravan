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
 * 客户端 → 服务器：玩家打开商队小屋 GUI 时发送，把建筑标记为脏，
 * 促使服务器尽快重新同步建筑视图（含【日志】等模块数据）。
 */
public class CaravanRefreshBuildingMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "refresh_building", CaravanRefreshBuildingMessage::new);

    public CaravanRefreshBuildingMessage(final IBuildingView buildingView)
    {
        super(TYPE, buildingView);
    }

    protected CaravanRefreshBuildingMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
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
        // 需求：打开 GUI 时同步触发 Waystone 名称的分批刷新（每 20 刻一批）。
        final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
        if (module != null)
        {
            module.startWaystoneRefresh();
        }
        building.markDirty();
    }
}
