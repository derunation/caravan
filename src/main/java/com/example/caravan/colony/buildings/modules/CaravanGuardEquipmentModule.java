package com.example.caravan.colony.buildings.modules;

import com.example.caravan.CaravanMod;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.Stack;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

/**
 * 需求（商队卫兵）：每殖民地刻检查卫兵装备——参照 Minecolonies 本体卫兵的机制
 * 请求武器与护甲（无武器时不工作的判定由卫兵 AI 后续版本实现）。
 * <p>请求挂在小屋建筑上（送达小屋存储，与帐篷/火把请求一致），
 * 避免市民请求导致卫兵进入 NEEDS_ITEM 状态卡住；装备等级随小屋等级提升：
 * 1-2 级铁质、3-4 级钻石、5 级下界合金。</p>
 */
public class CaravanGuardEquipmentModule extends AbstractBuildingModule implements ITickingModule
{
    /** 需求（装备请求防重）：市民 id →（槽位名 → 打开的请求令牌）。 */
    private final Map<Integer, Map<String, IToken<?>>> openRequests = new HashMap<>();

    @Override
    public void onColonyTick(final IColony colony)
    {
        try
        {
            final IRequestManager manager = colony.getRequestManager();
            if (manager == null)
            {
                return;
            }
            final int level = getBuilding().getBuildingLevel();
            for (final WorkerBuildingModule module : getBuilding()
                .getModulesByType(WorkerBuildingModule.class))
            {
                if (!module.getJobEntry().getKey().equals(CaravanMod.JOB_CARAVAN_GUARD.getKey()))
                {
                    continue;
                }
                for (final ICitizenData guard : module.getAssignedCitizen())
                {
                    checkGuardEquipment(manager, guard, level);
                }
            }
        }
        catch (final Exception ignored)
        {
            // 装备检查失败不影响殖民地 tick。
        }
    }

    /** 检查单个卫兵：武器 + 头盔/胸甲/护腿/靴子，缺失则创建弹性请求（送达小屋存储）。 */
    private void checkGuardEquipment(final IRequestManager manager, final ICitizenData guard, final int level)
    {
        final int id = guard.getId();
        final var requests = openRequests.computeIfAbsent(id, k -> new HashMap<>());
        // 实体未加载时无法核对背包，跳过本轮（加载后自然检查）。
        final AbstractEntityCitizen entity = guard.getEntity().orElse(null);
        if (entity == null)
        {
            return;
        }
        // 武器（主手）。
        if (!hasItem(entity, equipmentForLevel(level).weapon()))
        {
            requestIfMissing(manager, requests, id, "weapon", new ItemStack(equipmentForLevel(level).weapon()));
        }
        else
        {
            cancelOpen(requests, "weapon");
        }
        // 护甲四件套。
        final Item[] armor = equipmentForLevel(level).armor();
        final String[] slots = {"helmet", "chestplate", "leggings", "boots"};
        final EquipmentSlot[] equipSlots = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (int i = 0; i < slots.length; i++)
        {
            final Item armorItem = armor[i];
            if (armorItem == null || hasArmorAt(entity, equipSlots[i]))
            {
                cancelOpen(requests, slots[i]);
                continue;
            }
            requestIfMissing(manager, requests, id, slots[i], new ItemStack(armorItem));
        }
    }

    /** 背包中是否已有该物品（任意数量）。 */
    private static boolean hasItem(final AbstractEntityCitizen entity, final Item item)
    {
        for (int slot = 0; slot < entity.getInventoryCitizen().getSlots(); slot++)
        {
            if (entity.getInventoryCitizen().getStackInSlot(slot).getItem() == item)
            {
                return true;
            }
        }
        return false;
    }

    /** 对应护甲槽位是否已穿戴护甲。 */
    private static boolean hasArmorAt(final AbstractEntityCitizen entity, final EquipmentSlot slot)
    {
        return !entity.getItemBySlot(slot).isEmpty();
    }

    /** 创建弹性请求（1..1 件，送达小屋存储）；已有打开请求则不重复创建。 */
    private void requestIfMissing(final IRequestManager manager,
        final Map<String, IToken<?>> requests,
        final int guardId,
        final String slot,
        final ItemStack stack)
    {
        final IToken<?> existing = requests.get(slot);
        if (existing != null && isOpenRequest(manager, existing))
        {
            return;
        }
        requests.remove(slot);
        try
        {
            final IToken<?> token = manager.createAndAssignRequest(
                getBuilding().getRequester(),
                new Stack(stack, 1, 1));
            requests.put(slot, token);
            CaravanMod.LOGGER.info(
                "Caravan: 创建卫兵装备请求 {}（市民 {}，槽位 {}）",
                stack.getHoverName().getString(), guardId, slot);
        }
        catch (final Exception ignored)
        {
            // 请求失败下轮重试。
        }
    }

    /** 装备已满足时取消残留请求。 */
    private void cancelOpen(final Map<String, IToken<?>> requests, final String slot)
    {
        final IToken<?> token = requests.remove(slot);
        if (token == null)
        {
            return;
        }
        try
        {
            final IRequestManager manager = getBuilding().getColony().getRequestManager();
            if (manager != null && isOpenRequest(manager, token))
            {
                manager.updateRequestState(token, RequestState.CANCELLED);
            }
        }
        catch (final Exception ignored)
        {
            // 忽略过期令牌。
        }
    }

    /** 该请求令牌对应的请求是否仍在进行中（未结束）。 */
    private static boolean isOpenRequest(final IRequestManager manager, final IToken<?> token)
    {
        try
        {
            final IRequest<?> request = manager.getRequestForToken(token);
            if (request == null)
            {
                return false;
            }
            final RequestState state = request.getState();
            return state != RequestState.COMPLETED
                && state != RequestState.CANCELLED
                && state != RequestState.OVERRULED
                && state != RequestState.FAILED;
        }
        catch (final Exception ignored)
        {
            return false;
        }
    }

    /** 按建筑等级返回武器与护甲四件套（头盔/胸甲/护腿/靴子）。 */
    private static EquipmentSet equipmentForLevel(final int level)
    {
        if (level >= 5)
        {
            return new EquipmentSet(Items.NETHERITE_SWORD, new Item[]{
                Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE,
                Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS});
        }
        if (level >= 3)
        {
            return new EquipmentSet(Items.DIAMOND_SWORD, new Item[]{
                Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
                Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS});
        }
        return new EquipmentSet(Items.IRON_SWORD, new Item[]{
            Items.IRON_HELMET, Items.IRON_CHESTPLATE,
            Items.IRON_LEGGINGS, Items.IRON_BOOTS});
    }

    /** 一套装备：武器 + 护甲四件。 */
    private record EquipmentSet(Item weapon, Item[] armor)
    {
    }
}
