package com.example.caravan;

import com.example.caravan.block.BlockHutCaravanLeader;
import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.example.caravan.colony.buildings.modules.CaravanTradeRequestResolverFactory;
import com.example.caravan.colony.buildings.modules.CaravanTradeRequestFactory;
import com.example.caravan.colony.buildings.modules.CaravanTradeRequest;
import com.example.caravan.colony.buildings.modules.CaravanTradeRequestable;
import com.example.caravan.colony.buildings.modules.VillagerTradeEntry;
import com.example.caravan.init.ModBuildings;
import com.example.caravan.init.ModJobs;
import com.example.caravan.item.CaravanMarkerItem;
import com.example.caravan.item.ItemCaravanTent;
import com.example.caravan.tileentity.TileEntityCaravanLeader;
import com.example.caravan.network.CaravanGetToolMessage;
import com.example.caravan.network.CaravanGuardAssignMessage;
import com.example.caravan.network.CaravanCloseGuiMessage;
import com.example.caravan.network.CaravanDeleteVillagerMessage;
import com.example.caravan.network.CaravanRenameVillagerMessage;
import com.example.caravan.network.CaravanRefreshBuildingMessage;
import com.example.caravan.network.CaravanTradeQuantityMessage;
import com.example.caravan.network.CaravanTradeModeMessage;
import com.example.caravan.network.CaravanTradeOrderMessage;
import com.example.caravan.network.CaravanStockMessage;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.api.colony.requestsystem.StandardFactoryController;
import com.minecolonies.api.colony.requestsystem.manager.RequestMappingHandler;
import com.minecolonies.api.sounds.EventType;
import com.minecolonies.api.sounds.ModSoundEvents;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import com.minecolonies.api.util.Tuple;
import com.minecolonies.core.tileentities.TileEntityColonyBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minecolonies Caravans / 模拟殖民地商队附属。
 *
 * <p>为 MineColonies 增加商队小屋、商队领袖/成员/护卫职业、交易列表、
 * 模拟旅行、请求系统联动与旅行地图联动等玩法。</p>
 */
@Mod(CaravanMod.MODID)
public final class CaravanMod
{
    public static final String MODID = "caravan";
    public static final Logger LOGGER = LoggerFactory.getLogger(CaravanMod.class);

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
        DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, MODID);
    /** The caravan hut block, placed by the build tool from the caravanleader blueprints. */
    public static final DeferredBlock<BlockHutCaravanLeader> BLOCK_HUT_CARAVAN_LEADER =
        BLOCKS.register("blockhutcaravanleader", BlockHutCaravanLeader::new);

    /**
     * The hut block's item. Required so {@code new ItemStack(block)} works; without
     * it the block maps to {@code minecraft:air} and creative tab contents fail
     * with "The stack count must be 1 for 0 minecraft:air".
     */
    public static final DeferredItem<Item> ITEM_HUT_CARAVAN_LEADER =
        ITEMS.register("blockhutcaravanleader",
            () -> new BlockItem(BLOCK_HUT_CARAVAN_LEADER.get(), new Item.Properties()));

    /** Item used to record a villager trade plus the village location. */
    public static final DeferredItem<CaravanMarkerItem> CARAVAN_MARKER =
        ITEMS.register("caravan_marker", () -> new CaravanMarkerItem(new Item.Properties()));

    /** 商队帐篷（出行/休息消耗耐久，合成：羊毛 + 木棍 + 床）。 */
    public static final DeferredItem<ItemCaravanTent> CARAVAN_TENT =
        ITEMS.register("caravan_tent", () -> new ItemCaravanTent(new Item.Properties()));

    /** 本 mod 专属“商队/Caravan”创造标签页（图标 = MC 原版绿宝石）。 */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> CARAVAN_CREATIVE_TAB =
        CREATIVE_TABS.register("caravan", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.caravan"))
            .icon(() -> new ItemStack(Items.EMERALD))
            .displayItems((params, output) ->
            {
                output.accept(ITEM_HUT_CARAVAN_LEADER.get());
                output.accept(CARAVAN_MARKER.get());
                output.accept(CARAVAN_TENT.get());
            })
            .build());

    /** Tile entity used by the caravan hut (MineColonies' own colony building tile entity). */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<TileEntityCaravanLeader>> TILE_CARAVAN_LEADER =
        BLOCK_ENTITIES.register("blockhutcaravanleader",
            () -> BlockEntityType.Builder.of(TileEntityCaravanLeader::new, BLOCK_HUT_CARAVAN_LEADER.get()).build(null));

    /** Data component that stores the hut a caravan marker is bound to. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlockPos>> BOUND_HUT =
        DATA_COMPONENTS.registerComponentType("bound_hut",
            builder -> builder.persistent(BlockPos.CODEC).networkSynchronized(BlockPos.STREAM_CODEC));

    /** The Caravan Leader job entry（RegisterEvent 中填充）。 */
    public static JobEntry JOB_CARAVAN_LEADER;

    /** 商队成员职业条目（RegisterEvent 中填充）。 */
    public static JobEntry JOB_CARAVAN_MEMBER;

    private CaravanMod()
    {
    }

    public CaravanMod(final IEventBus modBus)
    {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        CREATIVE_TABS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        DATA_COMPONENTS.register(modBus);
        modBus.addListener(ModBuildings::registerBuildings);
        modBus.addListener(ModJobs::registerJobs);
        modBus.addListener(CaravanMod::onNetworkRegistry);
        modBus.addListener(CaravanMod::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(CaravanMod::onVillagerJoinLevel);
    }

    /** 未加载的交易目标村民在实体重新进入世界时补发模拟经验。 */
    private static void onVillagerJoinLevel(final EntityJoinLevelEvent event)
    {
        if (event.getLevel().isClientSide())
        {
            return;
        }
        if (!(event.getEntity() instanceof Villager villager))
        {
            return;
        }
        try
        {
            final ServerLevel level = (ServerLevel) event.getLevel();
            final UUID villagerId = villager.getUUID();
            for (final IColony colony : IColonyManager.getInstance().getAllColonies())
            {
                if (colony.getWorld() != level)
                {
                    continue;
                }
                for (final IBuilding building : colony.getServerBuildingManager().getBuildings().values())
                {
                    if (!building.hasModule(CaravanTradeModule.class))
                    {
                        continue;
                    }
                    final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
                    final VillagerTradeEntry entry = module.findVillager(villagerId);
                    if (entry != null && entry.pendingXp() > 0)
                    {
                        module.addVillagerTrades(villager, level);
                    }
                }
            }
        }
        catch (final Exception ex)
        {
            LOGGER.warn("Failed to grant pending villager xp", ex);
        }
    }

    /** CommonSetup 阶段注册自定义请求工厂并为本职业补音效表。 */
    private static void onCommonSetup(final FMLCommonSetupEvent event)
    {
        try
        {
            StandardFactoryController.getInstance()
                .registerNewFactory(new CaravanTradeRequestResolverFactory());
            StandardFactoryController.getInstance()
                .registerNewFactory(new CaravanTradeRequestFactory());
            RequestMappingHandler.registerRequestableTypeMapping(
                CaravanTradeRequestable.class, CaravanTradeRequest.class);
        }
        catch (final Exception ex)
        {
            LOGGER.warn("Failed to register caravan trade request resolver factory", ex);
        }
        try
        {
            final Map<String, Map<EventType, List<Tuple<SoundEvent, SoundEvent>>>> soundMap =
                ModSoundEvents.CITIZEN_SOUND_EVENTS;
            final Map<EventType, List<Tuple<SoundEvent, SoundEvent>>> deliverymanSounds =
                soundMap.get("deliveryman");
            soundMap.putIfAbsent("caravan_leader", deliverymanSounds);
            soundMap.putIfAbsent("caravan_member", deliverymanSounds);
        }
        catch (final Exception ex)
        {
            LOGGER.warn("Failed to patch Minecolonies citizen sound map", ex);
        }
    }

    private static void onNetworkRegistry(final RegisterPayloadHandlersEvent event)
    {
        final PayloadRegistrar registrar = event.registrar("1");
        CaravanGetToolMessage.TYPE.register(registrar);
        CaravanGuardAssignMessage.TYPE.register(registrar);
        CaravanDeleteVillagerMessage.TYPE.register(registrar);
        CaravanRenameVillagerMessage.TYPE.register(registrar);
        CaravanTradeModeMessage.TYPE.register(registrar);
        CaravanTradeQuantityMessage.TYPE.register(registrar);
        CaravanRefreshBuildingMessage.TYPE.register(registrar);
        CaravanCloseGuiMessage.TYPE.register(registrar);
        CaravanStockMessage.TYPE.register(registrar);
        CaravanTradeOrderMessage.TYPE.register(registrar);
    }

}
