package com.flwolfy.ults.data.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flwolfy.ults.crafting.UltsTestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UltsStoredEntryTest {

  @BeforeAll
  static void boot() {
    UltsTestBootstrap.boot();
  }

  @Test
  void anEntryKeepsTheTimeItWasLastPutIn() {
    UltsStoredEntry entry = new UltsStoredEntry(UltsTestBootstrap.stack(Items.STONE), 7L, 1_234L);
    Tag encoded = UltsStoredEntry.CODEC.encodeStart(NbtOps.INSTANCE, entry).getOrThrow();
    UltsStoredEntry parsed = UltsStoredEntry.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
    assertEquals(7L, parsed.amount());
    assertEquals(1_234L, parsed.updatedAt());
  }

  @Test
  void aSaveFromBeforeTheStampsLoadsWithAnUnknownTime() {
    UltsStoredEntry entry = new UltsStoredEntry(UltsTestBootstrap.stack(Items.STONE), 7L, 1_234L);
    CompoundTag encoded =
        (CompoundTag) UltsStoredEntry.CODEC.encodeStart(NbtOps.INSTANCE, entry).getOrThrow();
    encoded.remove("updatedAt");
    UltsStoredEntry parsed = UltsStoredEntry.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
    assertEquals(7L, parsed.amount());
    // Unknown age counts as the oldest, which is what the special category trims first.
    assertEquals(0L, parsed.updatedAt());
  }
}
