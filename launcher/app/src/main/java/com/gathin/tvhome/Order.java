package com.gathin.tvhome;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner's own tile order, set from the tile menu (Move, Add to dock, Remove from dock) and kept in the
 * launcher's preferences, so it survives reboots, updates and app installs (upstream 33ca9770 keeps it in a file).
 *
 * Saved tiles come first in this order; apps not in it (new installs, an app shown again) follow by the usual rule
 * (dock_apps, cards, alphabetical) and uninstalled ones simply drop out. It also records the dock_apps it was made
 * under: when dock_apps changes (a rebuild or a pushed theme) the saved order is ignored, so the new dock takes
 * effect until the owner moves something again. Settings menu, Reset order, forgets it.
 *
 * Format, text: {@code tvhome-order 1}, then {@code dock <comma-separated dock_apps>}, then one tile key
 * (component, flattened short) per line.
 */
final class Order {

    static final String PREF = "tile_order";
    private static final String MAGIC = "tvhome-order 1";
    private static final String DOCK = "dock ";

    /** dock_apps the order was saved under, comma-joined (package names cannot contain commas). */
    final String dock;
    final List<String> keys;

    Order(String dock, List<String> keys) {
        this.dock = dock;
        this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
    }

    static String dockKey(List<String> dockApps) {
        return String.join(",", dockApps);
    }

    static String encode(Order order) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n').append(DOCK).append(order.dock).append('\n');
        for (String k : order.keys) sb.append(k).append('\n');
        return sb.toString();
    }

    /** The order in this text, or null when it is not one (wrong header, a blank or duplicate key). */
    static Order decode(String text) {
        if (text == null) return null;
        String[] lines = text.split("\n", -1);
        if (lines.length < 2 || !lines[0].equals(MAGIC) || !lines[1].startsWith(DOCK)) return null;
        List<String> keys = new ArrayList<>();
        for (int i = 2; i < lines.length; i++) {
            String k = lines[i];
            if (k.isEmpty()) {
                if (i == lines.length - 1) break;   // the trailing newline
                return null;
            }
            if (keys.contains(k)) return null;
            keys.add(k);
        }
        return new Order(lines[1].substring(DOCK.length()), keys);
    }

    /**
     * Rank of each saved key (0 = first) when the order still applies to these dock_apps, else an empty map.
     * Tiles without a rank sort after every ranked one.
     */
    static Map<String, Integer> ranks(Order order, List<String> dockApps) {
        Map<String, Integer> r = new HashMap<>();
        if (order == null || !order.dock.equals(dockKey(dockApps))) return r;
        for (int i = 0; i < order.keys.size(); i++) r.put(order.keys.get(i), i);
        return r;
    }

    /** Sort step for two tiles: saved ones first in saved order, then 0 to fall through to the usual rule. */
    static int compare(Map<String, Integer> ranks, String keyA, String keyB) {
        Integer a = ranks.get(keyA), b = ranks.get(keyB);
        if (a == null && b == null) return 0;
        if (a == null) return 1;
        if (b == null) return -1;
        return Integer.compare(a, b);
    }

    // ---- preferences -----------------------------------------------------------------------------

    /** The saved order, or null when there is none or it does not decode (then it is ignored). */
    static Order load(SharedPreferences p) {
        return decode(p.getString(PREF, null));
    }

    static void save(SharedPreferences p, Order order) {
        p.edit().putString(PREF, encode(order)).apply();
    }

    /** A saved order that still applies to these dock_apps. */
    static boolean applies(SharedPreferences p, List<String> dockApps) {
        return !ranks(load(p), dockApps).isEmpty();
    }

    static void clear(SharedPreferences p) {
        p.edit().remove(PREF).apply();
    }
}
