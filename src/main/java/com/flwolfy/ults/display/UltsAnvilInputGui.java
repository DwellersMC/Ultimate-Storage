package com.flwolfy.ults.display;

import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/**
 * Shared behaviour for the anvil based Ults screens.
 *
 * <p>The vanilla client copies the name of the item in the input slot into its rename field every time
 * that slot is sent, and it rebuilds the result slot whenever the typed text changes. Therefore:
 *
 * <ul>
 *   <li>the input slot is written once, when the screen opens, and never while the player types - a
 *       send per keystroke would push the server's view of the text back into the field, which makes
 *       the field and the item name jump around while typing fast,
 *   <li>the cancel action shown in the input slot carries no name, because any name would end up in the
 *       field; its label is the first tooltip line instead,
 *   <li>the action slots are only ever written through SGUI, which re-sends the result slot itself
 *       after every input.
 * </ul>
 */
abstract class UltsAnvilInputGui extends AnvilInputGui {

  private static final String CANCEL_KEY = "ults.anvil.cancel";

  UltsAnvilInputGui(ServerPlayer player, boolean manipulatePlayerSlots) {
    super(player, manipulatePlayerSlots);
  }

  /** Places the cancel action in the input slot; call once, when the screen is built. */
  protected void showCancelSlot() {
    setSlot(0, cancelElement());
  }

  private GuiElement cancelElement() {
    return new GuiElementBuilder(Items.DYE.red())
        .setName(Component.empty())
        .addLoreLine(UltsGuiText.text(CANCEL_KEY).copy().withStyle(ChatFormatting.RED))
        .setCallback((slot, type, action, gui) -> cancel())
        .build();
  }

  /** Closes this screen and goes back to the storage screen. */
  protected abstract void cancel();
}
