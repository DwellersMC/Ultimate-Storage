package com.flwolfy.ults.display;

import com.flwolfy.ults.crafting.UltsCraftMath;
import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.state.UltsStoredView;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import java.util.ArrayList;
import java.util.List;
import java.lang.ref.WeakReference;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * What "take everything" asks before it does anything: everything of an item, with or without crafting.
 *
 * <p>Emptying a stock is not a thing to do by accident, and whether the recipes may fill in what is
 * short is a real choice — a server with automatic crafting on can turn a handful of logs into a stack
 * of chests on the way out. So this is one centred row of three: the way back at the third slot, the
 * mode at the fifth and the confirmation at the seventh, with the middle one switching between taking
 * what is stored and letting the crafting table fill in the rest. Nothing leaves until the confirmation
 * is clicked, and the mode says how much each answer would be.
 *
 * <p>It is a plain container and not the anvil screen the amount screen uses: there is nothing to type
 * here, and a screen that cannot be typed into is better than one whose field has to be kept empty. The
 * way back is the same red dye the anvil screens put in their input slot, so leaving a screen looks the
 * same wherever it is done.
 *
 * <h2>What one confirmation really takes</h2>
 *
 * <p>A stock can hold more than any backpack ever will, so "everything" is not one measurement but two:
 * what is there, and what one click takes. The row says both, and the confirmation starts the second as a
 * <b>stream</b>: {@code input.takeAllStacks} at most, handed over a tick's worth at a time, into the
 * backpack first and onto the ground after that while {@code input.allowFullInventory} says so. Whatever
 * the click could not take stays in the storage and is said on the button, so a player emptying a
 * warehouse knows what came out, what is still there, and can watch it arrive.
 */
public final class UltsTakeAllSGUI extends SimpleGui {

  /** The three buttons, at the third, fifth and seventh slot of the row as a player counts them. */
  private static final int BACK_SLOT = 2;
  private static final int MODE_SLOT = 4;
  private static final int CONFIRM_SLOT = 6;
  private static final List<WeakReference<UltsTakeAllSGUI>> OPEN_MENUS = new ArrayList<>();

  private final UltsRuntime runtime;
  private final Runnable returnTo;
  /** The stacks everything means, in the order they would leave: one item, or a whole bag's rows. */
  private List<ItemStack> wanted;
  /** How many pieces each of those stacks holds, so a stack that leaves can be counted. */
  private List<Long> amounts;
  private final Item bagItem;
  /**
   * What the storage holds of this, and what could be crafted of it.
   *
   * <p>Both are asked again every time the screen draws or decides, rather than kept from the moment it
   * opened: on a server of several players the warehouse can be emptied while the screen is open, and a
   * button that still says "2,000" would start a pour for items that are no longer there.
   */
  private long stored;
  private long craftable;
  /** Whether this is about a whole bag rather than about one item, which the counting follows. */
  private final boolean wholeBag;
  /** Whether the recipes may fill in what the storage is short of, which the mode slot switches. */
  private boolean crafting;
  private long renderedRevision;

  private UltsTakeAllSGUI(
      ServerPlayer player,
      UltsRuntime runtime,
      Runnable returnTo,
      List<ItemStack> wanted,
      List<Long> amounts,
      long stored,
      long craftable,
      boolean wholeBag,
      Item bagItem
  ) {
    super(MenuType.GENERIC_9x1, player, false);
    this.runtime = runtime;
    this.returnTo = returnTo;
    this.wanted = List.copyOf(wanted);
    this.amounts = List.copyOf(amounts);
    this.stored = stored;
    this.craftable = craftable;
    this.wholeBag = wholeBag;
    this.bagItem = bagItem;
    setTitle(UltsGuiText.text("ults.all.title"));
    setLockPlayerInventory(true);
    OPEN_MENUS.add(new WeakReference<>(this));
    render();
  }

  /**
   * Asks to empty out everything of one item.
   *
   * @param template the item, components and all, that "everything" is of
   * @param returnTo what to open once this screen is done with
   * @return the screen that was opened, or {@code null} while the configuration does not allow one
   */
  public static UltsTakeAllSGUI openForItem(
      ServerPlayer player,
      UltsRuntime runtime,
      ItemStack template,
      Runnable returnTo
  ) {
    if (!runtime.allowTakeAll()) {
      return null;
    }
    List<UltsStoredView> stock = runtime.storedItemsFresh();
    // Asked afresh, because this screen is opened by a click: the amounts it shows are the ones the
    // player is answering about, so they may not come from a listing that is a moment behind.
    long stored = UltsRuntime.storedAmount(template, stock);
    UltsTakeAllSGUI screen = new UltsTakeAllSGUI(
        player, runtime, returnTo, List.of(template.copyWithCount(1)), List.of(stored),
        stored, runtime.craftableFresh(template, stock), false, null);
    screen.open();
    return screen;
  }

  /**
   * Asks to empty out a whole bag: every stack of that item, just as they are.
   *
   * @param item the item whose bag is being emptied
   * @param returnTo what to open once this screen is done with
   * @return the screen that was opened, or {@code null} while the configuration does not allow one
   */
  public static UltsTakeAllSGUI openForBag(
      ServerPlayer player,
      UltsRuntime runtime,
      Item item,
      Runnable returnTo
  ) {
    if (!runtime.allowTakeAll()) {
      return null;
    }
    long stored = 0L;
    List<ItemStack> stacks = new ArrayList<>();
    List<Long> amounts = new ArrayList<>();
    for (UltsStoredView row : bagStock(item, runtime.storedItemsFresh()).rows()) {
      stored = UltsCraftMath.add(stored, row.amount());
      stacks.add(row.template());
      amounts.add(row.amount());
    }
    // Nothing in a bag is made by a recipe: those are the stacks somebody put in.
    UltsTakeAllSGUI screen = new UltsTakeAllSGUI(
        player, runtime, returnTo, stacks, amounts, stored, 0L, true, item);
    screen.open();
    return screen;
  }

  private long total(boolean withCrafting) {
    return UltsCraftMath.add(stored, withCrafting ? craftable : 0L);
  }

  /**
   * Asks the storage again for what it holds of this.
   *
   * <p>A bag is counted from its rows as they stand, and a single item from the storage as it stands. The
   * craftable amount goes through the ordinary answer rather than a fresh search, because this runs as the
   * screen draws.
   */
  private void refreshTotals() {
    refreshTotals(false);
  }

  private void refreshTotals(boolean fresh) {
    if (wholeBag) {
      List<UltsStoredView> stock = fresh ? runtime.storedItemsFresh() : runtime.storedItems();
      BagStock bag = bagStock(bagItem, stock);
      wanted = bag.rows().stream().map(UltsStoredView::template).toList();
      amounts = bag.rows().stream().map(UltsStoredView::amount).toList();
      stored = bag.total();
      craftable = 0L;
      return;
    }
    if (wanted.isEmpty()) {
      // A bag that was emptied while its screen was open: there is nothing to count, and asking would
      // throw where a redraw is expected.
      stored = 0L;
      craftable = 0L;
      return;
    }
    List<UltsStoredView> stock = fresh ? runtime.storedItemsFresh() : runtime.storedItems();
    stored = UltsRuntime.storedAmount(wanted.getFirst(), stock);
    craftable = fresh ? runtime.craftableFresh(wanted.getFirst(), stock)
        : runtime.craftable(wanted.getFirst(), stock);
  }

  /** Whether a mode is on offer: taking what is stored needs something stored, crafting needs a recipe. */
  private boolean modeOffered(boolean withCrafting) {
    return withCrafting ? craftable > 0L : stored > 0L;
  }

  record BagStock(List<UltsStoredView> rows, long total) {}

  static BagStock bagStock(Item item, List<UltsStoredView> stock) {
    List<UltsStoredView> rows = stock.stream()
        .filter(row -> row.special() && row.template().is(item) && row.amount() > 0)
        .sorted(java.util.Comparator.comparingLong(UltsStoredView::updatedAt).reversed())
        .toList();
    return new BagStock(rows, rows.stream().map(UltsStoredView::amount).reduce(0L, UltsCraftMath::add));
  }

  public static void refreshAll(UltsRuntime runtime) {
    OPEN_MENUS.removeIf(reference -> {
      UltsTakeAllSGUI gui = reference.get();
      if (gui == null || !gui.isOpen()) {
        return true;
      }
      if (gui.runtime == runtime
          && (gui.renderedRevision != runtime.contentRevision() || runtime.craftablePending())) {
        gui.render();
      }
      return false;
    });
  }

  /**
   * What one confirmation would really hand over for the player as they stand.
   *
   * <p>The rule itself lives in {@link UltsTakeAllPlan}, which is where it is read and tested; this only
   * hands it the storage's totals, the configured limit and the backpack as it is right now.
   *
   * @param withCrafting whether the recipes may fill in what the storage is short of
   */
  private UltsTakeAllPlan plan(boolean withCrafting) {
    // The totals are asked for once per draw and once per click, never here: a draw would otherwise ask
    // the storage twice for the same answer, and a craftable answer nobody could work out in time would
    // have its wait counted off twice as fast as the budget intends.
    return UltsTakeAllPlan.of(
        total(withCrafting), wanted, amounts, runtime.allowFullInventory(),
        runtime.takeAllStacks(), UltsBackpack.slots(player));
  }

  private void render() {
    renderedRevision = runtime.contentRevision();
    refreshTotals();
    GuiElementBuilder filler = element(Items.STAINED_GLASS_PANE.gray()).setName(Component.empty());
    for (int slot = 0; slot < getVirtualSize(); slot++) {
      setSlot(slot, filler.build());
    }
    setSlot(BACK_SLOT, backElement());
    setSlot(MODE_SLOT, modeElement());
    setSlot(CONFIRM_SLOT, confirmElement());
  }

  /**
   * The way back, which takes nothing.
   *
   * <p>It carries no name, exactly like the anvil screens' cancel button: its label is the first tooltip
   * line, so leaving a screen reads the same everywhere.
   */
  private GuiElementBuilder backElement() {
    return new GuiElementBuilder(Items.DYE.red())
        .setName(Component.empty())
        .addLoreLine(UltsGuiText.text("ults.anvil.cancel").copy().withStyle(ChatFormatting.GRAY))
        .setCallback((slot, type, action, gui) -> {
          UltsGuiSound.click(player);
          close();
          returnTo.run();
        });
  }

  /**
   * The mode, and what everything would be.
   *
   * <p>The slot glints while it stands for the item itself and not while it stands for the crafting
   * table: the glint is what says "this is the thing you are taking", and a station that fills in what
   * is missing is not that.
   */
  private GuiElementBuilder modeElement() {
    boolean withCrafting = crafting;
    GuiElementBuilder mode = withCrafting
        ? element(Items.CRAFTING_TABLE)
        : new GuiElementBuilder(icon()).hideDefaultTooltip().glow();
    mode
        .setName(UltsGuiText.text(withCrafting ? "ults.all.mode.craft" : "ults.all.mode.item").copy()
            .withStyle(ChatFormatting.YELLOW))
        .addLoreLine(Component.empty())
        .addLoreLine(UltsGuiText.labelled(
            "ults.all.stored", UltsGuiText.format(stored), stored < 1L))
        .addLoreLine(UltsGuiText.labelled(
            "ults.all.craftable", UltsGuiText.format(craftable), craftable < 1L))
        .addLoreLine(UltsGuiText.labelled(
            "ults.all.total", UltsGuiText.format(total(withCrafting)), !modeOffered(withCrafting)));
    if (modeOffered(!withCrafting)) {
      mode.addLoreLine(UltsGuiText.text("ults.all.mode.toggle").copy()
          .withStyle(ChatFormatting.GRAY));
      mode.setCallback((slot, type, action, gui) -> {
        UltsGuiSound.click(player);
        crafting = !crafting;
        render();
      });
    } else {
      // The other answer cannot be served, so the slot says which one it is instead of offering it.
      mode.addLoreLine(UltsGuiText.text(withCrafting ? "ults.all.plain.none" : "ults.all.craft.none")
          .copy().withStyle(ChatFormatting.RED));
    }
    return mode;
  }

  /** What "everything" is: one item, or the first stack of the bag the question is about. */
  private ItemStack icon() {
    return wanted.isEmpty() ? new ItemStack(Items.CHEST) : wanted.getFirst().copyWithCount(1);
  }

  /**
   * The confirmation, which is a barrier whenever there is nothing this click could hand over.
   *
   * <p>It answers the same question the amount screen does, with the same answer: a backpack that cannot
   * take the result blocks it while the configuration says a full backpack blocks a withdrawal. What it
   * promises is what the click will really move, which is at most one backpack's worth, so the number on
   * it is a number a click can keep.
   */
  private GuiElementBuilder confirmElement() {
    UltsTakeAllPlan plan = plan(crafting);
    boolean canTake = plan.possible();
    String name = canTake
        ? "ults.all.confirm"
        : plan.blocked() ? "ults.all.confirm.full" : "ults.all.confirm.none";
    GuiElementBuilder confirm = new GuiElementBuilder(canTake ? Items.DYE.lime() : Items.BARRIER)
        .setName(UltsGuiText.text(name).copy()
            .withStyle(canTake ? ChatFormatting.GREEN : ChatFormatting.RED));
    if (canTake) {
      confirm.addLoreLine(UltsGuiText.labelled(
          "ults.all.take", UltsGuiText.format(plan.taken()), false));
      if (plan.ground() > 0L) {
        confirm.addLoreLine(UltsGuiText.labelled(
            "ults.all.take.pack", UltsGuiText.format(plan.pack()), false));
        confirm.addLoreLine(UltsGuiText.labelled(
            "ults.all.take.ground", UltsGuiText.format(plan.ground()), false));
      }
      if (plan.left() > 0L) {
        confirm.addLoreLine(UltsGuiText.labelled(
            "ults.all.take.left", UltsGuiText.format(plan.left()), true));
      }
      confirm.addLoreLine(UltsGuiText.text("ults.all.confirm.hint").copy()
          .withStyle(ChatFormatting.GRAY));
      confirm.setCallback((slot, type, action, gui) -> confirm(crafting));
    } else {
      // Why the click cannot be served, in the same words the amount screen uses for the same answer.
      confirm.addLoreLine(UltsGuiText.text(
          plan.total() <= 0L ? "ults.all.plain.none" : "ults.withdraw.problem.inventory")
          .copy().withStyle(ChatFormatting.RED));
    }
    return confirm;
  }

  /**
   * Starts handing the stock over, and closes the screen so it can flow.
   *
   * <p>The numbers are worked out again here rather than trusted from the drawing: the storage is asked
   * as it stands, and a click that can no longer be served redraws the screen instead of promising more
   * than it can keep. What the click promises is not handed over at once but poured out a tick's worth at
   * a time by {@link UltsTakeAllStream}, which is what keeps a warehouse-sized stock from being one
   * enormous operation — and what lets the pour stop the moment the player opens a screen or cannot
   * receive any more.
   *
   * @param withCrafting whether the recipes may fill in what the storage is short of
   */
  private void confirm(boolean withCrafting) {
    if (!runtime.allowTakeAll()) {
      close();
      returnTo.run();
      return;
    }
    // A click is answered from the storage as it stands, not from the numbers the last draw was made of.
    refreshTotals(true);
    UltsTakeAllPlan plan = plan(withCrafting);
    if (!plan.possible()) {
      UltsGuiSound.click(player);
      // Said in the chat, like every other refusal a screen makes: it stays where the player can read it
      // back, instead of fading above the hotbar while they are still looking at the screen.
      UltsGuiChat.failure(player, plan.blocked() ? "ults.all.confirm.full" : "ults.all.confirm.none");
      render();
      return;
    }
    UltsGuiSound.confirm(player);
    close();
    UltsTakeAllStream.start(
        runtime, player, wanted, plan.amounts(),
        withCrafting ? runtime.craftingMode() : UltsCraftingMode.DISABLED,
        wholeBag);
    returnTo.run();
  }

  private static GuiElementBuilder element(Item item) {
    return new GuiElementBuilder(item).hideDefaultTooltip();
  }
}
