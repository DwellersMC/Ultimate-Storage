package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.state.UltsStoredView;
import com.flwolfy.ults.util.UltsTextBuilder;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
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
 */
public final class UltsTakeAllSGUI extends SimpleGui {

  /** The three buttons, at the third, fifth and seventh slot of the row as a player counts them. */
  private static final int BACK_SLOT = 2;
  private static final int MODE_SLOT = 4;
  private static final int CONFIRM_SLOT = 6;

  private final UltsRuntime runtime;
  private final Runnable returnTo;
  /** The stacks everything means: one item, or every stack of one item's bag. */
  private final List<ItemStack> wanted;
  private final long stored;
  private final long craftable;
  /** Whether the recipes may fill in what the storage is short of, which the mode slot switches. */
  private boolean crafting;

  private UltsTakeAllSGUI(
      ServerPlayer player,
      UltsRuntime runtime,
      Runnable returnTo,
      List<ItemStack> wanted,
      long stored,
      long craftable
  ) {
    super(MenuType.GENERIC_9x1, player, false);
    this.runtime = runtime;
    this.returnTo = returnTo;
    this.wanted = List.copyOf(wanted);
    this.stored = stored;
    this.craftable = craftable;
    setTitle(UltsGuiText.text("ults.all.title"));
    setLockPlayerInventory(true);
    render();
  }

  /**
   * Asks to empty out everything of one item.
   *
   * @param template the item, components and all, that "everything" is of
   * @param returnTo what to open once this screen is done with
   */
  public static void openForItem(
      ServerPlayer player,
      UltsRuntime runtime,
      ItemStack template,
      Runnable returnTo
  ) {
    List<UltsStoredView> stock = runtime.storedItems();
    // Asked afresh, because this screen is opened by a click: the amounts it shows are the ones the
    // player is answering about, so they may not come from a listing that is a moment behind.
    new UltsTakeAllSGUI(
        player, runtime, returnTo, List.of(template.copyWithCount(1)),
        UltsRuntime.storedAmount(template, stock),
        runtime.craftableFresh(template, stock)).open();
  }

  /** Asks to empty out a whole bag: every stack of that item, just as they are. */
  public static void openForBag(
      ServerPlayer player,
      UltsRuntime runtime,
      Item item,
      Runnable returnTo
  ) {
    long stored = 0L;
    List<ItemStack> stacks = new ArrayList<>();
    for (UltsStoredView row : runtime.specialsOf(item)) {
      stored += row.amount();
      stacks.add(row.template());
    }
    // Nothing in a bag is made by a recipe: those are the stacks somebody put in.
    new UltsTakeAllSGUI(player, runtime, returnTo, stacks, stored, 0L).open();
  }

  private long total(boolean withCrafting) {
    return stored + (withCrafting ? craftable : 0L);
  }

  /** Whether a mode is on offer: taking what is stored needs something stored, crafting needs a recipe. */
  private boolean modeOffered(boolean withCrafting) {
    return withCrafting ? craftable > 0L : stored > 0L;
  }

  private void render() {
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

  /** The mode, and what this mode would take. */
  private GuiElementBuilder modeElement() {
    boolean withCrafting = crafting;
    GuiElementBuilder mode = new GuiElementBuilder(withCrafting ? Items.CRAFTING_TABLE : icon())
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
  private Item icon() {
    return wanted.isEmpty() ? Items.CHEST : wanted.getFirst().getItem();
  }

  private GuiElementBuilder confirmElement() {
    boolean canTake = total(crafting) > 0L;
    GuiElementBuilder confirm = new GuiElementBuilder(canTake ? Items.DYE.lime() : Items.BARRIER)
        .setName(UltsGuiText.text(canTake ? "ults.all.confirm" : "ults.all.confirm.none").copy()
            .withStyle(canTake ? ChatFormatting.GREEN : ChatFormatting.RED))
        .addLoreLine(UltsGuiText.labelled(
            "ults.all.total", UltsGuiText.format(total(crafting)), !canTake));
    if (canTake) {
      confirm.addLoreLine(UltsGuiText.text("ults.all.confirm.hint").copy()
          .withStyle(ChatFormatting.GRAY));
      confirm.setCallback((slot, type, action, gui) -> confirm(crafting));
    }
    return confirm;
  }

  /**
   * Hands everything over.
   *
   * @param withCrafting whether the recipes may fill in what the storage is short of
   */
  private void confirm(boolean withCrafting) {
    synchronized (runtime.state()) {
      List<ItemStack> outputs = new ArrayList<>();
      if (wanted.size() == 1) {
        // One kind of thing: ask for every piece of it there is, plus what the recipes can add when the
        // player said they may.
        ItemStack template = wanted.getFirst();
        long quantity = total(withCrafting);
        if (quantity > 0L) {
          outputs.addAll(runtime.takePlanned(
              template,
              (int) Math.min(quantity, Integer.MAX_VALUE),
              false,
              withCrafting ? runtime.craftingMode() : UltsCraftingMode.DISABLED));
        }
      } else {
        // A whole bag: every stack in it leaves as the thing it is.
        for (ItemStack template : wanted) {
          ItemStack taken = runtime.takeBagRow(template.getItem(), template);
          if (!taken.isEmpty()) {
            outputs.add(taken);
          }
        }
      }
      if (!outputs.isEmpty()) {
        UltsGuiSound.confirm(player);
        // Everything that is leaving is on the ground if it does not fit: the player asked for all of
        // it, and half of an answer is not one.
        UltsGuiGive.hand(player, runtime, outputs, true);
        player.sendSystemMessage(UltsTextBuilder.success(UltsGuiText.text("ults.all.success")), false);
      }
    }
    close();
    returnTo.run();
  }

  private static GuiElementBuilder element(Item item) {
    return new GuiElementBuilder(item).hideDefaultTooltip();
  }
}
