package net.pocketodds.gambling.slot;

public class SlotOutcome {
    private final SlotSymbol[] symbols;
    private final boolean isJackpot;
    private final boolean isSkulls;
    private final boolean isThreeJokers;
    private final SlotSymbol matchedSymbol;
    private final int matchCount;
    private final double multiplier;

    public SlotOutcome(SlotSymbol[] symbols, boolean isJackpot, boolean isSkulls, boolean isThreeJokers,
                       SlotSymbol matchedSymbol, int matchCount, double multiplier) {
        this.symbols = symbols;
        this.isJackpot = isJackpot;
        this.isSkulls = isSkulls;
        this.isThreeJokers = isThreeJokers;
        this.matchedSymbol = matchedSymbol;
        this.matchCount = matchCount;
        this.multiplier = multiplier;
    }

    public SlotSymbol[] getSymbols() {
        return symbols;
    }

    public boolean isJackpot() {
        return isJackpot;
    }

    public boolean isSkulls() {
        return isSkulls;
    }

    public boolean isThreeJokers() {
        return isThreeJokers;
    }

    public SlotSymbol getMatchedSymbol() {
        return matchedSymbol;
    }

    public int getMatchCount() {
        return matchCount;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public boolean isWin() {
        return isJackpot || multiplier > 0.0;
    }
}
