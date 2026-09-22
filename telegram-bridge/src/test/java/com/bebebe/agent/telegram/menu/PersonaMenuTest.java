package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.memory.MemoryStore;
import com.bebebe.agent.memory.Persona;
import com.bebebe.agent.memory.PersonaStore;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.TestLibrary;
import com.bebebe.agent.telegram.input.InputOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonaMenuTest {

    private static final String CHAT = "TELEGRAM:1";

    @TempDir
    Path temp;

    private ScriptLibrary library;
    private MemoryStore memory;
    private PersonaStore personas;
    private MenuController controller;

    @BeforeEach
    void setUp() {
        library = TestLibrary.inDirectory(temp);
        memory = TestLibrary.memoryIn(temp);
        personas = new PersonaStore(temp.resolve("personas.db"));
        controller = new MenuController(new AgentSwitch(false), AppSettings.from(AppConfig.fromToml("")),
                List::of, library, TestLibrary.NO_CONFIRM, memory, TestLibrary.NO_MEMORY_ACTIONS);
        controller.attachPersonas(personas);
    }

    @AfterEach
    void tearDown() {
        personas.close();
        library.close();
        memory.close();
    }

    private static String keyboard(MenuScreen screen) {
        return screen.keyboard().inlineKeyboard().toString();
    }

    private MenuResponse press(CallbackData data) {
        return controller.handle(data, CHAT);
    }

    @Test
    void listMarksActiveAndButtonMakesActive() {
        Persona pirate = personas.create("Пират", "Арр");

        MenuScreen list = controller.screenFor(MenuSection.PERSONAS);
        assertTrue(list.text().contains("Active: <b>Default</b>"), list.text());
        assertTrue(keyboard(list).contains("✅ Default"), keyboard(list));
        assertTrue(keyboard(list).contains(CallbackData.personaActivate(pirate.id()).encode()));

        MenuResponse response = press(CallbackData.personaActivate(pirate.id()));

        assertEquals("Active: Пират", response.toast());
        assertTrue(personas.byId(pirate.id()).orElseThrow().active());
        assertTrue(keyboard(response.screen()).contains("✅ Пират"));
    }

    @Test
    void creationViaPendingNameInput() {
        MenuResponse await = press(CallbackData.personaCreate());

        InputOutcome outcome = await.inputRequest().handler().accept("Учитель");

        assertTrue(outcome.accepted());
        Persona created = personas.all().stream().filter(p -> p.name().equals("Учитель")).findFirst().orElseThrow();
        assertFalse(created.active(), "a new one does not become active by itself");
        assertEquals(CallbackData.personaView(created.id()).encode(), outcome.returnTo().encode());
    }

    @Test
    void emptyNameIsRejectedAndModeStays() {
        InputOutcome outcome = press(CallbackData.personaCreate()).inputRequest().handler().accept("   ");

        assertFalse(outcome.accepted());
        assertEquals(1, personas.all().size());
    }

    @Test
    void instructionTextIsSentInNextMessage() {
        Persona p = personas.create("Учитель", "");

        MenuResponse await = press(CallbackData.personaEdit(p.id()));
        assertTrue(await.screen().text().contains("Send the instruction text"), await.screen().text());
        InputOutcome outcome = await.inputRequest().handler().accept("Объясняй как школьнику, с примерами.");

        assertTrue(outcome.accepted());
        assertEquals("Объясняй как школьнику, с примерами.", personas.byId(p.id()).orElseThrow().prompt());
        assertTrue(press(CallbackData.personaView(p.id())).screen().text().contains("как школьнику"));
    }

    @Test
    void minusClearsInstruction() {
        Persona p = personas.create("Учитель", "старое");

        press(CallbackData.personaEdit(p.id())).inputRequest().handler().accept("-");

        assertEquals("", personas.byId(p.id()).orElseThrow().prompt());
    }

    @Test
    void deletionRequiresConfirmationAndLastCannotBeDeleted() {
        Persona p = personas.create("Лишняя", "");

        MenuScreen confirm = press(CallbackData.personaDelete(p.id(), false)).screen();
        assertTrue(confirm.text().contains("Delete persona «Лишняя»"), confirm.text());
        assertEquals(2, personas.all().size());

        assertEquals("Deleted: Лишняя", press(CallbackData.personaDelete(p.id(), true)).toast());
        assertEquals(1, personas.all().size());

        long last = personas.all().getFirst().id();
        MenuResponse refused = press(CallbackData.personaDelete(last, true));
        assertTrue(refused.toast().contains("last persona"), refused.toast());
        assertEquals(1, personas.all().size());
    }

    @Test
    void activeCardHasNoMakeActiveButton() {
        long active = personas.active().orElseThrow().id();

        MenuScreen screen = press(CallbackData.personaView(active)).screen();

        assertFalse(keyboard(screen).contains(CallbackData.personaActivate(active).encode()));
        assertTrue(screen.text().contains("active"));
    }

    @Test
    void withoutStoreSectionExplainsWhy() {
        MenuController bare = new MenuController(new AgentSwitch(false), AppSettings.from(AppConfig.fromToml("")),
                List::of, library, TestLibrary.NO_CONFIRM, memory, TestLibrary.NO_MEMORY_ACTIONS);

        assertEquals("Personas are off", bare.handle(CallbackData.personaCreate(), CHAT).toast());
    }
}
