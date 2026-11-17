package ksnxdev.calamus.access;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class AccessList {
    public static final int MAX_BYTES = 256 * 1024;
    public static final int MAX_IDS = 10000;
    private AccessList() {}
    public static Set<Long> parse(String text) throws IOException {
        if (text.length() > MAX_BYTES) throw new IOException("Access list is too large");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (String line : text.split("\\r?\\n", -1)) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            if (!value.matches("[0-9]{1,19}")) throw new IOException("Access list must contain one positive ID per line");
            try {
                long id = Long.parseLong(value);
                if (id <= 0) throw new NumberFormatException();
                ids.add(id);
            } catch (NumberFormatException error) { throw new IOException("Invalid account ID"); }
            if (ids.size() > MAX_IDS) throw new IOException("Too many account IDs");
        }
        return Collections.unmodifiableSet(ids);
    }
    public static URI source(String value) throws IOException {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || !uri.getPath().contains("/-/raw/")) throw new IOException("Expected a public HTTPS GitLab raw URL");
            String query = uri.getRawQuery();
            if (query != null && !query.equals("inline=false") && !query.equals("ref_type=heads")) throw new IOException("Access tokens must not be embedded in the source URL");
            return uri;
        } catch (java.net.URISyntaxException error) { throw new IOException("Invalid access list URL", error); }
    }
}
