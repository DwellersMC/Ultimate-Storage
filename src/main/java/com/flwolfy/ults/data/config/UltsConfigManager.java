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

  /** The configuration file sits directly in the config directory, like the other mods of this project. */
  private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
      .resolve("ults.json");
  private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
  private static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
  private static final UltsConfigManager INSTANCE = new UltsConfigManager();
  private volatile UltsConfigData data;

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

  public UltsConfigData loadForEditing() {
    LOCK.writeLock().lock();
    try {
      return readAndNormalize();
    } catch (Exception exception) {
      UltsMod.LOGGER.error("Failed to load Ults config for editing", exception);
      return data;
    } finally {
      LOCK.writeLock().unlock();
    }
  }

  public boolean reload() {
    LOCK.writeLock().lock();
    try {
      UltsConfigData loaded = readAndNormalize();
      data = loaded;
      UltsLangManager.getInstance().setLanguage(loaded.general().language());
      return true;
    } catch (Exception exception) {
      UltsMod.LOGGER.error("Failed to reload Ults config; active values were preserved", exception);
      return false;
    } finally {
      LOCK.writeLock().unlock();
    }
  }

  public boolean update(UltsConfigData replacement) {
    if (replacement == null || !replacement.validate().isEmpty()) {
      return false;
    }
    replacement = replacement.canonicalize();
    LOCK.writeLock().lock();
    try {
      save(replacement);
      data = replacement;
      UltsLangManager.getInstance().setLanguage(replacement.general().language());
      return true;
    } catch (Exception exception) {
      UltsMod.LOGGER.error("Failed to update Ults config", exception);
      return false;
    } finally {
      LOCK.writeLock().unlock();
    }
  }

  public boolean savePending(UltsConfigData replacement) {
    if (replacement == null || !replacement.validate().isEmpty()) {
      return false;
    }
    LOCK.writeLock().lock();
    try {
      save(replacement.canonicalize());
      return true;
    } catch (Exception exception) {
      UltsMod.LOGGER.error("Failed to save pending Ults config", exception);
      return false;
    } finally {
      LOCK.writeLock().unlock();
    }
  }

  private static UltsConfigData loadAtStartup() {
    try {
      return readAndNormalize();
    } catch (Exception exception) {
      UltsMod.LOGGER.error("Failed to load Ults config; using defaults", exception);
      try {
        save(UltsConfigData.DEFAULT);
      } catch (Exception saveException) {
        exception.addSuppressed(saveException);
      }
      return UltsConfigData.DEFAULT;
    }
  }

  private static UltsConfigData readAndNormalize() throws Exception {
    if (Files.notExists(CONFIG_PATH)) {
      save(UltsConfigData.DEFAULT);
      return UltsConfigData.DEFAULT;
    }
    JsonObject root;
    try (Reader reader = Files.newBufferedReader(CONFIG_PATH, StandardCharsets.UTF_8)) {
      JsonElement parsed = GSON.fromJson(reader, JsonElement.class);
      if (parsed == null || !parsed.isJsonObject()) {
        throw new IllegalArgumentException("The root configuration value must be an object");
      }
      root = parsed.getAsJsonObject();
    }
    mergeDefaults(root, GSON.toJsonTree(UltsConfigData.DEFAULT).getAsJsonObject());
    dropRetiredVisibility(root);
    dropRetiredCrafting(root);
    UltsConfigData loaded = GSON.fromJson(root, UltsConfigData.class);
    if (loaded == null || !loaded.validate().isEmpty()) {
      throw new IllegalArgumentException("Invalid Ults config fields: "
          + (loaded == null ? java.util.List.of("root") : loaded.validate()));
    }
    loaded = loaded.canonicalize();
    save(loaded);
    return loaded;
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
      UltsItemVisibility.valueOf(value.getAsString());
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
      UltsCraftingMode.valueOf(value.getAsString());
    } catch (IllegalArgumentException retired) {
      String fallback = UltsConfigData.DEFAULT.input().crafting().name();
      object.addProperty("crafting", fallback);
      UltsMod.LOGGER.warn("UltStorage: unknown crafting '{}' was replaced by '{}'",
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
    Files.createDirectories(CONFIG_PATH.getParent());
    Path temporary = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
    try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
      GSON.toJson(value, writer);
    }
    try {
      Files.move(temporary, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
