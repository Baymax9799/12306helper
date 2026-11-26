package com.jactil.tickethelper.core.station;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationRegistryTest {

    private static final String JS = "var station_names ='"
            + "@bjb|北京北|VAP|beijingbei|bjb|0|0357|"
            + "@bji|北京|BJP|beijing|bj|1|0357|"
            + "@shh|上海|SHH|shanghai|sh|2|0079|"
            + "@gzq|广州南|IZQ|guangzhounan|gzn|3|0401|"
            + "@szb|深圳北|IOQ|shenzhenbei|szb|4|0401|';";

    @Test
    void parsesStationsFromJs() {
        StationRegistry registry = new StationRegistry();
        registry.loadFromJs(JS);

        assertEquals(5, registry.size());
        assertEquals("VAP", registry.codeOf("北京北").orElseThrow());
        assertEquals("IZQ", registry.codeOf("广州南").orElseThrow());
        assertEquals("深圳北", registry.nameOf("IOQ"));
        assertTrue(registry.knows("BJP"));
        assertFalse(registry.knows("XXX"));
    }

    @Test
    void searchPrefersExactName() {
        StationRegistry registry = new StationRegistry();
        registry.loadFromJs(JS);

        List<StationRegistry.Station> found = registry.search("北京", 10);

        assertEquals("北京", found.get(0).name());
        assertTrue(found.size() >= 2, "应同时命中北京北：" + found);
    }

    @Test
    void searchByPinyinPrefix() {
        StationRegistry registry = new StationRegistry();
        registry.loadFromJs(JS);

        List<StationRegistry.Station> found = registry.search("shenzhen", 10);

        assertEquals(1, found.size());
        assertEquals("IOQ", found.get(0).code());
    }

    @Test
    void unknownCodeFallsBackToItself() {
        StationRegistry registry = new StationRegistry();
        registry.loadFromJs(JS);

        assertEquals("ZZZ", registry.nameOf("ZZZ"));
    }

    @Test
    void badInputDoesNotWipeExistingData() {
        StationRegistry registry = new StationRegistry();
        registry.loadFromJs(JS);
        registry.loadFromJs("var station_names ='';");

        assertEquals(5, registry.size());
    }
}
