package com.example.caravan.entity.ai;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.buildings.BuildingCaravanLeader;
import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.example.caravan.colony.jobs.JobCaravanMember;
import com.example.caravan.entity.CaravanExperienceHandler;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.ai.statemachine.AITarget;
import com.minecolonies.api.entity.ai.statemachine.states.AIWorkerState;
import com.minecolonies.api.entity.ai.statemachine.states.IAIState;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IBooleanConditionSupplier;
import com.minecolonies.api.entity.ai.statemachine.tickratestatemachine.IStateSupplier;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.constant.CitizenConstants;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;
import com.minecolonies.core.entity.ai.workers.AbstractEntityAIBasic;
import com.minecolonies.core.util.AttributeModifierUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 商队成员 AI（跟随商队领袖进行交易）。
 * <ul>
 *   <li>领袖在殖民地内且未消失：保持 6 格以内的跟随距离（超出则寻路跟上）；</li>
 *   <li>领袖消失（去程/回程模拟）时：寻路到领袖的消失位置，到达后与领袖一同
 *       消失（隐形）；领袖重新出现时，一同现身并继续跟随；</li>
 *   <li>领袖物品栏不足时，由领袖 AI 把溢出的交易品转入成员物品栏
 *       （成员作为扩展背包，见 EntityAIWorkCaravanLeader）。</li>
 * </ul>
 */
public class EntityAIWorkCaravanMember extends AbstractEntityAIBasic<JobCaravanMember, BuildingCaravanLeader>
{
    /** 跟随领袖的最大距离（格）。 */
    private static final int FOLLOW_DISTANCE = 6;
    /** 小屋附近待命范围平方（备货阶段成员在小屋待命）。 */
    private static final int HUT_RANGE_SQUARED = 100;
    /** 到达领袖消失位置（视为已消失）的判定距离平方。 */
    private static final int VANISH_RANGE_SQUARED = 9;
    private static final int WANDER_RADIUS = 5;

    /** 商队成员自定义状态。 */
    private enum MemberState implements IAIState
    {
        /** 跟随商队领袖。 */
        FOLLOW_LEADER,
        /** 领袖已消失：走到领袖的消失位置。 */
        VANISH_PREP,
        /** 与领袖一同消失（隐形，等待领袖归来）。 */
        VANISHED;

        @Override
        public boolean isOkayToEat()
        {
            // 消失期间不允许进食（隐形状态）。
            return this != VANISHED;
        }
    }

    /** 是否已与领袖一同消失（隐形）。 */
    private boolean memberVanished;
    private int wanderTimer;

    public EntityAIWorkCaravanMember(final JobCaravanMember job)
    {
        super(job);

        super.registerTargets(
            new AITarget<IAIState>(AIWorkerState.IDLE,
                (IBooleanConditionSupplier) this::shouldFollowLeader,
                (IStateSupplier<IAIState>) () -> isLeaderAway()
                    ? MemberState.VANISH_PREP
                    : MemberState.FOLLOW_LEADER, 20),
            new AITarget<IAIState>(MemberState.FOLLOW_LEADER,
                (IStateSupplier<IAIState>) this::followLeader, 20),
            new AITarget<IAIState>(MemberState.VANISH_PREP,
                (IStateSupplier<IAIState>) this::vanishPrep, 20),
            new AITarget<IAIState>(MemberState.VANISHED,
                (IStateSupplier<IAIState>) this::vanished, 20));

        worker.setCitizenExperienceHandler(
            new CaravanExperienceHandler(worker, worker.getCitizenExperienceHandler()));
        refreshSpeedBonus();
        if (isLeaderAway())
        {
            memberVanished = true;
            worker.setInvisible(true);
            worker.setInvulnerable(true);
            if (worker instanceof com.minecolonies.api.entity.ai.combat.threat.IThreatTableEntity threat)
            {
                threat.getThreatTable().resetTable();
            }
        }
    }

    @Override
    public Class<BuildingCaravanLeader> getExpectedBuildingClass()
    {
        return BuildingCaravanLeader.class;
    }

    /** 空闲时：存在领袖才进入对应状态（未消失 → 跟随；已消失 → 前往消失位置）；
     *  生病的商队成员不跟随商队领袖（留在殖民地，由本体 AI 管理）。 */
    private boolean shouldFollowLeader()
    {
        final ICitizenData data = worker.getCitizenData();
        if (data != null && data.getCitizenDiseaseHandler().isSick())
        {
            return false;
        }
        return findLeader() != null;
    }

    /** 跟随状态：领袖消失 → 回小屋；距离过远 → 寻路跟上；贴近 → 待机。 */
    private IAIState followLeader()
    {
        final AbstractEntityCitizen leader = findLeader();
        // 穿越结束重新隐形）时成员前往消失；领袖可见（穿越步行中）时保持跟随，
        // 不再依赖 walking 标志，避免标志时序抖动导致成员反复消失/现身。
        if (isLeaderAway() && (leader == null || leader.isInvisible()))
        {
            return MemberState.VANISH_PREP;
        }
        if (leader == null)
        {
            return AIWorkerState.IDLE;
        }
        // 不跟随领袖走动（小屋方块附近即可存取存储，成员需留出空间）。
        final JobCaravanLeader leaderJob = findLeaderJob();
        if (leaderJob != null && leaderJob.getStatus() != JobCaravanLeader.CaravanStatus.TRADING)
        {
            if (worker.blockPosition().distSqr(building.getPosition()) > HUT_RANGE_SQUARED)
            {
                walkToBuilding();
                return MemberState.FOLLOW_LEADER;
            }
            wanderNearHut();
            return AIWorkerState.IDLE;
        }
        if (worker.blockPosition().distSqr(leader.blockPosition()) > FOLLOW_DISTANCE * FOLLOW_DISTANCE)
        {
            // 避免长时间寻路导致单个成员掉队。
            if (worker.blockPosition().distSqr(leader.blockPosition()) > 100 * 100)
            {
                worker.teleportTo(
                    leader.getX() + world.random.nextInt(3) - 1,
                    leader.getY(),
                    leader.getZ() + world.random.nextInt(3) - 1);
                worker.getNavigation().stop();
                return MemberState.FOLLOW_LEADER;
            }
            walkToUnSafePos(leader.blockPosition());
            return MemberState.FOLLOW_LEADER;
        }
        return AIWorkerState.IDLE;
    }

    private void wanderNearHut()
    {
        if ((wanderTimer += 20) < 100)
        {
            return;
        }
        wanderTimer = 0;
        final BlockPos target = building.getPosition().offset(
            world.random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS,
            0,
            world.random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS);
        walkToUnSafePos(target);
    }

    private IAIState vanishPrep()
    {
        final AbstractEntityCitizen leader = findLeader();
        if (leader == null || !isLeaderAway() || !leader.isInvisible())
        {
            // 领袖未消失或已重新现身（如穿越开始）：回到 IDLE，重新进入跟随。
            return AIWorkerState.IDLE;
        }
        if (worker.blockPosition().distSqr(leader.blockPosition()) > VANISH_RANGE_SQUARED)
        {
            // 而不是缓慢寻路（消失点可能远在殖民地边界外）。
            if (worker.blockPosition().distSqr(leader.blockPosition()) > 100 * 100)
            {
                worker.teleportTo(
                    leader.getX() + world.random.nextInt(3) - 1,
                    leader.getY(),
                    leader.getZ() + world.random.nextInt(3) - 1);
                worker.getNavigation().stop();
                return MemberState.VANISH_PREP;
            }
            walkToUnSafePos(leader.blockPosition());
            return MemberState.VANISH_PREP;
        }
        // 到达领袖消失位置：与领袖一同消失。
        worker.getNavigation().stop();
        worker.setInvisible(true);
        worker.setInvulnerable(true);
        if (worker instanceof com.minecolonies.api.entity.ai.combat.threat.IThreatTableEntity threat)
        {
            threat.getThreatTable().resetTable();
        }
        memberVanished = true;
        return MemberState.VANISHED;
    }

    private IAIState vanished()
    {
        // 以领袖实体的可见性为准，避免标志抖动导致反复消失/现身。
        final AbstractEntityCitizen leader = findLeader();
        if (!isLeaderAway() || (leader != null && !leader.isInvisible()))
        {
            memberVanished = false;
            worker.setInvisible(false);
            worker.setInvulnerable(false);
            if (worker instanceof com.minecolonies.api.entity.ai.combat.threat.IThreatTableEntity threat)
            {
                threat.getThreatTable().resetTable();
            }
            worker.setLastHurtByMob(null);
            worker.setTarget(null);
            return AIWorkerState.IDLE;
        }
        return MemberState.VANISHED;
    }

    /** 商队领袖是否处于消失（去程/回程）状态。 */
    private boolean isLeaderAway()
    {
        final JobCaravanLeader leaderJob = findLeaderJob();
        return leaderJob != null && leaderJob.isAway();
    }

    /** 从商队小屋的工作模块中查找商队领袖的市民数据。 */
    private JobCaravanLeader findLeaderJob()
    {
        for (final WorkerBuildingModule module : building.getModulesByType(WorkerBuildingModule.class))
        {
            if (module.getJobEntry().getKey().equals(CaravanMod.JOB_CARAVAN_LEADER.getKey()))
            {
                for (final ICitizenData data : module.getAssignedCitizen())
                {
                    if (data.getJob() instanceof JobCaravanLeader leaderJob)
                    {
                        return leaderJob;
                    }
                }
            }
        }
        return null;
    }

    /** 商队领袖的实体（可能未加载，返回 null）。 */
    private AbstractEntityCitizen findLeader()
    {
        final JobCaravanLeader leaderJob = findLeaderJob();
        return leaderJob != null
            ? leaderJob.getCitizen().getEntity().orElse(null)
            : null;
    }

    /** 上次应用速度加成的敏捷等级。 */
    private int lastAgilityLevel = -1;

    private void refreshSpeedBonus()
    {
        final int agility = job.getCitizen() != null
            ? job.getCitizen().getCitizenSkillHandler().getLevel(Skill.Agility)
            : 0;
        if (agility == lastAgilityLevel)
        {
            return;
        }
        lastAgilityLevel = agility;
        final AttributeModifier modifier = new AttributeModifier(
            CitizenConstants.SKILL_BONUS_ADD_NAME,
            agility * 0.003D,
            AttributeModifier.Operation.ADD_VALUE);
        AttributeModifierUtils.addModifier(worker, modifier, Attributes.MOVEMENT_SPEED);
    }
}
