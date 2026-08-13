package com.example.caravan.colony.jobs;

import com.example.caravan.entity.ai.EntityAIWorkCaravanGuard;
import com.minecolonies.api.client.render.modeltype.ModModelTypes;
import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.core.colony.jobs.AbstractJob;
import net.minecraft.resources.ResourceLocation;

/**
 * 商队卫兵职业（对应商队小屋的卫兵工作模块）。
 * <p>模型参照 Minecolonies 本体的【骑士】（KNIGHT_ID）；属性由建筑工作模块定义
 * （主力量、副耐力，参照骑士）。装备请求由 {@code CaravanGuardEquipmentModule} 处理，
 * AI 由 {@link EntityAIWorkCaravanGuard} 实现（守卫-跟随-索敌战斗）。</p>
 */
public class JobCaravanGuard extends AbstractJob<EntityAIWorkCaravanGuard, JobCaravanGuard>
{
    public JobCaravanGuard(final ICitizenData citizen)
    {
        super(citizen);
    }

    @Override
    public EntityAIWorkCaravanGuard generateAI()
    {
        return new EntityAIWorkCaravanGuard(this);
    }

    @Override
    public ResourceLocation getModel()
    {
        return ModModelTypes.KNIGHT_ID;
    }
}
