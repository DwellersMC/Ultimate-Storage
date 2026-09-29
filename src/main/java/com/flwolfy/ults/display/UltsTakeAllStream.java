package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.util.UltsTextBuilder;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * A pile on its way out of the storage, a tick's worth at a time.
 *
 * <p>Emptying a warehouse is not one event: a stock can hold more than a server should move in a single
 * tick, so a take-everything that is confirmed becomes a stream instead — every tick it hands over at
 * most {@code input.takeAllRate} items, into the backpack first and onto the ground after that when the
 * configuration allows it, until what was asked for is out or until something stops it. The player is
 * told when it starts, and told again when it ends, whichever way it ends.
 *
 * <p>It stops the moment the player is no longer there to receive: they died, they left, or the server let
 * them go. A backpack that cannot take any more ends it too while the configuration forbids dropping,
 * because a stream that cannot deliver has nothing left to do. Opening a screen does <em>not</em> end it:
 * what the pour hands over is in the backpack and on the ground, where it stays, so a player who looks at
 * something else mid-pour keeps everything the pour already took. Every stop says why; nothing ever
 * leaves the storage without being handed over, so what a stream took is exactly what a player received.
 */
public final class UltsTakeAllStream {
  /** Why a stream stopped. */
  enum Stop {
    /** Everything that was asked for was handed over. */
    DONE,
    /** The backpack cannot take any more and the configuration forbids the ground. */
    BACKPACK,
    /** The player died, left, or is no longer there. */
    PLAYER,
    /** The storage has nothing left to hand over before the amount asked for was reached. */
    EMPTY,
    /** A newer take-everything took its place. */
    REPLACED
  }

  private final UltsRuntime runtime;
  private final UUID playerId;
  private final String playerName;
  /** Who it is pouring for, held weakly: the player list is the authority, and this is a fallback. */
  private final WeakReference<ServerPlayer> remembered;
  /** The stacks the question is about: one template for a single item, every row for a bag. */
  private final List<ItemStack> wanted;
  /** How many pieces each of those stacks holds, so a stack that leaves can be counted. */
  private final List<Long> amounts;
  private final UltsCraftingMode mode;
  /** How many pieces are still to leave. */
  private long remaining;
  /** How many pieces left so far. */
  private long taken;
  /** The next row of a bag to take, which is a whole stack at a time. */
  private int unit;

  private UltsTakeAllStream(
      UltsRuntime runtime,
      ServerPlayer player,
      List<ItemStack> wanted,
      List<Long> amounts,
      UltsCraftingMode mode,
      long remaining
  ) {
    this.runtime = runtime;
    this.playerId = player.getUUID();
    this.playerName = player.getGameProfile().name();
    this.remembered = new WeakReference<>(player);
    this.wanted = List.copyOf(wanted);
    this.amounts = List.copyOf(amounts);
    this.mode = mode;
    this.remaining = remaining;
  }

  /**
   * Starts handing a stock over, a tick at a time.
   *
   * @param runtime the storage it comes out of
   * @param player the player receiving it
   * @param wanted the stacks the question is about
   * @param amounts how many pieces each of them holds
   * @param mode whether the recipes may fill in what is short
   * @param amount how many pieces are to leave in all
   */
  public static void start(
      UltsRuntime runtime,
      ServerPlayer player,
      List<ItemStack> wanted,
      List<Long> amounts,
      UltsCraftingMode mode,
      long amount
  ) {
    UltsTakeAllStream stream = new UltsTakeAllStream(
        runtime, player, wanted, amounts, mode, amount);
    runtime.streams().start(stream);
    player.sendSystemMessage(UltsTextBuilder.info(UltsTextBuilder.format(
        UltsGuiText.text("ults.take.start"),
        wanted.isEmpty() ? "" : wanted.getFirst().getHoverName(),
        UltsGuiText.format(amount))), false);
  }

  /** How many items this stream may hand over in one tick. */
  private int rate() {
    return Math.max(1, runtime.takeAllRate());
  }

  /** The player this stream is pouring for, or {@code null} once they are gone. */
  private ServerPlayer player() {
    ServerPlayer listed = runtime.server().getPlayerList().getPlayer(playerId);
    if (listed != null) {
      return listed;
    }
    ServerPlayer held = remembered.get();
    return held != null && !held.hasDisconnected() ? held : null;
  }

  /** The player this stream is handing items to. */
  UUID playerId() {
    return playerId;
  }

  /**
   * One tick of the stream.
   *
   * @return whether it is still running
   */
  public boolean tick() {
    return tickFor(player());
  }

  /**
   * One tick of the stream for the player it is pouring for.
   *
   * <p>The player list is the authority on who is online; the player the stream started for is remembered
   * weakly as well, so a stream can still be driven for a player the list does not carry.
   *
   * @param player the player receiving, or {@code null} once they are gone
   * @return whether it is still running
   */
  boolean tickFor(ServerPlayer player) {
    if (player == null || player.isRemoved() || player.isDeadOrDying()
        || player.hasDisconnected()) {
      stop(player, Stop.PLAYER);
      return false;
    }
    boolean ground = runtime.allowFullInventory();
    if (wanted.size() == 1) {
      return tickItem(player, ground);
    }
    return tickBag(player, ground);
  }

  /** One item, taken by the piece: the pack first, then the ground when the configuration allows it. */
  private boolean tickItem(ServerPlayer player, boolean ground) {
    ItemStack template = wanted.getFirst();
    long room = ground
        ? rate()
        : Math.min(rate(), UltsBackpack.room(UltsBackpack.slots(player), template));
    long want = Math.min(remaining, room);
    if (want <= 0L) {
      stop(player, Stop.BACKPACK);
      return false;
    }
    List<ItemStack> outputs = runtime.takePlanned(template, (int) want, false, mode);
    long out = outputs.stream().mapToLong(ItemStack::getCount).sum();
    if (out <= 0L) {
      // The storage ran dry under the stream: what is there is what the player gets.
      stop(player, Stop.EMPTY);
      return false;
    }
    // What could not be handed over went back, so it is owed to the player rather than counted as taken.
    long handedOver = out - deliver(player, outputs, ground);
    taken += handedOver;
    remaining -= handedOver;
    if (remaining <= 0L) {
      stop(player, Stop.DONE);
      return false;
    }
    return true;
  }

  /** A bag, taken by the row: a row is one kind of thing and leaves as it is or not at all. */
  private boolean tickBag(ServerPlayer player, boolean ground) {
    List<ItemStack> leaving = new ArrayList<>();
    long out = 0L;
    List<ItemStack> slots = UltsBackpack.slots(player);
    while (unit < wanted.size() && out < rate() && remaining > 0L) {
      ItemStack row = wanted.get(unit);
      long pieces = Math.max(1L, amounts.get(unit));
      if (!ground && !UltsBackpack.place(slots, row)) {
        break;
      }
      ItemStack takenRow = runtime.takeBagRow(row.getItem(), row);
      if (takenRow.isEmpty()) {
        // That row is gone from the storage: it is not owed to the player any more, so the stream moves
        // on to the next one instead of standing still on a row nobody can hand over.
        remaining = Math.max(0L, remaining - pieces);
        unit++;
        continue;
      }
      leaving.add(takenRow);
      out += takenRow.getCount();
      remaining = Math.max(0L, remaining - pieces);
      unit++;
    }
    if (leaving.isEmpty()) {
      if (remaining <= 0L) {
        stop(player, Stop.DONE);
      } else {
        stop(player, unit >= wanted.size() ? Stop.EMPTY : Stop.BACKPACK);
      }
      return false;
    }
    // What could not be handed over went back, so it is owed to the player rather than counted as taken.
    long handedOver = out - deliver(player, leaving, ground);
    taken += handedOver;
    if (remaining <= 0L || unit >= wanted.size()) {
      stop(player, Stop.DONE);
      return false;
    }
    return true;
  }

  /**
   * Hands one tick's worth over, and answers how much of it could not be handed over at all.
   *
   * <p>The backpack takes what it can. What is left over lands on the ground while the configuration
   * allows it, and a drop the world refuses is put back into the storage rather than lost with it — a
   * stream never destroys what it was carrying. Whatever did not leave is answered back to the caller, so
   * the amount a stream reports as taken is the amount the player really received.
   */
  private long deliver(ServerPlayer player, List<ItemStack> outputs, boolean ground) {
    long undelivered = 0L;
    for (ItemStack output : outputs) {
      player.getInventory().add(output);
      if (output.isEmpty()) {
        continue;
      }
      if (ground && UltsGuiGive.drop(player, runtime, output)) {
        continue;
      }
      // Nowhere for it to go: back into the storage rather than out of the world.
      undelivered += output.getCount();
      runtime.state().deposit(output);
      output.setCount(0);
    }
    return undelivered;
  }

  private void stop(ServerPlayer player, Stop reason) {
    if (player != null) {
      Component message = reason == Stop.DONE
          ? UltsTextBuilder.done(UltsTextBuilder.format(
              UltsGuiText.text("ults.take.done"), UltsGuiText.format(taken)))
          : UltsTextBuilder.failure(UltsTextBuilder.format(
              UltsGuiText.text("ults.take.failed"), reasonText(reason)));
      player.sendSystemMessage(message, false);
    }
    // Taking itself out of the list is the caller's business: this is called from the tick that walks
    // that list, and a stream that removed itself while being walked would break the walk.
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

  /** Ends this stream because a newer one is taking its place, which the player is told about. */
  public void supersede() {
    ServerPlayer player = runtime.server().getPlayerList().getPlayer(playerId);
    stop(player, Stop.REPLACED);
  }

  /** The name this stream reports itself under, for the log. */
  @Override
  public String toString() {
    return "take-everything of " + playerName + ", " + taken + " taken, " + remaining + " to go";
  }
}
