package com.flwolfy.ults.data.state;

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

    public long boxAmount() {
      long total = 0L;
      for (Boxes entry : boxes) {
        total += entry.count();
      }
      return total;
    }

    public ItemStack preferredBox() {
      for (Boxes entry : boxes) {
        if (UltsBoxes.isPlain(entry.template())) {
          return entry.template().copyWithCount(1);
        }
      }
      return boxes.isEmpty() ? ItemStack.EMPTY : boxes.getFirst().template().copyWithCount(1);
    }

    /** Boxes for a packed withdrawal, built from the boxes that are actually present. */
    public List<ItemStack> packedBoxes(ItemStack template, int quantity) {
      List<ItemStack> result = new ArrayList<>();
      for (Boxes entry : boxes) {
        for (long index = 0; index < entry.count() && result.size() < quantity; index++) {
          result.add(UltsWithdrawalOutput.packedBox(entry.template(), template));
        }
        if (result.size() == quantity) {
          break;
        }
      }
      return List.copyOf(result);
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

  public static UltsWithdrawalPlan plan(
      Snapshot snapshot,
      ItemStack template,
      int quantity,
      boolean boxed
  ) {
    long itemAvailable = snapshot.amount(template);
    long boxAvailable = snapshot.boxAmount();
    if (template.isEmpty() || quantity < 1) {
      return plan(false, "invalid", itemAvailable, 0, boxAvailable, 0, List.of());
    }
    if (boxed && UltsBoxes.isShulker(template)) {
      return plan(false, "nested_box", itemAvailable, 0, boxAvailable, quantity, List.of());
    }
    int outputCount = boxed ? quantity : (int) (
        ((long) quantity + template.getMaxStackSize() - 1) / template.getMaxStackSize());
    if (outputCount > 36) {
      return plan(false, "too_large", itemAvailable, quantity, boxAvailable,
          boxed ? quantity : 0, List.of());
    }
    long required = boxed
        ? (long) quantity * SHULKER_SLOTS * template.getMaxStackSize() : quantity;
    int requiredBoxes = boxed ? quantity : 0;
    if (itemAvailable < required) {
      return plan(false, "items", itemAvailable, required, boxAvailable, requiredBoxes, List.of());
    }
    if (boxAvailable < requiredBoxes) {
      return plan(false, "boxes", itemAvailable, required, boxAvailable, requiredBoxes, List.of());
    }
    List<ItemStack> outputs = boxed
        ? snapshot.packedBoxes(template, requiredBoxes)
        : UltsWithdrawalOutput.looseStacks(template, quantity);
    if (outputs.size() < (boxed ? requiredBoxes : outputCount)) {
      return plan(false, "boxes", itemAvailable, required, boxAvailable, requiredBoxes, List.of());
    }
    return plan(true, "", itemAvailable, required, boxAvailable, requiredBoxes, outputs);
  }

  public static List<ItemStack> take(
      MinecraftServer server,
      List<UltsBinding> bindings,
      ItemStack template,
      int quantity,
      boolean boxed
  ) {
    Snapshot live = snapshot(server, bindings);
    UltsWithdrawalPlan plan = plan(live, template, quantity, boxed);
    if (!plan.available()) {
      return List.of();
    }
    List<ItemStack> removed = new ArrayList<>();
    if (!extract(
        server, bindings,
        stack -> ItemStack.isSameItemSameComponents(stack, template),
        plan.itemRequired(), removed)) {
      restore(server, bindings, removed);
      return List.of();
    }
    if (plan.boxRequired() > 0) {
      List<ItemStack> boxes = new ArrayList<>();
      if (!extract(server, bindings, UltsBoxes::isPackable, plan.boxRequired(), boxes)) {
        restore(server, bindings, removed);
        restore(server, bindings, boxes);
        return List.of();
      }
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

  private static UltsWithdrawalPlan plan(
      boolean available,
      String problem,
      long itemAvailable,
      long itemRequired,
      long boxAvailable,
      int boxRequired,
      List<ItemStack> outputs
  ) {
    return new UltsWithdrawalPlan(
        available, problem, itemAvailable, itemRequired, boxAvailable, boxRequired, outputs);
  }
}
