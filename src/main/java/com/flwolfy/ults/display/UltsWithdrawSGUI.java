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
  private boolean boxed;
  private long renderedRevision = -1;

  private UltsWithdrawSGUI(ServerPlayer player, UltsRuntime runtime, ItemStack template) {
    super(player, false);
    this.runtime = runtime;
    this.template = template.copyWithCount(1);
    setTitle(UltsGuiText.text("ults.withdraw.title"));
    setLockPlayerInventory(true);
    setDefaultInputValue("");
    showCancelSlot();
    synchronized (OPEN_MENUS) {
      OPEN_MENUS.add(new WeakReference<>(this));
    }
    render();
  }

  public static void open(ServerPlayer player, UltsRuntime runtime, ItemStack template) {
    new UltsWithdrawSGUI(player, runtime, template).open();
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
    boolean inventorySpace = plan.available() && canFit(plan.outputs());
    boolean confirmable = plan.available() && inventorySpace;

    ItemStack availableBox = runtime.availableBox();
    ItemStack boxIcon = availableBox.isEmpty() ? new ItemStack(Items.SHULKER_BOX) : availableBox;
    GuiElementBuilder mode = new GuiElementBuilder(boxed ? boxIcon : template.copyWithCount(1))
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
      confirm.addLoreLine(problem(plan, quantity, inventorySpace).copy()
          .withStyle(ChatFormatting.RED));
    } else {
      confirm.addLoreLine(UltsGuiText.text(boxed
          ? "ults.withdraw.confirm.box" : "ults.withdraw.confirm.item", quantity)
          .copy().withStyle(ChatFormatting.GREEN));
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
    UltsStorageSGUI.open(player, runtime);
  }

  private void confirm(int quantity) {
    synchronized (runtime.state()) {
      UltsWithdrawalPlan plan = runtime.withdrawalPlan(template, quantity, boxed);
      if (!plan.available() || !canFit(plan.outputs())) {
        render();
        return;
      }
      List<ItemStack> outputs = runtime.takePlanned(template, quantity, boxed);
      if (outputs.isEmpty()) {
        render();
        return;
      }
      for (ItemStack output : outputs) {
        player.getInventory().add(output);
        if (!output.isEmpty()) {
          // Remote mode has no void pool, so anything that did not fit stays with the player.
          if (runtime.remote()) {
            player.drop(output, false);
          } else {
            runtime.state().deposit(output);
          }
        }
      }
    }
    player.sendSystemMessage(UltsTextBuilder.success(UltsTextBuilder.format(
        UltsGuiText.text(boxed ? "ults.withdraw.success.box" : "ults.withdraw.success.item"),
        UltsTextBuilder.TEXT, UltsTextBuilder.HIGHLIGHT,
        quantity, template.getHoverName().getString())));
    close();
    UltsStorageSGUI.open(player, runtime);
  }

  private Component problem(
      UltsWithdrawalPlan plan,
      Integer quantity,
      boolean inventorySpace
  ) {
    if (quantity == null || quantity < 1) {
      return UltsGuiText.text("ults.withdraw.problem.invalid");
    }
    if (!plan.available()) {
      return switch (plan.problem()) {
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
    return inventorySpace
        ? Component.empty() : UltsGuiText.text("ults.withdraw.problem.inventory");
  }

  private boolean canFit(List<ItemStack> outputs) {
    List<ItemStack> slots = new ArrayList<>(36);
    for (int slot = 0; slot < 36; slot++) {
      slots.add(player.getInventory().getItem(slot).copy());
    }
    for (ItemStack requested : outputs) {
      ItemStack remaining = requested.copy();
      for (ItemStack current : slots) {
        if (remaining.isEmpty()) {
          break;
        }
        if (ItemStack.isSameItemSameComponents(current, remaining)
            && current.getCount() < current.getMaxStackSize()) {
          int moved = Math.min(
              remaining.getCount(), current.getMaxStackSize() - current.getCount());
          current.grow(moved);
          remaining.shrink(moved);
        }
      }
      for (int slot = 0; slot < slots.size() && !remaining.isEmpty(); slot++) {
        if (!slots.get(slot).isEmpty()) {
          continue;
        }
        int moved = Math.min(remaining.getCount(), remaining.getMaxStackSize());
        slots.set(slot, remaining.copyWithCount(moved));
        remaining.shrink(moved);
      }
      if (!remaining.isEmpty()) {
        return false;
      }
    }
    return true;
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
