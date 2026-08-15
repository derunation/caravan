package com.example.caravan.client.journeymap;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.moduleviews.CaravanLogModuleView;
import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.mojang.blaze3d.platform.NativeImage;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.client.display.Context;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.model.TextProperties;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;

/**
 * 旅行地图（JourneyMap）联动插件。
 * <p>商队领袖进入消失状态后，在旅行地图上用一个“头像”标记模拟其
 * 前往各个目的地（含返程）的移动过程：</p>
 * <ul>
 *   <li>标记位置按“当前段起点 → 终点”根据剩余距离插值移动；</li>
 *   <li>图标使用“下一个目的地将要进行的第一条交易”的结果物品图标（直接读取
 *       MC 原版物品贴图），回程阶段沿用上一目的地图标；</li>
 *   <li>不再绘制路线折线，文本按阶段显示“旅行中/交易中/返回中”，
 *       文本顶边对齐图标底边并水平居中。</li>
 * </ul>
 * <p>数据来源：商队小屋【日志】模块视图（服务端每 20 刻同步一次消失状态与路线）。</p>
 */
@JourneyMapPlugin(apiVersion = "2.0.0", dependencies = {}, require = false)
public class CaravanJourneyMapPlugin implements IClientPlugin
{
    /** 插件覆盖层分组名。 */
    private static final String OVERLAY_GROUP = "caravan";

    private static IClientAPI api;
    private static MarkerOverlay marker;
    /** 绿宝石图标（延迟加载一次，复用同一张 MapImage）。 */
    private static MapImage emeraldIcon;
    /** 按物品缓存已加载的原版图标，避免每 20 刻重复读取贴图。 */
    private static final java.util.Map<Item, MapImage> ITEM_ICON_CACHE = new java.util.HashMap<>();
    /** 当前显示的物品图标（回程阶段无新图标时沿用）。 */
    private static ItemStack currentIconStack = ItemStack.EMPTY;
    /** 最近一次有效的标记位置（领袖实体未加载/数据缺失时沿用）。 */
    private static BlockPos lastMarkerPos;
    private static int lastSignature = Integer.MIN_VALUE;

    @Override
    public String getModId()
    {
        return "caravan";
    }

    @Override
    public void initialize(final IClientAPI clientApi)
    {
        api = clientApi;
        NeoForge.EVENT_BUS.register(CaravanJourneyMapPlugin.class);
    }

    /** 玩家 tick：每 5 刻更新一次大地图上的商队标记（未消失时跟随领袖实体位置，减少延迟）。 */
    @SubscribeEvent
    public static void onPlayerTick(final PlayerTickEvent.Post event)
    {
        if (api == null)
        {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || event.getEntity() != mc.player || mc.level == null)
        {
            return;
        }
        if (mc.level.getGameTime() % 5 != 0)
        {
            return;
        }
        update(mc);
    }

    private static void update(final Minecraft mc)
    {
        try
        {
            updateInternal(mc);
        }
        catch (final Throwable ex)
        {
            // 插件更新绝不能导致游戏崩溃——记录警告并清理覆盖层。
            CaravanMod.LOGGER.warn("Failed to update JourneyMap caravan overlay", ex);
            removeOverlays();
        }
    }

    private static void updateInternal(final Minecraft mc)
    {
        final CaravanLogModuleView log = findCaravanLogModule(mc);
        if (log == null || !log.hasLeader())
        {
            removeOverlays();
            return;
        }

        // 未消失时优先用客户端实体位置（即时、精准），实体未加载时回退服务端同步位置。
        final boolean away = log.isAway();
        // 空闲/睡觉/备货/回到小屋后隐藏（领袖寻路找床位时不再显示标记）。
        final boolean trading = log.getStatus() == JobCaravanLeader.CaravanStatus.TRADING;
        if (!away && !trading)
        {
            removeOverlays();
            return;
        }
        BlockPos pos;
        if (away && log.isWalkingThroughColony())
        {
            // 穿越殖民地：实体现身步行中，标记跟随领袖实体位置（而非冻结的模拟点）。
            pos = leaderEntityPos(mc);
        }
        else if (away)
        {
            pos = interpolate(log);
        }
        else
        {
            pos = leaderEntityPos(mc);
        }
        if (pos == null && !away)
        {
            pos = log.getLeaderPos();
        }
        // 直到下次出发（离开小屋）时重新出现。
        if (!away && pos != null && pos.distSqr(log.getBuildingView().getID()) <= 36)
        {
            removeOverlays();
            return;
        }
        if (pos == null)
        {
            pos = lastMarkerPos;
        }
        if (pos == null && marker == null)
        {
            return; // 尚无任何有效位置，不创建标记
        }
        if (pos != null)
        {
            lastMarkerPos = pos;
        }
        // 回程阶段服务端传空物品，此时沿用上一目的地的图标。
        final ItemStack nextIcon = log.getNextTradeIcon();
        if (!nextIcon.isEmpty())
        {
            currentIconStack = nextIcon;
        }
        // 任何状态变化（旅行/夜行/扎营/露宿/交易）都会触发重绘。
        final String label = journeyMapLabel(log);
        final int signature = label.hashCode() * 31
            + (pos != null ? pos.hashCode() : 0) * 31
            + (currentIconStack.isEmpty() ? 0 : BuiltInRegistries.ITEM.getId(currentIconStack.getItem()));
        if (signature != lastSignature || marker == null)
        {
            lastSignature = signature;
            if (marker == null)
            {
                marker = new MarkerOverlay(OVERLAY_GROUP, pos != null ? pos : BlockPos.ZERO, iconFor(currentIconStack));
                marker.setDimension(mc.level.dimension());
                marker.setOverlayGroupName(OVERLAY_GROUP);
                marker.setActiveUIs(Context.UI.Fullscreen, Context.UI.Minimap);
                marker.setActiveMapTypes(Context.MapType.Day, Context.MapType.Night, Context.MapType.Topo);
                marker.setDisplayOrder(100);
            }
            // 再下移 8 像素、右移 8 像素。
            // 注意：JourneyMap 在北朝上（旋转角=0）时会对偏移取反（offsetY 取负），
            // 因此“向下/向右”需要传负值；图标底边在 +16，文字中心约在 +21，
            // 下移 8 → 中心 +29（offsetY=-29）；右移 8 → offsetX=-8。
            marker.setTextProperties(new TextProperties().setOffsetX(-8).setOffsetY(-29));
            marker.setPoint(pos != null ? pos : BlockPos.ZERO);
            marker.setIcon(iconFor(currentIconStack));
            marker.setLabel(label);
            // 否则旅行地图不会重绘（表现为只有打开地图时才刷新）。
            marker.flagForRerender();
            show(marker);
        }
    }

    /** 从所有殖民地视图中查找商队小屋的【日志】模块视图。 */
    private static CaravanLogModuleView findCaravanLogModule(final Minecraft mc)
    {
        try
        {
            for (final IColonyView colony : IColonyManager.getInstance().getColonyViews(mc.level))
            {
                for (final IBuildingView building : colony.getClientBuildingManager().getBuildings().values())
                {
                    final List<CaravanLogModuleView> logs =
                        building.getModuleViews(CaravanLogModuleView.class);
                    if (!logs.isEmpty())
                    {
                        return logs.get(0);
                    }
                }
            }
        }
        catch (final Throwable ignored)
        {
            // 殖民地视图尚未同步完成时静默跳过。
        }
        return null;
    }

    private static BlockPos leaderEntityPos(final Minecraft mc)
    {
        if (mc.level == null)
        {
            return null;
        }
        try
        {
            for (final Entity entity : mc.level.entitiesForRendering())
            {
                if (entity instanceof AbstractEntityCitizen citizen)
                {
                    final ICitizenDataView data = citizen.getCitizenDataView();
                    if (data != null && data.getJobView() != null
                        && data.getJobView().getEntry().getKey()
                            .equals(CaravanMod.JOB_CARAVAN_LEADER.getKey()))
                    {
                        return citizen.blockPosition();
                    }
                }
            }
        }
        catch (final Throwable ignored)
        {
            // 殖民地数据尚未同步时静默跳过。
        }
        return null;
    }

    private static BlockPos interpolate(final CaravanLogModuleView log)
    {
        final BlockPos start = log.getAwayLegStart();
        final BlockPos end = log.getAwayLegEnd();
        if (start == null)
        {
            return end;
        }
        if (end == null || log.getAwayPhase() == JobCaravanLeader.AwayPhase.TRADING)
        {
            return start;
        }
        // 修复瞬移：防御性地把剩余距离限制在 [0, max] 内，避免服务端同步到
        // 异常数值（如负值/超过初值）时进度越界，导致标记直接跳到终点。
        final int max = Math.max(1, log.getAwayMaxDistance());
        final int current = Math.max(0, Math.min(log.getAwayDistance(), max));
        final double progress = (double) (max - current) / max;
        return new BlockPos(
            (int) Math.round(start.getX() + (end.getX() - start.getX()) * progress),
            (int) Math.round(start.getY() + (end.getY() - start.getY()) * progress),
            (int) Math.round(start.getZ() + (end.getZ() - start.getZ()) * progress));
    }

    private static void show(final journeymap.api.v2.client.display.Displayable overlay)
    {
        try
        {
            api.show(overlay);
        }
        catch (final Exception ignored)
        {
            // 旅行地图暂时不可用时忽略。
        }
    }

    private static void removeOverlays()
    {
        if (marker != null)
        {
            api.remove(marker);
            marker = null;
        }
        currentIconStack = ItemStack.EMPTY;
        lastMarkerPos = null;
        lastSignature = Integer.MIN_VALUE;
    }

    private static String journeyMapLabel(final CaravanLogModuleView log)
    {
        return switch (log.getCampStatus())
        {
            case CAMP -> Component.translatable("com.caravan.journeymap.camping").getString();
            case ROUGH -> Component.translatable("com.caravan.journeymap.bivouac").getString();
            case NIGHT_TRAVEL -> Component.translatable("com.caravan.journeymap.night_travel").getString();
            case TRADING -> Component.translatable("com.caravan.journeymap.trading").getString();
            case TRAVEL -> Component.translatable("com.caravan.journeymap.outbound").getString();
        };
    }


    private static MapImage iconFor(final ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return fallbackIcon();
        }
        final Item item = stack.getItem();
        final MapImage cached = ITEM_ICON_CACHE.get(item);
        if (cached != null)
        {
            return cached;
        }
        final MapImage built = buildItemIcon(item);
        ITEM_ICON_CACHE.put(item, built);
        return built;
    }

    /** 读取物品的原版贴图（优先 textures/item，方块物品回退 textures/block），
     *  放大至 32×32；失败时回退到像素绿宝石。 */
    private static MapImage buildItemIcon(final Item item)
    {
        final ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item);
        if (itemId.getNamespace().equals("minecraft"))
        {
            final MapImage itemTexture = tryLoadTexture(
                ResourceLocation.fromNamespaceAndPath("minecraft", "textures/item/" + itemId.getPath() + ".png"));
            if (itemTexture != null)
            {
                return itemTexture;
            }
            if (item instanceof net.minecraft.world.item.BlockItem)
            {
                final MapImage blockTexture = tryLoadTexture(
                    ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/" + itemId.getPath() + ".png"));
                if (blockTexture != null)
                {
                    return blockTexture;
                }
            }
        }
        return fallbackIcon();
    }

    /** 尝试加载指定贴图并构建 32×32 居中 MapImage；失败返回 null。 */
    private static MapImage tryLoadTexture(final ResourceLocation location)
    {
        try
        {
            final var resource = Minecraft.getInstance().getResourceManager().getResource(location).orElse(null);
            if (resource != null)
            {
                try (final var stream = resource.open())
                {
                    final NativeImage image = NativeImage.read(stream);
                    return new MapImage(image).centerAnchors()
                        .setDisplayWidth(32.0).setDisplayHeight(32.0);
                }
            }
        }
        catch (final Exception ignored)
        {
            // 贴图不存在或读取失败时回退。
        }
        return null;
    }

    /** 回退图标：原版绿宝石贴图；再失败则使用像素绿宝石。 */
    private static MapImage fallbackIcon()
    {
        if (emeraldIcon == null)
        {
            final MapImage emerald = tryLoadTexture(
                ResourceLocation.withDefaultNamespace("textures/item/emerald.png"));
            emeraldIcon = emerald != null ? emerald : buildFallbackIcon();
        }
        return emeraldIcon;
    }

    /** 生成 16×16 的像素绿宝石回退图标（绿色菱形，ABGR）。 */
    private static MapImage buildFallbackIcon()
    {
        final NativeImage image = new NativeImage(16, 16, false);
        final int gem = 0xFFB0E064;        // 亮绿（ABGR）
        final int shade = 0xFF4FA24A;      // 深绿（ABGR）
        final int highlight = 0xFFE6FFB0;  // 高光（ABGR）
        for (int x = 0; x < 16; x++)
        {
            for (int y = 0; y < 16; y++)
            {
                final int dx = x - 7;
                final int dy = y - 7;
                final int diamond = Math.abs(dx) + Math.abs(dy);
                if (diamond > 7)
                {
                    continue; // 透明背景
                }
                if (diamond >= 5)
                {
                    image.setPixelRGBA(x, y, shade);
                }
                else if (diamond >= 2)
                {
                    image.setPixelRGBA(x, y, gem);
                }
                else
                {
                    image.setPixelRGBA(x, y, highlight);
                }
            }
        }
        return new MapImage(image).centerAnchors().setDisplayWidth(32.0).setDisplayHeight(32.0);
    }
}
