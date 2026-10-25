package com.streamvault.plugin.hap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** Pins the heuristics the events filter relies on, since the guide carries no genre at all. */
public class EventSportTest {

    @Test
    public void recognisesTheSportsTheFilterOffers() {
        assertEquals(EventSport.Kind.FUTBOL, EventSport.classify("Jornada 10: Barcelona - Real Madrid"));
        assertEquals(EventSport.Kind.FUTBOL, EventSport.classify("Fútbol: Atlético - Sevilla"));
        assertEquals(EventSport.Kind.FUTBOL, EventSport.classify("LaLiga EA Sports"));
        assertEquals(EventSport.Kind.BALONCESTO, EventSport.classify("NBA: Lakers - Celtics"));
        assertEquals(EventSport.Kind.BALONCESTO, EventSport.classify("Baloncesto: Real Madrid - Barça"));
        assertEquals(EventSport.Kind.BALONCESTO, EventSport.classify("Euroliga · Jornada 12"));
        assertEquals(EventSport.Kind.MOTOR, EventSport.classify("MotoGP: Gran Premio de Aragón"));
        assertEquals(EventSport.Kind.MOTOR, EventSport.classify("Fórmula 1: Clasificación"));
        assertEquals(EventSport.Kind.TENIS, EventSport.classify("Wimbledon: Final masculina"));
        assertEquals(EventSport.Kind.CICLISMO, EventSport.classify("Tour de Francia: Etapa 12"));
    }

    @Test
    public void ignoresCaseAndAccents() {
        assertEquals(EventSport.Kind.FUTBOL, EventSport.classify("FUTBOL CHAMPIONS"));
        assertEquals(EventSport.Kind.BALONCESTO, EventSport.classify("baloncesto acb"));
        assertEquals(EventSport.Kind.TENIS, EventSport.classify("Tenis: ATP Masters"));
    }

    @Test
    public void aMatchBetweenTwoNamedSidesIsSportButUnclassified() {
        // Two clubs and no competition: it is a sporting event, but nothing says which sport.
        assertEquals(EventSport.Kind.OTROS, EventSport.classify("Barcelona vs Real Madrid"));
    }

    @Test
    public void newsAndFilmsAreNotSport() {
        assertNull(EventSport.classify("Noticias 24H"));
        assertNull(EventSport.classify("Cine: El padrino"));
        assertNull(EventSport.classify(""));
        assertNull(EventSport.classify(null));
    }

    @Test
    public void aShortKeywordDoesNotMatchInsideAnotherWord() {
        // "nba" must not fire on a word that merely contains it.
        assertNull(EventSport.classify("Bananas y compañía"));
    }

    @Test
    public void everyKindHasALabelForTheFilter() {
        for (EventSport.Kind kind : EventSport.Kind.values()) {
            org.junit.Assert.assertFalse(kind.label().trim().isEmpty());
        }
    }
}
