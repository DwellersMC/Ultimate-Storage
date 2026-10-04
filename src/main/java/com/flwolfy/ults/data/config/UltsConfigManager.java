package com.flwolfy.ults.data.config;

import com.flwolfy.ults.UltsMod;
import com.flwolfy.ults.data.lang.UltsLangManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.fabricmc.loader.api.FabricLoader;

public final class UltsConfigManager {

  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
  /**
   * Held while the configuration file is read or written, and never while a value is handed out: two
   * writers cannot interleave their file work, and a reader never waits for a disk.
   */
  private static final Object FILE_LOCK = new Object();
  private static final UltsConfigManager INSTANCE = new UltsConfigManager();
  private volatile UltsConfigData data;

  /**
   * The configuration file sits directly in the config directory, like the other mods of this project.
   *
   * <p>The directory is asked for every time rather than once while the class loads, so a process
   * without a game directory — a test run — gets the defaults through the ordinary error path instead
   * of failing to load the class at all.
   */
  private static Path configPath() {
    return FabricLoader.getInstance().getConfigDir().resolve("ults.json");
  }

  private UltsConfigManager() {
    data = loadAtStartup();
    UltsLangManager.getInstance().setLanguage(data.general().language());
  }

  public static UltsConfigManager getInstance() {
    return INSTANCE;
  }

  public UltsConfigData data() {
    LOCK.readLock().lock();
    try {
      return data;
    } finally {
      LOCK.readLock().unlock();
    }
  }

  /**
   * Reads the file for the editor, publishing nothing.
   *
   * <p>Reading is file work, and it happens under the file lock only, never under the lock the server
   * thread reads its values through: that lock is taken for every stack the storage compares, so holding
   * it across a disk read would stall a tick for as long as the disk takes.
   */
  public UltsConfigData loadForEditing() {
    synchronized (FILE_LOCK) {
      try {
        return readAndNormalize();
      } catch (Exception exception) {
        UltsMod.LOGGER.error("Failed to load Ults config for editing", exception);
        return data();
      }
    }
  }

  public boolean reload() {
    UltsConfigData loaded;
    synchronized (FILE_LOCK) {
      try {
        loaded = readAndNormalize();
      } catch (Exception exception) {
        UltsMod.LOGGER.error("Failed to reload Ults config; active values were preserved", exception);
        return false;
      }
    }
    publishConfig(loaded);
    return true;
  }

  public boolean update(UltsConfigData replacement) {
    if (replacement == null || !replacement.validate().isEmpty()) {
      return false;
    }
    UltsConfigData write = replacement.canonicalize();
    synchronized (FILE_LOCK) {
      try {
        save(write);
      } catch (Exception exception) {
        UltsMod.LOGGER.error("Failed to update Ults config", exception);
        return false;
      }
    }
    publishConfig(write);
    return true;
  }

  /** Makes a freshly read configuration the active one. The lock is held for the swap and nothing else. */
  private void publishConfig(UltsConfigData loaded) {
    LOCK.writeLock().lock();
    try {
      data = loaded;
      UltsLangManager.getInstance().setLanguage(loaded.general().language());
    } finally {
      LOCK.writeLock().unlock();
    }
  }

  public boolean savePending(UltsConfigData replacement) {
    if (replacement == null || !replacement.validate().isEmpty()) {
      return false;
    }
    synchronized (FILE_LOCK) {
      try {
        save(replacement.canonicalize());
        return true;
      } catch (Exception exception) {
        UltsMod.LOGGER.error("Failed to save pending Ults config", exception);
        return false;
      }
    }
  }

  private static UltsConfigData loadAtStartup() {
    try {
      return readAndNormalize();
    } catch (Exception exception) {
      // The file is left alone. It is the only copy of the settings somebody wrote, and a single value the
      // mod cannot read — a typo, a value from a version that has moved on — is no reason to write the
      // defaults over everything else. The defaults are used for this run, and the log carries the reason.
      // The path is deliberately not touched here: finding it can fail on its own, and a failure to report
      // a failure would be the one thing worse than the failure.
      UltsMod.LOGGER.error(
          "Failed to read the Ults config; using defaults for this run and leaving the file as it is",
          exception);
      return UltsConfigData.DEFAULT;
    }
  }

  private static UltsConfigData readAndNormalize() throws Exception {
    Path path = configPath();
    if (Files.notExists(path)) {
      save(UltsConfigData.DEFAULT);
      return UltsConfigData.DEFAULT;
    }
    JsonObject root;
    try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      JsonElement parsed = GSON.fromJson(reader, JsonElement.class);
      if (parsed == null || !parsed.isJsonObject()) {
        throw new IllegalArgumentException("The root configuration value must be an object");
      }
      root = parsed.getAsJsonObject();
    }
    UltsConfigData loaded = decode(root, UltsLangManager.getInstance().availableLocales());
    save(loaded);
    return loaded;
  }

  /** The file and tests share migration, default merging and validation, without publishing values. */
  static UltsConfigData decode(JsonObject root, java.util.Set<String> availableLocales) {
    // Rename before merging defaults, otherwise a legacy custom value would be hidden by a default.
    migrateWithdrawalFields(root);
    mergeDefaults(root, GSON.toJsonTree(UltsConfigData.DEFAULT).getAsJsonObject());
    dropRetiredVisibility(root);
    dropRetiredCrafting(root);
    dropRetiredFilterMode(root);
    dropRetiredSpecialFields(root);
    dropRetiredStackByData(root);
    UltsConfigData loaded = GSON.fromJson(root, UltsConfigData.class);
    if (loaded == null || !loaded.validate(availableLocales).isEmpty()) {
      throw new IllegalArgumentException("Invalid Ults config fields: "
          + (loaded == null ? java.util.List.of("root") : loaded.validate(availableLocales)));
    }
    return loaded.canonicalize();
  }

  private static void migrateWithdrawalFields(JsonObject root) {
    JsonElement input = root.get("input");
    if (input == null || !input.isJsonObject()) return;
    JsonObject object = input.getAsJsonObject();
    rename(object, "allowTakeAll", "allowBulkWithdrawal");
    rename(object, "takeAllStacks", "bulkWithdrawalStacks");
    rename(object, "takeAllRate", "withdrawalRate");
  }

  /** An explicitly configured new field wins; absent or null fields may inherit their old value. */
  private static void rename(JsonObject object, String previous, String current) {
    JsonElement legacy = object.remove(previous);
    JsonElement configured = object.get(current);
    if (legacy != null && (configured == null || configured.isJsonNull())) object.add(current, legacy);
  }

  /**
   * The equipment switch and the old special cap are gone, and the value an old file holds does not
   * mean the same thing any more.
   *
   * <p>{@code filterEquipment} named equipment the filter took out on its own; filtering is by the
   * item ids in {@code filters} alone now, so an existing value is dropped rather than carried over:
   * a server that wants that gear gone names it. {@code maxEntries} counted the stacks the whole
   * category kept, while {@code bundleSlots} counts the stacks one bag holds, so the old number is
   * dropped and the new default put in its place.
   */
  private static void dropRetiredSpecialFields(JsonObject root) {
    JsonElement special = root.get("special");
    if (special == null || !special.isJsonObject()) {
      return;
    }
    JsonObject object = special.getAsJsonObject();
    boolean dropped = object.remove("filterEquipment") != null;
    dropped |= object.remove("filterLootEquipment") != null;
    if (object.remove("maxEntries") != null) {
      dropped = true;
      object.addProperty("bundleSlots", UltsConfigData.DEFAULT.special().bundleSlots());
    }
    if (dropped) {
      UltsMod.LOGGER.info(
          "UltStorage: the equipment switch and the old special cap were dropped; the filter names "
              + "{} item id(s) and a bag holds {} stack(s)",
          UltsConfigData.DEFAULT.special().filters().size(),
          UltsConfigData.DEFAULT.special().bundleSlots());
    }
  }

  /**
   * The switch that decided whether identical data meant one kind of thing is gone.
   *
   * <p>It is how the storage works now and not something to turn off, so an existing value is dropped
   * rather than carried over: what it used to turn off was the way the mod behaved before it.
   */
  private static void dropRetiredStackByData(JsonObject root) {
    JsonElement special = root.get("special");
    if (special == null || !special.isJsonObject()) {
      return;
    }
    if (special.getAsJsonObject().remove("stackByData") != null) {
      UltsMod.LOGGER.info("UltStorage: the stackByData switch was dropped; identical data always "
          + "means one kind of thing now");
    }
  }

  /** A visibility mode that is not part of the mod any more falls back to the default one. */
  private static void dropRetiredVisibility(JsonObject root) {
    JsonElement general = root.get("general");
    if (general == null || !general.isJsonObject()) {
      return;
    }
    JsonObject object = general.getAsJsonObject();
    JsonElement value = object.get("itemVisibility");
    if (value == null || !value.isJsonPrimitive()) {
      return;
    }
    try {
      // Read without regard to case, then written back in the one spelling the file uses: the value may
      // have been typed by hand, and what this reader accepts has to be what the file then holds.
      String canonical = UltsItemVisibility.valueOf(
          value.getAsString().toUpperCase(java.util.Locale.ROOT)).name();
      if (!canonical.equals(value.getAsString())) {
        object.addProperty("itemVisibility", canonical);
      }
    } catch (IllegalArgumentException retired) {
      String fallback = UltsConfigData.DEFAULT.general().itemVisibility().name();
      object.addProperty("itemVisibility", fallback);
      UltsMod.LOGGER.warn("UltStorage: unknown itemVisibility '{}' was replaced by '{}'",
          value.getAsString(), fallback);
    }
  }

  /** A crafting mode that is not part of the mod any more falls back to the default one. */
  private static void dropRetiredCrafting(JsonObject root) {
    JsonElement input = root.get("input");
    if (input == null || !input.isJsonObject()) {
      return;
    }
    JsonObject object = input.getAsJsonObject();
    JsonElement value = object.get("crafting");
    if (value == null || !value.isJsonPrimitive()) {
      return;
    }
    try {
      String canonical = UltsCraftingMode.valueOf(
          value.getAsString().toUpperCase(java.util.Locale.ROOT)).name();
      if (!canonical.equals(value.getAsString())) {
        object.addProperty("crafting", canonical);
      }
    } catch (IllegalArgumentException retired) {
      String fallback = UltsConfigData.DEFAULT.input().crafting().name();
      object.addProperty("crafting", fallback);
      UltsMod.LOGGER.warn("UltStorage: unknown crafting '{}' was replaced by '{}'",
          value.getAsString(), fallback);
    }
  }

  /** A special filter mode that is not part of the mod any more falls back to the default one. */
  private static void dropRetiredFilterMode(JsonObject root) {
    JsonElement special = root.get("special");
    if (special == null || !special.isJsonObject()) {
      return;
    }
    JsonObject object = special.getAsJsonObject();
    JsonElement value = object.get("filterMode");
    if (value == null || !value.isJsonPrimitive()) {
      return;
    }
    try {
      String canonical = UltsSpecialFilter.valueOf(
          value.getAsString().toUpperCase(java.util.Locale.ROOT)).name();
      if (!canonical.equals(value.getAsString())) {
        object.addProperty("filterMode", canonical);
      }
    } catch (IllegalArgumentException retired) {
      String fallback = UltsConfigData.DEFAULT.special().filterMode().name();
      object.addProperty("filterMode", fallback);
      UltsMod.LOGGER.warn("UltStorage: unknown filterMode '{}' was replaced by '{}'",
          value.getAsString(), fallback);
    }
  }

  private static void mergeDefaults(JsonObject target, JsonObject defaults) {
    defaults.entrySet().forEach(entry -> {
      JsonElement current = target.get(entry.getKey());
      if (current == null || current.isJsonNull()) {
        target.add(entry.getKey(), entry.getValue().deepCopy());
      } else if (current.isJsonObject() && entry.getValue().isJsonObject()) {
        mergeDefaults(current.getAsJsonObject(), entry.getValue().getAsJsonObject());
      }
    });
  }

  private static void save(UltsConfigData value) throws Exception {
    Path path = configPath();
    Files.createDirectories(path.getParent());
    Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
    try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
      GSON.toJson(value, writer);
    }
    try {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
