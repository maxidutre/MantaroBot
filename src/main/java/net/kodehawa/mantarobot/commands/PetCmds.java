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
 * along with Mantaro.  If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.EmbedBuilder;
import net.kodehawa.mantarobot.commands.currency.item.Item;
import net.kodehawa.mantarobot.commands.currency.item.ItemHelper;
import net.kodehawa.mantarobot.commands.currency.item.ItemReference;
import net.kodehawa.mantarobot.commands.currency.item.ItemStack;
import net.kodehawa.mantarobot.commands.currency.item.special.Food;
import net.kodehawa.mantarobot.commands.currency.pets.HousePet;
import net.kodehawa.mantarobot.commands.currency.pets.HousePetType;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.listeners.operations.InteractiveOperations;
import net.kodehawa.mantarobot.core.listeners.operations.core.Operation;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SubCommand;
import net.kodehawa.mantarobot.core.modules.commands.TreeCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Command;
import net.kodehawa.mantarobot.core.modules.commands.base.CommandCategory;
import net.kodehawa.mantarobot.core.modules.commands.base.Context;
import net.kodehawa.mantarobot.core.modules.commands.help.HelpContent;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.RatelimitUtils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.ratelimit.IncreasingRateLimiter;

import java.awt.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Module
//TODO: Multiple / Personal pets.
public class PetCmds {
    @Subscribe
    public void pet(CommandRegistry cr) {
        var rl = new IncreasingRateLimiter.Builder()
                .limit(1)
                .spamTolerance(2)
                .cooldown(5, TimeUnit.SECONDS)
                .maxCooldown(5, TimeUnit.SECONDS)
                .randomIncrement(true)
                .pool(MantaroData.getDefaultJedisPool())
                .prefix("pet")
                .build();

        var patRatelimiter = new IncreasingRateLimiter.Builder()
                .limit(1)
                .spamTolerance(2)
                .cooldown(40, TimeUnit.SECONDS)
                .maxCooldown(3, TimeUnit.MINUTES)
                .randomIncrement(true)
                .pool(MantaroData.getDefaultJedisPool())
                .prefix("pet-pat")
                .build();

        var petRemoveRatelimiter = new IncreasingRateLimiter.Builder()
                .limit(1)
                .spamTolerance(2)
                .cooldown(1, TimeUnit.HOURS)
                .maxCooldown(2, TimeUnit.HOURS)
                .randomIncrement(false)
                .pool(MantaroData.getDefaultJedisPool())
                .prefix("pet-remove")
                .build();

        TreeCommand pet = cr.register("pet", new TreeCommand(CommandCategory.CURRENCY) {
            @Override
            public Command defaultTrigger(Context ctx, String mainCommand, String commandName) {
                return new SubCommand() {
                    @Override
                    protected void call(Context ctx, String content) {
                        ctx.sendLocalized("commands.pet.explanation");
                    }
                };
            }

            @Override
            public HelpContent help() {
                return new HelpContent.Builder()
                        .setDescription("Pet commands. " +
                                "For a better explanation of the pet system see [here](https://github.com/Mantaro/MantaroBot/wiki)\n" +
                                "This contains an explanation about what pets are..")
                        .build();
            }
        });

        pet.setPredicate(ctx -> RatelimitUtils.handleIncreasingRatelimit(rl, ctx.getAuthor(), ctx.getEvent(), null, false));

        pet.addSubCommand("list", new SubCommand() {
            @Override
            public String description() {
                return "Lists the available pet types.";
            }

            @Override
            protected void call(Context ctx, String content) {
                var pets = Arrays
                        .stream(HousePetType.values())
                        .filter(HousePetType::isBuyable)
                        .map(pet -> {
                            var emoji = pet.getEmoji();
                            var name = pet.getName();
                            var abilities = pet.getStringAbilities();
                            var value = pet.getCost();

                            return String.format(ctx.getLanguageContext().get("commands.pet.list.summary"),
                                    emoji, name, abilities, value
                            );
                        })
                        .collect(Collectors.joining("\n"));

                ctx.sendLocalized("commands.pet.list.header", EmoteReference.TALKING, pets, EmoteReference.PENCIL, ctx.getLanguageContext().get("commands.pet.list.abilities"));
            }
        }).createSubCommandAlias("list", "ls");

        pet.addSubCommand("status", new SubCommand() {
            @Override
            public String description() {
                return "Shows the status of your current pet.";
            }

            @Override
            protected void call(Context ctx, String content) {
                var dbUser = ctx.getDBUser();

                var marriage = dbUser.getData().getMarriage();
                if(marriage == null) {
                    ctx.sendLocalized("commands.pet.no_marriage", EmoteReference.ERROR);
                    return;
                }

                var pet = marriage.getData().getPet();
                if(pet == null) {
                    ctx.sendLocalized("commands.pet.status.no_pet", EmoteReference.ERROR);
                    return;

                }

                var language = ctx.getLanguageContext();

                EmbedBuilder status = new EmbedBuilder()
                        .setAuthor(String.format(language.get("commands.pet.status.header"), pet.getName()), ctx.getUser().getEffectiveAvatarUrl())
                        .setColor(Color.PINK)
                        .setDescription(language.get("commands.pet.status.description"))
                        .addField(
                                EmoteReference.MONEY + " " + language.get("commands.pet.status.cost"),
                                String.valueOf(pet.getType().getCost()), true
                        )
                        .addBlankField(true)
                        .addField(
                                EmoteReference.ZAP + " " + language.get("commands.pet.status.type"),
                                pet.getType().getEmoji() + pet.getType().getName(), true
                        )
                        .addField(
                                EmoteReference.WRENCH + language.get("commands.pet.status.abilities"),
                                pet.getType().getStringAbilities(), true
                        )
                        .addBlankField(true)
                        .addField(
                                EmoteReference.BLUE_HEART + " "  + language.get("commands.pet.status.pet"),
                                String.valueOf(pet.getPatCounter()), true
                        )
                        .addField(
                                EmoteReference.ZAP + " "  + language.get("commands.pet.status.level"),
                                "**" + pet.getLevel() + " (XP: " + pet.getExperience() + ")**\n", true
                        )
                        .addBlankField(true)
                        .addField(
                                EmoteReference.HEART + " " + language.get("commands.pet.status.health"),
                                "**" + pet.getHealth() + " / 100**\n", true
                        )
                        .addField(
                                EmoteReference.DROPLET + " " + language.get("commands.pet.status.thirst"),
                                "**" + pet.getThirst() + " / 100**\n", true
                        )
                        .addBlankField(true)
                        .addField(
                                EmoteReference.CHOCOLATE + " " + language.get("commands.pet.status.hunger"),
                                "**" + pet.getHunger() + " / 100**\n", true
                        )
                        .setThumbnail(ctx.getAuthor().getEffectiveAvatarUrl())
                        .setFooter(language.get("commands.pet.status.footer"));

                ctx.send(status.build());
            }
        }).createSubCommandAlias("status", "stats");

        pet.addSubCommand("remove", new SubCommand() {
            @Override
            public String description() {
                return "Removes this pet. This will *reset all pet stats*. Just like buying a new tamagotchi.";
            }

            @Override
            protected void call(Context ctx, String content) {
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();

                if(marriage == null) {
                    ctx.sendLocalized("commands.pet.no_marriage", EmoteReference.ERROR);
                    return;
                }

                var pet = marriage.getData().getPet();

                if(pet == null) {
                    ctx.sendLocalized("commands.pet.remove.no_pet", EmoteReference.ERROR);
                    return;
                }

                if(!RatelimitUtils.handleIncreasingRatelimit(petRemoveRatelimiter, ctx.getAuthor(), ctx.getEvent(), null, false))
                    return;

                var toRefund = pet.getType().getCost() / 2;
                ctx.sendLocalized("commands.pet.remove.confirm", EmoteReference.WARNING, toRefund);
                InteractiveOperations.create(ctx.getChannel(), ctx.getAuthor().getIdLong(), 50, (e) -> {
                    if (!e.getAuthor().equals(ctx.getAuthor()))
                        return Operation.IGNORED;

                    if(e.getMessage().getContentRaw().equalsIgnoreCase("yes")) {
                        final var marriedWithPlayer = ctx.getPlayer(marriage.getOtherPlayer(ctx.getAuthor().getId()));
                        final var marriedPlayer = ctx.getPlayer();
                        var marriageConfirmed = dbUser.getData().getMarriage();

                        marriageConfirmed.getData().setPet(null);
                        marriageConfirmed.save();

                        marriedWithPlayer.addMoney(toRefund);
                        marriedPlayer.addMoney(toRefund);

                        marriedPlayer.save();
                        marriedWithPlayer.save();
                        ctx.sendLocalized("commands.pet.remove.success", EmoteReference.POPPER, toRefund);
                        return Operation.COMPLETED;
                    }

                    if(e.getMessage().getContentRaw().equalsIgnoreCase("no")) {
                        // This is reusing the string, nothing wrong here.
                        ctx.sendLocalized("commands.pet.buy.cancel_success", EmoteReference.CORRECT);
                        return Operation.COMPLETED;
                    }

                    return Operation.IGNORED;
                });

            }
        });

        pet.addSubCommand("pet", new SubCommand() {
            @Override
            public String description() {
                return "Pets your pet. Usage: `~>pet pet`. Cute.";
            }

            @Override
            protected void call(Context ctx, String content) {
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();

                if(marriage == null) {
                    ctx.sendLocalized("commands.pet.no_marriage", EmoteReference.ERROR);
                    return;
                }

                var pet = marriage.getData().getPet();

                if(pet == null) {
                    ctx.sendLocalized("commands.pet.pat.no_pet", EmoteReference.ERROR);
                    return;
                }

                if(!RatelimitUtils.handleIncreasingRatelimit(patRatelimiter, ctx.getAuthor(), ctx.getEvent(), null, false))
                    return;

                String message = pet.handlePat().getMessage();
                String extraMessage = "";
                if(pet.getPatCounter() > 100) {
                    extraMessage += "\n" + String.format(ctx.getLanguageContext().get("commands.pet.pet_reactions.counter_100"), EmoteReference.BLUE_HEART);
                }

                pet.increasePats();
                marriage.save();

                ctx.sendLocalized(message, pet.getType().getEmoji(), pet.getPatCounter(), extraMessage);
            }
        });

        pet.addSubCommand("buy", new SubCommand() {
            @Override
            public String description() {
                return "Buys a pet to have adventures with. You need to buy a pet house in market first. Usage: `~>pet buy <name> <type>`";
            }

            @Override
            protected void call(Context ctx, String content) {
                var player = ctx.getPlayer();
                var playerInventory = player.getInventory();
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();
                var args = ctx.getArguments();

                if(marriage == null) {
                    ctx.sendLocalized("commands.pet.no_marriage", EmoteReference.ERROR);
                    return;
                }

                var marriageData = marriage.getData();

                if(args.length < 2) {
                    ctx.sendLocalized("commands.pet.buy.not_enough_arguments", EmoteReference.ERROR);
                    return;
                }

                var name = args[0];
                var type = args[1];

                if(!marriageData.hasCar() || !marriageData.hasHouse()) {
                    ctx.sendLocalized("commands.pet.buy.no_requirements", EmoteReference.ERROR, marriageData.hasHouse(), marriageData.hasCar());
                    return;
                }

                if(!playerInventory.containsItem(ItemReference.PET_HOUSE)) {
                    ctx.sendLocalized("commands.pet.buy.no_house", EmoteReference.ERROR);
                    return;
                }

                if(marriageData.getPet() != null) {
                    ctx.sendLocalized("commands.pet.buy.already_has_pet", EmoteReference.ERROR);
                    return;
                }

                HousePetType toBuy = HousePetType.lookupFromString(type);
                if(toBuy == null) {
                    ctx.sendLocalized("commands.pet.buy.nothing_found", EmoteReference.ERROR, type,
                            Arrays.stream(HousePetType.values())
                                    .filter(HousePetType::isBuyable)
                                    .map(HousePetType::getName)
                                    .collect(Collectors.joining(", "))
                    );
                    return;
                }

                if(player.getCurrentMoney() < toBuy.getCost()) {
                    ctx.sendLocalized("commands.pet.buy.not_enough_money", EmoteReference.ERROR, toBuy.getCost(), player.getCurrentMoney());
                    return;
                }


                ctx.sendLocalized("commands.pet.buy.confirm", EmoteReference.WARNING, name, type, toBuy.getCost());
                InteractiveOperations.create(ctx.getChannel(), ctx.getAuthor().getIdLong(), 30, (e) -> {
                    if (!e.getAuthor().equals(ctx.getAuthor()))
                        return Operation.IGNORED;

                    if(e.getMessage().getContentRaw().equalsIgnoreCase("yes")) {
                        var playerConfirmed = ctx.getPlayer();
                        var playerInventoryConfirmed = playerConfirmed.getInventory();
                        var dbUserConfirmed = ctx.getDBUser();
                        var marriageConfirmed = dbUserConfirmed.getData().getMarriage();

                        // People like to mess around lol.
                        if(!playerInventory.containsItem(ItemReference.PET_HOUSE)) {
                            ctx.sendLocalized("commands.pet.buy.no_house", EmoteReference.ERROR);
                            return Operation.COMPLETED;
                        }

                        if(player.getCurrentMoney() < toBuy.getCost()) {
                            ctx.sendLocalized("commands.pet.buy.not_enough_money", EmoteReference.ERROR, toBuy.getCost());
                            return Operation.COMPLETED;
                        }

                        playerConfirmed.removeMoney(toBuy.getCost());
                        playerInventoryConfirmed.process(new ItemStack(ItemReference.PET_HOUSE, -1));
                        playerConfirmed.save();

                        marriageConfirmed.getData().setPet(new HousePet(name, toBuy));
                        marriageConfirmed.save();

                        ctx.sendLocalized("commands.pet.buy.success", EmoteReference.POPPER, toBuy.getEmoji(), toBuy.getName(), name, toBuy.getCost());
                        return Operation.COMPLETED;
                    }

                    if(e.getMessage().getContentRaw().equalsIgnoreCase("no")) {
                        ctx.sendLocalized("commands.pet.buy.cancel_success", EmoteReference.CORRECT);
                        return Operation.COMPLETED;
                    }

                    return Operation.IGNORED;
                });
            }
        });

        pet.addSubCommand("rename", new SubCommand() {
            @Override
            public String description() {
                return "Renames your pet. Usage: `~>pet rename <name>`";
            }

            @Override
            protected void call(Context ctx, String content) {
                var player = ctx.getPlayer();
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();
                var cost = 3000;

                if(marriage == null) {
                    ctx.sendLocalized("commands.pet.no_marriage", EmoteReference.ERROR);
                    return;
                }

                var pet = marriage.getData().getPet();
                if(pet == null) {
                    ctx.sendLocalized("commands.pet.rename.no_pet", EmoteReference.ERROR);
                    return;
                }

                if(content.isEmpty()) {
                    ctx.sendLocalized("commands.pet.rename.no_content", EmoteReference.ERROR);
                    return;
                }

                if(player.getCurrentMoney() < cost) {
                    ctx.sendLocalized("commands.pet.rename.not_enough_money", EmoteReference.ERROR, cost, player.getCurrentMoney());
                    return;
                }

                var oldName = pet.getName();
                pet.setName(content);
                player.removeMoney(cost);

                marriage.save();
                player.save();

                ctx.sendLocalized("commands.pet.rename.success", EmoteReference.POPPER, oldName, content, cost);
            }
        });

        pet.addSubCommand("feed", new SubCommand() {
            @Override
            public String description() {
                return "Feeds your pet. Types of food may vary per pet. Usage: `~>pet feed <food> [<amount>]`";
            }

            @Override
            protected void call(Context ctx, String content) {
                var player = ctx.getPlayer();
                var playerInventory = player.getInventory();
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();
                var args = ctx.getArguments();
                var food = content;
                var amount = 1;

                if(marriage == null) {
                    ctx.sendLocalized("commands.pet.no_marriage", EmoteReference.ERROR);
                    return;
                }

                if(args.length > 1) {
                    try {
                        amount = Integer.parseInt(args[1]);
                        // Only will go through if the amount parses properly :p
                        food = args[0];
                    } catch (Exception ignored) {
                        food = content;
                    }
                }

                var pet = marriage.getData().getPet();
                if(pet == null) {
                    ctx.sendLocalized("commands.pet.feed.no_pet", EmoteReference.ERROR);
                    return;
                }

                if(food.isEmpty()) {
                    ctx.sendLocalized("commands.pet.feed.no_content", EmoteReference.ERROR);
                    return;
                }

                var item = ItemHelper.fromAnyNoId(food);
                if(item.isEmpty()) {
                    ctx.sendLocalized("commands.pet.feed.no_item", EmoteReference.ERROR);
                    return;
                }

                var itemObject = item.get();
                if(!(itemObject instanceof Food)) {
                    ctx.sendLocalized("commands.pet.feed.not_food", EmoteReference.ERROR);
                    return;
                }

                if(pet.getHunger() > 95) {
                    ctx.sendLocalized("commands.pet.feed.no_need", EmoteReference.ERROR);
                    return;
                }

                if(!playerInventory.containsItem(itemObject) && playerInventory.getAmount(itemObject) >= amount) {
                    ctx.sendLocalized("commands.pet.feed.not_inventory", EmoteReference.ERROR, amount);
                    return;
                }

                var foodItem = (Food) itemObject;

                if(foodItem.getType().getApplicableType() != pet.getType()) {
                    ctx.sendLocalized("commands.pet.feed.not_applicable", EmoteReference.ERROR);
                    return;
                }

                var increase = foodItem.getHungerLevel() * amount;

                if((pet.getHunger() + increase) > 100) {
                    ctx.sendLocalized("commands.pet.feed.too_much", EmoteReference.ERROR);
                    return;
                }

                pet.increaseHunger(increase);
                pet.increaseHealth();
                pet.increaseStamina();

                playerInventory.process(new ItemStack(itemObject, -amount));
                player.save();

                marriage.save();
                ctx.sendLocalized("commands.pet.feed.success", EmoteReference.POPPER, foodItem.getName(), amount, increase, pet.getHunger());
            }
        });

        pet.addSubCommand("hydrate", new SubCommand() {
            @Override
            public String description() {
                return "Hydrates your pet.";
            }

            @Override
            protected void call(Context ctx, String content) {
                var player = ctx.getPlayer();
                var playerInventory = player.getInventory();
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();

                if(marriage == null) {
                    ctx.sendLocalized("commands.pet.no_marriage", EmoteReference.ERROR);
                    return;
                }

                var pet = marriage.getData().getPet();

                if(pet == null) {
                    ctx.sendLocalized("commands.pet.water.no_pet", EmoteReference.ERROR);
                    return;
                }

                if(pet.getThirst() > 95) {
                    ctx.sendLocalized("commands.pet.water.no_need", EmoteReference.ERROR);
                    return;
                }

                var item = ItemReference.WATER_BOTTLE;
                if(!playerInventory.containsItem(item)) {
                    ctx.sendLocalized("commands.pet.water.not_inventory", EmoteReference.ERROR);
                    return;
                }

                pet.increaseThirst();
                pet.increaseHealth();
                pet.increaseStamina();

                playerInventory.process(new ItemStack(item, -1));
                player.save();

                marriage.save();
                ctx.sendLocalized("commands.pet.water.success", EmoteReference.POPPER, 15, pet.getThirst());
            }
        });

        pet.addSubCommand("info", new SubCommand() {
            @Override
            protected void call(Context ctx, String content) {
                var lookup = HousePetType.lookupFromString(content);
                var languageContext = ctx.getLanguageContext();
                if (lookup == null) {
                    ctx.sendLocalized("commands.pet.info.not_found", EmoteReference.ERROR);
                    return;
                }

                var emoji = lookup.getEmoji();
                var name = lookup.getName();
                var cost = lookup.getCost();
                var abilities = lookup.getStringAbilities();
                var coinBuildup = lookup.getMaxCoinBuildup(1);
                var coinBuildup100 = lookup.getMaxCoinBuildup(100);
                var itemBuildup = lookup.getMaxItemBuildup(1);
                var itemBuildup100 = lookup.getMaxItemBuildup(100);
                var food = Arrays.stream(ItemReference.ALL)
                        .filter(Food.class::isInstance)
                        .map(Food.class::cast)
                        .filter(f -> f.getType().getApplicableType() == lookup)
                        .map(Item::toDisplayString)
                        .collect(Collectors.joining(", "));


                var embed = new EmbedBuilder()
                        .setAuthor(String.format(languageContext.get("commands.pet.info.author"), emoji, name))
                        .setColor(Color.PINK)
                        .addField(languageContext.get("commands.pet.info.name"), name, true)
                        .addField(languageContext.get("commands.pet.info.cost"), cost + " credits", true)
                        .addField(languageContext.get("commands.pet.info.abilities"), abilities, false)
                        .addField(languageContext.get("commands.pet.info.food"), food, false)
                        .addField(languageContext.get("commands.pet.info.coin_buildup"), coinBuildup + " credits", true)
                        .addBlankField(true)
                        .addField(languageContext.get("commands.pet.info.coin_buildup_100"), coinBuildup100 + " credits", true)
                        .addField(languageContext.get("commands.pet.info.item_buildup"), itemBuildup + " items", true)
                        .addBlankField(true)
                        .addField(languageContext.get("commands.pet.info.item_buildup_100"), itemBuildup100 + " items", true);

                ctx.send(embed.build());
            }
        });
    }
}
