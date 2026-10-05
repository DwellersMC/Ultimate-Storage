package com.flwolfy.ults;

import com.flwolfy.ults.crafting.*;
import com.flwolfy.ults.data.config.*;
import com.flwolfy.ults.data.state.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import com.flwolfy.ults.display.UltsStorageSGUI;
import net.minecraft.core.component.DataComponents;

/** Sweeps every vanilla recipe result against millions of stored items, using shared tick budgets. */
public final class UltsNativeHugeCraft {
  public static void check(MinecraftServer server, BiConsumer<String, Boolean> check) throws Exception {
    var previous = UltsConfigManager.getInstance().data();
    try {
      var input = previous.input();
      UltsConfigManager.getInstance().update(new UltsConfigData(new UltsConfigData.General(
          "en_us", UltsItemVisibility.AVAILABLE, UltsStorageMode.VOID), new UltsConfigData.Input(
          input.permissionLevel(), input.maxBindings(), input.drainInterval(), input.multiBlockContainers(),
          UltsCraftingMode.ALL, true, true, 36, 64), previous.special()));
      var unique = new TreeMap<String, ItemStack>();
      for (var recipe : UltsCraftCatalog.everything()) {
        var result = recipe.result();
        unique.putIfAbsent(BuiltInRegistries.ITEM.getKey(result.getItem()) + "|" + result.getComponentsPatch(), result);
      }
      var targets = new ArrayList<>(unique.values());
      var scenarios = new LinkedHashMap<String, List<UltsStoredView>>();
      scenarios.put("spruce", List.of(row(Items.SPRUCE_LOG, 40_000_003), row(Items.SPRUCE_PLANKS, 17), row(Items.STICK, 3),
          row(Items.CRAFTING_TABLE, 1), row(Items.STONECUTTER, 1)));
      var mixed = new ArrayList<UltsStoredView>();
      for (Item item : List.of(Items.OAK_LOG, Items.BIRCH_LOG, Items.SPRUCE_LOG, Items.BAMBOO, Items.COBBLESTONE,
          Items.STONE, Items.IRON_INGOT, Items.IRON_NUGGET, Items.COPPER_INGOT, Items.GOLD_INGOT,
          Items.REDSTONE, Items.COAL, Items.WHEAT, Items.SUGAR_CANE, Items.STRING, Items.LEATHER,
          Items.PAPER, Items.QUARTZ, Items.SAND, Items.GLASS, Items.WOOL.white(), Items.BONE_MEAL,
          Items.CRAFTING_TABLE, Items.STONECUTTER)) mixed.add(row(item, 30_000_007));
      scenarios.put("mixed", mixed);
      scenarios.put("mixed_3b", mixed.stream().map(row -> new UltsStoredView(row.template(), 3_000_000_003L, false)).toList());
      var legacyRows = List.of(row(Items.SPRUCE_LOG, 3_000_000_003L), row(Items.CRAFTING_TABLE, 1), row(Items.STONECUTTER, 1));
      var ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
      var entries = new ListTag();
      for (var row : legacyRows) {
        var entry = new CompoundTag(); entry.put("stack", ItemStack.CODEC.encodeStart(ops, row.template()).getOrThrow());
        entry.putLong("amount", row.amount()); entries.add(entry);
      }
      var oldSave = new CompoundTag(); oldSave.put("pool", entries);
      var loaded = UltsState.TYPE.codec().parse(ops, oldSave).getOrThrow();
      check.accept("legacy saved stock loads more than INT_MAX with no migration loss", UltsRuntime.storedAmount(Items.SPRUCE_LOG.getDefaultInstance(), loaded.items()) == 3_000_000_003L);
      scenarios.put("legacy_3b", loaded.items());
      var diagnostics = new ArrayList<String>(); diagnostics.add("scenario,item,components,amount");
      boolean all = true;
      for (var scenario : scenarios.entrySet()) {
        long[] tick = {1000};
        var runtime = new UltsRuntime(server, new UltsState(), () -> tick[0]);
        int originalMark = UltsCraftPool.of(scenario.getValue()).mark();
        var unresolved = new LinkedHashSet<ItemStack>(targets);
        var values = new HashMap<Item, Long>();
        for (int frame = 0; frame < 1500 && !unresolved.isEmpty(); frame++) {
          tick[0]++;
          for (var iterator = unresolved.iterator(); iterator.hasNext();) {
            var target = iterator.next();
            var amount = runtime.craftingAmount(target, scenario.getValue());
            if (!amount.pending()) {
              diagnostics.add(scenario.getKey() + "," + BuiltInRegistries.ITEM.getKey(target.getItem()) + "," + csv(target.getComponentsPatch().toString()) + "," + amount.amount());
              if (target.getComponentsPatch().isEmpty()) values.put(target.getItem(), amount.amount());
              iterator.remove();
            }
          }
        }
        for (var target : unresolved) diagnostics.add(scenario.getKey() + "," + BuiltInRegistries.ITEM.getKey(target.getItem()) + "," + csv(target.getComponentsPatch().toString()) + ",PENDING");
        UltsMod.LOGGER.info("Huge-stock sweep {}: {} of {} settled; pending {}", scenario.getKey(), targets.size() - unresolved.size(),
            targets.size(), unresolved.stream().map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem())).toList());
        all &= unresolved.isEmpty();
        if (scenario.getKey().equals("mixed")) {
          var expected = Map.of(Items.COPPER_TORCH, 120_000_028L, Items.STICK, 735_000_171L,
              Items.WOODEN_SWORD, 147_000_034L, Items.CAMPFIRE, 27_222_228L, Items.TRAPPED_CHEST, 42_000_009L,
              Items.COMPOSTER, 104_761_927L);
          for (var entry : expected.entrySet()) {
            check.accept("mixed stock exact " + entry.getKey() + " count=" + values.get(entry.getKey()), entry.getValue().equals(values.get(entry.getKey())));
          }
        }
        if (scenario.getKey().equals("mixed_3b")) {
          var expected = Map.of(Items.WOODEN_AXE, 9_187_500_008L, Items.WOODEN_PICKAXE, 9_187_500_008L,
              Items.STAINED_GLASS_PANE.white(), 8_000_000_000L);
          for (var entry : expected.entrySet()) check.accept("3 billion mixed stock exact " + entry.getKey() + " count=" + values.get(entry.getKey()), entry.getValue().equals(values.get(entry.getKey())));
        }
        var field = UltsRuntime.class.getDeclaredField("craftableViewPool"); field.setAccessible(true);
        var queriedPool = (UltsCraftPool) field.get(runtime);
        check.accept("huge " + scenario.getKey() + " capacity queries preserve every source count, reservation and rollback mark", queriedPool.mark() == originalMark
            && scenario.getValue().stream().allMatch(row -> queriedPool.amount(row.template()) == row.amount() && queriedPool.usableAmount(row.template()) == row.amount()));
      }
      Files.write(Path.of("huge-craft-sweep.csv"), diagnostics);
      check.accept("every vanilla recipe result settles for huge spruce, mixed and legacy stock", all);
      for (int trial = 0; trial < 12; trial++) {
        var pool = UltsCraftPool.of(scenarios.get("mixed_3b")); int mark = pool.mark();
        var resolver = UltsCraftResolver.of(pool, UltsCraftingMode.ALL, true);
        long amount = UltsCraftResolver.UNKNOWN;
        long[] slices = {100_000L, 1_000_000L, 5_000_000L, 30_000_000L};
        for (int frame = 0; frame < 200 && amount == UltsCraftResolver.UNKNOWN; frame++)
          amount = resolver.capacity(Items.STAINED_GLASS_PANE.white().getDefaultInstance(), System.nanoTime() + slices[(frame + trial) % slices.length]);
        check.accept("3 billion white panes converge across interrupted cold-start slices trial=" + trial,
            amount == 8_000_000_000L && pool.mark() == mark && pool.usableAmount(Items.GLASS.getDefaultInstance()) == 3_000_000_003L);
      }
      checkLatePage(server, loaded, check);
    } finally { UltsConfigManager.getInstance().update(previous); }
  }
  private static void expireBudget(UltsRuntime runtime, long tick) throws Exception {
    var frame = UltsRuntime.class.getDeclaredField("craftableBudgetTick"); frame.setAccessible(true); frame.setLong(runtime, tick);
    var deadline = UltsRuntime.class.getDeclaredField("craftableDeadline"); deadline.setAccessible(true); deadline.setLong(runtime, Long.MIN_VALUE);
  }
  private static void checkLatePage(MinecraftServer server, UltsState state, BiConsumer<String, Boolean> check) throws Exception {
    var player = UltsNativeQuantity.player(server, new UltsNativeQuantity.CapturedConnection());
    state.setViewProfile(player.getUUID(), new UltsViewProfile("", 0, 0, "spruce", UltsItemVisibility.AVAILABLE));
    long[] tick = {10_000}; var runtime = new UltsRuntime(server, state, () -> tick[0]);
    expireBudget(runtime, tick[0]); var page = UltsStorageSGUI.open(player, runtime);
    for (int frame = 0; frame < 250; frame++) { tick[0]++; expireBudget(runtime, tick[0]); UltsStorageSGUI.refreshAll(runtime); }
    check.accept("cold storage page keeps its calculation refresh alive beyond 200 ticks", runtime.craftablePending());
    boolean ready = false;
    for (int frame = 0; frame < 100 && !ready; frame++) {
      tick[0]++; UltsStorageSGUI.refreshAll(runtime);
      for (int slot = 0; slot < page.getVirtualSize(); slot++) {
        var element = page.getGuiElement(slot);
        if (element == null || !element.getItemStack().is(Items.SPRUCE_SLAB)) continue;
        var lore = element.getItemStack().get(DataComponents.LORE);
        if (lore != null) ready = lore.lines().stream().map(line -> line.getString()).anyMatch(line -> line.contains("24,000,000,024"));
      }
    }
    check.accept("old-save spruce page replaces calculating with the exact 24 billion slab count", ready);
    check.accept("old-save calculation and repeated rendering preserve every stored log", UltsRuntime.storedAmount(Items.SPRUCE_LOG.getDefaultInstance(), state.items()) == 3_000_000_003L);
    page.close();
  }
  private static UltsStoredView row(Item item, long amount) { return new UltsStoredView(item.getDefaultInstance(), amount, false); }
  private static String csv(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
}
