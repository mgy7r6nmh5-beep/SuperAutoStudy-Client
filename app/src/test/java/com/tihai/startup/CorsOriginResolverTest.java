package com.tihai.startup;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsOriginResolverTest {

    private final CorsOriginResolver resolver = new CorsOriginResolver();

    @Test
    void unconfiguredFallsBackToFourLocalOrigins() {
        CorsOriginSet set = resolver.resolve(null);
        assertEquals(CorsOriginSet.OriginSource.DEFAULT, set.getSource());
        assertEquals(Arrays.asList(
                "http://127.0.0.1:3030",
                "http://localhost:3030",
                "http://127.0.0.1:3080",
                "http://localhost:3080"), set.getOrigins());
    }

    @Test
    void emptyAndBlankOnlyAlsoFallback() {
        assertEquals(CorsOriginSet.OriginSource.DEFAULT, resolver.resolve(Collections.emptyList()).getSource());
        assertEquals(CorsOriginSet.OriginSource.DEFAULT, resolver.resolve(Arrays.asList("", "   ")).getSource());
    }

    @Test
    void explicitOriginsReplaceDefaults() {
        CorsOriginSet set = resolver.resolve(Arrays.asList("http://127.0.0.1:3080"));
        assertEquals(CorsOriginSet.OriginSource.EXPLICIT, set.getSource());
        assertEquals(Collections.singletonList("http://127.0.0.1:3080"), set.getOrigins());
    }

    @Test
    void duplicatesAreDroppedAndOrderKept() {
        CorsOriginSet set = resolver.resolve(Arrays.asList(
                " http://localhost:3080 ", "http://localhost:3080", "http://127.0.0.1:3030"));
        assertEquals(Arrays.asList("http://localhost:3080", "http://127.0.0.1:3030"), set.getOrigins());
    }

    @Test
    void invalidOriginsAreRejectedWithItemIndex() {
        CorsConfigException noScheme = assertThrows(CorsConfigException.class,
                () -> resolver.resolve(Collections.singletonList("127.0.0.1:3080")));
        assertTrue(noScheme.getMessage().contains("第 1 项"));
        assertEquals(CorsConfigException.ERROR_CODE, noScheme.getErrorCode());

        CorsConfigException withPath = assertThrows(CorsConfigException.class,
                () -> resolver.resolve(Collections.singletonList("http://127.0.0.1:3080/api")));
        assertTrue(withPath.getMessage().contains("第 1 项"));
    }

    @Test
    void wildcardIsRejectedBecauseCredentialsAreEnabled() {
        CorsConfigException ex = assertThrows(CorsConfigException.class,
                () -> resolver.resolve(new ArrayList<>(Collections.singletonList("*"))));
        assertEquals(CorsConfigException.ERROR_CODE, ex.getErrorCode());
        assertTrue(ex.getMessage().contains("通配符"));
    }

    @Test
    void returnedListIsImmutable() {
        CorsOriginSet set = resolver.resolve(Collections.singletonList("http://localhost:3080"));
        List<String> origins = set.getOrigins();
        assertThrows(UnsupportedOperationException.class, () -> origins.add("http://evil.example"));
    }
}