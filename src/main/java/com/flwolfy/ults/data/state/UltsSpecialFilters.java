package com.flwolfy.ults.data.state;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsSpecialFilter;
import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.flwolfy.ults.util.UltsItemIds;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Which stacks that carry data of their own the storage dismisses.
 *
 * <p>A stack that carries data of its own — an enchanted or worn tool, a brew, a book somebody wrote in
 * — is a kind of thing of its own, so a busy server ends up with hundreds of near identical swords
 * nobody asked for. The configuration may name the items that are not worth keeping. The filter names
 * them by item id and nothing else: what is not written down is kept, so a server that wants a farm's
 * gear gone writes that gear down.
 *
 * <p>A plain stack of the same item is never touched: it is the pile of its item, exactly like every
 * other plain stack, and there is nothing about it to dismiss. Neither is a stack a player renamed,
 * which is somebody's own thing rather than anonymous loot.
 *
 * <h2>What dismissing means</h2>
 *
 * <p>The two storage modes own their items differently, so the same decision has two outcomes:
 *
 * <ul>
 *   <li><b>Void storage</b> keeps the items itself, so a dismissed stack is <b>destroyed</b>: it is
 *       thrown away on the way in, and applying the filter again clears out whatever was stored
 *       before it named the item.
 *   <li><b>Remote storage</b> keeps the items in the containers a player bound, which are not the
 *       storage's to destroy. There a dismissed stack is only <b>hidden</b>: it stays in its
 *       container, and simply never appears in a listing, a box count or a plan.
 * </ul>
 */
public final class UltsSpecialFilters {

  /** Items the configuration names itself by id. */
  private static volatile Set<Item> declared = Set.of();

  private UltsSpecialFilters() {}

  /** Reads the configured ids. Called at startup and on {@code /ults reload}. */
  public static void rebuild() {
    Set<Item> items = new HashSet<>();
    for (String id : UltsConfigManager.getInstance().data().special().filters()) {
      UltsItemIds.resolve(id).ifPresentOrElse(items::add, () ->
          UltsMod.LOGGER.warn("UltStorage: the special filter names no item of this game: {}", id));
    }
    declared = Set.copyOf(items);
  }

  /**
   * Whether the storage dismisses this stack.
   *
   * <p>Only a stack that carries data of its own is at stake: it is a kind of thing of its own, and one
   * the configuration can decide it does not want. A plain stack of the same item is the pile of that
   * item and is left alone, however its item is listed. Callers then throw the stack away in void mode,
   * or leave it out of a listing in remote mode.
   *
   * <p>A stack a player gave a name to is never dismissed. Renaming something is a deliberate act —
   * the stack is somebody's, not anonymous loot — so the filter has no business with it, whatever the
   * list or the mode says. Enchanting is not renaming, so enchanted gear is still filtered.
   *
   * @param stack the stack as it would be stored, components and all
   * @return whether the configuration dismisses it
   */
  public static boolean dismisses(ItemStack stack) {
    if (stack.isEmpty()) {
      return false;
    }
    // The name is the one rule that can never be overridden, so it is asked before anything else.
    if (stack.has(DataComponents.CUSTOM_NAME)) {
      return false;
    }
    UltsConfigData.Special special = UltsConfigManager.getInstance().data().special();
    if (!special.filterMode().active()) {
      return false;
    }
    if (!declared.contains(stack.getItem())) {
      return false;
    }
    // A stack a category can show is that category's business: it pools with its own kind and never
    // takes a bag, so the filter has nothing to dismiss. What is left is the data a stack brought with
    // it, which is exactly what a bag would have kept.
    if (UltsCreativeCatalog.contains(stack)) {
      return false;
    }
    if (special.filterMode() == UltsSpecialFilter.FILTER_ALL) {
      return true;
    }
    // Keeping what is at full durability is the point of the middle mode: a pristine item is still
    // worth keeping, a worn one is not.
    return stack.isDamaged();
  }

  /** How many items the filter names, for the log and the configuration screen. */
  public static int declaredSize() {
    return declared.size();
  }
}
