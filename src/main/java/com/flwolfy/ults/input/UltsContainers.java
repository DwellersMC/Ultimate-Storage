package com.flwolfy.ults.input;

import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.util.UltsBlockIds;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Container lookup with large container semantics.
 *
 * <p>A large container is one container although it is made of several blocks, exactly how vanilla
 * resolves a double chest for hoppers and comparators. It is therefore bound, counted, drained and
 * highlighted once no matter which part was used, and every part is interchangeable.
 *
 * <p>Three sources are combined, so the behaviour is not limited to vanilla chests:
 *
 * <ol>
 *   <li>the vanilla chest connection ({@link ChestBlock#getConnectedBlockPos}), which covers chests,
 *       trapped chests and copper chests,
 *   <li>a generic rule for containers whose parts share one inventory: same block, next to each
 *       other, and at most one of them is a container of its own. That is how modded multi block
 *       containers are usually built: one block holds the inventory and the others are just parts,
 *   <li>the block ids the server owner lists in {@code input.multiBlockContainers}, for modded
 *       containers that give every part its own container block entity.
 * </ol>
 *
 * <p>Independent containers of the same block that stand next to each other (a wall of barrels for
 * example) are never merged, because all of them are containers of their own.
 */
public final class UltsContainers {

  /** How many blocks one container may be made of, so a broken setup cannot run away. */
  private static final int MAX_PARTS = 128;

  private static volatile List<String> configuredSource = List.of();
  private static volatile Set<Block> configuredBlocks = Set.of();

  private UltsContainers() {}

  /** The container at the position, or {@code null} when it is unloaded or not a container. */
  public static Container at(ServerLevel level, BlockPos position) {
    if (!loaded(level, position) || !(level.getBlockEntity(position) instanceof Container container)) {
      return null;
    }
    if (container instanceof ChestBlockEntity && chestPart(level, position)) {
      Container merged = merged(level, position);
      return merged == null ? container : merged;
    }
    return container;
  }

  /**
   * The logical container a position belongs to. Every part of a large container returns the same
   * position, so a large container can never be bound or counted twice.
   *
   * <p>Unloaded chunks are never resolved, so this can not load a chunk either.
   */
  public static BlockPos identity(ServerLevel level, BlockPos position) {
    if (!loaded(level, position) || !chestPart(level, position)) {
      return position;
    }
    BlockPos partner = partner(level, position);
    return partner == null || position.compareTo(partner) <= 0 ? position : partner;
  }

  /** Every block position of the large container this position belongs to. */
  public static List<BlockPos> parts(ServerLevel level, BlockPos position) {
    if (!loaded(level, position)) {
      return List.of(position);
    }
    Set<BlockPos> found = new LinkedHashSet<>();
    found.add(position);
    if (chestPart(level, position)) {
      BlockPos partner = partner(level, position);
      if (partner != null && loaded(level, partner)) {
        found.add(partner);
      }
    }
    if (configured(level, position) || hasContainer(level, position)) {
      grow(level, position, found);
    }
    return List.copyOf(found);
  }

  /** Follows the connected blocks of one large container, in every direction. */
  private static void grow(ServerLevel level, BlockPos origin, Set<BlockPos> found) {
    Block block = level.getBlockState(origin).getBlock();
    boolean configured = configured(level, origin);
    boolean originIsContainer = hasContainer(level, origin);
    Deque<BlockPos> pending = new ArrayDeque<>(found);
    while (!pending.isEmpty() && found.size() < MAX_PARTS) {
      BlockPos current = pending.poll();
      for (Direction direction : Direction.values()) {
        BlockPos next = current.relative(direction);
        if (found.contains(next) || !loaded(level, next)
            || level.getBlockState(next).getBlock() != block) {
          continue;
        }
        boolean nextIsContainer = hasContainer(level, next);
        // Either the owner listed the block, or the parts share one inventory, which shows as a
        // block without a container of its own next to the one that holds it.
        if (!configured && nextIsContainer && originIsContainer) {
          continue;
        }
        found.add(next);
        pending.add(next);
      }
    }
  }

  /** True when the block at this position is listed in the configuration as a large container. */
  private static boolean configured(ServerLevel level, BlockPos position) {
    Set<Block> blocks = configuredBlocks();
    return !blocks.isEmpty() && blocks.contains(level.getBlockState(position).getBlock());
  }

  /** The configured block ids, re-read only when the configuration changed. */
  private static Set<Block> configuredBlocks() {
    List<String> ids = UltsConfigManager.getInstance().data().input().multiBlockContainers();
    if (ids.equals(configuredSource)) {
      return configuredBlocks;
    }
    List<String> copy = List.copyOf(ids);
    Set<Block> blocks = new HashSet<>();
    for (String value : copy) {
      UltsBlockIds.resolve(value).ifPresent(blocks::add);
    }
    configuredBlocks = Set.copyOf(blocks);
    configuredSource = copy;
    return configuredBlocks;
  }

  private static boolean hasContainer(ServerLevel level, BlockPos position) {
    return loaded(level, position) && level.getBlockEntity(position) instanceof Container;
  }

  private static boolean chestPart(ServerLevel level, BlockPos position) {
    BlockState state = level.getBlockState(position);
    return state.getBlock() instanceof ChestBlock
        && state.hasProperty(ChestBlock.TYPE)
        && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE;
  }

  private static BlockPos partner(ServerLevel level, BlockPos position) {
    return ChestBlock.getConnectedBlockPos(position, level.getBlockState(position));
  }

  private static Container merged(ServerLevel level, BlockPos position) {
    BlockState state = level.getBlockState(position);
    if (!(state.getBlock() instanceof ChestBlock chest)) {
      return null;
    }
    // The same lookup vanilla uses for hoppers, so both halves are one inventory.
    return ChestBlock.getContainer(chest, state, level, position, true);
  }

  private static boolean loaded(ServerLevel level, BlockPos position) {
    return level.hasChunk(position.getX() >> 4, position.getZ() >> 4);
  }
}
