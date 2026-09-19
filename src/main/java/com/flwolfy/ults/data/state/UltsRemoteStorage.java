package com.flwolfy.ults.data.state;

import com.flwolfy.ults.crafting.UltsCraftPool;
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
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

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

  /** One kind of empty shulker box that packing may consume, in preference order. */
  public record Boxes(ItemStack template, long count) {}

  /** Immutable view of the aggregated remote contents. */
  public record Snapshot(List<UltsStoredView> items, List<Boxes> boxes) {

    public static final Snapshot EMPTY = new Snapshot(List.of(), List.of());

    public long amount(ItemStack template) {
      for (UltsStoredView view : items) {
        if (ItemStack.isSameItemSameComponents(view.template(), template)) {
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
    Map<Item, List<UltsStoredView>> grouped = new HashMap<>();
    Map<Item, List<Boxes>> boxes = new HashMap<>();
    forEachContainer(server, bindings, container -> {
      for (int slot = 0; slot < container.getContainerSize(); slot++) {
        ItemStack stack = container.getItem(slot);
        if (stack.isEmpty()) {
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
      if (ItemStack.isSameItemSameComponents(existing.template(), view.template())) {
        variants.set(index, new UltsStoredView(
            existing.template(), existing.amount() + view.amount(), existing.special()));
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
      ItemStack template,
      int quantity,
      boolean boxed,
      UltsCraftingMode mode
  ) {
    Snapshot live = snapshot(server, bindings);
    UltsCraftPool before = UltsCraftPool.of(live.items());
    UltsWithdrawalPlan plan = UltsWithdrawalPlanner.plan(
        before.copy(), template, quantity, boxed, mode);
    if (!plan.available()) {
      return List.of();
    }
    UltsCraftPool after = before.copy();
    if (!UltsWithdrawalPlanner.run(after, plan, template, quantity, boxed)) {
      return List.of();
    }
    List<ItemStack> removed = new ArrayList<>();
    for (int index = 0; index < before.size(); index++) {
      ItemStack kind = before.templateAt(index);
      long used = before.amountAt(index) - after.amount(kind);
      if (used > 0L && !extract(
          server, bindings,
          stack -> ItemStack.isSameItemSameComponents(stack, kind), used, removed)) {
        restore(server, bindings, removed);
        return List.of();
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
    if (!leftovers.isEmpty() && !bindings.isEmpty()) {
      restore(server, bindings, leftovers);
    }
    return plan.outputs().stream().map(ItemStack::copy).toList();
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
    Set<String> seen = new HashSet<>();
    for (UltsBinding binding : bindings) {
      ServerLevel level = level(server, binding.dimension());
      if (level == null) {
        continue;
      }
      Container container = containerAt(level, binding.pos());
      if (container == null
          || !seen.add(binding.dimension() + "@"
              + UltsContainers.identity(level, binding.pos()).asLong())) {
        continue;
      }
      visitor.accept(container);
    }
  }

  /** The container bound at the position, or {@code null} when it is unloaded or gone. */
  public static Container containerAt(ServerLevel level, net.minecraft.core.BlockPos position) {
    return UltsContainers.at(level, position);
  }

  private static boolean extract(
      MinecraftServer server,
      List<UltsBinding> bindings,
      Predicate<ItemStack> match,
      long requested,
      List<ItemStack> removed
  ) {
    if (requested <= 0) {
      return true;
    }
    long[] remaining = {requested};
    forEachContainer(server, bindings, container -> {
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

  private static void restore(
      MinecraftServer server,
      List<UltsBinding> bindings,
      List<ItemStack> stacks
  ) {
    for (ItemStack stack : stacks) {
      ItemStack pending = stack.copy();
      forEachContainer(server, bindings, container -> {
        if (!pending.isEmpty()) {
          insert(container, pending);
        }
      });
      if (!pending.isEmpty()) {
        ServerLevel level = level(server, bindings.getFirst().dimension());
        if (level != null) {
          net.minecraft.world.level.block.Block.popResource(
              level, bindings.getFirst().pos(), pending);
        }
      }
    }
  }

  private static void insert(Container container, ItemStack pending) {
    for (int slot = 0; slot < container.getContainerSize() && !pending.isEmpty(); slot++) {
      ItemStack existing = container.getItem(slot);
      if (existing.isEmpty()) {
        container.setItem(slot, pending.copy());
        pending.setCount(0);
        container.setChanged();
        continue;
      }
      if (!ItemStack.isSameItemSameComponents(existing, pending)) {
        continue;
      }
      int room = existing.getMaxStackSize() - existing.getCount();
      if (room <= 0) {
        continue;
      }
      int moved = Math.min(room, pending.getCount());
      container.setItem(slot, existing.copyWithCount(existing.getCount() + moved));
      pending.shrink(moved);
      container.setChanged();
    }
  }

  private static void addItem(Map<Item, List<UltsStoredView>> grouped, ItemStack stack) {
    List<UltsStoredView> variants = grouped.computeIfAbsent(
        stack.getItem(), key -> new ArrayList<>());
    for (int index = 0; index < variants.size(); index++) {
      UltsStoredView view = variants.get(index);
      if (ItemStack.isSameItemSameComponents(view.template(), stack)) {
        variants.set(index, new UltsStoredView(
            view.template(), view.amount() + stack.getCount(), view.special()));
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
      if (ItemStack.isSameItemSameComponents(entry.template(), template)) {
        variants.set(index, new Boxes(entry.template(), entry.count() + count));
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
