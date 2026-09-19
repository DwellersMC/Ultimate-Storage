package com.flwolfy.ults.display;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Click feedback of the Ults screens.
 *
 * <p>The sound is sent to the player who clicked only, so one player browsing the storage never makes
 * another player's game beep. Cancelling a screen stays silent, because leaving a screen needs no
 * confirmation.
 */
final class UltsGuiSound {

  private UltsGuiSound() {}

  /** A light click for a click that opens something, pages, or changes a selection. */
  static void click(ServerPlayer player) {
    play(player, SoundEvents.UI_BUTTON_CLICK, SoundSource.MASTER, 0.4F, 1.0F);
  }

  /** A brighter confirmation for a click that really applied something. */
  static void confirm(ServerPlayer player) {
    play(player, Holder.direct(SoundEvents.EXPERIENCE_ORB_PICKUP), SoundSource.PLAYERS, 0.6F, 1.35F);
  }

  private static void play(
      ServerPlayer player,
      Holder<SoundEvent> event,
      SoundSource source,
      float volume,
      float pitch
  ) {
    player.connection.send(new ClientboundSoundEntityPacket(
        event, source, player, volume, pitch, player.getRandom().nextLong()));
  }
}
