package com.flwolfy.ults.display;

import com.flwolfy.ults.util.UltsTextBuilder;
import net.minecraft.server.level.ServerPlayer;

/**
 * How a screen says something to one player.
 *
 * <p>Screens say what they could not do in the <em>chat</em> rather than above the hotbar. A line above the
 * hotbar fades after a moment, and a player who has to read why a click was refused — how much short of a
 * box the storage was, or that their backpack has no room — needs it to still be there when they look.
 */
final class UltsGuiChat {

  private UltsGuiChat() {}

  /**
   * Tells one player that what they asked for cannot be handed over, in the chat and in red.
   *
   * @param player the player being told
   * @param key the line to say
   */
  static void failure(ServerPlayer player, String key) {
    player.sendSystemMessage(UltsTextBuilder.failure(UltsGuiText.text(key)), false);
  }
}
