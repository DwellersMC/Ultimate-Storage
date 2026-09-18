package com.flwolfy.ults;

import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsStorageMode;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.data.state.UltsBinding;
import com.flwolfy.ults.data.state.UltsRemoteStorage;
import com.flwolfy.ults.data.state.UltsState;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.data.state.UltsWithdrawalPlan;
import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.flwolfy.ults.display.UltsStorageSGUI;
import com.flwolfy.ults.display.UltsSurvivalItems;
import com.flwolfy.ults.display.UltsWithdrawSGUI;
import com.flwolfy.ults.input.UltsInputManager;
import com.flwolfy.ults.util.UltsTextBuilder;
import com.flwolfy.ults.visual.UltsHighlights;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public final class UltsRuntime {

  /** How many slices the remote aggregate is computed in. */
  private static final int REMOTE_SHARDS = 8;

  /** How often one slice is recomputed while a screen is watching, in ticks. */
  private static final int REMOTE_SHARD_TICKS = 4;

  /** How long after the last query the slices keep being refreshed, in ticks. */
  private static final int REMOTE_WATCH_TICKS = 40;

  /** How often the "you are looking at binding #N" overlay is refreshed. */
  private static final int LOOK_REFRESH_TICKS = 10;

  /** How far a player may look to inspect or delete a binding. */
  private static final double LOOK_REACH = 6.0D;

  private final MinecraftServer server;
  private final UltsState state;
  private final UltsInputManager inputs;
  private final UltsHighlights highlights = new UltsHighlights();
  private final UltsRemoteStorage.Snapshot[] remoteShards =
      new UltsRemoteStorage.Snapshot[REMOTE_SHARDS];
  private UltsRemoteStorage.Snapshot merged;
  private boolean shardsStale = true;
  private int shardCursor;
  private long shardTick = -1;
  private long remoteDemandTick = Long.MIN_VALUE / 2;
  private long remoteRevision;

  UltsRuntime(MinecraftServer server) {
    this.server = server;
    UltsCreativeCatalog.rebuild(server);
    UltsSurvivalItems.rebuild(server);
    state = server.overworld().getDataStorage().computeIfAbsent(UltsState.TYPE);
    inputs = new UltsInputManager(server, state);
  }

  public MinecraftServer server() {
    return server;
  }

  public UltsState state() {
    return state;
  }

  public UltsInputManager inputs() {
    return inputs;
  }

  /** The glowing outlines shown while the highlight mode is on. */
  public UltsHighlights highlights() {
    return highlights;
  }

  /** Remote storage mode keeps the bound containers themselves as the storage. */
  public boolean remote() {
    return UltsConfigManager.getInstance().data().general().storageMode() == UltsStorageMode.REMOTE;
  }

  /**
   * Version of the shown contents. In remote mode the aggregate is refreshed one slice at a time
   * while a screen is asking for it, so a warehouse sized storage never has to be walked completely
   * inside a single tick.
   */
  public long contentRevision() {
    if (!remote()) {
      return state.revision();
    }
    watch();
    return remoteRevision;
  }

  public List<UltsStoredView> storedItems() {
    return remote() ? aggregate().items() : state.items();
  }

  public UltsWithdrawalPlan withdrawalPlan(ItemStack template, int quantity, boolean boxed) {
    return remote()
        ? UltsRemoteStorage.plan(aggregate(), template, quantity, boxed)
        : state.withdrawalPlan(template, quantity, boxed);
  }

  /** Withdrawals always read the live containers, then drop the cached aggregate. */
  public List<ItemStack> takePlanned(ItemStack template, int quantity, boolean boxed) {
    if (!remote()) {
      return state.takePlanned(template, quantity, boxed);
    }
    List<ItemStack> outputs = UltsRemoteStorage.take(
        server, state.bindings(), template, quantity, boxed);
    invalidateRemote();
    return outputs;
  }

  public ItemStack availableBox() {
    return remote() ? UltsRemoteStorage.availableBox(aggregate()) : state.availableBox();
  }

  /** Marks that a screen is looking at the aggregate, so the slices keep being refreshed. */
  private void watch() {
    remoteDemandTick = server.getTickCount();
  }

  /**
   * The aggregate of every slice. The first caller after an idle period walks everything once, later
   * calls are served from the slices that the tick loop keeps up to date.
   */
  private UltsRemoteStorage.Snapshot aggregate() {
    watch();
    if (shardsStale) {
      for (int shard = 0; shard < REMOTE_SHARDS; shard++) {
        remoteShards[shard] = UltsRemoteStorage.snapshot(server, slice(shard));
      }
      shardsStale = false;
      shardCursor = 0;
      remoteRevision++;
      merged = null;
    } else {
      advanceShards();
    }
    if (merged == null) {
      merged = UltsRemoteStorage.merge(List.of(remoteShards));
    }
    return merged;
  }

  /** Recomputes one slice, at most every {@link #REMOTE_SHARD_TICKS} ticks. */
  private void advanceShards() {
    long tick = server.getTickCount();
    if (tick - shardTick < REMOTE_SHARD_TICKS) {
      return;
    }
    shardTick = tick;
    remoteShards[shardCursor] = UltsRemoteStorage.snapshot(server, slice(shardCursor));
    shardCursor = (shardCursor + 1) % REMOTE_SHARDS;
    merged = null;
    remoteRevision++;
  }

  /** The bindings of one slice; a container always belongs to the same slice. */
  private List<UltsBinding> slice(int shard) {
    List<UltsBinding> all = state.bindings();
    if (REMOTE_SHARDS == 1) {
      return all;
    }
    List<UltsBinding> part = new ArrayList<>(all.size() / REMOTE_SHARDS + 1);
    for (UltsBinding binding : all) {
      if (shardOf(binding) == shard) {
        part.add(binding);
      }
    }
    return part;
  }

  private static int shardOf(UltsBinding binding) {
    int hash = binding.dimension().hashCode() * 31 + binding.pos().hashCode();
    return Math.floorMod(hash, REMOTE_SHARDS);
  }

  /** Drops the cached aggregate and walks everything again on the next call. */
  private void invalidateRemote() {
    shardsStale = true;
    merged = null;
    remoteRevision++;
  }

  /** A bound container was destroyed: that single binding is dropped and everyone is told. */
  public void onBlockBroken(Level level, BlockPos position) {
    UltsBinding binding = state.binding(level.dimension().identifier().toString(), position);
    if (binding == null) {
      return;
    }
    int index = state.number(binding);
    if (index <= 0) {
      return;
    }
    if (!state.removeBindings(index, index).isEmpty()) {
      announce(binding, index);
    }
  }

  private void announce(UltsBinding binding, int index) {
    invalidateRemote();
    Component message = UltsTextBuilder.info(UltsTextBuilder.format(
        UltsLangManager.getInstance().text("ults.input.destroyed"),
        UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
        index, binding.note().isEmpty()
            ? UltsLangManager.getInstance().text("ults.command.note.none")
            : binding.note()));
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      player.sendSystemMessage(message);
    }
    UltsMod.LOGGER.info("UltStorage binding #{} was destroyed and removed", index);
  }

  void tick() {
    for (UltsBinding binding : inputs.tick(!remote())) {
      int index = state.number(binding);
      if (index > 0 && !state.removeBindings(index, index).isEmpty()) {
        announce(binding, index);
      }
    }
    if (remote()) {
      // Keep the slices of the aggregate fresh while a screen is watching, otherwise forget them.
      if (server.getTickCount() - remoteDemandTick <= REMOTE_WATCH_TICKS) {
        if (shardsStale) {
          aggregate();
        } else {
          advanceShards();
        }
      } else if (!shardsStale) {
        invalidateRemote();
      }
    }
    if (highlights.active()) {
      highlights.tick(server, state.bindings());
    }
    if (server.getTickCount() % LOOK_REFRESH_TICKS == 0) {
      inspectLookedAtBindings();
    }
    UltsStorageSGUI.refreshAll(this);
    UltsWithdrawSGUI.refreshAll(this);
  }

  /** Shows "#N note" over the action bar while a player looks at a bound container. */
  private void inspectLookedAtBindings() {
    if (server.getPlayerList().getPlayers().isEmpty()) {
      return;
    }
    List<UltsBinding> bindings = state.bindings();
    if (bindings.isEmpty()) {
      return;
    }
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      if (!(player.pick(LOOK_REACH, 1.0F, false) instanceof BlockHitResult hit)
          || hit.getType() != HitResult.Type.BLOCK) {
        continue;
      }
      UltsBinding binding = state.binding(
          player.level().dimension().identifier().toString(), hit.getBlockPos());
      if (binding == null) {
        continue;
      }
      player.sendSystemMessage(lookedAt(binding, state.number(binding)), true);
    }
  }

  private static Component lookedAt(UltsBinding binding, int number) {
    UltsLangManager lang = UltsLangManager.getInstance();
    return UltsTextBuilder.format(
        lang.text("ults.visual.looking"), UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT, number,
        binding.note().isEmpty()
            ? lang.text("ults.command.note.none")
            : Component.literal(binding.note()).withStyle(UltsTextBuilder.HIGHLIGHT));
  }
}
