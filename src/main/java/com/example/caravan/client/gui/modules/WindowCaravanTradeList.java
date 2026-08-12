package com.example.caravan.client.gui.modules;

import com.example.caravan.CaravanMod;
import com.example.caravan.client.gui.WindowCaravanRenameVillager;
import com.example.caravan.colony.buildings.moduleviews.CaravanTradeListModuleView;
import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.example.caravan.colony.buildings.modules.TradeOfferData;
import com.example.caravan.colony.buildings.modules.VillagerTradeEntry;
import com.example.caravan.network.CaravanRefreshBuildingMessage;
import com.example.caravan.network.CaravanCloseGuiMessage;
import com.example.caravan.network.CaravanDeleteVillagerMessage;
import com.example.caravan.network.CaravanTradeQuantityMessage;
import com.example.caravan.network.CaravanTradeModeMessage;
import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ItemIcon;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.views.ScrollingList;
import com.ldtteam.blockui.views.View;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.core.client.gui.AbstractModuleWindow;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerTrades.ItemListing;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 商队小屋【交易列表】标签页（两级页面）：
 * <ul>
 *   <li>一级：村民列表——每位村民显示 坐标 职业 等级 与经验；
 *       有激活（非禁用）交易的村民用“交易”文字标记区分；</li>
 *   <li>点击村民进入二级页面：该村民的全部交易（物品图标、模式按钮、数量按钮）
 *       与升级预览，并提供【返回】按钮回到村民列表；</li>
 *   <li>经验显示包含未结算的模拟经验（pendingXp）。</li>
 * </ul>
 */
public class WindowCaravanTradeList extends AbstractModuleWindow<CaravanTradeListModuleView>
{
    private static final String PAGE_VILLAGERS = "villagerPage";
    private static final String PAGE_TRADES = "tradePage";
    private static final String LIST_VILLAGERS = "villagers";
    private static final String LIST_TRADES = "trades";
    private static final String BUTTON_VILLAGER = "villagerSelect";
    private static final String BUTTON_BACK = "backButton";
    private static final String BUTTON_DELETE = "deleteButton";
    private static final String BUTTON_RENAME = "renameButton";
    private static final String BUTTON_SELECT = "select";
    private static final String BUTTON_QTY_UP = "qtyUp";
    private static final String BUTTON_QTY_DOWN = "qtyDown";
    private static final String BUTTON_OVERVIEW = "overviewButton";

    /** 升级预览中的一行：解锁等级 + 生成的交易内容。 */
    private record PreviewTrade(int level, TradeOfferData offer)
    {
    }

    private final ScrollingList villagerList;
    private final ScrollingList tradeList;
    private final List<PreviewTrade> previewTrades = new ArrayList<>();
    /** 当前打开的村民下标（-1 = 一级村民列表页）。 */
    private int selectedVillager = -1;
    private int lastVillagerHash = -1;
    private int lastOfferCount = -1;
    private int lastModesHash = -1;
    private int lastXpHash = -1;
    private int lastNamesHash = -1;

    public WindowCaravanTradeList(final IBuildingView buildingView, final CaravanTradeListModuleView moduleView)
    {
        super(moduleView, ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "gui/layouthuts/layoutcaravantrades.xml"));
        this.villagerList = findPaneOfTypeByID(LIST_VILLAGERS, ScrollingList.class);
        this.tradeList = findPaneOfTypeByID(LIST_TRADES, ScrollingList.class);
        findPaneOfTypeByID(BUTTON_BACK, Button.class).setHandler(button -> backToVillagers());
        // 需求：删除当前村民及其全部交易，并返回村民列表主页面。
        findPaneOfTypeByID(BUTTON_DELETE, Button.class).setHandler(button -> deleteVillager());
        // 需求：重命名当前村民——弹出输入框，修改后替换职业文本显示。
        findPaneOfTypeByID(BUTTON_RENAME, Button.class).setHandler(button -> renameVillager());
        // 需求（总览）：右下角【总览】按钮——打开激活交易顺序窗口。
        findPaneOfTypeByID(BUTTON_OVERVIEW, Button.class).setHandler(button -> openOverview());
    }

    @Override
    public void onOpened()
    {
        super.onOpened();
        // 每次打开小屋 GUI 时请求服务器刷新建筑视图。
        new CaravanRefreshBuildingMessage(buildingView).sendToServer();
        selectedVillager = -1;
        showVillagerPage();
        updateOverviewButton();
        updateVillagers();
    }

    @Override
    public void onClosed()
    {
        super.onClosed();
        // 关闭 GUI 时若领袖在等待物品，则清空其请求重新备货。
        new CaravanCloseGuiMessage(buildingView).sendToServer();
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate();
        // 需求（总览）：无激活交易时【总览】按钮置灰；有激活交易时恢复可点。
        updateOverviewButton();
        // 服务器数据变化后（建筑视图同步），刷新当前页。
        if (selectedVillager < 0)
        {
            if (lastVillagerHash != villagersHash())
            {
                updateVillagers();
            }
        }
        else if (lastNamesHash != namesHash())
        {
            // 需求：重命名后即时刷新村民信息页标题（主页面由 villagersHash 刷新）。
            lastNamesHash = namesHash();
            refreshVillagerTitle();
        }
        else if (lastOfferCount != moduleView.getTotalOfferCount()
            || lastModesHash != modesHash()
            || lastXpHash != xpHash())
        {
            updateTrades();
        }
    }

    /** 需求（总览）：无激活交易时【总览】按钮置灰。 */
    private void updateOverviewButton()
    {
        final Button overview = findPaneOfTypeByID(BUTTON_OVERVIEW, Button.class);
        if (overview != null)
        {
            overview.setEnabled(!moduleView.getActiveOffersInOrder().isEmpty());
        }
    }

    private void showVillagerPage()
    {
        findPaneOfTypeByID(PAGE_VILLAGERS, View.class).setVisible(true);
        findPaneOfTypeByID(PAGE_TRADES, View.class).setVisible(false);
    }

    private void showTradePage()
    {
        findPaneOfTypeByID(PAGE_VILLAGERS, View.class).setVisible(false);
        findPaneOfTypeByID(PAGE_TRADES, View.class).setVisible(true);
    }

    /** 需求：【删除】按钮——从小屋中删除当前村民及其所有交易，返回主页面。 */
    private void deleteVillager()
    {
        if (selectedVillager < 0 || selectedVillager >= moduleView.getVillagers().size())
        {
            return;
        }
        final VillagerTradeEntry villager = moduleView.getVillagers().get(selectedVillager);
        new CaravanDeleteVillagerMessage(buildingView, villager.villagerId()).sendToServer();
        backToVillagers();
    }

    /** 需求：【重命名】按钮——弹出输入框，修改村民自定义名称。 */
    private void renameVillager()
    {
        if (selectedVillager < 0 || selectedVillager >= moduleView.getVillagers().size())
        {
            return;
        }
        final VillagerTradeEntry villager = moduleView.getVillagers().get(selectedVillager);
        final String current = moduleView.getCustomName(villager.villagerId());
        new WindowCaravanRenameVillager(buildingView, villager.villagerId(), current).openAsLayer();
    }

    /** 需求（总览）：打开激活交易顺序窗口（以图层方式弹出）。 */
    private void openOverview()
    {
        new WindowCaravanOverview(buildingView, moduleView).openAsLayer();
    }

    // ==================== 一级：村民列表 ====================

    private void updateVillagers()
    {
        lastVillagerHash = villagersHash();

        villagerList.setDataProvider(new ScrollingList.DataProvider()
        {
            @Override
            public int getElementCount()
            {
                return moduleView.getVillagers().size();
            }

            @Override
            public void updateElement(final int index, final Pane rowPane)
            {
                final VillagerTradeEntry villager = moduleView.getVillagers().get(index);
                final boolean hasActive = hasActiveTrades(index);
                // 需求：第一行从左到右：职业/名称  距离/传送石碑名称。
                final Text info = rowPane.findPaneOfTypeByID("villagerInfo", Text.class);
                info.setText(
                    Component.translatable("com.caravan.gui.trades.villager_name_dist",
                        villagerLabel(villager), locationLabel(villager)));
                // 需求（大师级）：村民达到大师级（满级）时不显示经验进度与附魔瓶图标。
                final Text xp = rowPane.findPaneOfTypeByID("villagerXp", Text.class);
                final ItemIcon levelIcon = rowPane.findPaneOfTypeByID("levelIcon", ItemIcon.class);
                levelIcon.setEnabled(false);
                if (!VillagerData.canLevelUp(villager.level()))
                {
                    xp.setText(Component.translatable("merchant.level." + villager.level()));
                    levelIcon.setVisible(false);
                    levelIcon.setItem(ItemStack.EMPTY);
                }
                else
                {
                    // 需求：第二行合并等级与经验进度（示例：新手 8/10）。
                    final int[] progress = xpProgress(villager);
                    xp.setText(
                        Component.translatable("com.caravan.gui.trades.level_xp",
                            Component.translatable("merchant.level." + villager.level()),
                            progress[0], progress[1]));
                    // 需求：经验已满时在绿宝石左侧显示附魔之瓶（悬停提示见 XML tooltip）。
                    final boolean maxed = isXpMaxed(villager);
                    levelIcon.setVisible(maxed);
                    levelIcon.setItem(maxed ? new ItemStack(Items.EXPERIENCE_BOTTLE) : ItemStack.EMPTY);
                }
                // 需求：有激活交易的村民以 MC 原版绿宝石图标代替“交易”文本。
                final ItemIcon activeIcon = rowPane.findPaneOfTypeByID("activeIcon", ItemIcon.class);
                activeIcon.setEnabled(false);
                activeIcon.setVisible(hasActive);
                activeIcon.setItem(hasActive ? new ItemStack(Items.EMERALD) : ItemStack.EMPTY);
                // 需求：整行可点击——文字/图标面板若保持可点会拦截按钮点击（BlockUI 中
                // 顶层子元素优先接收点击），因此禁用文字并统一为黑色渲染。
                makeTextNonClickable(info);
                makeTextNonClickable(xp);
                // 行按钮：进入该村民的交易页。
                rowPane.findPaneOfTypeByID(BUTTON_VILLAGER, Button.class)
                    .setHandler(button -> openVillager(index));
            }
        });
    }

    /** 禁用文字的点击能力（isEnabled=false 后 canHandleClick 返回 false），
     *  并固定为黑色渲染（禁用色/悬停色/普通色全设为黑色）。 */
    private static void makeTextNonClickable(final Text text)
    {
        text.setEnabled(false);
        text.setColors(0xFF000000, 0xFF000000, 0xFF000000);
    }

    /** 需求：村民位置显示——目标 100 格内有【已激活】的 Waystone 才显示名称，否则显示小屋距离。 */
    private Component locationLabel(final VillagerTradeEntry villager)
    {
        if (villager.waystoneUid() != null && isWaystoneActivatedClient(villager.waystoneUid()))
        {
            // 需求：未命名 Waystone 用占位标记，本地化显示“传送石碑/Waystone”。
            if (villager.waystoneName().equals(VillagerTradeEntry.WAYSTONE_UNNAMED))
            {
                return Component.translatable("com.caravan.gui.trades.waystone");
            }
            return Component.literal(villager.waystoneName());
        }
        final int distance = (int) Math.round(Math.sqrt(
            Math.max(1.0, buildingView.getID().distSqr(villager.workstationPos()))));
        return Component.translatable("com.caravan.gui.trades.distance", distance);
    }

    /**
     * 需求：未激活的传送石碑不显示名称——按当前玩家的“已激活 Waystone”列表（UUID）判断。
     * 该数据由 Waystones 模组同步到客户端（其自身的传送界面也依赖它）。
     */
    private static boolean isWaystoneActivatedClient(final UUID waystoneUid)
    {
        try
        {
            final var player = Minecraft.getInstance().player;
            if (player == null)
            {
                return false;
            }
            return WaystonesAPI.getActivatedWaystones(player).stream()
                .anyMatch(waystone -> waystone.getWaystoneUid().equals(waystoneUid));
        }
        catch (final Throwable ignored)
        {
            // Waystones 未安装或查询失败：不显示名称（按距离显示）。
            return false;
        }
    }

    /** 该村民是否含有激活（非禁用）的交易。 */
    private boolean hasActiveTrades(final int villagerIndex)
    {
        final int start = moduleView.getVillagerStartFlat(villagerIndex);
        final VillagerTradeEntry villager = moduleView.getVillagers().get(villagerIndex);
        for (int i = 0; i < villager.offers().size(); i++)
        {
            if (moduleView.getMode(start + i) != CaravanTradeModule.TradeMode.DISABLED)
            {
                return true;
            }
        }
        return false;
    }

    private void openVillager(final int villagerIndex)
    {
        selectedVillager = villagerIndex;
        refreshVillagerTitle();
        showTradePage();
        updateTrades();
    }

    /** 刷新村民信息页标题（坐标 名称/职业 等级）。 */
    private void refreshVillagerTitle()
    {
        if (selectedVillager < 0 || selectedVillager >= moduleView.getVillagers().size())
        {
            return;
        }
        final VillagerTradeEntry villager = moduleView.getVillagers().get(selectedVillager);
        // 需求：初始文本分两行——第一行职业/名称，第二行坐标。
        findPaneOfTypeByID("pageTitle", Text.class).setText(villagerLabel(villager));
        findPaneOfTypeByID("pageSubTitle", Text.class).setText(
            Component.translatable("com.caravan.gui.trades.coords",
                villager.workstationPos().getX(),
                villager.workstationPos().getY(),
                villager.workstationPos().getZ()));
    }

    /** 需求：村民显示名——有自定义名称时显示名称，否则显示职业文本。 */
    private Component villagerLabel(final VillagerTradeEntry villager)
    {
        final String customName = moduleView.getCustomName(villager.villagerId());
        if (customName != null && !customName.isEmpty())
        {
            return Component.literal(customName);
        }
        return Component.translatable("entity.minecraft.villager." + professionPathOf(villager.profession()));
    }

    private void backToVillagers()
    {
        selectedVillager = -1;
        showVillagerPage();
        updateVillagers();
    }

    // ==================== 二级：单个村民的交易 ====================

    private void updateTrades()
    {
        lastOfferCount = moduleView.getTotalOfferCount();
        lastModesHash = modesHash();
        lastXpHash = xpHash();
        buildPreviewTrades();

        tradeList.setDataProvider(new ScrollingList.DataProvider()
        {
            @Override
            public int getElementCount()
            {
                if (selectedVillager < 0 || selectedVillager >= moduleView.getVillagers().size())
                {
                    return 0;
                }
                return moduleView.getVillagers().get(selectedVillager).offers().size() + previewTrades.size();
            }

            @Override
            public void updateElement(final int index, final Pane rowPane)
            {
                if (index < moduleView.getVillagers().get(selectedVillager).offers().size())
                {
                    updateRealRow(index, rowPane);
                }
                else
                {
                    updatePreviewRow(index - moduleView.getVillagers().get(selectedVillager).offers().size(), rowPane);
                }
            }
        });
    }

    /** 真实交易行：物品图标 + 模式按钮 + 数量按钮（不再显示村民信息）。 */
    private void updateRealRow(final int index, final Pane rowPane)
    {
        final VillagerTradeEntry villager = moduleView.getVillagers().get(selectedVillager);
        final TradeOfferData offer = villager.offers().get(index);
        final int flatIndex = moduleView.getVillagerStartFlat(selectedVillager) + index;

        fillIcons(rowPane, offer);

        final Button select = rowPane.findPaneOfTypeByID(BUTTON_SELECT, Button.class);
        final boolean blocked = moduleView.getMode(flatIndex) == CaravanTradeModule.TradeMode.DISABLED
            && moduleView.getNonDisabledCount() >= moduleView.getMaxSelection();
        select.setEnabled(!blocked);
        select.setText(Component.translatable(modeKey(moduleView.getMode(flatIndex))));
        // 按钮处理器改为“行内绑定”（捕获当前行索引），
        // 避免 BlockUI 列表行复用导致的按钮事件只对部分行生效的问题。
        select.setHandler(button -> modeClicked(flatIndex));

        // 数量选择模块始终可用（不再灰化）——超出范围时由服务器/本地钳制到 1..maxUses。
        final int quantity = moduleView.getQuantity(flatIndex);
        // 需求（按需交易）：按需模式数量由请求缺口自动决定，显示“自动”并禁用数量按钮。
        final boolean onDemand = moduleView.getMode(flatIndex) == CaravanTradeModule.TradeMode.ON_DEMAND;
        rowPane.findPaneOfTypeByID("qty", Text.class).setText(onDemand
            ? Component.translatable("com.caravan.gui.trades.qty.auto")
            : Component.literal("x" + quantity));
        final Button qtyUp = rowPane.findPaneOfTypeByID(BUTTON_QTY_UP, Button.class);
        qtyUp.setEnabled(!onDemand);
        qtyUp.setHandler(button -> changeQuantity(flatIndex, 1));
        final Button qtyDown = rowPane.findPaneOfTypeByID(BUTTON_QTY_DOWN, Button.class);
        qtyDown.setEnabled(!onDemand);
        qtyDown.setHandler(button -> changeQuantity(flatIndex, -1));
    }

    /** 升级预览行：模拟村民升级后解锁的交易（不可选择）。 */
    private void updatePreviewRow(final int previewIndex, final Pane rowPane)
    {
        final PreviewTrade preview = previewTrades.get(previewIndex);

        fillIcons(rowPane, preview.offer());

        final Button select = rowPane.findPaneOfTypeByID(BUTTON_SELECT, Button.class);
        select.setEnabled(false);
        select.setText(Component.translatable("com.caravan.gui.trades.unlockable_lv", preview.level()));
        rowPane.findPaneOfTypeByID("qty", Text.class).setText(Component.literal(""));
        rowPane.findPaneOfTypeByID(BUTTON_QTY_UP, Button.class).setEnabled(false);
        rowPane.findPaneOfTypeByID(BUTTON_QTY_DOWN, Button.class).setEnabled(false);
        // 预览行按钮清空处理器，避免复用行残留上一行的点击逻辑。
        rowPane.findPaneOfTypeByID(BUTTON_SELECT, Button.class).setHandler(button -> {
        });
        rowPane.findPaneOfTypeByID(BUTTON_QTY_UP, Button.class).setHandler(button -> {
        });
        rowPane.findPaneOfTypeByID(BUTTON_QTY_DOWN, Button.class).setHandler(button -> {
        });
    }

    private void fillIcons(final Pane rowPane, final TradeOfferData offer)
    {
        setIcon(rowPane.findPaneOfTypeByID("costA", ItemIcon.class), offer.costA());
        setIcon(rowPane.findPaneOfTypeByID("costB", ItemIcon.class), offer.costB());
        setIcon(rowPane.findPaneOfTypeByID("result", ItemIcon.class), offer.result());
    }

    /**
     * 生成“升级后可解锁”的预览交易：对所选村民，按原版交易池为每个新等级
     * 随机抽取交易（复刻 updateTrades 的每级 +2 个交易）。
     */
    private void buildPreviewTrades()
    {
        previewTrades.clear();
        if (selectedVillager < 0 || selectedVillager >= moduleView.getVillagers().size())
        {
            return;
        }
        final VillagerTradeEntry entry = moduleView.getVillagers().get(selectedVillager);
        final int simulated = moduleView.getSimulatedLevel(entry);
        for (int level = entry.level() + 1; level <= simulated; level++)
        {
            for (final TradeOfferData offer : generateOffersForLevel(entry.profession(), level))
            {
                previewTrades.add(new PreviewTrade(level, offer));
            }
        }
    }

    /**
     * 客户端模拟：用“脱离世界”的村民实体 + 原版交易池生成指定等级的交易预览。
     * 任何异常均静默降级（返回空列表）。
     */
    private static List<TradeOfferData> generateOffersForLevel(final String professionId, final int level)
    {
        try
        {
            final ClientLevel clientLevel = Minecraft.getInstance().level;
            if (clientLevel == null)
            {
                return List.of();
            }
            final Villager villager = EntityType.VILLAGER.create(clientLevel);
            if (villager == null)
            {
                return List.of();
            }
            final VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.get(ResourceLocation.parse(professionId));
            villager.setVillagerData(new VillagerData(VillagerType.PLAINS, profession, level));
            final MerchantOffers offers = new MerchantOffers();
            final ItemListing[] listings = CaravanTradeModule.tradePoolFor(villager, level);
            if (listings != null)
            {
                CaravanTradeModule.addOffersFromListings(villager, offers, listings, 2);
            }
            final List<TradeOfferData> result = new ArrayList<>();
            for (final MerchantOffer offer : offers)
            {
                result.add(new TradeOfferData(
                    offer.getCostA().copy(),
                    offer.getCostB().copy(),
                    offer.getResult().copy(),
                    offer.getMaxUses(),
                    offer.getXp()));
            }
            return result;
        }
        catch (final Exception ignored)
        {
            return List.of();
        }
    }

    private static String professionPathOf(final String profession)
    {
        return profession.contains(":") ? profession.substring(profession.indexOf(':') + 1) : profession;
    }

    /** 交易执行模式 → 本地化键。 */
    private static String modeKey(final CaravanTradeModule.TradeMode mode)
    {
        return switch (mode)
        {
            case DISABLED -> "com.caravan.gui.trades.mode.disabled";
            case SINGLE -> "com.caravan.gui.trades.mode.single";
            case REPEAT -> "com.caravan.gui.trades.mode.repeat";
            case ON_DEMAND -> "com.caravan.gui.trades.mode.ondemand";
        };
    }

    /** 当前等级经验进度 {已获得, 本级所需}（包含未结算的模拟经验）。 */
    private static int[] xpProgress(final VillagerTradeEntry villager)
    {
        final int xp = villager.xpEarned() + villager.pendingXp();
        final int levelNo = villager.level();
        final boolean canUp = VillagerData.canLevelUp(levelNo);
        final int minXp = canUp ? VillagerData.getMinXpPerLevel(levelNo) : 0;
        final int maxXp = canUp ? VillagerData.getMaxXpPerLevel(levelNo) : Math.max(1, xp);
        final int shownXp = Math.max(0, xp - minXp);
        final int needXp = Math.max(1, maxXp - minXp);
        return new int[] {shownXp, needXp};
    }

    /** 经验是否已满（当前等级经验达到上限或已满级）。 */
    private static boolean isXpMaxed(final VillagerTradeEntry villager)
    {
        final int xp = villager.xpEarned() + villager.pendingXp();
        final int levelNo = villager.level();
        return !VillagerData.canLevelUp(levelNo) || xp >= VillagerData.getMaxXpPerLevel(levelNo);
    }

    private static void setIcon(final ItemIcon icon, final ItemStack stack)
    {
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

    private void modeClicked(final int flatIndex)
    {
        new CaravanTradeModeMessage(buildingView, flatIndex).sendToServer();
        // 本地先循环切换模式，界面即时响应（服务器后续同步校正）。
        cycleLocalMode(flatIndex);
        updateTrades();
    }

    private void changeQuantity(final int flatIndex, final int delta)
    {
        final int target = moduleView.getQuantity(flatIndex) + delta;
        new CaravanTradeQuantityMessage(buildingView, flatIndex, target).sendToServer();
        moduleView.setQuantityLocal(flatIndex, target);
        updateTrades();
    }

    /** 本地观感切换：禁用 → 单次 → 重复 → 禁用。 */
    private void cycleLocalMode(final int flatIndex)
    {
        final CaravanTradeModule.TradeMode next = switch (moduleView.getMode(flatIndex))
        {
            case DISABLED -> CaravanTradeModule.TradeMode.SINGLE;
            case SINGLE -> CaravanTradeModule.TradeMode.ON_DEMAND;
            case ON_DEMAND -> CaravanTradeModule.TradeMode.REPEAT;
            case REPEAT -> CaravanTradeModule.TradeMode.DISABLED;
        };
        moduleView.cycleModeLocal(flatIndex, next);
    }

    /** 所有交易条目模式的简单校验值，用于检测服务器同步变化。 */
    private int modesHash()
    {
        int hash = 0;
        for (int i = 0; i < moduleView.getTotalOfferCount(); i++)
        {
            hash = hash * 31 + moduleView.getMode(i).ordinal();
        }
        return hash;
    }

    /** 村民经验校验值（含未结算的模拟经验），用于检测升级/重录变化。 */
    private int xpHash()
    {
        int hash = 0;
        for (final VillagerTradeEntry entry : moduleView.getVillagers())
        {
            hash = hash * 31 + entry.xpEarned();
            hash = hash * 31 + entry.pendingXp();
        }
        return hash;
    }

    /** 村民自定义名称校验值，用于检测重命名后的界面刷新。 */
    private int namesHash()
    {
        int hash = 0;
        for (final VillagerTradeEntry entry : moduleView.getVillagers())
        {
            final String name = moduleView.getCustomName(entry.villagerId());
            hash = hash * 31 + (name != null ? name.hashCode() : 0);
        }
        return hash;
    }

    /** 一级页面校验值：村民列表内容与激活状态变化时刷新。 */
    private int villagersHash()
    {
        int hash = 0;
        final List<VillagerTradeEntry> list = moduleView.getVillagers();
        for (int i = 0; i < list.size(); i++)
        {
            final VillagerTradeEntry entry = list.get(i);
            hash = hash * 31 + entry.level();
            hash = hash * 31 + entry.xpEarned();
            hash = hash * 31 + entry.pendingXp();
            hash = hash * 31 + entry.offers().size();
            // 需求：Waystone 名称变化时也触发列表刷新（名称/距离切换）。
            hash = hash * 31 + (entry.waystoneUid() != null ? entry.waystoneUid().hashCode() : 0);
            hash = hash * 31 + (entry.waystoneName() != null ? entry.waystoneName().hashCode() : 0);
            // 需求：自定义名称变化时刷新列表。
            final String customName = moduleView.getCustomName(entry.villagerId());
            hash = hash * 31 + (customName != null ? customName.hashCode() : 0);
            hash = hash * 31 + (hasActiveTrades(i) ? 1 : 0);
        }
        return hash;
    }
}
