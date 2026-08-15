package com.example.caravan.colony.buildings.moduleviews;

import com.example.caravan.client.gui.modules.WindowCaravanStock;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 商队小屋【设置】标签页的客户端视图：保存并显示最小绿宝石库存与帐篷携带量。
 */
public class CaravanStockModuleView extends AbstractBuildingModuleView
{
    private int minEmeraldStock = 4;
    private int tentCarryCount = 1;
    private int foodCarryCount = 2;
    private int torchCarryCount = 2;

    /** 查询当前最小绿宝石库存（默认 4）。 */
    public int getMinEmeraldStock()
    {
        return minEmeraldStock;
    }

    public int getTentCarryCount()
    {
        return tentCarryCount;
    }

    public int getFoodCarryCount()
    {
        return foodCarryCount;
    }

    public int getTorchCarryCount()
    {
        return torchCarryCount;
    }

    @Override
    public void deserialize(final RegistryFriendlyByteBuf buffer)
    {
        minEmeraldStock = buffer.readVarInt();
        tentCarryCount = buffer.readVarInt();
        foodCarryCount = buffer.readVarInt();
        torchCarryCount = buffer.readVarInt();
    }

    @Override
    public BOWindow getWindow()
    {
        return new WindowCaravanStock(getBuildingView(), this);
    }

    @Override
    public Component getDesc()
    {
        return Component.translatable("com.caravan.gui.settings");
    }

    @Override
    public ResourceLocation getIconResourceLocation()
    {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/settings.png");
    }
}
