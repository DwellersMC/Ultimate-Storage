package com.flwolfy.ults;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.function.Predicate;

/** Independent sliding quiet windows; only a changed quantity becomes pending. */
final class UltsStockStability<K> {
  private boolean observed;
  private Map<K, Long> contents = new HashMap<>();
  private boolean individual = true;
  private final Map<K, Long> changedAt = new HashMap<>();
  private long revision;

  boolean observe(Map<K, Long> current, long tick, long quietTicks) {
    if (observed && !contents.equals(current)) {
      var keys = new HashSet<>(contents.keySet());
      keys.addAll(current.keySet());
      for (K key : keys) if (!contents.getOrDefault(key, 0L).equals(current.getOrDefault(key, 0L)))
        changed(key, tick);
    }
    observed = true;
    contents = Map.copyOf(current);
    individual = false;
    settle(tick, quietTicks);
    return !changedAt.isEmpty();
  }

  /** Crafting answers arrive independently, so observing one must not remove other answers. */
  boolean observe(K key, long quantity, long tick, long quietTicks) {
    if (!individual) { contents = new HashMap<>(contents); individual = true; }
    Long before = contents.get(key);
    if (before != null && before != quantity) changed(key, tick);
    contents.put(key, quantity);
    settle(tick, quietTicks);
    return pending(key);
  }

  private void changed(K key, long tick) {
    if (!changedAt.containsKey(key)) revision++;
    changedAt.put(key, tick);
  }

  void settle(long tick, long quietTicks) {
    changedAt.entrySet().removeIf(entry -> {
      if (tick - entry.getValue() < quietTicks) return false;
      revision++;
      return true;
    });
  }

  boolean pending(K key) { return changedAt.containsKey(key); }
  boolean pending(Predicate<K> matches) { return changedAt.keySet().stream().anyMatch(matches); }
  boolean pending() { return !changedAt.isEmpty(); }
  long revision() { return revision; }
  void forget(K key) {
    if (!individual) { contents = new HashMap<>(contents); individual = true; }
    contents.remove(key);
    if (changedAt.remove(key) != null) revision++;
  }
  void clear() { observed = false; contents = new HashMap<>(); individual = true; changedAt.clear(); revision++; }
}
