package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
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
        player.drop(output, false);
        dropped = true;
      } else {
        runtime.state().deposit(output);
      }
    }
    return dropped;
  }
}
