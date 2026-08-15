package com.example.caravan.colony.buildings.modules;

import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.IAltersRequiredItems;
import com.minecolonies.api.colony.buildings.modules.IPersistentModule;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.util.TriConsumer;

import java.util.function.Predicate;

/**
 * 商队小屋【设置】标签页的服务器端模块：最小绿宝石库存。
 *
 * <p>类似【最低存量】模块，但针对绿宝石固定生效（默认 4 组，1 组 = 64 个），
 * 玩家可在【设置】标签页通过文本框修改；快递员取货时会为小屋保留该数量。</p>
 */
public class CaravanStockModule extends AbstractBuildingModule implements IPersistentModule, IAltersRequiredItems
{
    private static final String TAG_MIN_STOCK = "minEmeraldStock";
    private static final String TAG_TENT_COUNT = "tentCarryCount";
    private static final String TAG_FOOD_COUNT = "foodCarryCount";
    private static final String TAG_TORCH_COUNT = "torchCarryCount";

    /** 每组绿宝石的数量（1 组 = 64 个）。 */
    public static final int EMERALD_PER_STACK = 64;

    /** 默认最小绿宝石库存：4 组。 */
    public static final int DEFAULT_MIN_STOCK = 4;
    /** 默认帐篷携带量。 */
    public static final int DEFAULT_TENT_COUNT = 1;
    /** 默认食物携带组数。 */
    public static final int DEFAULT_FOOD_COUNT = 2;
    /** 默认火把携带组数。 */
    public static final int DEFAULT_TORCH_COUNT = 2;

    private int minEmeraldStock = DEFAULT_MIN_STOCK;
    private int tentCarryCount = DEFAULT_TENT_COUNT;
    private int foodCarryCount = DEFAULT_FOOD_COUNT;
    private int torchCarryCount = DEFAULT_TORCH_COUNT;

    /** 查询当前最小绿宝石库存（组数）。 */
    public int getMinEmeraldStock()
    {
        return minEmeraldStock;
    }

    /** 设置最小绿宝石库存（组数，负数按 0 处理）。 */
    public void setMinEmeraldStock(final int value)
    {
        minEmeraldStock = Math.max(0, value);
        markDirty();
    }

    public int getTentCarryCount()
    {
        return tentCarryCount;
    }

    public void setTentCarryCount(final int value)
    {
        tentCarryCount = Math.max(0, Math.min(32, value));
        markDirty();
    }

    public int getFoodCarryCount()
    {
        return foodCarryCount;
    }

    public void setFoodCarryCount(final int value)
    {
        foodCarryCount = Math.max(0, Math.min(32, value));
        markDirty();
    }

    public int getTorchCarryCount()
    {
        return torchCarryCount;
    }

    public void setTorchCarryCount(final int value)
    {
        torchCarryCount = Math.max(0, Math.min(32, value));
        markDirty();
    }

    @Override
    public void alterItemsToBeKept(final TriConsumer<Predicate<ItemStack>, Integer, Boolean> consumer)
    {
        if (minEmeraldStock <= 0)
        {
            return;
        }
        consumer.accept(
            stack -> !stack.isEmpty() && stack.getItem() == Items.EMERALD,
            minEmeraldStock * EMERALD_PER_STACK,
            false);
    }

    @Override
    public void serializeNBT(final HolderLookup.Provider provider, final CompoundTag tag)
    {
        tag.putInt(TAG_MIN_STOCK, minEmeraldStock);
        tag.putInt(TAG_TENT_COUNT, tentCarryCount);
        tag.putInt(TAG_FOOD_COUNT, foodCarryCount);
        tag.putInt(TAG_TORCH_COUNT, torchCarryCount);
    }

    @Override
    public void deserializeNBT(final HolderLookup.Provider provider, final CompoundTag tag)
    {
        minEmeraldStock = tag.contains(TAG_MIN_STOCK) ? tag.getInt(TAG_MIN_STOCK) : DEFAULT_MIN_STOCK;
        tentCarryCount = tag.contains(TAG_TENT_COUNT)
            ? Math.max(0, Math.min(32, tag.getInt(TAG_TENT_COUNT)))
            : DEFAULT_TENT_COUNT;
        foodCarryCount = tag.contains(TAG_FOOD_COUNT)
            ? Math.max(0, Math.min(32, tag.getInt(TAG_FOOD_COUNT)))
            : DEFAULT_FOOD_COUNT;
        torchCarryCount = tag.contains(TAG_TORCH_COUNT)
            ? Math.max(0, Math.min(32, tag.getInt(TAG_TORCH_COUNT)))
            : DEFAULT_TORCH_COUNT;
    }

    @Override
    public void serializeToView(final RegistryFriendlyByteBuf buffer)
    {
        buffer.writeVarInt(minEmeraldStock);
        buffer.writeVarInt(tentCarryCount);
        buffer.writeVarInt(foodCarryCount);
        buffer.writeVarInt(torchCarryCount);
    }
}
