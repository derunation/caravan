package com.example.caravan.colony.buildings;

import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.example.caravan.colony.buildings.modules.CaravanStockModule;
import com.example.caravan.CaravanMod;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.core.colony.buildings.AbstractBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Tuple;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The caravan hut building. Hosts the Caravan Leader's work module (registered in
 * {@code ModBuildings}) and provides the storage chest the leader dumps his trade
 * results into.
 */
public class BuildingCaravanLeader extends AbstractBuilding
{
    private static final String SCHEMATIC_NAME = "caravanleader";
    private static final int MAX_LEVEL = 5;

    public BuildingCaravanLeader(final IColony colony, final BlockPos pos)
    {
        super(colony, pos);
    }

    @Override
    public String getSchematicName()
    {
        return SCHEMATIC_NAME;
    }

    @Override
    public int getMaxBuildingLevel()
    {
        return MAX_LEVEL;
    }

    /**
     * 需求（请求链重构）：商队小屋作为请求方收到“请求完成”通知时——
     * 若该请求由商队自身 resolver 处理（商队交易标记等），
     * 成果已在小屋存储中，无需再创建送达请求（避免本体为内部链生成
     * “自己送自己”或“运送 64 雕纹石砖”之类的虚假运送任务）；
     * 其它请求（如工匠生产售出物后送达商队小屋）由本体正常创建 Delivery。
     */
    @Override
    public void onRequestedRequestComplete(final IRequestManager manager, final IRequest<?> request)
    {
        try
        {
            final var resolver = manager.getResolverForRequest(request.getId());
            if (resolver instanceof com.example.caravan.colony.buildings.modules.CaravanTradeRequestResolver)
            {
                return;
            }
        }
        catch (final Exception ignored)
        {
            // 解析方查询失败时按本体默认处理。
        }
        super.onRequestedRequestComplete(manager, request);
    }

    /**
     * 需求（成果运送完成·回调驱动）：minecolonies 在快递员送达（RESOLVED）或
     * 取消 Delivery 时，会调用 requester（商队小屋）的 onRequestedRequestCancelled——
     * 若该请求是本模块创建的成果运送，立即完成主请求（不依赖轮询与物品数量），
     * 修复“快递员送达后主请求永不注销”的问题。
     */
    @Override
    public void onRequestedRequestCancelled(final IRequestManager manager, final IRequest<?> request)
    {
        try
        {
            final CaravanTradeModule module = getFirstModuleOccurance(CaravanTradeModule.class);
            if (module != null)
            {
                module.handleDeliveryRequestFinished(manager, request);
            }
        }
        catch (final Exception ignored)
        {
            // 处理失败时仍走本体默认清理。
        }
        super.onRequestedRequestCancelled(manager, request);
    }

    /**
     * 需求（bug 修复·最低存量）：覆写取货保留判定——在 minecolonies 遍历模块
     * （IAltersRequiredItems.alterItemsToBeKept）之外，直接把【绿宝石最低存量】
     * 作为【强制保留】条目加入（并置于 Map 首位优先匹配）。
     * 否则在部分环境下（模块遍历未被取货机制调用、或存在多个绿宝石保留条目时
     * 遍历顺序不定）快递员清仓会把全部绿宝石取走。
     */
    @Override
    public Map<Predicate<ItemStack>, Tuple<Integer, Boolean>> getRequiredItemsAndAmount()
    {
        final Map<Predicate<ItemStack>, Tuple<Integer, Boolean>> result = new LinkedHashMap<>();
        final CaravanStockModule stock = getFirstModuleOccurance(CaravanStockModule.class);
        if (stock != null && stock.getMinEmeraldStock() > 0)
        {
            final int keep = stock.getMinEmeraldStock() * CaravanStockModule.EMERALD_PER_STACK;
            result.put(
                stack -> !stack.isEmpty() && stack.getItem() == Items.EMERALD,
                new Tuple<>(keep, true));
        }
        // 需求（bug 修复）：商队帐篷必须保留在小屋/背包中（防止快递员取货收走）——
        // 否则帐篷送达小屋后会被 pickup 立即取走，商队永远拿不到帐篷。
        result.put(
            stack -> !stack.isEmpty() && stack.getItem() == CaravanMod.CARAVAN_TENT.get(),
            new Tuple<>(1, true));
        result.putAll(super.getRequiredItemsAndAmount());
        return result;
    }
}
