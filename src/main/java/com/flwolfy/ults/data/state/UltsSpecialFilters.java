package com.flwolfy.ults.data.state;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsSpecialFilter;
import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.flwolfy.ults.util.UltsItemIds;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Which stacks that carry data of their own the storage dismisses.
 *
 * <p>An enchanted or worn tool cannot stack with anything, so it takes a row of the special category
 * for itself. A busy server ends up with hundreds of near identical swords nobody asked for, so the
 * configuration may name the items that are not worth a row. Two sources feed that list: the ids a
 * server owner writes down, and, when it is switched on, every piece of equipment a loot table can
 * drop, which is what fills those rows in the first place.
 *
 * <p>A plain stack of the same item is never touched: it stacks with its own kind, so it is not
 * special and never reaches this rule.
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

  /** Items the configuration names itself. */
  private static volatile Set<Item> declared = Set.of();
  /** Equipment a loot table can drop, when the configuration asks for those too. */
  private static volatile Set<Item> lootEquipment = Set.of();

  private UltsSpecialFilters() {}

  /**
   * Reads the configured ids and takes the loot table equipment with them.
   *
   * <p>Called at startup and on {@code /ults reload}, after the survival catalogue has read the loot
   * tables.
   *
   * @param equipment what the loot tables drop and a player can wear or wield
   */
  public static void rebuild(Set<Item> equipment) {
    Set<Item> items = new HashSet<>();
    for (String id : UltsConfigManager.getInstance().data().special().filters()) {
      UltsItemIds.resolve(id).ifPresentOrElse(items::add, () ->
          UltsMod.LOGGER.warn("UltStorage: the special filter names no item of this game: {}", id));
    }
    declared = Set.copyOf(items);
    lootEquipment = Set.copyOf(equipment);
  }

  /**
   * Whether the storage dismisses this stack.
   *
   * <p>Only a stack that would otherwise take a special row can be dismissed; a plain stack of the
   * same item is left alone, however its item is listed. Callers then throw the stack away in void
   * mode, or leave it out of a listing in remote mode.
   *
   * @param stack the stack as it would be stored, components and all
   * @return whether the configuration dismisses it
   */
  public static boolean dismisses(ItemStack stack) {
    if (stack.isEmpty()) {
      return false;
    }
    UltsConfigData.Special special = UltsConfigManager.getInstance().data().special();
    if (!special.filterMode().active()) {
      return false;
    }
    Item item = stack.getItem();
    if (!declared.contains(item)
        && !(special.filterLootEquipment() && lootEquipment.contains(item))) {
      return false;
    }
    // A stack the catalogue knows is the plain one: it stacks with its own kind, so it is not special
    // and the filter has no business with it.
    if (UltsCreativeCatalog.contains(stack)) {
      return false;
    }
    if (special.filterMode() == UltsSpecialFilter.FILTER_ALL) {
      return true;
    }
    // Keeping what is at full durability is the point of the middle mode: a pristine item is still
    // worth a row, a worn one is not.
    return stack.isDamaged();
  }

  /** How many items the filter names, for the log and the configuration screen. */
  public static int declaredSize() {
    return declared.size();
  }

  /** How many equipment items the loot tables add to the filter. */
  public static int lootEquipmentSize() {
    return lootEquipment.size();
  }
}
