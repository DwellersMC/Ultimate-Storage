package com.flwolfy.ults.data.state;

import com.flwolfy.ults.crafting.UltsCraftPool;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.config.UltsStackRule;
import com.flwolfy.ults.data.config.UltsStorageMode;
import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Saved state of the one server storage.
 *
 * <p>It owns an ordered list of container bindings ({@code #1 #2 ...}, each with an optional note for
 * humans) and, in void mode, the single pool of stored items.
 *
 * <p>Every kind of item remembers when it was last put in. Only the special category has a use for
 * that — it sorts and trims by it — but the stamp is kept for every kind so a stack that moves between
 * the two categories keeps its history.
 */
public final class UltsState extends SavedData {

  private static final Codec<UltsState> CODEC = RecordCodecBuilder.create(instance ->
      instance.group(
          tolerantList(UltsBinding.CODEC).optionalFieldOf("bindings", List.of())
              .forGetter(state -> state.bindings),
          tolerantList(UltsStoredEntry.CODEC).optionalFieldOf("pool", List.of())
              .forGetter(state -> state.pool),
          Codec.unboundedMap(Codec.STRING, UltsViewProfile.CODEC)
              .optionalFieldOf("viewProfiles", Map.of()).forGetter(state -> state.viewProfiles),
          tolerantList(UltsStoredEntry.CODEC).optionalFieldOf("remoteRecovery", List.of())
              .forGetter(state -> state.remoteRecovery)
      ).apply(instance, UltsState::new)
  );

  /**
   * A codec that reads a whole list even when part of it cannot be read.
   *
   * <p>The state is one saved-data entry, and saved data that will not parse is thrown away as a whole and
   * then written back in its empty form. One stack naming an item this world no longer knows — a mod that
   * was removed, a datapack that changed — would therefore cost every binding and everything stored. An
   * entry that cannot be read is left out instead, and the rest of the state survives.
   *
   * @param element the codec of one entry
   * @param <T> what one entry is
   * @return a codec of the list, which drops the entries it cannot read
   */
  private static <T> Codec<List<T>> tolerantList(Codec<T> element) {
    // PASSTHROUGH accepts whatever is there, so the pair always reads: what the entry's own codec could
    // not read comes back as the right-hand side and is left out.
    return Codec.either(element, Codec.PASSTHROUGH).listOf().xmap(
        entries -> {
          List<T> kept = new ArrayList<>(entries.size());
          for (Either<T, Dynamic<?>> entry : entries) {
            entry.left().ifPresent(kept::add);
          }
          return List.copyOf(kept);
        },
        entries -> {
          List<Either<T, Dynamic<?>>> written = new ArrayList<>(entries.size());
          for (T entry : entries) {
            written.add(Either.left(entry));
          }
          return written;
        });
  }

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
  /** Undelivered remote items; independent of storage filters, stacking rules and bag limits. */
  private final List<UltsStoredEntry> remoteRecovery = new ArrayList<>();
  private int recoveryCursor;
  /**
   * How many players' views are kept.
   *
   * <p>A view is a convenience — which category, which page, which search — and one entry is written per
   * player who ever opens a screen. A server that has seen more players than this keeps the most recently
   * saved views and lets the oldest go, rather than carrying every player it has ever seen in its saved
   * data for ever.
   */
  private static final int MAX_VIEW_PROFILES = 10_000;
  /** Insertion ordered, so the oldest view is the first one there is to let go of. */
  private final Map<String, UltsViewProfile> viewProfiles = new LinkedHashMap<>();
  private long revision;

  public UltsState() {}

  private UltsState(
      List<UltsBinding> bindings,
      List<UltsStoredEntry> pool,
      Map<String, UltsViewProfile> viewProfiles,
      List<UltsStoredEntry> remoteRecovery
  ) {
    this.bindings.addAll(bindings);
    this.pool.addAll(sanitize(pool));
    this.viewProfiles.putAll(viewProfiles);
    this.remoteRecovery.addAll(sanitize(remoteRecovery));
    reindex();
    // A cap or a filter that was changed while the server was down takes effect on the way in, so an
    // existing storage is brought in line instead of waiting for the next stack to arrive.
    boolean trimmed = trimSpecial();
    trimmed |= purgeFiltered();
    if (trimmed) {
      setDirty();
    }
  }

  private static List<UltsStoredEntry> sanitize(List<UltsStoredEntry> entries) {
    List<UltsStoredEntry> clean = new ArrayList<>();
    entries.stream().filter(entry -> !entry.template().isEmpty() && entry.amount() > 0)
        .forEach(entry -> clean.add(new UltsStoredEntry(
            entry.template(), entry.amount(), entry.updatedAt())));
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
            entry.template(), entry.amount(), special(entry.template()), entry.updatedAt()))
        .sorted(Comparator.comparing(
            value -> value.template().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  /**
   * Whether a stored stack is one of the special ones, which a bag is kept for.
   *
   * <p>A category can only show what its own tabs list, component for component: a netherite sword is
   * the combat tab's business, an **enchanted** netherite sword is not, because no tab entry is that
   * exact stack. What a category cannot hold goes to the special category, where one bag keeps every
   * such stack of one item together. Three identically enchanted swords are still one stack there.
   */
  private static boolean special(ItemStack template) {
    return !UltsCreativeCatalog.contains(template);
  }

  /** How many kinds of item the storage holds that carry data of their own. */
  public synchronized int specialCount() {
    int special = 0;
    for (UltsStoredEntry entry : pool) {
      if (special(entry.template())) {
        special++;
      }
    }
    return special;
  }

  /**
   * The stored rows of one item that carries data of its own, newest first.
   *
   * <p>Every row is one arrival: two identical named swords are two rows, because the bag a row opens
   * is a list of the things themselves rather than of kinds of thing.
   *
   * @param item the item in question
   * @return the rows, newest first
   */
  public synchronized List<UltsStoredView> specialsOf(Item item) {
    return pool.stream()
        .filter(entry -> entry.template().is(item) && special(entry.template()))
        .map(entry -> new UltsStoredView(entry.template(), entry.amount(), true, entry.updatedAt()))
        .sorted(Comparator.comparingLong(UltsStoredView::updatedAt).reversed())
        .toList();
  }

  /**
   * Takes one row of a bag out of the storage, whole.
   *
   * <p>A bag hands things over one at a time, and a row is one kind of thing somebody stored, so this
   * removes exactly that row and leaves every other row of the item, and their times, untouched. The row
   * is found by its kind, which is what the screen was showing when it was clicked.
   *
   * @param item the item whose bag is being taken from
   * @param stack the stack that is leaving, as the row showed it
   * @return the row that left, as the stack it was stored as, or an empty stack when there is no such
   *     row any more
   */
  public synchronized ItemStack takeBagRow(Item item, ItemStack stack) {
    return takeBagPieces(item, stack, Integer.MAX_VALUE);
  }

  /** Takes at most the requested number from a bag row, preserving the remainder and its timestamp. */
  public synchronized ItemStack takeBagPieces(Item item, ItemStack stack, int quantity) {
    if (quantity <= 0) {
      return ItemStack.EMPTY;
    }
    UltsStoredEntry found = null;
    for (UltsStoredEntry entry : pool) {
      if (entry.template().is(item)
          && special(entry.template())
          && UltsStackKinds.same(entry.template(), stack)) {
        found = entry;
        break;
      }
    }
    if (found == null) {
      return ItemStack.EMPTY;
    }
    int taken = (int) Math.min(found.amount(), quantity);
    int index = pool.indexOf(found);
    if (taken == found.amount()) {
      pool.remove(index);
    } else {
      pool.set(index, new UltsStoredEntry(
          found.template(), found.amount() - taken, found.updatedAt()));
    }
    changed();
    return found.template().copyWithCount(taken);
  }

  /** Takes ownership of recovery items without unpacking boxes or applying destructive filters. */
  public synchronized void keepRemoteRecovery(List<ItemStack> stacks) {
    boolean any = false;
    for (ItemStack stack : stacks) {
      if (stack.isEmpty()) {
        continue;
      }
      remoteRecovery.add(new UltsStoredEntry(stack, stack.getCount(), now()));
      stack.setCount(0);
      any = true;
    }
    if (any) {
      setDirty();
    }
  }

  public synchronized List<UltsStoredView> remoteRecovery() {
    return remoteRecovery.stream().map(entry -> new UltsStoredView(
        entry.template(), entry.amount(), true, entry.updatedAt())).toList();
  }

  /** Retries at most one inventory of recovery stacks; the callback leaves unplaced counts in place. */
  public synchronized boolean retryRemoteRecovery(Consumer<List<ItemStack>> restore) {
    if (remoteRecovery.isEmpty()) {
      return false;
    }
    record Offer(int index, int count, ItemStack stack) {}
    List<Offer> offers = new ArrayList<>();
    int size = Math.min(remoteRecovery.size(), UltsWithdrawalPlanner.MAX_OUTPUT_STACKS);
    for (int offset = 0; offset < size; offset++) {
      int index = Math.floorMod(recoveryCursor + offset, remoteRecovery.size());
      UltsStoredEntry entry = remoteRecovery.get(index);
      int count = (int) Math.min(entry.amount(), Math.max(1, entry.template().getMaxStackSize()));
      offers.add(new Offer(index, count, entry.template().copyWithCount(count)));
    }
    restore.accept(offers.stream().map(Offer::stack).toList());
    boolean delivered = false;
    offers.sort(Comparator.comparingInt(Offer::index).reversed());
    for (Offer offer : offers) {
      int index = offer.index();
      UltsStoredEntry entry = remoteRecovery.get(index);
      long moved = offer.count() - offer.stack().getCount();
      if (moved <= 0) {
        continue;
      }
      long remaining = entry.amount() - moved;
      if (remaining == 0) {
        remoteRecovery.remove(index);
      } else {
        remoteRecovery.set(index, new UltsStoredEntry(entry.template(), remaining, entry.updatedAt()));
      }
      delivered = true;
    }
    recoveryCursor = remoteRecovery.isEmpty() ? 0 : (recoveryCursor + size) % remoteRecovery.size();
    if (delivered) {
      setDirty();
    }
    return delivered;
  }

  public synchronized void deposit(ItemStack source) {
    if (source.isEmpty()) {
      return;
    }
    store(source, source.getCount());
    trimSpecial();
    changed();
  }

  /**
   * Puts several stacks in at once, bringing the special category inside its cap once for all of them.
   *
   * <p>Emptying a container goes through here rather than through {@link #deposit} for every slot: the cap
   * is a property of the whole pool, so a chest of twenty-seven stacks used to walk the whole storage
   * twenty-seven times in one tick to answer a question whose answer only changes once.
   *
   * @param sources the stacks to put in, in the order they were taken
   */
  public synchronized void depositAll(List<ItemStack> sources) {
    boolean any = false;
    for (ItemStack source : sources) {
      if (source.isEmpty()) {
        continue;
      }
      store(source, source.getCount());
      any = true;
    }
    if (!any) {
      return;
    }
    trimSpecial();
    changed();
  }

  /**
   * Puts one stack, or one box and everything it holds, into the pool.
   *
   * <p>A box a player named is theirs: it goes in as it is, contents and all, and is never taken apart
   * or used as packaging material. An unnamed box is opened, so what it holds joins the storage.
   *
   * <p>Once the stacks are in, the special category is brought back inside its cap, which destroys the
   * least recently stored of them. The cap is asked about by the callers of this method, once per batch
   * rather than once per stack.
   */
  private void store(ItemStack source, long outerCount) {
    if (source.isEmpty() || outerCount < 1L) {
      return;
    }
    if (UltsBoxes.isShulker(source) && !UltsBoxes.isNamed(source)) {
      ItemContainerContents contents = source.get(DataComponents.CONTAINER);
      ItemStack emptyBox = source.copyWithCount(1);
      emptyBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of()));
      keep(emptyBox, outerCount);
      if (contents != null) {
        contents.allItemsCopyStream().forEach(inner ->
            keep(inner, Math.multiplyExact((long) inner.getCount(), outerCount)));
      }
    } else {
      keep(source, outerCount);
    }
  }

  /** One stack, kept unless it would take a special row the filter does not want. */
  private void keep(ItemStack source, long amount) {
    if (source.isEmpty() || amount < 1L) {
      return;
    }
    ItemStack template = source.copyWithCount(1);
    // Void storage owns what it holds, so a stack the filter dismisses is destroyed here. A plain
    // stack of the same item is never dismissed, so this only ever sees special stacks.
    if (UltsSpecialFilters.dismisses(template)) {
      return;
    }
    add(pool, template, amount, now());
  }

  /**
   * Destroys every stored stack the special filter dismisses.
   *
   * <p>Applying the filter again has to clean out what was stored before it named the item, otherwise
   * a filter would only ever stop new arrivals and the storage would still be full of what it is
   * meant to be rid of.
   *
   * <p>Remote storage holds nothing of its own, and the containers a player bound are not the
   * storage's to empty, so there the filter only hides its items and this does nothing.
   *
   * @return whether anything was destroyed
   */
  public synchronized boolean purgeFiltered() {
    if (pool.isEmpty()
        || UltsConfigManager.getInstance().data().general().storageMode() != UltsStorageMode.VOID) {
      return false;
    }
    boolean removed = pool.removeIf(entry -> UltsSpecialFilters.dismisses(entry.template()));
    if (removed) {
      changed();
    }
    return removed;
  }

  /**
   * Destroys the least recently stored stacks while a bag holds more than its slots.
   *
   * <p>A bag belongs to one item, and so does its cap: every row of one item that carries data of its
   * own is in that item's bag, whatever it holds, and those rows together are what
   * {@code special.bundleSlots} bounds. A stack that pools with its kind is therefore counted by the
   * kind it pooled into, and can hold a whole pile in one slot.
   *
   * @return whether anything was destroyed
   */
  private boolean trimSpecial() {
    int limit = UltsConfigManager.getInstance().data().special().bundleSlots();
    Map<Item, List<UltsStoredEntry>> bags = new LinkedHashMap<>();
    for (UltsStoredEntry entry : pool) {
      if (special(entry.template())) {
        bags.computeIfAbsent(entry.template().getItem(), key -> new ArrayList<>()).add(entry);
      }
    }
    boolean trimmed = false;
    for (List<UltsStoredEntry> bag : bags.values()) {
      int excess = bag.size() - limit;
      if (excess <= 0) {
        continue;
      }
      // The least recently stored ones go first: a bag keeps what came in last.
      bag.sort(Comparator.comparingLong(UltsStoredEntry::updatedAt));
      for (int index = 0; index < excess; index++) {
        pool.remove(bag.get(index));
        trimmed = true;
      }
    }
    return trimmed;
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
    String key = playerId.toString();
    // A player who comes back moves to the end of the order, so what is let go of is the view nobody has
    // asked for in the longest while rather than the first one ever written.
    viewProfiles.remove(key);
    while (viewProfiles.size() >= MAX_VIEW_PROFILES) {
      Iterator<String> oldest = viewProfiles.keySet().iterator();
      if (!oldest.hasNext()) {
        break;
      }
      oldest.next();
      oldest.remove();
    }
    if (!profile.equals(viewProfiles.put(key, profile))) {
      setDirty();
    }
  }

  public synchronized long revision() {
    return revision;
  }

  /** Makes catalogue-dependent screens redraw without changing or saving the inventory. */
  public synchronized void invalidateViews() {
    revision++;
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

  /**
   * Writes a pile back as the stored items.
   *
   * <p>A special stack is somebody's own thing and keeps its own row, so the rows that were there are
   * reused rather than rebuilt from the totals: a withdrawal that took one named sword has to leave
   * the other named swords exactly as they were, timestamps included.
   */
  private void adoptPool(UltsCraftPool contents) {
    Map<String, List<UltsStoredEntry>> previous = new LinkedHashMap<>();
    for (UltsStoredEntry entry : pool) {
      previous.computeIfAbsent(kindKey(entry.template()), key -> new ArrayList<>()).add(entry);
    }
    List<UltsStoredEntry> rebuilt = new ArrayList<>();
    for (UltsStoredView view : contents.views()) {
      long amount = view.amount();
      List<UltsStoredEntry> old = previous.remove(kindKey(view.template()));
      if (old == null) {
        rebuilt.add(new UltsStoredEntry(view.template(), amount, now()));
        continue;
      }
      // Oldest first, so what a recipe took came out of the rows that had been there longest.
      for (UltsStoredEntry entry : old) {
        if (amount <= 0L) {
          break;
        }
        long kept = Math.min(amount, entry.amount());
        rebuilt.add(new UltsStoredEntry(entry.template(), kept, entry.updatedAt()));
        amount -= kept;
      }
      if (amount > 0L) {
        rebuilt.add(new UltsStoredEntry(view.template(), amount, now()));
      }
    }
    pool.clear();
    pool.addAll(rebuilt);
    trimSpecial();
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

  private static void add(List<UltsStoredEntry> pool, ItemStack template, long amount, long stamp) {
    if (template.isEmpty() || amount < 1) {
      return;
    }
    // Two stacks of the same kind of thing are one stack here, whether or not the game would let them
    // stack: the storage keeps a pile per kind, and which kinds those are is what the stacking rule
    // decides — every component the same, or the same tooltip. Two identically named swords are
    // therefore one stack of two, and the rows a bag holds are one per different kind.
    String key = kindKey(template);
    for (int index = 0; index < pool.size(); index++) {
      UltsStoredEntry entry = pool.get(index);
      if (kindKey(entry.template()).equals(key)) {
        long sum;
        try {
          sum = Math.addExact(entry.amount(), amount);
        } catch (ArithmeticException ignored) {
          sum = Long.MAX_VALUE;
        }
        pool.set(index, new UltsStoredEntry(entry.template(), sum, stamp));
        return;
      }
    }
    pool.add(new UltsStoredEntry(template, amount, stamp));
  }

  /**
   * A key that names the kind of thing a stack is, which is what one pool entry stands for.
   *
   * <p>Which kinds there are is what {@link UltsStackKinds} answers, and the same answer decides what a
   * catalogue row counts as its own stock and whether a category can show a stack at all.
   */
  public static String kindKey(ItemStack template) {
    return UltsStackKinds.of(template);
  }

  private static long now() {
    return System.currentTimeMillis();
  }

  private void changed() {
    revision++;
    setDirty();
  }
}
