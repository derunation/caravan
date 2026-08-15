package com.example.caravan.network;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.ldtteam.common.network.PlayMessageType;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.network.messages.server.AbstractBuildingServerMessage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.Collection;


/**
 * 客户端 → 服务器：玩家关闭商队小屋 GUI 时发送。
 * 若商队领袖处于【等待物品】阶段，则清空其在小屋发布的所有请求，
 * 让 AI 以最新的交易列表重新备货。
 */
public class CaravanCloseGuiMessage extends AbstractBuildingServerMessage<BuildingCaravanLeader>
{
    public static final PlayMessageType<?> TYPE =
        PlayMessageType.forServer(CaravanMod.MODID, "close_gui", CaravanCloseGuiMessage::new);

    public CaravanCloseGuiMessage(final IBuildingView buildingView)
    {
        super(TYPE, buildingView);
    }

    protected CaravanCloseGuiMessage(final RegistryFriendlyByteBuf buffer, final PlayMessageType<?> type)
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
        final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
        if (module == null)
        {
            return;
        }
        module.applyPendingChanges();
        final JobCaravanLeader job = findLeader(building);
        if (job == null || job.getStatus() != JobCaravanLeader.CaravanStatus.WAITING_ITEMS)
        {
            return;
        }
        final IRequestManager manager = colony.getRequestManager();
        if (manager == null)
        {
            return;
        }
        for (final Collection<IToken<?>> tokens :
            new ArrayList<>(building.getOpenRequestsByRequestableType().values()))
        {
            for (final IToken<?> token : new ArrayList<>(tokens))
            {
                try
                {
                    final IRequest<?> request = manager.getRequestForToken(token);
                    if (request == null
                        || !(request.getRequest() instanceof com.minecolonies.api.colony.requestsystem.requestable.Stack))
                    {
                        continue;
                    }
                    final var probe =
                        (com.minecolonies.api.colony.requestsystem.requestable.IDeliverable) request.getRequest();
                    final var item = probe.getResult();
                    if (!item.isEmpty()
                        && (item.getItem() == com.example.caravan.CaravanMod.CARAVAN_TENT.get()
                            || item.getItem() == Items.TORCH))
                    {
                        continue;
                    }
                    manager.updateRequestState(token, RequestState.CANCELLED);
                }
                catch (final Exception ignored)
                {
                    // ignore stale requests
                }
            }
        }
    }

    private static JobCaravanLeader findLeader(final BuildingCaravanLeader building)
    {
        final com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule workerModule =
            building.getFirstModuleOccurance(com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule.class);
        if (workerModule == null)
        {
            return null;
        }
        for (final com.minecolonies.api.colony.ICitizenData citizen : workerModule.getAssignedCitizen())
        {
            if (citizen.getJob() instanceof JobCaravanLeader job)
            {
                return job;
            }
        }
        return null;
    }
}
