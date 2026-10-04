package com.flwolfy.ults.data.state;

/** Feasibility of a whole request without allocating its output stacks or consuming live stock. */
public record UltsWithdrawalAssessment(boolean available, boolean pending) {
  public static final UltsWithdrawalAssessment AVAILABLE = new UltsWithdrawalAssessment(true, false);
  public static final UltsWithdrawalAssessment SHORTAGE = new UltsWithdrawalAssessment(false, false);
  public static final UltsWithdrawalAssessment WAIT = new UltsWithdrawalAssessment(false, true);
}
