package com.example.caravan.init;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.CaravanBuildingView;
import com.example.caravan.colony.buildings.moduleviews.CaravanSettingsModuleView;
import com.example.caravan.colony.buildings.moduleviews.CaravanLogModuleView;
import com.example.caravan.colony.buildings.moduleviews.CaravanStockModuleView;
import com.example.caravan.colony.buildings.moduleviews.CaravanTradeListModuleView;
import com.example.caravan.colony.buildings.moduleviews.CaravanGuardModuleView;
import com.example.caravan.colony.buildings.modules.CaravanGuardModule;
import com.example.caravan.colony.buildings.modules.CaravanLogModule;
import com.example.caravan.colony.buildings.modules.CaravanSettingsModule;
import com.example.caravan.colony.buildings.modules.CaravanStockModule;
import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.minecolonies.core.colony.buildings.modules.RestaurantMenuModule;
import com.minecolonies.core.colony.buildings.moduleviews.RestaurantMenuModuleView;
import com.minecolonies.api.colony.buildings.registry.BuildingEntry;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import com.minecolonies.core.colony.buildings.moduleviews.WorkerBuildingModuleView;
import com.minecolonies.core.colony.buildings.modules.BuildingModules;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * Registers the caravan hut building into MineColonies' BUILDINGS registry.
 *
 * <p>The registry name is {@code caravan:caravanleader} which also drives the
 * blueprint name ({@code caravanleader1-5.blueprint}) and the translation key
 * ({@code com.caravan.building.caravanleader}).</p>
 */
public final class ModBuildings
{
    /** Filled during {@link #registerBuildings(RegisterEvent)}. */
    public static BuildingEntry caravanLeader;

    private ModBuildings()
    {
    }

    public static void registerBuildings(final RegisterEvent event)
    {
        if (!event.getRegistryKey().equals(CommonMinecoloniesAPIImpl.BUILDINGS))
        {
            return;
        }

        final BuildingEntry entry = new BuildingEntry.Builder()
            .setBuildingBlock(CaravanMod.BLOCK_HUT_CARAVAN_LEADER.get())
            .setBuildingProducer(BuildingCaravanLeader::new)
            // 自定义建筑视图：主窗口拦截【召回工人】按钮（领袖消失时只提示不召回）。
            .setBuildingViewProducer(() -> CaravanBuildingView::new)
            .setRegistryName(ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "caravanleader"))
            .addBuildingModuleProducer(new BuildingEntry.ModuleProducer<>("caravanLeaderWork",
                () -> new WorkerBuildingModule(CaravanMod.JOB_CARAVAN_LEADER, Skill.Agility, Skill.Intelligence, false, building -> 1),
                () -> WorkerBuildingModuleView::new))
            // 可与领袖同时雇佣，数量上限 = 小屋等级（1-5），属性与领袖一致。
            .addBuildingModuleProducer(new BuildingEntry.ModuleProducer<>("caravanMemberWork",
                () -> new WorkerBuildingModule(CaravanMod.JOB_CARAVAN_MEMBER, Skill.Agility, Skill.Intelligence, false, building -> building.getBuildingLevel()),
                () -> WorkerBuildingModuleView::new))
            .addBuildingModuleProducer(new BuildingEntry.ModuleProducer<>("caravanTrades",
                () -> new CaravanTradeModule(),
                () -> CaravanTradeListModuleView::new))
            .addBuildingModuleProducer(new BuildingEntry.ModuleProducer<>("caravanSettings",
                () -> new CaravanSettingsModule(),
                () -> CaravanSettingsModuleView::new))
            // RestaurantMenuModule（选择商队食用食物，作为最低存量保留）。
            // 存量 = 【设置】页“食物携带组数”模块输入的数值（0..32）。
            .addBuildingModuleProducer(new BuildingEntry.ModuleProducer<>("caravanMenu",
                () -> new RestaurantMenuModule(false, building ->
                {
                    final CaravanStockModule stock =
                        building.getFirstModuleOccurance(CaravanStockModule.class);
                    return stock != null ? stock.getFoodCarryCount() : 2;
                }),
                () -> RestaurantMenuModuleView::new))
            .addBuildingModuleProducer(new BuildingEntry.ModuleProducer<>("caravanStock",
                () -> new CaravanStockModule(),
                () -> CaravanStockModuleView::new))
            .addBuildingModuleProducer(new BuildingEntry.ModuleProducer<>("caravanLog",
                () -> new CaravanLogModule(),
                () -> CaravanLogModuleView::new))
            .addBuildingModuleProducer(new BuildingEntry.ModuleProducer<>("caravanGuard",
                () -> new CaravanGuardModule(),
                () -> CaravanGuardModuleView::new))
            // 注意：模块注册顺序决定标签页顺序——最低存量排在建筑统计之前。
            .addBuildingModuleProducer(BuildingModules.MIN_STOCK)
            .addBuildingModuleProducer(BuildingModules.STATS_MODULE)
            .createBuildingEntry();

        caravanLeader = entry;
        event.register(CommonMinecoloniesAPIImpl.BUILDINGS, helper -> helper.register(entry.getRegistryName(), entry));
    }
}
