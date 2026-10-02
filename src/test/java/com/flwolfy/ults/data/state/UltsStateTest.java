package com.flwolfy.ults.data.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.crafting.UltsTestBootstrap;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsItemVisibility;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

class UltsStateTest {

  @Test
  void bindingsAreOneOrderedListNumberedFromOne() {
    UltsState state = new UltsState();
    assertTrue(state.addBinding(binding("主仓库", 1, 2, 3)));
    assertTrue(state.addBinding(binding("", 4, 5, 6)));
    assertTrue(state.addBinding(binding("侧厅", 7, 8, 9)));
    assertEquals(3, state.bindingCount());
    assertEquals(List.of("主仓库", "", "侧厅"),
        state.bindings().stream().map(UltsBinding::note).toList());

    // The same container cannot be bound twice.
    assertFalse(state.addBinding(binding("别的备注", 1, 2, 3)));

    List<UltsBinding> removed = state.removeBindings(1, 1);
    assertEquals(1, removed.size());
    assertEquals(new BlockPos(1, 2, 3), removed.getFirst().pos());
    // The remaining bindings are re-numbered to #1 and #2.
    assertEquals(new BlockPos(4, 5, 6), state.bindings().getFirst().pos());
    assertEquals(2, state.bindingCount());
  }

  @Test
  void thePoolIsSharedByEveryBinding() {
    UltsState state = new UltsState();
    assertEquals(0, state.items().size());
    assertTrue(state.items().isEmpty());
  }

  @Test
  void bindingsAreFoundByPosition() {
    UltsState state = new UltsState();
    state.addBinding(binding("仓库", 1, 2, 3));
    assertNotNull(state.binding("minecraft:overworld", new BlockPos(1, 2, 3)));
    assertNull(state.binding("minecraft:the_nether", new BlockPos(1, 2, 3)));
    assertNull(state.binding("minecraft:overworld", new BlockPos(1, 3, 3)));
  }

  @Test
  void numbersFollowTheListOrderAndSurviveRemovals() {
    UltsState state = new UltsState();
    UltsBinding first = binding("一号", 1, 2, 3);
    UltsBinding second = binding("二号", 4, 5, 6);
    UltsBinding third = binding("三号", 7, 8, 9);
    state.addBinding(first);
    state.addBinding(second);
    state.addBinding(third);
    assertEquals(1, state.number(first));
    assertEquals(2, state.number(second));
    assertEquals(3, state.number(third));

    state.removeBindings(1, 1);
    // The removed binding is gone from both the index and the numbering.
    assertNull(state.binding("minecraft:overworld", new BlockPos(1, 2, 3)));
    assertEquals(0, state.number(first));
    assertEquals(1, state.number(second));
    assertEquals(2, state.number(third));
    // A position that was never bound has no number either.
    assertEquals(0, state.number(binding("不存在", 40, 50, 60)));
  }

  @Test
  void bindingCodecRoundTripsNoteAndCreator() {
    UltsBinding binding = new UltsBinding("主仓库", "minecraft:overworld", 5, 6, 7, "creator");
    Tag encoded = UltsBinding.CODEC.encodeStart(NbtOps.INSTANCE, binding).getOrThrow();
    assertEquals(binding, UltsBinding.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
  }

  @Test
  void bindingCodecAcceptsAMissingNote() {
    UltsBinding binding = new UltsBinding("", "minecraft:overworld", 5, 6, 7, "");
    Tag encoded = UltsBinding.CODEC.encodeStart(NbtOps.INSTANCE, binding).getOrThrow();
    assertEquals(binding, UltsBinding.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
  }

  @Test
  void theStateReadsBackEverythingItWrote() {
    UltsState state = new UltsState();
    state.addBinding(binding("仓库", 1, 2, 3));
    state.deposit(new ItemStack(Items.STONE, 5));
    Tag encoded = UltsState.TYPE.codec().encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    // The lists are read one entry at a time — an entry the game cannot make sense of is left out rather
    // than costing the whole file — so what is written has to come back whole as well.
    UltsState read = UltsState.TYPE.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow();
    assertEquals(1, read.bindings().size());
    assertEquals("仓库", read.bindings().getFirst().note());
    assertEquals(5L, UltsRuntime.storedAmount(new ItemStack(Items.STONE), read.items()));
  }

  @Test
  void savedDataCarriesOnlyTheCurrentFields() {
    UltsState state = new UltsState();
    state.addBinding(binding("仓库", 1, 2, 3));
    Tag encoded = UltsState.TYPE.codec().encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    String saved = encoded.toString();
    assertTrue(saved.contains("bindings"));
    assertTrue(saved.contains("note"));
    // Nothing of the removed named storages or of any other retired field may come back.
    assertFalse(saved.contains("terminals"));
    assertFalse(saved.contains("pools"));
    assertFalse(saved.contains("items"));
    assertFalse(saved.contains("legacy"));
  }

  @Test
  void viewProfileIsStoredPerPlayer() {
    UltsState state = new UltsState();
    UUID player = UUID.randomUUID();
    UltsViewProfile profile = new UltsViewProfile(
        "minecraft:building_blocks", 2, 4, "", UltsItemVisibility.SURVIVAL);
    state.setViewProfile(player, profile);
    assertEquals(profile, state.viewProfile(player));
    assertEquals(UltsViewProfile.DEFAULT, state.viewProfile(UUID.randomUUID()));
    assertEquals(0, state.revision());
  }

  @Test
  void viewProfilesNormalizePositionsAndFilter() {
    UltsViewProfile profile = new UltsViewProfile(
        "ultimate-storage:all", -3, -4, "  Diamond ", null);
    assertEquals(0, profile.categoryPage());
    assertEquals(0, profile.itemPage());
    assertEquals("diamond", profile.filter());
  }

  @Test
  void viewProfileCodecRoundTripsEveryField() {
    UltsViewProfile profile = new UltsViewProfile(
        "ultimate-storage:all", 2, 5, "diamond", UltsItemVisibility.AVAILABLE);
    Tag encoded = UltsViewProfile.CODEC.encodeStart(NbtOps.INSTANCE, profile).getOrThrow();
    assertEquals(profile, UltsViewProfile.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
  }

  @Test
  void viewProfileKeepsAProfileWithoutAVisibilityAndDropsUnknownModes() {
    // A profile written before the visibility could be chosen still loads.
    UltsViewProfile old = new UltsViewProfile("ultimate-storage:all", 1, 2, "stone", null);
    Tag encoded = UltsViewProfile.CODEC.encodeStart(NbtOps.INSTANCE, old).getOrThrow();
    assertEquals(old, UltsViewProfile.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());

    // A mode the enum does not know counts as "never chosen", so the configured default applies.
    UltsViewProfile chosen = new UltsViewProfile(
        "ultimate-storage:all", 1, 2, "stone", UltsItemVisibility.ALL);
    CompoundTag edited = (CompoundTag) UltsViewProfile.CODEC
        .encodeStart(NbtOps.INSTANCE, chosen).getOrThrow();
    edited.putString("visibility", "SOMETHING_ELSE");
    assertNull(UltsViewProfile.CODEC.parse(NbtOps.INSTANCE, edited).getOrThrow().visibility());
  }

  private static UltsBinding binding(String note, int x, int y, int z) {
    return new UltsBinding(note, "minecraft:overworld", x, y, z, "creator");
  }

  @Test
  void identicalArrivalsAreOneStackWhateverTheyAre() {
    UltsTestBootstrap.boot();
    UltsState state = new UltsState();
    ItemStack named = UltsTestBootstrap.stack(Items.DIAMOND_SWORD);
    named.set(DataComponents.CUSTOM_NAME, Component.literal("一号剑"));
    // Two arrivals that are identical in every way are one stack here, exactly as they would be for a
    // plain item: what makes two special stacks different is what they carry.
    state.deposit(named);
    state.deposit(named.copy());
    ItemStack other = UltsTestBootstrap.stack(Items.DIAMOND_SWORD);
    other.set(DataComponents.CUSTOM_NAME, Component.literal("二号剑"));
    state.deposit(other);

    List<UltsStoredView> rows = state.specialsOf(Items.DIAMOND_SWORD);
    assertEquals(2, rows.size());
    assertTrue(rows.stream().allMatch(UltsStoredView::special));
    assertTrue(rows.stream().allMatch(UltsStoredView::stampKnown));
    UltsStoredView pair = rows.stream()
        .filter(view -> "一号剑".equals(view.template().get(DataComponents.CUSTOM_NAME).getString()))
        .findFirst().orElseThrow();
    assertEquals(2L, pair.amount());

    // Taking one row out takes exactly that row, and leaves the other one as it was.
    ItemStack taken = state.takeBagRow(Items.DIAMOND_SWORD, pair.template());
    assertEquals(2, taken.getCount());
    List<UltsStoredView> left = state.specialsOf(Items.DIAMOND_SWORD);
    assertEquals(1, left.size());
    assertEquals("二号剑",
        left.getFirst().template().get(DataComponents.CUSTOM_NAME).getString());
  }

  @Test
  void takingOneRowOutOfABagLeavesTheOthersAndTheirTimesAlone() {
    UltsTestBootstrap.boot();
    UltsState state = new UltsState();
    ItemStack first = UltsTestBootstrap.stack(Items.DIAMOND_SWORD);
    first.set(DataComponents.CUSTOM_NAME, Component.literal("一号剑"));
    ItemStack second = UltsTestBootstrap.stack(Items.DIAMOND_SWORD);
    second.set(DataComponents.CUSTOM_NAME, Component.literal("二号剑"));
    state.deposit(first);
    state.deposit(second);
    List<UltsStoredView> before = state.specialsOf(Items.DIAMOND_SWORD);
    assertEquals(2, before.size());

    // Whatever the screen was showing is what comes back, and the row that stayed keeps its time.
    ItemStack taken = state.takeBagRow(Items.DIAMOND_SWORD, second);
    assertEquals("二号剑", taken.get(DataComponents.CUSTOM_NAME).getString());
    List<UltsStoredView> left = state.specialsOf(Items.DIAMOND_SWORD);
    assertEquals(1, left.size());
    assertEquals("一号剑", left.getFirst().template().get(DataComponents.CUSTOM_NAME).getString());
    assertTrue(left.getFirst().stampKnown());

    // A row that is not there any more hands over nothing instead of something else.
    assertTrue(state.takeBagRow(Items.DIAMOND_SWORD, second).isEmpty());
    assertEquals(1, state.specialsOf(Items.DIAMOND_SWORD).size());
  }

  @Test
  void aSpecialStackThatCanHoldMoreThanOnePoolsWithItsOwnKind() {
    UltsTestBootstrap.boot();
    UltsState state = new UltsState();
    ItemStack sticks = UltsTestBootstrap.stack(Items.STICK);
    sticks.set(DataComponents.CUSTOM_NAME, Component.literal("棍"));
    // A test cannot bind an item's own component map, so the stack size is put on the stack itself.
    // What matters is that a stack which can hold more than one piece is not a row of its own.
    sticks.set(DataComponents.MAX_STACK_SIZE, 64);
    state.deposit(sticks.copyWithCount(3));
    state.deposit(sticks.copyWithCount(4));

    List<UltsStoredView> pooled = state.specialsOf(Items.STICK);
    assertEquals(1, pooled.size());
    assertEquals(7L, pooled.getFirst().amount());
  }

  @Test
  void aBagDestroysTheLeastRecentlyStoredOnceItIsFull() {
    UltsTestBootstrap.boot();
    UltsState state = new UltsState();
    int slots = UltsConfigData.DEFAULT.special().bundleSlots();
    for (int arrival = 0; arrival <= slots; arrival++) {
      ItemStack sword = UltsTestBootstrap.stack(Items.DIAMOND_SWORD);
      sword.set(DataComponents.CUSTOM_NAME, Component.literal("剑 " + arrival));
      state.deposit(sword);
    }

    List<UltsStoredView> rows = state.specialsOf(Items.DIAMOND_SWORD);
    assertEquals(slots, rows.size());
    // The one that arrived first is the one that was destroyed, and the latest arrival is still there.
    // The arrivals of one loop share a millisecond, so which end of the bag they sit at is not fixed;
    // what the cap decides is which of them goes.
    assertTrue(rows.stream().noneMatch(view -> "剑 0".equals(
        view.template().get(DataComponents.CUSTOM_NAME).getString())));
    assertTrue(rows.stream().anyMatch(view -> ("剑 " + slots).equals(
        view.template().get(DataComponents.CUSTOM_NAME).getString())));
  }
}
