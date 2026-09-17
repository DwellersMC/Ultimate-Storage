package com.flwolfy.ults;

import com.flwolfy.ults.command.UltsCommand;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.util.UltsTextBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
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
      LOGGER.info("Ultimate-Storage is ready");
    });
    ServerLifecycleEvents.SERVER_STOPPING.register(server -> runtime = null);
    ServerTickEvents.END_SERVER_TICK.register(server -> {
      UltsRuntime active = runtime;
      if (active != null) {
        active.tick();
      }
    });
    PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, entity) -> {
      UltsRuntime active = runtime;
      if (active == null || !active.protects(level, pos)) {
        return true;
      }
      player.sendSystemMessage(UltsTextBuilder.info(
          UltsLangManager.getInstance().text("ults.input.protected")));
      return false;
    });
    AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
      UltsRuntime active = runtime;
      return active != null && active.protects(level, pos)
          ? InteractionResult.FAIL : InteractionResult.PASS;
    });
    UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
      UltsRuntime active = runtime;
      if (active == null || !active.protects(level, hit.getBlockPos())) {
        return InteractionResult.PASS;
      }
      ItemStack held = player.getItemInHand(hand);
      if (held.getItem() instanceof BlockItem) {
        InteractionResult placement = held.useOn(new UseOnContext(player, hand, hit));
        return placement == InteractionResult.PASS ? InteractionResult.FAIL : placement;
      }
      if (!level.isClientSide()) {
        player.sendSystemMessage(UltsTextBuilder.info(
            UltsLangManager.getInstance().text("ults.input.protected")));
      }
      return InteractionResult.FAIL;
    });
  }
}
