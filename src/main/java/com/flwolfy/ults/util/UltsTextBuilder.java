package com.flwolfy.ults.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public final class UltsTextBuilder {

  public static final ChatFormatting INFO = ChatFormatting.YELLOW;
  public static final ChatFormatting SUCCESS = ChatFormatting.GREEN;
  public static final ChatFormatting FAILURE = ChatFormatting.RED;

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
}
