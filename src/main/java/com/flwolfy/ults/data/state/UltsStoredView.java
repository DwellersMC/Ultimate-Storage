package com.flwolfy.ults.data.state;

import net.minecraft.world.item.ItemStack;

public record UltsStoredView(ItemStack template, long amount, boolean special) {
  public UltsStoredView {
    template = template.copyWithCount(1);
  }
}
