package com.flwolfy.ults.data.config;

import com.flwolfy.ults.data.lang.UltsLangManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record UltsConfigData(General general, Input input) {

  /** The supported range of {@code input.drainInterval}. */
  public static final int MIN_DRAIN_INTERVAL = 1;
  public static final int MAX_DRAIN_INTERVAL = 20;

  public record General(
      String language,
      UltsItemVisibility itemVisibility,
      UltsStorageMode storageMode
  ) {}

  public record Input(
      int bindPermissionLevel,
      int deletePermissionLevel,
      int maxBindings,
      int drainInterval
  ) {}

  public static final UltsConfigData DEFAULT = new UltsConfigData(
      new General("en_us", UltsItemVisibility.STOCKED_COMPACT, UltsStorageMode.VOID),
      new Input(2, 2, 0, 2)
  );

  public List<String> validate() {
    List<String> invalid = new ArrayList<>();
    if (general == null || general.language() == null
        || !UltsLangManager.getInstance().availableLocales().contains(
            general.language().trim().toLowerCase(Locale.ROOT))) {
      invalid.add("general.language");
    }
    if (general == null || general.itemVisibility() == null) {
      invalid.add("general.itemVisibility");
    }
    if (general == null || general.storageMode() == null) {
      invalid.add("general.storageMode");
    }
    if (input == null || input.bindPermissionLevel() < 0 || input.bindPermissionLevel() > 4) {
      invalid.add("input.bindPermissionLevel");
    }
    if (input == null || input.deletePermissionLevel() < 0 || input.deletePermissionLevel() > 4) {
      invalid.add("input.deletePermissionLevel");
    }
    if (input == null || input.maxBindings() < 0) {
      invalid.add("input.maxBindings");
    }
    if (input == null || input.drainInterval() < MIN_DRAIN_INTERVAL
        || input.drainInterval() > MAX_DRAIN_INTERVAL) {
      invalid.add("input.drainInterval");
    }
    return List.copyOf(invalid);
  }

  public UltsConfigData canonicalize() {
    return new UltsConfigData(
        new General(
            general.language().trim().toLowerCase(Locale.ROOT),
            general.itemVisibility(),
            general.storageMode()
        ),
        new Input(
            input.bindPermissionLevel(),
            input.deletePermissionLevel(),
            input.maxBindings(),
            input.drainInterval()
        )
    );
  }
}
