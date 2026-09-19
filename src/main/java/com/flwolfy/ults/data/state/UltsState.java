package com.flwolfy.ults.data.state;

import com.flwolfy.ults.crafting.UltsCraftPool;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jetbrains.annotations.Nullable;

/**
 * Saved state of the one server storage.
 *
 * <p>It owns an ordered list of container bindings ({@code #1 #2 ...}, each with an optional note for
 * humans) and, in void mode, the single pool of stored items.
 */
public final class UltsState extends SavedData {

  private static final Codec<UltsState> CODEC = RecordCodecBuilder.create(instance ->
      instance.group(
          UltsBinding.CODEC.listOf().optionalFieldOf("bindings", List.of())
              .forGetter(state -> state.bindings),
          UltsStoredEntry.CODEC.listOf().optionalFieldOf("pool", List.of())
              .forGetter(state -> state.pool),
          Codec.unboundedMap(Codec.STRING, UltsViewProfile.CODEC)
              .optionalFieldOf("viewProfiles", Map.of()).forGetter(state -> state.viewProfiles)
      ).apply(instance, UltsState::new)
  );

  public static final SavedDataType<UltsState> TYPE = new SavedDataType<>(
      Identifier.fromNamespaceAndPath("ultimate-storage", "state"),
      UltsState::new,
      CODEC,
      DataFixTypes.LEVEL
  );

  private final List<UltsBinding> bindings = new ArrayList<>();
  /** Position to binding, so a lookup by position never walks the whole list. */
  private final Map<String, Long2ObjectMap<UltsBinding>> byPosition = new HashMap<>();
  /** Binding to its current {@code #N} number, kept in step with the list order. */
  private final Map<UltsBinding, Integer> numbers = new HashMap<>();
  private final List<UltsStoredEntry> pool = new ArrayList<>();
  private final Map<String, UltsViewProfile> viewProfiles = new HashMap<>();
  private long revision;

  public UltsState() {}

  private UltsState(
      List<UltsBinding> bindings,
      List<UltsStoredEntry> pool,
      Map<String, UltsViewProfile> viewProfiles
  ) {
    this.bindings.addAll(bindings);
    this.pool.addAll(sanitize(pool));
    this.viewProfiles.putAll(viewProfiles);
    reindex();
  }

  private static List<UltsStoredEntry> sanitize(List<UltsStoredEntry> entries) {
    List<UltsStoredEntry> clean = new ArrayList<>();
    entries.stream().filter(entry -> !entry.template().isEmpty() && entry.amount() > 0)
        .forEach(entry -> clean.add(new UltsStoredEntry(entry.template(), entry.amount())));
    return clean;
  }

  /** Rebuilds the position index and the numbering after a change to the list. */
  private void reindex() {
    byPosition.clear();
    numbers.clear();
    for (int index = 0; index < bindings.size(); index++) {
      UltsBinding binding = bindings.get(index);
      byPosition
          .computeIfAbsent(binding.dimension(), key -> new Long2ObjectOpenHashMap<>())
          .put(binding.pos().asLong(), binding);
      numbers.put(binding, index + 1);
    }
  }

  // =================== //
  // ===== Bindings ==== //
  // =================== //

  /** Every binding, in the {@code #1 #2 ...} order shown by the listings. */
  public synchronized List<UltsBinding> bindings() {
    return List.copyOf(bindings);
  }

  public synchronized int bindingCount() {
    return bindings.size();
  }

  /** The binding at a position, in constant time. */
  public synchronized UltsBinding binding(String dimension, BlockPos position) {
    Long2ObjectMap<UltsBinding> positions = byPosition.get(dimension);
    return positions == null ? null : positions.get(position.asLong());
  }

  /** The current {@code #N} of a binding, or {@code 0} when it is not bound any more. */
  public synchronized int number(UltsBinding binding) {
    return numbers.getOrDefault(binding, 0);
  }

  public synchronized boolean addBinding(UltsBinding binding) {
    if (binding(binding.dimension(), binding.pos()) != null) {
      return false;
    }
    bindings.add(binding);
    reindex();
    changed();
    return true;
  }

  /** Removes the 1-based inclusive range of bindings and returns what was removed. */
  public synchronized List<UltsBinding> removeBindings(int from, int to) {
    List<UltsBinding> removed = new ArrayList<>();
    for (int index = from - 1; index <= to - 1 && index < bindings.size(); index++) {
      if (index >= 0) {
        removed.add(bindings.get(index));
      }
    }
    if (!removed.isEmpty()) {
      bindings.removeAll(removed);
      // Removing re-numbers everything after the range, so the index is rebuilt once.
      reindex();
      changed();
    }
    return List.copyOf(removed);
  }

  // ==================== //
  // ===== Contents ===== //
  // ==================== //

  public synchronized List<UltsStoredView> items() {
    return pool.stream()
        .map(entry -> new UltsStoredView(
            entry.template(), entry.amount(), !UltsCreativeCatalog.contains(entry.template())))
        .sorted(Comparator.comparing(
            value -> value.template().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  public synchronized void deposit(ItemStack source) {
    if (source.isEmpty()) {
      return;
    }
    int outerCount = source.getCount();
    if (UltsBoxes.isShulker(source)) {
      ItemContainerContents contents = source.get(DataComponents.CONTAINER);
      ItemStack emptyBox = source.copyWithCount(1);
      emptyBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of()));
      add(pool, emptyBox, outerCount);
      if (contents != null) {
        contents.allItemsCopyStream().forEach(inner ->
            add(pool, inner, Math.multiplyExact((long) inner.getCount(), outerCount)));
      }
    } else {
      add(pool, source, outerCount);
    }
    changed();
  }

  public synchronized UltsWithdrawalPlan withdrawalPlan(
      ItemStack template,
      int quantity,
      boolean boxed,
      UltsCraftingMode mode
  ) {
    return UltsWithdrawalPlanner.plan(craftPool(), template, quantity, boxed, mode);
  }

  /**
   * Runs a decided withdrawal.
   *
   * <p>The plan is run twice: once on a copy, so a storage that changed since the screen was drawn
   * can never leave a half done craft behind, and then for real.
   */
  public synchronized List<ItemStack> takePlanned(
      UltsWithdrawalPlan plan,
      ItemStack template,
      int quantity,
      boolean boxed
  ) {
    if (!plan.available()) {
      return List.of();
    }
    UltsCraftPool contents = craftPool();
    if (!UltsWithdrawalPlanner.run(contents.copy(), plan, template, quantity, boxed)) {
      return List.of();
    }
    UltsWithdrawalPlanner.run(contents, plan, template, quantity, boxed);
    adoptPool(contents);
    changed();
    return plan.outputs().stream().map(ItemStack::copy).toList();
  }

  public synchronized ItemStack availableBox() {
    for (UltsStoredEntry entry : packableBoxes(pool)) {
      return entry.template().copyWithCount(1);
    }
    return ItemStack.EMPTY;
  }

  // ==================== //
  // === View profiles === //
  // ==================== //

  public synchronized UltsViewProfile viewProfile(UUID playerId) {
    return viewProfiles.getOrDefault(playerId.toString(), UltsViewProfile.DEFAULT);
  }

  public synchronized void setViewProfile(UUID playerId, UltsViewProfile profile) {
    if (!profile.equals(viewProfiles.put(playerId.toString(), profile))) {
      setDirty();
    }
  }

  public synchronized long revision() {
    return revision;
  }

  // ===================== //
  // ====== Internals ===== //
  // ===================== //

  /** The stored items as a working pile, which is what a withdrawal plans and runs on. */
  private UltsCraftPool craftPool() {
    UltsCraftPool contents = new UltsCraftPool(pool.size() + 8);
    for (UltsStoredEntry entry : pool) {
      contents.add(entry.template(), entry.amount());
    }
    return contents;
  }

  /** Writes a pile back as the stored items. */
  private void adoptPool(UltsCraftPool contents) {
    pool.clear();
    for (UltsStoredView view : contents.views()) {
      pool.add(new UltsStoredEntry(view.template(), view.amount()));
    }
  }

  // Empty shulker boxes in packing order: the default colour first, then the remaining colours.
  private static List<UltsStoredEntry> packableBoxes(List<UltsStoredEntry> pool) {
    List<UltsStoredEntry> boxes = new ArrayList<>();
    for (UltsStoredEntry entry : pool) {
      if (UltsBoxes.isPackable(entry.template()) && entry.amount() > 0) {
        boxes.add(entry);
      }
    }
    boxes.sort(Comparator.comparingInt(entry -> UltsBoxes.isPlain(entry.template()) ? 0 : 1));
    return boxes;
  }

  private static void add(List<UltsStoredEntry> pool, ItemStack source, long amount) {
    if (source.isEmpty() || amount < 1) {
      return;
    }
    ItemStack template = source.copyWithCount(1);
    for (int index = 0; index < pool.size(); index++) {
      UltsStoredEntry entry = pool.get(index);
      if (ItemStack.isSameItemSameComponents(entry.template(), template)) {
        long sum;
        try {
          sum = Math.addExact(entry.amount(), amount);
        } catch (ArithmeticException ignored) {
          sum = Long.MAX_VALUE;
        }
        pool.set(index, new UltsStoredEntry(template, sum));
        return;
      }
    }
    pool.add(new UltsStoredEntry(template, amount));
  }

  private void changed() {
    revision++;
    setDirty();
  }
}
