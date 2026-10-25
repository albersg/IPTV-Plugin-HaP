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
    public void aNamedSportBeatsAGenericWordThatComesFirst() {
        // Both of these were on screen classified as football, which is what this pins.
        assertEquals(EventSport.Kind.BALONCESTO,
                EventSport.classify("DIRECTO Jornada 5: Força Lleida - Real Madrid T26/27 · Liga Endesa"));
        assertEquals(EventSport.Kind.MOTOR,
                EventSport.classify("DIRECTO Previo Carrera T2026 · Mundial F1 - GP de Estados Unidos"));
    }

    @Test
    public void aGenericWordStillClassifiesWhenNothingElseDoes() {
        // The match the request named: no competition in the title, but a jornada is football here.
        assertEquals(EventSport.Kind.FUTBOL, EventSport.classify("Jornada 10: Barcelona - Real Madrid"));
    }

    @Test
    public void mundialAloneNoLongerMeansFootball() {
        // It named F1 and basketball as often as football, so it is no longer a football keyword. The
        // sport is still recognised, just not as football.
        assertEquals(EventSport.Kind.OTROS, EventSport.classify("Mundial de atletismo"));
    }

    @Test
    public void sportsWithoutTheirOwnFilterGoToOtrosRatherThanAWrongOne() {
        // Both were on screen classified as football because of the word "jornada".
        assertEquals(EventSport.Kind.OTROS,
                EventSport.classify("División de Honor de rugby T26/27 · Jornada 4: Liceo Francés - CRC"));
        assertEquals(EventSport.Kind.OTROS,
                EventSport.classify("Golf: Open de España · Jornada 3"));
        assertEquals(EventSport.Kind.FUTBOL,
                EventSport.classify("NFL: Baltimore Ravens - Cincinnati Bengals"));
    }

    @Test
    public void aLongerNameBeatsAWordItContains() {
        // "Premier Padel" was reading as football because "premier" is a football word.
        assertEquals(EventSport.Kind.OTROS,
                EventSport.classify("Premier Padel. Milán T2026 · Final Femenina"));
    }

    @Test
    public void aSportNameBeatsACompetitionNameEvenWhenTheCompetitionIsLonger() {
        // All three were on screen classified wrongly; the titles are copied from the emulator.
        assertEquals(EventSport.Kind.TENIS, EventSport.classify(
                "Tennis: ATP 250 · Finale, Grand Prix Auvergne-Rhone-Alpes in Lyon (F), Finale"));
        assertEquals(EventSport.Kind.OTROS, EventSport.classify(
                "DP World Tour T25/26 · Genesis Championship. Jornada 4. Parte 2"));
        assertEquals(EventSport.Kind.OTROS, EventSport.classify(
                "DIRECTO Dificultad - Finales T2026 · Copa del mundo de escalada - Santiago de Chile"));
    }

    @Test
    public void aCompetitionNameStillClassifiesWhenNothingElseDoes() {
        assertEquals(EventSport.Kind.MOTOR, EventSport.classify("GP de Estados Unidos · Previo"));
        assertEquals(EventSport.Kind.FUTBOL, EventSport.classify("Jornada 10: Barcelona - Real Madrid"));
    }

    @Test
    public void everyKindHasALabelForTheFilter() {
        for (EventSport.Kind kind : EventSport.Kind.values()) {
            org.junit.Assert.assertFalse(kind.label().trim().isEmpty());
        }
    }
}
