package com.flwolfy.ults.display;

/** Constant-space measurement of an explicit request; stacks are created only by its stream. */
record UltsQuantityPreview(long requested, long selected, long pack, long ground, long left) {
  static long parse(String value) {
    if (value == null) return 0L;
    try { return Math.max(0L, Long.parseLong(value.trim())); }
    catch (NumberFormatException ignored) { return 0L; }
  }

  static UltsQuantityPreview of(long requested, long available, boolean pending,
      long room, boolean overflow) {
    requested = Math.max(0L, requested);
    long reachable = pending || requested > Math.max(0L, available) ? 0L : requested;
    long selected = overflow ? reachable : Math.min(reachable, Math.max(0L, room));
    long pack = Math.min(selected, Math.max(0L, room));
    return new UltsQuantityPreview(requested, selected, pack, selected - pack, requested - selected);
  }
}
