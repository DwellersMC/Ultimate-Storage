package com.flwolfy.ults.modmenu;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.data.config.UltsConfigData;
import com.flwolfy.ults.data.config.UltsConfigManager;
import com.flwolfy.ults.data.config.UltsItemVisibility;
import com.flwolfy.ults.data.config.UltsStorageMode;
import com.flwolfy.ults.data.lang.UltsLangManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.gui.entries.SubCategoryListEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    Field<Integer> bindPermission = new Field<>(current.input().bindPermissionLevel());
    Field<Integer> deletePermission = new Field<>(current.input().deletePermissionLevel());
    Field<Integer> maxBindings = new Field<>(current.input().maxBindings());
    Field<Integer> drainInterval = new Field<>(current.input().drainInterval());

    // Every section is shown twice: once in its own tab and once inside the "all" overview, so each
    // copy reports back into the shared field and the changed copy wins when the screen is saved.
    List<AbstractConfigListEntry<?>> generalEntries = List.of(
        languageEntry(entries, language),
        itemVisibilityEntry(entries, itemVisibility),
        storageModeEntry(entries, storageMode)
    );
    List<AbstractConfigListEntry<?>> inputEntries = List.of(
        permissionEntry(entries, bindPermission, "bind_permission"),
        permissionEntry(entries, deletePermission, "delete_permission"),
        countEntry(entries, maxBindings, "max_bindings"),
        drainEntry(entries, drainInterval)
    );
    List<AbstractConfigListEntry<?>> generalOverview = List.of(
        languageEntry(entries, language),
        itemVisibilityEntry(entries, itemVisibility),
        storageModeEntry(entries, storageMode)
    );
    List<AbstractConfigListEntry<?>> inputOverview = List.of(
        permissionEntry(entries, bindPermission, "bind_permission"),
        permissionEntry(entries, deletePermission, "delete_permission"),
        countEntry(entries, maxBindings, "max_bindings"),
        drainEntry(entries, drainInterval)
    );

    generalEntries.forEach(general::addEntry);
    inputEntries.forEach(input::addEntry);
    all.addEntry(subCategory("general", generalOverview));
    all.addEntry(subCategory("input", inputOverview));

    builder.setSavingRunnable(() -> save(new UltsConfigData(
        new UltsConfigData.General(
            language.resolve(), itemVisibility.resolve(), storageMode.resolve()),
        new UltsConfigData.Input(
            bindPermission.resolve(),
            deletePermission.resolve(),
            maxBindings.resolve(),
            drainInterval.resolve()
        )
    )));
    return builder.build();
  }

  private static AbstractConfigListEntry<?> subCategory(
      String key,
      List<AbstractConfigListEntry<?>> children
  ) {
    return new UltsSectionEntry(
        Component.translatable(KEY + key).withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD),
        children,
        true);
  }

  /**
   * Section header of the "all" overview.
   *
   * <p>Cloth paints sub-category headers through its own edit-aware colours and its label colour hook
   * is final, so the header is drawn here instead: the built-in label is blanked out and the title is
   * rendered bold yellow at all times.
   */
  private static final class UltsSectionEntry extends SubCategoryListEntry {

    private static final int SECTION_COLOR = 0xFFFFFF55;

    private final Component header;

    @SuppressWarnings({"rawtypes", "unchecked"})
    private UltsSectionEntry(
        Component title,
        List<AbstractConfigListEntry<?>> children,
        boolean expanded
    ) {
      super(title, (List) children, expanded);
      header = title;
    }

    @Override
    public Component getDisplayedFieldName() {
      return Component.empty();
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
      super.extractRenderState(
          graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, hovered, delta);
      graphics.text(Minecraft.getInstance().font, header, x, y + 6, SECTION_COLOR);
    }
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

  private static AbstractConfigListEntry<?> permissionEntry(
      ConfigEntryBuilder entries,
      Field<Integer> field,
      String key
  ) {
    AbstractConfigListEntry<Integer> entry = entries.startIntSlider(
            Component.translatable(KEY + key), field.initial(), 0, 4)
        .setDefaultValue(field.initial())
        .setTooltip(Component.translatable(KEY + key + ".tooltip"))
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

  private static void save(UltsConfigData data) {
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
