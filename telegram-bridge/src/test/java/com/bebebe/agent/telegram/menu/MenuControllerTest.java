package com.bebebe.agent.telegram.menu;

import com.bebebe.agent.config.AppConfig;
import com.bebebe.agent.config.AppSettings;
import com.bebebe.agent.core.AgentSwitch;
import com.bebebe.agent.script.library.ScriptLibrary;
import com.bebebe.agent.telegram.TestLibrary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MenuControllerTest {

    private final AgentSwitch agentSwitch = new AgentSwitch(false);
    private final AppSettings settings = AppSettings.from(AppConfig.fromToml("""
            [ollama]
            model = "gpt-oss:120b"
            [telegram]
            allowed_usernames = ["qateralong"]
            """));
    @TempDir
    Path temp;

    private ScriptLibrary library;
    private com.bebebe.agent.memory.MemoryStore memory;
    private MenuController controller;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        library = TestLibrary.inDirectory(temp);
        memory = TestLibrary.memoryIn(temp);
        controller = new MenuController(agentSwitch, settings,
                () -> java.util.List.of("gpt-oss:120b", "glm-5.3"), library, TestLibrary.NO_CONFIRM,
                memory, TestLibrary.NO_MEMORY_ACTIONS);
    }

    @AfterEach
    void tearDown() {
        library.close();
        memory.close();
    }

    @Test
    void rootMenuContainsAllSectionsAndClose() {
        MenuScreen root = controller.rootScreen();

        assertEquals(controller.visibleSections().size() + 1, root.keyboard().buttonCount());
        for (MenuSection section : controller.visibleSections()) {
            assertTrue(root.text() != null);
            assertTrue(containsCallback(root, CallbackData.section(section).encode()),
                    "No button for section " + section);
        }
        assertTrue(containsCallback(root, CallbackData.close().encode()));
    }

    @ParameterizedTest
    @EnumSource(MenuSection.class)
    void everySectionOpensAndHasBackAndClose(MenuSection section) {
        MenuResponse response = controller.handle(CallbackData.section(section));

        assertNotNull(response.screen());
        assertEquals(section, response.screen().section());
        assertTrue(containsCallback(response.screen(), CallbackData.root().encode()),
                "Section " + section + " has no Back button");
        assertTrue(containsCallback(response.screen(), CallbackData.close().encode()),
                "Section " + section + " has no Close button");
    }

    @Test
    void unimplementedSectionsAreMarkedAsStubs() {
        for (MenuSection section : MenuSection.values()) {
            MenuScreen screen = MenuRenderer.section(section, agentSwitch.state());
            if (section.isImplemented()) {
                assertFalse(screen.text().contains("not implemented"), section + " is declared implemented");
            } else {
                assertTrue(screen.text().contains("not implemented"), section + " must be a stub");
            }
        }
    }

    @Test
    void powerButtonEnablesAgent() {
        assertFalse(agentSwitch.isOn());

        MenuResponse response = controller.handle(CallbackData.power(true));

        assertTrue(agentSwitch.isOn());
        assertEquals("Agent switched on", response.toast());
        assertEquals(MenuSection.POWER, response.screen().section());
    }

    @Test
    void powerButtonDisablesAgent() {
        agentSwitch.turnOn();

        MenuResponse response = controller.handle(CallbackData.power(false));

        assertFalse(agentSwitch.isOn());
        assertEquals("Agent switched off", response.toast());
    }

    @Test
    void buttonCarriesDesiredStateNotToggle() {

        agentSwitch.turnOn();

        MenuResponse response = controller.handle(CallbackData.power(true));

        assertTrue(agentSwitch.isOn());
        assertEquals("Already in this state", response.toast());
    }

    @Test
    void powerButtonLabelDependsOnState() {
        assertTrue(MenuRenderer.power(agentSwitch.state()).keyboard()
                .inlineKeyboard().getFirst().getFirst().text().contains("Switch on"));

        agentSwitch.turnOn();

        assertTrue(MenuRenderer.power(agentSwitch.state()).keyboard()
                .inlineKeyboard().getFirst().getFirst().text().contains("Switch off"));
    }

    @Test
    void menuWorksWhenAgentIsOff() {

        assertFalse(agentSwitch.isOn());

        assertNotNull(controller.handle(CallbackData.root()).screen());
        assertNotNull(controller.handle(CallbackData.section(MenuSection.STATUS)).screen());
    }

    @Test
    void closingRemovesButtons() {
        MenuResponse response = controller.handle(CallbackData.close());

        assertEquals(0, response.screen().keyboard().buttonCount());
        assertTrue(response.screen().text().contains("/menu"));
    }

    @Test
    void backReturnsToRoot() {
        MenuResponse response = controller.handle(CallbackData.root());

        assertNull(response.screen().section());
        assertEquals(controller.visibleSections().size() + 1, response.screen().keyboard().buttonCount());
    }

    @Test
    void unknownButtonDoesNotBreakController() {
        assertEquals("Unknown button", controller.handle(CallbackData.of("wat", "x")).toast());
        assertEquals("No such section", controller.handle(CallbackData.of("menu", "nope")).toast());
        assertEquals("Unknown button", controller.handle(CallbackData.of("pwr", "set", "maybe")).toast());
        assertNull(controller.handle(CallbackData.of("wat", "x")).screen());
    }

    @Test
    void rootMenuShowsCurrentState() {
        assertTrue(MenuRenderer.root(agentSwitch.state()).text().contains("🔴 off"));

        agentSwitch.turnOn();
        assertTrue(MenuRenderer.root(agentSwitch.state()).text().contains("🟢 on"));
    }

    @Test
    void powerScreenIsMarkedAsStateDependent() {
        MenuScreen power = MenuRenderer.power(agentSwitch.state());
        assertTrue(power.reflectsAgentState());
        assertSame(MenuSection.POWER, power.section());
    }

    private static boolean containsCallback(MenuScreen screen, String callbackData) {
        return screen.keyboard().inlineKeyboard().stream()
                .flatMap(java.util.List::stream)
                .anyMatch(button -> callbackData.equals(button.callbackData()));
    }
}
