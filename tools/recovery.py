#!/usr/bin/env python3
"""The state a debloat list is about to change, so that undo means undo (#239).

    recovery.py --save <debloat list>                read the TV and write the record
    recovery.py --commands <list> [stock launcher]   print a batch for tools/tv.py --batch that puts
                                                     the recorded states back

`tools/restore.sh` used to enable every package in the list, including ones the owner had already
disabled, and reset Home and the animation scales to fixed values instead of to what was there
before. Both scripts now go through this record: `disable.sh` writes it before the first command
reaches the TV, and `restore.sh` replays it.

Records live in ~/.cleanyourtv/, one per TV and list. The first one wins: a retry after a
half-applied list must not record the broken state as the original.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time

# Every name from the TV or from the list ends up inside a command run by the TV's shell, so nothing
# that could carry shell syntax is allowed through.
PACKAGE_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+")
HOME_RE = re.compile(r"[A-Za-z_][\w.]*/[\w.$]+")
SCALE_RE = re.compile(r"-?\d+(\.\d+)?")
SETTINGS = ("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
VERSION = 1


def read_list(path):
    """Package names from a debloat list, in file order.

    A BOM and CRLF from a Windows editor are dropped, as is a trailing `# note`; anything that is not
    a bare package name stops the run before the TV is touched.
    """
    text = Path(path).read_text(encoding="utf-8-sig", errors="replace")
    names = []
    for number, line in enumerate(text.splitlines(), 1):
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        name = line.split()[0]
        if not PACKAGE_RE.fullmatch(name):
            raise SystemExit(f"{path}:{number}: not a package name: {name!r}")
        names.append(name)
    if not names:
        raise SystemExit(f"{path}: no package names in this list")
    return names


def record_path(list_path, host):
    """One record per TV and list. The list is identified by its absolute path, so a copy of the
    same file somewhere else gets its own record."""
    tag = re.sub(r"[^A-Za-z0-9.-]", "_", host or "no-host")
    digest = hashlib.sha1(str(Path(list_path).resolve()).encode()).hexdigest()[:8]
    return Path.home() / ".cleanyourtv" / f"recovery-{tag}-{digest}.json"


def load_record(path):
    try:
        with open(path) as f:
            record = json.load(f)
    except (OSError, ValueError) as e:
        raise SystemExit(f"{path}: not a usable recovery record ({e}).\n"
                         "It is left where it is: move it aside deliberately, a retry must not "
                         "replace the state recorded before the first change.")
    if record.get("version") != VERSION or not isinstance(record.get("packages"), dict):
        raise SystemExit(f"{path}: unknown recovery record format; move it aside deliberately.")
    return record


def write_record(path, record):
    # Written next to its final name first: a crash halfway through must not leave a truncated record
    # that then blocks every retry as "already recorded".
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with open(tmp, "w") as f:
        json.dump(record, f, indent=2, sort_keys=True)
        f.write("\n")
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def shell(dev, command):
    return (dev.shell(command, read_timeout_s=120.0) or "").strip()


def query_packages(dev):
    installed = {line.split(":", 1)[1].strip()
                 for line in shell(dev, "pm list packages").splitlines()
                 if line.startswith("package:")}
    if not installed:
        raise SystemExit("the TV returned no packages, so nothing was recorded and nothing will be "
                         "disabled. Check the connection (tools/tv.py --probe) and try again.")
    disabled = {line.split(":", 1)[1].strip()
                for line in shell(dev, "pm list packages -d").splitlines()
                if line.startswith("package:")}
    return installed, disabled


def query_home(dev):
    answer = shell(dev, "cmd package resolve-activity --brief -a android.intent.action.MAIN -c "
                        "android.intent.category.HOME")
    candidate = answer.splitlines()[-1].strip() if answer else ""
    # Android answers "android" (the chooser) when nothing handles HOME; that is not a component and
    # must never be written into a set-home-activity command.
    return candidate if HOME_RE.fullmatch(candidate) else None


def connect():
    # Imported here so --commands works on a machine where adb_shell is not installed: printing the
    # restore batch needs no TV. tv.py itself exits with a readable message when TV_HOST is unset.
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    try:
        import tv
    except ImportError as e:
        raise SystemExit(f"cannot load tools/tv.py ({e}).\nrun: python3 -m venv tools/venv && "
                         "tools/venv/bin/pip install -r tools/requirements.txt")
    return tv.connect()


def save(list_path, host):
    path = record_path(list_path, host)
    if path.exists():
        record = load_record(path)
        print(f"keeping the recovery record from {record.get('created')}: {path}")
        print("nothing was recorded again: it holds the state from before the first run.")
        return 0

    packages = read_list(list_path)
    dev = connect()
    try:
        installed, disabled = query_packages(dev)
        home = query_home(dev)
        scales = {key: shell(dev, f"settings get global {key}") for key in SETTINGS}
    finally:
        dev.close()

    # The launcher that owns Home may not be in the list, but it is what undo has to put back.
    home_package = home.split("/", 1)[0] if home else None
    targets = packages + ([home_package] if home_package and home_package not in packages else [])
    record = {
        "version": VERSION,
        "created": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "host": host,
        "list": str(Path(list_path).resolve()),
        "home": home,
        "settings": scales,
        "packages": {
            name: ("absent" if name not in installed
                   else "disabled" if name in disabled
                   else "enabled")
            for name in targets
        },
    }
    write_record(path, record)

    already = sum(1 for state in record["packages"].values() if state == "disabled")
    print(f"recorded {len(record['packages'])} packages on {host} "
          f"({already} already disabled, left alone by undo) -> {path}")
    print(f"HOME before: {home or 'no component answered'}")
    print("undo with tools/restore.sh <same list>; keep this file, it is the only copy.")
    return 0


def restore_commands(list_path, stock, host):
    """The batch that puts the recorded states back, one command per line."""
    packages = read_list(list_path)
    if stock and not PACKAGE_RE.fullmatch(stock):
        raise SystemExit(f"not a package name: {stock!r}")
    path = record_path(list_path, host)
    commands = []

    if not path.exists():
        sys.stderr.write(
            f"no recovery record for this TV and list ({path}).\n"
            "Enabling every listed package and resetting Home and the animation scales to their\n"
            "defaults. Packages that were already disabled before cleanup will be enabled too:\n"
            "check the list before running this.\n")
        for name in packages:
            commands.append(f"pm enable {name}")
        if stock:
            commands.append(f"pm enable {stock}")
        commands.append(f"cmd package set-home-activity --user 0 $(cmd package resolve-activity "
                        f"--brief -a android.intent.action.MAIN -c android.intent.category.HOME "
                        f"{stock or 'com.google.android.tvlauncher'} | tail -n 1)")
        commands.extend(f"settings put global {key} 1.0" for key in SETTINGS)
        commands.append("pm list packages -d")
        return commands

    record = load_record(path)
    states = record["packages"]
    re_enable = [name for name, state in states.items() if state == "enabled"]
    for name in re_enable:
        commands.append(f"pm enable {name}")
    untouched = [(name, state) for name, state in states.items() if state != "enabled"]
    for name, state in untouched:
        sys.stderr.write(f"  leaving {name} alone ({state} when the record was taken)\n")

    home = record.get("home")
    if home:
        commands.append(f"cmd package set-home-activity --user 0 {home}")
    else:
        # Nothing handled HOME before cleanup (fresh TV, chooser only), so there is no component to
        # replay; ask the TV to resolve the stock launcher the way restore.sh always did.
        fallback = stock or "com.google.android.tvlauncher"
        commands.append(f"cmd package set-home-activity --user 0 $(cmd package resolve-activity "
                        f"--brief -a android.intent.action.MAIN -c android.intent.category.HOME "
                        f"{fallback} | tail -n 1)")
    for key in SETTINGS:
        value = str(record.get("settings", {}).get(key, "")).strip()
        if value == "null":
            commands.append(f"settings delete global {key}")
        elif SCALE_RE.fullmatch(value):
            commands.append(f"settings put global {key} {value}")
        else:
            sys.stderr.write(f"  skipping {key}: the record holds {value!r}, not a number\n")
    # The list is not in the record (it may have been edited since); packages added afterwards are
    # left as they are, which is what "leave the TV alone" means for them.
    edited = [name for name in packages if name not in states]
    if edited:
        sys.stderr.write(f"not in the record, leaving alone: {', '.join(edited)}\n")
    commands.append("pm list packages -d")

    sys.stderr.write(f"restoring the state recorded on {record.get('created')}: "
                     f"re-enabling {len(re_enable)}, leaving {len(untouched)} as they were.\n")
    return commands


def main():
    args = sys.argv[1:]
    host = os.environ.get("TV_HOST", "")
    if len(args) == 2 and args[0] == "--save":
        return save(args[1], host)
    if len(args) in (2, 3) and args[0] == "--commands":
        if not host:
            sys.stderr.write("Set TV_HOST to your TV's IP address, e.g. TV_HOST=192.168.1.50\n")
        stock = args[2] if len(args) == 3 else "com.google.android.tvlauncher"
        for command in restore_commands(args[1], stock, host):
            print(command)
        return 0
    sys.stderr.write("usage: recovery.py --save <debloat list>\n"
                     "       recovery.py --commands <debloat list> [stock launcher package]\n")
    return 2


if __name__ == "__main__":
    sys.exit(main())
