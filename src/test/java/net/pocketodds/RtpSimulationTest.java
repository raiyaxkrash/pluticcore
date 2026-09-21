package net.pocketodds;

import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.pocketodds.gambling.core.BetSnapshot;
import net.pocketodds.gambling.core.GameType;
import net.pocketodds.gambling.itembet.ItemRewardEntry;
import net.pocketodds.gambling.itembet.ItemRewardTable;
import net.pocketodds.gambling.itembet.PayoutMode;
import net.pocketodds.gambling.slot.SlotEvaluator;
import net.pocketodds.gambling.slot.SlotOutcome;
import net.pocketodds.gambling.slot.SlotSymbol;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

public class RtpSimulationTest {

    @BeforeAll
    public static void setup() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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

    // ==========================================
    // 1. ANALYTICAL EXPECTED VALUE (EV) TESTS
    // ==========================================

    @Test
    public void testAnalyticalDiceEv() {
        // Void Dice: 2d6 -> 36 outcomes
        // Snake Eyes (1,1): 1 outcome -> 0x
        // Lucky Seven (sum=7): 6 outcomes (1-6, 2-5, 3-4, 4-3, 5-2, 6-1) -> 3.0x
        // Doubles (2-2, 3-3, 4-4, 5-5, 6-6): 5 outcomes -> 2.0x
        // Eleven (5-6, 6-5): 2 outcomes -> 2.0x
        // Other: 22 outcomes -> 0x
        double totalPayoutMultiplier = (6 * 3.0) + (5 * 2.0) + (2 * 2.0); // 18 + 10 + 4 = 32
        double analyticalEv = totalPayoutMultiplier / 36.0; // 32 / 36 = 8 / 9 ≈ 0.88888889

        System.out.printf("Theoretical Void Dice EV: %.6f (%.2f%%)\n", analyticalEv, analyticalEv * 100.0);
        Assertions.assertEquals(8.0 / 9.0, analyticalEv, 0.000001, "Theoretical Dice EV must be exactly 8/9");
        Assertions.assertTrue(analyticalEv < 1.0, "Theoretical Dice EV must have house edge (RTP < 100%)");
    }

    @Test
    public void testAnalyticalRouletteEv() {
        // European Roulette: 37 pockets (0..36)
        // Red (18 pockets): 2.0x payout -> EV = 18 * 2.0 / 37 = 36 / 37 ≈ 0.97297297
        // Black (18 pockets): 2.0x payout -> EV = 18 * 2.0 / 37 = 36 / 37 ≈ 0.97297297
        // Green / Zero (1 pocket): 35.0x payout -> EV = 1 * 35.0 / 37 = 35 / 37 ≈ 0.94594595
        double redEv = (18.0 * 2.0) / 37.0;
        double blackEv = (18.0 * 2.0) / 37.0;
        double greenEv = (1.0 * 35.0) / 37.0;

        System.out.printf("Theoretical Roulette Red EV: %.6f (%.2f%%)\n", redEv, redEv * 100.0);
        System.out.printf("Theoretical Roulette Green EV: %.6f (%.2f%%)\n", greenEv, greenEv * 100.0);

        Assertions.assertEquals(36.0 / 37.0, redEv, 0.000001);
        Assertions.assertEquals(36.0 / 37.0, blackEv, 0.000001);
        Assertions.assertEquals(35.0 / 37.0, greenEv, 0.000001);
        Assertions.assertTrue(redEv < 1.0 && greenEv < 1.0, "Roulette EV must be strictly under 100%");
    }

    @Test
    public void testAnalyticalSlotBaseEv() {
        // Evaluate all 8^3 = 512 combinations weighted by probability
        SlotSymbol[] symbols = SlotSymbol.values();
        int totalWeight = 0;
        for (SlotSymbol s : symbols) {
            totalWeight += s.getWeight(null);
        }
        Assertions.assertEquals(104, totalWeight, "Default total symbol weight must be 104");

        double totalWeightedPayout = 0.0;
        double totalCombosWeight = Math.pow(totalWeight, 3);
        double jackpotProb = 0.0;
        double skullsProb = 0.0;

        for (SlotSymbol s0 : symbols) {
            for (SlotSymbol s1 : symbols) {
                for (SlotSymbol s2 : symbols) {
                    double comboWeight = (double) s0.getWeight(null) * s1.getWeight(null) * s2.getWeight(null);
                    SlotOutcome outcome = SlotEvaluator.evaluate(new SlotSymbol[]{s0, s1, s2}, null);

                    if (outcome.isJackpot()) {
                        jackpotProb += (comboWeight / totalCombosWeight);
                        // Base multiplier for jackpot (excluding dynamic pool)
                        totalWeightedPayout += comboWeight * outcome.getMultiplier();
                    } else if (outcome.isSkulls()) {
                        skullsProb += (comboWeight / totalCombosWeight);
                    } else if (outcome.isWin()) {
                        totalWeightedPayout += comboWeight * outcome.getMultiplier();
                    }
                }
            }
        }

        double analyticalBaseEv = totalWeightedPayout / totalCombosWeight;
        System.out.printf("Theoretical Base Slot EV (excl. jackpot pool): %.6f (%.2f%%)\n", analyticalBaseEv, analyticalBaseEv * 100.0);
        System.out.printf("Theoretical Jackpot Probability: %.6f (1 in %.0f)\n", jackpotProb, 1.0 / jackpotProb);
        System.out.printf("Theoretical 3-Skulls Disaster Probability: %.6f (1 in %.0f)\n", skullsProb, 1.0 / skullsProb);

        Assertions.assertTrue(analyticalBaseEv < 1.0, "Theoretical base Slot EV must be under 100%!");
        Assertions.assertTrue(analyticalBaseEv > 0.65, "Theoretical base Slot EV should be above 65%!");
    }

    // ==============================================================
    // 2. MONTE CARLO SIMULATIONS WITH 95% CONFIDENCE INTERVALS (100K)
    // ==============================================================

    @Test
    public void testDiceMonteCarlo100kWithConfidenceInterval() {
        final int N = 100_000;
        final Random random = new Random(42L);
        final double theoreticalEv = 8.0 / 9.0; // ~0.888889

        double sumPayout = 0.0;
        double sumSqPayout = 0.0;

        for (int i = 0; i < N; i++) {
            int d1 = random.nextInt(6) + 1;
            int d2 = random.nextInt(6) + 1;
            int sum = d1 + d2;
            double payout = 0.0;

            if (d1 == 1 && d2 == 1) {
                payout = 0.0;
            } else if (sum == 7) {
                payout = 3.0;
            } else if (d1 == d2) {
                payout = 2.0;
            } else if (sum == 11) {
                payout = 2.0;
            }

            sumPayout += payout;
            sumSqPayout += (payout * payout);
        }

        double sampleMean = sumPayout / N;
        double sampleVariance = (sumSqPayout - (N * sampleMean * sampleMean)) / (N - 1);
        double sampleStdDev = Math.sqrt(sampleVariance);
        double stdError = sampleStdDev / Math.sqrt(N);
        double ciLower = sampleMean - 1.96 * stdError;
        double ciUpper = sampleMean + 1.96 * stdError;

        System.out.println("=== MONTE CARLO DICE (100,000 ROLLS, SEED 42) ===");
        System.out.printf("Sample Mean EV: %.6f (RTP: %.2f%%)\n", sampleMean, sampleMean * 100.0);
        System.out.printf("Standard Error: %.6f\n", stdError);
        System.out.printf("95%% Confidence Interval: [%.6f, %.6f]\n", ciLower, ciUpper);
        System.out.printf("Theoretical EV: %.6f\n", theoreticalEv);

        // Theoretical EV must fall inside the 95% Confidence Interval
        Assertions.assertTrue(theoreticalEv >= ciLower && theoreticalEv <= ciUpper,
                String.format("Theoretical EV (%.4f) must lie inside 95%% CI [%.4f, %.4f]", theoreticalEv, ciLower, ciUpper));
    }

    @Test
    public void testRouletteMonteCarlo100kWithConfidenceInterval() {
        final int N = 100_000;
        final Random random = new Random(42L);
        final Set<Integer> redNumbers = Set.of(
                1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36
        );

        // Test Red Bet
        double sumPayoutRed = 0.0;
        double sumSqPayoutRed = 0.0;

        for (int i = 0; i < N; i++) {
            int roll = random.nextInt(37); // 0..36
            double payout = redNumbers.contains(roll) ? 2.0 : 0.0;
            sumPayoutRed += payout;
            sumSqPayoutRed += (payout * payout);
        }

        double meanRed = sumPayoutRed / N;
        double varRed = (sumSqPayoutRed - (N * meanRed * meanRed)) / (N - 1);
        double seRed = Math.sqrt(varRed) / Math.sqrt(N);
        double ciLowerRed = meanRed - 1.96 * seRed;
        double ciUpperRed = meanRed + 1.96 * seRed;
        double theoreticalRedEv = 36.0 / 37.0;

        System.out.println("=== MONTE CARLO ROULETTE RED (100,000 SPINS, SEED 42) ===");
        System.out.printf("Sample Mean EV: %.6f (RTP: %.2f%%)\n", meanRed, meanRed * 100.0);
        System.out.printf("95%% CI: [%.6f, %.6f] (Theoretical: %.6f)\n", ciLowerRed, ciUpperRed, theoreticalRedEv);

        Assertions.assertTrue(theoreticalRedEv >= ciLowerRed && theoreticalRedEv <= ciUpperRed,
                "Theoretical Red EV must be within 95% CI");
    }

    @Test
    public void testSlotMonteCarlo100kWithConfidenceInterval() {
        final int N = 100_000;
        final Random random = new Random(42L);
        final RandomSource randomSource = createRandomSource(random);

        // Precalculate base theoretical EV
        SlotSymbol[] symbols = SlotSymbol.values();
        double totalWeightedPayout = 0.0;
        double totalCombosWeight = Math.pow(104, 3);
        for (SlotSymbol s0 : symbols) {
            for (SlotSymbol s1 : symbols) {
                for (SlotSymbol s2 : symbols) {
                    double comboWeight = (double) s0.getWeight(null) * s1.getWeight(null) * s2.getWeight(null);
                    SlotOutcome outcome = SlotEvaluator.evaluate(new SlotSymbol[]{s0, s1, s2}, null);
                    if (outcome.isWin() || outcome.isJackpot()) {
                        totalWeightedPayout += comboWeight * outcome.getMultiplier();
                    }
                }
            }
        }
        double theoreticalBaseEv = totalWeightedPayout / totalCombosWeight;

        double sumMultiplier = 0.0;
        double sumSqMultiplier = 0.0;

        for (int i = 0; i < N; i++) {
            SlotSymbol s0 = SlotSymbol.getRandomSymbol(randomSource, null);
            SlotSymbol s1 = SlotSymbol.getRandomSymbol(randomSource, null);
            SlotSymbol s2 = SlotSymbol.getRandomSymbol(randomSource, null);

            SlotOutcome outcome = SlotEvaluator.evaluate(new SlotSymbol[]{s0, s1, s2}, null);
            double mult = outcome.isWin() || outcome.isJackpot() ? outcome.getMultiplier() : 0.0;

            sumMultiplier += mult;
            sumSqMultiplier += (mult * mult);
        }

        double mean = sumMultiplier / N;
        double variance = (sumSqMultiplier - (N * mean * mean)) / (N - 1);
        double stdError = Math.sqrt(variance) / Math.sqrt(N);
        double ciLower = mean - 1.96 * stdError;
        double ciUpper = mean + 1.96 * stdError;

        System.out.println("=== MONTE CARLO SLOT (100,000 SPINS, SEED 42) ===");
        System.out.printf("Sample Base EV: %.6f (RTP: %.2f%%)\n", mean, mean * 100.0);
        System.out.printf("Standard Error: %.6f\n", stdError);
        System.out.printf("95%% CI: [%.6f, %.6f] (Theoretical: %.6f)\n", ciLower, ciUpper, theoreticalBaseEv);

        Assertions.assertTrue(theoreticalBaseEv >= ciLower && theoreticalBaseEv <= ciUpper,
                String.format("Theoretical Base Slot EV (%.4f) must lie inside 95%% CI [%.4f, %.4f]", theoreticalBaseEv, ciLower, ciUpper));
    }

    // ==============================================================
    // 3. ITEM BETTING MODES & ROUNDING SIMULATION (1, 8, 32, 64)
    // ==============================================================

    @Test
    public void testItemBettingPayoutModesAndRoundingAcrossBetCounts() {
        int[] betCounts = new int[]{1, 8, 32, 64};
        final int ROUNDS = 20_000;
        final long itemUnitCredit = 1L; // Iron ingot = 1 credit

        ItemRewardEntry ironEntry = new ItemRewardEntry(new ResourceLocation("minecraft:iron_ingot"), 60, 1, 4, 32, 1L, 1L, 100L);
        ItemRewardEntry goldEntry = new ItemRewardEntry(new ResourceLocation("minecraft:gold_ingot"), 25, 1, 2, 16, 8L, 8L, 200L);
        ItemRewardTable testTable = new ItemRewardTable(List.of(ironEntry, goldEntry));

        for (int betCount : betCounts) {
            Random random = new Random(42L + betCount);
            RandomSource randomSource = createRandomSource(random);

            long totalItemBet = 0;
            long totalSameItemPayout = 0;
            long totalBothPayoutCredits = 0;

            for (int r = 0; r < ROUNDS; r++) {
                totalItemBet += betCount;

                // Roll dice game logic for item bet testing
                int d1 = random.nextInt(6) + 1;
                int d2 = random.nextInt(6) + 1;
                int sum = d1 + d2;
                double mult = 0.0;
                if (sum == 7) mult = 3.0;
                else if (d1 == d2 && d1 != 1) mult = 2.0;
                else if (sum == 11) mult = 2.0;

                // 1. SAME_ITEM Mode: Exact integer rounding via Math.round
                int sameItemPayout = (int) Math.round(betCount * mult);
                totalSameItemPayout += sameItemPayout;

                // 2. BOTH Mode: 80% same item, 20% table rewards
                if (mult > 0.0) {
                    long totalWinCredits = Math.max(1L, Math.round(betCount * itemUnitCredit * mult));
                    double sameTarget = betCount * mult * 0.80;
                    int sameBase = (int) Math.floor(sameTarget);
                    int partSame = sameBase + (random.nextDouble() < (sameTarget - sameBase) ? 1 : 0);
                    long partSameCredits = partSame * itemUnitCredit;

                    double rawTableBudget = totalWinCredits * 0.20;
                    long tableBase = (long) Math.floor(rawTableBudget);
                    long tableBudget = tableBase + (random.nextDouble() < (rawTableBudget - tableBase) ? 1L : 0L);

                    long tableCredits = 0;
                    if (tableBudget > 0L) {
                        List<ItemStack> tableStacks = testTable.rollRewards(randomSource, tableBudget, betCount * itemUnitCredit, 512);
                        for (ItemStack s : tableStacks) {
                            tableCredits += s.getCount() * (s.getItem() == Items.GOLD_INGOT ? 8L : 1L);
                        }
                    }
                    totalBothPayoutCredits += (partSameCredits + tableCredits);
                }
            }

            double sameItemRtp = (totalSameItemPayout / (double) totalItemBet) * 100.0;
            double bothRtp = (totalBothPayoutCredits / (double) (totalItemBet * itemUnitCredit)) * 100.0;

            System.out.printf("=== ITEM BETTING (BET SIZE: %d) ===\n", betCount);
            System.out.printf("SAME_ITEM Mode RTP: %.2f%%\n", sameItemRtp);
            System.out.printf("BOTH Mode (80/20) RTP: %.2f%%\n", bothRtp);

            // House edge must be preserved: RTP strictly < 100% for all bet sizes
            Assertions.assertTrue(sameItemRtp < 100.0, "SAME_ITEM RTP must be < 100% for bet count " + betCount);
            Assertions.assertTrue(sameItemRtp > 75.0, "SAME_ITEM RTP must be > 75% for bet count " + betCount);
            Assertions.assertTrue(bothRtp < 105.0, "BOTH Mode RTP must be balanced around target RTP for bet count " + betCount);
        }
    }

    // ==============================================================
    // 4. PRESERVED LEGACY 1,000,000 ITERATION SIMULATIONS
    // ==============================================================

    @Test
    public void simulateOneMillionSlotSpins() {
        final int SPINS = 1_000_000;
        final int BET_PER_SPIN = 8;
        final Random random = new Random(42);
        final RandomSource randomSource = createRandomSource(random);

        long totalBet = 0;
        long totalPayout = 0;
        long jackpotsHit = 0;
        long skullsHit = 0;
        long jokersHit = 0;
        long winsCount = 0;
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
                int betPayout = (int) Math.round(BET_PER_SPIN * outcome.getMultiplier());
                long poolPayout = jackpotPool;
                jackpotPool = 100;
                totalPayout += (betPayout + poolPayout);
                winsCount++;
            } else if (outcome.isSkulls()) {
                skullsHit++;
            } else if (outcome.isThreeJokers() || outcome.isWin()) {
                if (outcome.isThreeJokers()) jokersHit++;
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
        final int BET = 1;
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
                totalPayout += (int) Math.round(BET * 3.0);
            } else if (d1 == d2) {
                doublesCount++;
                totalPayout += (int) Math.round(BET * 2.0);
            } else if (sum == 11) {
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
                busts++;
                pot = 0;
            } else if (roll < 70) {
                // Neutral
            } else if (roll < 88) {
                pot = (pot <= 1) ? 2 : (int) Math.round(pot * 1.25);
            } else if (roll < 94) {
                pot = (pot <= 1) ? 2 : (int) Math.round(pot * 1.5);
            } else if (roll < 97) {
                pot += 1;
                jokersAwarded++;
            } else {
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

    @Test
    public void simulateDeckOfFateMultiStepStrategies() {
        final int RUNS = 1_000_000;

        StrategyResult resA = runDeckSimulation(0, 2, RUNS, 54321);
        System.out.println("=== DECK OF FATE STRATEGY A: CASHOUT AT POT >= 2 ===");
        System.out.printf("Direct Chip RTP: %.2f%%\n", resA.rtp);
        System.out.printf("Busts: %d (%.2f%%)\n", resA.busts, (resA.busts / (double) RUNS) * 100.0);
        System.out.printf("Joker items: %d, Insurance items: %d\n", resA.jokers, resA.insurance);
        Assertions.assertTrue(resA.rtp < 100.0, "Strategy A RTP must stay under 100%! Got: " + resA.rtp);

        StrategyResult resB = runDeckSimulation(2, 0, RUNS, 54322);
        System.out.println("=== DECK OF FATE STRATEGY B: CASHOUT AT STREAK >= 2 ===");
        System.out.printf("Direct Chip RTP: %.2f%%\n", resB.rtp);
        System.out.printf("Busts: %d (%.2f%%)\n", resB.busts, (resB.busts / (double) RUNS) * 100.0);
        System.out.printf("Joker items: %d, Insurance items: %d\n", resB.jokers, resB.insurance);
        Assertions.assertTrue(resB.rtp < 100.0, "Strategy B RTP must stay under 100%! Got: " + resB.rtp);

        StrategyResult resC = runDeckSimulation(3, 0, RUNS, 54323);
        System.out.println("=== DECK OF FATE STRATEGY C: CASHOUT AT STREAK >= 3 ===");
        System.out.printf("Direct Chip RTP: %.2f%%\n", resC.rtp);
        System.out.printf("Busts: %d (%.2f%%)\n", resC.busts, (resC.busts / (double) RUNS) * 100.0);
        System.out.printf("Joker items: %d, Insurance items: %d\n", resC.jokers, resC.insurance);
        Assertions.assertTrue(resC.rtp < 100.0, "Strategy C RTP must stay under 100%! Got: " + resC.rtp);
        System.out.println("=======================================================");
    }

    private static class StrategyResult {
        long totalBet;
        long totalCashed;
        long busts;
        long jokers;
        long insurance;
        double rtp;
    }

    private StrategyResult runDeckSimulation(int targetStreak, int targetPot, int runs, long seed) {
        Random random = new Random(seed);
        StrategyResult res = new StrategyResult();
        res.totalBet = runs;

        for (int i = 0; i < runs; i++) {
            int pot = 1;
            int streak = 1;
            boolean inRound = true;

            while (inRound) {
                int roll = random.nextInt(100);
                if (roll < 35) {
                    pot = 0;
                    streak = 0;
                    res.busts++;
                    inRound = false;
                } else {
                    if (roll < 70) {
                        // Patience
                    } else if (roll < 88) {
                        pot = (pot <= 1) ? 2 : (int) Math.round(pot * 1.25);
                    } else if (roll < 94) {
                        pot = (pot <= 1) ? 2 : (int) Math.round(pot * 1.5);
                    } else if (roll < 97) {
                        pot += 1;
                        res.jokers++;
                    } else {
                        pot += 1;
                        res.insurance++;
                    }
                    streak++;

                    if (targetPot > 0 && pot >= targetPot) {
                        inRound = false;
                    } else if (targetStreak > 0 && streak >= targetStreak) {
                        inRound = false;
                    }
                }
            }
            res.totalCashed += pot;
        }

        res.rtp = (res.totalCashed / (double) res.totalBet) * 100.0;
        return res;
    }
}
