package com.example.caravan.colony.buildings.moduleviews;

import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;

/** 需求（商队卫兵）：装备请求模块的客户端视图——无 GUI 页面（不显示标签页）。 */
public class CaravanGuardEquipmentModuleView extends AbstractBuildingModuleView
{
    @Override
    public void deserialize(final RegistryFriendlyByteBuf buffer)
    {
        // 无同步数据。
    }

    @Override
    public BOWindow getWindow()
    {
        return null;
    }

    @Override
    public Component getDesc()
    {
        return Component.translatable("com.caravan.gui.guard_equipment");
    }

    @Override
    public boolean isPageVisible()
    {
        return false;
    }
}
