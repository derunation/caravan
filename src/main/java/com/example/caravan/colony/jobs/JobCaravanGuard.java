package com.example.caravan.colony.jobs;

import com.minecolonies.api.client.render.modeltype.ModModelTypes;
import com.minecolonies.api.colony.ICitizenData;
import net.minecraft.resources.ResourceLocation;

/**
 * 商队卫兵职业（对应商队小屋的卫兵工作模块）。
 * <p>模型参照 Minecolonies 本体的【骑士】（KNIGHT_ID）；属性由建筑工作模块定义
 * （主力量、副耐力，参照骑士）。装备请求由 {@code CaravanGuardEquipmentModule}
 * 处理；AI 行为（驻守/跟随/战斗）在后续版本扩展——当前复用商队成员 AI。</p>
 */
public class JobCaravanGuard extends JobCaravanMember
{
    public JobCaravanGuard(final ICitizenData citizen)
    {
        super(citizen);
    }

    @Override
    public ResourceLocation getModel()
    {
        return ModModelTypes.KNIGHT_ID;
    }
}
