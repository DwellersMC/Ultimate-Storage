package com.flwolfy.ults.crafting;

import com.flwolfy.ults.data.state.UltsBoxes;
import com.flwolfy.ults.data.state.UltsStoredView;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>A search tries a great many piles that it then throws away again, so a pile can be marked and
 * rolled back instead of being copied: {@link #mark()} remembers where it stood and
 * {@link #rollback(int)} puts it back exactly as it was. Copying a pile costs one entry per stack,
 * which is what used to make a search of any depth impossible to run.
 *
 * <p>Asking how much of a recipe slot the pile holds is the question a search asks more than any
 * other, so the pile keeps a bit per item of the registry and how much of each it holds. Counting a
 * slot is then an {@code &} of a handful of words rather than a walk over either the pile or the
 * slot, whatever a pack puts in front of it.
 */
public final class UltsCraftPool {

  private ItemStack[] templates;
  private long[] amounts;
  private int size;
  /** The entry a withdrawal wants to hand over, and how much of it has to stay in the pile. */
  private int keptIndex = -1;
  private long keptAmount;

  /** How much of every item the pile holds, by registry id, and which of them it holds at all. */
  private final long[] heldAmounts;
  private final long[] heldMask;
  /** How much the pile holds in total. */
  private long heldTotal;
  /** Which entries hold an item, in pile order, so a slot can be filled without walking the pile. */
  private final Map<Item, IntArrayList> entriesByItem = new HashMap<>();

  /** The changes a rollback has to undo, newest last. */
  private int[] undoneIndex = new int[64];
  private long[] undoneAmount = new long[64];
  private int[] undoneSize = new int[64];
  private int undone;

  /** The entries one take works on, reused so filling a slot does not allocate. */
  private int[] takeBuffer = new int[32];

  public UltsCraftPool(int capacity) {
    int room = Math.max(4, capacity);
    templates = new ItemStack[room];
    amounts = new long[room];
    heldAmounts = new long[UltsIngredients.itemWords() << 6];
    heldMask = new long[UltsIngredients.itemWords()];
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
    for (int index = 0; index < size; index++) {
      copy.append(templates[index], amounts[index]);
    }
    copy.keptIndex = keptIndex;
    copy.keptAmount = keptAmount;
    return copy;
  }

  /**
   * Takes over the contents of another pile, used to commit the pile a plan was worked out on.
   *
   * <p>The journal starts over, so a mark taken before this call can no longer be rolled back to.
   */
  public void copyFrom(UltsCraftPool other) {
    templates = new ItemStack[Math.max(4, other.size)];
    amounts = new long[templates.length];
    Arrays.fill(heldAmounts, 0L);
    Arrays.fill(heldMask, 0L);
    entriesByItem.clear();
    heldTotal = 0L;
    keptIndex = -1;
    keptAmount = 0L;
    undone = 0;
    size = 0;
    for (int index = 0; index < other.size; index++) {
      append(other.templates[index], other.amounts[index]);
    }
    keptIndex = other.keptIndex;
    keptAmount = other.keptAmount;
    undone = 0;
  }

  // ================== //
  // ===== Marks ====== //
  // ================== //

  /**
   * Remembers where the pile stands, so an attempt that fails can be undone.
   *
   * <p>Marks nest: a rollback puts the pile back to the state of its own mark and leaves anything an
   * older mark covers alone. Nothing else may change the pile between a mark and its rollback.
   *
   * @return a handle to hand to {@link #rollback(int)}
   */
  public int mark() {
    return undone;
  }

  /** Puts the pile back exactly as it stood at a mark, undoing every change made since. */
  public void rollback(int mark) {
    while (undone > mark) {
      undone--;
      int index = undoneIndex[undone];
      if (index >= undoneSize[undone]) {
        // The entry itself was appended by the step being undone, so it goes away with it.
        IntArrayList holders = entriesByItem.get(templates[index].getItem());
        if (holders != null && !holders.isEmpty()) {
          holders.removeInt(holders.size() - 1);
        }
      }
      setAmount(index, undoneAmount[undone]);
      size = undoneSize[undone];
    }
  }

  // =========================== //
  // ===== Reading a pile ====== //
  // =========================== //

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
    int id = UltsIngredients.itemId(item);
    return id >= 0 && id < heldAmounts.length && heldAmounts[id] > 0L;
  }

  /** How many items the pile holds in total; no craft can use more items than this. */
  public long total() {
    return heldTotal;
  }

  /**
   * How many items an ingredient accepts; used to see how hard a recipe slot is to fill.
   *
   * <p>Counted by intersecting the bits of what the slot names with the bits of what the pile holds,
   * because a slot matches on the item alone: a pile of three hundred stacks, or a slot naming six
   * hundred items, still answers this by looking at a couple of dozen words.
   */
  public long matches(Ingredient ingredient) {
    long[] mask = UltsIngredients.itemMask(ingredient);
    long total = 0L;
    int shared = Math.min(mask.length, heldMask.length);
    for (int word = 0; word < shared; word++) {
      long hits = mask[word] & heldMask[word];
      while (hits != 0L) {
        int item = (word << 6) | Long.numberOfTrailingZeros(hits);
        hits &= hits - 1L;
        total = UltsCraftMath.add(total, heldAmounts[item]);
      }
    }
    if (keptIndex >= 0 && keptIndex < size && keptAmount > 0L
        && UltsIngredients.accepts(ingredient, templates[keptIndex].getItem())) {
      total -= Math.min(amounts[keptIndex], keptAmount);
      if (total < 0L) {
        total = 0L;
      }
    }
    return total;
  }

  // =========================== //
  // ===== Changing a pile ===== //
  // =========================== //

  public void add(ItemStack template, long amount) {
    if (template == null || template.isEmpty() || amount <= 0L) {
      return;
    }
    int index = indexOf(template);
    if (index >= 0) {
      record(index);
      setAmount(index, UltsCraftMath.add(amounts[index], amount));
      return;
    }
    append(template, amount);
  }

  /** Removes up to {@code requested} items of exactly this stack. */
  public long take(ItemStack template, long requested) {
    int index = indexOf(template);
    if (index < 0 || requested <= 0L) {
      return 0L;
    }
    long taken = Math.min(requested, usable(index));
    if (taken > 0L) {
      record(index);
      setAmount(index, amounts[index] - taken);
    }
    return taken;
  }

  /** Removes up to {@code requested} items an ingredient accepts, in pile order. */
  public long takeMatching(Ingredient ingredient, long requested) {
    if (requested <= 0L) {
      return 0L;
    }
    int found = collect(UltsIngredients.itemMask(ingredient));
    long remaining = requested;
    for (int position = 0; position < found && remaining > 0L; position++) {
      int index = takeBuffer[position];
      long usable = usable(index);
      if (usable <= 0L) {
        continue;
      }
      long taken = Math.min(remaining, usable);
      record(index);
      setAmount(index, amounts[index] - taken);
      remaining -= taken;
    }
    return requested - remaining;
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

  // ===================== //
  // ===== Internals ===== //
  // ===================== //

  private long takePackable(long requested, boolean plain) {
    long remaining = requested;
    for (int index = 0; index < size && remaining > 0L; index++) {
      long usable = usable(index);
      if (usable <= 0L || !UltsBoxes.isPackable(templates[index])
          || UltsBoxes.isPlain(templates[index]) != plain) {
        continue;
      }
      long taken = Math.min(remaining, usable);
      record(index);
      setAmount(index, amounts[index] - taken);
      remaining -= taken;
    }
    return requested - remaining;
  }

  /** Adds an entry, however little it holds, keeping the index of every item up to date. */
  private void append(ItemStack template, long amount) {
    ensure(size + 1);
    record(size);
    templates[size] = template.copyWithCount(1);
    setAmount(size, amount);
    entriesByItem.computeIfAbsent(templates[size].getItem(), key -> new IntArrayList()).add(size);
    size++;
  }

  /**
   * Gathers the entries a slot accepts into {@link #takeBuffer}, in pile order.
   *
   * <p>Only the items the pile really holds are looked at, so a slot naming hundreds of items costs
   * the handful of them that are actually there.
   */
  private int collect(long[] mask) {
    int found = 0;
    int items = 0;
    int shared = Math.min(mask.length, heldMask.length);
    for (int word = 0; word < shared && found < size; word++) {
      long hits = mask[word] & heldMask[word];
      while (hits != 0L) {
        int item = (word << 6) | Long.numberOfTrailingZeros(hits);
        hits &= hits - 1L;
        IntArrayList holders = entriesByItem.get(UltsIngredients.itemAt(item));
        if (holders == null || holders.isEmpty()) {
          continue;
        }
        items++;
        int wanted = found + holders.size();
        if (wanted > takeBuffer.length) {
          takeBuffer = Arrays.copyOf(takeBuffer, Math.max(wanted, takeBuffer.length * 2));
        }
        for (int slot = 0; slot < holders.size(); slot++) {
          takeBuffer[found + slot] = holders.getInt(slot);
        }
        found = wanted;
      }
    }
    // One item's entries are already in pile order; several items' entries have to be merged.
    if (items > 1 && found > 1) {
      Arrays.sort(takeBuffer, 0, found);
    }
    return found;
  }

  /** How much of this entry crafting may take: what is kept for the withdrawal stays in the pile. */
  private long usable(int index) {
    return index == keptIndex ? Math.max(0L, amounts[index] - keptAmount) : amounts[index];
  }

  private int indexOf(ItemStack template) {
    // Only the entries of this item can hold it, so a pile of thousands of stacks is not walked.
    IntArrayList holders = entriesByItem.get(template.getItem());
    if (holders == null) {
      return -1;
    }
    for (int slot = 0; slot < holders.size(); slot++) {
      int index = holders.getInt(slot);
      if (index < size && ItemStack.isSameItemSameComponents(templates[index], template)) {
        return index;
      }
    }
    return -1;
  }

  /** Remembers one entry's amount, so a rollback can put it back. */
  private void record(int index) {
    if (undone == undoneIndex.length) {
      int room = undoneIndex.length * 2;
      undoneIndex = Arrays.copyOf(undoneIndex, room);
      undoneAmount = Arrays.copyOf(undoneAmount, room);
      undoneSize = Arrays.copyOf(undoneSize, room);
    }
    undoneIndex[undone] = index;
    undoneAmount[undone] = amounts[index];
    undoneSize[undone] = size;
    undone++;
  }

  /** Sets one entry's amount, keeping the per item totals and bits in step with it. */
  private void setAmount(int index, long value) {
    long previous = amounts[index];
    if (previous == value) {
      return;
    }
    amounts[index] = value;
    heldTotal += value - previous;
    if (heldTotal < 0L) {
      heldTotal = 0L;
    }
    int id = UltsIngredients.itemId(templates[index].getItem());
    if (id < 0 || id >= heldAmounts.length) {
      return;
    }
    // An item may be held by several entries at once, so only the item's own total says whether the
    // pile holds any of it at all.
    long wasHeld = heldAmounts[id];
    long nowHeld = wasHeld + value - previous;
    if (wasHeld <= 0L && nowHeld > 0L) {
      heldMask[id >>> 6] |= 1L << (id & 63);
    } else if (wasHeld > 0L && nowHeld <= 0L) {
      heldMask[id >>> 6] &= ~(1L << (id & 63));
    }
    heldAmounts[id] = nowHeld;
  }

  private void ensure(int wanted) {
    if (wanted <= templates.length) {
      return;
    }
    int room = Math.max(wanted, templates.length * 2);
    templates = Arrays.copyOf(templates, room);
    amounts = Arrays.copyOf(amounts, room);
  }
}
