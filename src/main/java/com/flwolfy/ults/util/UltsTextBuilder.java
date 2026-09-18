package com.flwolfy.ults.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent.RunCommand;
import net.minecraft.network.chat.ClickEvent.SuggestCommand;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent.ShowText;
import net.minecraft.network.chat.MutableComponent;

public final class UltsTextBuilder {

  /**
   * The chat palette: the body of every message is bright yellow, green marks the important values
   * (positions, numbers, notes) and red marks anything that failed. Grey is only used for inactive
   * or placeholder text, never for the body.
   */
  public static final ChatFormatting INFO = ChatFormatting.YELLOW;
  public static final ChatFormatting SUCCESS = ChatFormatting.YELLOW;
  public static final ChatFormatting FAILURE = ChatFormatting.RED;

  public static final ChatFormatting TEXT = ChatFormatting.YELLOW;
  public static final ChatFormatting HIGHLIGHT = ChatFormatting.GREEN;
  public static final ChatFormatting SHADE = ChatFormatting.DARK_GRAY;

  private UltsTextBuilder() {}

  public static Component info(Component text) {
    return text.copy().withStyle(INFO);
  }

  public static Component success(Component text) {
    return text.copy().withStyle(SUCCESS);
  }

  public static Component failure(Component text) {
    return text.copy().withStyle(FAILURE);
  }

  /**
   * Fills every <code>%s</code> of a template with the matching argument, painting the template text
   * in {@link #TEXT} and the substituted values in {@link #HIGHLIGHT}.
   */
  public static MutableComponent format(Component template, Object... arguments) {
    return format(template, TEXT, HIGHLIGHT, arguments);
  }

  /**
   * Fills every <code>%s</code> of a template with the matching argument, using the given colors for
   * the template text and for the substituted values. A component argument that already carries a
   * colour of its own keeps it, so a message can mix several colours.
   */
  public static MutableComponent format(
      Component template,
      ChatFormatting textColor,
      ChatFormatting highlightColor,
      Object... arguments
  ) {
    String[] parts = template.getString().split("%s", -1);
    MutableComponent result = Component.literal("").withStyle(textColor);
    for (int index = 0; index < parts.length; index++) {
      result.append(Component.literal(parts[index]).withStyle(textColor));
      if (index >= arguments.length) {
        continue;
      }
      Object argument = arguments[index];
      if (argument instanceof Component component) {
        result.append(component.getStyle().getColor() == null
            ? component.copy().withStyle(highlightColor)
            : component.copy());
      } else {
        result.append(Component.literal(String.valueOf(argument)).withStyle(highlightColor));
      }
    }
    return result;
  }

  /** Makes the component run a command when clicked, keeping its own style. */
  public static Component commandText(Component text, Component hoverText, String clickCommand) {
    return text.copy().setStyle(
        text.getStyle()
            .withClickEvent(new RunCommand(clickCommand))
            .withHoverEvent(new ShowText(hoverText))
    );
  }

  /** Makes the component fill the chat box with a command when clicked, keeping its own style. */
  public static Component suggestCommandText(
      Component text,
      Component hoverText,
      String clickCommand
  ) {
    return text.copy().setStyle(
        text.getStyle()
            .withClickEvent(new SuggestCommand(clickCommand))
            .withHoverEvent(new ShowText(hoverText))
    );
  }
}
