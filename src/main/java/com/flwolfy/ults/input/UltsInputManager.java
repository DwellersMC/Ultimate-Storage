package com.flwolfy.ults.input;

import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.state.UltsBinding;
import com.flwolfy.ults.data.state.UltsRemoteStorage;
import com.flwolfy.ults.data.state.UltsState;
import com.flwolfy.ults.mixin.UltsHopperAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

public final class UltsInputManager {

  public enum BindResult {
    SUCCESS,
    INVALID_NOTE,
    ALREADY_EXISTS,
    BINDING_LIMIT_REACHED,
    NOT_A_CONTAINER,
    NO_CONTAINERS,
    TOO_LARGE
  }

  /**
   * Outcome of an area binding: how many containers the scan found, how many were added and how many
   * were left alone because they are bound already.
   */
  public record AreaResult(BindResult result, int added, int found, int skipped) {}

  /** How far a chain of hoppers feeding one bound container is followed. */
  private static final int MAX_HOPPER_DEPTH = 8;

  /** Checks without a single drained item after which a binding is only looked at now and then. */
  private static final int IDLE_THRESHOLD = 3;
  /** Visit skip of an idle binding that still has a hopper on its input path. */
  private static final int IDLE_SKIP_HOPPER = 2;
  /** Visit skip of an idle binding without anything that could deliver items. */
  private static final int IDLE_SKIP_QUIET = 8;
  /** How often the bookkeeping of unbound containers is dropped, in ticks. */
  private static final int STATE_SWEEP_TICKS = 600;

  /** Bookkeeping per binding: how often it was visited, how idle it is and whether it has hoppers. */
  private static final class DrainState {

    private int visits;
    private int idle;
    private boolean hopper;
  }

  private final MinecraftServer server;
  private final UltsState state;
  private final Map<UltsBinding, DrainState> drainStates = new HashMap<>();
  private int drainPhase;
  private int sweepTick;

  public UltsInputManager(MinecraftServer server, UltsState state) {
    this.server = server;
    this.state = state;
  }

  /** Binds the single container at the given position, remembering the note for humans. */
  public synchronized BindResult bind(
      String creator,
      String note,
      ServerLevel level,
      BlockPos container
  ) {
    String remark = normalizeNote(note);
    if (remark == null) {
      return BindResult.INVALID_NOTE;
    }
    if (!UltsContainerFinder.isContainer(level, container)) {
      return BindResult.NOT_A_CONTAINER;
    }
    String dimension = level.dimension().identifier().toString();
    // A large container is stored under its canonical half, so either half refers to one binding.
    if (bound(level, dimension, container)) {
      return BindResult.ALREADY_EXISTS;
    }
    if (limit() > 0 && state.bindingCount() >= limit()) {
      return BindResult.BINDING_LIMIT_REACHED;
    }
    BlockPos target = UltsContainers.identity(level, container);
    boolean added = state.addBinding(new UltsBinding(
        remark,
        dimension,
        target.getX(),
        target.getY(),
        target.getZ(),
        creator
    ));
    return added ? BindResult.SUCCESS : BindResult.ALREADY_EXISTS;
  }

  /** Scans the cuboid once and binds every container found inside it under the same note. */
  public synchronized AreaResult bindArea(
      String creator,
      String note,
      ServerLevel level,
      BlockPos first,
      BlockPos second
  ) {
    String remark = normalizeNote(note);
    if (remark == null) {
      return new AreaResult(BindResult.INVALID_NOTE, 0, 0, 0);
    }
    if (UltsContainerFinder.tooLarge(first, second)) {
      return new AreaResult(BindResult.TOO_LARGE, 0, 0, 0);
    }
    List<BlockPos> found = UltsContainerFinder.find(level, first, second);
    if (found.isEmpty()) {
      return new AreaResult(BindResult.NO_CONTAINERS, 0, 0, 0);
    }
    String dimension = level.dimension().identifier().toString();
    int added = 0;
    int skipped = 0;
    boolean limited = false;
    for (BlockPos container : found) {
      // Containers that are bound already keep their binding, note and all.
      if (bound(level, dimension, container)) {
        skipped++;
        continue;
      }
      if (limit() > 0 && state.bindingCount() >= limit()) {
        limited = true;
        break;
      }
      BlockPos target = UltsContainers.identity(level, container);
      if (state.addBinding(new UltsBinding(
          remark, dimension, target.getX(), target.getY(), target.getZ(), creator))) {
        added++;
      }
    }
    return new AreaResult(
        limited ? BindResult.BINDING_LIMIT_REACHED : BindResult.SUCCESS, added, found.size(),
        skipped);
  }

  /** True when the container at this position is already bound, either half of it included. */
  private boolean bound(ServerLevel level, String dimension, BlockPos container) {
    return UltsContainers.parts(level, container).stream()
        .anyMatch(part -> state.binding(dimension, part) != null);
  }

  /** Removes the 1-based inclusive range of bindings. */
  public synchronized List<UltsBinding> remove(int from, int to) {
    return state.removeBindings(Math.max(1, from), Math.max(1, to));
  }

  /** The configured limit of bound containers, {@code 0} meaning unlimited. */
  public int limit() {
    return UltsConfigManager.getInstance().data().input().maxBindings();
  }

  /**
   * Drains the bound containers of the void mode and reports the bindings whose block is gone.
   *
   * @return bindings that lost their block and must be removed
   */
  public List<UltsBinding> tick(boolean voidMode) {
    List<UltsBinding> bindings = state.bindings();
    List<UltsBinding> broken = new ArrayList<>();
    if (bindings.isEmpty()) {
      drainStates.clear();
      return List.copyOf(broken);
    }
    int interval = drainInterval();
    // Resolving a dimension once per tick instead of once per binding keeps the allocations down.
    Map<String, ServerLevel> levels = new HashMap<>();
    drainPhase = (drainPhase + 1) % interval;
    for (int index = drainPhase; index < bindings.size(); index += interval) {
      UltsBinding binding = bindings.get(index);
      ServerLevel level = levels.get(binding.dimension());
      if (level == null) {
        level = level(binding.dimension());
        if (level == null) {
          continue;
        }
        levels.put(binding.dimension(), level);
      }
      if (!level.hasChunk(binding.x() >> 4, binding.z() >> 4)) {
        continue;
      }
      Container container = UltsRemoteStorage.containerAt(level, binding.pos());
      if (container == null) {
        if (level.getBlockState(binding.pos()).isAir()) {
          broken.add(binding);
        }
        continue;
      }
      if (!voidMode) {
        continue;
      }
      DrainState quiet = drainStates.computeIfAbsent(binding, key -> new DrainState());
      quiet.visits++;
      if (quiet.idle >= IDLE_THRESHOLD) {
        // An input that produced nothing for a while only carries a hopper on the path or nothing.
        int skip = quiet.hopper ? IDLE_SKIP_HOPPER : IDLE_SKIP_QUIET;
        if (quiet.visits % skip != 0) {
          continue;
        }
      }
      boolean drained = drain(container);
      quiet.hopper = drainInboundHoppers(level, binding.pos());
      quiet.idle = drained ? 0 : quiet.idle + 1;
    }
    forgetUnbound(bindings);
    return List.copyOf(broken);
  }

  /** Drops the bookkeeping of containers that are not bound any more, checked now and then. */
  private void forgetUnbound(List<UltsBinding> bindings) {
    if (++sweepTick < STATE_SWEEP_TICKS || drainStates.size() <= bindings.size()) {
      if (sweepTick >= STATE_SWEEP_TICKS) {
        sweepTick = 0;
      }
      return;
    }
    sweepTick = 0;
    drainStates.keySet().retainAll(new HashSet<>(bindings));
  }

  /** The configured number of ticks between two drain visits of the same binding. */
  private static int drainInterval() {
    return Math.max(1, UltsConfigManager.getInstance().data().input().drainInterval());
  }

  /**
   * Empties every hopper that pushes into the bound container, following hopper chains upstream.
   *
   * <p>A hopper belongs to the input path when it is enabled, faces the container (or faces another
   * hopper of that path) from any side, above included, and nothing blocks the path: a hopper that
   * redstone switched off keeps its contents and also stops the search, because nothing can flow
   * through it any more. Both halves of a double chest count as the same container.
   */
  private boolean drainInboundHoppers(ServerLevel level, BlockPos containerPos) {
    boolean fed = false;
    Set<BlockPos> visited = new HashSet<>();
    List<BlockPos> frontier = UltsContainers.parts(level, containerPos);
    for (int depth = 0; depth < MAX_HOPPER_DEPTH && !frontier.isEmpty(); depth++) {
      List<BlockPos> next = new ArrayList<>();
      for (BlockPos target : frontier) {
        for (Direction direction : Direction.values()) {
          BlockPos candidate = target.relative(direction);
          if (!visited.add(candidate) || !level.hasChunk(candidate.getX() >> 4, candidate.getZ() >> 4)) {
            continue;
          }
          var blockState = level.getBlockState(candidate);
          if (!(blockState.getBlock() instanceof HopperBlock)
              || !blockState.getValue(HopperBlock.ENABLED)
              || !candidate.relative(blockState.getValue(HopperBlock.FACING)).equals(target)
              || !(level.getBlockEntity(candidate) instanceof HopperBlockEntity hopper)) {
            continue;
          }
          fed = true;
          drain(hopper);
          ((UltsHopperAccessor) hopper).ults$setCooldown(0);
          next.add(candidate);
        }
      }
      frontier = next;
    }
    return fed;
  }

  /** Empties a container into the storage, reporting whether there was anything in it. */
  private boolean drain(Container container) {
    boolean changed = false;
    for (int slot = 0; slot < container.getContainerSize(); slot++) {
      ItemStack stack = container.getItem(slot);
      if (stack.isEmpty()) {
        continue;
      }
      state.deposit(stack.copy());
      container.setItem(slot, ItemStack.EMPTY);
      changed = true;
    }
    if (changed) {
      container.setChanged();
    }
    return changed;
  }

  private ServerLevel level(String dimension) {
    Identifier id = Identifier.tryParse(dimension);
    return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
  }

  /**
   * Normalizes a note. Unlike a storage name a note may be empty, it only has to be short and free of
   * control characters and of formatting codes, because it is printed in chat and in the listings.
   *
   * @return the trimmed note, or {@code null} when it cannot be used
   */
  public static String normalizeNote(String requested) {
    if (requested == null) {
      return null;
    }
    String value = requested.trim();
    if (value.length() > 64
        || value.codePoints().anyMatch(Character::isISOControl)
        || value.indexOf('\u00A7') >= 0) {
      return null;
    }
    return value;
  }
}
