package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.state.UltsWithdrawalPlan;
import com.flwolfy.ults.util.UltsTextBuilder;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class UltsWithdrawSGUI extends UltsAnvilInputGui {

  private static final List<WeakReference<UltsWithdrawSGUI>> OPEN_MENUS = new ArrayList<>();
  private final UltsRuntime runtime;
  private final ItemStack template;
  /** What this screen came from, so that leaving it lands where the player was. */
  private final Runnable returnTo;
  private boolean boxed;
  private long renderedRevision = -1;

  private UltsWithdrawSGUI(
      ServerPlayer player,
      UltsRuntime runtime,
      ItemStack template,
      Runnable returnTo
  ) {
    super(player, false);
    this.runtime = runtime;
    this.template = template.copyWithCount(1);
    this.returnTo = returnTo;
    setTitle(UltsGuiText.text("ults.withdraw.title"));
    setLockPlayerInventory(true);
    setDefaultInputValue("");
    showCancelSlot();
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.add(new WeakReference<>(this));
    }
    render();
  }

  /** Opens the amount screen on an item of the storage screen, which is where it comes back to. */
  public static void open(ServerPlayer player, UltsRuntime runtime, ItemStack template) {
    open(player, runtime, template, () -> UltsStorageSGUI.open(player, runtime));
  }

  /**
   * Opens the amount screen and says where leaving it goes.
   *
   * @param returnTo what to open once this screen is done with, which is the screen it came from
   * @return the screen that was opened, so a caller can look at what it says
   */
  public static UltsWithdrawSGUI open(
      ServerPlayer player,
      UltsRuntime runtime,
      ItemStack template,
      Runnable returnTo
  ) {
    UltsWithdrawSGUI screen = new UltsWithdrawSGUI(player, runtime, template, returnTo);
    screen.open();
    return screen;
  }

  public static void refreshAll(UltsRuntime runtime) {
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.removeIf(reference -> {
        UltsWithdrawSGUI gui = reference.get();
        if (gui == null || !gui.isOpen()) {
          return true;
        }
        if (gui.runtime == runtime && gui.renderedRevision != runtime.contentRevision()) {
          gui.render();
        }
        return false;
      });
    }
  }

  @Override
  public void onInput(String value) {
    render();
  }

  /**
   * Fills the two action slots. The input slot keeps the cancel action and is never rewritten here,
   * because the client mirrors the name of that slot into its input field.
   */
  private void render() {
    renderedRevision = runtime.contentRevision();
    String input = getInput();
    Integer quantity = parse(input);
    UltsWithdrawalPlan plan = runtime.withdrawalPlan(
        template, quantity == null ? 0 : quantity, boxed);
    // A full inventory only blocks the request while the configuration says it should; with the
    // option on the player would rather have the rest on the floor than not have it at all.
    boolean overflowToGround = runtime.allowFullInventory();
    boolean inventorySpace = plan.available() && canFit(player, plan.outputs());
    boolean confirmable = plan.available() && (inventorySpace || overflowToGround);

    ItemStack availableBox = runtime.availableBox();
    ItemStack boxIcon = availableBox.isEmpty() ? new ItemStack(Items.SHULKER_BOX) : availableBox;
    // The mode slot glints while it stands for the item itself and not while it stands for a box: the
    // glint is what says "this is the thing you are taking", and a box to pack it in is not that.
    GuiElementBuilder mode = boxed
        ? new GuiElementBuilder(boxIcon).hideDefaultTooltip()
        : new GuiElementBuilder(template.copyWithCount(1)).hideDefaultTooltip().glow();
    mode
        .setName(UltsGuiText.text(boxed ? "ults.withdraw.mode.box" : "ults.withdraw.mode.item")
            .copy().withStyle(ChatFormatting.YELLOW))
        .addLoreLine(template.getHoverName().copy().withStyle(UltsTextBuilder.HIGHLIGHT))
        .addLoreLine(UltsGuiText.labelled(
            "ults.withdraw.available", plan.itemAvailable(), plan.itemAvailable() < 1))
        .addLoreLine(UltsGuiText.labelled(
            "ults.withdraw.requested",
            quantity == null ? "-" : UltsGuiText.format(quantity),
            quantity == null));
    if (boxed) {
      mode
          .addLoreLine(UltsGuiText.labelled(
              "ults.withdraw.boxes_available", plan.boxAvailable(), plan.boxAvailable() < 1))
          .addLoreLine(UltsGuiText.labelled(
              "ults.withdraw.boxes_used", plan.boxStored(), plan.boxStored() < 1))
          .addLoreLine(UltsGuiText.labelled(
              "ults.withdraw.items_required",
              plan.itemRequired(),
              plan.itemRequired() > plan.itemAvailable()));
    }
    if (plan.craftItems() > 0) {
      mode.addLoreLine(UltsGuiText.labelled(
          "ults.withdraw.craft", UltsGuiText.format(plan.craftItems()), false));
    }
    if (plan.boxCrafted() > 0) {
      mode.addLoreLine(UltsGuiText.labelled(
          "ults.withdraw.craft.boxes", plan.boxCrafted(), false));
    }
    mode.addLoreLine(UltsGuiText.text("ults.withdraw.mode.toggle").copy()
        .withStyle(ChatFormatting.GRAY));
    mode.setCallback(() -> {
      UltsGuiSound.click(player);
      boxed = !boxed;
      render();
    });
    setSlot(1, mode.build());

    GuiElementBuilder confirm = new GuiElementBuilder(
        confirmable ? Items.DYE.lime() : Items.BARRIER)
        .setName(UltsGuiText.text(confirmable ? "ults.withdraw.confirm" : "ults.withdraw.unavailable")
            .copy().withStyle(confirmable ? ChatFormatting.GREEN : ChatFormatting.RED));
    if (!confirmable) {
      confirm.addLoreLine(problem(plan, quantity).copy()
          .withStyle(ChatFormatting.RED));
      if (plan.available() && !inventorySpace) {
        // Only the backpack is in the way, and the option that would let the rest fall to the ground is
        // off. Where that option lives is worth saying: it sits in the server's own file, so a player
        // cannot reach it from their side of the game.
        confirm.addLoreLine(UltsGuiText.text("ults.withdraw.problem.inventory.hint").copy()
            .withStyle(ChatFormatting.GRAY));
      }
    } else {
      confirm.addLoreLine(UltsGuiText.text(boxed
          ? "ults.withdraw.confirm.box" : "ults.withdraw.confirm.item", quantity)
          .copy().withStyle(ChatFormatting.GREEN));
      if (!inventorySpace) {
        confirm.addLoreLine(UltsGuiText.text("ults.withdraw.confirm.overflow").copy()
            .withStyle(ChatFormatting.RED));
      }
      confirm.setCallback(() -> {
        UltsGuiSound.confirm(player);
        confirm(quantity);
      });
    }
    setSlot(2, confirm.build());
  }

  @Override
  protected void cancel() {
    close();
    returnTo.run();
  }

  private void confirm(int quantity) {
    UltsGuiGive.Delivery delivery;
    synchronized (runtime.state()) {
      boolean overflowToGround = runtime.allowFullInventory();
      UltsWithdrawalPlan plan = runtime.withdrawalPlan(template, quantity, boxed);
      if (!plan.available() || !(canFit(player, plan.outputs()) || overflowToGround)) {
        render();
        return;
      }
      List<ItemStack> outputs = runtime.takePlanned(template, quantity, boxed);
      if (outputs.isEmpty()) {
        render();
        return;
      }
      delivery = UltsGuiGive.handWithFeedback(player, runtime, outputs, overflowToGround);
    }
    if (delivery.delivered() == 0L) { render(); return; }
    player.sendSystemMessage(UltsTextBuilder.success(UltsTextBuilder.format(
        UltsGuiText.text(boxed ? "ults.withdraw.success.box" : "ults.withdraw.success.item"),
        UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
        delivery.delivered(), template.getHoverName().getString())));
    close();
    returnTo.run();
  }

  private Component problem(UltsWithdrawalPlan plan, Integer quantity) {
    if (quantity == null || quantity < 1) {
      return UltsGuiText.text("ults.withdraw.problem.invalid");
    }
    if (!plan.available()) {
      return switch (plan.problem()) {
        case "pending" -> UltsGuiText.text("ults.withdraw.problem.pending");
        case "nested_box" -> UltsGuiText.text("ults.withdraw.problem.nested_box");
        case "too_large" -> UltsGuiText.text("ults.withdraw.problem.too_large");
        case "items" -> UltsGuiText.text(
            "ults.withdraw.problem.items", UltsGuiText.format(plan.itemRequired()),
            UltsGuiText.format(plan.itemAvailable()));
        case "boxes" -> UltsGuiText.text(
            "ults.withdraw.problem.boxes", plan.boxRequired(),
            UltsGuiText.format(plan.boxAvailable()));
        default -> UltsGuiText.text("ults.withdraw.problem.invalid");
      };
    }
    return UltsGuiText.text("ults.withdraw.problem.inventory");
  }

  /** Whether the backpack can take the whole result, which the special bag asks as well. */
  static boolean canFit(ServerPlayer player, List<ItemStack> outputs) {
    return UltsBackpack.fitting(UltsBackpack.slots(player), outputs) == outputs.size();
  }

  /**
   * How many more pieces of one stack the backpack can take as it stands.
   *
   * <p>Room left in stacks of the same thing counts the same as an empty slot, which is how the game
   * itself fills a backpack: a stack is topped up before a new slot is opened.
   *
   * @param player the player receiving
   * @param template what would be handed over, one piece of it
   * @return how many pieces fit right now
   */
  static long room(ServerPlayer player, ItemStack template) {
    return UltsBackpack.room(UltsBackpack.slots(player), template);
  }

  private static Integer parse(String value) {
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : null;
    } catch (NumberFormatException ignored) {
      return null;
    }
  }
}
