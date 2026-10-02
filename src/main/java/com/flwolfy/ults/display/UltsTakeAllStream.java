package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.util.UltsTextBuilder;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** A confirmed stock request delivered a bounded number of pieces per tick. */
public final class UltsTakeAllStream {
  enum Stop { DONE, BACKPACK, PLAYER, EMPTY, REPLACED }

  private final UltsRuntime runtime;
  private final UUID playerId;
  private final String playerName;
  private final WeakReference<ServerPlayer> remembered;
  private final UltsCraftingMode mode;
  private final boolean wholeBag;
  private final UltsTakeAllBatch batch;

  private UltsTakeAllStream(
      UltsRuntime runtime, ServerPlayer player, List<ItemStack> wanted,
      List<Long> amounts, UltsCraftingMode mode, boolean wholeBag
  ) {
    this.runtime = runtime;
    this.playerId = player.getUUID();
    this.playerName = player.getGameProfile().name();
    this.remembered = new WeakReference<>(player);
    this.mode = mode;
    this.wholeBag = wholeBag;
    this.batch = new UltsTakeAllBatch(wanted, amounts);
  }

  public static void start(
      UltsRuntime runtime, ServerPlayer player, List<ItemStack> wanted,
      List<Long> amounts, UltsCraftingMode mode, boolean wholeBag
  ) {
    UltsTakeAllStream stream = new UltsTakeAllStream(
        runtime, player, wanted, amounts, mode, wholeBag);
    runtime.streams().start(stream);
    player.sendSystemMessage(UltsTextBuilder.info(UltsTextBuilder.format(
        UltsGuiText.text("ults.take.start"),
        wanted.isEmpty() ? "" : wanted.getFirst().getHoverName(),
        UltsGuiText.format(stream.batch.remaining()))), false);
  }

  private ServerPlayer player() {
    ServerPlayer listed = runtime.server().getPlayerList().getPlayer(playerId);
    if (listed != null) {
      return listed;
    }
    ServerPlayer held = remembered.get();
    return held != null && !held.hasDisconnected() ? held : null;
  }

  UUID playerId() { return playerId; }

  public boolean tick() { return tickFor(player()); }

  boolean tickFor(ServerPlayer player) {
    if (player == null || player.isRemoved() || player.isDeadOrDying()
        || player.hasDisconnected()) {
      stop(player, Stop.PLAYER);
      return false;
    }
    boolean ground = runtime.allowFullInventory();
    Stop reason = batch.advance(runtime.takeAllRate(), ground, () -> UltsBackpack.slots(player),
        (template, count) -> {
          if (wholeBag) {
            return runtime.takeBagPieces(template.getItem(), template, count);
          }
          List<ItemStack> output = runtime.takePlanned(template, count, false, mode);
          if (!output.isEmpty()) {
            return output;
          }
          // Another player may have left less than one tick's request; deliver the remaining stock.
          long available = UltsRuntime.storedAmount(template, runtime.storedItemsFresh());
          int smaller = (int) Math.min(count, available);
          return smaller == 0 ? List.of()
              : runtime.takePlanned(template, smaller, false, UltsCraftingMode.DISABLED);
        }, outputs -> deliver(player, outputs, ground));
    if (reason != null) {
      stop(player, reason);
      return false;
    }
    return true;
  }

  private long deliver(ServerPlayer player, List<ItemStack> outputs, boolean ground) {
    return UltsGuiGive.hand(player, runtime, outputs, ground).rejected();
  }

  private void stop(ServerPlayer player, Stop reason) {
    if (player != null) {
      Component message = reason == Stop.DONE
          ? UltsTextBuilder.done(UltsTextBuilder.format(
              UltsGuiText.text("ults.take.done"), UltsGuiText.format(batch.taken())))
          : UltsTextBuilder.failure(UltsTextBuilder.format(
              UltsGuiText.text("ults.take.failed"), reasonText(reason))).copy()
              .append(Component.literal(" "))
              .append(UltsGuiText.text("ults.take.progress", UltsGuiText.format(batch.taken())));
      player.sendSystemMessage(message, false);
    }
  }

  private Component reasonText(Stop reason) {
    return UltsGuiText.text(switch (reason) {
      case BACKPACK -> "ults.take.failed.backpack";
      case PLAYER -> "ults.take.failed.player";
      case EMPTY -> "ults.take.failed.empty";
      case REPLACED -> "ults.take.failed.replaced";
      case DONE -> "ults.take.done";
    }).copy().withStyle(ChatFormatting.RED);
  }

  public void supersede() {
    stop(runtime.server().getPlayerList().getPlayer(playerId), Stop.REPLACED);
  }

  @Override public String toString() {
    return "take-everything of " + playerName + ", " + batch.taken()
        + " taken, " + batch.remaining() + " to go";
  }
}
