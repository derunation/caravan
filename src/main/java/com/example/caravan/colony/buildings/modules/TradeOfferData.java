package com.example.caravan.colony.buildings.modules;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 一笔已记录的村民交易（两个可选成本 + 结果 + 交易次数上限 + 经验值）。
 */
public record TradeOfferData(ItemStack costA, ItemStack costB, ItemStack result, int maxUses, int xp)
{
    private static final String TAG_COST_A = "costA";
    private static final String TAG_COST_B = "costB";
    private static final String TAG_RESULT = "result";
    private static final String TAG_MAX_USES = "maxUses";
    private static final String TAG_XP = "xp";

    /** All non-empty costs of this trade. */
    public List<ItemStack> costs()
    {
        final List<ItemStack> list = new ArrayList<>(2);
        if (!costA.isEmpty())
        {
            list.add(costA);
        }
        if (!costB.isEmpty())
        {
            list.add(costB);
        }
        return list;
    }

    public CompoundTag save(final HolderLookup.Provider provider)
    {
        final CompoundTag tag = new CompoundTag();
        tag.put(TAG_COST_A, costA.saveOptional(provider));
        tag.put(TAG_COST_B, costB.saveOptional(provider));
        tag.put(TAG_RESULT, result.saveOptional(provider));
        tag.putInt(TAG_MAX_USES, maxUses);
        tag.putInt(TAG_XP, xp);
        return tag;
    }

    public static TradeOfferData load(final HolderLookup.Provider provider, final CompoundTag tag)
    {
        return new TradeOfferData(
            ItemStack.parseOptional(provider, tag.getCompound(TAG_COST_A)),
            ItemStack.parseOptional(provider, tag.getCompound(TAG_COST_B)),
            ItemStack.parseOptional(provider, tag.getCompound(TAG_RESULT)),
            tag.getInt(TAG_MAX_USES),
            tag.getInt(TAG_XP));
    }

    public void toBuffer(final RegistryFriendlyByteBuf buffer)
    {
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, costA);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, costB);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, result);
        buffer.writeVarInt(maxUses);
        buffer.writeVarInt(xp);
    }

    public static TradeOfferData fromBuffer(final RegistryFriendlyByteBuf buffer)
    {
        return new TradeOfferData(
            ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
            ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
            ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer),
            buffer.readVarInt(),
            buffer.readVarInt());
    }
}
