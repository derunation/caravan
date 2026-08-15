package com.example.caravan.client.gui;

import com.example.caravan.CaravanMod;
import com.example.caravan.network.CaravanRenameVillagerMessage;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ButtonHandler;
import com.ldtteam.blockui.controls.TextField;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 空白名称表示清除自定义名称（恢复显示职业）。
 */
public class WindowCaravanRenameVillager extends BOWindow implements ButtonHandler
{
    private static final int MAX_NAME_LENGTH = 32;

    private final IBuildingView building;
    private final UUID villagerId;

    public WindowCaravanRenameVillager(
        final IBuildingView building,
        final UUID villagerId,
        final String currentName)
    {
        super(ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "gui/windowcaravanrename.xml"));
        this.building = building;
        this.villagerId = villagerId;
        findPaneOfTypeByID("name", TextField.class)
            .setText(currentName == null ? "" : currentName);
    }

    @Override
    public void onButtonClicked(final Button button)
    {
        if (button.getID().equals("done"))
        {
            String name = findPaneOfTypeByID("name", TextField.class).getText();
            if (name.length() > MAX_NAME_LENGTH)
            {
                name = name.substring(0, MAX_NAME_LENGTH);
            }
            new CaravanRenameVillagerMessage(building, villagerId, name).sendToServer();
        }
        close();
    }
}
