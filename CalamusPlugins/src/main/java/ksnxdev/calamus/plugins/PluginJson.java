package ksnxdev.calamus.plugins;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.StringReader;

final class PluginJson {
    static void validate(String value, int limit) throws Exception {
        if (value == null || value.length() > limit) throw new SecurityException("JSON size limit exceeded");
        try (JsonReader reader = new JsonReader(new StringReader(value))) {
            reader.setLenient(false);
            int depth = 0, tokens = 0;
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                if (++tokens > 8192) throw new SecurityException("JSON token limit exceeded");
                switch (reader.peek()) {
                    case BEGIN_OBJECT: if (++depth > 16) throw new SecurityException("JSON nesting limit exceeded"); reader.beginObject(); break;
                    case BEGIN_ARRAY: if (++depth > 16) throw new SecurityException("JSON nesting limit exceeded"); reader.beginArray(); break;
                    case END_OBJECT: reader.endObject(); depth--; break;
                    case END_ARRAY: reader.endArray(); depth--; break;
                    case NAME: reader.nextName(); break;
                    case STRING: case NUMBER: reader.nextString(); break;
                    case BOOLEAN: reader.nextBoolean(); break;
                    case NULL: reader.nextNull(); break;
                    default: throw new SecurityException("Invalid JSON token");
                }
            }
            if (depth != 0) throw new SecurityException("Incomplete JSON packet");
        }
    }
}
