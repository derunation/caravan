package com.example.caravan.colony.jobs;

import com.example.caravan.entity.ai.EntityAIWorkCaravanMember;
import com.minecolonies.api.client.render.modeltype.ModModelTypes;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.entity.citizen.Skill;
import com.minecolonies.api.util.constant.CitizenConstants;
import com.minecolonies.core.colony.jobs.AbstractJob;
import com.minecolonies.core.util.AttributeModifierUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * 商队成员职业（对应商队小屋的第二个工作模块，可同时雇佣 1-5 名）。
 * <p>商队成员跟随商队领袖行动，并为领袖提供额外的物品栏空间；
 * 属性与商队领袖一致（主敏捷、副智力，配合 {@code CaravanExperienceHandler}
 * 的自定义经验分配）。</p>
 */
public class JobCaravanMember extends AbstractJob<EntityAIWorkCaravanMember, JobCaravanMember>
{
    public JobCaravanMember(final ICitizenData citizen)
    {
        super(citizen);
    }

    @Override
    public EntityAIWorkCaravanMember generateAI()
    {
        return new EntityAIWorkCaravanMember(this);
    }

    @Override
    public ResourceLocation getModel()
    {
        return ModModelTypes.COURIER_ID;
    }

    @Override
    public void onLevelUp()
    {
        applySpeedBonus();
    }

    public void applySpeedBonus()
    {
        getCitizen().getEntity().ifPresent(entity ->
        {
            final int agility = getCitizen().getCitizenSkillHandler().getLevel(Skill.Agility);
            final AttributeModifier modifier = new AttributeModifier(
                CitizenConstants.SKILL_BONUS_ADD_NAME,
                agility * 0.003D,
                AttributeModifier.Operation.ADD_VALUE);
            AttributeModifierUtils.addModifier(entity, modifier, Attributes.MOVEMENT_SPEED);
        });
    }
}
