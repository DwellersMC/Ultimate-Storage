package com.flwolfy.ults.data.state;

import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

public final class UltsState extends SavedData {

  private static final Codec<UltsState> CODEC = RecordCodecBuilder.create(instance ->
      instance.group(
          UltsTerminal.CODEC.listOf().fieldOf("terminals").forGetter(state -> state.terminals),
          UltsStoredEntry.CODEC.listOf().fieldOf("items").forGetter(state -> state.entries),
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

  private final List<UltsTerminal> terminals = new ArrayList<>();
  private final List<UltsStoredEntry> entries = new ArrayList<>();
  private final Map<String, UltsViewProfile> viewProfiles = new HashMap<>();
  private long revision;

  public UltsState() {}

  private UltsState(
      List<UltsTerminal> terminals,
      List<UltsStoredEntry> entries,
      Map<String, UltsViewProfile> viewProfiles
  ) {
    this.terminals.addAll(terminals);
    entries.stream().filter(entry -> !entry.template().isEmpty() && entry.amount() > 0)
        .forEach(entry -> this.entries.add(new UltsStoredEntry(entry.template(), entry.amount())));
    this.viewProfiles.putAll(viewProfiles);
  }

  public synchronized List<UltsTerminal> terminals() {
    return List.copyOf(terminals);
  }

  public synchronized UltsTerminal terminal(String name) {
    String key = key(name);
    return terminals.stream().filter(value -> key(value.name()).equals(key)).findFirst().orElse(null);
  }

  public synchronized boolean addTerminal(UltsTerminal terminal) {
    if (terminal(terminal.name()) != null || terminals.stream().anyMatch(existing ->
        existing.dimension().equals(terminal.dimension())
            && existing.basePos().equals(terminal.basePos()))) {
      return false;
    }
    terminals.add(terminal);
    changed();
    return true;
  }

  public synchronized UltsTerminal removeTerminal(String name) {
    UltsTerminal terminal = terminal(name);
    if (terminal != null) {
      terminals.remove(terminal);
      changed();
    }
    return terminal;
  }

  public synchronized boolean protects(String dimension, net.minecraft.core.BlockPos position) {
    return terminals.stream().anyMatch(terminal -> terminal.dimension().equals(dimension)
        && (terminal.basePos().equals(position) || terminal.barrelPos().equals(position)));
  }

  public synchronized void deposit(ItemStack source) {
    if (source.isEmpty()) {
      return;
    }
    int outerCount = source.getCount();
    if (source.getItem() instanceof BlockItem blockItem
        && blockItem.getBlock() instanceof ShulkerBoxBlock) {
      ItemContainerContents contents = source.get(DataComponents.CONTAINER);
      ItemStack emptyBox = source.copyWithCount(1);
      emptyBox.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of()));
      add(emptyBox, outerCount);
      if (contents != null) {
        contents.allItemsCopyStream().forEach(inner ->
            add(inner, Math.multiplyExact((long) inner.getCount(), outerCount)));
      }
    } else {
      add(source, outerCount);
    }
    changed();
  }

  public synchronized ItemStack withdraw(ItemStack template, int requested) {
    if (template.isEmpty() || requested < 1) {
      return ItemStack.EMPTY;
    }
    for (int index = 0; index < entries.size(); index++) {
      UltsStoredEntry entry = entries.get(index);
      if (!ItemStack.isSameItemSameComponents(entry.template(), template)) {
        continue;
      }
      if (entry.amount() < requested) {
        return ItemStack.EMPTY;
      }
      long remaining = entry.amount() - requested;
      if (remaining == 0) {
        entries.remove(index);
      } else {
        entries.set(index, new UltsStoredEntry(entry.template(), remaining));
      }
      changed();
      return entry.template().copyWithCount(requested);
    }
    return ItemStack.EMPTY;
  }

  public synchronized UltsWithdrawalPlan withdrawalPlan(
      ItemStack template,
      int quantity,
      boolean boxed
  ) {
    long itemAvailable = amount(template);
    long boxAvailable = emptyBoxAmount();
    if (template.isEmpty() || quantity < 1) {
      return plan(false, "invalid", itemAvailable, 0, boxAvailable, 0, List.of());
    }
    if (boxed && isShulker(template)) {
      return plan(false, "nested_box", itemAvailable, 0, boxAvailable, quantity, List.of());
    }
    int outputCount = boxed ? quantity : (int) (
        ((long) quantity + template.getMaxStackSize() - 1) / template.getMaxStackSize());
    if (outputCount > 36) {
      return plan(false, "too_large", itemAvailable, quantity, boxAvailable,
          boxed ? quantity : 0, List.of());
    }
    long required = boxed
        ? (long) quantity * 27L * template.getMaxStackSize() : quantity;
    int requiredBoxes = boxed ? quantity : 0;
    if (itemAvailable < required) {
      return plan(false, "items", itemAvailable, required, boxAvailable, requiredBoxes, List.of());
    }
    if (boxAvailable < requiredBoxes) {
      return plan(false, "boxes", itemAvailable, required, boxAvailable, requiredBoxes, List.of());
    }
    List<ItemStack> outputs = boxed
        ? packedBoxes(template, requiredBoxes) : looseStacks(template, quantity);
    return plan(true, "", itemAvailable, required, boxAvailable, requiredBoxes, outputs);
  }

  public synchronized List<ItemStack> takePlanned(
      ItemStack template,
      int quantity,
      boolean boxed
  ) {
    UltsWithdrawalPlan plan = withdrawalPlan(template, quantity, boxed);
    if (!plan.available()) {
      return List.of();
    }
    consume(template, plan.itemRequired());
    if (plan.boxRequired() > 0) {
      consumeEmptyBoxes(plan.boxRequired());
    }
    changed();
    return plan.outputs().stream().map(ItemStack::copy).toList();
  }

  public synchronized List<UltsStoredView> items() {
    return entries.stream()
        .map(entry -> new UltsStoredView(
            entry.template(), entry.amount(), !UltsCreativeCatalog.contains(entry.template())))
        .sorted(Comparator.comparing(
            value -> value.template().getHoverName().getString(), String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  public synchronized long revision() {
    return revision;
  }

  public synchronized UltsViewProfile viewProfile(UUID playerId) {
    return viewProfiles.getOrDefault(playerId.toString(), UltsViewProfile.DEFAULT);
  }

  public synchronized void setViewProfile(UUID playerId, UltsViewProfile profile) {
    if (!profile.equals(viewProfiles.put(playerId.toString(), profile))) {
      setDirty();
    }
  }

  private long amount(ItemStack template) {
    return entries.stream()
        .filter(entry -> ItemStack.isSameItemSameComponents(entry.template(), template))
        .mapToLong(UltsStoredEntry::amount)
        .sum();
  }

  private long emptyBoxAmount() {
    return entries.stream().filter(entry -> isEmptyShulker(entry.template()))
        .mapToLong(UltsStoredEntry::amount).sum();
  }

  private List<ItemStack> looseStacks(ItemStack template, int quantity) {
    List<ItemStack> result = new ArrayList<>();
    int remaining = quantity;
    while (remaining > 0) {
      int count = Math.min(remaining, template.getMaxStackSize());
      result.add(template.copyWithCount(count));
      remaining -= count;
    }
    return List.copyOf(result);
  }

  private List<ItemStack> packedBoxes(ItemStack template, int quantity) {
    List<ItemStack> result = new ArrayList<>();
    for (UltsStoredEntry entry : entries) {
      if (!isEmptyShulker(entry.template())) {
        continue;
      }
      long count = Math.min(entry.amount(), quantity - result.size());
      for (long index = 0; index < count; index++) {
        ItemStack box = entry.template().copyWithCount(1);
        List<ItemStack> contents = new ArrayList<>(27);
        for (int slot = 0; slot < 27; slot++) {
          contents.add(template.copyWithCount(template.getMaxStackSize()));
        }
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        result.add(box);
      }
      if (result.size() == quantity) {
        break;
      }
    }
    return List.copyOf(result);
  }

  private void consume(ItemStack template, long requested) {
    for (int index = 0; index < entries.size() && requested > 0;) {
      UltsStoredEntry entry = entries.get(index);
      if (!ItemStack.isSameItemSameComponents(entry.template(), template)) {
        index++;
        continue;
      }
      long taken = Math.min(requested, entry.amount());
      requested -= taken;
      if (taken == entry.amount()) {
        entries.remove(index);
      } else {
        entries.set(index, new UltsStoredEntry(entry.template(), entry.amount() - taken));
        index++;
      }
    }
  }

  private void consumeEmptyBoxes(long requested) {
    for (int index = 0; index < entries.size() && requested > 0;) {
      UltsStoredEntry entry = entries.get(index);
      if (!isEmptyShulker(entry.template())) {
        index++;
        continue;
      }
      long taken = Math.min(requested, entry.amount());
      requested -= taken;
      if (taken == entry.amount()) {
        entries.remove(index);
      } else {
        entries.set(index, new UltsStoredEntry(entry.template(), entry.amount() - taken));
        index++;
      }
    }
  }

  private static boolean isShulker(ItemStack stack) {
    return stack.getItem() instanceof BlockItem blockItem
        && blockItem.getBlock() instanceof ShulkerBoxBlock;
  }

  private static boolean isEmptyShulker(ItemStack stack) {
    if (!isShulker(stack)) {
      return false;
    }
    ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
    if (contents == null) {
      return true;
    }
    for (var ignored : contents.nonEmptyItems()) {
      return false;
    }
    return true;
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

  private void add(ItemStack source, long amount) {
    if (source.isEmpty() || amount < 1) {
      return;
    }
    ItemStack template = source.copyWithCount(1);
    for (int index = 0; index < entries.size(); index++) {
      UltsStoredEntry entry = entries.get(index);
      if (ItemStack.isSameItemSameComponents(entry.template(), template)) {
        long sum;
        try {
          sum = Math.addExact(entry.amount(), amount);
        } catch (ArithmeticException ignored) {
          sum = Long.MAX_VALUE;
        }
        entries.set(index, new UltsStoredEntry(template, sum));
        return;
      }
    }
    entries.add(new UltsStoredEntry(template, amount));
  }

  private void changed() {
    revision++;
    setDirty();
  }

  private static String key(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }
}
