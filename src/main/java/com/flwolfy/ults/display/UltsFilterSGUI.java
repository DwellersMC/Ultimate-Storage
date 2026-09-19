package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.state.UltsViewProfile;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

public final class UltsFilterSGUI extends UltsAnvilInputGui {

  private final UltsRuntime runtime;

  private UltsFilterSGUI(ServerPlayer player, UltsRuntime runtime) {
    super(player, false);
    this.runtime = runtime;
    setTitle(UltsGuiText.text("ults.filter.title"));
    setLockPlayerInventory(true);
    setDefaultInputValue("");
    showCancelSlot();
    render();
  }

  public static void open(ServerPlayer player, UltsRuntime runtime) {
    new UltsFilterSGUI(player, runtime).open();
  }

  @Override
  public void onInput(String value) {
    render();
  }

  /** Fills the action slots; the input slot keeps the cancel action and is never rewritten here. */
  private void render() {
    String input = getInput() == null ? "" : getInput();
    long matches = UltsCreativeCatalog.matches(normalize(input));

    setSlot(1, new GuiElementBuilder(Items.BARRIER)
        .setName(UltsGuiText.text("ults.filter.clear").copy().withStyle(ChatFormatting.GOLD))
        .addLoreLine(UltsGuiText.text("ults.filter.clear.hint").copy()
            .withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          UltsGuiSound.click(player);
          back("");
        })
        .build());

    setSlot(2, new GuiElementBuilder(Items.DYE.lime())
        .setName(UltsGuiText.text("ults.filter.apply").copy().withStyle(ChatFormatting.GREEN))
        .addLoreLine(UltsGuiText.text("ults.filter.usage").copy()
            .withStyle(ChatFormatting.GRAY))
        .addLoreLine(UltsGuiText.labelled(
            "ults.filter.matches", "ults.filter.matches.suffix", matches, matches < 1))
        .addLoreLine(UltsGuiText.text("ults.filter.apply.hint").copy()
            .withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          UltsGuiSound.confirm(player);
          back(input);
        })
        .build());
  }

  @Override
  protected void cancel() {
    back(runtime.state().viewProfile(player.getUUID()).filter());
  }

  private void back(String filter) {
    UltsViewProfile current = runtime.state().viewProfile(player.getUUID());
    runtime.state().setViewProfile(player.getUUID(), new UltsViewProfile(
        current.categoryId(), current.categoryPage(), current.itemPage(), normalize(filter),
        current.visibility()));
    close();
    UltsStorageSGUI.open(player, runtime);
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }
}
