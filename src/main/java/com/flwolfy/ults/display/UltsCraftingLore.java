package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.state.UltsStoredView;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;

/** The same additional crafting amount is shown on stored rows, bag rows and quantity previews. */
final class UltsCraftingLore {
  static UltsRuntime.CraftingAmount add(GuiElementBuilder builder, UltsRuntime runtime,
      ItemStack template, List<UltsStoredView> stock) {
    UltsRuntime.CraftingAmount value = runtime.craftingAmount(template, stock);
    if (runtime.craftingMode().enabled() && (value.pending() || value.amount() > 0L)) builder.addLoreLine(UltsGuiText.labelled(
        "ults.gui.craftable", text(value), value.noStation()));
    return value;
  }

  static void stored(GuiElementBuilder builder, String key, long amount, boolean pending) {
    builder.addLoreLine(UltsGuiText.labelled(key, pending
        ? UltsGuiText.text("ults.gui.craftable.pending").getString() : UltsGuiText.format(amount),
        !pending && amount == 0L));
  }

  /** Rechecks live contents, so an old icon callback cannot bypass the waiting state. */
  static boolean ready(UltsRuntime runtime, ServerPlayer player, ItemStack template) {
    return ready(runtime, player, template, false);
  }

  static boolean ready(UltsRuntime runtime, ServerPlayer player, ItemStack template, boolean boxed) {
    var stock = runtime.storedItemsFresh();
    if (!runtime.craftingAmountFresh(template, stock).pending()
        && (!boxed || !runtime.packagingPending(stock))) return true;
    UltsGuiChat.failure(player, "ults.withdraw.problem.pending");
    return false;
  }

  static String text(UltsRuntime.CraftingAmount value) {
    if (value.pending()) return UltsGuiText.text("ults.gui.craftable.pending").getString();
    if (value.noStation()) return UltsGuiText.text("ults.gui.craftable.no_station").getString();
    return UltsGuiText.format(value.amount());
  }
}
