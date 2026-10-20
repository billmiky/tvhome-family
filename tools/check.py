#!/usr/bin/env python3
"""What a firmware update undid, compared with the list that was applied (#359).

    check.py <debloat list>                  report the drift; exit 1 when there is any
    check.py --fix <debloat list> [launcher] re-disable the listed packages that came back and put
                                             the launcher back on Home

A firmware update re-enables listed packages, hands Home back to the stock launcher and adds new
preloads, and nothing in this repo notices: nobody runs disable.sh again, and restore.sh would undo
the cleanup instead of redoing it. This command compares the TV with the list and exits 1 when the
TV drifted, so it fits a cron job; --fix applies the list again without touching the recovery
record (the packages were already recorded before the first run, so undo still means undo).

It reads the list, not the record: the record holds what the TV looked like *before* cleanup, which
is what restore needs, while the target here is the list itself.

Exit codes: 0 in sync (or fixed), 1 drift (or a fix that did not take), 2 the TV could not be read.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import recovery  # noqa: E402  (needs the tools/ directory on the path first)

# Same three special cases as disable.sh: these answer HOME but they are not launchers, so Home
# landing on one of them is the crash loop the Home guard exists to prevent, not a working Home.
NOT_LAUNCHERS = {"com.google.android.tungsten.setupwraith", "com.android.tv.settings",
                 "com.android.settings", "com.android.provision"}
HOME_QUERY = ("cmd package resolve-activity --brief -a android.intent.action.MAIN "
              "-c android.intent.category.HOME")
CLAIMS_QUERY = ("cmd package query-activities --brief -a android.intent.action.MAIN "
                "-c android.intent.category.HOME")


def home_of(component):
    return component.split("/", 1)[0] if component else None


def home_activities(dev):
    """Every HOME activity the TV can start, as package/activity, in the TV's order."""
    found = []
    for line in recovery.shell(dev, CLAIMS_QUERY).splitlines():
        candidate = line.strip()
        if recovery.HOME_RE.fullmatch(candidate):
            found.append(candidate)
    return found


def launcher_to_use(dev, listed, preferred=None):
    """The HOME activity --fix should switch to: asked for, or the only launcher the list keeps.

    After a firmware update both the stock launcher and the new one answer HOME. Every candidate
    that the list disables is a launcher this run is about to take Home away from, so what is left
    is the one the owner installed.
    """
    if preferred:
        if not recovery.PACKAGE_RE.fullmatch(preferred):
            raise SystemExit(f"not a package name: {preferred!r}")
        match = [c for c in home_activities(dev) if home_of(c) == preferred]
        if not match:
            raise SystemExit(f"{preferred} has no HOME activity on this TV: is the launcher "
                             f"installed? (launcher/build-and-install.sh)")
        return match[0]
    candidates = [c for c in home_activities(dev)
                  if home_of(c) not in listed and home_of(c) not in NOT_LAUNCHERS]
    if not candidates:
        return None
    own = [c for c in candidates if home_of(c) == recovery.CUSTOM_LAUNCHER]
    return own[0] if own else candidates[0]


def inspect(dev, listed):
    installed, disabled = recovery.query_packages(dev)
    home = recovery.query_home(dev)
    back_on = [name for name in listed if name in installed and name not in disabled]
    gone = [name for name in listed if name not in installed]
    home_drifted = home is None or home_of(home) in set(listed)
    return installed, disabled, home, back_on, gone, home_drifted


def report(list_path, listed, home, back_on, gone, home_drifted):
    print(f"checking {list_path} ({len(listed)} packages) on {os.environ.get('TV_HOST', '?')}")
    if back_on:
        print(f"  back on ({len(back_on)}): {', '.join(back_on)}")
    if home_drifted:
        print(f"  Home is {home or 'nothing (no component answers HOME)'}"
              + (", which this list disables" if home else ""))
    if gone:
        # Not drift: the update may have removed an app for good, and there is nothing to disable.
        print(f"  not installed any more ({len(gone)}): {', '.join(gone)}")
    drift = len(back_on) + (1 if home_drifted else 0)
    if not drift:
        print(f"nothing drifted: {len(listed) - len(gone)} listed packages are still disabled and "
              f"Home is {home}")
        return 0
    print(f"drift: {drift} of {len(listed)}; re-apply the list with --fix, or run "
          f"tools/disable.sh and the install script by hand")
    return 1


def fix(dev, listed, back_on, home_drifted, preferred):
    failed = False
    # Home first: on Google TV a disabled stock launcher with nothing to replace it is the crash
    # loop the Home guard exists to prevent, so a Home that cannot be fixed stops the run before
    # anything is disabled.
    if home_drifted:
        target = launcher_to_use(dev, listed, preferred)
        if not target:
            print("Home: nothing to switch to: the launcher is not installed, or nothing but the "
                  "packages this list disables answers HOME. Run launcher/build-and-install.sh, "
                  "then check again; nothing was changed on the TV.")
            return 1
        recovery.shell(dev, f"cmd package set-home-activity --user 0 {target}")
        home = recovery.query_home(dev)
        if home != target:
            print(f"Home: asked for {target} but the TV still resolves {home or 'nothing'}; "
                  f"reboot it and check again")
            failed = True
        else:
            print(f"Home: set to {target}")
    if back_on:
        for name in back_on:
            recovery.shell(dev, f"pm disable-user --user 0 {name}")
        # Read the TV back instead of trusting the command's silence: a package the firmware pins
        # stays enabled and the run has to say so.
        _, disabled = recovery.query_packages(dev)
        still_on = [name for name in back_on if name not in disabled]
        if still_on:
            failed = True
            print(f"still enabled after --fix: {', '.join(still_on)} (the firmware may pin them; "
                  f"re-run the list by hand after the next reboot)")
        else:
            print(f"disabled {len(back_on)} package(s) the update had turned back on")
    return 1 if failed else 0


def main():
    args = sys.argv[1:]
    wanted = "--fix" in args
    args = [a for a in args if a != "--fix"]
    if not 1 <= len(args) <= 2:
        sys.stderr.write("usage: check.py [--fix] <debloat list> [launcher package]\n")
        return 2
    list_path = args[0]
    preferred = args[1] if len(args) == 2 else None
    # read_list validates every name before anything is sent to the TV: its entries end up in the
    # TV's shell, and a list edited by hand since then is not to be trusted.
    listed = recovery.read_list(list_path)

    try:
        dev = recovery.connect()
    except (OSError, ConnectionError) as e:
        sys.stderr.write(f"could not reach the TV ({e}). Check it is on and TV_HOST is right "
                         f"(tools/venv/bin/python -I tools/tv.py --probe).\n")
        return 2
    try:
        installed, disabled, home, back_on, gone, home_drifted = inspect(dev, listed)
        code = report(list_path, listed, home, back_on, gone, home_drifted)
        if code and wanted:
            code = fix(dev, listed, back_on, home_drifted, preferred)
    finally:
        dev.close()
    return code


if __name__ == "__main__":
    sys.exit(main())
