package com.flwolfy.ults.data.state;

import static org.junit.jupiter.api.Assertions.*;
import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import com.flwolfy.ults.data.config.*;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("acceptance")
class UltsRecoveryTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }

  @Test void purgingPreviouslySavedStockMarksItDirtyAndPersistsTheDeletion() throws Exception {
    UltsState state = new UltsState();
    state.deposit(stack(Items.DIAMOND_SWORD));
    state.deposit(stack(Items.STONE));
    state.setDirty(false);
    long revision = state.revision();
    var defaults = UltsConfigData.DEFAULT;
    var config = new UltsConfigData(defaults.general(), defaults.input(),
        new UltsConfigData.Special(120, UltsStackRule.COMPONENTS, UltsSpecialFilter.FILTER_ALL,
            List.of("minecraft:diamond_sword")));
    CompoundTag saved;
    try (var active = new UltsTestConfig(config)) {
      UltsSpecialFilters.rebuild();
      assertTrue(state.purgeFiltered());
      assertTrue(state.isDirty());
      assertEquals(revision + 1, state.revision());
      saved = (CompoundTag) UltsState.TYPE.codec().encodeStart(NbtOps.INSTANCE, state).getOrThrow();
      state.setDirty(false);
      assertFalse(state.purgeFiltered());
      assertFalse(state.isDirty());
    } finally { UltsSpecialFilters.rebuild(); }
    var loaded = UltsState.TYPE.codec().parse(NbtOps.INSTANCE, saved).getOrThrow();
    assertEquals(1, loaded.items().size());
    assertTrue(loaded.items().getFirst().template().is(Items.STONE));
  }

  @Test void oldSavesWithoutARecoveryFieldStillLoad() {
    UltsState state = new UltsState();
    state.deposit(stack(Items.STONE));
    var encoded = (CompoundTag) UltsState.TYPE.codec().encodeStart(NbtOps.INSTANCE, state).getOrThrow();
    encoded.remove("remoteRecovery");
    UltsState loaded = UltsState.TYPE.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow();
    assertEquals(1L, loaded.items().getFirst().amount());
    assertTrue(loaded.remoteRecovery().isEmpty());
  }

  @Test void recoveryPreservesPackedBoxesAndTheirContents() {
    ItemStack box = stack(Items.SHULKER_BOX);
    box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(stack(Items.DIAMOND))));
    var state = new UltsState();
    state.keepRemoteRecovery(List.of(box));
    var loaded = UltsState.TYPE.codec().parse(NbtOps.INSTANCE,
        UltsState.TYPE.codec().encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
    ItemStack stored = loaded.remoteRecovery().getFirst().template();
    assertTrue(stored.is(Items.SHULKER_BOX));
    assertTrue(stored.get(DataComponents.CONTAINER).allItemsCopyStream().findFirst().orElseThrow().is(Items.DIAMOND));
    assertTrue(loaded.items().isEmpty());
  }

  @Test void recoveryDoesNotTrimRowsOrApplyConfiguredFilters() throws Exception {
    var defaults = UltsConfigData.DEFAULT;
    var config = new UltsConfigData(defaults.general(), defaults.input(),
        new UltsConfigData.Special(1, UltsStackRule.COMPONENTS, UltsSpecialFilter.FILTER_ALL,
            List.of("minecraft:diamond_sword")));
    try (var active = new UltsTestConfig(config)) {
      UltsSpecialFilters.rebuild();
      List<ItemStack> swords = new ArrayList<>();
      for (int index = 0; index < 3; index++) {
        ItemStack sword = stack(Items.DIAMOND_SWORD);
        sword.set(DataComponents.MAX_DAMAGE, 100);
        sword.set(DataComponents.DAMAGE, index + 1);
        swords.add(sword);
      }
      assertTrue(UltsSpecialFilters.dismisses(swords.getFirst()));
      var state = new UltsState();
      state.keepRemoteRecovery(swords);
      assertEquals(3, state.remoteRecovery().size());
      var loaded = UltsState.TYPE.codec().parse(NbtOps.INSTANCE,
          UltsState.TYPE.codec().encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow();
      assertEquals(3, loaded.remoteRecovery().size());
      assertTrue(loaded.items().isEmpty());
    } finally {
      UltsSpecialFilters.rebuild();
    }
  }

  @Test void blockedRecoveryEntriesDoNotStarveLaterItems() {
    var state = new UltsState();
    List<ItemStack> blocked = new ArrayList<>();
    for (int index = 0; index < 40; index++) { blocked.add(stack(Items.STONE)); }
    blocked.add(stack(Items.DIAMOND));
    state.keepRemoteRecovery(blocked);
    assertFalse(state.retryRemoteRecovery(offered -> {}));
    assertTrue(state.retryRemoteRecovery(offered -> {
      offered.stream().filter(item -> item.is(Items.DIAMOND)).forEach(item -> item.setCount(0));
    }));
    assertFalse(state.remoteRecovery().stream().anyMatch(item -> item.template().is(Items.DIAMOND)));
  }

  @Test void retryIsBoundedAndOnlyRemovesSuccessfulDeliveries() {
    var state = new UltsState();
    List<ItemStack> swords = new ArrayList<>();
    for (int index = 0; index < 50; index++) { swords.add(stack(Items.DIAMOND_SWORD)); }
    state.keepRemoteRecovery(swords);
    assertTrue(state.retryRemoteRecovery(offered -> {
      assertEquals(36, offered.size());
      assertEquals(36L, offered.stream().mapToLong(ItemStack::getCount).sum());
      offered.getFirst().setCount(0);
    }));
    assertEquals(49L, state.remoteRecovery().stream().mapToLong(UltsStoredView::amount).sum());
    assertFalse(state.retryRemoteRecovery(offered -> {}));
    assertEquals(49L, state.remoteRecovery().stream().mapToLong(UltsStoredView::amount).sum());
  }

  @Test void partialBagWithdrawalPreservesTimestampAndLaterArrivals() {
    ItemStack sword = stack(Items.DIAMOND_SWORD);
    sword.set(DataComponents.CUSTOM_NAME, Component.literal("bounded"));
    var state = new UltsState();
    state.deposit(sword.copyWithCount(8));
    long stamp = state.specialsOf(Items.DIAMOND_SWORD).getFirst().updatedAt();
    assertEquals(1, state.takeBagPieces(Items.DIAMOND_SWORD, sword, 1).getCount());
    assertEquals(7L, state.specialsOf(Items.DIAMOND_SWORD).getFirst().amount());
    assertEquals(stamp, state.specialsOf(Items.DIAMOND_SWORD).getFirst().updatedAt());
    state.deposit(sword.copyWithCount(3));
    assertEquals(2, state.takeBagPieces(Items.DIAMOND_SWORD, sword, 2).getCount());
    assertEquals(8L, state.specialsOf(Items.DIAMOND_SWORD).getFirst().amount());
    assertTrue(state.takeBagPieces(Items.DIAMOND_SWORD, sword, 0).isEmpty());
  }
}
