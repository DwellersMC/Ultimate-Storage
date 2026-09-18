package com.flwolfy.ults.visual;

import com.flwolfy.ults.data.state.UltsBinding;
import com.flwolfy.ults.input.UltsContainers;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Outlines the bound containers with a glowing copy of the block, for one player at a time.
 *
 * <p>The display entities are only ever sent as packets to the viewer, so they exist on that client
 * alone: no server entity, no collision, nothing in the world save, and another player never sees
 * them. A large container gets one copy per block, so the whole container glows. Every copy is put
 * exactly on its block: the block model of a display starts at the entity position, so the entity
 * sits on the block corner and the small enlargement is centred with a matching translation.
 *
 * <p>Every copy keeps its entity id for as long as it stays visible: re-creating them would make the
 * client build the display again on every refresh, which shows up as a flicker.
 *
 * <p>Because the server does not know these client side entities, a single lost or ineffective
 * removal packet would leave a copy behind forever, far away and long after it should be gone. Every
 * removed id therefore stays in a queue that repeats the removal for a while.
 */
public final class UltsHighlights {

  private static final int REFRESH_TICKS = 10;
  /** How long the removal of a copy keeps being repeated, in ticks. */
  private static final int REMOVAL_TICKS = 200;
  private static final int MAX_BOXES = 16;
  private static final double RADIUS = 48.0D;
  /** How far the glowing copy sticks out around its block. */
  private static final float MARGIN = 0.01F;
  private static final float SCALE = 1.0F + 2.0F * MARGIN;
  private static final int GLOW_COLOR = 0xFF66FF66;
  private static final byte GLOWING_FLAG = 0x40;
  /** Client side entities get ids far above the ones the server hands out. */
  private static final AtomicInteger NEXT_ID = new AtomicInteger(1_000_000_000);

  private record Nearby(UltsBinding binding, double distance) {}

  private record Copy(int entityId, BlockState state) {}

  private static ServerLevel level(MinecraftServer server, String dimension) {
    Identifier id = Identifier.tryParse(dimension);
    return id == null ? null
        : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
  }

  /** The copies one player sees. The player reference changes when they log in again. */
  private static final class Viewer {

    private final ServerPlayer player;
    private final String dimension;
    private final Map<String, Copy> copies = new HashMap<>();

    private Viewer(ServerPlayer player, String dimension) {
      this.player = player;
      this.dimension = dimension;
    }
  }

  private final Map<UUID, Viewer> viewers = new HashMap<>();
  /** ids that were dropped but whose removal is repeated until the deadline passes. */
  private final Map<UUID, Map<Integer, Integer>> removals = new HashMap<>();
  private int tick;

  /** Whether this player currently sees the highlight. */
  public boolean enabled(ServerPlayer player) {
    return viewers.containsKey(player.getUUID());
  }

  /** Whether anybody uses the highlight right now, so the caller can skip the work entirely. */
  public boolean active() {
    return !viewers.isEmpty() || !removals.isEmpty();
  }

  public void setEnabled(ServerPlayer player, boolean value) {
    if (value) {
      viewers.computeIfAbsent(
          player.getUUID(), id -> new Viewer(player, dimension(player)));
      return;
    }
    Viewer viewer = viewers.remove(player.getUUID());
    if (viewer != null) {
      drop(viewer.player, viewer.copies.values());
      viewer.copies.clear();
    }
  }

  /** How many containers this player would see highlighted right now. */
  public int visible(MinecraftServer server, ServerPlayer player, List<UltsBinding> bindings) {
    return nearest(player, index(server, bindings)).size();
  }

  /** Refreshes the copies of every viewer; called once per server tick. */
  public void tick(MinecraftServer server, List<UltsBinding> bindings) {
    if (viewers.isEmpty() && removals.isEmpty()) {
      return;
    }
    if (++tick % REFRESH_TICKS != 0) {
      return;
    }
    repeatRemovals(server);
    if (viewers.isEmpty()) {
      return;
    }
    viewers.entrySet().removeIf(entry -> server.getPlayerList().getPlayer(entry.getKey()) == null);
    // One index for every viewer: the binding list is only walked once per refresh.
    Map<String, Map<Long, List<UltsBinding>>> index = index(server, bindings);
    for (ServerPlayer player : server.getPlayerList().getPlayers()) {
      Viewer viewer = viewers.get(player.getUUID());
      if (viewer == null) {
        continue;
      }
      if (viewer.player != player || !viewer.dimension.equals(dimension(player))) {
        // Logging in again or changing the dimension wipes every client side entity: start over.
        drop(viewer.player, viewer.copies.values());
        viewer = new Viewer(player, dimension(player));
        viewers.put(player.getUUID(), viewer);
      }
      update(player, viewer, nearest(player, index));
    }
  }

  /** Adds the copies that became visible, updates changed blocks and drops the rest. */
  private void update(ServerPlayer player, Viewer viewer, List<UltsBinding> bindings) {
    ServerLevel level = (ServerLevel) player.level();
    Set<String> wanted = new HashSet<>();
    for (UltsBinding binding : bindings) {
      for (BlockPos part : UltsContainers.parts(level, binding.pos())) {
        if (!level.hasChunk(part.getX() >> 4, part.getZ() >> 4)) {
          continue;
        }
        String key = Long.toString(part.asLong());
        wanted.add(key);
        BlockState state = level.getBlockState(part);
        Copy copy = viewer.copies.get(key);
        if (copy == null) {
          viewer.copies.put(key, new Copy(show(player, part, state), state));
        } else if (copy.state() != state) {
          // The container was replaced by another block: same entity, new shape, no flicker.
          player.connection.send(new ClientboundSetEntityDataPacket(
              copy.entityId(), data(state)));
          viewer.copies.put(key, new Copy(copy.entityId(), state));
        }
      }
    }
    List<Copy> gone = new ArrayList<>();
    viewer.copies.entrySet().removeIf(entry -> {
      if (wanted.contains(entry.getKey())) {
        return false;
      }
      gone.add(entry.getValue());
      return true;
    });
    drop(player, gone);
  }

  /** Removes copies now and keeps repeating that removal, in case this one does not arrive. */
  private void drop(ServerPlayer player, Collection<Copy> copies) {
    if (copies.isEmpty()) {
      return;
    }
    List<Integer> ids = copies.stream().map(Copy::entityId).toList();
    remove(player, ids);
    Map<Integer, Integer> queue = removals.computeIfAbsent(
        player.getUUID(), id -> new HashMap<>());
    int deadline = tick + REMOVAL_TICKS;
    ids.forEach(id -> queue.put(id, deadline));
  }

  /** Repeats the pending removals and forgets the ones that expired or whose player left. */
  private void repeatRemovals(MinecraftServer server) {
    removals.entrySet().removeIf(entry -> {
      ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
      if (player == null) {
        // Nothing to clean up on a client that is gone.
        return true;
      }
      Map<Integer, Integer> queue = entry.getValue();
      queue.entrySet().removeIf(pending -> pending.getValue() <= tick);
      if (queue.isEmpty()) {
        return true;
      }
      remove(player, List.copyOf(queue.keySet()));
      return false;
    });
  }

  private static void remove(ServerPlayer player, List<Integer> ids) {
    if (ids.isEmpty()) {
      return;
    }
    int[] raw = new int[ids.size()];
    for (int index = 0; index < raw.length; index++) {
      raw[index] = ids.get(index);
    }
    player.connection.send(new ClientboundRemoveEntitiesPacket(raw));
  }

  /** One glowing copy of the block at this position, sent to this player only. */
  private static int show(ServerPlayer player, BlockPos position, BlockState state) {
    int id = NEXT_ID.getAndIncrement();
    // The block model of a display starts at the entity position, so the corner is the right spot.
    player.connection.send(new ClientboundAddEntityPacket(
        id,
        UUID.randomUUID(),
        position.getX(),
        position.getY(),
        position.getZ(),
        0.0F,
        0.0F,
        EntityTypes.BLOCK_DISPLAY,
        0,
        Vec3.ZERO,
        0.0D
    ));
    player.connection.send(new ClientboundSetEntityDataPacket(id, data(state)));
    return id;
  }

  private static List<SynchedEntityData.DataValue<?>> data(BlockState state) {
    List<SynchedEntityData.DataValue<?>> data = new ArrayList<>();
    data.add(SynchedEntityData.DataValue.create(Display.BlockDisplay.DATA_BLOCK_STATE_ID, state));
    data.add(SynchedEntityData.DataValue.create(
        Display.DATA_TRANSLATION_ID, new Vector3f(-MARGIN, -MARGIN, -MARGIN)));
    data.add(SynchedEntityData.DataValue.create(
        Display.DATA_SCALE_ID, new Vector3f(SCALE, SCALE, SCALE)));
    data.add(SynchedEntityData.DataValue.create(
        Display.DATA_GLOW_COLOR_OVERRIDE_ID, GLOW_COLOR));
    data.add(SynchedEntityData.DataValue.create(Entity.DATA_SHARED_FLAGS_ID, GLOWING_FLAG));
    return data;
  }

  /**
   * The bindings of the loaded chunks, grouped by dimension and chunk.
   *
   * <p>Walking every binding for every viewer would not scale on a busy server, so this index is
   * built once per refresh and each viewer only looks at the chunks around them.
   */
  private Map<String, Map<Long, List<UltsBinding>>> index(
      MinecraftServer server,
      List<UltsBinding> bindings
  ) {
    Map<String, Map<Long, List<UltsBinding>>> byDimension = new HashMap<>();
    Map<String, ServerLevel> levels = new HashMap<>();
    for (UltsBinding binding : bindings) {
      ServerLevel level = levels.get(binding.dimension());
      if (level == null) {
        level = level(server, binding.dimension());
        if (level == null) {
          continue;
        }
        levels.put(binding.dimension(), level);
      }
      if (!level.hasChunk(binding.x() >> 4, binding.z() >> 4)) {
        continue;
      }
      byDimension
          .computeIfAbsent(binding.dimension(), key -> new HashMap<>())
          .computeIfAbsent(
              ChunkPos.pack(binding.x() >> 4, binding.z() >> 4), key -> new ArrayList<>())
          .add(binding);
    }
    return byDimension;
  }

  /** The bindings near this player, nearest first, read from the shared chunk index. */
  private List<UltsBinding> nearest(
      ServerPlayer player,
      Map<String, Map<Long, List<UltsBinding>>> index
  ) {
    Map<Long, List<UltsBinding>> chunks = index.get(dimension(player));
    if (chunks == null || chunks.isEmpty()) {
      return List.of();
    }
    int centerX = player.getBlockX() >> 4;
    int centerZ = player.getBlockZ() >> 4;
    int span = (int) Math.ceil(RADIUS / 16.0D);
    List<Nearby> nearby = new ArrayList<>();
    for (int chunkX = centerX - span; chunkX <= centerX + span; chunkX++) {
      for (int chunkZ = centerZ - span; chunkZ <= centerZ + span; chunkZ++) {
        List<UltsBinding> candidates = chunks.get(ChunkPos.pack(chunkX, chunkZ));
        if (candidates == null) {
          continue;
        }
        for (UltsBinding binding : candidates) {
          double distance = squaredDistance(player, binding);
          if (distance <= RADIUS * RADIUS) {
            nearby.add(new Nearby(binding, distance));
          }
        }
      }
    }
    nearby.sort(Comparator.comparingDouble(Nearby::distance));
    List<UltsBinding> result = new ArrayList<>();
    for (Nearby entry : nearby) {
      if (result.size() >= MAX_BOXES) {
        break;
      }
      result.add(entry.binding());
    }
    return result;
  }

  private static double squaredDistance(ServerPlayer player, UltsBinding binding) {
    double x = player.getX() - (binding.x() + 0.5D);
    double y = player.getY() - (binding.y() + 0.5D);
    double z = player.getZ() - (binding.z() + 0.5D);
    return x * x + y * y + z * z;
  }

  private static String dimension(ServerPlayer player) {
    return player.level().dimension().identifier().toString();
  }
}
