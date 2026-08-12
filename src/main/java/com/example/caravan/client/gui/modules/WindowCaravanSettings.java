package com.example.caravan.client.gui.modules;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.moduleviews.CaravanSettingsModuleView;
import com.example.caravan.network.CaravanGetToolMessage;
import com.example.caravan.network.CaravanCloseGuiMessage;
import com.example.caravan.network.CaravanRefreshBuildingMessage;
import com.ldtteam.blockui.controls.Button;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.minecraft.resources.ResourceLocation;

/**
 * Settings tab of the caravan hut: currently hosts the "Get Tool" button which
 * gives the player a Caravan Marker bound to this hut.
 */
public class WindowCaravanSettings extends AbstractModuleWindow<CaravanSettingsModuleView>
{
    private static final String BUTTON_GET_TOOL = "getTool";

    public WindowCaravanSettings(final IBuildingView buildingView, final CaravanSettingsModuleView moduleView)
    {
        super(moduleView, ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "gui/layouthuts/layoutcaravansettings.xml"));
        registerButton(BUTTON_GET_TOOL, this::getToolClicked);
    }

    @Override
    public void onOpened()
    {
        super.onOpened();
        // 需求1：每次打开小屋 GUI 时请求服务器刷新建筑视图。
        new CaravanRefreshBuildingMessage(buildingView).sendToServer();
    }

    @Override
    public void onClosed()
    {
        super.onClosed();
        // 需求2：关闭 GUI 时若领袖在等待物品，则清空其请求重新备货。
        new CaravanCloseGuiMessage(buildingView).sendToServer();
    }

    private void getToolClicked(final Button button)
    {
        new CaravanGetToolMessage(buildingView).sendToServer();
    }
}
