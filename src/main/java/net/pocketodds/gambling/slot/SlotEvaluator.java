package net.pocketodds.gambling.slot;

import net.pocketodds.config.PocketOddsConfig;

public class SlotEvaluator {

    public static SlotOutcome evaluate(SlotSymbol[] symbols, PocketOddsConfig.Server config) {
        SlotSymbol s0 = symbols[0];
        SlotSymbol s1 = symbols[1];
        SlotSymbol s2 = symbols[2];

        boolean configReady = config != null && PocketOddsConfig.isConfigLoaded();

        // 1. Grand Jackpot: Exactly 3 Stars
        if (s0 == SlotSymbol.STAR && s1 == SlotSymbol.STAR && s2 == SlotSymbol.STAR) {
            double mult = configReady ? config.payoutStar3Multiplier.get() : 60.0;
            return new SlotOutcome(symbols, true, false, false, SlotSymbol.STAR, 3, mult);
        }

        // 2. Dangerous Disaster: Exactly 3 Skulls
        if (s0 == SlotSymbol.SKULL && s1 == SlotSymbol.SKULL && s2 == SlotSymbol.SKULL) {
            return new SlotOutcome(symbols, false, true, false, SlotSymbol.SKULL, 3, 0.0);
        }

        // 3. Three Jokers
        if (s0 == SlotSymbol.JOKER && s1 == SlotSymbol.JOKER && s2 == SlotSymbol.JOKER) {
            double mult = configReady ? config.payoutJoker3.get() : 60.0;
            return new SlotOutcome(symbols, false, false, true, SlotSymbol.JOKER, 3, mult);
        }

        // 4. Check 3 of a kind with Joker Wildcards
        SlotSymbol[] regularTargets = new SlotSymbol[]{
                SlotSymbol.STAR, SlotSymbol.EMERALD, SlotSymbol.DIAMOND,
                SlotSymbol.GOLD, SlotSymbol.IRON, SlotSymbol.CHERRY
        };

        for (SlotSymbol target : regularTargets) {
            int match = 0;
            if (s0 == target || s0 == SlotSymbol.JOKER) match++;
            if (s1 == target || s1 == SlotSymbol.JOKER) match++;
            if (s2 == target || s2 == SlotSymbol.JOKER) match++;

            if (match == 3) {
                double mult = get3MatchMultiplier(target, config);
                return new SlotOutcome(symbols, false, false, false, target, 3, mult);
            }
        }

        // 5. Check 2 of a kind with Joker Wildcards
        for (SlotSymbol target : regularTargets) {
            int directMatch = 0;
            int jokerCount = 0;
            if (s0 == target) directMatch++; else if (s0 == SlotSymbol.JOKER) jokerCount++;
            if (s1 == target) directMatch++; else if (s1 == SlotSymbol.JOKER) jokerCount++;
            if (s2 == target) directMatch++; else if (s2 == SlotSymbol.JOKER) jokerCount++;

            if (directMatch >= 1 && (directMatch + jokerCount >= 2)) {
                double mult = get2MatchMultiplier(target, config);
                if (mult > 0.0) {
                    return new SlotOutcome(symbols, false, false, false, target, 2, mult);
                }
            }
        }

        // No winning combination
        return new SlotOutcome(symbols, false, false, false, null, 0, 0.0);
    }

    private static double get3MatchMultiplier(SlotSymbol symbol, PocketOddsConfig.Server config) {
        if (config == null || !PocketOddsConfig.isConfigLoaded()) {
            return switch (symbol) {
                case EMERALD -> 40.0;
                case DIAMOND -> 20.0;
                case GOLD -> 10.0;
                case IRON -> 5.0;
                case CHERRY -> 3.0;
                case STAR -> 25.0; // 2 stars + joker
                default -> 2.0;
            };
        }
        return switch (symbol) {
            case EMERALD -> config.payoutEmerald3.get();
            case DIAMOND -> config.payoutDiamond3.get();
            case GOLD -> config.payoutGold3.get();
            case IRON -> config.payoutIron3.get();
            case CHERRY -> config.payoutCherry3.get();
            case STAR -> config.payoutStar3Multiplier.get() * 0.4;
            default -> 2.0;
        };
    }

    private static double get2MatchMultiplier(SlotSymbol symbol, PocketOddsConfig.Server config) {
        if (config == null || !PocketOddsConfig.isConfigLoaded()) {
            return switch (symbol) {
                case STAR -> 8.0;
                case EMERALD -> 4.0;
                case DIAMOND -> 2.5;
                case GOLD -> 1.5;
                case IRON -> 1.0;
                case CHERRY -> 0.5;
                default -> 0.0;
            };
        }
        return switch (symbol) {
            case STAR -> config.payoutStar2.get();
            case EMERALD -> config.payoutEmerald2.get();
            case DIAMOND -> config.payoutDiamond2.get();
            case GOLD -> config.payoutGold2.get();
            case IRON -> config.payoutIron2.get();
            case CHERRY -> config.payoutCherry2.get();
            default -> 0.0;
        };
    }

    public static double evaluateOutcomeScore(SlotOutcome outcome, int betCount) {
        if (outcome == null) return -20000.0;
        if (outcome.isJackpot()) {
            return 10000000.0 + outcome.getMultiplier() * betCount;
        }
        if (outcome.isThreeJokers()) {
            return 1000000.0 + outcome.getMultiplier() * betCount;
        }
        if (outcome.isWin()) {
            return 10000.0 + outcome.getMultiplier() * betCount;
        }
        if (outcome.isSkulls()) {
            return -10000.0; // Disaster
        }
        return 0.0; // Safe loss
    }

    public static class JokerRescueResult {
        private final boolean rescued;
        private final SlotSymbol[] symbols;
        private final SlotOutcome outcome;

        public JokerRescueResult(boolean rescued, SlotSymbol[] symbols, SlotOutcome outcome) {
            this.rescued = rescued;
            this.symbols = symbols;
            this.outcome = outcome;
        }

        public boolean isRescued() { return rescued; }
        public SlotSymbol[] getSymbols() { return symbols; }
        public SlotOutcome getOutcome() { return outcome; }
    }

    public static JokerRescueResult tryRescueWithJoker(SlotSymbol[] originalSymbols, int betCount, PocketOddsConfig.Server config) {
        SlotOutcome initialOutcome = evaluate(originalSymbols, config);
        // Product rule: Do NOT consume Joker if the spin is already winning!
        if (initialOutcome.isWin() || initialOutcome.isJackpot() || initialOutcome.isThreeJokers()) {
            return new JokerRescueResult(false, originalSymbols, initialOutcome);
        }

        double initialScore = evaluateOutcomeScore(initialOutcome, betCount);
        double bestScore = initialScore;
        SlotSymbol[] bestSymbols = originalSymbols;
        SlotOutcome bestOutcome = initialOutcome;
        boolean improved = false;

        for (int i = 0; i < 3; i++) {
            if (originalSymbols[i] == SlotSymbol.JOKER) continue;
            SlotSymbol[] candidate = new SlotSymbol[]{originalSymbols[0], originalSymbols[1], originalSymbols[2]};
            candidate[i] = SlotSymbol.JOKER;
            SlotOutcome candidateOutcome = evaluate(candidate, config);
            double candidateScore = evaluateOutcomeScore(candidateOutcome, betCount);
            if (candidateScore > bestScore) {
                bestScore = candidateScore;
                bestOutcome = candidateOutcome;
                bestSymbols = candidate;
                improved = true;
            }
        }

        return new JokerRescueResult(improved, bestSymbols, bestOutcome);
    }
}
