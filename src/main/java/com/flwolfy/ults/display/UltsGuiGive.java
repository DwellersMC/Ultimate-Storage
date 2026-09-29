package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * How a screen hands a withdrawal over.
 *
 * <p>Every screen that gives items to a player does it the same way: the backpack first, and what does
 * not fit is dropped on the ground when the configuration asks for that or when the storage is remote.
 * A remote storage has no pool of its own, so putting a stack back there would only swallow it; a void
 * storage does, so a stack that could not be handed over goes back where it came from instead of being
 * lost.
 */
final class UltsGuiGive {

  private UltsGuiGive() {}

  /**
   * Puts a withdrawal into a player's hands.
   *
   * @param player the player receiving
   * @param runtime the storage
   * @param outputs the stacks that left it
   * @param overflowToGround whether a stack that does not fit is dropped rather than put back
   * @return whether anything was dropped on the ground
   */
  static boolean hand(
      ServerPlayer player,
      UltsRuntime runtime,
      List<ItemStack> outputs,
      boolean overflowToGround
  ) {
    boolean dropped = false;
    for (ItemStack output : outputs) {
      player.getInventory().add(output);
      if (output.isEmpty()) {
        continue;
      }
      if (overflowToGround || runtime.remote()) {
        dropped |= drop(player, runtime, output);
      } else {
        runtime.state().deposit(output);
      }
    }
    return dropped;
  }

  /**
   * Puts one stack down at a player's feet, and keeps it if the world will not take it.
   *
   * <p>The game's own drop asks the level to add the item entity and then ignores the answer, so a stack
   * the level refused is a stack nobody will ever see again — which is what a player reports as items
   * vanishing when their backpack is full. The answer is therefore read here: a stack that did not land
   * goes back into the storage instead of out of the world.
   *
   * @param player the player dropping it, at whose feet it lands
   * @param runtime the storage it came from, to hand it back to
   * @param stack the stack to put down, which this call takes over
   * @return whether it really landed
   */
  static boolean drop(ServerPlayer player, UltsRuntime runtime, ItemStack stack) {
    ItemEntity entity = new ItemEntity(
        player.level(), player.getX(), player.getEyeY() - 0.3, player.getZ(), stack.copy());
    entity.setPickUpDelay(40);
    if (player.level().addFreshEntity(entity)) {
      stack.setCount(0);
      return true;
    }
    runtime.state().deposit(stack);
    stack.setCount(0);
    return false;
  }
}
