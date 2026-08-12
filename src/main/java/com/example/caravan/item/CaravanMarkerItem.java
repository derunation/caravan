package com.example.caravan.item;

import com.example.caravan.CaravanMod;
import com.example.caravan.block.BlockHutCaravanLeader;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.modules.CaravanTradeModule;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;

/**
 * Test item: right-click a vanilla villager to record its first trade together
 * with the nearest village centre (a bell, falling back to the villager itself).
 * Placing the marked item into the caravan hut's chest creates a trade order for
 * the Caravan Leader.
 */
public class CaravanMarkerItem extends Item
{
    public CaravanMarkerItem(final Properties properties)
    {
        super(properties);
    }

    /**
     * Right-click a caravan hut to bind the marker to it. The bound hut receives
     * all villager trades recorded with the marker.
     */
    @Override
    public InteractionResult useOn(final UseOnContext context)
    {
        if (!context.getLevel().isClientSide
            && context.getLevel().getBlockState(context.getClickedPos()).getBlock() instanceof BlockHutCaravanLeader)
        {
            final BlockPos hutPos = context.getClickedPos();
            context.getItemInHand().set(CaravanMod.BOUND_HUT.get(), hutPos);
            context.getPlayer().displayClientMessage(Component.translatable(
                "item.caravan.caravan_marker.bound",
                hutPos.getX(), hutPos.getY(), hutPos.getZ()), true);
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public InteractionResult interactLivingEntity(
        final ItemStack stack,
        final Player player,
        final LivingEntity target,
        final InteractionHand hand)
    {
        if (!(target instanceof Villager villager) || player.level().isClientSide || !player.isShiftKeyDown())
        {
            return InteractionResult.PASS;
        }

        final BlockPos hutPos = stack.get(CaravanMod.BOUND_HUT);
        if (hutPos == null)
        {
            player.displayClientMessage(Component.translatable("item.caravan.caravan_marker.not_bound"), true);
            return InteractionResult.FAIL;
        }

        final ServerLevel level = (ServerLevel) player.level();
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(level, hutPos);
        if (colony == null)
        {
            player.displayClientMessage(Component.translatable("item.caravan.caravan_marker.not_bound"), true);
            return InteractionResult.FAIL;
        }

        final BuildingCaravanLeader building = colony.getServerBuildingManager().getBuilding(hutPos, BuildingCaravanLeader.class);
        if (building == null)
        {
            player.displayClientMessage(Component.translatable("item.caravan.caravan_marker.not_bound"), true);
            return InteractionResult.FAIL;
        }

        final CaravanTradeModule module = building.getFirstModuleOccurance(CaravanTradeModule.class);
        if (module == null)
        {
            return InteractionResult.FAIL;
        }

        final int before = module.getTotalOfferCount();
        module.addVillagerTrades(villager, level);
        final int added = module.getTotalOfferCount() - before;
        player.displayClientMessage(Component.translatable(
            "item.caravan.caravan_marker.trades_written", added), true);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(
        final ItemStack stack,
        final TooltipContext context,
        final List<Component> tooltip,
        final TooltipFlag flag)
    {
        super.appendHoverText(stack, context, tooltip, flag);

        final BlockPos boundHut = stack.get(CaravanMod.BOUND_HUT);
        if (boundHut == null)
        {
            tooltip.add(Component.translatable("item.caravan.caravan_marker.tooltip.unbound"));
            return;
        }

        tooltip.add(Component.translatable("item.caravan.caravan_marker.tooltip.bound",
            boundHut.getX(), boundHut.getY(), boundHut.getZ()));
        tooltip.add(Component.translatable("item.caravan.caravan_marker.tooltip.sneak_hint"));
    }
}
