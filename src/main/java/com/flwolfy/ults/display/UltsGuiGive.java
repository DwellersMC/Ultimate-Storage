package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * How a screen hands a withdrawal over.
 *
 * <p>Every screen that gives items to a player does it the same way: the backpack first, and what does
 * not fit is dropped on the ground when the configuration allows it.
 * A stack that cannot be delivered is restored to its source storage or retained for recovery.
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
   * @return the actual delivered and rejected quantities, counting filled boxes as single items
   */
  static Delivery hand(
      ServerPlayer player,
      UltsRuntime runtime,
      List<ItemStack> outputs,
      boolean overflowToGround
  ) {
    return deliver(outputs, output -> UltsBackpack.placeInto(player, output),
        leftover -> drop(player, leftover), runtime::putBack, overflowToGround);
  }

  static Delivery handWithFeedback(ServerPlayer player, UltsRuntime runtime,
      List<ItemStack> outputs, boolean overflowToGround) {
    Delivery result = hand(player, runtime, outputs, overflowToGround);
    if (result.rejected() > 0L) UltsGuiChat.failure(player, "ults.withdraw.problem.delivery");
    return result;
  }

  record Delivery(long delivered, long rejected, long ground) {
    boolean complete() { return rejected == 0L; }
    long backpack() { return delivered - ground; }
  }

  /** The real delivery transaction, with narrow inventory/world seams for acceptance tests. */
  static Delivery deliver(List<ItemStack> outputs, Function<ItemStack, ItemStack> insert,
      Predicate<ItemStack> drop, Consumer<ItemStack> restore, boolean overflowToGround) {
    long delivered = 0L, rejected = 0L, ground = 0L;
    for (ItemStack output : outputs) {
      if (output.isEmpty()) continue;
      ItemStack leftover = insert.apply(output.copy());
      long remaining = leftover.getCount();
      delivered += output.getCount() - remaining;
      if (leftover.isEmpty()) continue;
      if (overflowToGround && drop.test(leftover)) {
        delivered += remaining;
        ground += remaining;
      } else {
        rejected += remaining;
        restore.accept(leftover);
      }
    }
    return new Delivery(delivered, rejected, ground);
  }

  /**
   * Tries to put one stack down at a player's feet; rejection leaves it for the caller to restore.
   *
   * <p>The game's own drop asks the level to add the item entity and then ignores the answer, so a stack
   * the level refused is a stack nobody will ever see again — which is what a player reports as items
   * vanishing when their backpack is full. The answer is read here, and it is not believed on its own
   * either: a level with nowhere to keep the entity reports that it took it and then never carries it, so
   * the entity is looked for before the stack is given up. Anything that did not really land goes back
   * into the storage instead of out of the world.
   *
   * @param player the player dropping it, at whose feet it lands
   * @param stack the stack to put down, retained unchanged on rejection
   * @return whether it really landed
   */
  private static boolean drop(ServerPlayer player, ItemStack stack) {
    ItemEntity entity = new ItemEntity(
        player.level(), player.getX(), player.getEyeY() - 0.3, player.getZ(), stack.copy());
    entity.setPickUpDelay(40);
    boolean accepted = player.level().addFreshEntity(entity);
    if (accepted && player.level().getEntity(entity.getId()) != null) {
      stack.setCount(0);
      return true;
    }
    if (accepted) {
      // Said yes and kept nothing: take the entity out of the way rather than leave a removed one listed.
      entity.discard();
    }
    return false;
  }
}
