package com.flwolfy.ults.modmenu.model;

import com.flwolfy.ults.modmenu.entry.UltsIdListEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Synchronizes independent id widget trees and validates what they hold. */
public final class UltsIdEditorModel {

  private final List<UltsIdListEntry> editors = new ArrayList<>();
  /** Whether one id names something this game has, which is what a row must say to be saved. */
  private final Predicate<String> resolvable;
  private final List<String> defaults;
  private List<String> value;

  /**
   * Creates an id list view model.
   *
   * @param initial ids loaded from the configuration file
   * @param defaults ids the reset button restores
   * @param resolvable whether one id names something this game has
   */
  public UltsIdEditorModel(List<String> initial, List<String> defaults,
      Predicate<String> resolvable) {
    value = List.copyOf(initial);
    this.defaults = List.copyOf(defaults);
    this.resolvable = resolvable;
  }

  /**
   * Registers an independent id widget tree.
   *
   * @param editor id list entry
   */
  public void register(UltsIdListEntry editor) {
    editors.add(editor);
  }

  /**
   * Publishes one entry's latest values to the shared model and sibling views.
   *
   * @param source publishing entry, or {@code null} when Cloth Config supplies the values
   * @param replacement replacement ids
   */
  public void publish(UltsIdListEntry source, List<String> replacement) {
    List<String> copied = List.copyOf(replacement);
    if (copied.equals(value)) {
      return;
    }
    value = copied;
    for (UltsIdListEntry editor : editors) {
      if (editor != source) {
        editor.receive(copied);
      }
    }
  }

  /**
   * Publishes values supplied by Cloth Config's save callback.
   *
   * @param replacement replacement ids
   */
  public void publish(List<String> replacement) {
    publish(null, replacement);
  }

  /** Publishes pending edits from every registered widget tree. */
  public void flush() {
    editors.forEach(UltsIdListEntry::publishPendingChanges);
  }

  /**
   * Returns the current ids.
   *
   * @return immutable id list
   */
  public List<String> values() {
    return value;
  }

  /**
   * Returns the ids the reset button restores.
   *
   * @return immutable id list
   */
  public List<String> defaults() {
    return defaults;
  }

  /**
   * Whether one id names something this game has.
   *
   * @param entry configured id
   * @return whether the id can be used
   */
  public boolean resolvable(String entry) {
    return resolvable.test(entry);
  }

  /**
   * Returns the entries no game object of this kind uses, which the configuration must not accept.
   *
   * @return immutable list of unusable entries
   */
  public List<String> invalid() {
    return value.stream().filter(entry -> !resolvable(entry)).toList();
  }
}
