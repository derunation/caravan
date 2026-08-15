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
        new CaravanRefreshBuildingMessage(buildingView).sendToServer();
    }

    @Override
    public void onClosed()
    {
        super.onClosed();
        new CaravanCloseGuiMessage(buildingView).sendToServer();
    }

    private void getToolClicked(final Button button)
    {
        new CaravanGetToolMessage(buildingView).sendToServer();
    }
}
