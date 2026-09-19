package com.flwolfy.ults.modmenu;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsCraftingMode;
import com.flwolfy.ults.data.config.UltsItemVisibility;
import com.flwolfy.ults.data.config.UltsStorageMode;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.flwolfy.ults.modmenu.entry.UltsBlockIdListEntry;
import com.flwolfy.ults.modmenu.entry.UltsSectionEntry;
import com.flwolfy.ults.modmenu.model.UltsBlockIdEditorModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class UltsConfigScreen {

  private static final String KEY = "ults.config.";
  private static final SystemToast.SystemToastId SAVE_RESULT = new SystemToast.SystemToastId();

  private UltsConfigScreen() {}

  public static Screen create(Screen parent) {
    UltsConfigData current = UltsConfigManager.getInstance().loadForEditing();
    ConfigBuilder builder = ConfigBuilder.create()
        .setParentScreen(parent)
        .setTitle(Component.translatable(KEY + "title"))
        .setDoesConfirmSave(true);
    ConfigEntryBuilder entries = builder.entryBuilder();

    ConfigCategory all = builder.getOrCreateCategory(Component.translatable(KEY + "all"));
    ConfigCategory general = builder.getOrCreateCategory(Component.translatable(KEY + "general"));
    ConfigCategory input = builder.getOrCreateCategory(Component.translatable(KEY + "input"));

    all.addEntry(entries.startTextDescription(Component.translatable(KEY + "local_only")
        .withStyle(ChatFormatting.GOLD, ChatFormatting.ITALIC)).build());

    Field<String> language = new Field<>(current.general().language());
    Field<UltsItemVisibility> itemVisibility = new Field<>(current.general().itemVisibility());
    Field<UltsStorageMode> storageMode = new Field<>(current.general().storageMode());
    Field<Integer> permission = new Field<>(current.input().permissionLevel());
    Field<Integer> maxBindings = new Field<>(current.input().maxBindings());
    Field<Integer> drainInterval = new Field<>(current.input().drainInterval());
    Field<UltsCraftingMode> crafting = new Field<>(current.input().crafting());
    UltsBlockIdEditorModel multiBlock =
        new UltsBlockIdEditorModel(current.input().multiBlockContainers());

    // Every section is shown twice: once in its own tab and once inside the "all" overview. Scalar
    // settings share a field, so the copy that was changed away from the loaded value wins; the block
    // id list shares a model instead, which keeps both copies in step while they are edited.
    List<AbstractConfigListEntry<?>> generalEntries = List.of(
        languageEntry(entries, language),
        itemVisibilityEntry(entries, itemVisibility),
        storageModeEntry(entries, storageMode)
    );
    List<AbstractConfigListEntry<?>> inputEntries = List.of(
        permissionEntry(entries, permission),
        countEntry(entries, maxBindings, "max_bindings"),
        drainEntry(entries, drainInterval),
        craftingEntry(entries, crafting),
        multiBlockEntry(entries, multiBlock, false)
    );
    List<AbstractConfigListEntry<?>> generalOverview = List.of(
        languageEntry(entries, language),
        itemVisibilityEntry(entries, itemVisibility),
        storageModeEntry(entries, storageMode)
    );
    List<AbstractConfigListEntry<?>> inputOverview = List.of(
        permissionEntry(entries, permission),
        countEntry(entries, maxBindings, "max_bindings"),
        drainEntry(entries, drainInterval),
        craftingEntry(entries, crafting),
        multiBlockEntry(entries, multiBlock, true)
    );

    generalEntries.forEach(general::addEntry);
    inputEntries.forEach(input::addEntry);
    all.addEntry(subCategory(entries, "general", generalOverview));
    all.addEntry(subCategory(entries, "input", inputOverview));

    builder.setSavingRunnable(() -> {
      multiBlock.flush();
      save(multiBlock, new UltsConfigData(
          new UltsConfigData.General(
              language.resolve(), itemVisibility.resolve(), storageMode.resolve()),
          new UltsConfigData.Input(
              permission.resolve(),
              maxBindings.resolve(),
              drainInterval.resolve(),
              multiBlock.values(),
              crafting.resolve()
          )
      ));
    });
    return builder.build();
  }

  /**
   * Header of one section inside the "all" overview.
   *
   * <p>The overview mirrors the tabs, so the errors a section reports are already shown by the tab
   * copy of the same entries and are hidden here.
   */
  private static AbstractConfigListEntry<?> subCategory(
      ConfigEntryBuilder entries,
      String key,
      List<AbstractConfigListEntry<?>> children
  ) {
    return new UltsSectionEntry(
        entries,
        Component.translatable(KEY + key).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
        children,
        true,
        true);
  }

  private static AbstractConfigListEntry<?> languageEntry(
      ConfigEntryBuilder entries,
      Field<String> field
  ) {
    String[] locales = UltsLangManager.getInstance().availableLocales().toArray(String[]::new);
    AbstractConfigListEntry<String> entry = entries.startSelector(
            Component.translatable(KEY + "language"), locales, field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.general().language())
        .setTooltip(Component.translatable(KEY + "language.tooltip"))
        .setNameProvider(locale -> Component.literal(
            UltsLangManager.getInstance().languageName(locale)))
        .build();
    return field.track(entry);
  }

  private static AbstractConfigListEntry<?> itemVisibilityEntry(
      ConfigEntryBuilder entries,
      Field<UltsItemVisibility> field
  ) {
    AbstractConfigListEntry<UltsItemVisibility> entry = entries.startEnumSelector(
            Component.translatable(KEY + "item_visibility"),
            UltsItemVisibility.class,
            field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.general().itemVisibility())
        .setTooltip(Component.translatable(KEY + "item_visibility.tooltip"))
        .setEnumNameProvider(value -> Component.translatable(
            KEY + "item_visibility." + value.name().toLowerCase(Locale.ROOT)))
        .build();
    return field.track(entry);
  }

  private static AbstractConfigListEntry<?> storageModeEntry(
      ConfigEntryBuilder entries,
      Field<UltsStorageMode> field
  ) {
    AbstractConfigListEntry<UltsStorageMode> entry = entries.startEnumSelector(
            Component.translatable(KEY + "storage_mode"),
            UltsStorageMode.class,
            field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.general().storageMode())
        .setTooltip(Component.translatable(KEY + "storage_mode.tooltip"))
        .setEnumNameProvider(value -> Component.translatable(
            KEY + "storage_mode." + value.name().toLowerCase(Locale.ROOT)))
        .build();
    return field.track(entry);
  }

  /** One permission level covers binding, deleting, the highlight and reloading. */
  private static AbstractConfigListEntry<?> permissionEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntSlider(
            Component.translatable(KEY + "permission"), field.initial(), 0, 4)
        .setDefaultValue(UltsConfigData.DEFAULT.input().permissionLevel())
        .setTooltip(Component.translatable(KEY + "permission.tooltip"))
        .build();
    return field.track(entry);
  }

  private static AbstractConfigListEntry<?> countEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field,
      String key
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntField(
            Component.translatable(KEY + key), field.initial())
        .setDefaultValue(field.initial())
        .setMin(0)
        .setTooltip(Component.translatable(KEY + key + ".tooltip"))
        .build();
    return field.track(entry);
  }

  /** The drain interval is a tick count between 1 and 20. */
  private static AbstractConfigListEntry<?> drainEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntSlider(
            Component.translatable(KEY + "drain_interval"), field.initial(),
            UltsConfigData.MIN_DRAIN_INTERVAL, UltsConfigData.MAX_DRAIN_INTERVAL)
        .setDefaultValue(UltsConfigData.DEFAULT.input().drainInterval())
        .setTooltip(Component.translatable(KEY + "drain_interval.tooltip"))
        .build();
    return field.track(entry);
  }

  /** Automatic crafting is off, limited to shulker boxes, or open to every recipe. */
  private static AbstractConfigListEntry<?> craftingEntry(
      ConfigEntryBuilder entries,
      Field<UltsCraftingMode> field
  ) {
    AbstractConfigListEntry<UltsCraftingMode> entry = entries.startEnumSelector(
            Component.translatable(KEY + "crafting"),
            UltsCraftingMode.class,
            field.initial())
        .setDefaultValue(UltsConfigData.DEFAULT.input().crafting())
        .setTooltip(Component.translatable(KEY + "crafting.tooltip"))
        .setEnumNameProvider(value -> Component.translatable(
            KEY + "crafting." + value.name().toLowerCase(Locale.ROOT)))
        .build();
    return field.track(entry);
  }

  /**
   * Block ids whose directly connected blocks together form one large container.
   *
   * <p>The All-category copy only mirrors the list, so it hides the validation errors the tab copy
   * already reports.
   */
  private static AbstractConfigListEntry<?> multiBlockEntry(
      ConfigEntryBuilder entries,
      UltsBlockIdEditorModel model,
      boolean suppressErrors
  ) {
    return new UltsBlockIdListEntry(
        Component.translatable(KEY + "multi_block_containers"),
        model,
        entries.getResetButtonKey(),
        suppressErrors);
  }

  private static void save(UltsBlockIdEditorModel multiBlock, UltsConfigData data) {
    List<String> invalid = multiBlock.invalid();
    if (!invalid.isEmpty()) {
      UltsMod.LOGGER.error(
          "UltStorage configuration not saved, unknown block ids: {}", invalid);
      SystemToast.add(
          Minecraft.getInstance().gui.toastManager(),
          SAVE_RESULT,
          Component.translatable(KEY + "save_failed"),
          Component.literal(String.join(", ", invalid))
      );
      return;
    }
    boolean success = UltsConfigManager.getInstance().savePending(data);
    if (!success) {
      UltsMod.LOGGER.error("Failed to save Ults client configuration");
    }
    SystemToast.add(
        Minecraft.getInstance().gui.toastManager(),
        SAVE_RESULT,
        Component.translatable(KEY + (success ? "save_success" : "save_failed")),
        Component.translatable(KEY + (success ? "save_success.detail" : "save_failed.detail"))
    );
  }

  /**
   * Shared value of one setting across every copy of its entry. The entry that was changed away from
   * the loaded value wins, so editing the setting in any tab is applied exactly once.
   */
  private static final class Field<T> {

    private final T initial;
    private final List<AbstractConfigListEntry<T>> copies = new ArrayList<>();

    private Field(T initial) {
      this.initial = initial;
    }

    private T initial() {
      return initial;
    }

    private AbstractConfigListEntry<?> track(AbstractConfigListEntry<T> entry) {
      copies.add(entry);
      return entry;
    }

    private T resolve() {
      for (AbstractConfigListEntry<T> copy : copies) {
        T value = copy.getValue();
        if (!Objects.equals(value, initial)) {
          return value;
        }
      }
      return initial;
    }
  }
}
