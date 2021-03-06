/*
 * Copyright (C) 2016-2020 David Rubio Escares / Kodehawa
 *
 *  Mantaro is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  Mantaro is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands.currency.item;

import net.kodehawa.mantarobot.commands.currency.item.special.Broken;
import net.kodehawa.mantarobot.commands.currency.item.special.Potion;
import net.kodehawa.mantarobot.commands.currency.item.special.helpers.Breakable;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.commands.currency.seasons.SeasonPlayer;
import net.kodehawa.mantarobot.core.modules.commands.base.Context;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBUser;
import net.kodehawa.mantarobot.db.entities.Player;
import net.kodehawa.mantarobot.db.entities.helpers.Inventory;
import net.kodehawa.mantarobot.db.entities.helpers.PlayerData;
import net.kodehawa.mantarobot.db.entities.helpers.UserData;
import net.kodehawa.mantarobot.utils.RandomCollection;
import net.kodehawa.mantarobot.utils.RatelimitUtils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.ratelimit.IncreasingRateLimiter;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ItemHelper {
    private static final Logger log = LoggerFactory.getLogger(ItemHelper.class);
    private static final Random random = new Random();
    private static final IncreasingRateLimiter lootCrateRatelimiter = new IncreasingRateLimiter.Builder()
            .limit(1)
            .spamTolerance(2)
            .cooldown(20, TimeUnit.SECONDS)
            .maxCooldown(2, TimeUnit.MINUTES)
            .randomIncrement(false)
            .pool(MantaroData.getDefaultJedisPool())
            .prefix("lootcrate")
            .premiumAware(true)
            .build();
    public static void setItemActions() {
        log.info("Registering item actions...");

        ItemReference.MOP.setAction(((ctx, season) -> {
            Player player = ctx.getPlayer();
            PlayerData playerData = player.getData();
            DBUser dbUser = ctx.getDBUser();
            UserData userData = dbUser.getData();
            Inventory playerInventory = player.getInventory();

            if (!playerInventory.containsItem(ItemReference.MOP))
                return false;

            if(dbUser.getData().getDustLevel() > 5) {
                playerData.setTimesMopped(playerData.getTimesMopped() + 1);
                player.save();
                ctx.sendLocalized("general.misc_item_usage.mop", EmoteReference.DUST);
                playerInventory.process(new ItemStack(ItemReference.MOP, -1));

                userData.setDustLevel(0);
                dbUser.save();
            } else {
                ctx.sendLocalized("general.misc_item_usage.mop_not_enough", EmoteReference.DUST);
                return false;
            }

            return true;
        }));

        ItemReference.POTION_CLEAN.setAction((ctx, season) -> {
            Player player = ctx.getPlayer();
            DBUser dbUser = ctx.getDBUser();
            UserData userData = dbUser.getData();
            Inventory playerInventory = player.getInventory();

            userData.getEquippedItems().resetEffect(PlayerEquipment.EquipmentType.POTION);
            dbUser.save();

            playerInventory.process(new ItemStack(ItemReference.POTION_CLEAN, -1));
            player.save();

            ctx.sendLocalized("general.misc_item_usage.milk", EmoteReference.CORRECT);
            return true;
        });
    }

    public static Optional<Item> fromAny(String any) {
        try {
            Item item = fromId(Integer.parseInt(any));

            if (item != null)
                return Optional.of(item);
        } catch (NumberFormatException ignored) { }

        return fromAnyNoId(any);
    }

    public static Optional<Item> fromAnyNoId(String any) {
        Optional<Item> itemOptional;

        itemOptional = fromEmoji(any);
        if (itemOptional.isPresent())
            return itemOptional;

        itemOptional = fromAlias(any);
        if (itemOptional.isPresent())
            return itemOptional;

        itemOptional = fromName(any);
        if (itemOptional.isPresent())
            return itemOptional;

        itemOptional = fromPartialName(any);
        return itemOptional;
    }

    public static Optional<Item> fromEmoji(String emoji) {
        return Stream.of(ItemReference.ALL)
                .filter(item -> item.getEmoji().equals(emoji.replace("\ufe0f", "")))
                .findFirst();
    }

    public static Item fromId(int id) {
        return ItemReference.ALL[id];
    }

    public static Optional<Item> fromName(String name) {
        return Arrays.stream(ItemReference.ALL)
                .filter(item -> item.getName().toLowerCase().trim().equals(name.toLowerCase().trim()))
                .findFirst();
    }

    public static Optional<Item> fromAlias(String name) {
        return Arrays.stream(ItemReference.ALL).filter(item -> {
            if (item.getAlias() == null) {
                return false;
            }

            return item.getAlias()
                    .toLowerCase()
                    .trim()
                    .equals(name.toLowerCase().trim());
        }).findFirst();
    }

    public static Optional<Item> fromPartialName(String name) {
        return Arrays.stream(ItemReference.ALL)
                .filter(item -> item.getName().toLowerCase().trim().contains(name.toLowerCase().trim()))
                .findFirst();
    }

    public static int idOf(Item item) {
        return Arrays.asList(ItemReference.ALL)
                .indexOf(item);
    }

    static boolean openLootCrate(Context ctx, ItemType.LootboxType type, int item, EmoteReference typeEmote, int bound, boolean season) {
        Player player = ctx.getPlayer();
        SeasonPlayer seasonPlayer = ctx.getSeasonPlayer();
        Inventory inventory = season ? seasonPlayer.getInventory() : player.getInventory();

        Item crate = fromId(item);

        if(inventory.containsItem(crate)) {
            if(inventory.containsItem(ItemReference.LOOT_CRATE_KEY)) {
                if(!RatelimitUtils.handleIncreasingRatelimit(lootCrateRatelimiter, ctx.getAuthor(), ctx.getEvent(),
                        ctx.getLanguageContext(), false))
                    return false;

                if(crate == ItemReference.LOOT_CRATE) {
                    player.getData().addBadgeIfAbsent(Badge.THE_SECRET);
                }

                //It saves the changes here.
                openLootBox(ctx, player, seasonPlayer, type, crate, typeEmote, bound, season);
                return true;
            } else {
                ctx.sendLocalized("general.misc_item_usage.crate.no_key", EmoteReference.ERROR);
                return false;
            }
        } else {
            ctx.sendLocalized("general.misc_item_usage.crate.no_crate", EmoteReference.ERROR);
            return false;
        }
    }

    private static void openLootBox(Context ctx, Player player, SeasonPlayer seasonPlayer, ItemType.LootboxType type, Item crate,
                                    EmoteReference typeEmote, int bound, boolean seasonal) {
        List<Item> toAdd = selectItems(random.nextInt(bound) + bound, type);

        ArrayList<ItemStack> ita = new ArrayList<>();
        toAdd.forEach(item -> ita.add(new ItemStack(item, 1)));

        PlayerData data = player.getData();
        if((type == ItemType.LootboxType.MINE || type == ItemType.LootboxType.MINE_PREMIUM) &&
                toAdd.contains(ItemReference.GEM5_PICKAXE) && toAdd.contains(ItemReference.SPARKLE_PICKAXE)) {
            data.addBadgeIfAbsent(Badge.DESTINY_REACHES);
        }

        if((type == ItemType.LootboxType.FISH || type == ItemType.LootboxType.FISH_PREMIUM) &&
                toAdd.contains(ItemReference.SHARK)) {
            data.addBadgeIfAbsent(Badge.TOO_BIG);
        }

        boolean overflow = seasonal ? seasonPlayer.getInventory().merge(ita) : player.getInventory().merge(ita);

        if(seasonal) {
            seasonPlayer.getInventory().process(new ItemStack(ItemReference.LOOT_CRATE_KEY, -1));
            seasonPlayer.getInventory().process(new ItemStack(crate, -1));
        } else {
            player.getInventory().process(new ItemStack(ItemReference.LOOT_CRATE_KEY, -1));
            player.getInventory().process(new ItemStack(crate, -1));
        }

        data.setCratesOpened(data.getCratesOpened() + 1);
        player.saveAsync();
        seasonPlayer.saveAsync();

        I18nContext lang = ctx.getLanguageContext();

        ctx.sendFormat(lang.get("general.misc_item_usage.crate.success"),
                typeEmote.getDiscordNotation() + " ",
                toAdd.stream()
                        .map(item -> item.getEmoji() + " " + item.getName())
                        .collect(Collectors.joining(", ")),
                overflow ? ". " + lang.get("general.misc_item_usage.crate.overflow") : "");
    }

    @SuppressWarnings("fallthrough")
    private static List<Item> selectItems(int amount, ItemType.LootboxType type) {
        List<Item> common = handleItemDrop(i -> i.getItemType() == ItemType.COMMON);
        List<Item> rare = handleItemDrop(i -> i.getItemType() == ItemType.RARE);
        List<Item> premium = handleItemDrop(i -> i.getItemType() == ItemType.PREMIUM);

        List<Item> mine = handleItemDrop(i ->
                i.getItemType() == ItemType.MINE ||
                i.getItemType() == ItemType.CAST_OBTAINABLE
        );

        List<Item> fish = handleItemDrop(i -> i.getItemType() == ItemType.FISHING);

        List<Item> premiumMine = handleItemDrop(i ->
                i.getItemType() == ItemType.CAST_MINE ||
                i.getItemType() == ItemType.MINE_PICK ||
                i.getItemType() == ItemType.MINE ||
                i.getItemType() == ItemType.MINE_RARE ||
                i.getItemType() == ItemType.CAST_OBTAINABLE ||
                i.getItemType() == ItemType.MINE_RARE_PICK
        );

        List<Item> premiumFish = handleItemDrop(i ->
                i.getItemType() == ItemType.CAST_FISH ||
                i.getItemType() == ItemType.FISHING ||
                i.getItemType() == ItemType.FISHING_RARE
        );

        RandomCollection<Item> items = new RandomCollection<>();

        switch (type) {
            case PREMIUM:
                premium.forEach(i -> items.add(2, i));
            case RARE:
                rare.forEach(i -> items.add(5, i));
            case COMMON:
                common.forEach(i -> items.add(20, i));
                break; //fallthrough intended until here.
            case FISH_PREMIUM:
                premiumFish.forEach(i -> items.add(5, i));
                break;
            case MINE_PREMIUM:
                premiumMine.forEach(i -> items.add(5, i));
                break;
            case MINE:
                mine.forEach(i -> items.add(8, i));
                break;
            case FISH:
                fish.forEach(i -> items.add(8, i));
        }

        List<Item> list = new ArrayList<>(amount);
        for (int i = 0; i < amount; i++) {
            list.add(items.next());
        }

        return list;
    }

    private static List<Item> handleItemDrop(Predicate<Item> predicate) {
        List<Item> all = Arrays.stream(ItemReference.ALL)
                .filter(i -> i.isBuyable() || i.isSellable())
                .collect(Collectors.toList());

        return all.stream()
                .filter(predicate)
                .sorted(Comparator.comparingLong(i -> i.value))
                .collect(Collectors.toList());
    }

    public static boolean handleEffect(PlayerEquipment.EquipmentType type, PlayerEquipment equipment, Item item, DBUser user) {
        boolean isEffectPresent = equipment.getCurrentEffect(type) != null;

        if (isEffectPresent) {
            //Not the correct item to handle the effect of = not handling this call.
            if (item != equipment.getEffectItem(type)) {
                return false;
            }

            //Effect is active when it's been used less than the max amount
            if (!equipment.isEffectActive(type, ((Potion) item).getMaxUses())) {
                //Reset effect if the current amount equipped is 0. Else, subtract one from the current amount equipped.
                if (!equipment.getCurrentEffect(type).use()) { //This call subtracts one from the current amount equipped.
                    equipment.resetEffect(type);
                    //This has to go twice, because I have to return on the next statement.
                    user.save();

                    return false;
                } else {
                    user.save();
                    return true;
                }
            } else {
                equipment.incrementEffectUses(type);
                user.save();

                return true;
            }
        }

        return false;
    }

    public static Item getBrokenItemFrom(Item item) {
        for (Item i : ItemReference.ALL) {
            if (i instanceof Broken) {
                if (((Broken) i).getMainItem() == idOf(item))
                    return i;
            }
        }

        return null;
    }

    public static Pair<Boolean, Player> handleDurability(Context ctx, Item item,
                                                         Player player, DBUser user, SeasonPlayer seasonPlayer, boolean isSeasonal) {
        Inventory playerInventory = isSeasonal ? seasonPlayer.getInventory() : player.getInventory();
        PlayerEquipment equippedItems = isSeasonal ? seasonPlayer.getData().getEquippedItems() : user.getData().getEquippedItems();

        float subtractFrom = 0f;
        if (handleEffect(PlayerEquipment.EquipmentType.POTION, equippedItems, ItemReference.POTION_STAMINA, user)) {
            subtractFrom = random.nextInt(7);
        } else {
            subtractFrom = random.nextInt(10);
        }

        //We do validation before this...
        PlayerEquipment.EquipmentType equipmentType = equippedItems.getTypeFor(item);

        //This is important for previously equipped items before we implemented durability.
        if(!equippedItems.getDurability().containsKey(equipmentType) && item instanceof Breakable) {
            equippedItems.resetDurabilityTo(equipmentType, ((Breakable) item).getMaxDurability());
        }

        int durability = equippedItems.reduceDurability(equipmentType, (int) Math.max(3, subtractFrom));
        boolean assumeBroken = durability < 5;
        I18nContext languageContext = ctx.getLanguageContext();

        if (assumeBroken) {
            equippedItems.resetOfType(equipmentType);

            String broken = "";
            Item brokenItem = getBrokenItemFrom(item);
            if (brokenItem != null && random.nextInt(100) > 20) {
                broken = "\n" + String.format(languageContext.get("commands.mine.broken_drop"),
                        EmoteReference.HEART, brokenItem.getEmoji(), brokenItem.getName()
                );

                playerInventory.process(new ItemStack(brokenItem, 1));
            }

            String toReplace = languageContext.get("commands.mine.item_broke");
            if(!user.getData().isAutoEquip())
                toReplace += "\n" + languageContext.get("commands.mine.item_broke_autoequip");

            ctx.sendFormat(toReplace, EmoteReference.SAD, item.getName(), broken);
            if (isSeasonal)
                seasonPlayer.save();
            else
                player.save();

            user.save();

            //is broken
            return Pair.of(true, player);
        } else {
            if (isSeasonal)
                seasonPlayer.save();
            else
                player.save();

            user.save();

            //is not broken
            return Pair.of(false, player);
        }
    }
}
