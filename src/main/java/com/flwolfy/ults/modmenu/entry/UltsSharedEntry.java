package com.flwolfy.ults.modmenu.entry;

import com.flwolfy.ults.modmenu.model.UltsValueModel;
import java.util.*;
import java.util.function.Function;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.gui.widget.DynamicEntryListWidget;
import me.shedaniel.math.Rectangle;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;

/** Independent Cloth widgets publish each interaction and rebuild from the shared value on return. */
public final class UltsSharedEntry<T> extends AbstractConfigListEntry<T> {
  private final UltsValueModel<T> model;
  private final Function<T, AbstractConfigListEntry<T>> factory;
  private AbstractConfigListEntry<T> delegate;
  private T shown;

  public UltsSharedEntry(UltsValueModel<T> model, Function<T, AbstractConfigListEntry<T>> factory) {
    this(model, factory, factory.apply(model.value()));
  }

  private UltsSharedEntry(UltsValueModel<T> model, Function<T, AbstractConfigListEntry<T>> factory,
      AbstractConfigListEntry<T> delegate) {
    super(delegate.getFieldName(), delegate.isRequiresRestart());
    this.model = model;
    this.factory = factory;
    this.delegate = delegate;
    shown = model.value();
  }

  /** Publish pending edits before receiving a sibling's value, including edits to the loaded value. */
  private void sync() {
    T current = delegate.getValue();
    if (!Objects.equals(shown, current)) {
      model.publish(current);
      shown = current;
    }
    if (!Objects.equals(shown, model.value())) {
      shown = model.value();
      delegate = factory.apply(shown);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private void bind() {
    sync();
    delegate.setParent((DynamicEntryListWidget) getParent());
    delegate.setScreen(getConfigScreen());
  }

  @Override public T getValue() { sync(); return model.value(); }
  @Override public Optional<T> getDefaultValue() { return delegate.getDefaultValue(); }
  @Override public boolean isEdited() { sync(); return model.edited(); }
  @Override public Optional<Component> getError() { sync(); return delegate.getError(); }
  @Override public Iterator<String> getSearchTags() { return delegate.getSearchTags(); }
  @Override public int getItemHeight() { return delegate.getItemHeight(); }
  @Override public int getInitialReferenceOffset() { return delegate.getInitialReferenceOffset(); }
  @Override public List<? extends GuiEventListener> children() { bind(); return delegate.children(); }
  @Override public List<? extends NarratableEntry> narratables() { bind(); return delegate.narratables(); }
  @Override public GuiEventListener getFocused() { return delegate.getFocused(); }
  @Override public void setFocused(GuiEventListener listener) { delegate.setFocused(listener); }
  @Override public boolean isDragging() { return delegate.isDragging(); }
  @Override public void setDragging(boolean dragging) { delegate.setDragging(dragging); }
  @Override public void tick() { bind(); delegate.tick(); sync(); }
  @Override public void updateSelected(boolean selected) { bind(); delegate.updateSelected(selected); sync(); }
  @Override public void save() { sync(); delegate.save(); }
  @Override public Rectangle getEntryArea(int x, int y, int width, int height) {
    bind(); return delegate.getEntryArea(x, y, width, height);
  }
  @Override public boolean isMouseOver(double x, double y) { bind(); return delegate.isMouseOver(x, y); }
  @Override public void extractRenderState(GuiGraphicsExtractor graphics, int index, int y, int x,
      int width, int height, int mouseX, int mouseY, boolean hovered, float delta) {
    bind();
    delegate.extractRenderState(graphics, index, y, x, width, height, mouseX, mouseY, hovered, delta);
    sync();
  }
  @Override public void lateRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
    bind(); delegate.lateRender(graphics, mouseX, mouseY, delta);
  }
  @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
    bind(); boolean handled = delegate.mouseClicked(event, doubleClick); sync(); return handled;
  }
  @Override public boolean mouseReleased(MouseButtonEvent event) {
    bind(); boolean handled = delegate.mouseReleased(event); sync(); return handled;
  }
  @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
    bind(); boolean handled = delegate.mouseDragged(event, dx, dy); sync(); return handled;
  }
  @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
    bind(); boolean handled = delegate.mouseScrolled(x, y, dx, dy); sync(); return handled;
  }
  @Override public boolean keyPressed(KeyEvent event) {
    bind(); boolean handled = delegate.keyPressed(event); sync(); return handled;
  }
  @Override public boolean keyReleased(KeyEvent event) {
    bind(); boolean handled = delegate.keyReleased(event); sync(); return handled;
  }
  @Override public boolean charTyped(CharacterEvent event) {
    bind(); boolean handled = delegate.charTyped(event); sync(); return handled;
  }
}
