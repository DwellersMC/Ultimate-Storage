package com.flwolfy.ults.data.config;

/** Changes active test configuration without touching any game configuration file. */
public final class UltsTestConfig implements AutoCloseable {
  private final java.lang.reflect.Field field;
  private final UltsConfigData previous;

  public UltsTestConfig(UltsConfigData config) throws ReflectiveOperationException {
    field = UltsConfigManager.class.getDeclaredField("data");
    field.setAccessible(true);
    previous = UltsConfigManager.getInstance().data();
    field.set(UltsConfigManager.getInstance(), config);
  }

  @Override public void close() throws IllegalAccessException {
    field.set(UltsConfigManager.getInstance(), previous);
  }
}
