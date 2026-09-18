package com.flwolfy.ults.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

class UltsTextBuilderTest {

  @Test
  void appliesSemanticMessageColors() {
    Component text = Component.literal("message");
    // Every message body is bright yellow; only failures stand out in red.
    assertEquals(
        TextColor.fromLegacyFormat(ChatFormatting.YELLOW),
        UltsTextBuilder.success(text).getStyle().getColor()
    );
    assertEquals(
        TextColor.fromLegacyFormat(ChatFormatting.YELLOW),
        UltsTextBuilder.info(text).getStyle().getColor()
    );
    assertEquals(
        TextColor.fromLegacyFormat(ChatFormatting.RED),
        UltsTextBuilder.failure(text).getStyle().getColor()
    );
    // The palette itself: yellow body, green values, red failures.
    assertEquals(ChatFormatting.YELLOW, UltsTextBuilder.TEXT);
    assertEquals(ChatFormatting.YELLOW, UltsTextBuilder.SUCCESS);
    assertEquals(ChatFormatting.GREEN, UltsTextBuilder.HIGHLIGHT);
    assertEquals(ChatFormatting.RED, UltsTextBuilder.FAILURE);
  }

  @Test
  void formatsEveryPlaceholderWithAColour() {
    Component styled = Component.literal("备注").withStyle(ChatFormatting.AQUA);
    MutableComponent formatted = UltsTextBuilder.format(
        Component.literal("a %s b %s"), ChatFormatting.YELLOW, ChatFormatting.GREEN, styled, 7);
    List<Component> parts = formatted.getSiblings();
    // No part of a formatted message may be left unstyled white.
    for (Component part : parts) {
      assertNotNull(part.getStyle().getColor(), "unstyled part: " + part.getString());
    }
    assertEquals(TextColor.fromLegacyFormat(ChatFormatting.YELLOW),
        parts.getFirst().getStyle().getColor());
    // A value that brought its own colour keeps it, everything else uses the highlight colour.
    assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA),
        parts.get(1).getStyle().getColor());
    assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN),
        parts.get(3).getStyle().getColor());
  }
}
