package com.example.caravan.colony.buildings.modules;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.location.ILocation;
import com.minecolonies.api.colony.requestsystem.resolver.IRequestResolverFactory;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.api.util.constant.TypeConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * 需求（请求系统接入）：{@link CaravanTradeRequestResolver} 的工厂——
 * 请求系统持久化/重建 resolver 时使用（序列化为 token + location）。
 */
public class CaravanTradeRequestResolverFactory implements IRequestResolverFactory<CaravanTradeRequestResolver>
{
    private static final String NBT_TOKEN = "Token";
    private static final String NBT_LOCATION = "Location";
    /** 唯一序列化 ID（short 范围，避免与既有工厂冲突）。 */
    private static final short SERIALIZATION_ID = 30010;

    @Override
    public TypeToken<? extends CaravanTradeRequestResolver> getFactoryOutputType()
    {
        return TypeToken.of(CaravanTradeRequestResolver.class);
    }

    @Override
    public TypeToken<? extends ILocation> getFactoryInputType()
    {
        return TypeConstants.ILOCATION;
    }

    @Override
    public short getSerializationId()
    {
        return SERIALIZATION_ID;
    }

    @Override
    public CaravanTradeRequestResolver getNewInstance(
        final IFactoryController controller,
        final ILocation location,
        final Object... context)
    {
        final IToken<?> token = controller.getNewInstance(TypeConstants.ITOKEN);
        return new CaravanTradeRequestResolver(location, token);
    }

    @Override
    public CompoundTag serialize(
        final HolderLookup.Provider provider,
        final IFactoryController controller,
        final CaravanTradeRequestResolver resolver)
    {
        final CompoundTag tag = new CompoundTag();
        tag.put(NBT_TOKEN, controller.serializeTag(provider, resolver.getId()));
        tag.put(NBT_LOCATION, controller.serializeTag(provider, resolver.getLocation()));
        return tag;
    }

    @Override
    public CaravanTradeRequestResolver deserialize(
        final HolderLookup.Provider provider,
        final IFactoryController controller,
        final CompoundTag tag)
    {
        final IToken<?> token = (IToken<?>) controller.deserializeTag(provider, tag.getCompound(NBT_TOKEN));
        final ILocation location = (ILocation) controller.deserializeTag(provider, tag.getCompound(NBT_LOCATION));
        return new CaravanTradeRequestResolver(location, token);
    }

    @Override
    public void serialize(
        final IFactoryController controller,
        final CaravanTradeRequestResolver resolver,
        final RegistryFriendlyByteBuf buffer)
    {
        controller.serialize(buffer, resolver.getId());
        controller.serialize(buffer, resolver.getLocation());
    }

    @Override
    public CaravanTradeRequestResolver deserialize(
        final IFactoryController controller,
        final RegistryFriendlyByteBuf buffer)
    {
        final IToken<?> token = (IToken<?>) controller.deserialize(buffer);
        final ILocation location = (ILocation) controller.deserialize(buffer);
        return new CaravanTradeRequestResolver(location, token);
    }
}
