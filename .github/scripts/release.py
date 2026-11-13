#!/usr/bin/env python3
"""Janky Paper - publishes the server jars as a GitHub release (run by .github/workflows/release.yml).

Every Minecraft version in versions.txt is built from its own branch. CI ("Patch and Build", build.yml) builds and
tests each push and uploads that branch's server jar to the "ci-builds" pre-release as
janky-paper-<minecraft version>.jar (release assets, unlike Actions artifacts, don't count against the Actions
storage quota). A newer push to a branch cancels its older CI run, so a jar uploaded after the CI run of the branch's
newest commit started is that commit's jar. This script works out what the next release holds and publishes it:

* First release, or releaseVersion in main's gradle.properties raised above the last release (1.0 -> 1.1, 2.0):
  a full release with exactly that number and a jar for every Minecraft version. A version whose code did not
  change since its last release ships that same jar again, renamed to the new number.
* Otherwise: only the Minecraft versions whose code changed since their last release, numbered one patch above
  the last release (1.0 -> 1.0.1 -> 1.0.2). Pushes that only touch docs or CI files (README, .github/, ...)
  do not count as a change.
* A version is only released from a commit whose CI run succeeded. A full release waits until every version is
  ready. A patch release ships the ready ones; the others follow in a later release once their CI passes.

Each release carries release-manifest.json: for every Minecraft version, the commit its current jar was built from
and the release that jar was published in. The next run compares against it.

Environment: GITHUB_REPOSITORY (owner/name), GH_TOKEN (for gh); optional FULL_RELEASE=true (ship every version even
without a releaseVersion bump), DRY_RUN=true (print the plan, publish nothing).
Testing: GH_CLI (command used instead of `gh`), RELEASE_REMOTE (remote of the branches, '' = local branches).
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True).stdout.strip())
REPO = os.environ["GITHUB_REPOSITORY"]
GH = os.environ.get("GH_CLI", "gh").split()
REMOTE = os.environ.get("RELEASE_REMOTE", "origin")
CI_WORKFLOW = os.environ.get("CI_WORKFLOW", "build.yml")
FULL_REQUESTED = os.environ.get("FULL_RELEASE", "").lower() == "true"
DRY_RUN = os.environ.get("DRY_RUN", "").lower() == "true"
WORK = Path(os.environ.get("RUNNER_TEMP") or tempfile.gettempdir()) / "janky-release"
MANIFEST = "release-manifest.json"
CI_BUILDS = "ci-builds"  # pre-release CI uploads every branch's newest jar to (build.yml)
SERVER = os.environ.get("GITHUB_SERVER_URL", "https://github.com")

# Files whose changes never change a server jar.
NOT_CODE = re.compile(r"^(.*\.md|\.github/.*|\.idea/.*|\.gitignore|\.gitattributes|\.editorconfig|LICENSE.*|versions\.txt|build-all-versions\.(sh|bat))$")
VERSION = re.compile(r"^\d+(\.\d+){1,2}$")


def run(*cmd, check=True):
    result = subprocess.run(list(cmd), cwd=ROOT, capture_output=True, text=True)
    if check and result.returncode != 0:
        sys.exit(f"Command failed ({result.returncode}): {' '.join(cmd)}\n{result.stdout}{result.stderr}")
    return result


def gh(*args, check=True):
    return run(*GH, *args, check=check)


def log(message):
    print(message, flush=True)


def version_key(version):
    parts = [int(p) for p in version.split(".")]
    return tuple(parts + [0] * (3 - len(parts)))


def next_patch(version):
    major, minor, patch = version_key(version)
    return f"{major}.{minor}.{patch + 1}"


def read_versions():
    lines = []
    for raw in (ROOT / "versions.txt").read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            mc, branch = line.split()[:2]
            lines.append((mc, branch))
    return lines


def declared_version():
    match = re.search(r"^releaseVersion=(.*)$", (ROOT / "gradle.properties").read_text(encoding="utf-8"), re.M)
    version = match.group(1).strip() if match else "1.0"
    if not VERSION.match(version):
        sys.exit(f"releaseVersion '{version}' in gradle.properties must look like 1.0 or 1.0.3")
    return version


def previous_release():
    """The newest release that has a manifest: (manifest dict or None, set of every existing tag)."""
    listing = gh("release", "list", "--repo", REPO, "--limit", "200", "--json", "tagName,createdAt,isDraft,isPrerelease").stdout.strip()
    releases = sorted(json.loads(listing or "[]"), key=lambda r: r["createdAt"], reverse=True)
    tags = {r["tagName"] for r in releases}
    for release in releases:
        if release.get("isDraft") or release.get("isPrerelease"):
            continue  # ci-builds and anything made by hand
        target = WORK / "previous" / release["tagName"]
        got = gh("release", "download", release["tagName"], "--repo", REPO, "--pattern", MANIFEST,
                 "--dir", str(target), "--clobber", check=False)
        if got.returncode == 0 and (target / MANIFEST).is_file():
            return json.loads((target / MANIFEST).read_text(encoding="utf-8")), tags
    return None, tags


def branch_head(branch):
    ref = f"{REMOTE}/{branch}" if REMOTE else branch
    return run("git", "rev-parse", "--verify", "--quiet", ref + "^{commit}", check=False).stdout.strip() or None


def code_changed(old, new):
    if old == new:
        return False
    if run("git", "cat-file", "-e", old + "^{commit}", check=False).returncode != 0:
        return True  # history was rewritten: rebuild
    changed = run("git", "diff", "--name-only", old, new).stdout.splitlines()
    return any(not NOT_CODE.match(name) for name in changed)


def successful_ci_run(sha):
    """(run id, start time) of a successful CI run of this commit, or None."""
    result = gh("api", f"repos/{REPO}/actions/workflows/{CI_WORKFLOW}/runs?head_sha={sha}&status=success&event=push&per_page=1",
                check=False)
    if result.returncode != 0:
        return None
    runs = json.loads(result.stdout or "{}").get("workflow_runs", [])
    return (runs[0]["id"], runs[0].get("run_started_at") or runs[0].get("created_at") or "") if runs else None


def ci_jars():
    """Jars on the ci-builds pre-release: name -> upload time (empty when it does not exist yet)."""
    result = gh("release", "view", CI_BUILDS, "--repo", REPO, "--json", "assets", check=False)
    if result.returncode != 0:
        return {}
    return {asset["name"]: max(asset.get("createdAt") or "", asset.get("updatedAt") or "")
            for asset in json.loads(result.stdout or "{}").get("assets", [])}


def ci_jar_name(mc):
    return f"janky-paper-{mc}.jar"


def asset_name(mc, version):
    return f"janky-paper-{mc}-{version}.jar"


def asset_url(version, name):
    return f"{SERVER}/{REPO}/releases/download/v{version}/{name}"


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    versions = read_versions()
    declared = declared_version()
    previous, tags = previous_release()
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
    available = ci_jars()
    for mc, branch in versions:
        sha = branch_head(branch)
        old = previous_lines.get(mc)
        if sha is None:
            if full and old:
                plan.append(dict(mc=mc, branch=old["branch"], commit=old["commit"], source="release", release=old["release"], asset=old["asset"]))
            elif full:
                waiting.append(f"{mc}: branch `{branch}` is not on GitHub yet")
            else:
                skipped.append(f"{mc}: branch `{branch}` is not on GitHub")
            continue
        if old is None or code_changed(old["commit"], sha):
            ci_run = successful_ci_run(sha)
            uploaded = available.get(ci_jar_name(mc))
            if ci_run is None or uploaded is None or uploaded < ci_run[1]:
                problem = ("has no successful CI run yet" if ci_run is None
                           else "has no jar from its CI run on the ci-builds pre-release")
                message = f"{mc}: `{branch}` @ {sha[:7]} {problem}"
                (waiting if full else skipped).append(message)
                continue
            plan.append(dict(mc=mc, branch=branch, commit=sha, source="ci", run=ci_run[0]))
        elif full:
            plan.append(dict(mc=mc, branch=old["branch"], commit=old["commit"], source="release", release=old["release"], asset=old["asset"]))

    for line in plan:
        how = f"CI run {line['run']}" if line["source"] == "ci" else f"unchanged, jar from {line['release']}"
        log(f"  release {line['mc']:<8} {line['branch']:<12} {line['commit'][:7]}  ({how})")
    for message in skipped:
        log(f"  not yet  {message}")
    if full and waiting:
        log(f"Release {version} ships every Minecraft version, so it waits for:")
        for message in waiting:
            log(f"  - {message}")
        summary(f"Release {version} is waiting for every Minecraft version to be ready:\n\n" + "\n".join(f"- {m}" for m in waiting))
        return
    if not plan:
        log("Nothing to release: no Minecraft version changed since the last release.")
        summary("Nothing to release: no Minecraft version changed since the last release.")
        return

    # manifest: every version's current jar, the new ones from this release
    known = {mc for mc, _ in versions}
    lines = {mc: entry for mc, entry in previous_lines.items() if mc in known}
    for line in plan:
        lines[line["mc"]] = dict(branch=line["branch"], commit=line["commit"], release=version, asset=asset_name(line["mc"], version))
    manifest = dict(version=version, lines={mc: lines[mc] for mc, _ in versions if mc in lines})

    notes = release_notes(version, full, plan, manifest, versions, skipped)
    summary(notes)
    if DRY_RUN:
        log("\nDRY RUN - release notes would be:\n\n" + notes)
        return

    jars = WORK / "jars"
    shutil.rmtree(jars, ignore_errors=True)
    jars.mkdir(parents=True)
    for line in plan:
        download = WORK / "download" / line["mc"]
        shutil.rmtree(download, ignore_errors=True)
        if line["source"] == "ci":
            name = ci_jar_name(line["mc"])
            gh("release", "download", CI_BUILDS, "--repo", REPO, "--pattern", name, "--dir", str(download))
            found = [download / name]
        else:
            gh("release", "download", f"v{line['release']}", "--repo", REPO, "--pattern", line["asset"], "--dir", str(download))
            found = [download / line["asset"]]
        if not found or not found[-1].is_file():
            sys.exit(f"No server jar found for Minecraft {line['mc']} ({line['source']})")
        shutil.copy2(found[-1], jars / asset_name(line["mc"], version))
    (WORK / MANIFEST).write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (WORK / "notes.md").write_text(notes, encoding="utf-8")

    built = [line for line in plan if line["source"] == "ci"]
    target = built[0]["commit"] if len(built) == 1 else (branch_head("main") or plan[0]["commit"])
    gh("release", "create", f"v{version}", "--repo", REPO, "--target", target, "--title", f"Janky Paper {version}",
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
    for mc, _ in versions:
        entry = manifest["lines"].get(mc)
        if entry is None:
            out.append(f"| {mc} | not released yet | |")
            continue
        jar = f"[{entry['asset']}]({asset_url(entry['release'], entry['asset'])})"
        if mc not in in_release:
            jar += f" (from {entry['release']})"
        commit = f"[{entry['commit'][:7]}]({SERVER}/{REPO}/commit/{entry['commit']})"
        out.append(f"| {mc} | {jar} | `{entry['branch']}` @ {commit} |")
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


def summary(text):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text + "\n")


if __name__ == "__main__":
    main()
