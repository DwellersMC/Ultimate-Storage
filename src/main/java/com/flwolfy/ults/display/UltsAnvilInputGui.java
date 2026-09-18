package com.flwolfy.ults.display;

import eu.pb4.sgui.api.SguiUtils;
import eu.pb4.sgui.api.containerwrappers.SlotBasedWrapperMenu;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Shared behaviour for the anvil based Ults screens.
 *
 * <p>The vanilla client rebuilds its own anvil result whenever an input slot changes and clears the
 * result slot in the process, so an action element placed there (the confirm/apply buttons) would
 * silently vanish. Every input change is therefore followed by an ordered re-send of the action
 * slots, and the slots are marked as synced so the periodic menu broadcast cannot resend an input
 * slot on its own and clear the button again.
 */
abstract class UltsAnvilInputGui extends AnvilInputGui {

  private static final int[] ACTION_SLOTS = {0, 1, 2};

  UltsAnvilInputGui(ServerPlayer player, boolean manipulatePlayerSlots) {
    super(player, manipulatePlayerSlots);
  }

  protected void syncActionSlots() {
    SlotBasedWrapperMenu menu = wrappedMenu;
    if (menu == null || !isOpen()) {
      return;
    }
    for (int slot : ACTION_SLOTS) {
      ItemStack stack = menu.getSlot(slot).getItem().copy();
      SguiUtils.sendSlotUpdate(player, getSyncId(), slot, stack, menu.getStateId());
      menu.setRemoteSlot(slot, stack);
    }
  }
}
