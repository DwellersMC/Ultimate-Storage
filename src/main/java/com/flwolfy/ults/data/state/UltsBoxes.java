package com.flwolfy.ults.data.state;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

public final class UltsBoxes {

  private UltsBoxes() {}

  public static boolean isShulker(ItemStack stack) {
    return stack.getItem() instanceof BlockItem blockItem
        && blockItem.getBlock() instanceof ShulkerBoxBlock;
  }

  public static boolean isEmptyShulker(ItemStack stack) {
    if (!isShulker(stack)) {
      return false;
    }
    ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
    if (contents == null) {
      return true;
    }
    for (var ignored : contents.nonEmptyItems()) {
      return false;
    }
    return true;
  }

  /** An empty shulker box that packing may consume: renamed boxes are left to their owner. */
  public static boolean isPackable(ItemStack stack) {
    return isEmptyShulker(stack) && !stack.has(DataComponents.CUSTOM_NAME);
  }

  public static boolean isPlain(ItemStack stack) {
    return stack.is(Items.SHULKER_BOX);
  }
}
