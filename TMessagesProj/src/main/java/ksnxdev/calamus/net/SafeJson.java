package ksnxdev.calamus.net;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

public final class SafeJson {
    private SafeJson() {}
    public static String read(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maximum) throw new IOException("JSON size limit exceeded");
            output.write(buffer, 0, count);
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.toByteArray())).toString();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false); int depth = 0, tokens = 0;
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                if (++tokens > 8192) throw new IOException("JSON token limit exceeded");
                switch (reader.peek()) {
                    case BEGIN_OBJECT: if (++depth > 16) throw new IOException("JSON nesting limit exceeded"); reader.beginObject(); break;
                    case BEGIN_ARRAY: if (++depth > 16) throw new IOException("JSON nesting limit exceeded"); reader.beginArray(); break;
                    case END_OBJECT: reader.endObject(); depth--; break;
                    case END_ARRAY: reader.endArray(); depth--; break;
                    case NAME: reader.nextName(); break;
                    case STRING: case NUMBER: reader.nextString(); break;
                    case BOOLEAN: reader.nextBoolean(); break;
                    case NULL: reader.nextNull(); break;
                    default: throw new IOException("Invalid JSON");
                }
            }
        }
        return text;
    }
}
