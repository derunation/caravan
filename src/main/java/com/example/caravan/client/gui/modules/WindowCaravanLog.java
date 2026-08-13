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
    /** 需求（消耗品）：日志页单行布局中各消耗品类别的图标槽位数（帐篷-食物-饱食度-火把）。 */
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
        // 需求1：每次打开小屋 GUI 时请求服务器刷新建筑视图（含日志数据）。
        new CaravanRefreshBuildingMessage(buildingView).sendToServer();
        updateLog();
    }

    @Override
    public void onClosed()
    {
        super.onClosed();
        // 需求2：关闭 GUI 时若领袖在等待物品，则清空其请求重新备货。
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
                // 需求：交易中状态显示秒数（1 秒 = 20 游戏刻）。
                case TRADING -> Component.translatable("com.caravan.gui.log.trading", moduleView.getAwayTradeTicks() / 20);
                case RETURNING -> Component.translatable("com.caravan.gui.log.returning", moduleView.getAwayDistance());
            });
        }
        else
        {
            info.setText(Component.translatable("com.caravan.gui.log.inside_colony"));
        }

        // 需求（模拟旅行状态机）：“目前状态”按服务端同步的模拟状态显示。
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

        // 需求（消耗品）：帐篷每顶一个独立图标（含耐久度条）；
        // 食物/火把每个堆叠一个图标（带数量角标）。
        final List<ItemStack> tents = moduleView.getTentStacks();
        updateConsumableIcons(tents, "tentIcon", TENT_ICONS);
        updateConsumableIcons(moduleView.getFoodStacks(), "foodIcon", FOOD_ICONS);
        updateConsumableIcons(moduleView.getTorchStacks(), "torchIcon", TORCH_ICONS);
        // 需求（饥饿）：食物图标右侧显示玩家饱食度图标（与 Minecolonies 公民信息页一致，
        // 使用 minecraft:hud/food_full / hud/food_empty 鸡腿贴图）——
        // 无人饥饿 = 满鸡腿，有人饥饿 = 空鸡腿；Tooltip 显示饿肚子人数。
        final Image saturationIcon = findPaneOfTypeByID("saturationIcon", Image.class);
        if (saturationIcon != null)
        {
            saturationIcon.setVisible(true);
            saturationIcon.setImage(ResourceLocation.withDefaultNamespace(
                moduleView.getHungryCount() > 0 ? "hud/food_empty" : "hud/food_full"), false);
            final Text saturationTooltip = findPaneOfTypeByID("saturationTooltip", Text.class);
            if (saturationTooltip != null)
            {
                saturationTooltip.setText(Component.translatable(
                    moduleView.getHungryCount() > 0
                        ? "com.caravan.gui.log.saturation.hungry"
                        : "com.caravan.gui.log.saturation.none",
                    moduleView.getHungryCount()));
            }
        }
        final Text tentInfo = findPaneOfTypeByID("tentInfo", Text.class);
        final Text consumablesTitle = findPaneOfTypeByID("consumablesTitle", Text.class);
        // 需求（GUI）：取消标题隐藏——无论有无消耗品，“消耗品：”标题始终显示。
        consumablesTitle.setVisible(true);
        if (tents.isEmpty()
            && moduleView.getFoodStacks().isEmpty()
            && moduleView.getTorchStacks().isEmpty())
        {
            // 需求（GUI）：无消耗品时在标题下方显示提示文字。
            tentInfo.setVisible(true);
            tentInfo.setText(Component.translatable("com.caravan.gui.log.consumables.none"));
        }
        else
        {
            // 需求（GUI）：有帐篷时提示文字隐藏。
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
                // 需求（备货供应比例）：商队领袖处于备货/等待出发（未消失）时，
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

    /** 需求（消耗品）：把一类消耗品的堆叠列表渲染到对应前缀的图标槽位。 */
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
        // 需求（饥饿）：饥饿人数变化时刷新饱食度图标。
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
            // 需求（备货供应比例）：供应量变化时刷新界面。
            for (final int supplied : entry.supplied())
            {
                hash = hash * 31 + supplied;
            }
        }
        return hash;
    }
}
