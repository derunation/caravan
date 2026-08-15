package com.example.caravan.colony.buildings.modules;

import com.google.common.reflect.TypeToken;
import com.minecolonies.api.colony.requestsystem.factory.IFactoryController;
import com.minecolonies.api.colony.requestsystem.request.IRequestFactory;
import com.minecolonies.api.colony.requestsystem.request.RequestState;
import com.minecolonies.api.colony.requestsystem.requester.IRequester;
import com.minecolonies.api.colony.requestsystem.token.IToken;
import com.minecolonies.core.colony.requestsystem.requests.StandardRequestFactories;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * 复用 minecolonies 的 StandardRequestFactories 序列化助手，保证请求可持久化/同步。
 */
public class CaravanTradeRequestFactory implements IRequestFactory<CaravanTradeRequestable, CaravanTradeRequest>
{
    private static final String NBT_RESULT = "result";
    private static final String NBT_COUNT = "count";
    /** 唯一序列化 ID（与其它工厂不冲突）。 */
    private static final short SERIALIZATION_ID = 30012;

    @Override
    public CaravanTradeRequest getNewInstance(
        final CaravanTradeRequestable requestable,
        final IRequester requester,
        final IToken<?> token,
        final RequestState state)
    {
        return new CaravanTradeRequest(requester, token, state, requestable);
    }

    @Override
    public TypeToken<? extends CaravanTradeRequest> getFactoryOutputType()
    {
        return TypeToken.of(CaravanTradeRequest.class);
    }

    @Override
    public TypeToken<? extends CaravanTradeRequestable> getFactoryInputType()
    {
        return TypeToken.of(CaravanTradeRequestable.class);
    }

    @Override
    public short getSerializationId()
    {
        return SERIALIZATION_ID;
    }

    @Override
    public CompoundTag serialize(
        final HolderLookup.Provider provider,
        final IFactoryController controller,
        final CaravanTradeRequest request)
    {
        return StandardRequestFactories.serializeToNBT(provider, controller, request,
            (lookup, factory, requestable) ->
            {
                final CompoundTag tag = new CompoundTag();
                tag.put(NBT_RESULT, requestable.getResult().saveOptional(lookup));
                tag.putInt(NBT_COUNT, requestable.getCount());
                return tag;
            });
    }

    @Override
    public CaravanTradeRequest deserialize(
        final HolderLookup.Provider provider,
        final IFactoryController controller,
        final CompoundTag tag)
    {
        return StandardRequestFactories.deserializeFromNBT(provider, controller, tag,
            (lookup, factory, nbt) -> new CaravanTradeRequestable(
                ItemStack.parseOptional(lookup, nbt.getCompound(NBT_RESULT)),
                nbt.getInt(NBT_COUNT)),
            (requestable, token, requester, state) ->
                new CaravanTradeRequest(requester, token, state, requestable));
    }

    @Override
    public void serialize(
        final IFactoryController controller,
        final CaravanTradeRequest request,
        final RegistryFriendlyByteBuf buffer)
    {
        StandardRequestFactories.serializeToRegistryFriendlyByteBuf(controller, request, buffer,
            (factory, buf, requestable) ->
            {
                ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, requestable.getResult());
                buf.writeVarInt(requestable.getCount());
            });
    }

    @Override
    public CaravanTradeRequest deserialize(
        final IFactoryController controller,
        final RegistryFriendlyByteBuf buffer)
    {
        return StandardRequestFactories.deserializeFromRegistryFriendlyByteBuf(controller, buffer,
            (factory, buf) -> new CaravanTradeRequestable(
                ItemStack.OPTIONAL_STREAM_CODEC.decode(buf),
                buf.readVarInt()),
            (requestable, token, requester, state) ->
                new CaravanTradeRequest(requester, token, state, requestable));
    }
}
