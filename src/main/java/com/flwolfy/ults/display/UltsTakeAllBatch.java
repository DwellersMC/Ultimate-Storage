package com.flwolfy.ults.display;

import com.flwolfy.ults.data.state.UltsWithdrawalPlanner;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.ToLongFunction;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;

/** Progress of a confirmed request; accounting advances only after successful delivery. */
final class UltsTakeAllBatch {
  private final List<ItemStack> wanted;
  private final long[] owed;
  private int unit;
  private long remaining;
  private long taken;
  private boolean missing;

  UltsTakeAllBatch(List<ItemStack> wanted, List<Long> amounts) {
    if (wanted.size() != amounts.size()) {
      throw new IllegalArgumentException("Each requested row needs an amount");
    }
    this.wanted = wanted.stream().map(stack -> stack.copyWithCount(1)).toList();
    owed = new long[amounts.size()];
    for (int index = 0; index < owed.length; index++) {
      owed[index] = Math.max(0L, amounts.get(index));
      remaining = Math.addExact(remaining, owed[index]);
    }
  }

  /** Returns a stop reason, or null if more ticks are needed. Delivery answers the rejected count. */
  UltsTakeAllStream.Stop advance(
      int rate, boolean ground, Supplier<List<ItemStack>> slots,
      BiFunction<ItemStack, Integer, List<ItemStack>> withdraw,
      ToLongFunction<List<ItemStack>> deliver
  ) {
    long budget = Math.max(1, rate);
    while (unit < owed.length && budget > 0L) {
      if (owed[unit] == 0L) {
        unit++;
        continue;
      }
      ItemStack template = wanted.get(unit);
      long most = (long) UltsWithdrawalPlanner.MAX_OUTPUT_STACKS
          * Math.max(1, template.getMaxStackSize());
      long count = Math.min(Math.min(owed[unit], budget), most);
      if (!ground) {
        count = Math.min(count, UltsBackpack.room(slots.get(), template));
      }
      if (count == 0L) {
        return UltsTakeAllStream.Stop.BACKPACK;
      }
      List<ItemStack> output = withdraw.apply(template, (int) count);
      long out = output.stream().mapToLong(ItemStack::getCount).sum();
      if (out > count) {
        throw new IllegalStateException("Withdrawal exceeded the requested batch");
      }
      long rejected = out == 0 ? 0 : deliver.applyAsLong(output);
      if (rejected < 0L || rejected > out) {
        throw new IllegalStateException("Invalid delivery remainder");
      }
      long delivered = out - rejected;
      taken += delivered;
      remaining -= delivered;
      owed[unit] -= delivered;
      budget -= delivered;
      if (rejected > 0L) {
        return UltsTakeAllStream.Stop.BACKPACK;
      }
      if (out < count) {
        missing = true;
        unit++;
      }
    }
    while (unit < owed.length && owed[unit] == 0L) {
      unit++;
    }
    if (unit == owed.length) {
      return missing ? UltsTakeAllStream.Stop.EMPTY : UltsTakeAllStream.Stop.DONE;
    }
    return null;
  }

  long taken() { return taken; }
  long remaining() { return remaining; }
}
