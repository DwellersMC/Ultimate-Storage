package com.flwolfy.ults.display;

import com.flwolfy.ults.data.lang.UltsLangManager;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Shared text styling for every Ults screen: the body of a line is bright yellow, values are green
 * and values that are empty or would fail the action are red.
 */
final class UltsGuiText {

  private UltsGuiText() {}

  static Component text(String key, Object... arguments) {
    return UltsLangManager.getInstance().text(key, arguments);
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
}
