package com.bebebe.agent.core;

import com.bebebe.agent.memory.DialogMessage;
import com.bebebe.agent.memory.DialogSession;
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
                "entities", Map.of("type", "array", "items", Map.of("type", "string")),
                "replaces", Map.of("type", "array", "items", Map.of("type", "integer")),
                "keywords", Map.of("type", "array", "items", Map.of("type", "string"))));
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

                keywords -- 3-6 other words somebody might use when asking about this fact later,
                in Russian, one word each, NOT repeating the words already in the text. Recall is
                by words: "Саша не ест мясо" is found by "мясо" and missed entirely by
                "вегетарианец", "питание", "еда" -- which is how a question gets asked in real
                life. Write the synonyms, the general category, and the word for the thing itself:
                  "Саша не ест мясо"            -> ["вегетарианец", "питание", "еда", "ужин"]
                  "Марина боится собак"         -> ["животные", "щенок", "страх", "фобия"]
                  "У пользователя аллергия на орехи" -> ["аллергия", "здоровье", "еда", "нельзя"]
                Do not write words that are already in the text, and do not pad the list.

                replaces -- the numbers of already known facts that this one makes obsolete. Use it
                when what you are writing CONTRADICTS or SUPERSEDES something in the list of known
                facts below: moved to another city, changed jobs, quit smoking, an agreement moved
                to another day. The old fact is not deleted -- it stops being current, and the
                history of what was true and when stays readable. Do NOT use replaces for a detail
                that merely adds to the old fact: "любит чай" and "любит чай без сахара" are both
                true, and the second one alone would lose the first.

                Do not record: one-off questions ("how much disk space"), retellings of the agent's
                answers, weather, pleasantries. If there is nothing to remember, both arrays are empty.
                When in doubt whether something is worth remembering -- write it down. A fact nobody
                needs costs one line; a fact that was not written down is lost for good.
                Formulate facts briefly, in the third person, in Russian.
                """;
    }

    public static String extractionUserPrompt(List<Entity> known, List<DialogMessage> messages) {
        return extractionUserPrompt(known, List.of(), messages);
    }

    /**
     * @param knownFacts what is already remembered around this conversation. Without it the model
     *                   cannot mark anything as replaced -- it would be contradicting facts it has
     *                   never seen -- and contradictions simply piled up next to each other.
     */
    public static String extractionUserPrompt(List<Entity> known, List<Fact> knownFacts,
                                              List<DialogMessage> messages) {
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

        if (!knownFacts.isEmpty()) {
            sb.append("Already remembered (use the numbers in \"replaces\" when something below "
                    + "is made obsolete by the conversation):\n");
            for (Fact fact : knownFacts) {
                sb.append("  ").append(fact.describeForModel()).append('\n');
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
        return contextBlock(mentioned, factsByEntity, aboutUser, procedures, recalled, List.of());
    }

    public static String contextBlock(List<Entity> mentioned,
                                      Map<Entity, List<Fact>> factsByEntity,
                                      List<Fact> aboutUser,
                                      List<Fact> procedures,
                                      List<Fact> recalled,
                                      List<DialogSession> episodes) {
        return contextBlock(mentioned, factsByEntity, aboutUser, procedures, recalled, episodes, Map.of());
    }

    /**
     * Everything remembered that is worth showing for this one message.
     *
     * <p>Each fact carries its number, and the block ends by asking for those numbers back. That
     * is the whole of the feedback loop: until the model said which facts it had leaned on, there
     * was no way to tell a fact that earns its place in the prompt from one that has been riding
     * along unread for months, and the ranking weights were guesses that nothing could check.
     */
    public static String contextBlock(List<Entity> mentioned,
                                      Map<Entity, List<Fact>> factsByEntity,
                                      List<Fact> aboutUser,
                                      List<Fact> procedures,
                                      List<Fact> recalled,
                                      List<DialogSession> episodes,
                                      Map<Entity, List<Fact>> related) {
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

        if (!related.isEmpty()) {

            // One hop across a shared fact. Offered as a connection, not as the subject: nobody
            // asked about these people, they simply turned up in the same fact as somebody who
            // was asked about.
            sb.append("\nConnected to them:\n");
            related.forEach((entity, facts) -> {
                sb.append("  ").append(entity.describeForModel()).append('\n');
                facts.forEach(fact -> sb.append("    ").append(fact.describeForModel()).append('\n'));
            });
        }

        if (!episodes.isEmpty()) {

            // What is left of conversations that are over. Their logs stopped being context when
            // they closed, and without this the agent's world began with the current session.
            sb.append("\nRecent conversations (already finished):\n");
            for (DialogSession episode : episodes) {
                sb.append("  ").append(describeEpisode(episode)).append('\n');
            }
        }

        if (sb.length() > 0) {
            sb.append("\nUse this when appropriate; do not retell it needlessly. Facts marked "
                    + "[подтверждено] or [со слов пользователя] are reliable -- the user said or "
                    + "checked them; the rest were picked out of a conversation by you and may "
                    + "have been understood wrongly, so lean on them but do not quote them back "
                    + "as something the user definitely said. The #N numbers are internal: never "
                    + "show them to the user, but list in \"used_facts\" the numbers of the facts "
                    + "you actually relied on for your answer.\n");
        }
        return sb.toString();
    }

    private static String describeEpisode(DialogSession session) {
        String when = session.endedAt() == null ? "" : session.endedAt().toString().substring(0, 10) + ": ";
        return "• " + when + session.summary().replace("\n", " / ");
    }

    /**
     * What a finished conversation leaves behind.
     *
     * <p>Kept deliberately short. This is not a transcript -- the transcript is still in the
     * database and can be read by hand. It is what the agent should carry into next week: what was
     * discussed and what was left hanging, so that "мы же вчера про это говорили" has an answer
     * and a dropped thread can be picked up instead of quietly disappearing.
     */
    public static Map<String, Object> summarySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
                "summary", Map.of("type", "string"),
                "open", Map.of("type", "array", "items", Map.of("type", "string"))));
        schema.put("required", List.of("summary"));
        schema.put("additionalProperties", false);
        return schema;
    }

    public static String summarySystemPrompt() {
        return """
                You are the memory module of a personal agent. A conversation between the user and
                the agent has ended. Write down what is worth carrying forward, and answer ONLY
                with JSON:
                {"summary": "2-4 sentences: what the conversation was about and what came of it",
                 "open": ["what was left unfinished, one line each"]}

                summary -- in Russian, in the third person, concrete: what the user wanted, what was
                done, how it ended. Names, paths and numbers that came up are worth keeping; small
                talk is not. Do not retell the dialogue turn by turn and do not repeat facts that
                belong in long-term memory ("Сашу зовут Саша") -- those are extracted separately.
                open -- only what genuinely hangs: a promise not yet kept, a question left
                unanswered, work interrupted halfway. An empty array when the conversation is closed.
                If there is nothing worth remembering at all, summary is an empty string.
                """;
    }

    public static String summaryUserPrompt(List<DialogMessage> messages) {
        StringBuilder sb = new StringBuilder("Conversation:\n");
        for (DialogMessage message : messages) {
            sb.append(message.role() == com.bebebe.agent.memory.MessageRole.USER ? "User: " : "Agent: ")
                    .append(message.text().strip()).append('\n');
        }
        return sb.toString();
    }
}
