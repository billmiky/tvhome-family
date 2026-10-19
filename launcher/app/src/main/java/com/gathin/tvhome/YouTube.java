package com.gathin.tvhome;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * YouTube links for the occasion Play card: a strict video-id parser and the text used to search when an
 * occasion has no video of its own. Plain Java, so it is tested on the JVM.
 */
final class YouTube {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{11}");

    private YouTube() {
    }

    /**
     * The 11-character video id in a bare id, a youtube.com/watch?v=… link, a youtu.be/… link or a
     * youtube.com/shorts|embed|live/… link; "" for anything else. Only http(s) and YouTube's own hosts.
     */
    static String videoId(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.isEmpty() || t.length() > 200) return "";
        if (ID.matcher(t).matches()) return t;
        URI u;
        try {
            u = new URI(t.contains("://") ? t : "https://" + t);
        } catch (Exception e) {
            return "";
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) return "";
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        String id = "";
        if (host.equals("youtu.be")) {
            id = firstSegment(path);
        } else if (host.equals("youtube.com") || host.equals("www.youtube.com") || host.equals("m.youtube.com")
                || host.equals("music.youtube.com")) {
            if (path.equals("/watch")) {
                id = query(u.getRawQuery(), "v");
            } else {
                for (String prefix : new String[] {"/shorts/", "/embed/", "/live/"}) {
                    if (path.startsWith(prefix)) id = firstSegment(path.substring(prefix.length() - 1));
                }
            }
        }
        return ID.matcher(id).matches() ? id : "";
    }

    private static String firstSegment(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int slash = p.indexOf('/');
        return slash >= 0 ? p.substring(0, slash) : p;
    }

    private static String query(String raw, String key) {
        if (raw == null) return "";
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) return part.substring(eq + 1);
        }
        return "";
    }

    /** Whether an occasion's text is a birthday, in English or Vietnamese. */
    static boolean isBirthday(String text) {
        if (text == null) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("birthday") || t.contains("sinh nhật") || t.contains("sinh nhat");
    }

    /**
     * Text without emoji and other pictographs (and their joiners and variation selectors), spaces
     * collapsed: "Happy birthday, Phúc 🥳 🎁" → "Happy birthday, Phúc". Letters with diacritics stay.
     */
    static String withoutEmoji(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            boolean pictograph = type == Character.OTHER_SYMBOL || type == Character.SURROGATE
                    || cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0x1F3FB && cp <= 0x1F3FF)
                    || (cp >= 0xE0020 && cp <= 0xE007F);
            sb.appendCodePoint(pictograph ? ' ' : cp);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }
}
