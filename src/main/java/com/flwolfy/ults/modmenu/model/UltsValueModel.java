package com.flwolfy.ults.modmenu.model;

import java.util.Objects;

/** One current scalar value shared by every independent configuration widget for that setting. */
public final class UltsValueModel<T> {
  private final T initial;
  private T value;

  public UltsValueModel(T initial) { this.initial = initial; this.value = initial; }
  public T value() { return value; }
  public void publish(T value) { this.value = value; }
  public boolean edited() { return !Objects.equals(initial, value); }
}
