package com.example.caravan.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The data stored on a Caravan Marker: the target village position and the
 * recorded villager trade (input costs + resulting item).
 */
public record TradeRecord(BlockPos villagePos, List<ItemStack> costs, ItemStack result)
{
    public static final Codec<TradeRecord> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        BlockPos.CODEC.fieldOf("village_pos").forGetter(TradeRecord::villagePos),
        ItemStack.OPTIONAL_CODEC.listOf().fieldOf("costs").forGetter(TradeRecord::costs),
        ItemStack.OPTIONAL_CODEC.fieldOf("result").forGetter(TradeRecord::result))
        .apply(instance, TradeRecord::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeRecord> STREAM_CODEC = new StreamCodec<>()
    {
        @Override
        public TradeRecord decode(final RegistryFriendlyByteBuf buffer)
        {
            final BlockPos villagePos = BlockPos.STREAM_CODEC.decode(buffer);
            final int costCount = buffer.readVarInt();
            final List<ItemStack> costs = new ArrayList<>(costCount);
            for (int i = 0; i < costCount; i++)
            {
                costs.add(ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer));
            }
            final ItemStack result = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            return new TradeRecord(villagePos, costs, result);
        }

        @Override
        public void encode(final RegistryFriendlyByteBuf buffer, final TradeRecord record)
        {
            BlockPos.STREAM_CODEC.encode(buffer, record.villagePos());
            buffer.writeVarInt(record.costs().size());
            for (final ItemStack cost : record.costs())
            {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, cost);
            }
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, record.result());
        }
    };
}
