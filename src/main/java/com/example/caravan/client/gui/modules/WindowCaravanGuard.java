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

/** 需求（商队护卫）：商队小屋【护卫】页——列出商队护卫模式卫兵，可选中/取消。 */
public class WindowCaravanGuard extends AbstractModuleWindow<CaravanGuardModuleView>
{
    private static final String LIST_GUARDS = "guards";

    private final ScrollingList guardList;
    private final Text emptyText;

    public WindowCaravanGuard(final IBuildingView buildingView, final CaravanGuardModuleView moduleView)
    {
        super(moduleView, ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "gui/layouthuts/layoutcaravanguard.xml"));
        this.guardList = findPaneOfTypeByID(LIST_GUARDS, ScrollingList.class);
        this.emptyText = findPaneOfTypeByID("guardEmpty", Text.class);
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

    private void updateGuards()
    {
        final java.util.List<GuardEntry> guards = moduleView.getGuards();
        if (guards.isEmpty())
        {
            emptyText.setVisible(true);
            emptyText.setText(Component.translatable("com.caravan.gui.guard.empty"));
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
                final Button button = rowPane.findPaneOfTypeByID("guardButton", Button.class);
                button.setText(Component.translatable(entry.assigned()
                    ? "com.caravan.gui.guard.unassign"
                    : "com.caravan.gui.guard.assign"));
                button.setHandler(btn -> new CaravanGuardAssignMessage(
                    buildingView, entry.citizenId(), !entry.assigned()).sendToServer());
            }
        });
    }
}
