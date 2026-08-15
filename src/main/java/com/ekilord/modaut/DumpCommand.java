package com.ekilord.modaut;

import com.google.gson.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.JsonOps;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class DumpCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("modaut")
                        .then(Commands.literal("dump")
                                .then(Commands.argument("type", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(new String[]{"biomes", "entities", "recipes"}, builder))
                                        .executes(DumpCommand::executeDump)
                                )
                        )
        );
    }

    private static int executeDump(CommandContext<CommandSourceStack> ctx) {
        String type = StringArgumentType.getString(ctx, "type");
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();

        // Use GAMEDIR (works for both singleplayer and dedicated server)
        Path basePath = FMLPaths.GAMEDIR.get().resolve("modaut");
        Path dumpPath;
        switch (type) {
            case "biomes" -> dumpPath = basePath.resolve("biomes");
            case "entities" -> dumpPath = basePath.resolve("entities");
            case "recipes" -> dumpPath = basePath.resolve("recipes");
            default -> {
                source.sendFailure(Component.literal("Invalid type. Use biomes, entities or recipes."));
                return 0;
            }
        }

        try {
            Files.createDirectories(dumpPath);
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not create folder: " + e.getMessage()));
            return 0;
        }

        try {
            switch (type) {
                case "biomes" -> dumpBiomes(server, dumpPath);
                case "entities" -> dumpEntities(dumpPath);
                case "recipes" -> dumpRecipes(server, dumpPath);
            }
            source.sendSuccess(() -> Component.literal("Successfully dumped " + type + " to " + dumpPath), true);
        } catch (Exception e) {
            source.sendFailure(Component.literal("Error while dumping: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
        return 1;
    }

    // ------------------- BIOMES -------------------
    private static void dumpBiomes(MinecraftServer server, Path folder) throws IOException {
        var biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
        JsonObject root = new JsonObject();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        Set<Map.Entry<ResourceKey<Biome>, Biome>> biomeSet = biomeRegistry.entrySet();
        List<Map.Entry<ResourceKey<Biome>, Biome>> biomeList = biomeSet.stream()
                .sorted(Comparator.comparing(e -> e.getKey().location().toString()))
                .toList();

        for (var entry : biomeList) {
            ResourceLocation id = entry.getKey().location();
            Biome biome = entry.getValue();
            root.add(id.toString(), biomeToJson(biome));
        }

        Path file = folder.resolve("biomes.json");
        Files.writeString(file, gson.toJson(root));
    }

    private static JsonObject biomeToJson(Biome biome) {
        JsonObject json = new JsonObject();

        // Basic climate
        json.addProperty("temperature", biome.getBaseTemperature());
        json.addProperty("has_precipitation", biome.hasPrecipitation());
        try {
            json.addProperty("precipitation_type", biome.getPrecipitationAt(BlockPos.ZERO).toString()); // RAIN, SNOW, NONE
        } catch (Exception ignored) {}

        // Color settings
        var specialEffects = biome.getSpecialEffects();
        json.addProperty("fog_color", specialEffects.getFogColor());
        json.addProperty("water_color", specialEffects.getWaterColor());
        json.addProperty("water_fog_color", specialEffects.getWaterFogColor());
        json.addProperty("sky_color", specialEffects.getSkyColor());
        if (specialEffects.getGrassColorOverride().isPresent()) {
            json.addProperty("grass_color_override", specialEffects.getGrassColorOverride().get());
        }
        if (specialEffects.getFoliageColorOverride().isPresent()) {
            json.addProperty("foliage_color_override", specialEffects.getFoliageColorOverride().get());
        }

        // Music & sounds
        specialEffects.getBackgroundMusic().ifPresent(music ->
                json.addProperty("music", music.getEvent().unwrapKey().get().location().toString()));
        specialEffects.getAmbientLoopSoundEvent().ifPresent(sound ->
                json.addProperty("ambient_sound", sound.unwrapKey().get().location().toString()));
        specialEffects.getAmbientMoodSettings().ifPresent(mood ->
                json.addProperty("mood_sound", mood.getSoundEvent().unwrapKey().get().location().toString()));

        return json;
    }

    // ------------------- ENTITIES -------------------
    private static void dumpEntities(Path folder) throws IOException {
        JsonObject root = new JsonObject();
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        Set<Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>>> entitySet = BuiltInRegistries.ENTITY_TYPE.entrySet();
        List<Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>>> entityList = entitySet.stream()
                .sorted(Comparator.comparing(e -> e.getKey().location().toString()))
                .toList();

        for (var entry : entityList) {
            ResourceLocation id = entry.getKey().location();
            EntityType<?> type = entry.getValue();
            root.add(id.toString(), entityTypeToJson(type));
        }

        Path file = folder.resolve("entities.json");
        Files.writeString(file, gson.toJson(root));
    }

    private static JsonObject entityTypeToJson(EntityType<?> type) {
        JsonObject json = new JsonObject();

        json.addProperty("category", type.getCategory().getName());
        json.addProperty("width", type.getWidth());
        json.addProperty("height", type.getHeight());
        json.addProperty("client_tracking_range", type.clientTrackingRange());
        json.addProperty("update_interval", type.updateInterval());
        json.addProperty("summonable", type.canSummon());
        json.addProperty("fire_immune", type.fireImmune());
        json.addProperty("can_spawn_far_from_player", type.canSpawnFarFromPlayer());

        // Default attributes (only for LivingEntity subtypes)
        if (DefaultAttributes.hasSupplier(type)) {
            JsonObject attributes = new JsonObject();
            AttributeSupplier supplier = DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) type);

            // Iterate over all registered attributes and check if this entity has them
            for (Attribute attr : BuiltInRegistries.ATTRIBUTE) {
                if (supplier.hasAttribute(attr)) {
                    String attrName = BuiltInRegistries.ATTRIBUTE.getKey(attr).toString();
                    double baseValue = supplier.getValue(attr);
                    attributes.addProperty(attrName, baseValue);
                }
            }
            json.add("default_attributes", attributes);
        }

        return json;
    }

    // ------------------- RECIPES -------------------
    private static void dumpRecipes(MinecraftServer server, Path folder) throws IOException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Collection<Recipe<?>> recipes = server.getRecipeManager().getRecipes();

        for (Recipe<?> recipe : recipes) {
            ResourceLocation id = recipe.getId();

            // Create a JSON object for the recipe’s content
            JsonObject recipeJson = new JsonObject();

            // The serializer (recipe type) – e.g. "minecraft:crafting_shaped"
            recipeJson.addProperty("type",
                    Objects.requireNonNull(BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer())).toString());

            // Ingredients
            JsonArray ingredients = new JsonArray();
            for (Ingredient ingredient : recipe.getIngredients()) {
                ingredients.add(ingredient.toJson());   // Ingredient already has a toJson method
            }
            recipeJson.add("ingredients", ingredients);

            // Result item
            ItemStack resultStack = recipe.getResultItem(server.registryAccess());
            JsonObject result = new JsonObject();
            result.addProperty("item", BuiltInRegistries.ITEM.getKey(resultStack.getItem()).toString());
            result.addProperty("count", resultStack.getCount());
            if (resultStack.hasTag()) {
                assert resultStack.getTag() != null;
                result.addProperty("nbt", resultStack.getTag().toString());
            }
            recipeJson.add("result", result);

            // Wrap with metadata
            JsonObject output = new JsonObject();
            output.addProperty("id", id.toString());
            output.addProperty("source", id.getNamespace());   // mod id or datapack name
            output.add("recipe", recipeJson);

            // Write to file
            Path namespaceDir = folder.resolve(id.getNamespace());
            Files.createDirectories(namespaceDir);
            Path file = namespaceDir.resolve(id.getPath() + ".json");
            Files.writeString(file, gson.toJson(output));
        }
    }
}
