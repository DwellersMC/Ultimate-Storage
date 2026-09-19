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
import com.flwolfy.ults.input.UltsContainers;
import com.flwolfy.ults.input.UltsInputManager;
import com.flwolfy.ults.util.UltsTextBuilder;
import com.flwolfy.ults.visual.UltsHighlights;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
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

  /** How long the "sneak to break it" notice stays quiet after being shown once, in ticks. */
  private static final int PROTECTED_NOTICE_TICKS = 40;

  private final MinecraftServer server;
  private final UltsState state;
  private final UltsInputManager inputs;
  private final UltsHighlights highlights = new UltsHighlights();
  /** Per player tick of the last break-protection notice, so it cannot flood the chat. */
  private final Map<UUID, Long> protectedNotices = new HashMap<>();
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

  /**
   * Whether a player may break this block. The whole bound container is protected, both halves of a
   * large one included, and only a sneaking player can take it down.
   */
  public boolean allowBreak(Player player, Level level, BlockPos position) {
    if (player == null || !(level instanceof ServerLevel serverLevel)
        || player.isShiftKeyDown()) {
      return true;
    }
    if (!bound(serverLevel, position)) {
      return true;
    }
    if (player instanceof ServerPlayer serverPlayer) {
      // Tell the client to drop its predicted break, then explain why nothing happened. The notice
      // goes to the chat and not to the action bar, where the look-at binding hint would overwrite
      // it within a few ticks.
      serverPlayer.connection.send(new ClientboundBlockUpdatePacket(
          position, serverLevel.getBlockState(position)));
      long tick = server.getTickCount();
      Long last = protectedNotices.get(serverPlayer.getUUID());
      if (last == null || tick - last >= PROTECTED_NOTICE_TICKS) {
        protectedNotices.put(serverPlayer.getUUID(), tick);
        serverPlayer.sendSystemMessage(UltsTextBuilder.info(
            UltsLangManager.getInstance().text("ults.input.protected")));
      }
    }
    return false;
  }

  /** True when this position, or the other half of its large container, is bound. */
  private boolean bound(ServerLevel level, BlockPos position) {
    String dimension = level.dimension().identifier().toString();
    for (BlockPos part : UltsContainers.parts(level, position)) {
      if (state.binding(dimension, part) != null) {
        return true;
      }
    }
    return false;
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
    if (server.getTickCount() % 600 == 0) {
      long now = server.getTickCount();
      protectedNotices.entrySet().removeIf(entry -> now - entry.getValue() > 600);
    }
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
      UltsBinding binding = bindingAt(player.level(), hit.getBlockPos());
      if (binding == null) {
        continue;
      }
      player.sendSystemMessage(lookedAt(binding, state.number(binding)), true);
    }
  }

  /**
   * The binding of the container a position belongs to, whatever part of a large container it is.
   * Looking at any part of a bound multi block container therefore shows the same binding.
   */
  public UltsBinding bindingAt(Level level, BlockPos position) {
    if (!(level instanceof ServerLevel serverLevel)) {
      return null;
    }
    String dimension = level.dimension().identifier().toString();
    for (BlockPos part : UltsContainers.parts(serverLevel, position)) {
      UltsBinding binding = state.binding(dimension, part);
      if (binding != null) {
        return binding;
      }
    }
    return null;
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
