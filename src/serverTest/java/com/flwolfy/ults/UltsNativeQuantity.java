package com.flwolfy.ults;

import com.flwolfy.ults.data.config.*;
import com.flwolfy.ults.data.state.*;
import com.flwolfy.ults.display.*;
import com.mojang.authlib.GameProfile;
import eu.pb4.sgui.api.ClickType;
import io.netty.channel.ChannelFutureListener;
import java.util.*;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Blocks;

/** Real server SGUI input/clicks and streams, using captured packets instead of a game client. */
public final class UltsNativeQuantity {
  static void pump(UltsRuntime runtime, long[] tick) {
    tick[0]++;
    runtime.streams().tick();
    runtime.storedItems();
  }
  static final class CapturedConnection extends Connection {
    final List<Packet<?>> packets = new ArrayList<>();
    CapturedConnection() { super(PacketFlow.SERVERBOUND); }
    @Override public boolean isConnected() { return true; }
    @Override public void send(Packet<?> packet) { packets.add(packet); }
    @Override public void send(Packet<?> packet, ChannelFutureListener listener) { packets.add(packet); }
    @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { packets.add(packet); }
  }

  static ServerPlayer player(MinecraftServer server, CapturedConnection connection) {
    var profile = new GameProfile(UUID.randomUUID(), "UltsQuantity");
    var player = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
    player.connection = new ServerGamePacketListenerImpl(server, connection, player,
        CommonListenerCookie.createInitial(profile, false));
    player.setPos(8.5, 64, 8.5);
    return player;
  }

  static void click(UltsWithdrawSGUI gui, int slot) {
    gui.getGuiElement(slot).getGuiCallback().click(slot, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, gui);
  }

  static int active(UltsRuntime runtime) throws ReflectiveOperationException {
    var field = UltsTakeAllStreams.class.getDeclaredField("streams");
    field.setAccessible(true);
    return ((List<?>) field.get(runtime.streams())).size();
  }

  static long backpack(ServerPlayer player) {
    long count = 0;
    for (int slot = 0; slot < 36; slot++) count += player.getInventory().getItem(slot).getCount();
    return count;
  }

  static void overflow(UltsConfigData initial, boolean enabled) {
    var input = initial.input();
    UltsConfigManager.getInstance().update(new UltsConfigData(
        new UltsConfigData.General("en_us", initial.general().itemVisibility(), UltsStorageMode.VOID),
        new UltsConfigData.Input(input.permissionLevel(), input.maxBindings(), input.drainInterval(),
            input.multiBlockContainers(), UltsCraftingMode.ALL, enabled, true, 36, 64), initial.special()));
  }

  public static void check(MinecraftServer server, BiConsumer<String, Boolean> check) throws Exception {
    var previous = UltsConfigManager.getInstance().data();
    var connection = new CapturedConnection();
    var player = player(server, connection);
    var target = Items.STONE.getDefaultInstance();
    target.set(DataComponents.CUSTOM_NAME, Component.literal("ults quantity acceptance"));
    List<ItemEntity> drops = new ArrayList<>();
    ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
      if (entity instanceof ItemEntity item && (ItemStack.isSameItemSameComponents(item.getItem(), target)
          || UltsBoxes.isShulker(item.getItem()))) drops.add(item);
    });
    long[] tick = {1000};
    try {
      overflow(previous, true);
      var state = new UltsState();
      state.deposit(new ItemStack(Items.STICK, 100));
      state.deposit(new ItemStack(Items.OAK_LOG, 64));
      state.deposit(Items.CRAFTING_TABLE.getDefaultInstance());
      var runtime = new UltsRuntime(server, state, () -> tick[0]);
      var gui = UltsWithdrawSGUI.open(player, runtime, Items.STICK.getDefaultInstance(), () -> {});
      String lore = gui.getGuiElement(1).getItemStack().get(DataComponents.LORE).lines().toString();
      var amount = runtime.craftingAmount(Items.STICK.getDefaultInstance(), state.items());
      check.accept("stocked vanilla sticks still report 512 additional craftable sticks", amount.amount() == 512);
      check.accept("quantity mode icon displays crafting amount with stored output", lore.contains("512"));
      gui.close();

      state = new UltsState();
      state.deposit(target.copyWithCount(10_000));
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      gui = UltsWithdrawSGUI.open(player, runtime, target, () -> {});
      for (String input : List.of("2305", "10000", "3000000000", "9223372036854775807",
          "9223372036854775808", "abc", "")) {
        gui.receiveInput(input);
        check.accept("SGUI action icons survive input " + input,
            !gui.getGuiElement(1).getItemStack().isEmpty() && !gui.getGuiElement(2).getItemStack().isEmpty());
        var packet = connection.packets.stream().filter(ClientboundContainerSetSlotPacket.class::isInstance)
            .map(ClientboundContainerSetSlotPacket.class::cast).filter(value -> value.getSlot() == 2)
            .reduce((first, last) -> last).orElseThrow();
        check.accept("SGUI sends nonempty confirmation packet for " + input, !packet.getItem().isEmpty());
      }
      gui.close();

      overflow(previous, false);
      gui = UltsWithdrawSGUI.open(player, runtime, target, () -> {});
      gui.receiveInput("5000");
      check.accept("overflow off permits the fitting prefix of a large request",
          gui.getGuiElement(2).getItemStack().is(Items.DYE.lime()));
      click(gui, 2);
      check.accept("confirmation starts one stream without removing stock", active(runtime) == 1
          && UltsRuntime.storedAmount(target, state.items()) == 10_000);
      for (int count = 0; count < 100 && active(runtime) > 0; count++) pump(runtime, tick);
      check.accept("overflow off delivers 2304 and retains 7696", backpack(player) == 2304
          && UltsRuntime.storedAmount(target, state.items()) == 7696 && active(runtime) == 0);

      overflow(previous, true);
      tick[0] += runtime.stabilityQuietTicks();
      gui = UltsWithdrawSGUI.open(player, runtime, target, () -> {});
      gui.receiveInput("5000");
      var confirm = gui.getGuiElement(2);
      click(gui, 2);
      confirm.getGuiCallback().click(2, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, gui);
      check.accept("duplicate confirmation retains exactly one stream", active(runtime) == 1);
      pump(runtime, tick);
      check.accept("full backpack delivers only 64 units on first tick", UltsRuntime.storedAmount(target, state.items()) == 7632);
      for (int count = 0; count < 100 && active(runtime) > 0; count++) pump(runtime, tick);
      long ground = drops.stream().filter(item -> ItemStack.isSameItemSameComponents(item.getItem(), target))
          .mapToLong(item -> item.getItem().getCount()).sum();
      check.accept("large quantity completes 5000 dropped units without duplicate stock", ground == 5000
          && UltsRuntime.storedAmount(target, state.items()) == 2696 && active(runtime) == 0);
      drops.forEach(ItemEntity::discard);
      drops.clear();

      var second = player(server, new CapturedConnection());
      state = new UltsState();
      state.deposit(target.copyWithCount(150));
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      for (var receiver : List.of(player, second)) {
        for (int slot = 0; slot < 36; slot++) receiver.getInventory().setItem(slot, ItemStack.EMPTY);
        gui = UltsWithdrawSGUI.open(receiver, runtime, target, () -> {});
        gui.receiveInput("100");
        click(gui, 2);
      }
      for (int count = 0; count < 10 && active(runtime) > 0; count++) pump(runtime, tick);
      check.accept("two players competing for 150 units preserve total quantity",
          backpack(player) + backpack(second) == 150 && UltsRuntime.storedAmount(target, state.items()) == 0);

      state = new UltsState();
      state.deposit(target.copyWithCount(69120));
      state.deposit(new ItemStack(Items.SHULKER_BOX, 40));
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
      gui = UltsWithdrawSGUI.open(player, runtime, target, () -> {});
      gui.receiveInput("40");
      click(gui, 1);
      check.accept("forty full boxes are accepted without a 36-box request limit",
          gui.getGuiElement(2).getItemStack().is(Items.DYE.lime()));
      click(gui, 2);
      for (int count = 0; count < 100 && active(runtime) > 0; count++) pump(runtime, tick);
      List<ItemStack> boxes = new ArrayList<>();
      for (int slot = 0; slot < 36; slot++) boxes.add(player.getInventory().getItem(slot));
      drops.stream().filter(item -> UltsBoxes.isShulker(item.getItem())).forEach(item -> boxes.add(item.getItem()));
      check.accept("boxed stream hands over exactly forty boxes", boxes.size() == 40 && active(runtime) == 0);
      check.accept("all forty boxes retain 1728 contents", boxes.stream().allMatch(box -> UltsBoxes.isShulker(box)
          && box.get(DataComponents.CONTAINER).allItemsCopyStream().mapToLong(ItemStack::getCount).sum() == 1728));
      check.accept("boxed stream consumes contents and empty boxes exactly once", UltsRuntime.storedAmount(target, state.items()) == 0
          && UltsRuntime.storedAmount(Items.SHULKER_BOX.getDefaultInstance(), state.items()) == 0);
      drops.forEach(ItemEntity::discard);
      drops.clear();

      // A take-everything request replaces the same player's quantity request, with one submission.
      state = new UltsState();
      state.deposit(target.copyWithCount(3000));
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
      gui = UltsWithdrawSGUI.open(player, runtime, target, () -> {});
      gui.receiveInput("100");
      click(gui, 2);
      var all = UltsTakeAllSGUI.openForItem(player, runtime, target, () -> {});
      var allConfirm = all.getGuiElement(6);
      long starts = connection.packets.stream().filter(ClientboundSystemChatPacket.class::isInstance)
          .map(ClientboundSystemChatPacket.class::cast).filter(packet -> packet.content().getString().contains("Taking out")).count();
      allConfirm.getGuiCallback().click(6, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, all);
      allConfirm.getGuiCallback().click(6, ClickType.MOUSE_LEFT, ContainerInput.PICKUP, all);
      long started = connection.packets.stream().filter(ClientboundSystemChatPacket.class::isInstance)
          .map(ClientboundSystemChatPacket.class::cast).filter(packet -> packet.content().getString().contains("Taking out")).count();
      check.accept("take-everything replaces quantity and repeated confirmation sends one start", active(runtime) == 1 && started == starts + 1);
      for (int count = 0; count < 100 && active(runtime) > 0; count++) pump(runtime, tick);
      check.accept("replacement retains take-everything's selection limit", backpack(player) == 2304
          && UltsRuntime.storedAmount(target, state.items()) == 696 && drops.isEmpty());

      // Turning crafting off during a stream must leave unused materials in stock.
      state = new UltsState();
      state.deposit(new ItemStack(Items.OAK_LOG, 16));
      state.deposit(Items.CRAFTING_TABLE.getDefaultInstance());
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
      gui = UltsWithdrawSGUI.open(player, runtime, Items.STICK.getDefaultInstance(), () -> {});
      gui.receiveInput("128");
      for (int count = 0; count < 20 && !gui.getGuiElement(2).getItemStack().is(Items.DYE.lime()); count++) {
        tick[0]++;
        gui.receiveInput("128");
      }
      tick[0]++; // A real confirmation receives its own server tick's planning budget.
      click(gui, 2);
      for (int count = 0; count < 20 && backpack(player) == 0 && active(runtime) > 0; count++) pump(runtime, tick);
      check.accept("crafting stream crafts and delivers its first 64-piece batch", backpack(player) == 64
          && UltsRuntime.storedAmount(Items.OAK_LOG.getDefaultInstance(), state.items()) == 8);
      var enabled = UltsConfigManager.getInstance().data();
      var input = enabled.input();
      UltsConfigManager.getInstance().update(new UltsConfigData(enabled.general(), new UltsConfigData.Input(
          input.permissionLevel(), input.maxBindings(), input.drainInterval(), input.multiBlockContainers(),
          UltsCraftingMode.DISABLED, true, true, 36, 64), enabled.special()));
      for (int count = 0; count < 20 && active(runtime) > 0; count++) pump(runtime, tick);
      check.accept("disabled crafting stops the remaining request without consuming ingredients", backpack(player) == 64
          && UltsRuntime.storedAmount(Items.OAK_LOG.getDefaultInstance(), state.items()) == 8 && active(runtime) == 0);

      // Remote mode must use the same stream and physically remove each batch from real containers.
      overflow(previous, true);
      var remoteConfig = UltsConfigManager.getInstance().data();
      UltsConfigManager.getInstance().update(new UltsConfigData(new UltsConfigData.General("en_us",
          remoteConfig.general().itemVisibility(), UltsStorageMode.REMOTE), remoteConfig.input(), remoteConfig.special()));
      state = new UltsState();
      server.overworld().getChunk(1, 0);
      int remaining = 5000;
      for (int index = 0; index < 3; index++) {
        var pos = new BlockPos(28 + index, 64, 4);
        server.overworld().setBlockAndUpdate(pos, Blocks.BARREL.defaultBlockState());
        var container = (Container) server.overworld().getBlockEntity(pos);
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
          int count = Math.min(64, remaining);
          container.setItem(slot, count == 0 ? ItemStack.EMPTY : target.copyWithCount(count));
          remaining -= count;
        }
        state.addBinding(new UltsBinding("quantity remote " + index, "minecraft:overworld",
            pos.getX(), pos.getY(), pos.getZ(), "test"));
      }
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.DIRT, 64));
      gui = UltsWithdrawSGUI.open(player, runtime, target, () -> {});
      gui.receiveInput("5000");
      click(gui, 2);
      check.accept("remote confirmation does not pre-extract the request", UltsRuntime.storedAmount(target, runtime.storedItemsFresh()) == 5000);
      pump(runtime, tick);
      check.accept("remote stream extracts only 64 on its first tick", UltsRuntime.storedAmount(target, runtime.storedItemsFresh()) == 4936);
      for (int count = 0; count < 120 && active(runtime) > 0; count++) pump(runtime, tick);
      ground = drops.stream().filter(item -> ItemStack.isSameItemSameComponents(item.getItem(), target))
          .mapToLong(item -> item.getItem().getCount()).sum();
      check.accept("remote stream delivers exactly 5000 and empties the physical containers", ground == 5000
          && UltsRuntime.storedAmount(target, runtime.storedItemsFresh()) == 0 && active(runtime) == 0);

      // Shared rate applies to explicit quantities; the bulk-only switch and cap do not.
      overflow(previous, true);
      var shared = UltsConfigManager.getInstance().data();
      input = shared.input();
      UltsConfigManager.getInstance().update(new UltsConfigData(shared.general(), new UltsConfigData.Input(
          input.permissionLevel(), input.maxBindings(), input.drainInterval(), input.multiBlockContainers(),
          input.crafting(), true, false, 1, 7), shared.special()));
      state = new UltsState();
      state.deposit(target.copyWithCount(80));
      runtime = new UltsRuntime(server, state, () -> tick[0]);
      for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, ItemStack.EMPTY);
      gui = UltsWithdrawSGUI.open(player, runtime, target, () -> {});
      gui.receiveInput("80");
      check.accept("disabling bulk withdrawal keeps fixed quantity withdrawal available",
          UltsTakeAllSGUI.openForItem(player, runtime, target, () -> {}) == null
              && gui.getGuiElement(2).getItemStack().is(Items.DYE.lime()));
      click(gui, 2);
      pump(runtime, tick);
      check.accept("renamed withdrawalRate limits fixed quantity delivery to seven per tick", backpack(player) == 7
          && UltsRuntime.storedAmount(target, state.items()) == 73);
      for (int count = 0; count < 30 && active(runtime) > 0; count++) pump(runtime, tick);
      check.accept("bulk-only stack cap does not truncate explicit quantity requests", backpack(player) == 80
          && UltsRuntime.storedAmount(target, state.items()) == 0 && active(runtime) == 0);
      var configPath = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("ults.json");
      var saved = com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(configPath))
          .getAsJsonObject().getAsJsonObject("input");
      check.accept("saved configuration uses only the new withdrawal field names",
          saved.get("withdrawalRate").getAsInt() == 7 && saved.get("bulkWithdrawalStacks").getAsInt() == 1
              && !saved.get("allowBulkWithdrawal").getAsBoolean() && !saved.has("takeAllRate")
              && !saved.has("takeAllStacks") && !saved.has("allowTakeAll"));
    } finally {
      drops.forEach(ItemEntity::discard);
      UltsConfigManager.getInstance().update(previous);
    }
  }
}
