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
 * 需求（请求系统接入）：商队小屋的自定义请求解析方——
 * 当殖民地中某个建筑发出、且商队小屋有激活【按需】交易可产出的物品请求时，
 * 该 resolver 接单并把需求记录到 {@link CaravanTradeModule}，
 * 由商队下次出发执行交易、归来后交付物品并完成请求。
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
        // 需求（请求链重构）："商队交易"标记子请求（M 节点）由本解析方持有（显示节点）。
        if (request.getRequest() instanceof CaravanTradeRequestable)
        {
            return true;
        }
        // 需求（防重复接单）：同一请求已在记录/送达中时不重复接单，
        // 避免商队反复执行同一请求、重复创建 Delivery。
        if (module.isOnDemandRequestHandled(request.getId()))
        {
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 请求 {} 已在处理中，resolver 拒绝重复接单",
                String.valueOf(request.getId()));
            return false;
        }
        // 需求（bug 修复）：已进入完成流程（物品满足、等待成品运送）的请求
        // 不再重新接单——否则会创建悬空的 M/S 子请求，导致请求链无法结单。
        if (module.isOnDemandRequestCompleted(request.getId()))
        {
            return false;
        }
        // 需求（非递归重构）：已下放的主请求不重新接入——重试解析方周期性
        // 重新询问时也拒绝，避免“接入→下放”死循环；下次商队交易归来后由
        // recheckReleasedRequestsAfterReturn() 重新检查。
        if (module.isReleasedRequest(request.getId()))
        {
            return false;
        }
        // 需求（非递归重构）：商队只对“主请求”（其它建筑发出的请求）进行检测。
        // 商队小屋自己发出的售出品请求（请求链 S 节点、备货请求等）一律不接入——
        // 殖民地无法提供售出品时，由 CaravanTradeModule.checkAdoptedRequestHealth()
        // 注销子请求并把主请求下放；下次商队归来后由 recheckReleasedRequestsAfterReturn()
        // 重新检查是否接入。旧实现会重新接回自身请求形成递归链，导致请求流卡死。
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
        com.example.caravan.CaravanMod.LOGGER.info(
            "Caravan: resolver 接单 {} ({} x{})",
            String.valueOf(request.getId()),
            module.findOnDemandResultFor(deliverable).getHoverName().getString(), deliverable.getCount());
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
        // 需求（防无限递归）："商队交易"标记子请求（M 节点）仅作显示/推进节点，
        // 由本解析方持有即可——绝不为其再建链（否则 adoptRequest 创建的 M 被分配后
        // 再次进入 resolveRequest → 无限递归栈溢出，请求永远无法接入商队）。
        if (request.getRequest() instanceof CaravanTradeRequestable)
        {
            return;
        }
        final CaravanTradeModule module = findModule(manager);
        if (module != null && request.getRequest() instanceof IDeliverable deliverable)
        {
            // 需求（0.4.18 回退）：记录需求并创建“A×商队交易 + B×售出物”两个子请求。
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
        // 需求（重构）：本体 Delivery 送达完成后清理按需记录。
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
            // 需求（0.4.19 参考）：级联取消请求链子请求（售出物 S + 交易标记 M）。
            module.removeOnDemandRequest(manager, request.getId());
        }
    }

    @Override
    public boolean isValid()
    {
        return true;
    }

    /**
     * 需求（优先级修正）：minecolonies 请求系统实际按【降序】尝试解析方
     * （RequestHandler 的排序键为 -getPriority()：数值越大越先尝试），
     * 真实顺序为 仓库调配 200 → 工匠生产 125 → 商队 75 → 重试 50 → 玩家 0。
     * 旧实现设为 1000 会导致商队【最先】被询问，抢在仓库/工匠之前接单
     * （“殖民地有产能却由商队处理”的根因）。改为 75 后，
     * 仓库/工匠可处理时优先处理，确实无人处理时才由商队兜底。
     */
    @Override
    public int getPriority()
    {
        return 75;
    }

    /**
     * 需求（请求链可视化）：请求树中“处理中”一栏显示“商队小屋”，
     * 配合交易品/运送子请求，使信箱 GUI 呈现“请求 → 商队小屋处理 → 送达”流程链。
     */
    @Override
    public MutableComponent getRequesterDisplayName(
        final IRequestManager manager,
        final IRequest<?> request)
    {
        return Component.translatable("com.caravan.gui.resolver_name");
    }
}
