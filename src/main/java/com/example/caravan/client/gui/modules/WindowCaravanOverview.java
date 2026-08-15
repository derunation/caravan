package com.example.caravan.client.gui.modules;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.example.caravan.colony.buildings.moduleviews.CaravanTradeListModuleView;
import com.example.caravan.colony.buildings.modules.TradeOfferData;
import com.example.caravan.colony.buildings.modules.VillagerTradeEntry;
import com.example.caravan.network.CaravanTradeOrderMessage;
import com.ldtteam.blockui.Alignment;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ItemIcon;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.BOWindow;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 商队小屋【交易列表】→【总览】窗口：
 * 以表格形式列出所有已激活交易（默认按距离从上到下排序），只读展示，
 * 玩家可通过每行右侧的上下箭头调整交易执行顺序；
 * 【按需】交易按此顺序分配给请求系统。
 */
public class WindowCaravanOverview extends BOWindow
{
    private static final String LIST_OVERVIEW = "overview";
    private static final String BUTTON_UP = "moveUp";
    private static final String BUTTON_DOWN = "moveDown";
    private static final String BUTTON_BACK = "backButton";

    private final IBuildingView buildingView;
    private final CaravanTradeListModuleView moduleView;
    private int lastSignature = Integer.MIN_VALUE;
    private boolean forceRefresh = true;

    public WindowCaravanOverview(final IBuildingView buildingView, final CaravanTradeListModuleView moduleView)
    {
        super(ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "gui/layouthuts/layoutcaravanoverview.xml"));
        this.buildingView = buildingView;
        this.moduleView = moduleView;
    }

    @Override
    public void onOpened()
    {
        super.onOpened();
        final Button back = findPaneOfTypeByID(BUTTON_BACK, Button.class);
        if (back != null)
        {
            back.setHandler(button -> close());
        }
        forceRefresh = true;
        refresh();
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate();
        refresh();
    }

    private void refresh()
    {
        final int signature = signature();
        if (!forceRefresh && signature == lastSignature)
        {
            return;
        }
        forceRefresh = false;
        lastSignature = signature;

        final ScrollingList list = findPaneOfTypeByID(LIST_OVERVIEW, ScrollingList.class);
        if (list == null)
        {
            return;
        }
        final List<Integer> offers = moduleView.getActiveOffersInOrder();
        list.setDataProvider(new ScrollingList.DataProvider()
        {
            @Override
            public int getElementCount()
            {
                return offers.size();
            }

            @Override
            public void updateElement(final int index, final Pane rowPane)
            {
                final int flatIndex = offers.get(index);
                final TradeOfferData offer = moduleView.getOffer(flatIndex);
                final VillagerTradeEntry villager = moduleView.getVillagerForOffer(flatIndex);
                if (offer == null)
                {
                    return;
                }
                final ItemIcon costIcon = rowPane.findPaneOfTypeByID("costIcon", ItemIcon.class);
                final ItemStack cost = !offer.costs().isEmpty() ? offer.costs().get(0) : ItemStack.EMPTY;
                costIcon.setVisible(!cost.isEmpty());
                costIcon.setItem(cost.copy());
                final ItemIcon icon = rowPane.findPaneOfTypeByID("resultIcon", ItemIcon.class);
                icon.setVisible(true);
                icon.setItem(offer.result().copy());
                final Text info = rowPane.findPaneOfTypeByID("offerInfo", Text.class);
                // 否则该文本会表现为居中对齐）。
                info.setTextAlignment(Alignment.MIDDLE_LEFT);
                info.setText(Component.translatable("com.caravan.gui.overview.status",
                    Component.translatable(modeKey(moduleView.getMode(flatIndex))),
                    locationLabel(villager)));
                rowPane.findPaneOfTypeByID(BUTTON_UP, Button.class)
                    .setHandler(button -> move(flatIndex, true));
                rowPane.findPaneOfTypeByID(BUTTON_DOWN, Button.class)
                    .setHandler(button -> move(flatIndex, false));
            }
        });
    }

    private int signature()
    {
        int hash = moduleView.getTotalOfferCount();
        for (final int idx : moduleView.getActiveOffersInOrder())
        {
            hash = hash * 31 + idx;
        }
        hash = hash * 31 + moduleView.getOfferOrder().size();
        for (int i = 0; i < moduleView.getTotalOfferCount(); i++)
        {
            hash = hash * 31 + moduleView.getMode(i).ordinal();
        }
        return hash;
    }

    private static String modeKey(final CaravanTradeModule.TradeMode mode)
    {
        return switch (mode)
        {
            case SINGLE -> "com.caravan.gui.trades.mode.single";
            case ON_DEMAND -> "com.caravan.gui.trades.mode.ondemand";
            case REPEAT -> "com.caravan.gui.trades.mode.repeat";
            case DISABLED -> "com.caravan.gui.trades.mode.disabled";
        };
    }

    /** 位置信息：有 Waystone 名称显示名称，否则显示与小屋的距离。 */
    private Component locationLabel(final VillagerTradeEntry villager)
    {
        if (villager != null
            && villager.waystoneName() != null
            && !villager.waystoneName().isEmpty()
            && !villager.waystoneName().equals(VillagerTradeEntry.WAYSTONE_UNNAMED))
        {
            return Component.literal(villager.waystoneName());
        }
        final int distance = villager != null
            ? (int) Math.round(Math.sqrt(Math.max(1.0, buildingView.getID().distSqr(villager.workstationPos()))))
            : 0;
        return Component.translatable("com.caravan.gui.trades.distance", distance);
    }

    private void move(final int flatIndex, final boolean up)
    {
        new CaravanTradeOrderMessage(buildingView, flatIndex, up).sendToServer();
    }
}
