package com.flwolfy.ults.modmenu;

import static org.junit.jupiter.api.Assertions.*;
import com.flwolfy.ults.modmenu.entry.UltsSharedEntry;
import com.flwolfy.ults.modmenu.model.UltsValueModel;
import java.util.*;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.*;

@Tag("acceptance")
class UltsSharedEntryTest {
  static final class Entry<T> extends AbstractConfigListEntry<T> {
    T value, pending;
    final T defaults;
    Entry(T value, T defaults) { super(Component.literal("setting"), false); this.value = value; this.defaults = defaults; }
    @Override public T getValue() { return value; }
    @Override public Optional<T> getDefaultValue() { return Optional.of(defaults); }
    @Override public List<? extends GuiEventListener> children() { return List.of(); }
    @Override public List<? extends NarratableEntry> narratables() { return List.of(); }
    @Override public boolean keyPressed(KeyEvent ignored) { value = pending; return true; }
  }
  static final class View<T> {
    Entry<T> widget;
    final UltsSharedEntry<T> entry;
    View(UltsValueModel<T> model, T defaults) {
      entry = new UltsSharedEntry<>(model, value -> widget = new Entry<>(value, defaults));
    }
    void edit(T value) { entry.getValue(); widget.pending = value; assertTrue(entry.keyPressed(null)); }
  }
  @Test void latestInteractionWinsInEitherTabOrder() {
    var model = new UltsValueModel<>(64);
    var tab = new View<>(model, 64);
    var overview = new View<>(model, 64);
    tab.edit(128);
    overview.edit(256);
    assertEquals(256, tab.entry.getValue());
    assertEquals(256, tab.widget.value);
    tab.edit(512);
    assertEquals(512, overview.entry.getValue());
    assertEquals(512, overview.widget.value);
  }
  @Test void resettingToTheLoadedValueIsARealEditAndSynchronizesBothViews() {
    var model = new UltsValueModel<>(64);
    var tab = new View<>(model, 64);
    var overview = new View<>(model, 64);
    tab.edit(128);
    assertTrue(overview.entry.isEdited());
    overview.edit(64);
    assertEquals(64, tab.entry.getValue());
    assertFalse(tab.entry.isEdited());
    assertFalse(overview.entry.isEdited());
  }
  @Test void togglesCanBeUndoneFromTheOtherView() {
    var model = new UltsValueModel<>(false);
    var tab = new View<>(model, false);
    var overview = new View<>(model, false);
    overview.edit(true);
    tab.edit(false);
    assertFalse(overview.entry.getValue());
    assertFalse(model.edited());
  }
  @Test void savingAnUnchangedStaleCopyDoesNotOverwriteAnEdit() {
    var model = new UltsValueModel<>(64);
    var tab = new View<>(model, 64);
    var overview = new View<>(model, 64);
    overview.edit(256);
    tab.entry.save();
    assertEquals(256, model.value());
    assertEquals(64, tab.entry.getDefaultValue().orElseThrow());
  }
  @Test void pendingEditsArePublishedWithoutRequiringARender() {
    var model = new UltsValueModel<>(64);
    var tab = new View<>(model, 64);
    var overview = new View<>(model, 64);
    tab.widget.value = 128;
    assertEquals(128, tab.entry.getValue());
    overview.widget.value = 256;
    assertEquals(256, overview.entry.getValue());
    assertEquals(256, tab.entry.getValue());
  }
}
