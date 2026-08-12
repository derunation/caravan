package com.example.caravan.colony.buildings.modules;

import com.google.common.collect.ImmutableSet;
import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.util.ItemStackUtils;
import net.minecraft.world.item.ItemStack;

import java.util.Set;

/**
 * 需求（请求链可视化）：商队小屋的"商队交易"中间请求——
 * 挂在原始按需请求下作为子请求，使信箱/请求树 GUI 显示
 * "1×商队交易：雕纹石砖"（参照工匠的"1×配方：XXX"格式）。
 */
public class CaravanTradeRequestable implements IDeliverable
{
    private static final Set<TypeToken<?>> TYPE_TOKENS = ImmutableSet.of(
        TypeToken.of(CaravanTradeRequestable.class),
        TypeToken.of(IDeliverable.class));

    private final ItemStack result;
    private final int count;

    public CaravanTradeRequestable(final ItemStack result, final int count)
    {
        this.result = result.copy();
        this.count = count;
    }

    @Override
    public boolean matches(final ItemStack stack)
    {
        return !stack.isEmpty() && ItemStackUtils.compareItemStacksIgnoreStackSize(stack, result);
    }

    @Override
    public int getCount()
    {
        return count;
    }

    @Override
    public int getMinimumCount()
    {
        return 1;
    }

    @Override
    public ItemStack getResult()
    {
        return result;
    }

    @Override
    public void setResult(final ItemStack result)
    {
        // 不可变。
    }

    @Override
    public IDeliverable copyWithCount(final int count)
    {
        return new CaravanTradeRequestable(result, count);
    }

    @Override
    public Set<TypeToken<?>> getSuperClasses()
    {
        return TYPE_TOKENS;
    }
}
