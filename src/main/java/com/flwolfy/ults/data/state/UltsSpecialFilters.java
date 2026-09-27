package com.flwolfy.ults.data.state;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsSpecialFilter;
import com.flwolfy.ults.util.UltsItemIds;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Which stacks that carry data of their own are not worth keeping.
 *
 * <p>An enchanted or worn tool cannot stack with anything, so it takes a row of the special category
 * for itself. A busy server ends up with hundreds of near identical swords nobody asked for, so the
 * configuration may name the items that are thrown away on sight instead. Two sources feed that list:
 * the ids a server owner writes down, and, when it is switched on, every piece of equipment a loot
 * table can drop, which is what fills those rows in the first place.
 *
 * <p>A plain stack of the same item is never touched: it stacks with its own kind, so it is not
 * special and never reaches this rule.
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
   * Whether one special stack is destroyed instead of being stored.
   *
   * @param stack the stack as it would be stored, components and all
   * @return whether the configuration throws it away
   */
  public static boolean destroys(ItemStack stack) {
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
