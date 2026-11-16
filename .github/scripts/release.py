#!/usr/bin/env python3
"""Janky Paper - publishes the server jars as a GitHub release (run by .github/workflows/release.yml).

Every Minecraft version in versions.txt is a project on main in versions/<mc>/. CI ("Patch and Build", build.yml) builds each line whose code changed and uploads its server jar to
the "ci-builds" pre-release as janky-paper-<minecraft version>.jar, labelled with the fingerprint of the code it was
built from (janky_lines.py; release assets, unlike Actions artifacts, don't count against the Actions storage
quota). This script works out what the next release holds and publishes it:

* First release, or releaseVersion in main's gradle.properties raised above the last release (1.0 -> 1.1, 2.0):
  a full release with exactly that number and a jar for every Minecraft version. A version whose code did not
  change since its last release ships that same jar again, renamed to the new number.
* Otherwise: only the Minecraft versions whose code changed since their last release, numbered one patch above
  the last release (1.0 -> 1.0.1 -> 1.0.2). Changes to docs, CI files or another version's folder do not count.
* A changed version is released only once ci-builds has a jar built from exactly its current code (CI uploads it
  only when the build passed). A full release waits until every version is ready. A patch release ships the ready
  ones; the others follow in a later release once their CI passes.

Each release carries release-manifest.json: for every Minecraft version, the main commit and folder its current jar
was built from, that code's fingerprint, and the release the jar was published in. The next run compares against it,
and the sync step (janky_lines.py sync) copies the released code to the version's mirror branch.

Environment: GITHUB_REPOSITORY (owner/name), GH_TOKEN (for gh); optional FULL_RELEASE=true (ship every version even
without a releaseVersion bump), DRY_RUN=true (print the plan, publish nothing).
Testing: GH_CLI (command used instead of `gh`), RELEASE_REMOTE (remote of main, '' = local branches).
"""
import json
import os
import re
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import janky_lines as lines_  # noqa: E402 - version lines, fingerprints, ci-builds jars

ROOT = lines_.ROOT
REPO = lines_.REPO
GH = lines_.GH
REMOTE = lines_.REMOTE
WORK = lines_.WORK.parent / "janky-release"
MANIFEST = lines_.MANIFEST
CI_BUILDS = lines_.CI_BUILDS
run, gh, log, summary = lines_.run, lines_.gh, lines_.log, lines_.summary
ci_jar_name = lines_.ci_jar_name

FULL_REQUESTED = os.environ.get("FULL_RELEASE", "").lower() == "true"
DRY_RUN = os.environ.get("DRY_RUN", "").lower() == "true"
SERVER = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
VERSION = re.compile(r"^\d+(\.\d+){1,2}$")


def version_key(version):
    parts = [int(p) for p in version.split(".")]
    return tuple(parts + [0] * (3 - len(parts)))


def next_patch(version):
    major, minor, patch = version_key(version)
    return f"{major}.{minor}.{patch + 1}"


def declared_version():
    match = re.search(r"^releaseVersion=(.*)$", (ROOT / "gradle.properties").read_text(encoding="utf-8"), re.M)
    version = match.group(1).strip() if match else "1.0"
    if not VERSION.match(version):
        sys.exit(f"releaseVersion '{version}' in gradle.properties must look like 1.0 or 1.0.3")
    return version


def asset_name(mc, version):
    return f"janky-paper-{mc}-{version}.jar"


def asset_url(version, name):
    return f"{SERVER}/{REPO}/releases/download/v{version}/{name}"


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    head = lines_.commit_of(lines_.ref(lines_.MAIN))
    if head is None:
        sys.exit(f"{lines_.ref(lines_.MAIN)} not found")
    versions = lines_.read_versions(head)
    declared = declared_version()
    previous, tags = lines_.latest_manifest()
    previous_lines = previous["lines"] if previous else {}
    previous_version = previous["version"] if previous else None

    if previous_version is None:
        full, version, reason = True, declared, "first release"
    elif version_key(declared) > version_key(previous_version):
        full, version, reason = True, declared, f"releaseVersion raised from {previous_version} to {declared}"
    else:
        full, version = FULL_REQUESTED, next_patch(previous_version)
        reason = "full release requested" if FULL_REQUESTED else f"versions changed since {previous_version}"
    while f"v{version}" in tags:  # a tag made by hand: take the next free number
        version = next_patch(version)
    log(f"Last release: {previous_version or 'none'} - next: {version} ({'every version' if full else 'changed versions'}; {reason})")

    plan, waiting, skipped = [], [], []
    available = lines_.ci_jars()
    for mc, path, branch in versions:
        code = lines_.fingerprint(head, path)
        old = previous_lines.get(mc)
        if old is None or lines_.entry_fingerprint(old) != code:
            jar = available.get(ci_jar_name(mc))
            if jar is None or jar["fingerprint"] != code:
                message = f"{mc}: `{path or '.'}` @ {head[:7]} has no CI jar of its current code yet (build running or failed)"
                (waiting if full else skipped).append(message)
                continue
            plan.append(dict(mc=mc, path=path, branch=branch, commit=head, fingerprint=code, source="ci", asset_id=jar["id"]))
        elif full:
            plan.append(dict(mc=mc, path=old.get("path", ""), branch=old["branch"], commit=old["commit"],
                             fingerprint=lines_.entry_fingerprint(old), source="release", release=old["release"], asset=old["asset"]))

    for line in plan:
        how = "new CI jar" if line["source"] == "ci" else f"unchanged, jar from {line['release']}"
        log(f"  release {line['mc']:<8} {line['path'] or '.':<18} {line['commit'][:7]}  ({how})")
    for message in skipped:
        log(f"  not yet  {message}")
    if full and waiting:
        log(f"Release {version} ships every Minecraft version, so it waits for:")
        for message in waiting:
            log(f"  - {message}")
        summary(f"Release {version} is waiting for every Minecraft version to be ready:\n\n" + "\n".join(f"- {m}" for m in waiting))
        return
    if not any(line["source"] == "ci" for line in plan):
        message = ("Nothing to release: no Minecraft version changed since the last release." if not skipped
                   else "Nothing to release yet: the changed Minecraft versions have no CI jar of their current code.")
        log(message)
        summary(message)
        return

    # manifest: every version's current jar, the new ones from this release
    known = {mc for mc, _, _ in versions}
    manifest_lines = {mc: entry for mc, entry in previous_lines.items() if mc in known}
    for line in plan:
        if line["source"] == "ci":
            manifest_lines[line["mc"]] = dict(branch=line["branch"], path=line["path"], commit=line["commit"],
                                              fingerprint=line["fingerprint"], release=version,
                                              asset=asset_name(line["mc"], version))
        else:
            manifest_lines[line["mc"]] = {**manifest_lines[line["mc"]], "release": version, "asset": asset_name(line["mc"], version),
                                          "fingerprint": line["fingerprint"]}
    manifest = dict(version=version, lines={mc: manifest_lines[mc] for mc, _, _ in versions if mc in manifest_lines})

    notes = release_notes(version, full, plan, manifest, versions, skipped)
    summary(notes)
    if DRY_RUN:
        log("\nDRY RUN - release notes would be:\n\n" + notes)
        return

    jars = WORK / "jars"
    shutil.rmtree(jars, ignore_errors=True)
    jars.mkdir(parents=True)
    for line in plan:
        target_jar = jars / asset_name(line["mc"], version)
        if line["source"] == "ci":
            # by asset id: if CI replaced the jar meanwhile (newer code), this fails instead of shipping other code
            with open(target_jar, "wb") as out:
                got = lines_.subprocess.run([*GH, "api", "-H", "Accept: application/octet-stream",
                                             f"repos/{REPO}/releases/assets/{line['asset_id']}"], cwd=ROOT, stdout=out)
            if got.returncode != 0 or target_jar.stat().st_size == 0:
                sys.exit(f"The CI jar of Minecraft {line['mc']} changed while releasing - the next run picks up the new one")
        else:
            download = WORK / "download" / line["mc"]
            shutil.rmtree(download, ignore_errors=True)
            gh("release", "download", f"v{line['release']}", "--repo", REPO, "--pattern", line["asset"], "--dir", str(download))
            found = download / line["asset"]
            if not found.is_file():
                sys.exit(f"No server jar found for Minecraft {line['mc']} (release {line['release']})")
            shutil.copy2(found, target_jar)
    (WORK / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (WORK / "notes.md").write_text(notes, encoding="utf-8")

    gh("release", "create", f"v{version}", "--repo", REPO, "--target", head, "--title", f"Janky Paper {version}",
       "--notes-file", str(WORK / "notes.md"), *[str(p) for p in sorted(jars.iterdir())], str(WORK / MANIFEST))
    log(f"Published Janky Paper {version}: {SERVER}/{REPO}/releases/tag/v{version}")


def release_notes(version, full, plan, manifest, versions, skipped):
    in_release = {line["mc"] for line in plan}
    out = [
        f"## Janky Paper {version}",
        "",
        "Created and owned by [Pepe-tf](https://github.com/Pepe-tf) - code by [CamerG1011](https://github.com/CamerG1011)"
        " - built on [Paper](https://papermc.io) by the PaperMC team and contributors",
        "",
    ]
    if full:
        out.append("A jar for every supported Minecraft version.")
    else:
        out.append("Updated in this release: " + ", ".join(f"**{line['mc']}**" for line in plan) + ".")
        out.append("Every other Minecraft version is unchanged - its current jar is linked below.")
    out += ["", "| Minecraft | Server jar | Built from |", "|---|---|---|"]
    for mc, _, _ in versions:
        entry = manifest["lines"].get(mc)
        if entry is None:
            out.append(f"| {mc} | not released yet | |")
            continue
        jar = f"[{entry['asset']}]({asset_url(entry['release'], entry['asset'])})"
        if mc not in in_release:
            jar += f" (from {entry['release']})"
        commit = f"[{entry['commit'][:7]}]({SERVER}/{REPO}/commit/{entry['commit']})"
        where = f"`main` `{entry['path']}/`" if entry.get("path") else f"`{entry['branch']}`"
        source = f"[`{entry['branch']}`]({SERVER}/{REPO}/tree/{entry['branch']})" if entry.get("path") else ""
        out.append(f"| {mc} | {jar} | {where} @ {commit}{' - branch ' + source if source else ''} |")
    if skipped:
        out += ["", "Changed but not in this release yet (they follow once their CI passes):"]
        out += [f"- {message}" for message in skipped]
    out += [
        "",
        "Pick the jar for the Minecraft version your players use. Minecraft 26.1 and 26.1.1 clients can also join the "
        "26.1.2 jar (same protocol). Java 25 for 26.x, Java 21 or newer for 1.21.11:",
        "",
        "```",
        f"java -Xms4G -Xmx4G -jar {asset_name(plan[0]['mc'], version)} --nogui",
        "```",
        "",
    ]
    return "\n".join(out)


if __name__ == "__main__":
    main()
