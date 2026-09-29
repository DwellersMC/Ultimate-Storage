package com.flwolfy.ults.display;

import com.flwolfy.ults.data.lang.UltsLangManager;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Shared text styling for every Ults screen: the body of a line is bright yellow, values are green
 * and values that are empty or would fail the action are red.
 */
final class UltsGuiText {

  /** Where a pattern expects what it is to be filled with. */
  private static final String PLACEHOLDER = "%s";
  /** How a stored stamp is written on a special row, in the time zone of the server. */
  private static final DateTimeFormatter STAMP_FORMAT = DateTimeFormatter
      .ofPattern("yyyy-MM-dd HH:mm")
      .withZone(ZoneId.systemDefault());

  private UltsGuiText() {}

  static Component text(String key, Object... arguments) {
    return UltsLangManager.getInstance().text(key, arguments);
  }

  /**
   * A line with its one placeholder filled by a component rather than by words.
   *
   * <p>An item's name is not text the server knows: it is a component the client translates into whatever
   * language that client is set to. A line that names an item therefore cannot be built by formatting a
   * string — a component printed into one comes out as its own source rather than as a name — so the
   * pattern is split at its placeholder and the component is put between the two halves, left for the
   * client to draw in its own words.
   *
   * @param key the pattern, which holds one {@code %s}
   * @param part what goes in its place
   * @return the line, ready to be drawn
   */
  static MutableComponent text(String key, Component part) {
    String pattern = text(key).getString();
    int at = pattern.indexOf(PLACEHOLDER);
    if (at < 0) {
      return Component.literal(pattern);
    }
    return Component.literal(pattern.substring(0, at))
        .append(part)
        .append(Component.literal(pattern.substring(at + PLACEHOLDER.length())));
  }

  static String format(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }

  static MutableComponent label(String key) {
    return text(key).copy().withStyle(ChatFormatting.YELLOW);
  }

  static Component labelled(String labelKey, String value, boolean alert) {
    return label(labelKey).append(Component.literal(value)
        .withStyle(alert ? ChatFormatting.RED : ChatFormatting.GREEN));
  }

  static Component labelled(String labelKey, long value, boolean alert) {
    return labelled(labelKey, format(value), alert);
  }

  static Component labelled(String labelKey, String suffixKey, long value, boolean alert) {
    return label(labelKey)
        .append(Component.literal(format(value))
            .withStyle(alert ? ChatFormatting.RED : ChatFormatting.GREEN))
        .append(label(suffixKey));
  }

  static Component boxes(long boxes, long items) {
    return label("ults.gui.amount.boxes")
        .append(Component.literal(format(boxes)).withStyle(ChatFormatting.GREEN))
        .append(label("ults.gui.amount.boxes.between"))
        .append(Component.literal(format(items)).withStyle(ChatFormatting.GREEN))
        .append(label("ults.gui.amount.boxes.suffix"));
  }

  /**
   * A stored stamp as it is shown on a special row, or the "not recorded" text for a save that was
   * written before the stamps existed.
   *
   * @param millis when a stack was stored, or {@code 0} when that is not known
   * @return the text a row shows
   */
  static String stamp(long millis) {
    if (millis <= 0L) {
      return text("ults.gui.special.updated.unknown").getString();
    }
    return STAMP_FORMAT.format(Instant.ofEpochMilli(millis));
  }
}
