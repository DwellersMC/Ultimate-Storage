package com.flwolfy.ults.input;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Scans areas for containers.
 *
 * <p>Only loaded chunks are searched, so a container that is not loaded simply does not take part
 * until its chunk is loaded again. The scan walks the block entities of the loaded chunks instead of
 * every single block, which keeps warehouse sized selections cheap.
 */
public final class UltsContainerFinder {

  /** Chunk span limit per axis, so a selection stays cheap to scan. 128 chunks = 2048 blocks. */
  public static final int MAX_CHUNK_SPAN = 128;

  private UltsContainerFinder() {}

  public static boolean tooLarge(BlockPos first, BlockPos second) {
    return chunkSpan(first.getX(), second.getX()) > MAX_CHUNK_SPAN
        || chunkSpan(first.getZ(), second.getZ()) > MAX_CHUNK_SPAN;
  }

  private static int chunkSpan(int first, int second) {
    return Math.abs((first >> 4) - (second >> 4)) + 1;
  }

  /** One time scan used by area binding: every loaded container position inside the cuboid. */
  public static List<BlockPos> find(ServerLevel level, BlockPos first, BlockPos second) {
    List<BlockPos> found = new ArrayList<>();
    forEach(level, first, second, found::add);
    return List.copyOf(found);
  }

  /**
   * Visits every loaded container inside the cuboid.
   *
   * <p>Large containers are visited once: both halves of a double chest are the same container, so
   * only the position that identifies it is reported.
   */
  public static void forEach(
      ServerLevel level,
      BlockPos first,
      BlockPos second,
      java.util.function.Consumer<BlockPos> visitor
  ) {
    Set<BlockPos> visited = new HashSet<>();
    forEachBlockEntity(level, first, second, entity -> {
      if (!(entity instanceof Container)) {
        return;
      }
      BlockPos position = entity.getBlockPos();
      if (visited.add(UltsContainers.identity(level, position))) {
        visitor.accept(position);
      }
    });
  }

  public static boolean isContainer(ServerLevel level, BlockPos position) {
    return level.hasChunk(position.getX() >> 4, position.getZ() >> 4)
        && level.getBlockEntity(position) instanceof Container;
  }

  private static void forEachBlockEntity(
      ServerLevel level,
      BlockPos first,
      BlockPos second,
      java.util.function.Consumer<BlockEntity> visitor
  ) {
    int minX = Math.min(first.getX(), second.getX());
    int maxX = Math.max(first.getX(), second.getX());
    int minY = Math.min(first.getY(), second.getY());
    int maxY = Math.max(first.getY(), second.getY());
    int minZ = Math.min(first.getZ(), second.getZ());
    int maxZ = Math.max(first.getZ(), second.getZ());
    for (int chunkX = minX >> 4; chunkX <= (maxX >> 4); chunkX++) {
      for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4); chunkZ++) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
          continue;
        }
        for (BlockEntity entity : chunk.getBlockEntities().values()) {
          BlockPos position = entity.getBlockPos();
          if (position.getX() < minX || position.getX() > maxX
              || position.getY() < minY || position.getY() > maxY
              || position.getZ() < minZ || position.getZ() > maxZ) {
            continue;
          }
          visitor.accept(entity);
        }
      }
    }
  }
}
