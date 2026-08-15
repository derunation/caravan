package com.example.caravan.colony.buildings.modules;

import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.requestsystem.resolvers.core.AbstractRequestResolver;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.util.List;

/**
 * 商队小屋的自定义请求解析方：当殖民地中某个建筑发出、且商队小屋有
 * 激活【按需】交易可产出的物品请求时，该 resolver 接单并把需求记录到
 * {@link CaravanTradeModule}，由商队下次出发执行交易、归来后交付并完成请求。
 */
public class CaravanTradeRequestResolver extends AbstractRequestResolver<IDeliverable>
{
    public CaravanTradeRequestResolver(final ILocation location, final IToken<?> token)
    {
        super(location, token);
    }

    @Override
    public TypeToken<? extends IDeliverable> getRequestType()
    {
        return TypeToken.of(IDeliverable.class);
    }

    /** 通过本 resolver 的位置（商队小屋）查找交易模块。 */
    private CaravanTradeModule findModule(final IRequestManager manager)
    {
        try
        {
            final IColony colony = manager.getColony();
            final IBuilding building = colony.getServerBuildingManager()
                .getBuilding(getLocation().getInDimensionLocation(), BuildingCaravanLeader.class);
            if (building instanceof BuildingCaravanLeader caravan)
            {
                return caravan.getFirstModuleOccurance(CaravanTradeModule.class);
            }
        }
        catch (final Exception ignored)
        {
            // 殖民地数据未就绪。
        }
        return null;
    }

    @Override
    public boolean canResolveRequest(
        final IRequestManager manager,
        final IRequest<? extends IDeliverable> request)
    {
        if (!(request.getRequest() instanceof IDeliverable deliverable))
        {
            return false;
        }
        final CaravanTradeModule module = findModule(manager);
        if (module == null)
        {
            return false;
        }
        if (request.getRequest() instanceof CaravanTradeRequestable)
        {
            return true;
        }
        if (module.isOnDemandRequestHandled(request.getId()))
        {
            return false;
        }
        if (module.isOnDemandRequestCompleted(request.getId()))
        {
            return false;
        }
        // 已下放的主请求不重新接入，避免“接入→下放”死循环。
        if (module.isReleasedRequest(request.getId()))
        {
            return false;
        }
        // 商队只对其它建筑发出的“主请求”接单；商队小屋自身的售出品请求一律不接入，
        // 避免重新接回自身请求形成递归链。
        boolean selfRequest = false;
        try
        {
            final com.minecolonies.api.colony.requestsystem.requester.IRequester requester = request.getRequester();
            selfRequest = requester != null
                && requester.getLocation().getInDimensionLocation()
                    .equals(getLocation().getInDimensionLocation());
        }
        catch (final Exception ignored)
        {
            // 位置不可用时按可接单处理。
        }
        if (selfRequest)
        {
            return false;
        }
        if (!module.hasOnDemandOfferFor(deliverable))
        {
            return false;
        }
        return true;
    }

    @Override
    public List<IToken<?>> attemptResolveRequest(
        final IRequestManager manager,
        final IRequest<? extends IDeliverable> request)
    {
        // 商队执行交易后直接交付，不创建子请求。
        return List.of();
    }

    @Override
    public void resolveRequest(
        final IRequestManager manager,
        final IRequest<? extends IDeliverable> request)
    {
        // "商队交易"标记子请求仅作显示/推进节点，由本解析方持有，不再为其建链。
        if (request.getRequest() instanceof CaravanTradeRequestable)
        {
            return;
        }
        final CaravanTradeModule module = findModule(manager);
        if (module != null && request.getRequest() instanceof IDeliverable deliverable)
        {
            module.adoptRequest(manager, request, deliverable);
        }
    }

    @Override
    public void onAssignedRequestBeingCancelled(
        final IRequestManager manager,
        final IRequest<? extends IDeliverable> request)
    {
        removeRequest(manager, request);
    }

    @Override
    public void onAssignedRequestCancelled(
        final IRequestManager manager,
        final IRequest<? extends IDeliverable> request)
    {
        removeRequest(manager, request);
    }

    @Override
    public void onRequestedRequestComplete(
        final IRequestManager manager,
        final IRequest<?> request)
    {
        removeRequest(manager, request);
    }

    @Override
    public void onRequestedRequestCancelled(
        final IRequestManager manager,
        final IRequest<?> request)
    {
        removeRequest(manager, request);
    }

    private void removeRequest(final IRequestManager manager, final IRequest<?> request)
    {
        final CaravanTradeModule module = findModule(manager);
        if (module != null)
        {
            module.removeOnDemandRequest(manager, request.getId());
        }
    }

    @Override
    public boolean isValid()
    {
        return true;
    }

    /** 优先级 75：仓库（200）/工匠（125）优先，商队兜底，最后才是玩家（0）。 */
    @Override
    public int getPriority()
    {
        return 75;
    }

    /** 请求树中“处理中”一栏显示“商队小屋”。 */
    @Override
    public MutableComponent getRequesterDisplayName(
        final IRequestManager manager,
        final IRequest<?> request)
    {
        return Component.translatable("com.caravan.gui.resolver_name");
    }
}
