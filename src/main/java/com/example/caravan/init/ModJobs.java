package com.example.caravan.init;

import com.example.caravan.CaravanMod;
import com.example.caravan.colony.jobs.JobCaravanLeader;
import com.example.caravan.colony.jobs.JobCaravanMember;
import com.example.caravan.colony.jobs.JobCaravanGuard;
import com.minecolonies.apiimp.CommonMinecoloniesAPIImpl;
import com.minecolonies.api.colony.jobs.registry.JobEntry;
import com.minecolonies.core.colony.jobs.views.DefaultJobView;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * 创建商队领袖/商队成员职业条目，并通过 RegisterEvent 注册到 minecolonies 的
 * JOBS 注册表（与 {@link com.example.caravan.init.ModBuildings} 的建筑注册方式一致，
 * 确保服务端与客户端注册表都包含本 mod 职业——
 * 旅行地图（minecolonies JourneymapPlugin）据此显示职业名而非“无业”）。
 */
public final class ModJobs
{
    private ModJobs()
    {
    }

    public static void registerJobs(final RegisterEvent event)
    {
        if (!event.getRegistryKey().equals(CommonMinecoloniesAPIImpl.JOBS))
        {
            return;
        }
        final JobEntry leader = new JobEntry.Builder()
            .setJobProducer(JobCaravanLeader::new)
            .setJobViewProducer(() -> DefaultJobView::new)
            .setRegistryName(ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "caravan_leader"))
            .createJobEntry();
        final JobEntry member = new JobEntry.Builder()
            .setJobProducer(JobCaravanMember::new)
            .setJobViewProducer(() -> DefaultJobView::new)
            .setRegistryName(ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "caravan_member"))
            .createJobEntry();
        final JobEntry guard = new JobEntry.Builder()
            .setJobProducer(JobCaravanGuard::new)
            .setJobViewProducer(() -> DefaultJobView::new)
            .setRegistryName(ResourceLocation.fromNamespaceAndPath(CaravanMod.MODID, "caravan_guard"))
            .createJobEntry();
        event.register(CommonMinecoloniesAPIImpl.JOBS, helper ->
        {
            helper.register(leader.getKey(), leader);
            helper.register(member.getKey(), member);
            helper.register(guard.getKey(), guard);
        });
        CaravanMod.JOB_CARAVAN_LEADER = leader;
        CaravanMod.JOB_CARAVAN_MEMBER = member;
        CaravanMod.JOB_CARAVAN_GUARD = guard;
    }
}
