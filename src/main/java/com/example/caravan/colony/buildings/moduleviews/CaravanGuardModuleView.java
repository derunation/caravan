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

public class CaravanGuardModuleView extends AbstractBuildingModuleView
{
    /** 一条护卫卫兵信息（名字/所属卫兵塔/塔是否已指派）。 */
    public record GuardEntry(int citizenId, String name, BlockPos towerPos, boolean assigned)
    {
    }

    private final List<GuardEntry> guards = new ArrayList<>();

    @Override
    public void deserialize(final RegistryFriendlyByteBuf buffer)
    {
        guards.clear();
        final int count = buffer.readVarInt();
        for (int i = 0; i < count; i++)
        {
            guards.add(new GuardEntry(
                buffer.readVarInt(),
                buffer.readUtf(),
                new BlockPos(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()),
                buffer.readBoolean()));
        }
    }

    public List<GuardEntry> getGuards()
    {
        return guards;
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

    @Override
    public ResourceLocation getIconResourceLocation()
    {
        return ResourceLocation.fromNamespaceAndPath("minecolonies", "textures/gui/modules/sword.png");
    }
}
