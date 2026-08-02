package io.github.martinkm.tandem;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single-line JSON object whose keys and values are all strings.
 *
 * <p>Deliberately not a general JSON library. The run log only ever needs a flat
 * map of strings, and that narrow shape is a hundred readable lines instead of a
 * dependency that would land in every consumer's classpath and clash with
 * whichever JSON library they already use.
 *
 * <p>The format is one object per line, which makes a log file appendable,
 * tailable, and readable by `jq` without Tandem being involved.
 */
final class JsonLine {

    private JsonLine() {}

    static String encode(Map<String, String> fields) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(out, entry.getKey());
            out.append(':');
            writeString(out, entry.getValue() == null ? "" : entry.getValue());
        }
        return out.append('}').toString();
    }

    static Map<String, String> decode(String line) {
        Map<String, String> out = new LinkedHashMap<>();
        String text = line.trim();
        if (text.length() < 2 || text.charAt(0) != '{' || text.charAt(text.length() - 1) != '}') {
            throw new TandemException("not a JSON object: " + line);
        }

        int i = 1;
        int end = text.length() - 1;
        while (i < end) {
            while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == ',')) {
                i++;
            }
            if (i >= end) {
                break;
            }
            if (text.charAt(i) != '"') {
                throw new TandemException("expected a quoted key at index " + i);
            }
            StringBuilder key = new StringBuilder();
            i = readString(text, i, key);

            while (i < end && text.charAt(i) == ' ') {
                i++;
            }
            if (i >= end || text.charAt(i) != ':') {
                throw new TandemException("expected ':' at index " + i);
            }
            i++;
            while (i < end && text.charAt(i) == ' ') {
                i++;
            }
            if (i >= end || text.charAt(i) != '"') {
                throw new TandemException("expected a quoted value at index " + i);
            }
            StringBuilder value = new StringBuilder();
            i = readString(text, i, value);

            out.put(key.toString(), value.toString());
        }
        return out;
    }

    /** Reads a quoted string at {@code start}, returning the index after the closing quote. */
    private static int readString(String text, int start, StringBuilder out) {
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
                if (i >= text.length()) {
                    throw new TandemException("unterminated escape");
                }
                char escaped = text.charAt(i);
                switch (escaped) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    case 'u':
                        if (i + 4 >= text.length()) {
                            throw new TandemException("truncated \\u escape");
                        }
                        out.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
                        i += 4;
                        break;
                    default:
                        out.append(escaped);
                        break;
                }
                i++;
            } else if (c == '"') {
                return i + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        throw new TandemException("unterminated string");
    }

    private static void writeString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                    break;
            }
        }
        out.append('"');
    }
}
