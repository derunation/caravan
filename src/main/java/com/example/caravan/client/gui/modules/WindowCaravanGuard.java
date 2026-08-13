package com.example.caravan.client.gui.modules;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.moduleviews.CaravanGuardModuleView;
import com.example.caravan.colony.buildings.moduleviews.CaravanGuardModuleView.GuardEntry;
import com.example.caravan.network.CaravanGuardAssignMessage;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 需求（商队护卫）：商队小屋【护卫】页——布局/按钮样式/按钮文字参照
 * 矿井【守卫指派】页（layoutguardlist.xml：guardName + assignGuard 按钮），
 * 列出【商队护卫】模式的卫兵，分配/取消分配作用于其卫兵塔（塔级指派）。
 */
public class WindowCaravanGuard extends AbstractModuleWindow<CaravanGuardModuleView>
{
    private static final String LIST_GUARDS = "guards";

    private final ScrollingList guardList;
    private final Text emptyText;

    public WindowCaravanGuard(final IBuildingView buildingView, final CaravanGuardModuleView moduleView)
    {
        super(moduleView, ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "gui/layouthuts/layoutcaravanguard.xml"));
        this.guardList = findPaneOfTypeByID(LIST_GUARDS, ScrollingList.class);
        this.emptyText = findPaneOfTypeByID("noguardwarning", Text.class);
        registerButton("assignGuard", this::assignGuardClicked);
    }

    @Override
    public void onOpened()
    {
        super.onOpened();
        updateGuards();
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate();
        updateGuards();
    }

    private void assignGuardClicked(final Button button)
    {
        final int row = guardList.getListElementIndexByPane(button);
        final List<GuardEntry> guards = moduleView.getGuards();
        if (row < 0 || row >= guards.size())
        {
            return;
        }
        final GuardEntry entry = guards.get(row);
        new CaravanGuardAssignMessage(buildingView, entry.towerPos(), !entry.assigned()).sendToServer();
    }

    private void updateGuards()
    {
        final List<GuardEntry> guards = moduleView.getGuards();
        if (guards.isEmpty())
        {
            emptyText.setVisible(true);
            guardList.setVisible(false);
            return;
        }
        emptyText.setVisible(false);
        guardList.setVisible(true);
        guardList.setDataProvider(new ScrollingList.DataProvider()
        {
            @Override
            public int getElementCount()
            {
                return guards.size();
            }

            @Override
            public void updateElement(final int index, final Pane rowPane)
            {
                final GuardEntry entry = guards.get(index);
                rowPane.findPaneOfTypeByID("guardName", Text.class)
                    .setText(Component.literal(entry.name()));
                rowPane.findPaneOfTypeByID("assignGuard", Button.class)
                    .setText(Component.translatable(entry.assigned()
                        ? "com.minecolonies.coremod.gui.hiring.buttonunassign"
                        : "com.minecolonies.coremod.gui.hiring.buttonassign"));
            }
        });
    }
}
