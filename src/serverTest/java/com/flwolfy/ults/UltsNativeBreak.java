package com.flwolfy.ults;

import com.flwolfy.ults.data.state.UltsBinding;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

/** Breaks the unbound half of a real double chest, without connecting a game client. */
public final class UltsNativeBreak {
  public static void check(MinecraftServer server, UltsRuntime runtime, BiConsumer<String, Boolean> check) {
    var level = server.overworld();
    level.getChunk(2, 0);
    var left = new BlockPos(36, 64, 0);
    var right = left.east();
    level.setBlock(left, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
        .setValue(ChestBlock.TYPE, ChestType.LEFT), 2);
    level.setBlock(right, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
        .setValue(ChestBlock.TYPE, ChestType.RIGHT), 2);
    var binding = new UltsBinding("break", level.dimension().identifier().toString(),
        left.getX(), left.getY(), left.getZ(), "test");
    runtime.state().addBinding(binding);
    var player = new ServerPlayer(server, level, new GameProfile(UUID.randomUUID(), "UltsBreak"),
        ClientInformation.createDefault());
    player.setShiftKeyDown(true);
    check.accept("sneaking break of other chest half is allowed", runtime.allowBreak(player, level, right));
    runtime.tick(); // Another listener cancelled: no AFTER event was delivered.
    check.accept("cancelled break preserves binding", runtime.state().number(binding) > 0);
    check.accept("second sneaking break is allowed", runtime.allowBreak(player, level, right));
    level.setBlock(right, Blocks.AIR.defaultBlockState(), 3);
    runtime.onBlockBroken(level, right);
    check.accept("breaking other chest half removes original binding", runtime.state().number(binding) == 0);
    runtime.onBlockBroken(level, right);
    check.accept("duplicate break notification does not remove another binding", runtime.state().bindingCount() == 1);
  }
}
