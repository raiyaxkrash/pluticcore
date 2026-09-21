package net.pocketodds;

import net.minecraft.util.RandomSource;
import net.pocketodds.gambling.slot.SlotEvaluator;
import net.pocketodds.gambling.slot.SlotOutcome;
import net.pocketodds.gambling.slot.SlotSymbol;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Random;

public class RtpSimulationTest {

    private RandomSource createRandomSource(final Random random) {
        return new RandomSource() {
            @Override public RandomSource fork() { return this; }
            @Override public net.minecraft.world.level.levelgen.PositionalRandomFactory forkPositional() { return null; }
            @Override public void setSeed(long seed) { random.setSeed(seed); }
            @Override public int nextInt() { return random.nextInt(); }
            @Override public int nextInt(int bound) { return random.nextInt(bound); }
            @Override public long nextLong() { return random.nextLong(); }
            @Override public boolean nextBoolean() { return random.nextBoolean(); }
            @Override public float nextFloat() { return random.nextFloat(); }
            @Override public double nextDouble() { return random.nextDouble(); }
            @Override public double nextGaussian() { return random.nextGaussian(); }
        };
    }

    @Test
    public void simulateOneMillionSlotSpins() {
        final int SPINS = 1_000_000;
        final int BET_PER_SPIN = 8; // standard bet test
        final Random random = new Random(42);
        final RandomSource randomSource = createRandomSource(random);

        long totalBet = 0;
        long totalPayout = 0;
        long jackpotsHit = 0;
        long skullsHit = 0;
        long jokersHit = 0;
        long winsCount = 0;

        // Simulated jackpot pool: 5% contribution per bet
        long jackpotPool = 100;

        for (int i = 0; i < SPINS; i++) {
            totalBet += BET_PER_SPIN;
            jackpotPool += Math.max(1, (long) Math.round(BET_PER_SPIN * 0.05));

            SlotSymbol s0 = SlotSymbol.getRandomSymbol(randomSource, null);
            SlotSymbol s1 = SlotSymbol.getRandomSymbol(randomSource, null);
            SlotSymbol s2 = SlotSymbol.getRandomSymbol(randomSource, null);

            SlotOutcome outcome = SlotEvaluator.evaluate(new SlotSymbol[]{s0, s1, s2}, null);

            if (outcome.isJackpot()) {
                jackpotsHit++;
                // In actual game: base bet multiplier payout + full claimed jackpot pool
                int betPayout = (int) Math.round(BET_PER_SPIN * outcome.getMultiplier());
                long poolPayout = jackpotPool;
                jackpotPool = 100; // reset
                totalPayout += (betPayout + poolPayout);
                winsCount++;
            } else if (outcome.isSkulls()) {
                skullsHit++;
            } else if (outcome.isThreeJokers()) {
                jokersHit++;
                int betPayout = (int) Math.round(BET_PER_SPIN * outcome.getMultiplier());
                totalPayout += betPayout;
                winsCount++;
            } else if (outcome.isWin()) {
                // Exact integer payout math used in SlotRollSession:
                int betPayout = (int) Math.round(BET_PER_SPIN * outcome.getMultiplier());
                totalPayout += betPayout;
                winsCount++;
            }
        }

        double rtp = (totalPayout / (double) totalBet) * 100.0;
        System.out.println("=== POCKET ODDS SLOT 1,000,000 SPINS SIMULATION ===");
        System.out.println("Total Spins: " + SPINS);
        System.out.println("Total Bet: " + totalBet);
        System.out.println("Total Payout: " + totalPayout);
        System.out.printf("Calculated RTP: %.2f%%\n", rtp);
        System.out.printf("Win Rate: %.2f%%\n", (winsCount / (double) SPINS) * 100.0);
        System.out.println("Jackpots Hit: " + jackpotsHit);
        System.out.println("Disasters Hit: " + skullsHit);
        System.out.println("Three Jokers Hit: " + jokersHit);
        System.out.println("====================================================");

        Assertions.assertTrue(rtp < 100.0, "Slot RTP must be strictly under 100%! Got: " + rtp);
        Assertions.assertTrue(rtp > 75.0, "Slot RTP should be above 75%! Got: " + rtp);
    }

    @Test
    public void simulateOneMillionDiceRolls() {
        final int ROLLS = 1_000_000;
        final int BET = 1; // Testing bet 1 specifically to verify rounding
        final Random random = new Random(12345);

        long totalBet = 0;
        long totalPayout = 0;
        long snakeEyesCount = 0;
        long luckySevenCount = 0;
        long doublesCount = 0;

        for (int i = 0; i < ROLLS; i++) {
            totalBet += BET;
            int d1 = random.nextInt(6) + 1;
            int d2 = random.nextInt(6) + 1;
            int sum = d1 + d2;

            if (d1 == 1 && d2 == 1) {
                snakeEyesCount++;
            } else if (sum == 7) {
                luckySevenCount++;
                // Exact game logic: (int) Math.round(betCount * mult) with mult=3.0
                totalPayout += (int) Math.round(BET * 3.0);
            } else if (d1 == d2) {
                doublesCount++;
                // Exact game logic: (int) Math.round(betCount * mult) with mult=2.0
                totalPayout += (int) Math.round(BET * 2.0);
            } else if (sum == 11) {
                // Exact game logic: BET * 2
                totalPayout += BET * 2;
            }
        }

        double rtp = (totalPayout / (double) totalBet) * 100.0;
        System.out.println("=== POCKET ODDS VOID DICE 1,000,000 ROLLS SIMULATION (BET 1) ===");
        System.out.println("Total Rolls: " + ROLLS);
        System.out.println("Total Bet: " + totalBet);
        System.out.println("Total Payout: " + totalPayout);
        System.out.printf("Calculated Dice RTP: %.2f%%\n", rtp);
        System.out.println("Lucky Sevens (3.0x): " + luckySevenCount);
        System.out.println("Doubles (2.0x): " + doublesCount);
        System.out.println("Snake Eyes (1-1): " + snakeEyesCount);
        System.out.println("================================================================");

        Assertions.assertTrue(rtp < 100.0, "Dice RTP must be strictly under 100%! Got: " + rtp);
        Assertions.assertTrue(rtp > 80.0, "Dice RTP should be around 88.89%! Got: " + rtp);
    }

    /**
     * Симуляция 1,000,000 первых ходов в Колоде Судьбы (Deck of Fate).
     * Раздельно анализирует возврат фишек (RTP) и частоту вспомогательных карт.
     */
    @Test
    public void simulateOneMillionDeckOfFateFirstDraws() {
        final int RUNS = 1_000_000;
        final int START_BET = 1;
        final Random random = new Random(9876);

        long totalBet = 0;
        long totalCashedOutChips = 0;
        long busts = 0;
        long jokersAwarded = 0;
        long insuranceAwarded = 0;

        for (int i = 0; i < RUNS; i++) {
            totalBet += START_BET;
            int pot = START_BET;
            int roll = random.nextInt(100);

            if (roll < 35) {
                // Curse (busted)
                busts++;
                pot = 0;
            } else if (roll < 70) {
                // Neutral (Patience card): pot stays 1
            } else if (roll < 88) {
                // Fortune (1.25x): pot becomes 2
                pot = (pot <= 1) ? 2 : (int) Math.round(pot * 1.25);
            } else if (roll < 94) {
                // Riches (1.5x): pot becomes 2
                pot = (pot <= 1) ? 2 : (int) Math.round(pot * 1.5);
            } else if (roll < 97) {
                // Joker: pot + 1, item awarded
                pot += 1;
                jokersAwarded++;
            } else {
                // Guardian: pot + 1, item awarded
                pot += 1;
                insuranceAwarded++;
            }

            totalCashedOutChips += pot;
        }

        double chipRtp = (totalCashedOutChips / (double) totalBet) * 100.0;
        double jokerRate = (jokersAwarded / (double) RUNS) * 100.0;
        double insuranceRate = (insuranceAwarded / (double) RUNS) * 100.0;

        System.out.println("=== POCKET ODDS DECK OF FATE 1,000,000 FIRST DRAW SIMULATION ===");
        System.out.println("Total Runs: " + RUNS);
        System.out.println("Total Bet: " + totalBet);
        System.out.println("Direct Chip Return: " + totalCashedOutChips);
        System.out.printf("Direct Chip RTP: %.2f%%\n", chipRtp);
        System.out.printf("Joker Item Drop Rate: %.2f%% (Total: %d)\n", jokerRate, jokersAwarded);
        System.out.printf("Insurance Item Drop Rate: %.2f%% (Total: %d)\n", insuranceRate, insuranceAwarded);
        System.out.println("Busts: " + busts);
        System.out.println("===============================================================");

        Assertions.assertTrue(chipRtp < 100.0, "Deck first draw chip RTP must be under 100%! Got: " + chipRtp);
        Assertions.assertTrue(chipRtp > 80.0, "Deck first draw chip RTP should be around 95%! Got: " + chipRtp);
        Assertions.assertEquals(3.0, jokerRate, 0.2, "Joker drop rate must be ~3%");
        Assertions.assertEquals(3.0, insuranceRate, 0.2, "Insurance drop rate must be ~3%");
    }

    /**
     * Симуляция многошаговых стратегий в Колоде Судьбы на 1,000,000 раундов:
     * - Стратегия А: Кэшаут сразу при первом удвоении банка (pot >= 2)
     * - Стратегия Б: Кэшаут на серии 2 (streak >= 2)
     * - Стратегия В: Жадная стратегия до серии 3 (streak >= 3)
     */
    @Test
    public void simulateDeckOfFateMultiStepStrategies() {
        final int RUNS = 1_000_000;
        final Random random = new Random(54321);

        // Стратегия А: Кэшаут при pot >= 2
        long totalBetA = 0;
        long totalCashedA = 0;
        long jokersA = 0;
        long insuranceA = 0;

        for (int i = 0; i < RUNS; i++) {
            totalBetA += 1;
            int pot = 1;
            boolean inRound = true;

            while (inRound) {
                int roll = random.nextInt(100);
                if (roll < 35) {
                    // Curse
                    pot = 0;
                    inRound = false;
                } else if (roll < 70) {
                    // Patience (pot stays 1, push luck again)
                } else if (roll < 88) {
                    // Fortune: pot = 2 -> Cash out!
                    pot = 2;
                    inRound = false;
                } else if (roll < 94) {
                    // Riches: pot = 2 -> Cash out!
                    pot = 2;
                    inRound = false;
                } else if (roll < 97) {
                    // Joker: pot = 2 + item -> Cash out!
                    pot = 2;
                    jokersA++;
                    inRound = false;
                } else {
                    // Insurance: pot = 2 + item -> Cash out!
                    pot = 2;
                    insuranceA++;
                    inRound = false;
                }
            }
            totalCashedA += pot;
        }

        double rtpA = (totalCashedA / (double) totalBetA) * 100.0;
        System.out.println("=== DECK OF FATE MULTI-STEP: CASHOUT AT POT >= 2 ===");
        System.out.printf("Direct Chip RTP: %.2f%%\n", rtpA);
        System.out.printf("Joker items: %d, Insurance items: %d\n", jokersA, insuranceA);
        Assertions.assertTrue(rtpA < 100.0, "Multi-step strategy RTP must stay under 100%! Got: " + rtpA);
    }
}
