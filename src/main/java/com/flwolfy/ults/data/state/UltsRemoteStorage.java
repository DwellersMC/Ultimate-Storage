package com.flwolfy.ults.data.state;

import com.flwolfy.ults.UltsMod;

import com.flwolfy.ults.crafting.UltsCraftPool;
import com.flwolfy.ults.crafting.UltsCraftMath;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.flwolfy.ults.input.UltsContainers;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Remote storage mode: the containers inside the bound areas are the storage.
 *
 * <p>Contents are aggregated from the loaded chunks of every bound area, so a warehouse can grow by
 * simply placing more containers and an unloaded area simply contributes nothing until it is loaded.
 * Because containers change without this mod, the aggregate is cached as a {@link Snapshot} and only
 * refreshed on an interval. Withdrawals always read live contents and remove through the vanilla
 * {@link Container} API, rolling back instead of leaving a half applied change behind.
 */
public final class UltsRemoteStorage {

  private static final int SHULKER_SLOTS = 27;

  private UltsRemoteStorage() {}

  /** Loaded containers and the world's item-spawn boundary, shared by reads, withdrawals and recovery. */
  interface Access {
    void forEach(Consumer<Container> visitor);
    boolean drop(ItemStack stack);
  }

  private static Access access(MinecraftServer server, List<UltsBinding> bindings) {
    return new Access() {
      @Override public void forEach(Consumer<Container> visitor) {
        forEachContainer(server, bindings, visitor);
      }

      @Override public boolean drop(ItemStack stack) {
        for (UltsBinding binding : bindings) {
          ServerLevel level = level(server, binding.dimension());
          if (level == null || !level.getChunkSource().hasChunk(
              binding.pos().getX() >> 4, binding.pos().getZ() >> 4)) {
            continue;
          }
          var entity = new net.minecraft.world.entity.item.ItemEntity(
              level, binding.pos().getX() + 0.5, binding.pos().getY() + 0.5,
              binding.pos().getZ() + 0.5, stack.copy());
          entity.setDefaultPickUpDelay();
          if (level.addFreshEntity(entity) && level.getEntity(entity.getId()) != null) {
            return true;
          }
          entity.discard();
        }
        return false;
      }
    };
  }

  /** One kind of empty shulker box that packing may consume, in preference order. */
  public record Boxes(ItemStack template, long count) {}

  /** Immutable view of the aggregated remote contents. */
  public record Snapshot(List<UltsStoredView> items, List<Boxes> boxes) {

    public static final Snapshot EMPTY = new Snapshot(List.of(), List.of());

    public long amount(ItemStack template) {
      for (UltsStoredView view : items) {
        if (UltsStackKinds.same(view.template(), template)) {
          return view.amount();
        }
      }
      return 0L;
    }

    public ItemStack preferredBox() {
      for (Boxes entry : boxes) {
        if (UltsBoxes.isPlain(entry.template())) {
          return entry.template().copyWithCount(1);
        }
      }
      return boxes.isEmpty() ? ItemStack.EMPTY : boxes.getFirst().template().copyWithCount(1);
    }
  }

  public static Snapshot snapshot(MinecraftServer server, List<UltsBinding> bindings) {
    return snapshot(access(server, bindings));
  }

  /** Stable physical inventory identity, independent of a chest joining or splitting. */
  public record Part(String dimension, BlockPos position) {}

  /** A slice retains inventory provenance so merging overlapping bindings cannot duplicate items. */
  public record ShardSnapshot(long generation, Map<Part, Snapshot> parts) {
    public ShardSnapshot { parts = Map.copyOf(parts); }
  }

  public static ShardSnapshot snapshotShard(
      MinecraftServer server, List<UltsBinding> bindings, long generation
  ) {
    Map<Part, Snapshot> parts = new HashMap<>();
    for (UltsBinding binding : bindings) {
      ServerLevel level = level(server, binding.dimension());
      if (level == null || !level.hasChunk(binding.x() >> 4, binding.z() >> 4)) {
        continue;
      }
      for (UltsContainers.InventoryPart inventory : UltsContainers.inventories(level, binding.pos())) {
        BlockPos position = inventory.position();
        Part part = new Part(binding.dimension(), position.immutable());
        if (parts.containsKey(part)) continue;
        Container container = inventory.inventory();
        // Read the block entity's own slots, never the joined double-chest wrapper.
        parts.put(part, snapshot(new Access() {
          @Override public void forEach(Consumer<Container> visitor) { visitor.accept(container); }
          @Override public boolean drop(ItemStack stack) { return false; }
        }));
      }
    }
    return new ShardSnapshot(generation, parts);
  }

  public static Snapshot mergeShards(List<ShardSnapshot> shards) {
    Map<Part, ShardSnapshot> owners = new HashMap<>();
    for (ShardSnapshot shard : shards) {
      for (Part part : shard.parts().keySet()) {
        owners.merge(part, shard, (first, second) ->
            first.generation() >= second.generation() ? first : second);
      }
    }
    List<Snapshot> unique = new ArrayList<>(owners.size());
    owners.forEach((part, shard) -> unique.add(shard.parts().get(part)));
    return merge(unique);
  }

  static Snapshot snapshot(Access access) {
    Map<Item, List<UltsStoredView>> grouped = new HashMap<>();
    Map<Item, List<Boxes>> boxes = new HashMap<>();
    access.forEach(container -> {
      for (int slot = 0; slot < container.getContainerSize(); slot++) {
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty()) {
          continue;
        }
        // Remote storage has no pool to destroy anything in, and a bound container is not the
        // storage's to empty, so the filter only hides: a dismissed stack stays where it is and never
        // reaches a listing, a box count or a plan.
        if (UltsSpecialFilters.dismisses(stack)) {
          continue;
        }
        addItem(grouped, stack);
        if (UltsBoxes.isPackable(stack)) {
          addBox(boxes, stack);
        }
      }
    });
    List<UltsStoredView> views = new ArrayList<>();
    grouped.values().forEach(views::addAll);
    views.sort(Comparator.comparing(
        value -> value.template().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER));
    List<Boxes> boxList = new ArrayList<>();
    boxes.values().forEach(boxList::addAll);
    // The plain colour is used first, then whatever other colours are left.
    boxList.sort(Comparator.comparingInt(entry -> UltsBoxes.isPlain(entry.template()) ? 0 : 1));
    return new Snapshot(List.copyOf(views), List.copyOf(boxList));
  }

  public static ItemStack availableBox(Snapshot snapshot) {
    return snapshot.preferredBox();
  }

  /**
   * Combines the aggregates of several slices of the same storage.
   *
   * <p>The runtime refreshes one slice at a time so a warehouse sized storage never has to be walked
   * completely in a single tick; this puts the slices back together for display.
   */
  public static Snapshot merge(List<Snapshot> slices) {
    if (slices.size() == 1) {
      return slices.getFirst();
    }
    Map<Item, List<UltsStoredView>> grouped = new HashMap<>();
    Map<Item, List<Boxes>> boxes = new HashMap<>();
    for (Snapshot slice : slices) {
      for (UltsStoredView view : slice.items()) {
        add(grouped, view);
      }
      for (Boxes entry : slice.boxes()) {
        addBox(boxes, entry.template(), entry.count());
      }
    }
    List<UltsStoredView> views = new ArrayList<>();
    grouped.values().forEach(views::addAll);
    views.sort(Comparator.comparing(
        value -> value.template().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER));
    List<Boxes> boxList = new ArrayList<>();
    boxes.values().forEach(boxList::addAll);
    boxList.sort(Comparator.comparingInt(entry -> UltsBoxes.isPlain(entry.template()) ? 0 : 1));
    return new Snapshot(List.copyOf(views), List.copyOf(boxList));
  }

  private static void add(Map<Item, List<UltsStoredView>> grouped, UltsStoredView view) {
    List<UltsStoredView> variants = grouped.computeIfAbsent(
        view.template().getItem(), key -> new ArrayList<>());
    for (int index = 0; index < variants.size(); index++) {
      UltsStoredView existing = variants.get(index);
      if (UltsStackKinds.same(existing.template(), view.template())) {
        variants.set(index, new UltsStoredView(
            existing.template(), UltsCraftMath.add(existing.amount(), view.amount()), existing.special()));
        return;
      }
    }
    variants.add(view);
  }

  /**
   * Whether one withdrawal would run, decided on the aggregated contents.
   *
   * @param snapshot aggregated contents
   * @param template what is withdrawn
   * @param quantity how much is withdrawn
   * @param boxed whether the amount counts full boxes
   * @param mode configured crafting mode
   * @return the plan
   */
  public static UltsWithdrawalPlan plan(
      Snapshot snapshot,
      ItemStack template,
      int quantity,
      boolean boxed,
      UltsCraftingMode mode
  ) {
    return UltsWithdrawalPlanner.plan(
        UltsCraftPool.of(snapshot.items()), template, quantity, boxed, mode);
  }

  /**
   * Runs one withdrawal against the containers themselves.
   *
   * <p>The plan is worked out on the live contents, then run on a copy of them, so what has to be
   * taken out of the containers is exactly the difference between the two: everything a run made and
   * the request did not need is put back instead of disappearing.
   */
  public static List<ItemStack> take(
      MinecraftServer server,
      List<UltsBinding> bindings,
      UltsState recovery,
      ItemStack template,
      int quantity,
      boolean boxed,
      UltsCraftingMode mode
  ) {
    return take(access(server, bindings), recovery, template, quantity, boxed, mode);
  }

  static List<ItemStack> take(
      Access access, UltsState recovery, ItemStack template,
      int quantity, boolean boxed, UltsCraftingMode mode
  ) {
    return take(access, recovery, template, quantity, boxed, mode, Long.MAX_VALUE).outputs();
  }

  public static UltsWithdrawalResult take(
      MinecraftServer server, List<UltsBinding> bindings, UltsState recovery,
      ItemStack template, int quantity, boolean boxed, UltsCraftingMode mode, long deadline) {
    return take(access(server, bindings), recovery, template, quantity, boxed, mode, deadline);
  }

  static UltsWithdrawalResult take(
      Access access, UltsState recovery, ItemStack template,
      int quantity, boolean boxed, UltsCraftingMode mode, long deadline
  ) {
    Snapshot live = snapshot(access);
    UltsCraftPool before = UltsCraftPool.of(live.items());
    UltsWithdrawalPlan plan = UltsWithdrawalPlanner.plan(
        before.copy(), template, quantity, boxed, mode, deadline);
    if (!plan.available()) {
      return "pending".equals(plan.problem()) ? UltsWithdrawalResult.WAIT : UltsWithdrawalResult.EMPTY;
    }
    UltsCraftPool after = before.copy();
    if (!UltsWithdrawalPlanner.run(after, plan, template, quantity, boxed)) {
      return UltsWithdrawalResult.EMPTY;
    }
    List<ItemStack> removed = new ArrayList<>();
    for (int index = 0; index < before.size(); index++) {
      ItemStack kind = before.templateAt(index);
      long used = before.amountAt(index) - after.amount(kind);
      if (used > 0L && !extract(
          access,
          stack -> UltsStackKinds.same(stack, kind), used, removed)) {
        restoreOrKeep(access, recovery, removed);
        return UltsWithdrawalResult.EMPTY;
      }
    }
    // What a run made on the way stays in the storage: it was never asked for.
    List<ItemStack> leftovers = new ArrayList<>();
    for (int index = 0; index < after.size(); index++) {
      ItemStack kind = after.templateAt(index);
      long extra = after.amountAt(index) - before.amount(kind);
      if (extra > 0L) {
        leftovers.addAll(UltsWithdrawalOutput.stacks(kind, extra));
      }
    }
    restoreOrKeep(access, recovery, leftovers);
    return new UltsWithdrawalResult(plan.outputs().stream().map(ItemStack::copy).toList(), false);
  }

  private static void restoreOrKeep(Access access, UltsState recovery, List<ItemStack> stacks) {
    if (!restore(access, stacks)) {
      recovery.keepRemoteRecovery(stacks);
      UltsMod.LOGGER.warn("UltStorage retained undelivered remote items in the saved recovery queue");
    }
  }

  /**
   * Visits the container of every binding; a binding only counts while its chunk is loaded.
   *
   * <p>Large containers are one container, so a storage that (from before this rule) holds both
   * halves of the same double chest still counts its slots exactly once.
   */
  public static void forEachContainer(
      MinecraftServer server,
      List<UltsBinding> bindings,
      Consumer<Container> visitor
  ) {
    Set<Part> seen = new HashSet<>();
    for (UltsBinding binding : bindings) {
      ServerLevel level = level(server, binding.dimension());
      if (level == null) {
        continue;
      }
      for (UltsContainers.InventoryPart part : UltsContainers.inventories(level, binding.pos())) {
        if (seen.add(new Part(binding.dimension(), part.position()))) visitor.accept(part.inventory());
      }
    }
  }

  /** The container bound at the position, or {@code null} when it is unloaded or gone. */
  public static Container containerAt(ServerLevel level, net.minecraft.core.BlockPos position) {
    return UltsContainers.at(level, position);
  }

  private static boolean extract(
      Access access,
      Predicate<ItemStack> match,
      long requested,
      List<ItemStack> removed
  ) {
    if (requested <= 0) {
      return true;
    }
    long[] remaining = {requested};
    access.forEach(container -> {
      if (remaining[0] <= 0) {
        return;
      }
      for (int slot = 0; slot < container.getContainerSize() && remaining[0] > 0; slot++) {
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty() || !match.test(stack)) {
          continue;
        }
        int take = (int) Math.min(remaining[0], stack.getCount());
        ItemStack taken = container.removeItem(slot, take);
        if (taken.isEmpty()) {
          continue;
        }
        removed.add(taken);
        remaining[0] -= taken.getCount();
      }
    });
    return remaining[0] <= 0;
  }

  /**
   * Puts stacks back into the containers the storage is made of, and drops whatever will not fit.
   *
   * <p>The drop goes to a container that is really there, rather than to whichever binding happens to be
   * listed first: a storage spread over two dimensions would otherwise drop its leftovers in the wrong
   * one, or nowhere at all.
   *
   * @param server the server
   * @param bindings the containers the storage is made of
   * @param stacks the stacks to put back
   * @return whether every stack found a home
   */
  public static boolean restore(
      MinecraftServer server,
      List<UltsBinding> bindings,
      List<ItemStack> stacks
  ) {
    return restore(access(server, bindings), stacks);
  }

  static boolean restore(Access access, List<ItemStack> stacks) {
    boolean placed = true;
    for (ItemStack stack : stacks) {
      ItemStack pending = stack.copy();
      access.forEach(container -> {
        if (!pending.isEmpty()) {
          insert(container, pending);
        }
      });
      if (!pending.isEmpty() && access.drop(pending.copy())) {
        pending.setCount(0);
      }
      // What could not be put anywhere is written back into the stack the caller handed in, so the caller
      // can deal with exactly that remainder: the part that did land is already in a container, and
      // depositing the whole stack would put it in two places at once.
      stack.setCount(pending.getCount());
      placed &= pending.isEmpty();
    }
    return placed;
  }

  private static void insert(Container container, ItemStack pending) {
    for (int slot = 0; slot < container.getContainerSize() && !pending.isEmpty(); slot++) {
      ItemStack existing = container.getItem(slot);
      if (!container.canPlaceItem(slot, pending)) {
        continue;
      }
      // Putting something back into a container is the game's own business, not the storage's: two
      // stacks only share a slot when every component agrees, whatever the stacking rule says about
      // what the storage treats as one kind of thing.
      if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, pending)) {
        continue;
      }
      int before = existing.getCount();
      int room = container.getMaxStackSize(pending) - before;
      if (room <= 0) {
        continue;
      }
      int moved = Math.min(room, pending.getCount());
      container.setItem(slot, pending.copyWithCount(before + moved));
      ItemStack stored = container.getItem(slot);
      int accepted = ItemStack.isSameItemSameComponents(stored, pending)
          ? Math.max(0, Math.min(moved, stored.getCount() - before)) : 0;
      pending.shrink(accepted);
      container.setChanged();
    }
  }

  private static void addItem(Map<Item, List<UltsStoredView>> grouped, ItemStack stack) {
    List<UltsStoredView> variants = grouped.computeIfAbsent(
        stack.getItem(), key -> new ArrayList<>());
    for (int index = 0; index < variants.size(); index++) {
      UltsStoredView view = variants.get(index);
      // The configured stacking rule, the same one the void pool and the merge use: counted one way here
      // and another way there, a listing could offer an amount that taking it cannot serve.
      if (UltsStackKinds.same(view.template(), stack)) {
        variants.set(index, new UltsStoredView(
            view.template(), UltsCraftMath.add(view.amount(), stack.getCount()), view.special()));
        return;
      }
    }
    ItemStack template = stack.copyWithCount(1);
    variants.add(new UltsStoredView(
        template, stack.getCount(), !UltsCreativeCatalog.contains(template)));
  }

  private static void addBox(Map<Item, List<Boxes>> boxes, ItemStack template, long count) {
    List<Boxes> variants = boxes.computeIfAbsent(template.getItem(), key -> new ArrayList<>());
    for (int index = 0; index < variants.size(); index++) {
      Boxes entry = variants.get(index);
      if (UltsStackKinds.same(entry.template(), template)) {
        variants.set(index, new Boxes(entry.template(), UltsCraftMath.add(entry.count(), count)));
        return;
      }
    }
    variants.add(new Boxes(template, count));
  }

  private static void addBox(Map<Item, List<Boxes>> boxes, ItemStack stack) {
    addBox(boxes, stack.copyWithCount(1), stack.getCount());
  }

  private static ServerLevel level(MinecraftServer server, String dimension) {
    Identifier id = Identifier.tryParse(dimension);
    return id == null ? null
        : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
  }
}
