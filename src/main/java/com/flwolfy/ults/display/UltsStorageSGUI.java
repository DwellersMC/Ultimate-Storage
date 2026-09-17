package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.data.state.UltsViewProfile;
import eu.pb4.sgui.api.ClickType;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class UltsStorageSGUI extends SimpleGui {

  private static final int[] CATEGORY_SLOTS = {0, 9, 18, 27, 36, 45};
  private static final int[] CONTENT_SLOTS = {
      11, 12, 13, 14, 15, 16, 17,
      20, 21, 22, 23, 24, 25, 26,
      29, 30, 31, 32, 33, 34, 35,
      38, 39, 40, 41, 42, 43, 44,
      47, 48, 49, 50, 51, 52, 53
  };
  private static final List<WeakReference<UltsStorageSGUI>> OPEN_MENUS = new ArrayList<>();
  private final UltsRuntime runtime;
  private UltsItemCategory category;
  private int categoryPage;
  private int itemPage;
  private long renderedRevision = -1;

  private UltsStorageSGUI(ServerPlayer player, UltsRuntime runtime) {
    super(MenuType.GENERIC_9x6, player, false);
    this.runtime = runtime;
    UltsViewProfile profile = runtime.state().viewProfile(player.getUUID());
    category = UltsCreativeCatalog.category(profile.categoryId());
    categoryPage = profile.categoryPage();
    itemPage = profile.itemPage();
    setTitle(text("ults.gui.title"));
    setLockPlayerInventory(true);
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.add(new WeakReference<>(this));
    }
    render();
  }

  public static void open(ServerPlayer player, UltsRuntime runtime) {
    new UltsStorageSGUI(player, runtime).open();
  }

  public static void refreshAll(UltsRuntime runtime) {
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.removeIf(reference -> {
        UltsStorageSGUI gui = reference.get();
        if (gui == null || !gui.isOpen()) {
          return true;
        }
        if (gui.runtime == runtime && gui.renderedRevision != runtime.state().revision()) {
          gui.render();
        }
        return false;
      });
    }
  }

  private void render() {
    renderedRevision = runtime.state().revision();
    GuiElementBuilder filler = element(Items.STAINED_GLASS_PANE.gray()).setName(Component.empty());
    for (int slot = 0; slot < getVirtualSize(); slot++) {
      setSlot(slot, filler.build());
    }
    GuiElementBuilder divider = element(Items.STAINED_GLASS_PANE.brown())
        .setName(Component.empty());
    for (int row = 0; row < 6; row++) {
      setSlot(row * 9 + 1, divider.build());
    }
    GuiElementBuilder content = element(Items.STAINED_GLASS_PANE.black())
        .setName(Component.empty());
    for (int slot : CONTENT_SLOTS) {
      setSlot(slot, content.build());
    }

    List<UltsItemCategory> categories = UltsCreativeCatalog.categories();
    int categoryPages = Math.max(1, (categories.size() + CATEGORY_SLOTS.length - 1)
        / CATEGORY_SLOTS.length);
    categoryPage = Math.floorMod(categoryPage, categoryPages);
    int firstCategory = categoryPage * CATEGORY_SLOTS.length;
    int lastCategory = Math.min(firstCategory + CATEGORY_SLOTS.length, categories.size());
    for (int index = firstCategory; index < lastCategory; index++) {
      setSlot(CATEGORY_SLOTS[index - firstCategory], categoryButton(categories.get(index)));
    }

    if (category == null && !categories.isEmpty()) {
      category = categories.getFirst();
    }
    List<UltsStoredView> items = category == null ? List.of() : runtime.state().items().stream()
        .filter(category::accepts).toList();
    int itemPages = Math.max(1, (items.size() + CONTENT_SLOTS.length - 1) / CONTENT_SLOTS.length);
    itemPage = Math.floorMod(itemPage, itemPages);
    int firstItem = itemPage * CONTENT_SLOTS.length;
    int lastItem = Math.min(firstItem + CONTENT_SLOTS.length, items.size());
    for (int index = firstItem; index < lastItem; index++) {
      setSlot(CONTENT_SLOTS[index - firstItem], itemButton(items.get(index)));
    }

    if (itemPages > 1) {
      setSlot(2, arrow("MHF_ArrowLeft", "ults.gui.previous", () -> changeItemPage(-1, itemPages)));
      setSlot(8, arrow("MHF_ArrowRight", "ults.gui.next", () -> changeItemPage(1, itemPages)));
    }
    if (categoryPages > 1) {
      setSlot(3, arrow(
          "MHF_ArrowLeft", "ults.gui.category.previous", () -> changeCategoryPage(-1, categoryPages)));
      setSlot(7, arrow(
          "MHF_ArrowRight", "ults.gui.category.next", () -> changeCategoryPage(1, categoryPages)));
    }
    setSlot(5, element(Items.WRITABLE_BOOK)
        .setName(text("ults.gui.status").copy().withStyle(ChatFormatting.GOLD))
        .addLoreLine(text("ults.gui.category", category == null
            ? "-" : category.displayName().getString()))
        .addLoreLine(text("ults.gui.page", itemPage + 1, itemPages))
        .addLoreLine(text("ults.gui.category.page", categoryPage + 1, categoryPages))
        .addLoreLine(text("ults.gui.types", items.size()))
        .build());
    saveView();
  }

  private GuiElementBuilder categoryButton(UltsItemCategory value) {
    boolean selected = category != null && category.id().equals(value.id());
    GuiElementBuilder builder = element(value.icon())
        .setName(value.displayName().copy().withStyle(
            selected ? ChatFormatting.GREEN : ChatFormatting.GRAY))
        .addLoreLine(text("ults.gui.category.select"))
        .setCallback(() -> {
          category = value;
          itemPage = 0;
          render();
        });
    if (selected) {
      builder.glow();
    }
    return builder;
  }

  private GuiElementBuilder itemButton(UltsStoredView view) {
    int visible = (int) Math.min(view.amount(), view.template().getMaxStackSize());
    ItemStack display = view.template().copyWithCount(Math.max(1, visible));
    return new GuiElementBuilder(display)
        .addLoreLine(Component.empty())
        .addLoreLine(text("ults.gui.amount", format(view.amount()))
            .copy().withStyle(ChatFormatting.GOLD))
        .addLoreLine(text("ults.gui.open_withdraw").copy().withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          if (type == ClickType.MOUSE_LEFT) {
            UltsWithdrawSGUI.open(player, runtime, view.template());
          }
        });
  }

  private eu.pb4.sgui.api.elements.GuiElement arrow(
      String profile,
      String key,
      Runnable callback
  ) {
    return element(Items.PLAYER_HEAD)
        .setProfile(profile)
        .setName(text(key).copy().withStyle(ChatFormatting.AQUA))
        .setCallback(callback)
        .build();
  }

  private void changeItemPage(int offset, int pages) {
    itemPage = Math.floorMod(itemPage + offset, pages);
    render();
  }

  private void changeCategoryPage(int offset, int pages) {
    categoryPage = Math.floorMod(categoryPage + offset, pages);
    render();
  }

  private void saveView() {
    runtime.state().setViewProfile(player.getUUID(), new UltsViewProfile(
        category == null ? "" : category.id(), categoryPage, itemPage));
  }

  private static String format(long amount) {
    return String.format(java.util.Locale.ROOT, "%,d", amount);
  }

  private static Component text(String key, Object... arguments) {
    return UltsLangManager.getInstance().text(key, arguments);
  }

  private static GuiElementBuilder element(net.minecraft.world.item.Item item) {
    return new GuiElementBuilder(item).hideDefaultTooltip();
  }

  private static GuiElementBuilder element(ItemStack stack) {
    return new GuiElementBuilder(stack);
  }
}
