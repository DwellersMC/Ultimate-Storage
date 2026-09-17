package com.flwolfy.ults.input;

import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.state.UltsState;
import com.flwolfy.ults.data.state.UltsTerminal;
import com.flwolfy.ults.mixin.UltsHopperAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

public final class UltsInputManager {

  public enum CreateResult {
    SUCCESS,
    INVALID_NAME,
    ALREADY_EXISTS,
    LIMIT_REACHED,
    NOT_ON_BARREL,
    WRONG_BASE,
    POSITION_OCCUPIED
  }

  private final MinecraftServer server;
  private final UltsState state;

  public UltsInputManager(MinecraftServer server, UltsState state) {
    this.server = server;
    this.state = state;
  }

  public synchronized CreateResult create(ServerPlayer player, String requestedName) {
    String name = normalizeName(requestedName);
    if (name == null) {
      return CreateResult.INVALID_NAME;
    }
    if (state.terminal(name) != null) {
      return CreateResult.ALREADY_EXISTS;
    }
    UltsConfigData.Input config = UltsConfigManager.getInstance().data().input();
    if (config.maxTerminals() > 0 && state.terminals().size() >= config.maxTerminals()) {
      return CreateResult.LIMIT_REACHED;
    }
    ServerLevel level = player.level();
    BlockPos barrelPos = player.blockPosition().below();
    if (!(level.getBlockEntity(barrelPos) instanceof BarrelBlockEntity)) {
      return CreateResult.NOT_ON_BARREL;
    }
    BlockPos basePos = barrelPos.below();
    Block configured = BuiltInRegistries.BLOCK.getOptional(
        Identifier.parse(config.baseBlock())).orElse(Blocks.AIR);
    if (!level.getBlockState(basePos).is(configured)) {
      return CreateResult.WRONG_BASE;
    }
    UltsTerminal terminal = new UltsTerminal(
        name,
        level.dimension().identifier().toString(),
        basePos.getX(),
        basePos.getY(),
        basePos.getZ(),
        player.getUUID().toString()
    );
    return state.addTerminal(terminal) ? CreateResult.SUCCESS : CreateResult.POSITION_OCCUPIED;
  }

  public synchronized UltsTerminal delete(String name) {
    return state.removeTerminal(name);
  }

  public void tick() {
    for (UltsTerminal terminal : state.terminals()) {
      ServerLevel level = level(terminal.dimension());
      BlockPos position = terminal.barrelPos();
      if (level == null || !level.hasChunk(position.getX() >> 4, position.getZ() >> 4)
          || !(level.getBlockEntity(position) instanceof BarrelBlockEntity barrel)) {
        continue;
      }
      drain(barrel);
      drainAttachedHoppers(level, position);
    }
  }

  private void drainAttachedHoppers(ServerLevel level, BlockPos barrelPos) {
    for (Direction direction : Direction.values()) {
      BlockPos hopperPos = barrelPos.relative(direction);
      var blockState = level.getBlockState(hopperPos);
      if (!(blockState.getBlock() instanceof HopperBlock)
          || !blockState.getValue(HopperBlock.ENABLED)
          || !hopperPos.relative(blockState.getValue(HopperBlock.FACING)).equals(barrelPos)
          || !(level.getBlockEntity(hopperPos) instanceof HopperBlockEntity hopper)) {
        continue;
      }
      drain(hopper);
      ((UltsHopperAccessor) hopper).ults$setCooldown(0);
    }
  }

  private void drain(Container container) {
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
  }

  private ServerLevel level(String dimension) {
    Identifier id = Identifier.tryParse(dimension);
    return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
  }

  public static String normalizeName(String requested) {
    if (requested == null) {
      return null;
    }
    String value = requested.trim();
    if (value.isEmpty() || value.length() > 64 || value.codePoints().anyMatch(Character::isISOControl)) {
      return null;
    }
    return value;
  }
}
