package forge.game;

import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.deck.Deck;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.replacement.ReplacementEffect;
import forge.game.replacement.ReplacementHandler;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertNotSame;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

public class DanDanAcquisitionTest extends AITest {
    @DataProvider(name = "mimeoplasmEntries")
    public Object[][] mimeoplasmEntries() {
        return new Object[][] {
                {false, false, 1}, {false, false, 2}, {false, true, 1}, {false, true, 2},
                {true, false, 1}, {true, false, 2}, {true, true, 1}, {true, true, 2}
        };
    }

    @Test(dataProvider = "mimeoplasmEntries")
    public void mimeoplasmEntryUsesOnlyEligibleGraveyardCards(boolean shared, boolean simultaneous, int eligible) {
        Game game = newGame(shared);
        Player other = game.getPlayers().get(0);
        Player recipient = game.getPlayers().get(1);
        Player sourceOwner = shared ? other : recipient;
        Card source = addCardToZone("The Mimeoplasm", sourceOwner, ZoneType.Graveyard);
        Card sourceLki = CardCopyService.getLKICopy(source);
        Set<Player> sourceOwners = Set.copyOf(source.getOwners());
        Card companion = simultaneous ? addCardToZone("Runeclaw Bear", sourceOwner, ZoneType.Graveyard) : null;
        Card copy = addCardToZone("Grizzly Bears", recipient, ZoneType.Graveyard);
        Card counters = eligible == 2 ? addCardToZone("Hill Giant", other, ZoneType.Graveyard) : null;
        int[] choicesMade = {0};
        recipient.dangerouslySetController(new PlayerControllerAi(game, recipient, new LobbyPlayerAi("recipient", null)) {
            @Override
            public boolean confirmReplacementEffect(ReplacementEffect replacement, SpellAbility ability,
                    GameEntity affected, String question) {
                return true;
            }

            @Override
            public CardCollectionView chooseCardsForEffect(CardCollectionView choices, SpellAbility ability,
                    String title, int min, int max, boolean optional, Map<String, Object> params) {
                assertEquals(eligible, 2, "entry must not ask for two cards when only one is eligible");
                assertEquals(Set.copyOf(choices.stream().map(Card::getId).toList()),
                        Set.of(copy.getId(), counters.getId()),
                        "only the two eligible cards are offered; neither incoming creature may be exiled");
                CardCollection chosen = new CardCollection();
                if (max == 2) {
                    assertSame(game.getCardState(source), source, "the live source has not moved during the choice");
                    assertEquals(source.getOwners(), sourceOwners);
                    chosen.addAll(choices);
                } else {
                    assertEquals(max, 1);
                    chosen.add(choices.stream().filter(c -> c.getId() == copy.getId()).findFirst().orElseThrow());
                }
                choicesMade[0]++;
                return chosen;
            }
        });

        Card spell = addCardToZone(simultaneous ? "Ever After" : "Reanimate", recipient, ZoneType.Hand);
        SpellAbility ability = spell.getSpells().get(0);
        ability.setActivatingPlayer(recipient);
        assertTrue(ability.canTarget(source));
        ability.getTargets().add(source);
        if (companion != null) {
            assertTrue(ability.canTarget(companion));
            ability.getTargets().add(companion);
        }
        AbilityUtils.resolve(ability);

        assertEquals(choicesMade[0], eligible == 2 ? 2 : 0);
        Card result = game.getCardState(source);
        assertTrue(result.isInZone(ZoneType.Battlefield));
        assertSame(result.getOwner(), recipient);
        assertSame(result.getController(), recipient);
        assertEquals(sourceLki.getOwners(), sourceOwners, "entry preserves historical ownership");
        assertEquals(result.getName(), eligible == 2 ? "Grizzly Bears" : "The Mimeoplasm");
        assertEquals(game.getCardsIn(ZoneType.Exile).size(), eligible == 2 ? 2 : 0);
        assertEquals(game.getCardState(copy).isInZone(ZoneType.Exile), eligible == 2);
        if (companion != null) {
            assertTrue(game.getCardState(companion).isInZone(ZoneType.Battlefield),
                    "the simultaneous entrant reaches the battlefield instead of being exiled");
        }
        long occurrences = 0;
        for (ZoneType zone : List.of(ZoneType.Library, ZoneType.Graveyard, ZoneType.Hand,
                ZoneType.Battlefield, ZoneType.Exile, ZoneType.Stack)) {
            occurrences += game.getCardsIn(zone).stream().filter(c -> c.getId() == source.getId()).count();
        }
        assertEquals(occurrences, 1L, "the source occupies exactly one physical zone");
    }

    @Test
    public void commonPoolBattlefieldEntryAcquiresRecipientWithoutMutatingSource() {
        Game game = newGame(true);
        Player originalOwner = game.getRegisteredPlayers().get(0);
        Player recipient = game.getRegisteredPlayers().get(1);
        Card source = addCardToZone("Dandân", originalOwner, ZoneType.Graveyard);

        Card incoming = game.getAction().moveToPlay(source, recipient, null, AbilityKey.newMap());

        assertNotSame(incoming, source, "shared-pool entry must prepare a separate incoming object");
        assertSame(incoming.getOwner(), recipient);
        assertSame(incoming.getExplicitController(), recipient);
        assertTrue(recipient.getCardsIn(ZoneType.Battlefield).contains(incoming));
        assertTrue(source.isOwnedBy(originalOwner) && source.isOwnedBy(recipient));
        assertNull(source.getExplicitController(), "the source retains its pre-move control state");
    }

    @Test
    public void heldBattlefieldReplacementKeepsSourceAndProposedViewsDistinct() {
        Game game = newGame(true);
        Player originalOwner = game.getPlayers().get(0);
        Player recipient = game.getPlayers().get(1);
        Card source = addCardToZone("Dandân", originalOwner, ZoneType.Graveyard);
        Card replacementSource = addCardToZone("Island", recipient, ZoneType.Command);
        replacementSource.addReplacementEffect(ReplacementHandler.parseReplacement(
                "Event$ Moved | ActiveZones$ Command | Origin$ Graveyard | Destination$ Battlefield "
                        + "| ValidCard$ Creature.YouOwn | Optional$ True | Prevent$ True "
                        + "| Description$ Prevent this entry.", replacementSource, false));
        AtomicBoolean prompted = new AtomicBoolean();
        recipient.dangerouslySetController(new PlayerControllerAi(game, recipient,
                new LobbyPlayerAi("recipient", null)) {
            @Override
            public boolean confirmReplacementEffect(ReplacementEffect replacement, SpellAbility ability,
                    GameEntity affected, String question) {
                prompted.set(true);
                Card incoming = (Card) affected;
                assertNotSame(incoming, source);
                assertSame(game.getCardState(source), source);
                assertEquals(source.getOwners(), Set.of(originalOwner, recipient));
                assertNull(source.getExplicitController());
                assertEquals(Set.copyOf(source.getView().getOwners()),
                        Set.of(originalOwner.getView(), recipient.getView()));
                assertEquals(incoming.getOwners(), Set.of(recipient));
                assertSame(incoming.getExplicitController(), recipient);
                assertEquals(Set.copyOf(incoming.getView().getOwners()), Set.of(recipient.getView()));
                assertNotSame(incoming.getView(), source.getView());
                return true;
            }
        });

        Card result = game.getAction().moveToPlay(source, recipient, null, AbilityKey.newMap());

        assertTrue(prompted.get(), "the prevention choice inspects the proposed entry");
        assertSame(result, source);
        assertSame(game.getCardState(source), source);
        assertSame(source.getZone(), originalOwner.getZone(ZoneType.Graveyard));
        assertNull(source.getExplicitController());
        assertTrue(recipient.getCardsIn(ZoneType.Battlefield).isEmpty());
    }

    @Test
    public void commonLibraryCardAcquiresNamedHandOwnerAndKeepsSourceLki() {
        assertCommonPoolHandAcquisition(ZoneType.Library);
    }

    @Test
    public void commonGraveyardCardAcquiresNamedHandOwnerAndKeepsSourceLki() {
        assertCommonPoolHandAcquisition(ZoneType.Graveyard);
    }

    @Test
    public void preventedProspectiveAcquisitionLeavesSourceAndOwnerUnchanged() {
        Game game = newGame(true);
        Player originalOwner = game.getRegisteredPlayers().get(0);
        Player recipient = game.getRegisteredPlayers().get(1);
        installRecipientOwnershipReplacement(game, recipient,
                "Event$ Moved | ActiveZones$ Battlefield | Origin$ Library | Destination$ Hand "
                        + "| ValidCard$ Card.YouOwn | Prevent$ True | Description$ Prevent this move.");
        Card card = addCardToZone("Dandân", originalOwner, ZoneType.Library);

        game.getAction().moveTo(recipient.getZone(ZoneType.Hand), card, null);

        assertTrue(originalOwner.getCardsIn(ZoneType.Library).contains(card),
                "the replacement prevents the proposed move, leaving the card in its source zone");
        assertTrue(recipient.getCardsIn(ZoneType.Hand).isEmpty());
        assertSame(card.getOwner(), originalOwner);
    }

    @Test
    public void prospectiveAcquisitionReplacementRedirectsWithoutChangingProvenance() {
        Game game = newGame(true);
        Player originalOwner = game.getRegisteredPlayers().get(0);
        Player recipient = game.getRegisteredPlayers().get(1);
        Card replacementSource = addCardToZone("Veteran Ghoulcaller", recipient, ZoneType.Hand);
        game.getAction().moveToPlay(replacementSource, recipient, null, AbilityKey.newMap());
        replacementSource.setSVar("RedirectToSharedGraveyard",
                "DB$ ChangeZone | Defined$ ReplacedCard | Origin$ Library | Destination$ Graveyard | Hidden$ True");
        ReplacementEffect replacement = ReplacementHandler.parseReplacement(
                "Event$ Moved | ActiveZones$ Battlefield | Origin$ Library | Destination$ Hand "
                        + "| ValidCard$ Card.YouOwn | ReplaceWith$ RedirectToSharedGraveyard "
                        + "| Description$ Put this card into the shared graveyard instead.", replacementSource, false);
        replacementSource.addReplacementEffect(replacement);
        Card card = addCardToZone("Dandân", originalOwner, ZoneType.Library);

        game.getAction().moveTo(recipient.getZone(ZoneType.Hand), card, null);

        Card inGraveyard = game.findById(card.getId());
        assertTrue(originalOwner.getCardsIn(ZoneType.Graveyard).contains(inGraveyard),
                "the replacement's actual destination is the shared graveyard");
        assertTrue(recipient.getCardsIn(ZoneType.Hand).isEmpty());
        assertSame(inGraveyard.getOwner(), originalOwner,
                "redirected movement preserves the source owner rather than acquiring the proposed hand recipient");
        assertTrue(recipient.getZone(ZoneType.Graveyard).getCardsAddedThisTurn(ZoneType.Library).stream()
                .anyMatch(lki -> lki.getId() == card.getId() && lki.getOwner() == originalOwner),
                "the redirected zone transition retains source LKI");
    }

    private void assertCommonPoolHandAcquisition(ZoneType sourceType) {
        Game game = newGame(true);
        Player originalOwner = game.getRegisteredPlayers().get(0);
        Player recipient = game.getRegisteredPlayers().get(1);
        Card card = addCardToZone("Dandân", originalOwner, sourceType);

        game.getAction().moveTo(recipient.getZone(ZoneType.Hand), card, null);

        Card inHand = game.findById(card.getId());
        assertTrue(recipient.getCardsIn(ZoneType.Hand).contains(inHand));
        assertSame(inHand.getOwner(), recipient,
                "a successful shared " + sourceType + " to named hand move transfers ownership");
        assertEquals(originalOwner, card.getOwner(), "the source object retains its last-known owner");
        assertTrue(recipient.getZone(ZoneType.Hand).getCardsAddedThisTurn(sourceType).stream()
                .anyMatch(lki -> lki.getId() == card.getId() && lki.getOwner() == originalOwner),
                "the hand's recorded source state keeps its original owner");
    }

    private void installRecipientOwnershipReplacement(Game game, Player recipient, String script) {
        Card replacementSource = addCardToZone("Veteran Ghoulcaller", recipient, ZoneType.Hand);
        game.getAction().moveToPlay(replacementSource, recipient, null, AbilityKey.newMap());
        ReplacementEffect replacement = ReplacementHandler.parseReplacement(script, replacementSource, false);
        replacementSource.addReplacementEffect(replacement);
    }

    private Game newGame(boolean danDan) {
        ArrayList<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("first", null)));
        players.add(new RegisteredPlayer(new Deck()).setPlayer(new LobbyPlayerAi("second", null)));
        GameRules rules = new GameRules(GameType.Constructed);
        if (danDan) {
            rules.setAppliedVariants(EnumSet.of(GameType.DanDan));
        }
        Match match = new Match(rules, players, "DanDan acquisition test");
        return match.createGame();
    }
}
