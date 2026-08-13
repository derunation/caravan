package com.example.caravan.mixin;

import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.buildings.CaravanGuardHelper;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.ai.combat.threat.IThreatTableEntity;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.inventory.InventoryCitizen;
import com.minecolonies.api.util.ItemStackUtils;
import com.minecolonies.core.colony.buildings.modules.EntityListModule;
import com.minecolonies.core.colony.jobs.AbstractJobGuard;
import com.minecolonies.core.entity.ai.workers.AbstractAISkeleton;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 需求（商队护卫·消失 AI）：卫兵 AI 的 tick 扩展——
 * 商队领袖处于模拟旅行（消失）时：
 * <ul>
 *   <li>卫兵未到达消失点 → 由 decide/follow 寻路到领袖位置（跟随中同时索敌）；</li>
 *   <li>到达领袖消失位置附近 → 一同隐形（消失状态），装备暂存背包（连同装备隐藏）；</li>
 *   <li>消失期间冻结 job AI（不索敌、不觅食、不移动）——本 mod 接管；</li>
 *   <li>穿越殖民地（领袖现身步行）→ 卫兵传送跟随；穿越结束（领袖再次隐形）→ 传送同步并隐形；</li>
 *   <li>商队模拟结束 → 传送到领袖旁、解除隐形、穿回装备，恢复 guard/follow。</li>
 * </ul>
 */
@Mixin(AbstractAISkeleton.class)
public abstract class AbstractAISkeletonMixin
{
    @Shadow(remap = false)
    protected AbstractEntityCitizen worker;

    @Shadow(remap = false)
    public com.minecolonies.api.entity.ai.statemachine.states.IAIState getState()
    {
        throw new AbstractMethodError();
    }

    /** 索敌扫描计时器。 */
    private long caravan$lastScan;
    /** 需求（诊断）：最近一次输出卫兵状态日志的时刻（节流 200 刻）。 */
    private long caravan$lastDiag;

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void caravan$guardTick(final CallbackInfo ci)
    {
        // 仅处理卫兵（骑士/弓箭手等卫兵职业）。
        final ICitizenData data = worker != null ? worker.getCitizenData() : null;
        if (data == null || !(data.getJob() instanceof AbstractJobGuard))
        {
            return;
        }
        final BuildingCaravanLeader hut = CaravanGuardHelper.caravanHutForGuard(worker);
        if (hut == null)
        {
            return;
        }
        final AbstractEntityCitizen leader = CaravanGuardHelper.leaderEntity(hut);
        if (CaravanGuardHelper.isLeaderAway(hut))
        {
            if (leader != null && !leader.isInvisible())
            {
                // 穿越殖民地：领袖现身步行 → 卫兵传送到领袖旁并现身（跟随穿越）。
                caravan$teleportNear(leader);
                if (worker.isInvisible())
                {
                    worker.setInvisible(false);
                    worker.setInvulnerable(false);
                    caravan$clearThreats();
                    caravan$restoreEquipment();
                }
                caravan$diag("穿越传送跟随");
                ci.cancel();
                return;
            }
            if (worker.isInvisible())
            {
                // 已消失：位置与领袖同步（穿越结束领袖再次隐形时位置变化），冻结 AI。
                if (leader != null && worker.distanceToSqr(leader) > 25)
                {
                    worker.teleportTo(leader.getX(), leader.getY(), leader.getZ());
                    caravan$diag("消失位置同步");
                }
                ci.cancel();
                return;
            }
            // 未消失：跟随中索敌；到达领袖消失位置附近（3 格）→ 一同消失（隐形+装备暂存）。
            // 需求（bug 修复）：超过 30 格时原版 follow() 依赖 TeleportHelper，
            // 而模拟消失点常找不到安全出生点导致传送失败、卫兵原地不动——
            // 改为直接传送到领袖实体旁（实体所在位置必然是有效坐标）。
            if (leader != null && worker.distanceToSqr(leader) > 900)
            {
                caravan$teleportNear(leader);
                caravan$diag("远离领袖传送跟随");
            }
            caravan$scanAndThreaten();
            final BlockPos leaderPos = CaravanGuardHelper.leaderPosition(hut);
            if (leaderPos != null && worker.blockPosition().distSqr(leaderPos) <= 9)
            {
                worker.setInvisible(true);
                worker.setInvulnerable(true);
                caravan$clearThreats();
                caravan$hideEquipment();
                caravan$diag("到达消失点一同消失");
                ci.cancel();
            }
            else
            {
                caravan$diag("跟随中（距消失点 "
                    + (leaderPos != null ? (int) Math.sqrt(worker.blockPosition().distSqr(leaderPos)) : -1) + "）");
            }
            // 未到达：由 decide→follow 继续寻路到领袖位置。
        }
        else
        {
            // 商队未出行/模拟结束：传送到领袖旁并现身（与领袖/成员一起），恢复驻守/跟随。
            if (worker.isInvisible())
            {
                if (leader != null)
                {
                    caravan$teleportNear(leader);
                }
                worker.setInvisible(false);
                worker.setInvulnerable(false);
                caravan$clearThreats();
                caravan$restoreEquipment();
                caravan$diag("模拟结束回归现身");
            }
            else
            {
                // 待命/驻守中索敌（补充卫兵自身的威胁检测）。
                caravan$scanAndThreaten();
            }
        }
    }

    /** 需求（诊断）：节流输出卫兵状态（每 200 刻一次）。 */
    private void caravan$diag(final String reason)
    {
        final long time = worker.level().getGameTime();
        if (time - caravan$lastDiag < 200)
        {
            return;
        }
        caravan$lastDiag = time;
        try
        {
            final BlockPos leaderPos = com.example.caravan.colony.buildings.CaravanGuardHelper.leaderPosition(
                com.example.caravan.colony.buildings.CaravanGuardHelper.caravanHutForGuard(worker));
            final IAIState aiState = getState();
            final LivingEntity threatTarget = worker instanceof IThreatTableEntity threat
                ? threat.getThreatTable().getTargetMob()
                : null;
            com.example.caravan.CaravanMod.LOGGER.info(
                "Caravan: 护卫卫兵 {}：{}（隐形={}，距领袖 {}，AI状态={}，武器={}，威胁={}）",
                worker.getCitizenData() != null ? worker.getCitizenData().getName() : "?",
                reason,
                worker.isInvisible(),
                leaderPos != null
                    ? (int) Math.sqrt(worker.blockPosition().distSqr(leaderPos))
                    : -1,
                aiState,
                caravan$hasWeapon(),
                threatTarget != null ? threatTarget.getType().getDescriptionId() : "无");
        }
        catch (final Exception ignored)
        {
            // 诊断失败不影响逻辑。
        }
    }

    /** 传送到领袖旁（±1 格随机偏移）。 */
    private void caravan$teleportNear(final AbstractEntityCitizen leader)
    {
        final var random = worker.level().random;
        worker.teleportTo(
            leader.getX() + random.nextInt(3) - 1,
            leader.getY(),
            leader.getZ() + random.nextInt(3) - 1);
    }

    /** 消失：护甲/主手武器暂存背包（连同装备一起隐藏）。 */
    private void caravan$hideEquipment()
    {
        final InventoryCitizen inv = worker.getInventoryCitizen();
        for (final EquipmentSlot slot : new EquipmentSlot[]{
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
        {
            if (!inv.getArmorInSlot(slot).isEmpty())
            {
                inv.moveArmorToInventory(slot);
            }
        }
        if (!worker.getMainHandItem().isEmpty())
        {
            final ItemStack hand = worker.getMainHandItem();
            worker.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            for (int slot = 0; slot < inv.getSlots(); slot++)
            {
                if (inv.getStackInSlot(slot).isEmpty())
                {
                    inv.setStackInSlot(slot, hand);
                    break;
                }
            }
        }
    }

    /** 回归：背包中的护甲/武器穿回装备槽。 */
    private void caravan$restoreEquipment()
    {
        final InventoryCitizen inv = worker.getInventoryCitizen();
        for (int slot = 0; slot < inv.getSlots(); slot++)
        {
            final ItemStack stack = inv.getStackInSlot(slot);
            if (stack.isEmpty())
            {
                continue;
            }
            if (stack.getItem() instanceof ArmorItem armor)
            {
                final EquipmentSlot armorSlot = armor.getEquipmentSlot();
                if (inv.getArmorInSlot(armorSlot).isEmpty())
                {
                    inv.transferArmorToSlot(armorSlot, slot);
                }
            }
            else if (ItemStackUtils.doesItemServeAsWeapon(stack) && worker.getMainHandItem().isEmpty())
            {
                worker.setItemSlot(EquipmentSlot.MAINHAND, inv.extractItem(slot, 1, false));
            }
        }
    }

    /** 跟随/驻守中索敌：把周围敌对加入威胁表（触发卫兵自身战斗状态机）。 */
    private void caravan$scanAndThreaten()
    {
        final long time = worker.level().getGameTime();
        if (time - caravan$lastScan < 40)
        {
            return;
        }
        caravan$lastScan = time;
        if (!(worker instanceof IThreatTableEntity threat))
        {
            return;
        }
        final AABB box = worker.getBoundingBox().inflate(16);
        for (final LivingEntity entity : worker.level().getEntitiesOfClass(
            LivingEntity.class, box, e -> e != worker && caravan$isEnemy(e)))
        {
            // 需求（bug 修复）：只加入与卫兵自身距离 ≤35 格的敌人（与
            // KnightCombatAI 的追击范围一致）——否则威胁表会长期持有
            // “不可攻击”的敌人，导致领袖的 guardsBlockMovement 误判为战斗中。
            if (worker.distanceToSqr(entity) <= 35.0 * 35.0)
            {
                threat.getThreatTable().addThreat(entity, 20);
            }
        }
    }

    /** 清除威胁表与最近攻击者（隐形/回归时调用，防止 mob 继续锁定商队人员）。 */
    private void caravan$clearThreats()
    {
        if (worker instanceof IThreatTableEntity threat)
        {
            threat.getThreatTable().resetTable();
        }
        worker.setLastHurtByMob(null);
        worker.setTarget(null);
    }

    /** 诊断：卫兵是否持有武器（背包或主手）。 */
    private boolean caravan$hasWeapon()
    {
        try
        {
            final InventoryCitizen inv = worker.getInventoryCitizen();
            for (int slot = 0; slot < inv.getSlots(); slot++)
            {
                if (ItemStackUtils.doesItemServeAsWeapon(inv.getStackInSlot(slot)))
                {
                    return true;
                }
            }
        }
        catch (final Exception ignored)
        {
            // 诊断失败不阻塞。
        }
        return ItemStackUtils.doesItemServeAsWeapon(worker.getMainHandItem());
    }

    /** 敌对判定：卫兵塔【敌对】列表 + MC 敌对生物接口。 */
    private boolean caravan$isEnemy(final LivingEntity entity)
    {
        final ICitizenData data = worker.getCitizenData();
        final IBuilding tower = data != null ? data.getWorkBuilding() : null;
        if (tower != null)
        {
            final var entityId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            for (final EntityListModule list : tower.getModulesByType(EntityListModule.class))
            {
                if (list.isEntityInList(entityId))
                {
                    return true;
                }
            }
        }
        return entity instanceof Enemy;
    }
}
