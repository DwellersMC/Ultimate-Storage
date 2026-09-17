package com.flwolfy.ults.data.state;

import java.util.List;
import net.minecraft.world.item.ItemStack;

public record UltsWithdrawalPlan(
    boolean available,
    String problem,
    long itemAvailable,
    long itemRequired,
    long boxAvailable,
    int boxRequired,
    List<ItemStack> outputs
) {
  public UltsWithdrawalPlan {
    outputs = outputs.stream().map(ItemStack::copy).toList();
  }
}
