package com.bebebe.agent.core;

import com.bebebe.agent.memory.DialogMessage;
import com.bebebe.agent.memory.Entity;
import com.bebebe.agent.memory.Fact;
import com.bebebe.agent.memory.FactCategory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MemoryProtocol {

    private MemoryProtocol() {
    }

    public static Map<String, Object> extractionSchema() {
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("type", "object");
        match.put("properties", Map.of(
                "entity_id", Map.of("type", "integer"),
                "confidence", Map.of("type", "string", "enum", List.of("high", "low", "none"))));
        match.put("required", List.of("entity_id", "confidence"));
        match.put("additionalProperties", false);

        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("type", "object");
        entity.put("properties", Map.of(
                "name", Map.of("type", "string"),
                "relation", Map.of("type", "string"),
                "aliases", Map.of("type", "array", "items", Map.of("type", "string")),
                "match", match));
        entity.put("required", List.of("name", "match"));
        entity.put("additionalProperties", false);

        Map<String, Object> fact = new LinkedHashMap<>();
        fact.put("type", "object");
        fact.put("properties", Map.of(
                "text", Map.of("type", "string"),
                "category", Map.of("type", "string",
                        "enum", java.util.Arrays.stream(FactCategory.values()).map(FactCategory::wireName).toList()),
                "date", Map.of("type", "string"),
                "entities", Map.of("type", "array", "items", Map.of("type", "string"))));
        fact.put("required", List.of("text", "category", "entities"));
        fact.put("additionalProperties", false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "entities", Map.of("type", "array", "items", entity),
                "facts", Map.of("type", "array", "items", fact)));
        schema.put("required", List.of("entities", "facts"));
        schema.put("additionalProperties", false);
        return schema;
    }

    public static String extractionSystemPrompt() {
        return """
                You are the memory module of a personal agent. You are given a fragment of the
                conversation between the user and the agent and the list of people the agent
                already knows.
                The task is to extract what is worth remembering for a long time and answer ONLY with JSON:
                {
                  "entities": [
                    {"name": "Саша", "relation": "друг", "aliases": ["Александр"],
                     "confidence": "high" | "low" | "none", "entity_id": 0}
                  ],
                  "facts": [
                    {"text": "Саша не ест мясо", "category": "preference",
                     "date": "", "entities": ["Саша"]}
                  ]
                }

                entities -- people (and other named entities: pets, projects) mentioned in the
                conversation. relation -- who they are to the user, in Russian and short:
                «друг», «коллега», «мама», «сосед». For each one, a match against the known people:
                  * confidence "high" + entity_id -- definitely a known person
                    (name or alias matched, context does not contradict);
                  * confidence "low" + entity_id -- looks like a known person, but not sure
                    (same name but different context; or only a name without details);
                  * confidence "none", entity_id 0 -- definitely new.
                Do not include the user or the agent themselves.

                facts -- statements that will be useful in a week or a year.
                Categories:
                  event        -- something happened (with a date if there is one)
                  preference   -- likes / dislikes / habits
                  agreement    -- what was agreed, what was promised
                  trait        -- a stable characteristic: job, city, family
                  procedure    -- a sequence of actions the user described as
                                  "do it this way": "before the stream -- scripts A, B, C".
                                  Record it in full, step by step.
                date -- "YYYY-MM-DD" or "" if not tied to a date.
                entities -- names from the entities list above; empty if the fact is about the
                user themselves.

                THE USER THEMSELVES IS NOT AN ENTITY, BUT FACTS ABOUT THEM ARE THE MOST VALUABLE
                ONES. Write them with entities: []. In particular, never skip:
                  * what they are called -- «Пользователя зовут Иван» -- the very first thing
                    worth having and the easiest to walk past, because a name is not a "fact";
                  * where they live and work, what they do;
                  * what they own and use: machine, OS, tools, languages, equipment;
                  * how they want to be talked to: length, tone, language, what irritates them;
                  * health, restrictions, allergies, things that must not be forgotten;
                  * paths, project names, addresses, accounts they mention in passing;
                  * plans and intentions: what they are going to do, what they are waiting for.

                Do not record: one-off questions ("how much disk space"), retellings of the agent's
                answers, weather, pleasantries. If there is nothing to remember, both arrays are empty.
                When in doubt whether something is worth remembering -- write it down. A fact nobody
                needs costs one line; a fact that was not written down is lost for good.
                Formulate facts briefly, in the third person, in Russian.
                """;
    }

    public static String extractionUserPrompt(List<Entity> known, List<DialogMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("Today: ").append(java.time.LocalDate.now()).append("\n\n");

        if (known.isEmpty()) {
            sb.append("No known people yet.\n\n");
        } else {
            sb.append("Known people:\n");
            for (Entity entity : known) {
                sb.append("  ").append(entity.describeForModel()).append('\n');
            }
            sb.append('\n');
        }

        sb.append("Conversation:\n");
        for (DialogMessage message : messages) {
            sb.append(message.role() == com.bebebe.agent.memory.MessageRole.USER ? "User: " : "Agent: ")
                    .append(message.text().strip()).append('\n');
        }
        return sb.toString();
    }

    public static String contextBlock(List<Entity> mentioned,
                                      Map<Entity, List<Fact>> factsByEntity,
                                      List<Fact> aboutUser,
                                      List<Fact> procedures,
                                      List<Fact> recalled) {
        StringBuilder sb = new StringBuilder();

        if (!mentioned.isEmpty()) {
            sb.append("\nThe message mentions people you know:\n");
            for (Entity entity : mentioned) {
                sb.append("  ").append(entity.describeForModel()).append('\n');
                List<Fact> facts = factsByEntity.getOrDefault(entity, List.of());
                if (facts.isEmpty()) {
                    sb.append("    (no facts yet)\n");
                }
                for (Fact fact : facts) {
                    sb.append("    ").append(fact.describeForModel()).append('\n');
                }
            }
        }

        if (!aboutUser.isEmpty()) {
            sb.append("\nWhat you know about the user:\n");
            for (Fact fact : aboutUser) {
                sb.append("  ").append(fact.describeForModel()).append('\n');
            }
        }

        if (!procedures.isEmpty()) {
            sb.append("\nProcedures the user asked to remember:\n");
            for (Fact fact : procedures) {
                sb.append("  ").append(fact.describeForModel()).append('\n');
            }
        }

        if (!recalled.isEmpty()) {

            // Found by words of the message rather than by a name in it, so it is offered as a
            // lead, not as an established part of the subject.
            sb.append("\nAlso remembered, possibly about this:\n");
            for (Fact fact : recalled) {
                sb.append("  ").append(fact.describeForModel()).append('\n');
            }
        }

        if (sb.length() > 0) {
            sb.append("\nUse this when appropriate; do not retell it needlessly.\n");
        }
        return sb.toString();
    }
}
