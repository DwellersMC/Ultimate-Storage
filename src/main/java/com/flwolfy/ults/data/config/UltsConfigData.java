package com.flwolfy.ults.data.config;

import com.flwolfy.ults.data.lang.UltsLangManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.Identifier;

public record UltsConfigData(General general, Input input, Special special) {

  /** The supported range of {@code input.drainInterval}. */
  public static final int MIN_DRAIN_INTERVAL = 1;
  public static final int MAX_DRAIN_INTERVAL = 20;

  /** How many stored stacks one page of a bag holds: the grid the bag screen draws them in. */
  public static final int BUNDLE_PAGE_SIZE = 45;
  /** How many stored stacks one bag holds out of the box, which is three pages of that grid. */
  public static final int DEFAULT_BUNDLE_SLOTS = 120;
  /** The largest number of slots one bundle may be given. */
  public static final int MAX_BUNDLE_SLOTS = 1_000_000;

  public record General(
      String language,
      UltsItemVisibility itemVisibility,
      UltsStorageMode storageMode
  ) {}

  /**
   * How the storage is fed and emptied.
   *
   * @param permissionLevel vanilla permission level needed to manage the storage
   * @param maxBindings how many containers may be bound; {@code 0} is unlimited
   * @param drainInterval ticks between two drain visits of one bound container
   * @param multiBlockContainers block ids whose connected blocks form one large container
   * @param crafting whether a withdrawal may craft what is missing
   * @param allowFullInventory whether a withdrawal may go ahead with no room in the inventory, in
   *     which case what does not fit is dropped on the ground
   */
  public record Input(
      int permissionLevel,
      int maxBindings,
      int drainInterval,
      List<String> multiBlockContainers,
      UltsCraftingMode crafting,
      boolean allowFullInventory
  ) {}

  /**
   * What the storage does with the stacks that carry data of their own.
   *
   * @param bundleSlots how many stacks one bag holds; past it the oldest are destroyed, and
   *     {@link #pagesOf(int)} says how many pages of a bag that is
   * @param stackRule when two stacks of one item are the same kind of thing and pool into one row
   * @param filterMode what the filter does to the items it names
   * @param filters item ids the filter names itself
   */
  public record Special(
      int bundleSlots,
      UltsStackRule stackRule,
      UltsSpecialFilter filterMode,
      List<String> filters
  ) {}

  public static final UltsConfigData DEFAULT = new UltsConfigData(
      new General("en_us", UltsItemVisibility.AVAILABLE, UltsStorageMode.VOID),
      new Input(2, 0, 2, List.of(), UltsCraftingMode.DISABLED, false),
      new Special(
          DEFAULT_BUNDLE_SLOTS,
          UltsStackRule.COMPONENTS,
          UltsSpecialFilter.OFF,
          List.of())
  );

  /** How many pages of a bag a number of stored stacks fills. */
  public static int pagesOf(int entries) {
    return entries <= 0 ? 0 : (entries + BUNDLE_PAGE_SIZE - 1) / BUNDLE_PAGE_SIZE;
  }

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
    if (input == null || input.permissionLevel() < 0 || input.permissionLevel() > 4) {
      invalid.add("input.permissionLevel");
    }
    if (input == null || input.maxBindings() < 0) {
      invalid.add("input.maxBindings");
    }
    if (input == null || input.drainInterval() < MIN_DRAIN_INTERVAL
        || input.drainInterval() > MAX_DRAIN_INTERVAL) {
      invalid.add("input.drainInterval");
    }
    if (input == null || input.multiBlockContainers() == null
        || input.multiBlockContainers().stream().anyMatch(
            value -> value == null || Identifier.tryParse(value) == null)) {
      invalid.add("input.multiBlockContainers");
    }
    if (input == null || input.crafting() == null) {
      invalid.add("input.crafting");
    }
    if (special == null || special.bundleSlots() < 1
        || special.bundleSlots() > MAX_BUNDLE_SLOTS) {
      invalid.add("special.bundleSlots");
    }
    if (special == null || special.stackRule() == null) {
      invalid.add("special.stackRule");
    }
    if (special == null || special.filterMode() == null) {
      invalid.add("special.filterMode");
    }
    if (special == null || special.filters() == null
        || special.filters().stream().anyMatch(
            value -> value == null || Identifier.tryParse(value) == null)) {
      invalid.add("special.filters");
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
            input.permissionLevel(),
            input.maxBindings(),
            input.drainInterval(),
            normalize(input.multiBlockContainers()),
            input.crafting(),
            input.allowFullInventory()
        ),
        new Special(
            special.bundleSlots(),
            special.stackRule(),
            special.filterMode(),
            normalize(special.filters())
        )
    );
  }

  /** Ids are stored the way they are looked up: trimmed, lower case and without duplicates. */
  private static List<String> normalize(List<String> values) {
    return values.stream()
        .map(value -> value.trim().toLowerCase(Locale.ROOT))
        .distinct()
        .toList();
  }
}
