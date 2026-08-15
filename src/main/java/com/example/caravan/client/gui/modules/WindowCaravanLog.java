package com.example.caravan.client.gui.modules;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.moduleviews.CaravanLogModuleView;
import com.example.caravan.colony.buildings.moduleviews.CaravanLogModuleView.LogTradeEntry;
import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.example.caravan.network.CaravanRefreshBuildingMessage;
import com.example.caravan.network.CaravanCloseGuiMessage;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Image;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.controls.ItemIcon;
import com.ldtteam.blockui.controls.Tooltip;
import com.ldtteam.blockui.views.ScrollingList;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 商队小屋【日志】标签页：
 * <ul>
 *   <li>未消失 → “在殖民地内”；已消失 → “去程/回程：剩余 X 格”；</li>
 *   <li>目前状态：等待物品 / 准备出发 / 交易中；</li>
 *   <li>交易内容：本次行程的每笔交易（成果物品）分别标记 已完成/未完成。</li>
 * </ul>
 */
public class WindowCaravanLog extends AbstractModuleWindow<CaravanLogModuleView>
{
    private static final String LIST_TRADES = "trades";
    private static final int TENT_ICONS = 3;
    private static final int FOOD_ICONS = 3;
    private static final int TORCH_ICONS = 3;

    private final ScrollingList tradeList;
    private int lastSignature = Integer.MIN_VALUE;

    public WindowCaravanLog(final IBuildingView buildingView, final CaravanLogModuleView moduleView)
    {
        super(moduleView, ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "gui/layouthuts/layoutcaravanlog.xml"));
        this.tradeList = findPaneOfTypeByID(LIST_TRADES, ScrollingList.class);
    }

    @Override
    public void onOpened()
    {
        super.onOpened();
        new CaravanRefreshBuildingMessage(buildingView).sendToServer();
        updateLog();
    }

    @Override
    public void onClosed()
    {
        super.onClosed();
        new CaravanCloseGuiMessage(buildingView).sendToServer();
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate();
        updateLog();
    }

    private void updateLog()
    {
        final int signature = signature();
        if (signature == lastSignature)
        {
            return;
        }
        lastSignature = signature;

        final Text info = findPaneOfTypeByID("logInfo", Text.class);
        if (!moduleView.hasLeader())
        {
            info.setText(Component.translatable("com.caravan.gui.log.no_leader"));
        }
        else if (moduleView.isAway())
        {
            info.setText(switch (moduleView.getAwayPhase())
            {
                case OUTBOUND -> Component.translatable("com.caravan.gui.log.outbound", moduleView.getAwayDistance());
                case TRADING -> Component.translatable("com.caravan.gui.log.trading", moduleView.getAwayTradeTicks() / 20);
                case RETURNING -> Component.translatable("com.caravan.gui.log.returning", moduleView.getAwayDistance());
            });
        }
        else
        {
            info.setText(Component.translatable("com.caravan.gui.log.inside_colony"));
        }

        final String statusKeyText = switch (moduleView.getCampStatus())
        {
            case CAMP -> "com.caravan.gui.log.status.resting";
            case ROUGH -> "com.caravan.gui.log.status.bivouac";
            case NIGHT_TRAVEL -> "com.caravan.gui.log.status.night_travel";
            case TRADING -> "com.caravan.gui.log.status.trading";
            case TRAVEL -> statusKey(moduleView.getStatus());
        };
        findPaneOfTypeByID("statusText", Text.class).setText(Component.translatable(
            "com.caravan.gui.log.status", Component.translatable(statusKeyText)));

        // 食物/火把每个堆叠一个图标（带数量角标）。
        final List<ItemStack> tents = moduleView.getTentStacks();
        updateConsumableIcons(tents, "tentIcon", TENT_ICONS);
        updateConsumableIcons(moduleView.getFoodStacks(), "foodIcon", FOOD_ICONS);
        updateConsumableIcons(moduleView.getTorchStacks(), "torchIcon", TORCH_ICONS);
        final boolean noConsumables = tents.isEmpty()
            && moduleView.getFoodStacks().isEmpty()
            && moduleView.getTorchStacks().isEmpty();
        // 使用 minecraft:hud/food_full / hud/food_empty 鸡腿贴图）——
        // 无人饥饿 = 满鸡腿，有人饥饿 = 空鸡腿；Tooltip 显示饿肚子人数。
        final Image saturationIcon = findPaneOfTypeByID("saturationIcon", Image.class);
        if (saturationIcon != null)
        {
            saturationIcon.setVisible(!noConsumables);
            if (!noConsumables)
            {
                saturationIcon.setImage(ResourceLocation.withDefaultNamespace(
                    moduleView.getHungryCount() > 0 ? "hud/food_empty" : "hud/food_full"), false);
                // 不会被物品图标角标遮挡），替代 onHoverId 隐藏文本方案。
                final Tooltip saturationTooltip = new Tooltip();
                saturationTooltip.setText(Component.translatable(
                    moduleView.getHungryCount() > 0
                        ? "com.caravan.gui.log.saturation.hungry"
                        : "com.caravan.gui.log.saturation.none",
                    moduleView.getHungryCount()));
                saturationIcon.setHoverPane(saturationTooltip);
            }
        }
        final Text tentInfo = findPaneOfTypeByID("tentInfo", Text.class);
        final Text consumablesTitle = findPaneOfTypeByID("consumablesTitle", Text.class);
        consumablesTitle.setVisible(true);
        if (noConsumables)
        {
            tentInfo.setVisible(true);
            tentInfo.setText(Component.translatable("com.caravan.gui.log.consumables.none"));
        }
        else
        {
            tentInfo.setVisible(false);
            tentInfo.setText(Component.literal(""));
        }

        tradeList.setDataProvider(new ScrollingList.DataProvider()
        {
            @Override
            public int getElementCount()
            {
                return moduleView.getTrades().size();
            }

            @Override
            public void updateElement(final int index, final Pane rowPane)
            {
                final LogTradeEntry entry = moduleView.getTrades().get(index);
                // 每笔交易显示“售出品供应 A/B”（A/B 取供应比例最低的瓶颈成本）；
                // 商队出发/交易后恢复显示“已完成 X/Y”。
                final boolean preparing = !moduleView.isAway()
                    && (moduleView.getStatus() == JobCaravanLeader.CaravanStatus.WAITING_ITEMS
                        || moduleView.getStatus() == JobCaravanLeader.CaravanStatus.READY_TO_DEPART);
                if (preparing && !entry.supplied().isEmpty() && !entry.costs().isEmpty())
                {
                    int best = 0;
                    double bestRatio = Double.MAX_VALUE;
                    for (int i = 0; i < entry.costs().size() && i < entry.supplied().size(); i++)
                    {
                        final int needed = entry.costs().get(i).getCount() * entry.total();
                        final double ratio = needed > 0
                            ? (double) entry.supplied().get(i) / needed : 0.0D;
                        if (ratio < bestRatio)
                        {
                            bestRatio = ratio;
                            best = i;
                        }
                    }
                    rowPane.findPaneOfTypeByID("tradeLine", Text.class).setText(
                        Component.translatable("com.caravan.gui.log.supply",
                            entry.supplied().get(best),
                            entry.costs().get(best).getCount() * entry.total()));
                }
                else
                {
                    // 聚合条目：全部完成 → 已完成；否则显示 X/Y完成。
                    final String key = entry.total() > 0 && entry.completed() >= entry.total()
                        ? "com.caravan.gui.log.trade_done"
                        : "com.caravan.gui.log.trade_partial";
                    rowPane.findPaneOfTypeByID("tradeLine", Text.class).setText(
                        Component.translatable(key, entry.completed(), entry.total()));
                }
                // 购入（支付）与售出（获得）物品：图标样式（带数量角标）。
                setIcon(rowPane, "costAIcon", entry.costs().size() > 0 ? entry.costs().get(0) : ItemStack.EMPTY);
                setIcon(rowPane, "costBIcon", entry.costs().size() > 1 ? entry.costs().get(1) : ItemStack.EMPTY);
                setIcon(rowPane, "resultIcon", entry.result());
            }
        });
    }

    private static void setIcon(final Pane rowPane, final String id, final ItemStack stack)
    {
        final ItemIcon icon = rowPane.findPaneOfTypeByID(id, ItemIcon.class);
        if (stack.isEmpty())
        {
            icon.setVisible(false);
            icon.setItem(ItemStack.EMPTY);
        }
        else
        {
            icon.setVisible(true);
            icon.setItem(stack.copy());
        }
    }

    private void updateConsumableIcons(final List<ItemStack> stacks, final String baseId, final int maxIcons)
    {
        for (int i = 0; i < maxIcons; i++)
        {
            final ItemIcon icon = findPaneOfTypeByID(baseId + i, ItemIcon.class);
            if (icon == null)
            {
                continue;
            }
            final boolean visible = i < stacks.size();
            icon.setVisible(visible);
            icon.setItem(visible ? stacks.get(i).copy() : ItemStack.EMPTY);
        }
    }

    private static String statusKey(final JobCaravanLeader.CaravanStatus status)
    {
        return switch (status)
        {
            case WAITING_ITEMS -> "com.caravan.gui.log.status.waiting";
            case READY_TO_DEPART -> "com.caravan.gui.log.status.ready";
            case TRADING -> "com.caravan.gui.log.status.trading";
        };
    }

    /** 数据签名：变化时刷新界面。 */
    private int signature()
    {
        int hash = moduleView.hasLeader() ? 1 : 0;
        hash = hash * 31 + (moduleView.isAway() ? 2 : 0);
        hash = hash * 31 + moduleView.getAwayPhase().ordinal();
        hash = hash * 31 + moduleView.getStatus().ordinal();
        hash = hash * 31 + moduleView.getCampStatus().ordinal();
        for (final ItemStack tent : moduleView.getTentStacks())
        {
            hash = hash * 31 + Item.getId(tent.getItem());
            hash = hash * 31 + tent.getDamageValue();
            hash = hash * 31 + tent.getCount();
        }
        for (final ItemStack food : moduleView.getFoodStacks())
        {
            hash = hash * 31 + Item.getId(food.getItem());
            hash = hash * 31 + food.getCount();
        }
        for (final ItemStack torch : moduleView.getTorchStacks())
        {
            hash = hash * 31 + Item.getId(torch.getItem());
            hash = hash * 31 + torch.getCount();
        }
        hash = hash * 31 + moduleView.getHungryCount();
        hash = hash * 31 + (moduleView.getAwayPhase() == JobCaravanLeader.AwayPhase.TRADING
            ? moduleView.getAwayTradeTicks()
            : moduleView.getAwayDistance());
        for (final LogTradeEntry entry : moduleView.getTrades())
        {
            hash = hash * 31 + entry.completed();
            hash = hash * 31 + entry.total();
            hash = hash * 31 + Item.getId(entry.result().getItem());
            hash = hash * 31 + entry.result().getCount();
            hash = hash * 31 + entry.costs().size();
            for (final ItemStack cost : entry.costs())
            {
                hash = hash * 31 + Item.getId(cost.getItem());
                hash = hash * 31 + cost.getCount();
            }
            for (final int supplied : entry.supplied())
            {
                hash = hash * 31 + supplied;
            }
        }
        return hash;
    }
}
