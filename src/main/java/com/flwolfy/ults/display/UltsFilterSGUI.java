package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsItemVisibility;
import com.flwolfy.ults.data.lang.UltsItemNames;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.data.state.UltsViewProfile;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

public final class UltsFilterSGUI extends UltsAnvilInputGui {

  private final UltsRuntime runtime;
  /** The last count worked out, and what it was worked out for, so a burst of reports costs one. */
  private Count counted;

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
    // The slots are filled before anything is worked out for them: a count that cannot be answered must
    // never be able to leave the player looking at an empty screen.
    setSlot(1, new GuiElementBuilder(Items.BARRIER)
        .setName(UltsGuiText.text("ults.filter.clear").copy().withStyle(ChatFormatting.GOLD))
        .addLoreLine(UltsGuiText.text("ults.filter.clear.hint").copy()
            .withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          UltsGuiSound.click(player);
          back("");
        })
        .build());

    GuiElementBuilder apply = new GuiElementBuilder(Items.DYE.lime())
        .setName(UltsGuiText.text("ults.filter.apply").copy().withStyle(ChatFormatting.GREEN))
        .addLoreLine(UltsGuiText.text("ults.filter.usage").copy()
            .withStyle(ChatFormatting.GRAY));
    long matches = matchesInCategory(normalize(input));
    if (matches >= 0L) {
      apply.addLoreLine(UltsGuiText.labelled(
          "ults.filter.matches", "ults.filter.matches.suffix", matches, matches < 1));
    }
    setSlot(2, apply
        .addLoreLine(UltsGuiText.text("ults.filter.apply.hint").copy()
            .withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          UltsGuiSound.confirm(player);
          back(input);
        })
        .build());
  }

  /**
   * How many rows of the category this player is looking at the typed text would leave, or {@code -1}
   * while there is nothing to count.
   *
   * <p>A search reads the category it is typed in, so this runs the very listing the storage screen
   * would draw: the number here is the number of rows the search leads to, never a count from somewhere
   * the player cannot see.
   *
   * <p>It is asked once per text the client reports, which it does on opening the screen as well as on
   * every keystroke, so two things keep it from costing more than it is worth: an empty search counts
   * nothing at all, and an answer is kept until the storage, the category, the text or the language
   * changes, which makes the burst of reports a screen sends while it settles cost one listing.
   */
  private long matchesInCategory(String filter) {
    if (filter.isEmpty()) {
      return -1L;
    }
    UltsViewProfile profile = runtime.state().viewProfile(player.getUUID());
    long revision = runtime.contentRevision();
    if (counted != null && counted.revision() == revision
        && counted.categoryId().equals(profile.categoryId())
        && counted.filter().equals(filter)
        && counted.locale().equals(locale())) {
      return counted.rows();
    }
    UltsItemCategory category = UltsCreativeCatalog.category(profile.categoryId());
    List<UltsStoredView> stored = runtime.storedItems();
    // The visibility this player sees, which is the configured default until they pick one of their
    // own — the same answer the storage screen itself gives.
    UltsItemVisibility visibility = profile.visibility() != null
        ? profile.visibility() : UltsConfigManager.getInstance().data().general().itemVisibility();
    long rows = UltsStorageSGUI.rows(
        category, UltsStorageSGUI.index(stored), stored, visibility, filter, locale(),
        template -> runtime.craftableNow(template, stored)).size();
    counted = new Count(revision, profile.categoryId(), filter, locale(), rows);
    return rows;
  }

  /** What a count was worked out for, so it is never handed out for anything else. */
  private record Count(long revision, String categoryId, String filter, String locale, long rows) {}

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

  /** The language the player's own client is set to, which is the one the search is answered in. */
  private String locale() {
    return UltsItemNames.normalize(player.clientInformation().language());
  }
}
