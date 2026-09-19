package com.flwolfy.ults.modmenu.entry;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.modmenu.model.UltsBlockIdEditorModel;
import java.util.List;
import java.util.Optional;
import me.shedaniel.clothconfig2.gui.entries.AbstractTextFieldListListEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Block id list whose initial and dynamically added rows use the same custom cell type.
 *
 * <p>The list keeps its own row geometry for hit tests and it maintains the focus chain of the row
 * that is being edited, because Cloth only hands the keyboard to a text list cell through a selection
 * flag that does not reach the copy of the list shown inside a section.
 */
public final class UltsBlockIdListEntry extends AbstractTextFieldListListEntry<
    String,
    UltsBlockIdCell,
    UltsBlockIdListEntry
> {

  private static final String KEY = "ults.config.multi_block_containers";
  /** Height of the row that carries the label and the add and delete buttons. */
  private static final int HEADER_HEIGHT = 24;
  private static final int ROW_INDENT = 14;

  private final UltsBlockIdEditorModel model;
  private final boolean suppressErrors;
  private List<String> observedValues;
  private int renderX;
  private int renderY;
  private int renderWidth;
  private boolean renderExpanded;
  private boolean renderKnown;

  /**
   * Creates an independently rendered block id list.
   *
   * @param title localized list title
   * @param model shared block id model
   * @param resetButtonKey Cloth Config reset button text
   * @param suppressErrors whether this All-category copy suppresses aggregate errors
   */
  public UltsBlockIdListEntry(
      Component title,
      UltsBlockIdEditorModel model,
      Component resetButtonKey,
      boolean suppressErrors
  ) {
    super(
        title,
        model.values(),
        true,
        () -> Optional.of(new Component[]{Component.translatable(KEY + ".tooltip")}),
        model::publish,
        () -> UltsConfigData.DEFAULT.input().multiBlockContainers(),
        resetButtonKey,
        false,
        true,
        true,
        UltsBlockIdCell::new
    );
    this.model = model;
    this.suppressErrors = suppressErrors;
    observedValues = model.values();
    model.register(this);
  }

  @Override
  public UltsBlockIdListEntry self() {
    return this;
  }

  @Override
  public Optional<Component> getError() {
    return suppressErrors ? Optional.empty() : super.getError();
  }

  @Override
  public boolean isMouseOver(double mouseX, double mouseY) {
    if (super.isMouseOver(mouseX, mouseY)) {
      return true;
    }
    for (GuiEventListener child : children()) {
      if (child.isMouseOver(mouseX, mouseY)) {
        return true;
      }
    }
    return false;
  }

  /** Routes a click to the row under the pointer, using the geometry of the last render. */
  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (!isEnabled()) {
      return false;
    }
    UltsBlockIdCell row = rowAt(event.x(), event.y());
    if (row != null) {
      // The row takes its own input focus, which also links the focus chain down to that input.
      row.mouseClicked(event, doubleClick);
      // Cloth clears the old focused child before setting the new one and cannot set the same child
      // twice, so a repeated click on the editing row would drop the input focus again.
      if (getFocused() != row) {
        setFocused(row);
      }
      if (!row.isEditing()) {
        UltsMod.LOGGER.warn("UltStorage: a clicked row did not take the keyboard focus");
      }
      setDragging(true);
      return true;
    }
    if (isInsideCreateNew(event.x(), event.y())) {
      addRow();
      return true;
    }
    if (isInsideDelete(event.x(), event.y()) && deleteRow()) {
      return true;
    }
    return super.mouseClicked(event, doubleClick);
  }

  /**
   * Forwards a key press to the editing row.
   *
   * <p>Cloth walks the focus chain first; the input of the editing row is the fallback that keeps a
   * keystroke from being lost when that chain is incomplete.
   */
  @Override
  public boolean keyPressed(KeyEvent event) {
    return super.keyPressed(event)
        || editingCell().map(cell -> cell.keyToWidget(event)).orElse(false);
  }

  @Override
  public boolean keyReleased(KeyEvent event) {
    return super.keyReleased(event)
        || editingCell().map(cell -> cell.keyReleaseToWidget(event)).orElse(false);
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    return super.charTyped(event)
        || editingCell().map(cell -> cell.charToWidget(event)).orElse(false);
  }

  @Override
  public boolean preeditUpdated(@Nullable PreeditEvent event) {
    return super.preeditUpdated(event)
        || editingCell().map(cell -> cell.preeditToWidget(event)).orElse(false);
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
    renderX = x;
    renderY = y;
    renderWidth = entryWidth;
    renderExpanded = isExpanded();
    renderKnown = true;
    publishPendingChanges();
    for (UltsBlockIdCell cell : cells) {
      if (!narratables.contains(cell)) {
        narratables.add(cell);
      }
    }
    super.extractRenderState(
        graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, delta
    );
  }

  /** Publishes this widget tree's pending cell values to the shared model. */
  public void publishPendingChanges() {
    List<String> current = List.copyOf(getValue());
    if (current.equals(observedValues)) {
      return;
    }
    observedValues = current;
    model.publish(this, current);
  }

  /**
   * Receives values published by another independent block id view.
   *
   * @param replacement replacement block ids
   */
  public void receive(List<String> replacement) {
    if (!List.copyOf(getValue()).equals(observedValues)) {
      return;
    }
    replaceCells(replacement);
    observedValues = List.copyOf(replacement);
  }

  /**
   * Returns the one-based position of a cell in this list.
   *
   * @param cell rendered cell
   * @return one-based row number
   */
  int numberOf(UltsBlockIdCell cell) {
    return cells.indexOf(cell) + 1;
  }

  /** Finds the row that covers one point of the screen, or {@code null} when no row does. */
  private UltsBlockIdCell rowAt(double mouseX, double mouseY) {
    if (!renderKnown || !renderExpanded) {
      return null;
    }
    int rowX = renderX + ROW_INDENT;
    if (mouseX < rowX || mouseX > renderX + renderWidth) {
      return null;
    }
    int rowY = renderY + HEADER_HEIGHT;
    for (UltsBlockIdCell cell : cells) {
      int height = cell.getCellHeight();
      if (mouseY >= rowY && mouseY < rowY + height) {
        return cell;
      }
      rowY += height;
    }
    return null;
  }

  /** Adds one empty row, in front of the others when the list is configured that way. */
  private void addRow() {
    setExpanded(true);
    UltsBlockIdCell cell = getFromValue("");
    if (insertInFront()) {
      cells.add(0, cell);
      widgets.add(0, cell);
    } else {
      cells.add(cell);
      widgets.add(cell);
    }
    cell.onAdd();
    observedValues = List.copyOf(getValue());
    model.publish(this, observedValues);
  }

  /** Removes the row that currently holds the keyboard. */
  private boolean deleteRow() {
    GuiEventListener focused = getFocused();
    if (!isExpanded() || !(focused instanceof UltsBlockIdCell cell)) {
      return false;
    }
    cell.onDelete();
    cells.remove(cell);
    widgets.remove(cell);
    setFocused(null);
    observedValues = List.copyOf(getValue());
    model.publish(this, observedValues);
    return true;
  }

  /**
   * Finds the row whose input currently holds the keyboard.
   *
   * <p>Cloth's own key path walks the focus chain, which a row inside a section does not always reach,
   * so the input of the editing row is used as a fallback instead of losing the keystroke.
   */
  private Optional<UltsBlockIdCell> editingCell() {
    return cells.stream().filter(UltsBlockIdCell::isEditing).findFirst();
  }

  private void replaceCells(List<String> replacement) {
    widgets.removeAll(cells);
    narratables.removeAll(cells);
    for (UltsBlockIdCell cell : cells) {
      cell.onDelete();
    }
    cells.clear();
    for (String value : replacement) {
      UltsBlockIdCell cell = getFromValue(value);
      cells.add(cell);
      widgets.add(cell);
      narratables.add(cell);
    }
  }
}
