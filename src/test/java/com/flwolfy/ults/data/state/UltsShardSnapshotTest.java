package com.flwolfy.ults.data.state;

import static org.junit.jupiter.api.Assertions.*;
import static com.flwolfy.ults.crafting.UltsTestBootstrap.stack;
import com.flwolfy.ults.crafting.UltsTestBootstrap;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.*;

@Tag("acceptance")
class UltsShardSnapshotTest {
  @BeforeAll static void boot() { UltsTestBootstrap.boot(); }
  private static UltsRemoteStorage.Snapshot stock(long count) {
    return new UltsRemoteStorage.Snapshot(List.of(new UltsStoredView(stack(Items.STONE), count, false)), List.of());
  }
  @Test void joiningInventoriesAreCountedOnceAcrossIndependentSlices() {
    var left = new UltsRemoteStorage.Part("minecraft:overworld", BlockPos.ZERO);
    var right = new UltsRemoteStorage.Part("minecraft:overworld", BlockPos.ZERO.east());
    var joined = Map.of(left, stock(64), right, stock(32));
    var snapshot = UltsRemoteStorage.mergeShards(List.of(
        new UltsRemoteStorage.ShardSnapshot(1, joined),
        new UltsRemoteStorage.ShardSnapshot(2, joined)));
    assertEquals(96, snapshot.amount(stack(Items.STONE)));
  }
  @Test void newestPhysicalSnapshotWinsRegardlessOfShardOrder() {
    var part = new UltsRemoteStorage.Part("minecraft:overworld", BlockPos.ZERO);
    var old = new UltsRemoteStorage.ShardSnapshot(1, Map.of(part, stock(64)));
    var fresh = new UltsRemoteStorage.ShardSnapshot(2, Map.of(part, stock(12)));
    assertEquals(12, UltsRemoteStorage.mergeShards(List.of(fresh, old)).amount(stack(Items.STONE)));
    var empty = new UltsRemoteStorage.ShardSnapshot(3, Map.of(part, UltsRemoteStorage.Snapshot.EMPTY));
    assertEquals(0, UltsRemoteStorage.mergeShards(List.of(empty, old, fresh)).amount(stack(Items.STONE)));
  }
  @Test void splitInventoriesAndDifferentDimensionsRemainDistinct() {
    var left = new UltsRemoteStorage.Part("minecraft:overworld", BlockPos.ZERO);
    var right = new UltsRemoteStorage.Part("minecraft:overworld", BlockPos.ZERO.east());
    var nether = new UltsRemoteStorage.Part("minecraft:the_nether", BlockPos.ZERO);
    var snapshot = UltsRemoteStorage.mergeShards(List.of(
        new UltsRemoteStorage.ShardSnapshot(1, Map.of(left, stock(64))),
        new UltsRemoteStorage.ShardSnapshot(2, Map.of(right, stock(32), nether, stock(16)))));
    assertEquals(112, snapshot.amount(stack(Items.STONE)));
  }
}
