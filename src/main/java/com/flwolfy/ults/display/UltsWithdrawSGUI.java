package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.data.state.UltsWithdrawalPlan;
import com.flwolfy.ults.util.UltsTextBuilder;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.AnvilInputGui;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class UltsWithdrawSGUI extends AnvilInputGui {

  private static final List<WeakReference<UltsWithdrawSGUI>> OPEN_MENUS = new ArrayList<>();
  private final UltsRuntime runtime;
  private final ItemStack template;
  private boolean boxed;
  private long renderedRevision = -1;

  private UltsWithdrawSGUI(ServerPlayer player, UltsRuntime runtime, ItemStack template) {
    super(player, false);
    this.runtime = runtime;
    this.template = template.copyWithCount(1);
    setTitle(text("ults.withdraw.title"));
    setLockPlayerInventory(true);
    setDefaultInputValue("1");
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
        if (gui.runtime == runtime && gui.renderedRevision != runtime.state().revision()) {
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

  private void render() {
    renderedRevision = runtime.state().revision();
    String input = getInput();
    Integer quantity = parse(input);
    UltsWithdrawalPlan plan = runtime.state().withdrawalPlan(
        template, quantity == null ? 0 : quantity, boxed);
    boolean inventorySpace = plan.available() && canFit(plan.outputs());
    boolean confirmable = plan.available() && inventorySpace;

    int visible = (int) Math.max(1, Math.min(plan.itemAvailable(), template.getMaxStackSize()));
    ItemStack display = template.copyWithCount(visible);
    GuiElementBuilder selected = new GuiElementBuilder(display)
        .setName(Component.literal(input))
        .addLoreLine(template.getHoverName().copy().withStyle(ChatFormatting.WHITE))
        .addLoreLine(text("ults.withdraw.available", format(plan.itemAvailable())))
        .addLoreLine(text("ults.withdraw.requested", quantity == null ? "-" : quantity));
    if (boxed) {
      selected.addLoreLine(text(
          "ults.withdraw.boxes_available", format(plan.boxAvailable())));
      selected.addLoreLine(text(
          "ults.withdraw.items_required", format(plan.itemRequired())));
    }
    setSlot(0, selected.build());

    setSlot(1, new GuiElementBuilder(
        boxed ? new ItemStack(Items.SHULKER_BOX) : template)
        .setName(text(boxed ? "ults.withdraw.mode.box" : "ults.withdraw.mode.item")
            .copy().withStyle(ChatFormatting.YELLOW))
        .addLoreLine(text("ults.withdraw.mode.toggle"))
        .setCallback(() -> {
          boxed = !boxed;
          render();
        }).build());

    GuiElementBuilder confirm = new GuiElementBuilder(
        confirmable ? Items.DYE.lime() : Items.BARRIER)
        .setName(text(confirmable ? "ults.withdraw.confirm" : "ults.withdraw.unavailable")
            .copy().withStyle(confirmable ? ChatFormatting.GREEN : ChatFormatting.RED));
    if (!confirmable) {
      confirm.addLoreLine(problem(plan, quantity, inventorySpace));
    } else {
      confirm.addLoreLine(text(boxed
          ? "ults.withdraw.confirm.box" : "ults.withdraw.confirm.item", quantity));
      confirm.setCallback(() -> confirm(quantity));
    }
    setSlot(2, confirm.build());
  }

  private void confirm(int quantity) {
    synchronized (runtime.state()) {
      UltsWithdrawalPlan plan = runtime.state().withdrawalPlan(template, quantity, boxed);
      if (!plan.available() || !canFit(plan.outputs())) {
        render();
        return;
      }
      List<ItemStack> outputs = runtime.state().takePlanned(template, quantity, boxed);
      if (outputs.isEmpty()) {
        render();
        return;
      }
      for (ItemStack output : outputs) {
        player.getInventory().add(output);
        if (!output.isEmpty()) {
          runtime.state().deposit(output);
        }
      }
    }
    player.sendSystemMessage(UltsTextBuilder.success(text(
        boxed ? "ults.withdraw.success.box" : "ults.withdraw.success.item", quantity,
        template.getHoverName().getString())));
    close();
    UltsStorageSGUI.open(player, runtime);
  }

  private Component problem(
      UltsWithdrawalPlan plan,
      Integer quantity,
      boolean inventorySpace
  ) {
    if (quantity == null || quantity < 1) {
      return text("ults.withdraw.problem.invalid");
    }
    if (!plan.available()) {
      return switch (plan.problem()) {
        case "nested_box" -> text("ults.withdraw.problem.nested_box");
        case "too_large" -> text("ults.withdraw.problem.too_large");
        case "items" -> text(
            "ults.withdraw.problem.items", format(plan.itemRequired()), format(plan.itemAvailable()));
        case "boxes" -> text(
            "ults.withdraw.problem.boxes", plan.boxRequired(), format(plan.boxAvailable()));
        default -> text("ults.withdraw.problem.invalid");
      };
    }
    return inventorySpace
        ? Component.empty() : text("ults.withdraw.problem.inventory");
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

  private static String format(long amount) {
    return String.format(java.util.Locale.ROOT, "%,d", amount);
  }

  private static Component text(String key, Object... arguments) {
    return UltsLangManager.getInstance().text(key, arguments);
  }
}
