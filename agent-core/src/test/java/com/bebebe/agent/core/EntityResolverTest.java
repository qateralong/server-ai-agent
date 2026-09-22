package com.bebebe.agent.core;

import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.MemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityResolverTest {

    @TempDir
    Path temp;

    private MemoryStore memory;
    private EntityResolver resolver;

    @BeforeEach
    void setUp() {
        memory = TestMemory.inDirectory(temp);
        resolver = new EntityResolver(memory);
    }

    @AfterEach
    void tearDown() {
        memory.close();
    }

    private List<String> names(String text) {
        return resolver.resolve(text).stream().map(Entity::canonicalName).toList();
    }

    @Test
    void recognisesNameInAnyCase() {
        memory.addEntity("Саша", List.of(), "друг", "");

        assertEquals(List.of("Саша"), names("Позвони Саше вечером"));
        assertEquals(List.of("Саша"), names("Мы с Сашей ходили в кино"));
        assertEquals(List.of("Саша"), names("САША опять опоздал"));
    }

    @Test
    void recognisesByAlias() {
        memory.addEntity("Александр", List.of("Саша", "Шурик"), "", "");

        assertEquals(List.of("Александр"), names("Шурику надо передать ключи"));
    }

    @Test
    void severalPeopleInMentionOrder() {
        memory.addEntity("Петя", List.of(), "", "");
        memory.addEntity("Саша", List.of(), "", "");

        assertEquals(List.of("Саша", "Петя"), names("Саша и Петя придут вместе"));
    }

    @Test
    void shortNamesDoNotMatchByStem() {

        memory.addEntity("Ян", List.of(), "", "");

        assertTrue(names("в январе будет холодно").isEmpty());
        assertEquals(List.of("Ян"), names("Ян звонил"));
    }

    @Test
    void nameIsNotRecognisedInsideLongerWord() {

        memory.addEntity("Марк", List.of(), "", "");

        assertEquals(List.of("Марк"), names("отдай Марку"));
        assertTrue(names("отдел маркетинга").isEmpty());
    }

    @Test
    void emptyWithoutEntities() {
        assertTrue(names("Саша и Петя").isEmpty());
        assertTrue(names("").isEmpty());
    }

    @Test
    void doesNotDuplicateOneEntity() {
        memory.addEntity("Саша", List.of("Александр"), "", "");

        assertEquals(1, names("Саша, он же Александр").size());
    }
}
