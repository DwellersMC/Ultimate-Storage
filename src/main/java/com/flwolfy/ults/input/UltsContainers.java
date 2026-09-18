package com.flwolfy.ults.input;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Container lookup with vanilla's large container semantics.
 *
 * <p>A double chest is one container of 54 slots although it is two block entities, exactly how
 * vanilla resolves it for hoppers and comparators. It is therefore bound, counted and drained once
 * no matter which half was used, and both halves are interchangeable when the feeding hoppers are
 * searched.
 */
public final class UltsContainers {

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
   * The logical container a position belongs to. Both halves of a double chest return the same
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

  /** Every block position of the container at this position: one, or both halves of a double chest. */
  public static List<BlockPos> parts(ServerLevel level, BlockPos position) {
    BlockPos partner = loaded(level, position) && chestPart(level, position)
        ? partner(level, position) : null;
    return partner == null ? List.of(position) : List.of(position, partner);
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
