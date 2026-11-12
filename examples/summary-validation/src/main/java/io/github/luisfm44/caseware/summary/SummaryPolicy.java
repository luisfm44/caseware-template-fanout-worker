package io.github.luisfm44.caseware.summary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/** Compares the approved pair and controls what may be rendered and grouped. */
final class SummaryPolicy {

    private static final String SIGNOFF_PATH = "/inventory/signoffRequired";
    private static final String EXCEPTION_PATH = "/inventory/exceptionReason";

    private final ObjectReader groupingReader;

    SummaryPolicy(ObjectMapper mapper) {
        this.groupingReader = mapper.reader()
                .with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                        DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    List<Change> changes(JsonNode before, JsonNode after) {
        var changes = new ArrayList<Change>();
        compare("", before, after, changes);
        changes.sort(Comparator.comparing(Change::id));
        return List.copyOf(changes);
    }

    private static void compare(String path, JsonNode before, JsonNode after, List<Change> changes) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (before != null && after != null && before.isObject() && after.isObject()) {
            var names = new TreeSet<String>();
            before.properties().forEach(field -> names.add(field.getKey()));
            after.properties().forEach(field -> names.add(field.getKey()));
            for (var name : names) {
                var pointerSegment = name.replace("~", "~0").replace("/", "~1");
                compare(path + "/" + pointerSegment, before.get(name), after.get(name), changes);
            }
            return;
        }

        var id = switch (path) {
            case SIGNOFF_PATH -> "C17";
            case EXCEPTION_PATH -> "C18";
            default -> "unmapped:" + path;
        };
        var operation = before == null ? "ADDED" : after == null ? "REMOVED" : "REPLACED";
        changes.add(new Change(id, path, operation, copy(before), copy(after)));
    }

    private static JsonNode copy(JsonNode value) {
        return value == null ? null : value.deepCopy();
    }

    Optional<List<Sentence>> rules(List<Change> changes, String locale) {
        if (!"en-CA".equals(locale) && !"fr-CA".equals(locale)) {
            return Optional.empty();
        }

        var sentences = new ArrayList<Sentence>();
        boolean french = "fr-CA".equals(locale);
        for (var change : changes) {
            var text = wording(change, french);
            if (text == null) {
                return Optional.empty();
            }
            sentences.add(new Sentence(change, text, "inventory"));
        }
        return Optional.of(List.copyOf(sentences));
    }

    private static String wording(Change change, boolean french) {
        if (SIGNOFF_PATH.equals(change.path()) && "REPLACED".equals(change.operation())
                && change.before().isBoolean() && !change.before().booleanValue()
                && change.after().isBoolean() && change.after().booleanValue()) {
            return french ? "La validation de l’inventaire est désormais obligatoire."
                    : "Inventory sign-off is now required.";
        }
        if (EXCEPTION_PATH.equals(change.path()) && "ADDED".equals(change.operation())) {
            return french ? "L’inventaire comporte désormais un champ pour le motif d’une exception."
                    : "Inventory now has a field for the reason for an exception.";
        }
        return null;
    }

    Optional<List<Sentence>> grouped(String candidate, List<Sentence> rules) {
        if (candidate == null) {
            return Optional.empty();
        }

        JsonNode root;
        try {
            root = groupingReader.readTree(candidate);
        } catch (JacksonException malformed) {
            return Optional.empty();
        }
        if (root == null || !root.isObject() || root.size() != 1) {
            return Optional.empty();
        }
        var groups = root.get("groups");
        if (groups == null || !groups.isArray()) {
            return Optional.empty();
        }

        var remaining = new HashMap<String, Sentence>();
        for (var sentence : rules) {
            if (remaining.put(sentence.change().id(), sentence) != null) {
                return Optional.empty();
            }
        }
        var ordered = new ArrayList<Sentence>();
        for (var group : groups) {
            if (!group.isObject() || group.size() != 2) {
                return Optional.empty();
            }
            var topicNode = group.get("topic");
            var ids = group.get("ids");
            if (topicNode == null || !topicNode.isString() || ids == null || !ids.isArray() || ids.isEmpty()) {
                return Optional.empty();
            }
            var topic = topicNode.stringValue();
            for (var id : ids) {
                if (!id.isString()) {
                    return Optional.empty();
                }
                var sentence = remaining.remove(id.stringValue());
                if (sentence == null || !allowsTopic(sentence.change().id(), topic)) {
                    return Optional.empty();
                }
                ordered.add(new Sentence(sentence.change(), sentence.text(), topic));
            }
        }
        return remaining.isEmpty() ? Optional.of(List.copyOf(ordered)) : Optional.empty();
    }

    private static boolean allowsTopic(String id, String topic) {
        return switch (id) {
            case "C17" -> "inventory".equals(topic) || "review".equals(topic);
            case "C18" -> "inventory".equals(topic) || "exceptions".equals(topic);
            default -> false;
        };
    }

    record Change(String id, String path, String operation, JsonNode before, JsonNode after) {}

    record Sentence(Change change, String text, String topic) {}
}
