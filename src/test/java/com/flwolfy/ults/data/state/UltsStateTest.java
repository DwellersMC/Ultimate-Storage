package com.flwolfy.ults.data.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
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
    UltsViewProfile profile = new UltsViewProfile("minecraft:building_blocks", 2, 4, "");
    state.setViewProfile(player, profile);
    assertEquals(profile, state.viewProfile(player));
    assertEquals(UltsViewProfile.DEFAULT, state.viewProfile(UUID.randomUUID()));
    assertEquals(0, state.revision());
  }

  @Test
  void viewProfilesNormalizePositionsAndFilter() {
    UltsViewProfile profile = new UltsViewProfile("ultimate-storage:all", -3, -4, "  Diamond ");
    assertEquals(0, profile.categoryPage());
    assertEquals(0, profile.itemPage());
    assertEquals("diamond", profile.filter());
  }

  @Test
  void viewProfileCodecRoundTripsEveryField() {
    UltsViewProfile profile = new UltsViewProfile("ultimate-storage:all", 2, 5, "diamond");
    Tag encoded = UltsViewProfile.CODEC.encodeStart(NbtOps.INSTANCE, profile).getOrThrow();
    assertEquals(profile, UltsViewProfile.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow());
  }

  private static UltsBinding binding(String note, int x, int y, int z) {
    return new UltsBinding(note, "minecraft:overworld", x, y, z, "creator");
  }
}
