package com.flwolfy.ults;

import static com.flwolfy.ults.UltsNativeQuantity.*;
import com.flwolfy.ults.data.config.*;
import com.flwolfy.ults.data.state.*;
import com.flwolfy.ults.display.*;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.function.BiConsumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;

/** Real vanilla recipe counts for the user's large spruce stock. */
public final class UltsNativeLargeCraft {
  public static void check(MinecraftServer server, BiConsumer<String, Boolean> check) throws Exception {
    var previous = UltsConfigManager.getInstance().data();
    try {
      var input = previous.input();
      UltsConfigManager.getInstance().update(new UltsConfigData(new UltsConfigData.General(
          "en_us", UltsItemVisibility.AVAILABLE, UltsStorageMode.VOID), new UltsConfigData.Input(
          input.permissionLevel(), input.maxBindings(), input.drainInterval(), input.multiBlockContainers(),
          UltsCraftingMode.ALL, true, true, 36, 64), previous.special()));
      var state = new UltsState();
      state.deposit(new ItemStack(Items.SPRUCE_LOG, 40000));
      state.deposit(Items.CRAFTING_TABLE.getDefaultInstance());
      long[] tick = {1000};
      var runtime = new UltsRuntime(server, state, () -> tick[0]);
      Item[] targets = {Items.SPRUCE_SLAB, Items.SPRUCE_STAIRS, Items.SPRUCE_FENCE_GATE, Items.SPRUCE_FENCE,
          Items.SPRUCE_TRAPDOOR, Items.SPRUCE_DOOR, Items.SPRUCE_PLANKS, Items.SPRUCE_PRESSURE_PLATE};
      long[] expected = {319998, 106664, 40000, 96000, 53332, 79998, 160000, 80000};
      boolean[] ready = new boolean[targets.length];
      long[] values = new long[targets.length];
      for (int frame = 0; frame < 100; frame++) {
        tick[0]++;
        for (int index = 0; index < targets.length; index++) {
          var amount = runtime.craftingAmount(targets[index].getDefaultInstance(), state.items());
          ready[index] = !amount.pending(); values[index] = amount.amount();
        }
        if (java.util.stream.IntStream.range(0, ready.length).allMatch(index -> ready[index])) break;
      }
      for (int index = 0; index < targets.length; index++) check.accept("40000 spruce logs settle exact " + targets[index]
          + " count=" + values[index], ready[index] && values[index] == expected[index]);
      check.accept("large capacity queries never consume the source stock", UltsRuntime.storedAmount(
          Items.SPRUCE_LOG.getDefaultInstance(), state.items()) == 40000);
      checkStoragePage(server, state, tick, targets, check);
      checkScreens(server, runtime, state, tick, check);
    } finally { UltsConfigManager.getInstance().update(previous); }
  }

  private static String lore(SimpleGui gui, int slot) {
    var lines = gui.getGuiElement(slot).getItemStack().get(DataComponents.LORE);
    return lines == null ? "" : lines.lines().stream().map(line -> line.getString()).collect(java.util.stream.Collectors.joining("\n"));
  }

  private static void click(SimpleGui gui, int slot) {
    gui.getGuiElement(slot).getGuiCallback().click(slot, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, gui);
  }

  private static String rowLore(SimpleGui gui, Item item) {
    for (int slot = 0; slot < gui.getVirtualSize(); slot++) {
      var element = gui.getGuiElement(slot);
      if (element != null && element.getItemStack().is(item) && lore(gui, slot).contains("Stored:")) return lore(gui, slot);
    }
    return "";
  }

  private static void checkStoragePage(MinecraftServer server, UltsState state, long[] tick,
      Item[] targets, BiConsumer<String, Boolean> check) throws Exception {
    var receiver = player(server, new CapturedConnection());
    state.setViewProfile(receiver.getUUID(), new UltsViewProfile("", 0, 0, "spruce", UltsItemVisibility.AVAILABLE));
    var runtime = new UltsRuntime(server, state, () -> tick[0]);
    var storage = UltsStorageSGUI.open(receiver, runtime);
    for (int frame = 0; frame < 100; frame++) {
      tick[0]++;
      UltsStorageSGUI.refreshAll(runtime);
      if (java.util.Arrays.stream(targets).allMatch(item -> rowLore(storage, item).contains("Craftable:")
          && !rowLore(storage, item).contains("Calculating"))) break;
    }
    for (Item item : targets) check.accept("cold spruce storage page settles " + item,
        rowLore(storage, item).contains("Craftable:") && !rowLore(storage, item).contains("Calculating"));
    storage.close();
  }

  private static void checkScreens(MinecraftServer server, UltsRuntime runtime, UltsState state,
      long[] tick, BiConsumer<String, Boolean> check) throws Exception {
    var connection = new CapturedConnection();
    var receiver = player(server, connection);
    var quantity = UltsWithdrawSGUI.open(receiver, runtime, Items.SPRUCE_SLAB.getDefaultInstance(), () -> {});
    quantity.receiveInput("64");
    var stale = quantity.getGuiElement(2);
    state.deposit(Items.SPRUCE_LOG.getDefaultInstance());
    for (String text : java.util.List.of("1", "64", "10000", "abc", "")) {
      quantity.receiveInput(text);
      check.accept("pending quantity input " + text + " has a calculating barrier", quantity.getGuiElement(2).getItemStack().is(Items.BARRIER)
          && lore(quantity, 2).contains("calculat"));
      var packet = connection.packets.stream().filter(ClientboundContainerSetSlotPacket.class::isInstance)
          .map(ClientboundContainerSetSlotPacket.class::cast).filter(value -> value.getSlot() == 2)
          .reduce((first, last) -> last).orElseThrow();
      check.accept("pending quantity input " + text + " sends a nonempty result with a current revision", packet.getItem().is(Items.BARRIER)
          && packet.getStateId() > 0);
    }
    quantity.receiveInput("64");
    int before = connection.packets.size();
    UltsWithdrawSGUI.refreshAll(runtime);
    check.accept("unchanged pending result is explicitly resent on refresh", connection.packets.size() > before
        && quantity.getGuiElement(2).getItemStack().is(Items.BARRIER));
    stale.getGuiCallback().click(2, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, quantity);
    check.accept("old quantity confirmation cannot withdraw during large-stock recalculation", active(runtime) == 0
        && backpack(receiver) == 0 && UltsRuntime.storedAmount(Items.SPRUCE_LOG.getDefaultInstance(), state.items()) == 40001);
    tick[0] += runtime.stabilityQuietTicks();
    for (int frame = 0; frame < 100 && !quantity.getGuiElement(2).getItemStack().is(Items.DYE.lime()); frame++) {
      tick[0]++; UltsWithdrawSGUI.refreshAll(runtime);
    }
    check.accept("quantity recovers after the large-stock calculation completes", quantity.getGuiElement(2).getItemStack().is(Items.DYE.lime()));
    quantity.close();

    var bulk = UltsTakeAllSGUI.openForItem(receiver, runtime, Items.SPRUCE_SLAB.getDefaultInstance(), () -> {});
    check.accept("no-stock item mode has a barrier but keeps its toggle", bulk.getGuiElement(6).getItemStack().is(Items.BARRIER));
    click(bulk, 4);
    check.accept("no-stock item can switch to its feasible crafting mode", bulk.getGuiElement(4).getItemStack().is(Items.CRAFTING_TABLE)
        && bulk.getGuiElement(6).getItemStack().is(Items.DYE.lime()));
    var oldCrafting = bulk.getGuiElement(6);
    click(bulk, 4);
    oldCrafting.getGuiCallback().click(6, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, bulk);
    check.accept("old crafting confirmation cannot bypass current empty item mode", active(runtime) == 0 && bulk.getGuiElement(6).getItemStack().is(Items.BARRIER));
    state.deposit(Items.SPRUCE_SLAB.getDefaultInstance());
    UltsTakeAllSGUI.refreshAll(runtime);
    click(bulk, 4);
    check.accept("bulk mode can toggle while pending and confirmation stays blocked", bulk.getGuiElement(4).getItemStack().is(Items.CRAFTING_TABLE)
        && bulk.getGuiElement(6).getItemStack().is(Items.BARRIER));
    click(bulk, 4);
    check.accept("pending bulk mode can switch back to item", bulk.getGuiElement(4).getItemStack().is(Items.SPRUCE_SLAB));
    bulk.close();

    state = new UltsState(); state.deposit(new ItemStack(Items.STONE, 64));
    runtime = new UltsRuntime(server, state, () -> tick[0]);
    bulk = UltsTakeAllSGUI.openForItem(receiver, runtime, Items.STONE.getDefaultInstance(), () -> {});
    var oldItem = bulk.getGuiElement(6);
    click(bulk, 4);
    check.accept("stored uncraftable item switches to crafting with a barrier", bulk.getGuiElement(4).getItemStack().is(Items.CRAFTING_TABLE)
        && bulk.getGuiElement(6).getItemStack().is(Items.BARRIER));
    oldItem.getGuiCallback().click(6, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, bulk);
    check.accept("old item confirmation cannot empty stock in unavailable crafting mode", active(runtime) == 0 && UltsRuntime.storedAmount(Items.STONE.getDefaultInstance(), state.items()) == 64);
    click(bulk, 4);
    check.accept("unavailable crafting mode switches back to an available item", bulk.getGuiElement(6).getItemStack().is(Items.DYE.lime()));
    click(bulk, 6); pump(runtime, tick);
    check.accept("item mode still delivers all stored items after switching", active(runtime) == 0 && backpack(receiver) == 64
        && UltsRuntime.storedAmount(Items.STONE.getDefaultInstance(), state.items()) == 0);

    bulk = UltsTakeAllSGUI.openForItem(receiver, runtime, Items.STONE.getDefaultInstance(), () -> {});
    check.accept("empty stock opens item mode with a barrier", bulk.getGuiElement(6).getItemStack().is(Items.BARRIER));
    click(bulk, 4);
    check.accept("empty stock switches to unavailable crafting mode with a barrier", bulk.getGuiElement(4).getItemStack().is(Items.CRAFTING_TABLE)
        && bulk.getGuiElement(6).getItemStack().is(Items.BARRIER));
    click(bulk, 4);
    check.accept("both unavailable modes still allow switching back", bulk.getGuiElement(4).getItemStack().is(Items.STONE)
        && bulk.getGuiElement(6).getItemStack().is(Items.BARRIER));
    bulk.close();

    var configuration = UltsConfigManager.getInstance().data();
    var input = configuration.input();
    UltsConfigManager.getInstance().update(new UltsConfigData(configuration.general(), new UltsConfigData.Input(
        input.permissionLevel(), input.maxBindings(), input.drainInterval(), input.multiBlockContainers(),
        UltsCraftingMode.DISABLED, input.allowFullInventory(), input.allowBulkWithdrawal(), input.bulkWithdrawalStacks(),
        input.withdrawalRate()), configuration.special()));
    state.deposit(Items.STONE.getDefaultInstance());
    runtime = new UltsRuntime(server, state, () -> tick[0]);
    bulk = UltsTakeAllSGUI.openForItem(receiver, runtime, Items.STONE.getDefaultInstance(), () -> {});
    click(bulk, 4);
    check.accept("disabled crafting still allows selecting crafting mode with a barrier", bulk.getGuiElement(4).getItemStack().is(Items.CRAFTING_TABLE)
        && bulk.getGuiElement(6).getItemStack().is(Items.BARRIER));
    click(bulk, 4);
    check.accept("disabled crafting switches back to available item mode", bulk.getGuiElement(6).getItemStack().is(Items.DYE.lime()));
    bulk.close();
  }
}
