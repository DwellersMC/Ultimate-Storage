package com.flwolfy.ults.data.state;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** A bounded batch either commits its outputs, proves a shortage, or waits without touching stock. */
public record UltsWithdrawalResult(List<ItemStack> outputs, boolean pending) {
  public UltsWithdrawalResult { outputs = List.copyOf(outputs); }
  public static final UltsWithdrawalResult WAIT = new UltsWithdrawalResult(List.of(), true);
  public static final UltsWithdrawalResult EMPTY = new UltsWithdrawalResult(List.of(), false);
}
