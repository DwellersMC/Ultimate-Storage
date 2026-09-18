package com.flwolfy.ults.data.state;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

public final class UltsWithdrawalOutput {

  private static final int SHULKER_SLOTS = 27;

  private UltsWithdrawalOutput() {}

  public static List<ItemStack> looseStacks(ItemStack template, int quantity) {
    List<ItemStack> result = new ArrayList<>();
    int remaining = quantity;
    while (remaining > 0) {
      int count = Math.min(remaining, template.getMaxStackSize());
      result.add(template.copyWithCount(count));
      remaining -= count;
    }
    return List.copyOf(result);
  }

  public static ItemStack packedBox(ItemStack boxTemplate, ItemStack template) {
    ItemStack box = boxTemplate.copyWithCount(1);
    List<ItemStack> contents = new ArrayList<>(SHULKER_SLOTS);
    for (int slot = 0; slot < SHULKER_SLOTS; slot++) {
      contents.add(template.copyWithCount(template.getMaxStackSize()));
    }
    box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
    return box;
  }
}
