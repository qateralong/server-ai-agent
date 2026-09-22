package com.bebebe.agent.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonaStoreTest {

    @TempDir
    Path temp;

    private PersonaStore store;

    @BeforeEach
    void setUp() {
        store = new PersonaStore(temp.resolve("personas.db"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private long activeId() {
        return store.active().orElseThrow().id();
    }

    @Test
    void firstRunHasExactlyOneActivePersona() {
        assertEquals(1, store.all().size());
        assertEquals(PersonaStore.DEFAULT_NAME, store.active().orElseThrow().name());
        assertTrue(store.promptBlock().contains("Persona «Default»"));
    }

    @Test
    void newPersonaDoesNotBecomeActiveByItself() {
        Persona pirate = store.create("Пират", "Говори как пират");

        assertFalse(pirate.active());
        assertEquals(PersonaStore.DEFAULT_NAME, store.active().orElseThrow().name());
    }

    @Test
    void exactlyOneActiveAndSwitchIsVisibleImmediately() {
        Persona pirate = store.create("Пират", "Говори как пират");

        assertTrue(store.activate(pirate.id()));

        assertEquals(1, store.all().stream().filter(Persona::active).count());
        assertEquals("Пират", store.active().orElseThrow().name());
        assertTrue(store.promptBlock().contains("Говори как пират"));
        assertFalse(store.promptBlock().contains("Default"));
    }

    @Test
    void emptyPromptGivesEmptyBlock() {
        Persona bare = store.create("Молчун", "");
        store.activate(bare.id());

        assertEquals("", store.promptBlock());
    }

    @Test
    void deletingActivePassesFlagToNeighbour() {
        Persona pirate = store.create("Пират", "x");
        store.activate(pirate.id());

        assertTrue(store.delete(pirate.id()));

        assertEquals(PersonaStore.DEFAULT_NAME, store.active().orElseThrow().name());
    }

    @Test
    void lastCannotBeDeleted() {
        assertThrows(IllegalStateException.class, () -> store.delete(activeId()));
        assertEquals(1, store.all().size());
    }

    @Test
    void editTextAndName() {
        Persona p = store.create("Черновик", "старый");

        store.updatePrompt(p.id(), "новый");
        store.update(p.id(), "Готово", "новый");

        Persona fresh = store.byId(p.id()).orElseThrow();
        assertEquals("Готово", fresh.name());
        assertEquals("новый", fresh.prompt());
    }

    @Test
    void emptyNameIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> store.create("  ", "x"));
    }

    @Test
    void listenersLearnAboutEveryChange() {
        List<String> events = new ArrayList<>();
        store.addListener(() -> events.add("!"));

        Persona p = store.create("А", "");
        store.activate(p.id());
        store.updatePrompt(p.id(), "б");
        store.delete(p.id());

        assertTrue(events.size() >= 4, events.toString());
    }

    @Test
    void reopeningKeepsActive() {
        Persona pirate = store.create("Пират", "x");
        store.activate(pirate.id());
        store.close();

        store = new PersonaStore(temp.resolve("personas.db"));

        assertEquals("Пират", store.active().orElseThrow().name());
        assertEquals(2, store.all().size());
    }
}
