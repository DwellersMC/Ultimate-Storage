package com.flwolfy.ults.display;

import com.flwolfy.ults.crafting.UltsCraftMath;
import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.lang.UltsItemNames;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.data.state.UltsWithdrawalOutput;
import com.flwolfy.ults.data.state.UltsWithdrawalPlan;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What a bag row opens: the things inside the bag, themselves.
 *
 * <p>A bag holds every stack of one item, newest first, one stored stack to a slot and a whole page of
 * them at a time, with the arrows beside the book while there is more than one page. A left click takes
 * the stack in that slot, whole, straight into the backpack; shift and a left click packs a full shulker
 * box of it; a right click asks how many, which is worth asking exactly when the stack holds more than
 * one piece.
 *
 * <p>The search the player typed in the storage screen applies here too, which is what makes a bag of
 * enchanted books answer "sharpness" with the books that have it: only the matching stacks are laid out,
 * the book says which search is on, and both the pages and the arrows follow the stacks that are left.
 */
public final class UltsBagSGUI extends SimpleGui {

  /** Five rows of the screen, the whole width: one page of the grid a bag is measured in. */
  private static final int CONTENT_SLOTS = UltsConfigData.BUNDLE_PAGE_SIZE;
  private static final int BACK_SLOT = 45;
  private static final int PREVIOUS_SLOT = 48;
  private static final int STATUS_SLOT = 49;
  private static final int NEXT_SLOT = 50;
  private static final List<WeakReference<UltsBagSGUI>> OPEN_MENUS = new ArrayList<>();

  private final UltsRuntime runtime;
  private final Item item;
  private final String filter;
  private int page;
  private long renderedRevision = -1;

  private UltsBagSGUI(ServerPlayer player, UltsRuntime runtime, Item item, int page) {
    super(MenuType.GENERIC_9x6, player, false);
    this.runtime = runtime;
    this.item = item;
    this.page = page;
    // The search belongs to the player rather than to a screen, so a bag opened from a filtered listing
    // is filtered the same way, and so is the bag a withdrawal or a take-everything comes back to.
    this.filter = runtime.state().viewProfile(player.getUUID()).filter();
    setTitle(item.getDefaultInstance().getHoverName());
    setLockPlayerInventory(true);
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.add(new WeakReference<>(this));
    }
    render();
  }

  /**
   * Opens the bag of one item.
   *
   * @param player the player looking
   * @param runtime the storage
   * @param item the item whose stored stacks the bag holds
   */
  public static void open(ServerPlayer player, UltsRuntime runtime, Item item) {
    new UltsBagSGUI(player, runtime, item, 0).open();
  }

  /** Opens the bag on the page it was last left on, which is how a withdrawal comes back to it. */
  public static void open(ServerPlayer player, UltsRuntime runtime, Item item, int page) {
    new UltsBagSGUI(player, runtime, item, page).open();
  }

  /** Redraws every open bag whose storage changed, the way the storage screen is redrawn. */
  public static void refreshAll(UltsRuntime runtime) {
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.removeIf(reference -> {
        UltsBagSGUI gui = reference.get();
        if (gui == null || !gui.isOpen()) {
          return true;
        }
        if (gui.runtime == runtime && (gui.renderedRevision != runtime.contentRevision()
            || runtime.craftablePending())) {
          gui.render();
        }
        return false;
      });
    }
  }

  private void render() {
    renderedRevision = runtime.contentRevision();
    GuiElementBuilder filler = element(Items.STAINED_GLASS_PANE.gray()).setName(Component.empty());
    for (int slot = 0; slot < getVirtualSize(); slot++) {
      setSlot(slot, filler.build());
    }
    GuiElementBuilder content = element(Items.STAINED_GLASS_PANE.black()).setName(Component.empty());
    for (int slot = 0; slot < CONTENT_SLOTS; slot++) {
      setSlot(slot, content.build());
    }

    List<UltsStoredView> kept = runtime.specialsOf(item);
    // The search the player typed in the storage screen is still on, so a bag shows the stacks it keeps
    // and not the ones it holds: what the listing promised is what opening it delivers.
    List<UltsStoredView> rows = shown(kept, filter, locale());
    int pages = Math.max(1, (rows.size() + CONTENT_SLOTS - 1) / CONTENT_SLOTS);
    page = Math.floorMod(page, pages);
    int first = page * CONTENT_SLOTS;
    int last = Math.min(first + CONTENT_SLOTS, rows.size());
    // One question for the whole screen: whether there is a box to fill at all is a fact about the
    // storage, not about a row, so the rows do not each ask it again.
    for (int index = first; index < last; index++) {
      setSlot(index - first, rowButton(rows.get(index)));
    }
    if (rows.isEmpty()) {
      setSlot(CONTENT_SLOTS / 2, element(Items.PAPER)
          .setName(UltsGuiText.text(
              filter.isEmpty() ? "ults.special.empty" : "ults.special.empty.filter")
              .copy().withStyle(ChatFormatting.GRAY))
          .build());
    }

    setSlot(BACK_SLOT, element(Items.DARK_OAK_DOOR)
        .setName(UltsGuiText.text("ults.special.back").copy().withStyle(ChatFormatting.YELLOW))
        .addLoreLine(UltsGuiText.text("ults.special.back.hint").copy()
            .withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          UltsGuiSound.click(player);
          close();
          UltsStorageSGUI.open(player, runtime);
        }));

    if (pages > 1) {
      setSlot(PREVIOUS_SLOT, arrow("ults.gui.previous", -1, pages));
      setSlot(NEXT_SLOT, arrow("ults.gui.next", 1, pages));
    }

    setSlot(STATUS_SLOT, statusBook(kept.size(), rows.size(), pages));
  }

  /**
   * The book between the arrows: what the bag holds, which page is open, and which search is on.
   *
   * <p>The search is named here rather than left to be remembered, because it is what decides which
   * stacks the bag is showing at all: a bag that looks half empty is saying that half of it was filtered
   * out, and this is where it says so. It glints while one is on, the way the storage screen's own book
   * does.
   */
  private GuiElementBuilder statusBook(int kept, int shown, int pages) {
    boolean takeAll = runtime.allowBulkWithdrawal();
    GuiElementBuilder book = element(Items.WRITABLE_BOOK)
        .setName(UltsGuiText.text("ults.special.status").copy().withStyle(ChatFormatting.YELLOW));
    UltsCraftingLore.stored(book, "ults.special.status.rows", kept, runtime.stockPending(item, runtime.storedItems()));
    if (!filter.isEmpty()) {
      book.addLoreLine(UltsGuiText.labelled("ults.special.status.shown", shown, shown == 0));
    }
    book.addLoreLine(UltsGuiText.labelled(
            "ults.special.status.pages", (page + 1) + " / " + pages, false))
        .addLoreLine(filter.isEmpty()
            ? UltsGuiText.label("ults.gui.filter.none")
            : UltsGuiText.labelled("ults.gui.filter", filter, false))
        .addLoreLine(UltsGuiText.text("ults.special.status.hint").copy()
            .withStyle(ChatFormatting.GRAY));
    if (takeAll) {
      // Emptying the bag belongs to the bag and not to the row that opens it, so it is offered here,
      // where the whole bag is in view. A bag holds what somebody put in, which no recipe makes, so
      // there the crafting answer is not on offer and the screen says so by leaving it out.
      book.addLoreLine(UltsGuiText.text("ults.special.status.take").copy()
              .withStyle(ChatFormatting.GRAY))
          .setCallback((slot, type, action, gui) -> {
            if (type == ClickType.MOUSE_RIGHT) {
              UltsGuiSound.click(player);
              UltsTakeAllSGUI.openForBag(
                  player, runtime, item, () -> UltsBagSGUI.open(player, runtime, item, page));
            }
          });
    }
    if (!filter.isEmpty()) {
      book.glow();
    }
    return book;
  }

  /** The language the player's own client is set to, which is the one a search is answered in. */
  private String locale() {
    return UltsItemNames.normalize(player.clientInformation().language());
  }

  /**
   * The stacks of a bag a search leaves, in the order the bag lays them out.
   *
   * <p>A search that found this bag by something inside it has to show that thing when the bag opens,
   * and hide the rest: the bag is answered in the same words the listing was, so the two agree on what
   * the search means. A stack is kept by its own name, its id and what it carries, exactly as a row of
   * the storage screen is.
   *
   * @param kept every stack the bag holds
   * @param filter the search text, already trimmed and lower-cased, or empty
   * @param locale the language the player's client is set to
   * @return the stacks to lay out
   */
  static List<UltsStoredView> shown(List<UltsStoredView> kept, String filter, String locale) {
    if (filter == null || filter.isEmpty()) {
      return kept;
    }
    return kept.stream()
        .filter(view -> UltsCreativeCatalog.matches(view.template(), filter, locale))
        .toList();
  }

  private GuiElementBuilder arrow(String key, int offset, int pages) {
    return element(Items.SPECTRAL_ARROW)
        .setName(UltsGuiText.text(key).copy().withStyle(ChatFormatting.YELLOW))
        .addLoreLine(UltsGuiText.text("ults.gui.page", page + 1, pages).copy()
            .withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          UltsGuiSound.click(player);
          page += offset;
          render();
        });
  }

  /** One stored stack, behaving exactly like a row of the storage screen does. */
  private GuiElementBuilder rowButton(UltsStoredView view) {
    // One piece is what the icon shows, whatever the stack holds: how much is there is said in the lore,
    // so a bag reads as rows of one thing each rather than as a row of numbers.
    List<UltsStoredView> stock = runtime.storedItems();
    GuiElementBuilder builder = new GuiElementBuilder(view.template().copyWithCount(1))
        .addLoreLine(Component.empty());
    var craftingAmount = runtime.craftingAmount(view.template(), stock);
    UltsCraftingLore.stored(builder, "ults.gui.amount", view.amount(), craftingAmount.pending());
    builder
        .addLoreLine(UltsGuiText.labelled(
            "ults.gui.special.updated", UltsGuiText.stamp(view.updatedAt()), !view.stampKnown()));
    long obtainable = UltsCraftMath.add(view.amount(),
        UltsCraftingLore.add(builder, runtime, view.template(), stock).amount());
    if (craftingAmount.pending()) builder.addLoreLine(UltsGuiText.text("ults.withdraw.problem.pending"));
    else UltsTakeHints.hints(builder, view.template(), obtainable, "ults.gui.take.choose",
        UltsTakeHints.boxPossible(runtime, view.template(), stock, obtainable),
        runtime.allowBulkWithdrawal());
    return builder.setCallback((slot, type, action, gui) -> {
      if (type == ClickType.MOUSE_LEFT) {
        takeOneStack(view);
      } else if (type == ClickType.MOUSE_LEFT_SHIFT) {
        takeBox(view);
      } else if (type == ClickType.MOUSE_RIGHT) {
        // How many is always the player's question to answer, even when there is a single piece of it.
        UltsGuiSound.click(player);
        UltsWithdrawSGUI.open(player, runtime, view.template(),
            () -> UltsBagSGUI.open(player, runtime, item, page));
      } else if (type == ClickType.MOUSE_RIGHT_SHIFT && runtime.allowBulkWithdrawal()) {
        // A server that does not allow taking everything answers this click with nothing at all.
        UltsGuiSound.click(player);
        UltsTakeAllSGUI.openForItem(player, runtime, view.template(),
            () -> UltsBagSGUI.open(player, runtime, item, page));
      }
    });
  }

  /**
   * Hands over as much of that stack as a stack of it holds, crafting what is missing.
   *
   * <p>Exactly what a left click on a row of the storage screen does, so a bag is not a different kind
   * of place: the amount is one stack, or everything there is when there is less than one. How much
   * that is is asked of the storage as it stands at the click, not of the numbers the slot was drawn
   * with, so a click never asks for what is no longer there.
   */
  private void takeOneStack(UltsStoredView view) {
    if (!UltsCraftingLore.ready(runtime, player, view.template())) { render(); return; }
    int quantity = runtime.stackQuantity(view.template());
    if (quantity < 1) {
      UltsGuiSound.click(player);
      render();
      return;
    }
    if (!runtime.allowFullInventory() && !UltsWithdrawSGUI.canFit(
        player, UltsWithdrawalOutput.stacks(view.template(), quantity))) {
      // In the chat, like the box refusals: a backpack with no room is a refusal the player has to be able
      // to read back, not a line that fades above the hotbar.
      UltsGuiChat.failure(player, "ults.withdraw.problem.inventory");
      return;
    }
    List<ItemStack> outputs = runtime.takePlanned(view.template(), quantity, false);
    if (outputs.isEmpty()) {
      // The row was drawn a moment ago and the storage is asked as it stands: nothing left means somebody
      // else took it, so the click says so instead of looking like a click that did not register.
      UltsGuiChat.failure(player, "ults.withdraw.problem.gone");
      render();
      return;
    }
    UltsGuiSound.confirm(player);
    UltsGuiGive.handWithFeedback(player, runtime, outputs, runtime.allowFullInventory());
    render();
  }

  /**
   * Packs one full shulker box of the stack in that slot and hands it over.
   *
   * <p>A whole box is a big ask — twenty-seven full stacks and an empty box to put them in — so it is
   * only offered while the storage really can: what is stored plus what could be crafted right now.
   */
  private void takeBox(UltsStoredView view) {
    if (!UltsCraftingLore.ready(runtime, player, view.template(), true)) { render(); return; }
    UltsWithdrawalPlan plan = runtime.withdrawalPlan(view.template(), 1, true);
    if (!plan.available()) {
      // In the chat, not above the hotbar: how short of a box the storage is, or that no box could be
      // filled, is something the player has to be able to read back.
      UltsGuiChat.failure(player, "ults.gui.take.box.failed");
      return;
    }
    if (!runtime.allowFullInventory() && !UltsWithdrawSGUI.canFit(player, plan.outputs())) {
      UltsGuiChat.failure(player, "ults.withdraw.problem.inventory");
      return;
    }
    List<ItemStack> outputs = runtime.takePlanned(view.template(), 1, true);
    if (outputs.isEmpty()) {
      // The row was drawn a moment ago and the storage is asked as it stands: nothing left means somebody
      // else took it, so the click says so instead of looking like a click that did not register.
      UltsGuiChat.failure(player, "ults.withdraw.problem.gone");
      render();
      return;
    }
    UltsGuiSound.confirm(player);
    UltsGuiGive.handWithFeedback(player, runtime, outputs, runtime.allowFullInventory());
    render();
  }

  private static GuiElementBuilder element(Item item) {
    return new GuiElementBuilder(item).hideDefaultTooltip();
  }
}
