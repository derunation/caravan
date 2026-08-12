package com.example.caravan.colony.buildings.modules;

import com.minecolonies.api.colony.requestsystem.requester.IRequester;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.requestsystem.requests.AbstractRequest;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 需求（请求链可视化）："商队交易"中间请求——请求树显示
 * "1×商队交易：雕纹石砖"，参照工匠"1×配方：XXX"格式。
 */
public class CaravanTradeRequest extends AbstractRequest<CaravanTradeRequestable>
{
    public CaravanTradeRequest(
        final IRequester requester,
        final IToken<?> token,
        final RequestState state,
        final CaravanTradeRequestable requestable)
    {
        super(requester, token, state, requestable);
    }

    @Override
    public MutableComponent getShortDisplayString()
    {
        return Component.translatable("com.caravan.request.trade.display",
            getRequest().getCount(), getRequest().getResult().getHoverName());
    }

    @Override
    public List<ItemStack> getDisplayStacks()
    {
        return List.of(getRequest().getResult());
    }
}
