package com.flwolfy.ults.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

class UltsTextBuilderTest {

  @Test
  void appliesSemanticMessageColors() {
    Component text = Component.literal("message");
    assertEquals(
        TextColor.fromLegacyFormat(ChatFormatting.GREEN),
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
  }
}
