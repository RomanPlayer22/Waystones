package net.blay09.mods.waystones.core;

import net.blay09.mods.waystones.WaystoneConfig;
import net.blay09.mods.waystones.api.IWaystone;
import net.blay09.mods.waystones.api.WaystoneActivatedEvent;
import net.blay09.mods.waystones.item.ModItems;
import net.blay09.mods.waystones.network.NetworkHandler;
import net.blay09.mods.waystones.network.message.MessageTeleportEffect;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;

public class PlayerWaystoneManager {

    private static IPlayerWaystoneData persistentPlayerWaystoneData = new PersistentPlayerWaystoneData();
    private static IPlayerWaystoneData inMemoryPlayerWaystoneData = new InMemoryPlayerWaystoneData();

    public static boolean mayBreakWaystone(PlayerEntity player, IBlockReader world, BlockPos pos) {
        if (WaystoneConfig.SERVER.creativeModeOnly.get() && !player.abilities.isCreativeMode) {
            return false;
        }

        IWaystone waystone = WaystoneManager.get().getWaystoneAt(world, pos).orElseThrow(IllegalStateException::new);
        if (!player.abilities.isCreativeMode) {
            if (waystone.wasGenerated() && WaystoneConfig.COMMON.disallowBreakingGenerated.get()) {
                return false;
            }

            return !waystone.isGlobal() || WaystoneConfig.SERVER.allowEveryoneGlobal.get();
        }

        return true;
    }

    public static boolean mayPlaceWaystone(@Nullable PlayerEntity player) {
        return !WaystoneConfig.SERVER.creativeModeOnly.get() || (player != null && player.abilities.isCreativeMode);
    }

    public static WaystoneEditPermissions mayEditWaystone(PlayerEntity player, World world, BlockPos pos, IWaystone waystone) {
        if (WaystoneConfig.SERVER.creativeModeOnly.get() && !player.abilities.isCreativeMode) {
            return WaystoneEditPermissions.NOT_CREATIVE;
        }

        if (WaystoneConfig.SERVER.restrictRenameToOwner.get() && !waystone.isOwner(player)) {
            return WaystoneEditPermissions.NOT_THE_OWNER;
        }

        if (waystone.isGlobal() && !player.abilities.isCreativeMode && !WaystoneConfig.SERVER.allowEveryoneGlobal.get()) {
            return WaystoneEditPermissions.GET_CREATIVE;
        }

        return WaystoneEditPermissions.ALLOW;
    }

    public static boolean isWaystoneActivated(PlayerEntity player, IWaystone waystone) {
        return getPlayerWaystoneData(player.world).isWaystoneActivated(player, waystone);
    }

    public static void activateWaystone(PlayerEntity player, IWaystone waystone) {
        getPlayerWaystoneData(player.world).activateWaystone(player, waystone);

        if (!player.world.isRemote) {
            StringTextComponent nameComponent = new StringTextComponent(waystone.getName());
            nameComponent.getStyle().setColor(TextFormatting.WHITE);
            TranslationTextComponent chatComponent = new TranslationTextComponent("chat.waystones.waystone_activated", nameComponent);
            chatComponent.getStyle().setColor(TextFormatting.YELLOW);
            player.sendMessage(chatComponent);
        }

        MinecraftForge.EVENT_BUS.post(new WaystoneActivatedEvent(player, waystone));
    }

    public static int getExperienceLevelCost(PlayerEntity player, IWaystone waystone, WarpMode warpMode) {
        boolean enableXPCost = warpMode.hasXpCost() && !player.abilities.isCreativeMode;
        if (waystone.isGlobal() && !WaystoneConfig.SERVER.globalWaystonesCostXp.get()) {
            enableXPCost = false;
        }

        BlockPos pos = waystone.getPos();
        int dist = (int) Math.sqrt(player.getDistanceSq(pos.getX(), pos.getY(), pos.getZ()));
        int xpLevelCost = WaystoneConfig.SERVER.blocksPerXPLevel.get() > 0 ? MathHelper.clamp(dist / WaystoneConfig.SERVER.blocksPerXPLevel.get(), 0, WaystoneConfig.SERVER.maximumXpCost.get()) : 0;
        return enableXPCost ? xpLevelCost : 0;
    }


    public static boolean canUseInventoryButton(PlayerEntity player) {
        return System.currentTimeMillis() - getPlayerWaystoneData(player.world).getLastInventoryWarp(player) > WaystoneConfig.SERVER.teleportButtonCooldown.get() * 1000;
    }

    public static boolean canUseWarpStone(PlayerEntity player, ItemStack heldItem) {
        return System.currentTimeMillis() - getPlayerWaystoneData(player.world).getLastWarpStoneWarp(player) > WaystoneConfig.SERVER.warpStoneCooldown.get() * 1000;
    }

    public static boolean shouldTriggerCooldown(IWaystone waystone) {
        return !waystone.isGlobal() || !WaystoneConfig.COMMON.globalNoCooldown.get();
    }

    public static boolean tryTeleportToWaystone(PlayerEntity player, IWaystone waystone, WarpMode warpMode, @Nullable IWaystone fromWaystone) {
        if (!waystone.isValid()) {
            return false;
        }

        ItemStack warpItem = findWarpItem(player, warpMode);
        if (!canUseWarpMode(player, warpMode, warpItem, fromWaystone)) {
            return false;
        }

        int xpLevelCost = getExperienceLevelCost(player, waystone, warpMode);
        if (player.experienceLevel < xpLevelCost) {
            return false;
        }

        if (warpMode == WarpMode.WARP_SCROLL) {
            warpItem.shrink(1);
        } else if (shouldTriggerCooldown(waystone)) {
            if (warpMode == WarpMode.INVENTORY_BUTTON) {
                getPlayerWaystoneData(player.world).setLastInventoryWarp(player, System.currentTimeMillis());
            } else if (warpMode == WarpMode.WARP_STONE) {
                getPlayerWaystoneData(player.world).setLastWarpStoneWarp(player, System.currentTimeMillis());
            }
        }

        if (xpLevelCost > 0) {
            player.addExperienceLevel(-xpLevelCost);
        }

        teleportToWaystone(player, waystone);
        return true;
    }

    private static ItemStack findWarpItem(PlayerEntity player, WarpMode warpMode) {
        switch (warpMode) {
            case WARP_SCROLL:
                return findWarpItem(player, ModItems.warpScroll);
            case WARP_STONE:
                return findWarpItem(player, ModItems.warpStone);
            case RETURN_SCROLL:
                return findWarpItem(player, ModItems.returnScroll);
            case BOUND_SCROLL:
                return findWarpItem(player, ModItems.boundScroll);
            default:
                return ItemStack.EMPTY;
        }
    }

    private static ItemStack findWarpItem(PlayerEntity player, Item warpItem) {
        if (player.getHeldItemMainhand().getItem() == warpItem) {
            return player.getHeldItemMainhand();
        } else if (player.getHeldItemOffhand().getItem() == warpItem) {
            return player.getHeldItemOffhand();
        } else {
            return ItemStack.EMPTY;
        }
    }

    private static void teleportToWaystone(PlayerEntity player, IWaystone waystone) {
        BlockPos pos = waystone.getPos();
        player.setPositionAndUpdate(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        NetworkHandler.channel.send(PacketDistributor.TRACKING_CHUNK.with(() -> player.world.getChunkAt(pos)), new MessageTeleportEffect(pos));
    }

    public static void deactivateWaystone(PlayerEntity player, IWaystone entry) {
        getPlayerWaystoneData(player.world).deactivateWaystone(player, entry);
    }

    private static boolean canUseWarpMode(PlayerEntity player, WarpMode warpMode, ItemStack heldItem, @Nullable IWaystone fromWaystone) {
        switch (warpMode) {
            case INVENTORY_BUTTON:
                return PlayerWaystoneManager.canUseInventoryButton(player);
            case WARP_SCROLL:
                return !heldItem.isEmpty() && heldItem.getItem() == ModItems.warpScroll;
            case BOUND_SCROLL:
                return !heldItem.isEmpty() && heldItem.getItem() == ModItems.boundScroll;
            case RETURN_SCROLL:
                return !heldItem.isEmpty() && heldItem.getItem() == ModItems.returnScroll;
            case WARP_STONE:
                return !heldItem.isEmpty() && heldItem.getItem() == ModItems.warpStone && PlayerWaystoneManager.canUseWarpStone(player, heldItem);
            case WAYSTONE_TO_WAYSTONE:
                return fromWaystone != null && fromWaystone.isValid();
        }

        return false;
    }

    public static long getLastWarpStoneWarp(PlayerEntity player) {
        return getPlayerWaystoneData(player.world).getLastWarpStoneWarp(player);
    }

    public static void setLastWarpStoneWarp(PlayerEntity player, int timeStamp) {
        getPlayerWaystoneData(player.world).setLastWarpStoneWarp(player, timeStamp);
    }

    public static long getLastInventoryWarp(PlayerEntity player) {
        return getPlayerWaystoneData(player.world).getLastInventoryWarp(player);
    }

    public static void setLastInventoryWarp(PlayerEntity player, long timeStamp) {
        getPlayerWaystoneData(player.world).setLastInventoryWarp(player, timeStamp);
    }

    @Nullable
    public static IWaystone getNearestWaystone(PlayerEntity player) {
        return getPlayerWaystoneData(player.world).getWaystones(player).stream().min((first, second) -> {
            double firstDist = first.getPos().distanceSq(player.posX, player.posY, player.posZ, true);
            double secondDist = second.getPos().distanceSq(player.posX, player.posY, player.posZ, true);
            return (int) Math.round(firstDist) - (int) Math.round(secondDist);
        }).orElse(null);
    }

    public static List<IWaystone> getWaystones(PlayerEntity player) {
        return getPlayerWaystoneData(player.world).getWaystones(player);
    }

    public static IPlayerWaystoneData getPlayerWaystoneData(World world) {
        return world.isRemote ? inMemoryPlayerWaystoneData : persistentPlayerWaystoneData;
    }

    public static IPlayerWaystoneData getPlayerWaystoneData(LogicalSide side) {
        return side.isClient() ? inMemoryPlayerWaystoneData : persistentPlayerWaystoneData;
    }

    public static boolean mayTeleportToWaystone(PlayerEntity player, IWaystone waystone) {
        return true;
    }
}