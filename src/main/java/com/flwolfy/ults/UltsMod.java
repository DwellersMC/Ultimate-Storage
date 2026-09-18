package com.flwolfy.ults;

import com.flwolfy.ults.command.UltsCommand;
import com.flwolfy.ults.data.config.UltsConfigManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UltsMod implements ModInitializer {

  public static final String MOD_ID = "ultimate-storage";
  public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
  private static volatile UltsRuntime runtime;

  public static UltsRuntime getRuntime() {
    return runtime;
  }

  @Override
  public void onInitialize() {
    UltsConfigManager.getInstance();
    CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
        UltsCommand.register(dispatcher));
    ServerLifecycleEvents.SERVER_STARTED.register(server -> {
      runtime = new UltsRuntime(server);
      LOGGER.info("UltStorage is ready");
    });
    ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime = null);
    ServerTickEvents.END_SERVER_TICK.register(server -> {
      UltsRuntime active = runtime;
      if (active != null) {
        active.tick();
      }
    });
    // The inputs are only an organisational construct: breaking any of their blocks tears the input
    // down and tells everyone about it.
    PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, entity) -> {
      UltsRuntime active = runtime;
      if (active != null && !level.isClientSide()) {
        active.onBlockBroken(level, pos);
      }
    });
  }
}
