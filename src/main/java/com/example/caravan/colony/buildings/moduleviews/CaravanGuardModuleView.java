package com.example.caravan.colony.buildings.moduleviews;

import com.example.caravan.client.gui.modules.WindowCaravanGuard;
import com.ldtteam.blockui.views.BOWindow;
import com.minecolonies.api.colony.buildings.modules.AbstractBuildingModuleView;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** 需求（商队护卫）：【护卫】页客户端视图——商队护卫模式卫兵塔列表。 */
public class CaravanGuardModuleView extends AbstractBuildingModuleView
{
    /** 一条护卫卫兵塔信息。 */
    public record GuardTowerEntry(BlockPos towerPos, String name, int guardCount, boolean assigned)
    {
    }

    private final List<GuardTowerEntry> towers = new ArrayList<>();

    @Override
    public void deserialize(final RegistryFriendlyByteBuf buffer)
    {
        towers.clear();
        final int count = buffer.readVarInt();
        for (int i = 0; i < count; i++)
        {
            towers.add(new GuardTowerEntry(
                new BlockPos(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()),
                buffer.readUtf(),
                buffer.readVarInt(),
                buffer.readBoolean()));
        }
    }

    public List<GuardTowerEntry> getTowers()
    {
        return towers;
    }

    @Override
    public BOWindow getWindow()
    {
        return new WindowCaravanGuard(getBuildingView(), this);
    }

    @Override
    public Component getDesc()
    {
        return Component.translatable("com.caravan.gui.guard");
    }

    /** 需求（GUI）：标签页图标使用【矿井】-【守卫指派】页图标（sword.png）。 */
    @Override
    public ResourceLocation getIconResourceLocation()
    {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/sword.png");
    }
}
