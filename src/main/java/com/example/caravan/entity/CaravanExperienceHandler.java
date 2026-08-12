package com.example.caravan.entity;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.entity.citizen.AbstractEntityCitizen;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenExperienceHandler;
import com.minecolonies.api.entity.citizen.citizenhandlers.ICitizenSkillHandler;
import com.minecolonies.api.research.util.ResearchConstants;
import com.minecolonies.core.colony.buildings.modules.WorkerBuildingModule;

/**
 * 商队领袖/商队成员的自定义经验分配处理器。
 * <p>需求属性配置（与 minecolonies 默认的“主/副属性+互补/相克”分布不同，需单独实现）：</p>
 * <ul>
 *   <li>敏捷：+100% XP（主属性）</li>
 *   <li>适应：+10% XP</li>
 *   <li>魔力：-10% XP</li>
 *   <li>智力：+50% XP（副属性）</li>
 *   <li>运动：+5% XP</li>
 *   <li>创意：-5% XP</li>
 * </ul>
 * <p>经验总量加成（建筑等级、智力、研究、饱和度检查）与 minecolonies 本体一致，
 * 其余方法委托给原处理器。</p>
 */
public class CaravanExperienceHandler implements ICitizenExperienceHandler
{
    private final AbstractEntityCitizen citizen;
    private final ICitizenExperienceHandler delegate;

    public CaravanExperienceHandler(final AbstractEntityCitizen citizen, final ICitizenExperienceHandler delegate)
    {
        this.citizen = citizen;
        this.delegate = delegate;
    }

    @Override
    public void addExperience(final double xp)
    {
        final ICitizenData data = citizen.getCitizenData();
        if (data == null)
        {
            return;
        }
        // 与本体一致：必须有工作建筑且建筑带 WorkerBuildingModule，否则不给经验。
        final IBuilding workBuilding = data.getWorkBuilding();
        if (workBuilding == null || !workBuilding.hasModule(WorkerBuildingModule.class))
        {
            return;
        }
        // 经验总量：1 + (工作建筑等级 + 住宅建筑等级) / 10 倍率。
        final IBuilding home = citizen.getCitizenColonyHandler().getHomeBuilding();
        final double homeLevel = home != null ? home.getBuildingLevelEquivalent() : 0.0;
        double amount = xp * (1.0 + (workBuilding.getBuildingLevelEquivalent() + homeLevel) / 10.0);
        // 饱食度 <= 0 时不获得经验（与本体一致）。
        if (data.getSaturation() <= 0.0)
        {
            return;
        }
        // 智力加成：经验 = 经验 + 经验 × 智力 / 100（与本体一致）。
        final int intelligence = data.getCitizenSkillHandler().getLevel(Skill.Intelligence);
        amount += amount * (intelligence / 100.0);
        // 研究加成（本体 LEVELING 研究）。
        amount *= 1.0 + data.getColony().getResearchManager()
            .getResearchEffects().getEffectStrength(ResearchConstants.LEVELING);

        // 自定义分配：敏捷 100%、适应 10%、魔力 -10%、智力 50%、运动 5%、创意 -5%。
        final ICitizenSkillHandler skills = data.getCitizenSkillHandler();
        skills.addXpToSkill(Skill.Agility, amount, data);
        skills.addXpToSkill(Skill.Adaptability, amount * 0.1, data);
        skills.removeXpFromSkill(Skill.Mana, amount * 0.1, data);
        skills.addXpToSkill(Skill.Intelligence, amount * 0.5, data);
        skills.addXpToSkill(Skill.Athletics, amount * 0.05, data);
        skills.removeXpFromSkill(Skill.Creativity, amount * 0.05, data);
    }

    @Override
    public void updateLevel()
    {
        delegate.updateLevel();
    }

    @Override
    public void dropExperience()
    {
        delegate.dropExperience();
    }

    @Override
    public void gatherXp()
    {
        delegate.gatherXp();
    }
}
