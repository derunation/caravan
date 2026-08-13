package com.example.caravan.colony.buildings.modules;

import com.example.caravan.CaravanMod;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModule;
import com.minecolonies.api.colony.buildings.modules.ITickingModule;
import com.minecolonies.api.colony.requestsystem.manager.IRequestManager;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requestable.StackList;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.entity.ai.workers.util.GuardGear;
import com.minecolonies.api.entity.ai.workers.util.GuardGearBuilder;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.equipment.ModEquipmentTypes;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Tuple;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求（商队卫兵）：每殖民地刻检查卫兵装备——沿用 Minecolonies 本体卫兵塔的模式：
 * 按 {@link GuardGear}（装备槽位 + 装备类型 + 等级范围 + 建筑等级范围）检查，
 * 缺失时以“候选物品列表”（该装备类型下、等级范围内的物品）创建【公民请求】
 * （关联卫兵市民，快递员直接送达市民背包；无武器不工作的判定由卫兵 AI 后续版本实现）。
 */
public class CaravanGuardEquipmentModule extends AbstractBuildingModule implements ITickingModule
{
    /** 需求（装备请求防重）：市民 id →（装备槽名 → 打开的请求令牌）。 */
    private final Map<Integer, Map<String, IToken<?>>> openRequests = new HashMap<>();

    @Override
    public void onColonyTick(final IColony colony)
    {
        try
        {
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
                    checkGuardEquipment(guard, level);
                }
            }
        }
        catch (final Exception ignored)
        {
            // 装备检查失败不影响殖民地 tick。
        }
    }

    /** 按本体的 GuardGear 定义检查单个卫兵的每一件装备。 */
    private void checkGuardEquipment(final ICitizenData guard, final int level)
    {
        final AbstractEntityCitizen entity = guard.getEntity().orElse(null);
        final Map<String, IToken<?>> requests =
            openRequests.computeIfAbsent(guard.getId(), k -> new HashMap<>());
        for (final GuardGear gear : guardGearForLevel(level))
        {
            // 建筑等级范围过滤（与卫兵塔一致：低等级建筑不请求高级装备）。
            if (level < gear.getMinBuildingLevelRequired()
                || level > gear.getMaxBuildingLevelRequired())
            {
                continue;
            }
            final String slotKey = gear.getType().name();
            // 实体未加载时跳过检查（加载后自然补齐）。
            if (entity == null)
            {
                continue;
            }
            if (bestEquipmentLevel(entity, gear) >= gear.getMinArmorLevel())
            {
                // 已有足够等级的装备 → 取消残留请求。
                cancelOpen(requests, slotKey);
                continue;
            }
            // 构造候选物品：该装备类型下、等级在 [min, max] 范围内的物品——
            // 不设数量上限（多 mod 环境下注册表遍历顺序不定，截断会导致
            // 部分 mod 的装备无法被请求到；本体基于类型/tag 匹配、无此限制）。
            final List<ItemStack> candidates = new ArrayList<>();
            for (final Item item : BuiltInRegistries.ITEM)
            {
                final ItemStack stack = new ItemStack(item);
                if (!gear.getItemNeeded().checkIsEquipment(stack))
                {
                    continue;
                }
                final int itemLevel = gear.getItemNeeded().getMiningLevel(stack);
                if (itemLevel >= gear.getMinArmorLevel()
                    && itemLevel <= gear.getMaxArmorLevel())
                {
                    candidates.add(stack);
                }
            }
            if (candidates.isEmpty())
            {
                continue;
            }
            requestIfMissing(guard, requests, slotKey,
                new StackList(candidates, "com.caravan.gui.guard_equipment", 1, 1, 0));
        }
    }

    /**
     * 需求（装备请求）：完全复制 Minecolonies 本体各等级卫兵塔的装备配置
     * （AbstractEntityAIFight 构造函数逐级调用 GuardGearBuilder.buildGearForLevel）——
     * 护甲四件套按建筑等级：1 级=1-1、2 级=1-2、3 级=1-3、4 级=2-4、5 级=3-∞（含 5 级下界合金）；
     * 剑（本体在 toolsNeeded 中）等级上限=建筑等级（5 级塔可请求 5 级武器）。
     */
    private static List<GuardGear> guardGearForLevel(final int level)
    {
        final List<GuardGear> gear = new ArrayList<>();
        // 等级要求 0-99（本体 LEATHER_BUILDING_LEVEL_RANGE——已有装备只要有任意等级即满足）。
        final Tuple<Integer, Integer> anyLevel = new Tuple<>(0, 99);
        switch (level)
        {
            case 1 -> gear.addAll(GuardGearBuilder.buildGearForLevel(
                1, 1, anyLevel, new Tuple<>(1, 2)));
            case 2 -> gear.addAll(GuardGearBuilder.buildGearForLevel(
                1, 2, anyLevel, new Tuple<>(2, 3)));
            case 3 -> gear.addAll(GuardGearBuilder.buildGearForLevel(
                1, 3, anyLevel, new Tuple<>(3, 4)));
            case 4 -> gear.addAll(GuardGearBuilder.buildGearForLevel(
                2, 4, anyLevel, new Tuple<>(4, 5)));
            default -> gear.addAll(GuardGearBuilder.buildGearForLevel(
                3, Integer.MAX_VALUE, anyLevel, new Tuple<>(4, 5)));
        }
        // 剑：本体在 toolsNeeded（EntityAIKnight），等级上限 = 建筑最大装备等级（5 级 → 5 级武器）。
        final int maxWeaponLevel = Math.min(5, level);
        final int minWeaponLevel = level >= 5 ? 3 : (level >= 3 ? 2 : 1);
        gear.add(new GuardGear(ModEquipmentTypes.sword.get(), EquipmentSlot.MAINHAND,
            minWeaponLevel, maxWeaponLevel, anyLevel, new Tuple<>(1, 5)));
        return gear;
    }

    /** 卫兵现有装备中该 GuardGear 类型的最高等级（装备槽 + 背包；无则 -1）。 */
    private static int bestEquipmentLevel(final AbstractEntityCitizen entity, final GuardGear gear)
    {
        int best = -1;
        for (int slot = 0; slot < entity.getInventoryCitizen().getSlots(); slot++)
        {
            final ItemStack stack = entity.getInventoryCitizen().getStackInSlot(slot);
            if (gear.test(stack))
            {
                best = Math.max(best, gear.getItemNeeded().getMiningLevel(stack));
            }
        }
        if (gear.getType().isArmor())
        {
            final ItemStack worn = entity.getInventoryCitizen().getArmorInSlot(gear.getType());
            if (gear.test(worn))
            {
                best = Math.max(best, gear.getItemNeeded().getMiningLevel(worn));
            }
        }
        else
        {
            final ItemStack held = entity.getItemBySlot(gear.getType());
            if (gear.test(held))
            {
                best = Math.max(best, gear.getItemNeeded().getMiningLevel(held));
            }
        }
        return best;
    }

    /** 创建公民请求（关联卫兵，快递员送达市民背包）；已有打开请求则不重复创建。 */
    private void requestIfMissing(final ICitizenData guard,
        final Map<String, IToken<?>> requests,
        final String slotKey,
        final StackList requestable)
    {
        final IToken<?> existing = requests.get(slotKey);
        if (existing != null && isOpenRequest(getBuilding().getColony().getRequestManager(), existing))
        {
            return;
        }
        requests.remove(slotKey);
        try
        {
            final IToken<?> token = getBuilding().createRequest(guard, requestable, false);
            requests.put(slotKey, token);
            CaravanMod.LOGGER.info(
                "Caravan: 创建卫兵装备请求 {}（市民 {}，槽位 {}）",
                requestable.getResult().getHoverName().getString(), guard.getId(), slotKey);
        }
        catch (final Exception ignored)
        {
            // 请求失败下轮重试。
        }
    }

    /** 装备已满足时取消残留请求。 */
    private void cancelOpen(final Map<String, IToken<?>> requests, final String slotKey)
    {
        final IToken<?> token = requests.remove(slotKey);
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
        if (manager == null)
        {
            return false;
        }
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
}
