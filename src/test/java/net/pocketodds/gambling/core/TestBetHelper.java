package net.pocketodds.gambling.core;

import net.pocketodds.data.JackpotSavedData;

/**
 * Test helper in net.pocketodds.gambling.core package to expose the package-private
 * applyDebitTransition method solely to unit tests. Not packaged into production JAR.
 */
public final class TestBetHelper {
    private TestBetHelper() {}

    public static boolean applyDebitTransition(BetPreparation prep, JackpotSavedData jackpotData) {
        return RewardTransactionService.applyDebitTransition(prep, jackpotData);
    }
}