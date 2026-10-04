package com.flwolfy.ults.display;

import com.flwolfy.ults.UltsRuntime;
import com.flwolfy.ults.crafting.UltsCraftMath;
import com.flwolfy.ults.data.state.*;
import com.flwolfy.ults.util.UltsTextBuilder;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.SguiUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** An explicit quantity starts the same bounded delivery stream as take-everything. */
public final class UltsWithdrawSGUI extends UltsAnvilInputGui {
  private static final List<WeakReference<UltsWithdrawSGUI>> OPEN_MENUS = new ArrayList<>();
  private final UltsRuntime runtime;
  private final ItemStack template;
  private final Runnable returnTo;
  private boolean boxed;
  private boolean submitted;
  private boolean pending;
  private long renderedRevision = -1;

  private UltsWithdrawSGUI(ServerPlayer player, UltsRuntime runtime, ItemStack template,
      Runnable returnTo) {
    super(player, false);
    this.runtime = runtime;
    this.template = template.copyWithCount(1);
    this.returnTo = returnTo;
    setTitle(UltsGuiText.text("ults.withdraw.title"));
    setLockPlayerInventory(true);
    setDefaultInputValue("");
    showCancelSlot();
    OPEN_MENUS.add(new WeakReference<>(this));
    render();
  }

  public static void open(ServerPlayer player, UltsRuntime runtime, ItemStack template) {
    open(player, runtime, template, () -> UltsStorageSGUI.open(player, runtime));
  }

  public static UltsWithdrawSGUI open(ServerPlayer player, UltsRuntime runtime, ItemStack template,
      Runnable returnTo) {
    var screen = new UltsWithdrawSGUI(player, runtime, template, returnTo);
    screen.open();
    return screen;
  }

  public static void refreshAll(UltsRuntime runtime) {
    OPEN_MENUS.removeIf(reference -> {
      var gui = reference.get();
      if (gui == null || !gui.isOpen()) return true;
      if (gui.runtime == runtime && (gui.renderedRevision != runtime.contentRevision()
          || gui.pending || runtime.craftablePending())) gui.render();
      return false;
    });
  }

  @Override public void onInput(String value) { render(); }

  /** Use the current menu revision, and let render synchronize the result after every edit. */
  @Override public void receiveInput(String value) {
    this.inputText = value;
    onInput(value);
  }

  private record Preview(UltsQuantityPreview quantity, UltsWithdrawalPlan first, long room,
      long available, boolean waiting, boolean shortage) {
    boolean possible() { return !waiting && !shortage && quantity.selected() > 0L && first.available(); }
  }

  private Preview preview(long requested, List<UltsStoredView> stock,
      UltsRuntime.CraftingAmount crafting) {
    long held = UltsRuntime.storedAmount(template, stock);
    long available = boxed ? requested : UltsCraftMath.add(held, crafting.amount());
    boolean waiting = crafting.pending() || (boxed && runtime.packagingPending(stock));
    boolean shortage = !boxed && requested > available;
    if (boxed && requested > 0L && !waiting) {
      var assessment = runtime.assessRequest(template, requested, true, stock);
      waiting = assessment.pending();
      shortage = !assessment.available() && !waiting;
    }
    // A filled box never stacks with an empty one. Use its full components for room checks.
    ItemStack receiving = boxed && !UltsBoxes.isShulker(template)
        ? UltsWithdrawalOutput.packedBox(Items.SHULKER_BOX.getDefaultInstance(), template) : template;
    long room = room(player, receiving);
    var quantity = UltsQuantityPreview.of(requested, shortage ? 0L : available, waiting,
        room, runtime.allowFullInventory());
    int first = (int) Math.min(quantity.selected(), boxed ? 1L : template.getMaxStackSize());
    var batch = runtime.batchPreview(template, first, boxed, stock);
    return new Preview(quantity, batch, room, available,
        waiting || "pending".equals(batch.problem()), shortage);
  }

  /** Always writes both action icons, including invalid and extremely large text input. */
  private void render() {
    renderedRevision = runtime.contentRevision();
    long requested = UltsQuantityPreview.parse(getInput());
    List<UltsStoredView> stock = runtime.storedItems();
    long held = UltsRuntime.storedAmount(template, stock);
    var icon = new GuiElementBuilder(boxed ? Items.SHULKER_BOX.getDefaultInstance()
        : template.copyWithCount(1)).hideDefaultTooltip();
    if (!boxed) icon.glow();
    icon.setName(UltsGuiText.text(boxed ? "ults.withdraw.mode.box" : "ults.withdraw.mode.item")
        .copy().withStyle(ChatFormatting.YELLOW))
        .addLoreLine(template.getHoverName().copy().withStyle(UltsTextBuilder.HIGHLIGHT));
    var crafting = runtime.craftingAmount(template, stock);
    UltsCraftingLore.stored(icon, "ults.withdraw.available", held, crafting.pending());
    icon
        .addLoreLine(UltsGuiText.labelled("ults.withdraw.requested",
            requested == 0L ? "-" : UltsGuiText.format(requested), requested == 0L));
    UltsCraftingLore.add(icon, runtime, template, stock);
    Preview preview = preview(requested, stock, crafting);
    pending = preview.waiting();
    if (boxed) UltsCraftingLore.stored(icon, "ults.withdraw.boxes_available",
        preview.first().boxAvailable(), pending);
    icon.addLoreLine(UltsGuiText.text("ults.withdraw.mode.toggle").copy().withStyle(ChatFormatting.GRAY));
    icon.setCallback(() -> { if (!submitted) { boxed = !boxed; render(); } });
    setSlot(1, icon.build());

    boolean possible = preview.possible();
    var confirm = new GuiElementBuilder(possible ? Items.DYE.lime() : Items.BARRIER)
        .setName(UltsGuiText.text(possible ? "ults.withdraw.confirm" : "ults.withdraw.unavailable")
            .copy().withStyle(possible ? ChatFormatting.GREEN : ChatFormatting.RED));
    if (possible) {
      confirm.addLoreLine(UltsGuiText.text(boxed ? "ults.withdraw.confirm.box" : "ults.withdraw.confirm.item",
          UltsGuiText.format(preview.quantity().selected())).copy().withStyle(ChatFormatting.GREEN))
          .addLoreLine(UltsGuiText.text("ults.withdraw.stream.hint").copy().withStyle(ChatFormatting.GRAY));
      if (preview.quantity().ground() > 0L) confirm.addLoreLine(
          UltsGuiText.text("ults.withdraw.confirm.overflow").copy().withStyle(ChatFormatting.RED));
      if (preview.quantity().left() > 0L) confirm.addLoreLine(UltsGuiText.text(
          "ults.withdraw.stream.left", UltsGuiText.format(preview.quantity().left()))
          .copy().withStyle(ChatFormatting.YELLOW));
      if (boxed) confirm.addLoreLine(UltsGuiText.text("ults.withdraw.stream.box_hint")
          .copy().withStyle(ChatFormatting.GRAY));
      confirm.setCallback(this::confirm);
    } else {
      String problem = pending ? "pending" : requested == 0L ? "invalid"
          : boxed && UltsBoxes.isShulker(template) ? "nested_box" : preview.shortage() ? "shortage"
          : preview.room() == 0L && !runtime.allowFullInventory() ? "inventory"
              : preview.quantity().selected() == 0L ? "gone" : preview.first().problem();
      Component reason = "shortage".equals(problem) ? boxed
          ? UltsGuiText.text("ults.withdraw.problem.shortage.box", UltsGuiText.format(requested))
          : UltsGuiText.text("ults.withdraw.problem.shortage.item",
              UltsGuiText.format(requested), UltsGuiText.format(preview.available()))
          : problem(preview.first(), problem);
      confirm.addLoreLine(reason.copy().withStyle(ChatFormatting.RED));
    }
    setSlot(2, confirm.build());
    // The client recomputes anvil results locally. An unchanged barrier still needs to be sent
    // during pending refreshes; delta-only slot updates can leave the client showing an empty slot.
    if (isOpen()) SguiUtils.sendSlotUpdate(player, syncId, 2, getGuiElement(2).getItemStack(),
        player.containerMenu.incrementStateId());
  }

  private void confirm() {
    if (submitted) return;
    long requested = UltsQuantityPreview.parse(getInput());
    List<UltsStoredView> stock = runtime.storedItemsFresh();
    Preview preview = preview(requested, stock, runtime.craftingAmountFresh(template, stock));
    if (!preview.possible()) { render(); return; }
    submitted = true;
    UltsGuiSound.confirm(player);
    close();
    UltsTakeAllStream.startRequested(runtime, player, template, preview.quantity().selected(), boxed);
    returnTo.run();
  }

  @Override protected void cancel() { close(); returnTo.run(); }

  private static Component problem(UltsWithdrawalPlan first, String problem) {
    return switch (problem) {
      case "pending", "nested_box", "inventory", "gone" -> UltsGuiText.text("ults.withdraw.problem." + problem);
      case "boxes" -> UltsGuiText.text("ults.withdraw.problem.boxes", first.boxRequired(),
          UltsGuiText.format(first.boxAvailable()));
      case "items" -> UltsGuiText.text("ults.withdraw.problem.items",
          UltsGuiText.format(first.itemRequired()), UltsGuiText.format(first.itemAvailable()));
      default -> UltsGuiText.text("ults.withdraw.problem.invalid");
    };
  }

  static boolean canFit(ServerPlayer player, List<ItemStack> outputs) {
    return UltsBackpack.fitting(UltsBackpack.slots(player), outputs) == outputs.size();
  }

  static long room(ServerPlayer player, ItemStack template) {
    return UltsBackpack.room(UltsBackpack.slots(player), template);
  }
}
