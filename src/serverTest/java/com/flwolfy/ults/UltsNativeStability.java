package com.flwolfy.ults;

import static com.flwolfy.ults.UltsNativeQuantity.*;

import com.flwolfy.ults.data.config.*;
import com.flwolfy.ults.data.state.*;
import com.flwolfy.ults.display.*;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** Real SGUI callbacks, live vanilla crafting and stable/pending transitions without a client. */
public final class UltsNativeStability {
  private static String lore(SimpleGui gui, int slot) {
    var lines = gui.getGuiElement(slot).getItemStack().get(DataComponents.LORE);
    return lines == null ? "" : lines.lines().stream().map(line -> line.getString())
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private static int row(SimpleGui gui, Item item) {
    for (int slot = 0; slot < gui.getVirtualSize(); slot++) {
      var element = gui.getGuiElement(slot);
      if (element != null && element.getItemStack().is(item) && lore(gui, slot).contains("Stored:")) return slot;
    }
    throw new IllegalStateException("Missing stored row for " + item);
  }

  private static void refresh(UltsRuntime runtime) {
    UltsStorageSGUI.refreshAll(runtime);
    UltsWithdrawSGUI.refreshAll(runtime);
    UltsTakeAllSGUI.refreshAll(runtime);
    UltsBagSGUI.refreshAll(runtime);
  }

  public static void check(MinecraftServer server, BiConsumer<String, Boolean> check) throws Exception {
    var previous = UltsConfigManager.getInstance().data();
    try {
      overflow(previous, true);
      long[] tick = {1000};
      var state = new UltsState();
      state.deposit(new ItemStack(Items.STICK, 100));
      state.deposit(Items.OAK_LOG.getDefaultInstance());
      state.deposit(Items.CRAFTING_TABLE.getDefaultInstance());
      state.deposit(new ItemStack(Items.STONE, 64));
      var sword = new ItemStack(Items.DIAMOND_SWORD, 2);
      sword.set(DataComponents.CUSTOM_NAME, Component.literal("pending bag acceptance"));
      state.deposit(sword);
      var runtime = new UltsRuntime(server, state, () -> tick[0]);
      var sender = player(server, new CapturedConnection());
      var receiver = player(server, new CapturedConnection());
      var allReceiver = player(server, new CapturedConnection());
      var rowReceiver = player(server, new CapturedConnection());
      var zeroReceiver = player(server, new CapturedConnection());
      var bagReceiver = player(server, new CapturedConnection());
      var gui = UltsWithdrawSGUI.open(sender, runtime, Items.STICK.getDefaultInstance(), () -> {});
      gui.receiveInput("109");
      check.accept("quantity above stored plus craftable is a shortage barrier", gui.getGuiElement(2).getItemStack().is(Items.BARRIER)
          && lore(gui, 2).contains("108") && active(runtime) == 0);
      gui.receiveInput("108");
      check.accept("exact stored plus craftable quantity is accepted", gui.getGuiElement(2).getItemStack().is(Items.DYE.lime()));
      var oldAmountConfirm = gui.getGuiElement(2);
      gui.receiveInput("109");
      oldAmountConfirm.getGuiCallback().click(2, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, gui);
      check.accept("an old valid callback cannot submit a newly entered excessive quantity", active(runtime) == 0
          && UltsRuntime.storedAmount(Items.STICK.getDefaultInstance(), state.items()) == 100);
      gui.receiveInput("80");
      var other = UltsWithdrawSGUI.open(receiver, runtime, Items.STICK.getDefaultInstance(), () -> {});
      other.receiveInput("1");
      var staleConfirm = other.getGuiElement(2);
      var all = UltsTakeAllSGUI.openForItem(allReceiver, runtime, Items.STICK.getDefaultInstance(), () -> {});
      var staleAll = all.getGuiElement(6);
      state.setViewProfile(rowReceiver.getUUID(), new UltsViewProfile("", 0, 0, "stick", UltsItemVisibility.AVAILABLE));
      state.setViewProfile(zeroReceiver.getUUID(), new UltsViewProfile("", 0, 0, "stone", UltsItemVisibility.AVAILABLE));
      tick[0]++;
      for (int count = 0; count < 20 && runtime.craftingAmountFresh(Items.STONE.getDefaultInstance(), state.items()).pending(); count++) tick[0]++;
      var storage = UltsStorageSGUI.open(rowReceiver, runtime);
      var zeroStorage = UltsStorageSGUI.open(zeroReceiver, runtime);
      UltsBagSGUI.open(bagReceiver, runtime, Items.DIAMOND_SWORD);
      var openBags = UltsBagSGUI.class.getDeclaredField("OPEN_MENUS");
      openBags.setAccessible(true);
      var bag = (UltsBagSGUI) ((java.lang.ref.WeakReference<?>) ((java.util.List<?>) openBags.get(null)).getLast()).get();
      int bagSlot = row(bag, Items.DIAMOND_SWORD);
      var staleBag = bag.getGuiElement(bagSlot);
      int slot = row(storage, Items.STICK);
      var staleRow = storage.getGuiElement(slot);
      check.accept("settled zero craftable amount has no craftable lore", !lore(zeroStorage, row(zeroStorage, Items.STONE)).contains("Craftable:"));
      tick[0]++;
      click(gui, 2);
      pump(runtime, tick);
      refresh(runtime);
      String mode = lore(other, 1);
      check.accept("quantity menu shows both stored and craftable as calculating", mode.contains("Stored: Calculating")
          && mode.contains("Craftable: Calculating") && other.getGuiElement(2).getItemStack().is(Items.BARRIER));
      check.accept("storage row shows both amounts as calculating", lore(storage, row(storage, Items.STICK)).contains("Stored: Calculating")
          && lore(storage, row(storage, Items.STICK)).contains("Craftable: Calculating"));
      check.accept("take-everything waits for the same stock calculation", all.getGuiElement(6).getItemStack().is(Items.BARRIER)
          && lore(all, 4).contains("Stored: Calculating"));
      check.accept("unrelated bag rows retain their amounts during stick withdrawal", lore(bag, row(bag, Items.DIAMOND_SWORD)).contains("Stored: 2")
          && !lore(bag, row(bag, Items.DIAMOND_SWORD)).contains("Calculating"));
      check.accept("unrelated stone rows are not marked calculating", !lore(zeroStorage, row(zeroStorage, Items.STONE)).contains("Calculating"));
      long before = UltsRuntime.storedAmount(Items.STICK.getDefaultInstance(), state.items());
      staleConfirm.getGuiCallback().click(2, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, other);
      staleAll.getGuiCallback().click(6, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, all);
      staleRow.getGuiCallback().click(slot, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, storage);
      staleRow.getGuiCallback().click(slot, ClickType.MOUSE_LEFT_SHIFT, ContainerInput.PICKUP, storage);
      check.accept("old quantity, bulk and quick callbacks cannot bypass pending", active(runtime) == 1
          && backpack(receiver) == 0 && backpack(allReceiver) == 0 && backpack(rowReceiver) == 0 && backpack(bagReceiver) == 0
          && UltsRuntime.storedAmount(Items.STICK.getDefaultInstance(), state.items()) == before);
      pump(runtime, tick);
      refresh(runtime);
      check.accept("existing delivery stream finishes while new requests are blocked", active(runtime) == 0 && backpack(sender) == 80);
      staleBag.getGuiCallback().click(bagSlot, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, bag);
      check.accept("unrelated bag withdrawal proceeds during another item's quiet window", backpack(bagReceiver) == 1
          && runtime.stockPending(Items.STICK.getDefaultInstance(), runtime.storedItems()));
      bag.close();
      for (int count = 1; count < runtime.stabilityQuietTicks(); count++) {
        tick[0]++;
        refresh(runtime);
        if (!other.getGuiElement(2).getItemStack().is(Items.BARRIER)) throw new IllegalStateException("Pending flickered before quiet window elapsed");
      }
      check.accept("pending never flickers during the entire quiet window", runtime.stockPending());
      tick[0]++;
      refresh(runtime);
      check.accept("stock quiet window uses the configured interval independently of solver workload", !runtime.stockPending());
      // Other open pages share the crafting budget; a deferred exact answer can require more ticks.
      for (int count = 0; count < 100 && !other.getGuiElement(2).getItemStack().is(Items.DYE.lime()); count++) {
        tick[0]++;
        refresh(runtime);
      }
      check.accept("unchanged stock automatically re-enables confirmation once settled and calculated", other.getGuiElement(2).getItemStack().is(Items.DYE.lime())
          && !lore(other, 1).contains("Calculating") && lore(other, 1).contains("20"));
      for (int count = 0; count < 100 && lore(zeroStorage, row(zeroStorage, Items.STONE)).contains("Calculating"); count++) {
        tick[0]++;
        refresh(runtime);
      }
      check.accept("zero crafting lore stays hidden after automatic refresh", !lore(zeroStorage, row(zeroStorage, Items.STONE)).contains("Craftable:"));
      other.close(); all.close(); storage.close(); zeroStorage.close(); bag.close();

      checkStoredAndCraftedStages(server, check);

      // No crafting is involved: external stock changes alone must trigger the same pending state.
      var enabled = UltsConfigManager.getInstance().data();
      var input = enabled.input();
      UltsConfigManager.getInstance().update(new UltsConfigData(enabled.general(), new UltsConfigData.Input(
          input.permissionLevel(), input.maxBindings(), input.drainInterval(), input.multiBlockContainers(),
          UltsCraftingMode.DISABLED, true, true, 36, 64), enabled.special()));
      state = new UltsState(); state.deposit(new ItemStack(Items.STONE, 10));
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      gui = UltsWithdrawSGUI.open(receiver, runtime, Items.STONE.getDefaultInstance(), () -> {});
      gui.receiveInput("5");
      var stale = gui.getGuiElement(2);
      state.deposit(Items.STONE.getDefaultInstance());
      stale.getGuiCallback().click(2, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, gui);
      check.accept("external deposits block fresh confirmation even with crafting disabled", active(runtime) == 0
          && gui.getGuiElement(2).getItemStack().is(Items.BARRIER) && lore(gui, 1).contains("Stored: Calculating")
          && !lore(gui, 1).contains("Craftable:"));
      tick[0] += runtime.stabilityQuietTicks();
      refresh(runtime);
      check.accept("disabled crafting does not prevent quantities from settling", gui.getGuiElement(2).getItemStack().is(Items.DYE.lime()));
      gui.close();

      // Both filled-box contents and empty boxes must suffice for the complete request.
      overflow(previous, true);
      state = new UltsState();
      state.deposit(new ItemStack(Items.STONE, 3456));
      state.deposit(Items.SHULKER_BOX.getDefaultInstance());
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      gui = UltsWithdrawSGUI.open(receiver, runtime, Items.STONE.getDefaultInstance(), () -> {});
      gui.receiveInput("2"); click(gui, 1);
      check.accept("a full-box request is blocked when only one empty box exists", gui.getGuiElement(2).getItemStack().is(Items.BARRIER)
          && lore(gui, 2).contains("Insufficient"));
      gui.receiveInput("1");
      check.accept("a feasible full-box request still has a confirmation", gui.getGuiElement(2).getItemStack().is(Items.DYE.lime()));
      gui.close();

      // A fresh remote click notices a physical change before the cached display has been refreshed.
      var config = UltsConfigManager.getInstance().data();
      UltsConfigManager.getInstance().update(new UltsConfigData(new UltsConfigData.General("en_us",
          config.general().itemVisibility(), UltsStorageMode.REMOTE), config.input(), config.special()));
      var pos = new BlockPos(34, 64, 4);
      server.overworld().getChunk(2, 0);
      server.overworld().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
      var container = (Container) server.overworld().getBlockEntity(pos);
      container.setItem(0, new ItemStack(Items.STONE, 10));
      state = new UltsState(); state.addBinding(new UltsBinding("pending", "minecraft:overworld", 34, 64, 4, "test"));
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      gui = UltsWithdrawSGUI.open(receiver, runtime, Items.STONE.getDefaultInstance(), () -> {});
      gui.receiveInput("5"); stale = gui.getGuiElement(2);
      container.setItem(0, new ItemStack(Items.STONE, 9));
      stale.getGuiCallback().click(2, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, gui);
      check.accept("live remote change blocks an old confirmation without physical extraction", active(runtime) == 0
          && container.getItem(0).getCount() == 9 && gui.getGuiElement(2).getItemStack().is(Items.BARRIER));
      tick[0] += runtime.stabilityQuietTicks();
      refresh(runtime);
      check.accept("old remote slices cannot keep a settled request oscillating", gui.getGuiElement(2).getItemStack().is(Items.DYE.lime())
          && lore(gui, 1).contains("9") && !lore(gui, 1).contains("Calculating"));
      click(gui, 2);
      for (int count = 0; count < 10 && active(runtime) > 0; count++) pump(runtime, tick);
      check.accept("settled remote request delivers five and leaves four", backpack(receiver) == 5 && container.getItem(0).getCount() == 4 && active(runtime) == 0);
    } finally { UltsConfigManager.getInstance().update(previous); }
  }

  private static void checkStoredAndCraftedStages(MinecraftServer server, BiConsumer<String, Boolean> check) throws Exception {
    var config = UltsConfigManager.getInstance().data();
    var input = config.input();
    UltsConfigManager.getInstance().update(new UltsConfigData(config.general(), new UltsConfigData.Input(
        input.permissionLevel(), input.maxBindings(), 7, input.multiBlockContainers(), UltsCraftingMode.ALL,
        true, true, 36, 1000), config.special()));
    long[] tick = {2000};
    var state = new UltsState();
    state.deposit(new ItemStack(Items.OAK_PLANKS, 2000));
    state.deposit(new ItemStack(Items.OAK_LOG, 1000));
    state.deposit(new ItemStack(Items.STONE, 64));
    state.deposit(Items.CRAFTING_TABLE.getDefaultInstance());
    var runtime = new UltsRuntime(server, state, () -> tick[0]);
    var sender = player(server, new CapturedConnection());
    var receiver = player(server, new CapturedConnection());
    var unrelated = player(server, new CapturedConnection());
    var logGui = UltsWithdrawSGUI.open(receiver, runtime, Items.OAK_LOG.getDefaultInstance(), () -> {});
    logGui.receiveInput("1");
    var staleLog = logGui.getGuiElement(2);
    var stoneGui = UltsWithdrawSGUI.open(unrelated, runtime, Items.STONE.getDefaultInstance(), () -> {});
    stoneGui.receiveInput("1");
    var gui = UltsWithdrawSGUI.open(sender, runtime, Items.OAK_PLANKS.getDefaultInstance(), () -> {});
    gui.receiveInput("3000");
    for (int attempt = 0; attempt < 100 && !gui.getGuiElement(2).getItemStack().is(Items.DYE.lime()); attempt++) {
      tick[0]++; refresh(runtime);
    }
    check.accept("3000-plank request accepts 2000 stored plus real crafting", gui.getGuiElement(2).getItemStack().is(Items.DYE.lime()));
    tick[0]++;
    click(gui, 2);
    pump(runtime, tick); refresh(runtime);
    check.accept("first stored 1000 planks leave logs numeric and withdrawable", logGui.getGuiElement(2).getItemStack().is(Items.DYE.lime())
        && !lore(logGui, 1).contains("Calculating") && UltsRuntime.storedAmount(Items.OAK_LOG.getDefaultInstance(), state.items()) == 1000);
    pump(runtime, tick); refresh(runtime);
    check.accept("all 2000 stored planks leave logs unblocked and untouched", logGui.getGuiElement(2).getItemStack().is(Items.DYE.lime())
        && !lore(logGui, 1).contains("Calculating") && UltsRuntime.storedAmount(Items.OAK_LOG.getDefaultInstance(), state.items()) == 1000
        && UltsRuntime.storedAmount(Items.OAK_PLANKS.getDefaultInstance(), state.items()) == 0);
    pump(runtime, tick); refresh(runtime);
    check.accept("only actual crafted delivery marks consumed logs calculating", logGui.getGuiElement(2).getItemStack().is(Items.BARRIER)
        && lore(logGui, 1).contains("Stored: Calculating") && UltsRuntime.storedAmount(Items.OAK_LOG.getDefaultInstance(), state.items()) == 750
        && active(runtime) == 0);
    staleLog.getGuiCallback().click(2, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, logGui);
    check.accept("old raw-material confirmation cannot bypass actual crafting mutation", active(runtime) == 0 && backpack(receiver) == 0
        && UltsRuntime.storedAmount(Items.OAK_LOG.getDefaultInstance(), state.items()) == 750);
    check.accept("unrelated item remains numeric and confirmable through both phases", stoneGui.getGuiElement(2).getItemStack().is(Items.DYE.lime())
        && !lore(stoneGui, 1).contains("Calculating"));
    click(stoneGui, 2); pump(runtime, tick); refresh(runtime);
    check.accept("unrelated withdrawal finishes while consumed logs still wait", backpack(unrelated) == 1
        && logGui.getGuiElement(2).getItemStack().is(Items.BARRIER) && active(runtime) == 0);
    check.accept("local quiet time derives from drain interval seven", runtime.stabilityQuietTicks() == 8);
    tick[0] += runtime.stabilityQuietTicks(); refresh(runtime);
    check.accept("consumed logs recover after their configured quiet window", logGui.getGuiElement(2).getItemStack().is(Items.DYE.lime())
        && !lore(logGui, 1).contains("Calculating"));
    gui.close(); logGui.close(); stoneGui.close();
    UltsConfigManager.getInstance().update(config);
  }
}
