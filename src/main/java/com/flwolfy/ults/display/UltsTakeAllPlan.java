package com.flwolfy.ults.display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.world.item.ItemStack;

/** The bounded request a confirmation promises, including how much of each row may leave. */
record UltsTakeAllPlan(
    long total, int units, long taken, long pack, long ground, long left, List<Long> amounts
) {
  UltsTakeAllPlan {
    amounts = List.copyOf(amounts);
  }

  static UltsTakeAllPlan of(
      long total, List<ItemStack> wanted, List<Long> amounts,
      boolean overflow, int limitStacks, List<ItemStack> slots
  ) {
    total = Math.max(0L, total);
    List<Long> selected = new ArrayList<>(Collections.nCopies(wanted.size(), 0L));
    List<ItemStack> trial = new ArrayList<>(slots.stream().map(ItemStack::copy).toList());
    int limit = Math.max(1, limitStacks);
    int units = 0;
    long taken = 0L;
    long pack = 0L;
    for (int index = 0; index < wanted.size() && units < limit && taken < total; index++) {
      ItemStack template = wanted.get(index);
      if (template.isEmpty()) {
        continue;
      }
      int stackSize = Math.max(1, template.getMaxStackSize());
      long available = wanted.size() == 1 ? total : Math.max(0L, amounts.get(index));
      long most = Math.min(Math.min(available, total - taken), (long) (limit - units) * stackSize);
      long room = UltsBackpack.room(trial, template);
      long count = overflow ? most : Math.min(most, room);
      long fitting = Math.min(count, room);
      if (count == 0L) {
        break;
      }
      UltsBackpack.fill(trial, template.copyWithCount((int) fitting));
      selected.set(index, count);
      taken += count;
      pack += fitting;
      units += (int) ((count + stackSize - 1L) / stackSize);
      if (count < most || count < available) {
        break;
      }
    }
    return new UltsTakeAllPlan(total, units, taken, pack, taken - pack, total - taken, selected);
  }

  boolean possible() { return taken > 0L; }
  boolean partial() { return possible() && left > 0L; }
  boolean blocked() { return total > 0L && taken <= 0L; }
}
