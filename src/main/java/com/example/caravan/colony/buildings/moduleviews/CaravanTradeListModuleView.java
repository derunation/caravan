package com.example.caravan.colony.buildings.moduleviews;

import com.example.caravan.CaravanMod;
import com.example.caravan.client.gui.modules.WindowCaravanTradeList;
import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.example.caravan.colony.buildings.modules.TradeOfferData;
import com.example.caravan.colony.buildings.modules.VillagerTradeEntry;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商队小屋【交易列表】标签页的客户端视图：村民、交易条目及其执行模式
 * （禁用/单次/重复）与交易数量设置（按 村民UUID+条目序号 存储）。
 */
public class CaravanTradeListModuleView extends AbstractBuildingModuleView
{
    private final List<VillagerTradeEntry> villagers = new ArrayList<>();
    private final Map<String, CaravanTradeModule.TradeMode> modes = new HashMap<>();
    private final Map<String, Integer> quantities = new HashMap<>();
    private final Map<UUID, String> customNames = new HashMap<>();
    private final List<Integer> offerOrder = new ArrayList<>();
    private int maxSelection = 1;

    @Override
    public void deserialize(final RegistryFriendlyByteBuf buffer)
    {
        villagers.clear();
        final int villagerCount = buffer.readVarInt();
        for (int i = 0; i < villagerCount; i++)
        {
            villagers.add(VillagerTradeEntry.fromBuffer(buffer));
        }

        modes.clear();
        final int modeCount = buffer.readVarInt();
        for (int i = 0; i < modeCount; i++)
        {
            modes.put(readSettingKey(buffer), modeFromOrdinal(buffer.readVarInt()));
        }

        quantities.clear();
        final int quantityCount = buffer.readVarInt();
        for (int i = 0; i < quantityCount; i++)
        {
            quantities.put(readSettingKey(buffer), buffer.readVarInt());
        }
        // 直接覆盖 modes/quantities 显示，避免同步把旧值推回导致界面跳变；
        // 玩家退出 GUI 时服务器已把待应用值合并进正式设置并清空待应用列表。
        final int pendingModeCount = buffer.readVarInt();
        for (int i = 0; i < pendingModeCount; i++)
        {
            modes.put(readSettingKey(buffer), modeFromOrdinal(buffer.readVarInt()));
        }
        final int pendingQuantityCount = buffer.readVarInt();
        for (int i = 0; i < pendingQuantityCount; i++)
        {
            quantities.put(readSettingKey(buffer), buffer.readVarInt());
        }
        customNames.clear();
        final int nameCount = buffer.readVarInt();
        for (int i = 0; i < nameCount; i++)
        {
            customNames.put(buffer.readUUID(), buffer.readUtf());
        }
        offerOrder.clear();
        final int orderCount = buffer.readVarInt();
        for (int i = 0; i < orderCount; i++)
        {
            offerOrder.add(buffer.readVarInt());
        }
        maxSelection = buffer.readVarInt();
    }

    private static String readSettingKey(final RegistryFriendlyByteBuf buffer)
    {
        return buffer.readUtf() + ":" + buffer.readVarInt();
    }

    private static CaravanTradeModule.TradeMode modeFromOrdinal(final int ordinal)
    {
        return ordinal >= 0 && ordinal < CaravanTradeModule.TradeMode.values().length
            ? CaravanTradeModule.TradeMode.values()[ordinal]
            : CaravanTradeModule.TradeMode.DISABLED;
    }

    @Override
    public BOWindow getWindow()
    {
        return new WindowCaravanTradeList(getBuildingView(), this);
    }

    @Override
    public Component getDesc()
    {
        return Component.translatable("com.caravan.gui.trades");
    }

    @Override
    public ResourceLocation getIconResourceLocation()
    {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/inventory.png");
    }

    public List<VillagerTradeEntry> getVillagers()
    {
        return villagers;
    }

    /** 平铺索引 → {村民下标, 条目序号}。 */
    private int[] resolveOffer(final int flatIndex)
    {
        int remaining = flatIndex;
        for (int vi = 0; vi < villagers.size(); vi++)
        {
            final int size = villagers.get(vi).offers().size();
            if (remaining < size)
            {
                return new int[] {vi, remaining};
            }
            remaining -= size;
        }
        return null;
    }

    public CaravanTradeModule.TradeMode getMode(final int flatIndex)
    {
        final int[] resolved = resolveOffer(flatIndex);
        if (resolved == null)
        {
            return CaravanTradeModule.TradeMode.DISABLED;
        }
        return modes.getOrDefault(
            villagers.get(resolved[0]).villagerId() + ":" + resolved[1],
            CaravanTradeModule.TradeMode.DISABLED);
    }

    public int getQuantity(final int flatIndex)
    {
        final int[] resolved = resolveOffer(flatIndex);
        if (resolved == null)
        {
            return 1;
        }
        return quantities.getOrDefault(villagers.get(resolved[0]).villagerId() + ":" + resolved[1], 1);
    }

    /** 客户端本地切换模式（乐观更新，等待服务器同步）。 */
    public void cycleModeLocal(final int flatIndex, final CaravanTradeModule.TradeMode mode)
    {
        final int[] resolved = resolveOffer(flatIndex);
        if (resolved != null)
        {
            modes.put(villagers.get(resolved[0]).villagerId() + ":" + resolved[1], mode);
        }
    }

    /** 客户端本地更新交易数量（乐观更新，等待服务器同步）。 */
    public void setQuantityLocal(final int flatIndex, final int quantity)
    {
        final int[] resolved = resolveOffer(flatIndex);
        if (resolved != null)
        {
            final int max = Math.max(1, villagers.get(resolved[0]).offers().get(resolved[1]).maxUses());
            quantities.put(villagers.get(resolved[0]).villagerId() + ":" + resolved[1],
                Math.max(1, Math.min(quantity, max)));
        }
    }

    public int getMaxSelection()
    {
        return maxSelection;
    }

    /** 当前非禁用（已选择）的交易条目数。 */
    public int getNonDisabledCount()
    {
        int count = 0;
        for (int i = 0; i < getTotalOfferCount(); i++)
        {
            if (getMode(i) != CaravanTradeModule.TradeMode.DISABLED)
            {
                count++;
            }
        }
        return count;
    }

    public int getTotalOfferCount()
    {
        int count = 0;
        for (final VillagerTradeEntry entry : villagers)
        {
            count += entry.offers().size();
        }
        return count;
    }

    public VillagerTradeEntry getVillagerForOffer(final int flatIndex)
    {
        final int[] resolved = resolveOffer(flatIndex);
        return resolved != null ? villagers.get(resolved[0]) : null;
    }

    public TradeOfferData getOffer(final int flatIndex)
    {
        final int[] resolved = resolveOffer(flatIndex);
        return resolved != null ? villagers.get(resolved[0]).offers().get(resolved[1]) : null;
    }

    public List<Integer> getOfferOrder()
    {
        return offerOrder;
    }

    public List<Integer> getActiveOffersInOrder()
    {
        final List<Integer> result = new ArrayList<>();
        final java.util.Set<Integer> active = new java.util.HashSet<>();
        for (int i = 0; i < getTotalOfferCount(); i++)
        {
            if (getMode(i) != CaravanTradeModule.TradeMode.DISABLED)
            {
                active.add(i);
            }
        }
        for (final int idx : offerOrder)
        {
            if (active.contains(idx) && !result.contains(idx))
            {
                result.add(idx);
            }
        }
        for (int i = 0; i < getTotalOfferCount(); i++)
        {
            if (active.contains(i) && !result.contains(i))
            {
                result.add(i);
            }
        }
        return result;
    }

    public String getCustomName(final UUID villagerId)
    {
        return customNames.get(villagerId);
    }

    /**
     * 村民的“模拟等级”：基础等级 + 由模拟经验（xpEarned）换算出的升级数。
     */
    public int getSimulatedLevel(final VillagerTradeEntry entry)
    {
        int level = entry.level();
        // 修复：显示经验包含未结算的模拟经验（pendingXp），升级预览不再滞后。
        int remaining = entry.xpEarned() + entry.pendingXp();
        while (VillagerData.canLevelUp(level) && remaining >= VillagerData.getMaxXpPerLevel(level))
        {
            remaining -= VillagerData.getMaxXpPerLevel(level);
            level++;
        }
        return level;
    }

    /** 某位村民在“平铺索引”中的起始位置（其第一条交易的全局索引）。 */
    public int getVillagerStartFlat(final int villagerIndex)
    {
        int flat = 0;
        for (int vi = 0; vi < villagerIndex && vi < villagers.size(); vi++)
        {
            flat += villagers.get(vi).offers().size();
        }
        return flat;
    }
}
