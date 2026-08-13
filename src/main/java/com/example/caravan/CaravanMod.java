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
import com.example.caravan.commands.CaravanCommands;
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
 * Caravan - a test MineColonies addon.
 *
 * <p>Adds the Caravan Leader profession, the caravan hut and the Caravan Marker item.
 * The Caravan Leader picks up a marked order from the hut, requests the trade goods
 * through the MineColonies request system, walks towards the recorded village,
 * vanishes when leaving the colony, and reappears 1-10 in-game days later with the
 * trade results (mirroring the Nether Miner's disappear/reappear behaviour).</p>
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

    /** The test item used to record a villager trade plus the village location. */
    public static final DeferredItem<CaravanMarkerItem> CARAVAN_MARKER =
        ITEMS.register("caravan_marker", () -> new CaravanMarkerItem(new Item.Properties()));

    /** 商队帐篷（出行/休息消耗耐久，合成：羊毛 + 木棍 + 床）。 */
    public static final DeferredItem<ItemCaravanTent> CARAVAN_TENT =
        ITEMS.register("caravan_tent", () -> new ItemCaravanTent(new Item.Properties()));

    /** 需求（创造标签页）：本 mod 专属“商队/Caravan”标签页（图标 = MC 原版绿宝石）。 */
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
        // RegisterCommandsEvent 是游戏总线事件（非 IModBusEvent），须注册到 NeoForge.EVENT_BUS。
        NeoForge.EVENT_BUS.addListener(CaravanCommands::registerCommands);
        NeoForge.EVENT_BUS.addListener(CaravanMod::onVillagerJoinLevel);
    }

    /**
     * 修复需求1：未加载的交易目标村民无法获得模拟经验。
     * minecolonies 的建筑模块只在自身区块加载时才 tick（onColonyTick），而目标村民
     * 位于远处村庄——当玩家在村庄附近（村民已加载）而商队小屋区块未加载时，
     * 经验永远不会结算。这里监听村民实体加入世界的时刻，无论小屋区块是否加载，
     * 只要记录中有该村民且有待结算经验（pendingXp），立即补发并重录。
     */
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
                    // 修复bug：普通建筑没有 CaravanTradeModule，getFirstModuleOccurance 会抛异常
                    // 并中断整个扫描，导致未加载村民的模拟经验永远无法结算。
                    if (!building.hasModule(CaravanTradeModule.class))
                    {
                        continue;
                    }
                    final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
                    final VillagerTradeEntry entry = module.findVillager(villagerId);
                    if (entry != null && entry.pendingXp() > 0)
                    {
                        // addVillagerTrades 内部会先结算 pendingXp，再以实体当前状态重录。
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

    /**
     * 修复崩溃（Ticking player / SoundUtils NPE）：
     * minecolonies 的 ModSoundEvents.CITIZEN_SOUND_EVENTS 只在静态初始化时收录
     * ModJobs.getJobs()（minecolonies 内置职业的快照列表），自定义职业（caravan_leader）
     * 永远不在其中。玩家碰撞商队领袖市民时，SoundUtils.playSoundAtCitizenWith 取职业声音表
     * 得到 null 再调用 Map.get，抛出 NullPointerException。
     * 这里在 CommonSetup 阶段为我们的职业补音效表——需求：使用快递员（deliveryman）的音效。
     */
    private static void onCommonSetup(final FMLCommonSetupEvent event)
    {
        // 需求（请求系统接入）：注册商队小屋自定义请求解析方的工厂，
        // 使请求系统能够持久化/重建该 resolver。
        try
        {
            StandardFactoryController.getInstance()
                .registerNewFactory(new CaravanTradeRequestResolverFactory());
            StandardFactoryController.getInstance()
                .registerNewFactory(new CaravanTradeRequestFactory());
            // 需求（请求链可视化）：注册 requestable → request 类型映射，
            // 使 createRequest(CaravanTradeRequestable) 能正确创建"商队交易"标记请求。
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
            // 需求：商队领袖与商队成员使用快递员的音效。
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
