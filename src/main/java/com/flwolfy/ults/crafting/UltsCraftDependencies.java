package com.flwolfy.ults.crafting;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Conservative cache dependencies, never a reason on their own to display a pending amount. */
final class UltsCraftDependencies {
  private final Map<Item, Set<Item>> ingredients = new HashMap<>();
  private final Map<Item, Set<Item>> stations = new HashMap<>();
  private final Map<Item, Set<Item>> answers = new HashMap<>();

  UltsCraftDependencies(List<UltsCraftRecipe> recipes) {
    for (var recipe : recipes) {
      var inputs = new HashSet<Item>();
      var outputs = new HashSet<Item>();
      outputs.add(recipe.result().getItem());
      for (var slot : recipe.ingredients()) for (Item item : UltsIngredients.accepted(slot)) {
        inputs.add(item);
        var returned = item.getCraftingRemainder();
        if (!recipe.needsStonecutter() && returned != null) outputs.add(returned.create().getItem());
      }
      for (Item output : outputs) {
        ingredients.computeIfAbsent(output, ignored -> new HashSet<>()).addAll(inputs);
        stations.computeIfAbsent(output, ignored -> new HashSet<>()).add(
            recipe.needsStonecutter() ? Items.STONECUTTER : Items.CRAFTING_TABLE);
      }
    }
  }

  Set<Item> inputs(Item target) {
    return answers.computeIfAbsent(target, wanted -> {
      var visited = new HashSet<Item>();
      var requiredStations = new HashSet<Item>();
      var queue = new ArrayDeque<Item>();
      queue.add(wanted);
      while (!queue.isEmpty()) {
        Item item = queue.remove();
        if (!visited.add(item)) continue;
        requiredStations.addAll(stations.getOrDefault(item, Set.of()));
        queue.addAll(ingredients.getOrDefault(item, Set.of()));
      }
      visited.addAll(requiredStations);
      return Set.copyOf(visited);
    });
  }
}
