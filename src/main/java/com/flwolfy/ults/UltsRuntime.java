package com.flwolfy.ults;

import com.flwolfy.ults.data.state.UltsState;
import com.flwolfy.ults.display.UltsCreativeCatalog;
import com.flwolfy.ults.display.UltsStorageSGUI;
import com.flwolfy.ults.display.UltsWithdrawSGUI;
import com.flwolfy.ults.input.UltsInputManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

public final class UltsRuntime {

  private final MinecraftServer server;
  private final UltsState state;
  private final UltsInputManager inputs;

  UltsRuntime(MinecraftServer server) {
    this.server = server;
    UltsCreativeCatalog.rebuild(server);
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

  public boolean protects(Level level, BlockPos position) {
    return state.protects(level.dimension().identifier().toString(), position);
  }

  void tick() {
    inputs.tick();
    UltsStorageSGUI.refreshAll(this);
    UltsWithdrawSGUI.refreshAll(this);
  }
}
