package com.flwolfy.ults.modmenu.entry;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Expandable;
import me.shedaniel.clothconfig2.gui.entries.SubCategoryListEntry;
import me.shedaniel.clothconfig2.gui.widget.DynamicEntryListWidget;
import me.shedaniel.math.Rectangle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Delegates an expandable container to Cloth Config's supported builder API. */
public final class UltsSectionEntry
    extends AbstractConfigListEntry<List<AbstractConfigListEntry<?>>> implements Expandable {

  private final SubCategoryListEntry delegate;
  private final GuiEventListener labelWidget;
  private final NarratableEntry labelNarratable;
  private final boolean suppressErrors;
  private final Rectangle interactionArea = new Rectangle();

  /**
   * Creates an expandable section through Cloth Config's public builder API.
   *
   * @param builder Cloth Config entry builder
   * @param title localized section title
   * @param entries initial child entries
   * @param expanded whether the section starts expanded
   * @param suppressErrors whether the aggregate child errors are hidden
   */
  public UltsSectionEntry(
      ConfigEntryBuilder builder,
      Component title,
      List<AbstractConfigListEntry<?>> entries,
      boolean expanded,
      boolean suppressErrors
  ) {
    super(title, false);
    var subcategory = builder.startSubCategory(title).setExpanded(expanded);
    subcategory.addAll(entries);
    delegate = subcategory.build();
    labelWidget = delegate.children().getFirst();
    labelNarratable = delegate.narratables().getFirst();
    this.suppressErrors = suppressErrors;
    setReferenceProviderEntries(new ArrayList<>(entries));
  }

  @Override
  public Iterator<String> getSearchTags() {
    return delegate.getSearchTags();
  }

  @Override
  public boolean isExpanded() {
    return delegate.isExpanded();
  }

  @Override
  public void setExpanded(boolean expanded) {
    delegate.setExpanded(expanded);
  }

  @Override
  public boolean isRequiresRestart() {
    return delegate.isRequiresRestart();
  }

  @Override
  public void setRequiresRestart(boolean requiresRestart) {
    delegate.setRequiresRestart(requiresRestart);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<AbstractConfigListEntry<?>> getValue() {
    return (List<AbstractConfigListEntry<?>>) (List<?>) delegate.getValue();
  }

  @Override
  public Optional<List<AbstractConfigListEntry<?>>> getDefaultValue() {
    return Optional.empty();
  }

  @Override
  @SuppressWarnings({"rawtypes", "unchecked"})
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
    bindDelegate();
    interactionArea.setBounds(delegate.getEntryArea(x, y, entryWidth, entryHeight));
    delegate.extractRenderState(
        graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, delta
    );
  }

  @Override
  public void tick() {
    delegate.tick();
  }

  @Override
  public void updateSelected(boolean selected) {
    delegate.updateSelected(selected);
  }

  @Override
  public boolean isEdited() {
    return delegate.isEdited();
  }

  @Override
  public void lateRender(
      GuiGraphicsExtractor graphics,
      int mouseX,
      int mouseY,
      float delta
  ) {
    delegate.lateRender(graphics, mouseX, mouseY, delta);
  }

  @Override
  public Rectangle getEntryArea(int x, int y, int entryWidth, int entryHeight) {
    bindDelegate();
    interactionArea.setBounds(delegate.getEntryArea(x, y, entryWidth, entryHeight));
    return new Rectangle(interactionArea);
  }

  @Override
  public void setFocused(GuiEventListener listener) {
    if (getFocused() == listener) {
      return;
    }
    super.setFocused(listener);
    delegate.setFocused(listener);
  }

  @Override
  public int getItemHeight() {
    return delegate.getItemHeight();
  }

  @Override
  public int getInitialReferenceOffset() {
    return delegate.getInitialReferenceOffset();
  }

  @Override
  public List<? extends GuiEventListener> children() {
    List<GuiEventListener> children = new ArrayList<>();
    children.add(labelWidget);
    children.addAll(getValue());
    return children;
  }

  @Override
  public List<? extends NarratableEntry> narratables() {
    List<NarratableEntry> narratables = new ArrayList<>();
    narratables.add(labelNarratable);
    narratables.addAll(getValue());
    return narratables;
  }

  @Override
  public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    if (!isEnabled()) {
      return false;
    }
    Optional<GuiEventListener> target = getChildAt(event.x(), event.y());
    if (target.isEmpty()) {
      return false;
    }
    GuiEventListener listener = target.get();
    boolean handled = listener.mouseClicked(event, doubleClick);
    if (handled && listener.shouldTakeFocusAfterInteraction() && getFocused() != listener) {
      setFocused(listener);
      if (event.button() == 0) {
        setDragging(true);
      }
    }
    return handled;
  }

  @Override
  public boolean mouseReleased(MouseButtonEvent event) {
    return super.mouseReleased(event);
  }

  @Override
  public boolean mouseDragged(MouseButtonEvent event, double offsetX, double offsetY) {
    return super.mouseDragged(event, offsetX, offsetY);
  }

  @Override
  public boolean mouseScrolled(
      double mouseX,
      double mouseY,
      double horizontalAmount,
      double verticalAmount
  ) {
    return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
  }

  @Override
  public boolean keyPressed(KeyEvent event) {
    return super.keyPressed(event);
  }

  @Override
  public boolean keyReleased(KeyEvent event) {
    return super.keyReleased(event);
  }

  @Override
  public boolean charTyped(CharacterEvent event) {
    return super.charTyped(event);
  }

  @Override
  public void save() {
    delegate.save();
  }

  @Override
  public Optional<Component> getError() {
    return suppressErrors ? Optional.empty() : delegate.getError();
  }

  @Override
  public boolean isMouseOver(double mouseX, double mouseY) {
    return interactionArea.contains(mouseX, mouseY)
        || delegate.isMouseOver(mouseX, mouseY);
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void bindDelegate() {
    delegate.setParent((DynamicEntryListWidget) getParent());
    delegate.setScreen(getConfigScreen());
  }
}
