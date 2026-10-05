package com.flwolfy.ults.acceptance;

import com.flwolfy.ults.*;
import com.flwolfy.ults.crafting.*;
import com.flwolfy.ults.data.config.*;
import com.flwolfy.ults.data.state.*;
import com.flwolfy.ults.input.UltsInputManager;
import com.flwolfy.ults.input.UltsContainers;
import com.flwolfy.ults.display.UltsNativeDelivery;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.*;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Runs only in the opt-in isolated server source set, never in a user's game or release jar. */
public final class UltsServerAcceptance implements ModInitializer {
  private final List<String> results = new ArrayList<>();
  private UltsRuntime runtime;
  private int ticks;
  private boolean finished;
  private final BlockPos left = new BlockPos(0, 64, 0), right = left.east();
  private final BlockPos input = new BlockPos(4, 64, 0), downstream = input.east();
  private final BlockPos upstream = downstream.east(), branch = input.south();

  @Override public void onInitialize() {
    ServerTickEvents.END_SERVER_TICK.register(server -> {
      if (finished) return;
      try {
        if (runtime == null) {
          runtime = UltsMod.getRuntime();
          setup(server);
        }
        advance(server);
      } catch (Throwable error) {
        results.add("FAIL: " + error);
        UltsMod.LOGGER.error("UltStorage native acceptance failed", error);
        finish(server, false);
      }
    });
  }

  private void setup(MinecraftServer server) throws Exception {
    // Recipes here come from the real server's vanilla resource reload, not a fixture catalogue.
    var pool = new UltsCraftPool(8);
    pool.add(Items.OAK_LOG.getDefaultInstance(), 64);
    pool.add(Items.CRAFTING_TABLE.getDefaultInstance(), 1);
    craft("64 logs", pool, 512);
    pool = new UltsCraftPool(8);
    for (Item item : List.of(Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG, Items.CRAFTING_TABLE)) {
      pool.add(item.getDefaultInstance(), 1);
    }
    craft("three log varieties", pool, 24);
    pool = new UltsCraftPool(8);
    pool.add(Items.OAK_PLANKS.getDefaultInstance(), 2);
    pool.add(Items.BAMBOO.getDefaultInstance(), 2);
    pool.add(Items.CRAFTING_TABLE.getDefaultInstance(), 1);
    craft("wood and bamboo routes", pool, 5);

    packing(server);
    multiBlock(server);

    config(UltsStorageMode.REMOTE);
    ServerLevel level = server.overworld();
    level.setChunkForced(0, 0, true);
    level.getChunk(0, 0);
    level.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH), 2);
    level.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH), 2);
    check("bind first single chest", runtime.inputs().bind("test", "acceptance", level, left).name().equals("SUCCESS"));
    check("bind second single chest", runtime.inputs().bind("test", "acceptance", level, right).name().equals("SUCCESS"));
    container(level, left).setItem(0, new ItemStack(Items.STONE, 64));
    assertStock("single chest cache", 64);
    level.setBlock(left, level.getBlockState(left).setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
    level.setBlock(right, level.getBlockState(right).setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
    container(level, right).setItem(0, new ItemStack(Items.STONE, 32));
    runtime.reload();
    assertStock("joined chest across shards", 96);
    container(level, left).setItem(0, new ItemStack(Items.STONE, 10));
    container(level, right).setItem(0, ItemStack.EMPTY);
    check("quick withdrawal uses ten live items despite older cached stock",
        runtime.stackQuantity(Items.STONE.getDefaultInstance()) == 10);
    container(level, left).setItem(0, new ItemStack(Items.STONE, 64));
    container(level, right).setItem(0, new ItemStack(Items.STONE, 32));

    BlockPos unloaded = new BlockPos(16000, 64, 16000);
    check("unloaded test chunk initially absent", !level.hasChunk(1000, 1000));
    var missing = new UltsBinding("unloaded", "minecraft:overworld", unloaded.getX(), unloaded.getY(), unloaded.getZ(), "test");
    check("unloaded shard contributes nothing", UltsRemoteStorage.snapshotShard(server, List.of(missing), 1).parts().isEmpty());
    check("reading a shard does not load chunks", !level.hasChunk(1000, 1000));

    runtime.state().removeBindings(1, runtime.state().bindingCount());
    config(UltsStorageMode.VOID);
    ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
    sword.setDamageValue(1);
    runtime.state().deposit(sword);
    level.getDataStorage().saveAndJoin();
    check("actual save clears dirty flag", !runtime.state().isDirty());
    var defaults = UltsConfigManager.getInstance().data();
    UltsConfigManager.getInstance().update(new UltsConfigData(defaults.general(), defaults.input(),
        new UltsConfigData.Special(120, UltsStackRule.COMPONENTS, UltsSpecialFilter.FILTER_ALL, List.of("minecraft:diamond_sword"))));
    runtime.reload();
    check("reload purge removes sword", amount(runtime.state().items(), Items.DIAMOND_SWORD) == 0);
    check("reload purge dirties saved data", runtime.state().isDirty());
    level.getDataStorage().saveAndJoin();
    var properties = new Properties();
    try (var reader = Files.newBufferedReader(Path.of("server.properties"))) { properties.load(reader); }
    Path data = Path.of(properties.getProperty("level-name"), "dimensions/minecraft/overworld/data/ultimate-storage/state.dat");
    CompoundTag disk = NbtIo.readCompressed(data, NbtAccounter.unlimitedHeap());
    check("actual disk save contains no purged sword", !disk.toString().contains("diamond_sword"));
    config(UltsStorageMode.VOID);
    var loaded = UltsState.TYPE.codec().parse(RegistryOps.create(NbtOps.INSTANCE, level.registryAccess()),
        disk.getCompound("data").orElseThrow()).getOrThrow();
    check("disabling filter and reading saved data does not resurrect sword", amount(loaded.items(), Items.DIAMOND_SWORD) == 0);

    level.setBlock(input, Blocks.CHEST.defaultBlockState(), 3);
    level.setBlock(downstream, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.WEST), 3);
    level.setBlock(upstream, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.WEST), 3);
    level.setBlock(branch, Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING, Direction.NORTH), 3);
    runtime.state().addBinding(new UltsBinding("hopper", "minecraft:overworld", input.getX(), input.getY(), input.getZ(), "test"));
  }

  private void advance(MinecraftServer server) {
    ServerLevel level = server.overworld();
    switch (++ticks) {
      // Give forced chunks time to enter entity tracking before testing an accepted world drop.
      case 20 -> {
        UltsNativeDelivery.check(server, runtime, this::check);
        UltsNativeBreak.check(server, runtime, this::check);
        try {
          UltsNativeQuantity.check(server, this::check);
          com.flwolfy.ults.UltsNativeStability.check(server, this::check);
          com.flwolfy.ults.UltsNativeLargeCraft.check(server, this::check);
          com.flwolfy.ults.UltsNativeHugeCraft.check(server, this::check);
        }
        catch (Exception error) { throw new IllegalStateException(error); }
      }
      case 41 -> {
        level.setBlock(downstream.below(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        check("downstream hopper redstone lock is active", !level.getBlockState(downstream).getValue(HopperBlock.ENABLED));
        check("upstream hopper remains enabled", level.getBlockState(upstream).getValue(HopperBlock.ENABLED));
        container(level, downstream).setItem(0, new ItemStack(Items.DIAMOND));
        container(level, upstream).setItem(0, new ItemStack(Items.EMERALD));
        container(level, branch).setItem(0, new ItemStack(Items.GOLD_INGOT));
      }
      case 42 -> {
        check("cached lock retains diamond", chainAmount(level, Items.DIAMOND) == 1 && stored(Items.DIAMOND) == 0);
        check("locked downstream blocks upstream branch", chainAmount(level, Items.EMERALD) == 1 && stored(Items.EMERALD) == 0);
        check("independent enabled branch still forwards", delivered(level, Items.GOLD_INGOT) == 1);
        level.setBlock(downstream.below(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(downstream, level.getBlockState(downstream).setValue(HopperBlock.FACING, Direction.EAST), 3);
      }
      case 43 -> {
        check("rotated cached edge cannot forward diamond", chainAmount(level, Items.DIAMOND) == 1 && stored(Items.DIAMOND) == 0);
        check("disconnected upstream cannot forward emerald", chainAmount(level, Items.EMERALD) == 1 && stored(Items.EMERALD) == 0);
        level.setBlock(downstream, level.getBlockState(downstream).setValue(HopperBlock.FACING, Direction.WEST), 3);
      }
      case 44 -> {
        check("restored cached path resumes forwarding", delivered(level, Items.DIAMOND) == 1 && delivered(level, Items.EMERALD) == 1);
      }
      case 64 -> {
        check("forwarded branches reach storage on drain schedule", stored(Items.DIAMOND) == 1
            && stored(Items.EMERALD) == 1 && stored(Items.GOLD_INGOT) == 1);
        level.setBlock(input, Blocks.AIR.defaultBlockState(), 3);
        container(level, downstream).setItem(0, new ItemStack(Items.LAPIS_LAZULI));
      }
      case 65 -> {
        check("removed root container disconnects cached path", chainAmount(level, Items.LAPIS_LAZULI) == 1 && stored(Items.LAPIS_LAZULI) == 0);
        // Continue with remote topology changes over a complete refresh cycle, without reload.
        runtime.state().removeBindings(1, runtime.state().bindingCount());
        config(UltsStorageMode.REMOTE);
        runtime.state().addBinding(new UltsBinding("left", "minecraft:overworld", 0, 64, 0, "test"));
        runtime.state().addBinding(new UltsBinding("right", "minecraft:overworld", 1, 64, 0, "test"));
        assertStock("initial joined cache", 96);
        level.setBlock(left, level.getBlockState(left).setValue(ChestBlock.TYPE, ChestType.SINGLE), 2);
        level.setBlock(right, level.getBlockState(right).setValue(ChestBlock.TYPE, ChestType.SINGLE), 2);
      }
      case 85 -> {
        assertStock("split chest after shard refresh", 96);
        level.setBlock(left, level.getBlockState(left).setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
        level.setBlock(right, level.getBlockState(right).setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
      }
      case 105 -> {
        assertStock("joined chest after shard refresh", 96);
        level.setBlock(right, Blocks.AIR.defaultBlockState(), 3);
      }
      case 145 -> {
        assertStock("removed chest half after shard refresh", 64);
        finish(server, true);
      }
    }
    // Keep the lazy cache under demand, as an open storage screen does.
    if (ticks >= 65) runtime.storedItems();
  }

  private void craft(String name, UltsCraftPool before, int expected) {
    ItemStack target = Items.STICK.getDefaultInstance();
    long capacity = UltsCraftResolver.capacity(target, before, UltsCraftingMode.ALL, true);
    // A cold JVM may hit the documented clock bound; UNKNOWN must be retryable, never cached.
    if (capacity == UltsCraftResolver.UNKNOWN) capacity = UltsCraftResolver.capacity(target, before, UltsCraftingMode.ALL, true);
    check(name + " exact capacity=" + capacity, capacity == expected);
    var planned = before.copy();
    var plan = UltsWithdrawalPlanner.plan(planned, target, expected, false, UltsCraftingMode.ALL);
    check(name + " withdrawal plan", plan.available());
    var replayed = before.copy();
    check(name + " execution", UltsWithdrawalPlanner.run(replayed, plan, target, expected, false));
    check(name + " requested output", plan.outputs().stream().mapToInt(ItemStack::getCount).sum() == expected);
    planned.take(target, expected);
    check(name + " exact replayed pool", planned.views().stream().allMatch(row -> replayed.amount(row.template()) == row.amount())
        && replayed.views().stream().allMatch(row -> planned.amount(row.template()) == row.amount()));
  }

  private void packing(MinecraftServer server) {
    for (int logs : new int[]{216, 218}) {
      var before = new UltsCraftPool(8);
      before.add(Items.OAK_LOG.getDefaultInstance(), logs);
      before.add(Items.CRAFTING_TABLE.getDefaultInstance(), 1);
      before.add(Items.SHULKER_SHELL.getDefaultInstance(), 2);
      before.add(Items.DYED_SHULKER_BOX.blue().getDefaultInstance(), 1);
      var planned = before.copy();
      var target = Items.STICK.getDefaultInstance();
      var plan = UltsWithdrawalPlanner.plan(planned, target, 1, true, UltsCraftingMode.ALL);
      if (!plan.available()) plan = UltsWithdrawalPlanner.plan(planned, target, 1, true, UltsCraftingMode.ALL);
      String name = logs + " logs boxed withdrawal";
      check(name + " joint plan", plan.available());
      check(name + " preferred feasible box", plan.outputs().getFirst().is(
          logs == 216 ? Items.DYED_SHULKER_BOX.blue() : Items.SHULKER_BOX));
      check(name + " full contents", plan.outputs().getFirst().get(DataComponents.CONTAINER)
          .allItemsCopyStream().mapToInt(ItemStack::getCount).sum() == 1728);
      var replay = before.copy();
      check(name + " replay", UltsWithdrawalPlanner.run(replay, plan, target, 1, true));
      planned.take(target, 1728);
      planned.takePackable(1);
      check(name + " exact replayed pool", planned.views().stream().allMatch(row -> replay.amount(row.template()) == row.amount())
          && replay.views().stream().allMatch(row -> planned.amount(row.template()) == row.amount()));
      check(name + " original shells preserved only when using existing box",
          replay.amount(Items.SHULKER_SHELL.getDefaultInstance()) == (logs == 216 ? 2 : 0));
    }
  }

  private void multiBlock(MinecraftServer server) {
    var manager = UltsConfigManager.getInstance();
    var old = manager.data();
    var i = old.input();
    try {
      check("configure independent multi-block inventory parts", manager.update(new UltsConfigData(old.general(),
          new UltsConfigData.Input(i.permissionLevel(), i.maxBindings(), 1, List.of("minecraft:dispenser"),
              i.crafting(), i.allowFullInventory(), i.allowBulkWithdrawal(), i.bulkWithdrawalStacks(), i.withdrawalRate()), old.special())));
      var level = server.overworld();
      level.getChunk(1, 0);
      var root = new BlockPos(20, 64, 0);
      int[] counts = {32, 64, 16};
      for (int index = 0; index < 3; index++) {
        level.setBlock(root.east(index), Blocks.DISPENSER.defaultBlockState(), 3);
        container(level, root.east(index)).setItem(0, new ItemStack(Items.STONE, counts[index]));
      }
      var state = new UltsState();
      var inputs = new UltsInputManager(server, state);
      check("multi-block binds once", inputs.bind("test", "three parts", level, root) == UltsInputManager.BindResult.SUCCESS);
      check("multi-block discovers all independent parts", UltsContainers.parts(level, root).size() == 3);
      // Simulate old overlapping bindings as well: physical slots must still count once.
      state.addBinding(new UltsBinding("legacy overlap", "minecraft:overworld", 21, 64, 0, "test"));
      var snapshot = UltsRemoteStorage.snapshot(server, state.bindings());
      check("multi-block live snapshot includes all parts exactly once", snapshot.amount(Items.STONE.getDefaultInstance()) == 112);
      var shards = UltsRemoteStorage.mergeShards(List.of(
          UltsRemoteStorage.snapshotShard(server, state.bindings().subList(0, 1), 1),
          UltsRemoteStorage.snapshotShard(server, state.bindings().subList(1, 2), 2)));
      check("multi-block cached and live snapshots agree", shards.amount(Items.STONE.getDefaultInstance()) == snapshot.amount(Items.STONE.getDefaultInstance()));
      var taken = UltsRemoteStorage.take(server, state.bindings(), state, Items.STONE.getDefaultInstance(), 112, false, UltsCraftingMode.DISABLED);
      check("multi-block withdrawal reaches all parts", taken.stream().mapToInt(ItemStack::getCount).sum() == 112);
      for (int index = 0; index < 3; index++) container(level, root.east(index)).setItem(0, new ItemStack(Items.STONE, counts[index]));
      inputs.tick(true);
      check("multi-block void drain collects all parts exactly once", amount(state.items(), Items.STONE) == 112);
      check("multi-block void drain empties every part", container(level, root).isEmpty()
          && container(level, root.east()).isEmpty() && container(level, root.east(2)).isEmpty());
    } finally {
      if (!manager.update(old)) throw new IllegalStateException("Cannot restore isolated test config");
    }
  }
  private static Container container(ServerLevel level, BlockPos position) {
    return (Container) level.getBlockEntity(position);
  }
  private static long amount(List<UltsStoredView> views, Item item) {
    return views.stream().filter(row -> row.template().is(item)).mapToLong(UltsStoredView::amount).sum();
  }
  private long stored(Item item) { return amount(runtime.state().items(), item); }
  private long delivered(ServerLevel level, Item item) {
    long amount = stored(item);
    Container destination = container(level, input);
    for (int slot = 0; slot < destination.getContainerSize(); slot++) {
      if (destination.getItem(slot).is(item)) amount += destination.getItem(slot).getCount();
    }
    return amount;
  }
  private long chainAmount(ServerLevel level, Item item) {
    long count = 0;
    for (BlockPos position : List.of(downstream, upstream)) {
      Container container = container(level, position);
      for (int slot = 0; slot < container.getContainerSize(); slot++) {
        if (container.getItem(slot).is(item)) count += container.getItem(slot).getCount();
      }
    }
    return count;
  }
  private void assertStock(String name, long expected) {
    check(name + " fresh=" + expected, amount(runtime.storedItemsFresh(), Items.STONE) == expected);
    check(name + " cached=" + expected, amount(runtime.storedItems(), Items.STONE) == expected);
  }
  private void config(UltsStorageMode mode) {
    var d = UltsConfigData.DEFAULT;
    var i = d.input();
    if (!UltsConfigManager.getInstance().update(new UltsConfigData(
        new UltsConfigData.General("en_us", d.general().itemVisibility(), mode),
        new UltsConfigData.Input(i.permissionLevel(), i.maxBindings(), 20, i.multiBlockContainers(),
            UltsCraftingMode.ALL, i.allowFullInventory(), i.allowBulkWithdrawal(), i.bulkWithdrawalStacks(), i.withdrawalRate()), d.special()))) {
      throw new IllegalStateException("Isolated config update failed");
    }
    runtime.reload();
  }
  private void check(String name, boolean condition) {
    if (!condition) throw new AssertionError(name);
    results.add("PASS: " + name);
    UltsMod.LOGGER.info("SERVER_ACCEPTANCE: {}", name);
  }
  private void finish(MinecraftServer server, boolean passed) {
    finished = true;
    try {
      Files.writeString(Path.of("server-acceptance-results.txt"), (passed ? "PASS\n" : "FAIL\n") + String.join("\n", results) + "\n");
    } catch (Exception error) { throw new RuntimeException(error); }
    server.halt(false);
  }
}
