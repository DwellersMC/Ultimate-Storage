package com.flwolfy.ults.modmenu.entry;

import com.flwolfy.ults.util.UltsBlockIds;
import java.util.Optional;
import me.shedaniel.clothconfig2.gui.entries.AbstractTextFieldListListEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Block id cell that numbers its row, marks its focus and rejects ids no block of this game uses.
 *
 * <p>The cell drives its own input focus. Cloth re-applies the keyboard focus of a cell every frame
 * from its selection flag, and that flag never reaches the copy of the list that lives inside a
 * section, so the focus chain of the cell decides instead: without it the input loses the keyboard,
 * backspace is dropped and the arrow keys fall through to the list navigation.
 */
public final class UltsBlockIdCell extends AbstractTextFieldListListEntry
    .AbstractTextFieldListCell<
        String,
        UltsBlockIdCell,
        UltsBlockIdListEntry
    > {

  private static final String KEY = "ults.config.multi_block_containers";
  private static final int NUMBER_COLOR = 0xFFFFAA00;
  private static final int LINE_COLOR = 0x60FFFFFF;
  private static final int FOCUSED_LINE_COLOR = 0xFFFFFFFF;
  private static final int ERROR_LINE_COLOR = 0xFFFF5555;

  private final UltsBlockIdListEntry entry;

  /**
   * Creates one block id input cell.
   *
   * @param value initial block id
   * @param entry owning block id list
   */
  public UltsBlockIdCell(
      String value,
      UltsBlockIdListEntry entry
  ) {
    super(value, entry);
    this.entry = entry;
    widget.setHint(Component.translatable(KEY + ".hint"));
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    // Places the caret when the input itself was hit, and links this row to its input in any case.
    super.mouseClicked(event, doubleClick);
    setFocused(widget);
    return true;
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    if (super.keyPressed(event)) {
      return true;
    }
    // A single line input has no use for the vertical arrows, and consuming them keeps the list from
    // scrolling and selecting another row while this one is being edited.
    return isEditing() && (event.key() == 264 || event.key() == 265);
  }

  @Override
  public void extractRenderState(
      GuiGraphicsExtractor graphics,
      int index,
      int y,
      int x,
      int entryWidth,
      int entryHeight,
      int mouseX,
      int mouseY,
      boolean hovered,
      float delta
  ) {
    boolean editing = getFocused() != null;
    super.extractRenderState(
        graphics,
        index,
        y,
        x,
        entryWidth,
        entryHeight,
        mouseX,
        mouseY,
        hovered,
        delta
    );
    if (editing) {
      widget.setFocused(true);
    }
    Component number = Component.literal(entry.numberOf(this) + ".")
        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    int numberX = widget.getX()
        - Minecraft.getInstance().font.width(number)
        - 4;
    graphics.text(
        Minecraft.getInstance().font,
        number,
        numberX,
        widget.getY(),
        NUMBER_COLOR
    );
    int lineColor = getError().isPresent()
        ? ERROR_LINE_COLOR
        : editing ? FOCUSED_LINE_COLOR : LINE_COLOR;
    graphics.fill(
        widget.getX(),
        widget.getY() + 11,
        widget.getX() + widget.getWidth(),
        widget.getY() + 12,
        lineColor
    );
  }

  @Override
  protected String substituteDefault(String value) {
    return value == null ? "" : value;
  }

  @Override
  protected boolean isValidText(String value) {
    return true;
  }

  @Override
  public String getValue() {
    return widget.getValue();
  }

  /** Whether the keyboard currently sits on this cell's input. */
  boolean isEditing() {
    return getFocused() != null;
  }

  /**
   * Sends one key press straight to this cell's input.
   *
   * @param event key press
   * @return whether the input used the key
   */
  boolean keyToWidget(KeyEvent event) {
    return widget.keyPressed(event);
  }

  /**
   * Sends one key release straight to this cell's input.
   *
   * @param event key release
   * @return whether the input used the key
   */
  boolean keyReleaseToWidget(KeyEvent event) {
    return widget.keyReleased(event);
  }

  /**
   * Sends one typed character straight to this cell's input.
   *
   * @param event typed character
   * @return whether the input used the character
   */
  boolean charToWidget(CharacterEvent event) {
    return widget.charTyped(event);
  }

  /**
   * Sends one input method composition update straight to this cell's input.
   *
   * @param event composition update
   * @return whether the input used the event
   */
  boolean preeditToWidget(@Nullable PreeditEvent event) {
    return widget.preeditUpdated(event);
  }

  /**
   * Whether the text names a block of this game.
   *
   * <p>The verdict is given while the row is edited as well: Cloth only disables the save button of
   * the screen while an entry reports an error, so hiding the error until the row loses focus would
   * let an unusable id be saved.
   */
  @Override
  public Optional<Component> getError() {
    return UltsBlockIds.resolve(getValue()).isPresent() ? Optional.empty() : invalid();
  }

  private static Optional<Component> invalid() {
    return Optional.of(Component.translatable(KEY + ".invalid"));
  }
}
