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
        return Component.translatable("com.caravan.gui.scepter");
    }

    @Override
    public ResourceLocation getIconResourceLocation()
    {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/scepter.png");
    }
}
