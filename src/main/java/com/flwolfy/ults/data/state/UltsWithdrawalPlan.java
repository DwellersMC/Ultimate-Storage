package com.flwolfy.ults.data.state;

import com.flwolfy.ults.crafting.UltsCraftStep;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/**
 * What one withdrawal would do, crafting included.
 *
 * @param available whether it can run at all
 * @param problem why it cannot run, or an empty string
 * @param itemAvailable how much of the item is stored
 * @param itemRequired how much of the item the withdrawal needs
 * @param boxAvailable how many empty boxes are stored
 * @param boxRequired how many boxes the withdrawal needs
 * @param boxStored how many of those boxes the storage hands over
 * @param boxCrafted how many of those boxes are crafted first
 * @param outputs the stacks that would be handed over
 * @param craftItems how many items of the request are produced by crafting
 * @param steps the crafting runs, in the order they have to happen
 */
public record UltsWithdrawalPlan(
    boolean available,
    String problem,
    long itemAvailable,
    long itemRequired,
    long boxAvailable,
    int boxRequired,
    long boxStored,
    long boxCrafted,
    List<ItemStack> outputs,
    long craftItems,
    List<UltsCraftStep> steps
) {
  public UltsWithdrawalPlan {
    outputs = outputs.stream().map(ItemStack::copy).toList();
    steps = List.copyOf(steps);
  }
}
