package com.example.caravan.item;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * 商队帐篷（占位物品）——商队出行/休息时消耗耐久。
 * 耐久 60；图标为占位符（工作区 caravan_tent_icon.png 可替换
 * assets/caravan/textures/item/caravan_tent.png）。
 */
public class ItemCaravanTent extends Item
{
    /** 商队帐篷的总耐久度。 */
    public static final int MAX_DURABILITY = 60;

    public ItemCaravanTent(final Properties properties)
    {
        super(properties.durability(MAX_DURABILITY));
    }

    @Override
    public void appendHoverText(
        final ItemStack stack,
        final TooltipContext context,
        final List<Component> tooltip,
        final TooltipFlag flag)
    {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.caravan.caravan_tent.tooltip"));
    }
}
