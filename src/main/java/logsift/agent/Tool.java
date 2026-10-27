package logsift.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One thing an agent can ask Logsift. Tools know nothing about how agents connect (MCP now,
 * others later): they take arguments and answer with text, which the {@link Workspace} masks
 * and cuts to size.
 */
public interface Tool {
    String name();

    /** A short title for people ("Log insights"). */
    String title();

    /** What it does and when to use it, for the agent's model. */
    String description();

    /** The arguments, as a JSON Schema object. */
    Map<String, Object> inputSchema();

    /** Whether it only reads (every tool, for now). */
    default boolean readOnly() {
        return true;
    }

    /** The answer, as text for the model. Throw {@link Failure} for an answer the agent should read. */
    String run(Args args, Workspace w) throws Exception;

    /** A mistake the agent can fix (a wrong path, a missing argument): its message goes back as is. */
    final class Failure extends Exception {
        public Failure(String message) {
            super(message);
        }
    }

    /** Builds an input schema. */
    final class Schema {
        private final Map<String, Object> props = new LinkedHashMap<>();
        private final List<String> required = new ArrayList<>();

        public Schema string(String name, String description, boolean req) {
            return prop(name, "string", description, req, null);
        }

        public Schema integer(String name, String description, boolean req) {
            return prop(name, "integer", description, req, null);
        }

        public Schema bool(String name, String description) {
            return prop(name, "boolean", description, false, null);
        }

        public Schema choice(String name, String description, boolean req, String... values) {
            return prop(name, "string", description, req, List.of(values));
        }

        private Schema prop(String name, String type, String description, boolean req, List<String> values) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("type", type);
            p.put("description", description);
            if (values != null) {
                p.put("enum", values);
            }
            props.put(name, p);
            if (req) {
                required.add(name);
            }
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "object");
            s.put("properties", props);
            if (!required.isEmpty()) {
                s.put("required", required);
            }
            s.put("additionalProperties", false);
            return s;
        }
    }

    /** The arguments of one call. */
    final class Args {
        private final Map<String, Object> map;

        public Args(Map<String, Object> map) {
            this.map = map == null ? Map.of() : map;
        }

        public boolean has(String name) {
            return map.get(name) != null;
        }

        public String string(String name) throws Failure {
            Object v = map.get(name);
            if (v == null || v.toString().isBlank()) {
                throw new Failure("Missing argument: " + name);
            }
            return v.toString();
        }

        public String string(String name, String or) {
            Object v = map.get(name);
            return v == null ? or : v.toString();
        }

        public long number(String name, long or, long min, long max) throws Failure {
            Object v = map.get(name);
            if (v == null) {
                return or;
            }
            try {
                double d = Double.parseDouble(v.toString());
                long n = (long) d;
                if (n < min || n > max) {
                    throw new Failure(name + " must be between " + min + " and " + max + ", not " + v);
                }
                return n;
            } catch (NumberFormatException e) {
                throw new Failure(name + " must be a number, not " + v);
            }
        }

        public boolean bool(String name, boolean or) {
            Object v = map.get(name);
            return v == null ? or : Boolean.parseBoolean(v.toString());
        }

        /** The arguments for the audit log: names and short values. */
        public String brief() {
            StringBuilder b = new StringBuilder();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String v = String.valueOf(e.getValue());
                if (v.length() > 80) {
                    v = v.substring(0, 80) + "…";
                }
                b.append(b.isEmpty() ? "" : " ").append(e.getKey()).append('=').append(v.replace('\n', ' '));
            }
            return b.toString();
        }
    }
}
