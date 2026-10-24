package com.gathin.tvhome;

import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Apps the owner took off Home on the TV (tile menu, Hide from Home; the App library's toggles), kept in the
 * launcher's preferences. They add to config.xml's hidden_apps, which stay hidden whatever the owner does on
 * the TV. Shown again from the Settings menu (Hidden apps) or the App library.
 */
final class HiddenApps {

    static final String PREF = "hidden_by_owner";

    private HiddenApps() {
    }

    /** The packages the owner hid (a copy: the preference's own set must not be changed). */
    static Set<String> get(SharedPreferences p) {
        return new HashSet<>(p.getStringSet(PREF, Collections.emptySet()));
    }

    static void set(SharedPreferences p, String pkg, boolean hidden) {
        p.edit().putStringSet(PREF, toggled(get(p), pkg, hidden)).apply();
    }

    /** owner with pkg added (hidden) or taken out. */
    static Set<String> toggled(Set<String> owner, String pkg, boolean hidden) {
        Set<String> out = new HashSet<>(owner);
        if (pkg == null || pkg.isEmpty()) return out;
        if (hidden) out.add(pkg);
        else out.remove(pkg);
        return out;
    }

    /** Everything hidden from Home: config.xml's hidden_apps and the owner's. */
    static Set<String> union(Collection<String> config, Collection<String> owner) {
        Set<String> all = new HashSet<>(config);
        all.addAll(owner);
        return all;
    }
}
