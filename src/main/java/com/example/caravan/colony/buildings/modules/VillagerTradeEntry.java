package com.example.caravan.colony.buildings.modules;

import com.example.caravan.waystone.WaystoneHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 已记录的村民：身份、职业、等级、工作方块位置及其全部交易
 * （含每笔交易的次数上限），以及模拟获得的经验（用于村民未加载时的升级模拟）。
 */
public record VillagerTradeEntry(
    UUID villagerId,
    String profession,
    int level,
    BlockPos workstationPos,
    List<TradeOfferData> offers,
    int xpEarned,
    int pendingXp,
    UUID waystoneUid,
    String waystoneName)
{
    public static final String WAYSTONE_UNNAMED = "@waystone";
    private static final String TAG_ID = "id";
    private static final String TAG_PROFESSION = "profession";
    private static final String TAG_LEVEL = "level";
    private static final String TAG_POS = "pos";
    private static final String TAG_OFFERS = "offers";
    private static final String TAG_XP = "xp";
    private static final String TAG_PENDING_XP = "pendingXp";
    private static final String TAG_WAYSTONE_UID = "waystoneUid";
    private static final String TAG_WAYSTONE = "waystone";

    /**
     * Captures a vanilla villager: all current trades plus the workstation
     * position (the villager's claimed job site, falling back to its own
     * position).
     */
    public static VillagerTradeEntry fromVillager(final Villager villager, final ServerLevel level)
    {
        final List<TradeOfferData> offers = new ArrayList<>();
        for (final MerchantOffer offer : villager.getOffers())
        {
            offers.add(new TradeOfferData(
                offer.getCostA().copy(),
                offer.getCostB().copy(),
                offer.getResult().copy(),
                offer.getMaxUses(),
                offer.getXp()));
        }

        final BlockPos workstation = villager.getBrain()
            .getMemory(MemoryModuleType.JOB_SITE)
            .map(GlobalPos::pos)
            .orElse(villager.blockPosition());

        final ResourceLocation professionKey = BuiltInRegistries.VILLAGER_PROFESSION.getKey(villager.getVillagerData().getProfession());
        final String profession = professionKey != null ? professionKey.toString() : "unknown";
        // 修复：xpEarned 记录村民实体的【当前总经验】（用于 GUI 显示与升级模拟），
        // pendingXp 单独记录尚未结算给实体的模拟经验，二者不再混用。
        final WaystoneHelper.WaystoneInfo waystone = WaystoneHelper.findWaystoneNear(level, workstation);
        return new VillagerTradeEntry(
            villager.getUUID(), profession, villager.getVillagerData().getLevel(),
            workstation, offers, villager.getVillagerXp(), 0,
            waystone != null ? waystone.waystoneUid() : null,
            waystone != null ? waystone.waystoneName() : null);
    }

    public static WaystoneHelper.WaystoneInfo refreshWaystoneInfo(final ServerLevel level, final BlockPos workstationPos)
    {
        return WaystoneHelper.findWaystoneNear(level, workstationPos);
    }

    public CompoundTag save(final HolderLookup.Provider provider)
    {
        final CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, villagerId);
        tag.putString(TAG_PROFESSION, profession);
        tag.putInt(TAG_LEVEL, level);
        tag.putLong(TAG_POS, workstationPos.asLong());
        final ListTag offerList = new ListTag();
        for (final TradeOfferData offer : offers)
        {
            offerList.add(offer.save(provider));
        }
        tag.put(TAG_OFFERS, offerList);
        tag.putInt(TAG_XP, xpEarned);
        tag.putInt(TAG_PENDING_XP, pendingXp);
        if (waystoneUid != null)
        {
            tag.putUUID(TAG_WAYSTONE_UID, waystoneUid);
        }
        if (waystoneName != null)
        {
            tag.putString(TAG_WAYSTONE, waystoneName);
        }
        return tag;
    }

    public static VillagerTradeEntry load(final HolderLookup.Provider provider, final CompoundTag tag)
    {
        final List<TradeOfferData> offers = new ArrayList<>();
        for (final Tag element : tag.getList(TAG_OFFERS, Tag.TAG_COMPOUND))
        {
            offers.add(TradeOfferData.load(provider, (CompoundTag) element));
        }
        return new VillagerTradeEntry(
            tag.getUUID(TAG_ID),
            tag.getString(TAG_PROFESSION),
            tag.getInt(TAG_LEVEL),
            BlockPos.of(tag.getLong(TAG_POS)),
            offers,
            tag.getInt(TAG_XP),
            tag.getInt(TAG_PENDING_XP),
            tag.contains(TAG_WAYSTONE_UID) ? tag.getUUID(TAG_WAYSTONE_UID) : null,
            tag.contains(TAG_WAYSTONE) ? tag.getString(TAG_WAYSTONE) : null);
    }

    public void toBuffer(final RegistryFriendlyByteBuf buffer)
    {
        buffer.writeUUID(villagerId);
        buffer.writeUtf(profession);
        buffer.writeVarInt(level);
        BlockPos.STREAM_CODEC.encode(buffer, workstationPos);
        buffer.writeVarInt(offers.size());
        for (final TradeOfferData offer : offers)
        {
            offer.toBuffer(buffer);
        }
        buffer.writeVarInt(xpEarned);
        buffer.writeVarInt(pendingXp);
        buffer.writeBoolean(waystoneUid != null);
        if (waystoneUid != null)
        {
            buffer.writeUUID(waystoneUid);
        }
        buffer.writeBoolean(waystoneName != null);
        if (waystoneName != null)
        {
            buffer.writeUtf(waystoneName);
        }
    }

    public static VillagerTradeEntry fromBuffer(final RegistryFriendlyByteBuf buffer)
    {
        final UUID id = buffer.readUUID();
        final String profession = buffer.readUtf();
        final int level = buffer.readVarInt();
        final BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
        final int count = buffer.readVarInt();
        final List<TradeOfferData> offers = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
        {
            offers.add(TradeOfferData.fromBuffer(buffer));
        }
        final int xpEarned = buffer.readVarInt();
        final int pendingXp = buffer.readVarInt();
        final UUID waystoneUid = buffer.readBoolean() ? buffer.readUUID() : null;
        final String waystoneName = buffer.readBoolean() ? buffer.readUtf() : null;
        return new VillagerTradeEntry(id, profession, level, pos, offers, xpEarned, pendingXp, waystoneUid, waystoneName);
    }
}
