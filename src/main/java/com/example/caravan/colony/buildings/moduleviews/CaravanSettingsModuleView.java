package com.example.caravan.colony.buildings.moduleviews;

import com.example.caravan.client.gui.modules.WindowCaravanSettings;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Client view of the caravan hut's "Settings" tab (the Get Tool button).
 */
public class CaravanSettingsModuleView extends AbstractBuildingModuleView
{
    @Override
    public void deserialize(final RegistryFriendlyByteBuf buffer)
    {
        // 该标签页（选区工具）不携带任何同步数据。
    }

    @Override
    public BOWindow getWindow()
    {
        return new WindowCaravanSettings(getBuildingView(), this);
    }

    @Override
    public Component getDesc()
    {
        // 需求：原【设置】页面更名为【选区工具】页面。
        return Component.translatable("com.caravan.gui.scepter");
    }

    @Override
    public ResourceLocation getIconResourceLocation()
    {
        // 需求：标签页图标更换为【卫兵塔】的【选取工具】标签页图标（scepter.png）。
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/scepter.png");
    }
}
