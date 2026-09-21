================================================================================
                                POCKET ODDS
================================================================================
Pocket Odds is a standalone Forge 1.20.1 pocket casino mod designed for pure
inventory-based gameplay with ZERO custom GUIs, screens, or containers.
All gaming interactions occur via items, action bar animations, sounds,
particles, and automatic transactional reward delivery.

Target Environment:
- Minecraft: 1.20.1
- Forge: 47.x (tested on Forge 47.2.0)
- Java: 17
- Modpack: All the Mods 9: To the Sky (ATM9: To the Sky) compatible

--------------------------------------------------------------------------------
FEATURES & ITEMS (12 Total):
--------------------------------------------------------------------------------
1. Casino Chips:
   - Copper Chip   (pocketodds:copper_chip):    Base value = 1
   - Gold Chip     (pocketodds:gold_chip):      Base value = 8
   - Diamond Chip  (pocketodds:diamond_chip):   Base value = 64
   - Netherite Chip (pocketodds:netherite_chip): Base value = 512

2. Gambling Devices:
   - Pocket Slot (pocketodds:pocket_slot):
     3-reel slot machine played directly in inventory.
     Controls:
       [Shift + Right Click]: Cycle bet count (1 / 8 / 32 / 64).
       [Right Click]: Spin reels (deducts bet from offhand chip tier).
     Action bar displays tick-by-tick reel spinning animation.

   - Void Dice (pocketodds:void_dice):
     2d6 dice game.
     Controls:
       [Shift + Right Click]: Cycle bet count.
       [Right Click]: Roll 2d6.
     Payouts:
       Sum 7 = 3.0x payout.
       Doubles = 2.0x payout.
       Sum 11 = 2.0x payout.
       Snake Eyes (1-1) = Disaster with Wither & Darkness effects.

   - Deck of Fate (pocketodds:deck_of_fate):
     Risk-and-reward streak card game with locked-in currency tier.
     Controls:
       [Right Click]: Draw card to push your luck and multiply accumulated pot.
       [Shift + Right Click]: Cash Out current pot in the locked-in currency.
     Cards:
       - Curse (35%): Lose pot (Insurance refunds 50%, else gives Cursed Card).
       - Patience (35%): Safe neutral card, pot preserved, streak advances.
       - Fortune (18%): Multiplies pot by 1.25x.
       - Riches (6%): Multiplies pot by 1.5x.
       - Joker's Favor (3%): Pot +1 and gives Wild Joker item.
       - Guardian Shield (3%): Pot +1 and gives Insurance item.

   - Roulette Token (pocketodds:roulette_token):
     European roulette (numbers 0..36).
     Controls:
       [Shift + Right Click]: Choose bet (Red / Black / Green).
       [Right Click]: Spin wheel (consumes 1 token, contributes to jackpot).
     Payouts:
       Red / Black = 2.0x in Gold Chips (18/37 probability, 97.3% RTP).
       Green (Zero) = 35.0x in Gold Chips (1/37 probability, 94.6% RTP).

3. Support & Utility Items:
   - Wild Joker (pocketodds:joker):
     Acts as a wild card in slot machines. When held in inventory during a
     losing spin or 3-skulls disaster, 1 Joker is consumed to rescue the spin
     into the best possible winning match! Preserved on already winning spins.
   - Insurance Policy (pocketodds:insurance):
     Single-use protection item. Automatically consumed on total loss in dice,
     slot, or deck of fate to refund 50% of your bet.
   - Cursed Card (pocketodds:cursed_card):
     Penalty token received upon busting in Deck of Fate without insurance.
   - Grand Jackpot Trophy (pocketodds:jackpot_token):
     Commemorative trophy awarded on hitting 3 Stars in the slot.
     Records winner name, amount, and date in NBT.
     [Right Click]: Displays current server jackpot pool with fireworks.

--------------------------------------------------------------------------------
RELIABILITY & ARCHITECTURE:
--------------------------------------------------------------------------------
- Transactional Outbox Pattern: Rewards are persisted in world SavedData
  (PendingTransactions) before jackpot reset and session removal. Delivery is
  confirmed item-by-item, guaranteeing at-least-once delivery with zero reward loss.
- Currency Lock-In: Deck of Fate locks the bet chip tier in NBT upon first draw,
  preventing offhand substitution exploits.
- Split Large Payouts: Large jackpot payouts are safely split into valid stacks
  of <= 64 items, and dropped safely at player feet if inventory is full.
- Automatic Config Migration: Legacy v1 configurations are seamlessly migrated
  to v2 balanced defaults upon loading without overwriting custom admin settings.
================================================================================
