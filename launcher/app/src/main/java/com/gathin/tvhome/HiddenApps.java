package com.gathin.tvhome;

import android.content.SharedPreferences;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The owner's choice of apps on Home, made on the TV and kept in the launcher's preferences:
 * - hidden: apps taken off Home (tile menu, Hide from Home; Settings menu, Hidden apps; the App library);
 * - added: apps put on Home from the App library that would not be there by themselves: an app without a TV entry
 *   (a phone app), or one config.xml's hidden_apps leaves out.
 * An app is on Home when the owner has not hidden it and either added it or it is a TV app hidden_apps lets through
 * (upstream's LibraryApps.visible, a793048f). config.xml stays the default; the owner's choice wins over it.
 */
final class HiddenApps {

    static final String PREF = "hidden_by_owner";
    static final String PREF_ADDED = "added_by_owner";

    private HiddenApps() {
    }

    /** The packages the owner hid (a copy: the preference's own set must not be changed). */
    static Set<String> get(SharedPreferences p) {
        return new HashSet<>(p.getStringSet(PREF, Collections.emptySet()));
    }

    /** The packages the owner added from the App library (a copy). */
    static Set<String> added(SharedPreferences p) {
        return new HashSet<>(p.getStringSet(PREF_ADDED, Collections.emptySet()));
    }

    /**
     * Puts an app on Home or takes it off. tvApp: it has a TV (leanback) entry; configHidden: hidden_apps lists it.
     * Showing an app that would not be there by itself also adds it; hiding one drops it from the added set.
     */
    static void setOnHome(SharedPreferences p, String pkg, boolean onHome, boolean tvApp, boolean configHidden) {
        boolean byItself = tvApp && !configHidden;
        p.edit()
                .putStringSet(PREF, toggled(get(p), pkg, !onHome))
                .putStringSet(PREF_ADDED, toggled(added(p), pkg, onHome && !byItself))
                .apply();
    }

    /** Whether an app is on Home under these choices. */
    static boolean onHome(boolean tvApp, boolean configHidden, boolean ownerHidden, boolean ownerAdded) {
        return !ownerHidden && (ownerAdded || (tvApp && !configHidden));
    }

    /** owner with pkg added or taken out. */
    static Set<String> toggled(Set<String> owner, String pkg, boolean in) {
        Set<String> out = new HashSet<>(owner);
        if (pkg == null || pkg.isEmpty()) return out;
        if (in) out.add(pkg);
        else out.remove(pkg);
        return out;
    }

    /** Every package kept off Home: the owner's hidden ones and hidden_apps, less those the owner added back. */
    static Set<String> effective(Collection<String> config, Collection<String> ownerHidden, Collection<String> ownerAdded) {
        Set<String> all = new HashSet<>(config);
        all.removeAll(ownerAdded);
        all.addAll(ownerHidden);
        return all;
    }
}
