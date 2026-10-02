package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.state.UltsStackKinds;
import com.mojang.authlib.GameProfile;
import java.util.*;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;

/** Exercises real inventory insertion, world rejection, and storage rollback without a game client. */
public final class UltsNativeDelivery {
  public static void check(MinecraftServer server, UltsRuntime runtime, BiConsumer<String, Boolean> check) {
    var player = new ServerPlayer(server, server.overworld(),
        new GameProfile(UUID.randomUUID(), "UltsAcceptance"), ClientInformation.createDefault());
    player.setPos(8.5, 64, 8.5);
    var target = Items.COBBLESTONE.getDefaultInstance();
    target.set(DataComponents.CUSTOM_NAME, Component.literal("ults acceptance delivery"));
    boolean[] rejects = {false};
    List<ItemEntity> entities = new ArrayList<>();
    ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
      if (entity instanceof ItemEntity item && UltsStackKinds.same(item.getItem(), target)) {
        entities.add(item);
        if (rejects[0]) item.discard();
      }
    });
    try {
      for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
      runtime.state().deposit(target.copyWithCount(64));
      var outputs = runtime.takePlanned(target, 64, false, UltsCraftingMode.DISABLED);
      rejects[0] = true;
      var result = UltsGuiGive.hand(player, runtime, outputs, true);
      check.accept("actual rejected world delivery reports zero delivered", result.delivered() == 0 && result.rejected() == 64);
      check.accept("actual rejected world delivery returns items to storage", UltsRuntime.storedAmount(target, runtime.state().items()) == 64);
      check.accept("rejected item entity is absent", entities.stream().allMatch(ItemEntity::isRemoved));

      player.getInventory().setItem(0, target.copyWithCount(60));
      outputs = runtime.takePlanned(target, 64, false, UltsCraftingMode.DISABLED);
      result = UltsGuiGive.hand(player, runtime, outputs, true);
      check.accept("actual partial delivery reports only inventory insertion", result.delivered() == 4 && result.rejected() == 60);
      check.accept("actual partial delivery preserves rejected remainder", UltsRuntime.storedAmount(target, runtime.state().items()) == 60
          && player.getInventory().getItem(0).getCount() == 64);

      outputs = runtime.takePlanned(target, 60, false, UltsCraftingMode.DISABLED);
      rejects[0] = false;
      result = UltsGuiGive.hand(player, runtime, outputs, true);
      check.accept("actual accepted world delivery reports dropped quantity", result.delivered() == 60 && result.ground() == 60 && result.complete());
      check.accept("accepted world delivery is not duplicated in storage", UltsRuntime.storedAmount(target, runtime.state().items()) == 0);
    } finally {
      rejects[0] = false;
      entities.forEach(ItemEntity::discard);
    }
  }
}
