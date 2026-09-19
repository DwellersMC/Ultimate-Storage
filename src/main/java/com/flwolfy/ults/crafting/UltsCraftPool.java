package com.flwolfy.ults.crafting;

import com.flwolfy.ults.data.state.UltsBoxes;
import com.flwolfy.ults.data.state.UltsStoredView;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/**
 * The pile of items a withdrawal works on.
 *
 * <p>Planning and running a craft both happen here: an ingredient is taken out of the pile and what a
 * recipe produces is put back in, which is what lets a recipe consume the result of another one. The
 * pile is also what the storage looks like, so the same arithmetic decides whether a request fits.
 */
public final class UltsCraftPool {

  private ItemStack[] templates;
  private long[] amounts;
  private int size;
  /** The entry a withdrawal wants to hand over, and how much of it has to stay in the pile. */
  private int keptIndex = -1;
  private long keptAmount;

  public UltsCraftPool(int capacity) {
    int room = Math.max(4, capacity);
    templates = new ItemStack[room];
    amounts = new long[room];
  }

  public static UltsCraftPool of(List<UltsStoredView> views) {
    UltsCraftPool pool = new UltsCraftPool(views.size() + 4);
    for (UltsStoredView view : views) {
      pool.add(view.template(), view.amount());
    }
    return pool;
  }

  public UltsCraftPool copy() {
    UltsCraftPool copy = new UltsCraftPool(size);
    System.arraycopy(templates, 0, copy.templates, 0, size);
    System.arraycopy(amounts, 0, copy.amounts, 0, size);
    copy.size = size;
    copy.keptIndex = keptIndex;
    copy.keptAmount = keptAmount;
    return copy;
  }

  /** Takes over the contents of another pile, used to commit the pile a plan was worked out on. */
  public void copyFrom(UltsCraftPool other) {
    ensure(other.size);
    System.arraycopy(other.templates, 0, templates, 0, other.size);
    System.arraycopy(other.amounts, 0, amounts, 0, other.size);
    size = other.size;
    keptIndex = other.keptIndex;
    keptAmount = other.keptAmount;
  }

  /**
   * Keeps an amount of what is already stored of one stack in the pile: no crafting may take it away.
   *
   * <p>A withdrawal promises to hand over what it asked for, so the items it wants to hand over are
   * off limits to the recipes that are worked out or run on the way. Only what is stored right now is
   * kept; what a recipe makes while the plan runs is its own to use.
   *
   * @param template the stack that stays, or {@code null} to lift the rule
   * @param amount how much of the stored amount stays
   */
  public void keep(@Nullable ItemStack template, long amount) {
    keptIndex = template == null || template.isEmpty() || amount <= 0L ? -1 : indexOf(template);
    keptAmount = keptIndex < 0 ? 0L : amount;
  }

  public int size() {
    return size;
  }

  public ItemStack templateAt(int index) {
    return templates[index];
  }

  public long amountAt(int index) {
    return amounts[index];
  }

  /** How much of exactly this stack is in the pile, components included. */
  public long amount(ItemStack template) {
    int index = indexOf(template);
    return index < 0 ? 0L : amounts[index];
  }

  /** Whether the pile holds at least one of this item, whatever its components are. */
  public boolean has(Item item) {
    for (int index = 0; index < size; index++) {
      if (amounts[index] > 0L && templates[index].is(item)) {
        return true;
      }
    }
    return false;
  }

  /** How many items the pile holds in total; no craft can use more items than this. */
  public long total() {
    long total = 0L;
    for (int index = 0; index < size; index++) {
      total = UltsCraftMath.add(total, amounts[index]);
    }
    return total;
  }

  public void add(ItemStack template, long amount) {
    if (template == null || template.isEmpty() || amount <= 0L) {
      return;
    }
    int index = indexOf(template);
    if (index >= 0) {
      amounts[index] = UltsCraftMath.add(amounts[index], amount);
      return;
    }
    ensure(size + 1);
    templates[size] = template.copyWithCount(1);
    amounts[size] = amount;
    size++;
  }

  /** Removes up to {@code requested} items of exactly this stack. */
  public long take(ItemStack template, long requested) {
    int index = indexOf(template);
    if (index < 0 || requested <= 0L) {
      return 0L;
    }
    long taken = Math.min(requested, usable(index));
    amounts[index] -= taken;
    return taken;
  }

  /** How many items an ingredient accepts; used to see how hard a recipe slot is to fill. */
  public long matches(Ingredient ingredient) {
    long total = 0L;
    for (int index = 0; index < size; index++) {
      if (usable(index) > 0L && ingredient.test(templates[index])) {
        total = UltsCraftMath.add(total, usable(index));
      }
    }
    return total;
  }

  /** Removes up to {@code requested} items an ingredient accepts, in pile order. */
  public long takeMatching(Ingredient ingredient, long requested) {
    long remaining = requested;
    for (int index = 0; index < size && remaining > 0L; index++) {
      long usable = usable(index);
      if (usable <= 0L || !ingredient.test(templates[index])) {
        continue;
      }
      long taken = Math.min(remaining, usable);
      amounts[index] -= taken;
      remaining -= taken;
    }
    return requested - remaining;
  }

  /** How many empty boxes the pile holds. */
  public long packableAmount() {
    long total = 0L;
    for (int index = 0; index < size; index++) {
      if (amounts[index] > 0L && UltsBoxes.isPackable(templates[index])) {
        total = UltsCraftMath.add(total, amounts[index]);
      }
    }
    return total;
  }

  /** How many empty boxes of the default colour the pile holds, the ones packing uses first. */
  public long plainPackableAmount() {
    long total = 0L;
    for (int index = 0; index < size; index++) {
      if (amounts[index] > 0L && UltsBoxes.isPackable(templates[index])
          && UltsBoxes.isPlain(templates[index])) {
        total = UltsCraftMath.add(total, amounts[index]);
      }
    }
    return total;
  }

  /**
   * Removes empty boxes for a packed withdrawal: the default colour first, whatever other colours are
   * stored only after that, so a box that can be crafted is never preferred over a plain one.
   */
  public long takePackable(long requested) {
    long remaining = requested;
    remaining -= takePackable(remaining, true);
    if (remaining > 0L) {
      remaining -= takePackable(remaining, false);
    }
    return requested - Math.max(0L, remaining);
  }

  /** The empty boxes of one kind, in pile order, so packing can name the boxes it fills. */
  public List<UltsStoredView> packableViews(boolean plain) {
    List<UltsStoredView> boxes = new ArrayList<>();
    for (int index = 0; index < size; index++) {
      if (amounts[index] <= 0L || !UltsBoxes.isPackable(templates[index])
          || UltsBoxes.isPlain(templates[index]) != plain) {
        continue;
      }
      boxes.add(new UltsStoredView(templates[index], amounts[index], false));
    }
    return boxes;
  }

  /** Everything in the pile, for writing it back into the storage. */
  public List<UltsStoredView> views() {
    List<UltsStoredView> views = new ArrayList<>(size);
    for (int index = 0; index < size; index++) {
      if (amounts[index] > 0L) {
        views.add(new UltsStoredView(templates[index], amounts[index], false));
      }
    }
    return views;
  }

  private long takePackable(long requested, boolean plain) {
    long remaining = requested;
    for (int index = 0; index < size && remaining > 0L; index++) {
      long usable = usable(index);
      if (usable <= 0L || !UltsBoxes.isPackable(templates[index])
          || UltsBoxes.isPlain(templates[index]) != plain) {
        continue;
      }
      long taken = Math.min(remaining, usable);
      amounts[index] -= taken;
      remaining -= taken;
    }
    return requested - remaining;
  }

  /** How much of this entry crafting may take: what is kept for the withdrawal stays in the pile. */
  private long usable(int index) {
    return index == keptIndex ? Math.max(0L, amounts[index] - keptAmount) : amounts[index];
  }

  private int indexOf(ItemStack template) {
    for (int index = 0; index < size; index++) {
      if (ItemStack.isSameItemSameComponents(templates[index], template)) {
        return index;
      }
    }
    return -1;
  }

  private void ensure(int wanted) {
    if (wanted <= templates.length) {
      return;
    }
    int room = Math.max(wanted, templates.length * 2);
    ItemStack[] grownTemplates = new ItemStack[room];
    long[] grownAmounts = new long[room];
    System.arraycopy(templates, 0, grownTemplates, 0, size);
    System.arraycopy(amounts, 0, grownAmounts, 0, size);
    templates = grownTemplates;
    amounts = grownAmounts;
  }
}
