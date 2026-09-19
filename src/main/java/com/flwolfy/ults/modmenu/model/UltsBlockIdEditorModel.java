package com.flwolfy.ults.modmenu.model;

import com.flwolfy.ults.modmenu.entry.UltsBlockIdListEntry;
import com.flwolfy.ults.util.UltsBlockIds;
import java.util.ArrayList;
import java.util.List;

/** Synchronizes independent block id widget trees and validates what they hold. */
public final class UltsBlockIdEditorModel {

  private final List<UltsBlockIdListEntry> editors = new ArrayList<>();
  private List<String> value;

  /**
   * Creates a block id view model.
   *
   * @param initial block ids loaded from the configuration file
   */
  public UltsBlockIdEditorModel(List<String> initial) {
    value = List.copyOf(initial);
  }

  /**
   * Registers an independent block id widget tree.
   *
   * @param editor block id entry
   */
  public void register(UltsBlockIdListEntry editor) {
    editors.add(editor);
  }

  /**
   * Publishes one entry's latest values to the shared model and sibling views.
   *
   * @param source publishing entry, or {@code null} when Cloth Config supplies the values
   * @param replacement replacement block ids
   */
  public void publish(UltsBlockIdListEntry source, List<String> replacement) {
    List<String> copied = List.copyOf(replacement);
    if (copied.equals(value)) {
      return;
    }
    value = copied;
    for (UltsBlockIdListEntry editor : editors) {
      if (editor != source) {
        editor.receive(copied);
      }
    }
  }

  /**
   * Publishes values supplied by Cloth Config's save callback.
   *
   * @param replacement replacement block ids
   */
  public void publish(List<String> replacement) {
    publish(null, replacement);
  }

  /**
   * Publishes pending edits from every registered widget tree.
   */
  public void flush() {
    editors.forEach(UltsBlockIdListEntry::publishPendingChanges);
  }

  /**
   * Returns the current block ids.
   *
   * @return immutable block id list
   */
  public List<String> values() {
    return value;
  }

  /**
   * Returns the entries no block of this game uses, which the configuration must not accept.
   *
   * @return immutable list of unusable entries
   */
  public List<String> invalid() {
    return value.stream().filter(entry -> UltsBlockIds.resolve(entry).isEmpty()).toList();
  }
}
