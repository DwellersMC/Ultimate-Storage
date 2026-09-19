package com.flwolfy.ults.crafting;

/** The arithmetic of automatic crafting, saturating instead of ever overflowing. */
public final class UltsCraftMath {

  private UltsCraftMath() {}

  /** {@code value / divisor} rounded up, without overflowing on large inputs. */
  public static long divideRoundingUp(long value, long divisor) {
    if (value <= 0 || divisor <= 0) {
      return 0L;
    }
    return value / divisor + (value % divisor == 0 ? 0L : 1L);
  }

  /** An addition that saturates at {@link Long#MAX_VALUE}. */
  public static long add(long first, long second) {
    long sum = first + second;
    return sum < 0L ? Long.MAX_VALUE : sum;
  }

  /** A multiplication that saturates at {@link Long#MAX_VALUE}. */
  public static long multiply(long first, long second) {
    if (first <= 0L || second <= 0L) {
      return 0L;
    }
    if (first > Long.MAX_VALUE / second) {
      return Long.MAX_VALUE;
    }
    return first * second;
  }
}
