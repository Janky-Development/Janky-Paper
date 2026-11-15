#!/usr/bin/env python3
"""Janky Paper - the Minecraft version lines on main, shared by the CI, release and sync steps.

Every supported Minecraft version is a complete paperweight project on main: the newest one is the repository
root, the older ones live in versions/<minecraft version>/ (versions.txt lists them). Each one also has a mirror
branch (ver/<minecraft version>) that only CI writes to: after a release it holds exactly the code of that
version's newest released jar, so the branch can still be cloned and built on its own.

A version line's "fingerprint" is a hash of its code files (paths relative to the line's folder; docs, CI files
and the other lines' folders excluded). Two commits that hold the same code for a line have the same fingerprint,
whatever else changed - that is how CI knows which lines to build and the release which lines changed.

Commands (run from anywhere in the repository):
  plan   decide which lines CI builds; writes a GitHub Actions matrix to $GITHUB_OUTPUT (or prints it)
  sync   update every mirror branch to its line's newest released code and push it
  fingerprint <commit> [<mc>...]   print fingerprints (debugging)

Environment: GITHUB_REPOSITORY, GH_TOKEN (gh); plan also reads GITHUB_EVENT_NAME, PR_BASE_SHA (pull requests) and
BUILD_ALL=true (workflow_dispatch: build every line). Testing: GH_CLI (command used instead of `gh`),
RELEASE_REMOTE (remote of main and the mirrors, '' = local branches), DRY_RUN=true (sync pushes nothing).
"""
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(subprocess.run(["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True).stdout.strip())
REPO = os.environ.get("GITHUB_REPOSITORY", "Janky-Development/Janky-Paper")
GH = os.environ.get("GH_CLI", "gh").split()
REMOTE = os.environ.get("RELEASE_REMOTE", "origin")
DRY_RUN = os.environ.get("DRY_RUN", "").lower() == "true"
WORK = Path(os.environ.get("RUNNER_TEMP") or tempfile.gettempdir()) / "janky-lines"
MAIN = "main"
CI_BUILDS = "ci-builds"  # pre-release CI uploads every line's newest jar to
MANIFEST = "release-manifest.json"
VERSIONS_DIR = "versions/"

# Files whose changes never change a server jar (paths relative to a line's folder).
NOT_CODE = re.compile(
    r"^(.*\.md|\.github/.*|\.idea/.*|\.gitignore|\.gitattributes|\.editorconfig|LICENSE.*"
    r"|versions\.txt|build-all-versions\.(sh|bat))$"
)
# Main's files every mirror branch gets a copy of (the license terms require them in every copy).
SHARED_FILES = ["LICENSE", "NOTICE.md", "CREDITS.md"]
# ci-builds asset label: "janky-paper-<mc>.jar · code <fingerprint>" - the code that jar was built from.
LABEL_CODE = re.compile(r"code ([0-9a-f]{64})")


def run(*cmd, check=True, env=None, cwd=ROOT):
    result = subprocess.run(list(cmd), cwd=cwd, capture_output=True, text=True,
                            env=None if env is None else {**os.environ, **env})
    if check and result.returncode != 0:
        sys.exit(f"Command failed ({result.returncode}): {' '.join(cmd)}\n{result.stdout}{result.stderr}")
    return result


def gh(*args, check=True):
    return run(*GH, *args, check=check)


def log(message):
    print(message, flush=True)


def ref(branch):
    return f"{REMOTE}/{branch}" if REMOTE else branch


def commit_of(rev):
    return run("git", "rev-parse", "--verify", "--quiet", rev + "^{commit}", check=False).stdout.strip() or None


def read_versions(commit=None):
    """[(minecraft version, folder on main, mirror branch)], newest first. Folder '' is the repository root."""
    text = (run("git", "show", f"{commit}:versions.txt").stdout if commit
            else (ROOT / "versions.txt").read_text(encoding="utf-8"))
    lines = []
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) != 3:
            sys.exit(f"versions.txt: expected '<minecraft version> <folder> <mirror branch>', got: {raw!r}")
        mc, path, branch = parts
        lines.append((mc, "" if path in (".", "./") else path.strip("/"), branch))
    return lines


def fingerprint(commit, path):
    """Hash of the code files of the line in `path` (relative to the repository root, '' = root) at `commit`."""
    tree = f"{commit}:{path}" if path else f"{commit}:"
    listing = run("git", "ls-tree", "-r", tree, check=False)  # an explicit tree: names are relative to it
    if listing.returncode != 0:
        return None  # the commit or the folder does not exist
    digest = hashlib.sha256()
    for entry in sorted(listing.stdout.splitlines(), key=lambda l: l.split("\t", 1)[1]):
        meta, name = entry.split("\t", 1)
        if not path and name.startswith(VERSIONS_DIR):
            continue  # the root line does not own the older lines' folders
        if NOT_CODE.match(name):
            continue
        mode, _, blob = meta.split()
        digest.update(f"{mode} {blob} {name}\n".encode())
    return digest.hexdigest()


def entry_fingerprint(entry):
    """Fingerprint of a release-manifest entry (older manifests have no fingerprint: computed from their commit)."""
    if entry.get("fingerprint"):
        return entry["fingerprint"]
    commit = entry.get("commit")
    if not commit or run("git", "cat-file", "-e", commit + "^{commit}", check=False).returncode != 0:
        return None
    return fingerprint(commit, entry.get("path", ""))


def ci_jars():
    """Jars on the ci-builds pre-release: name -> {id, fingerprint} (empty when the pre-release does not exist)."""
    result = gh("api", f"repos/{REPO}/releases/tags/{CI_BUILDS}", check=False)
    if result.returncode != 0:
        return {}
    jars = {}
    for asset in json.loads(result.stdout or "{}").get("assets", []):
        match = LABEL_CODE.search(asset.get("label") or "")
        jars[asset["name"]] = dict(id=asset["id"], fingerprint=match.group(1) if match else None)
    return jars


def ci_jar_name(mc):
    return f"janky-paper-{mc}.jar"


def ci_jar_label(mc, code):
    return f"{ci_jar_name(mc)} · code {code}"


def latest_manifest():
    """(manifest of the newest release that has one, or None; set of every release tag)."""
    listing = gh("release", "list", "--repo", REPO, "--limit", "200", "--json", "tagName,createdAt,isDraft,isPrerelease").stdout.strip()
    releases = sorted(json.loads(listing or "[]"), key=lambda r: r["createdAt"], reverse=True)
    tags = {r["tagName"] for r in releases}
    for release in releases:
        if release.get("isDraft") or release.get("isPrerelease"):
            continue  # ci-builds and anything made by hand
        target = WORK / "manifests" / release["tagName"]
        got = gh("release", "download", release["tagName"], "--repo", REPO, "--pattern", MANIFEST,
                 "--dir", str(target), "--clobber", check=False)
        if got.returncode == 0 and (target / MANIFEST).is_file():
            return json.loads((target / MANIFEST).read_text(encoding="utf-8")), tags
    return None, tags


def output(name, value):
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(f"{name}={value}\n")
    log(f"{name}={value}")


def summary(text):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if path:
        with open(path, "a", encoding="utf-8") as f:
            f.write(text + "\n")


# ---------------------------------------------------------------------------------------------------------------- plan

def plan():
    """Which lines this CI run builds. Push: every line whose code has neither been released nor built into a ci-builds
    jar yet (a line whose build failed is retried by the next push). Pull request: the lines the PR changes."""
    WORK.mkdir(parents=True, exist_ok=True)
    head = commit_of("HEAD")
    event = os.environ.get("GITHUB_EVENT_NAME", "push")
    build_all = os.environ.get("BUILD_ALL", "").lower() == "true"
    lines = read_versions()
    selected, rows = [], []
    if event == "pull_request":
        base = os.environ.get("PR_BASE_SHA") or commit_of(ref(MAIN))
        for mc, path, branch in lines:
            code = fingerprint(head, path)
            changed = build_all or code != fingerprint(base, path)
            rows.append(f"| {mc} | `{path or '.'}` | {'build (changed in this PR)' if changed else 'unchanged'} |")
            if changed:
                selected.append(dict(mc=mc, path=path or ".", code=code))
    else:
        manifest, _ = latest_manifest()
        released = {mc: entry_fingerprint(entry) for mc, entry in (manifest or {}).get("lines", {}).items()}
        built = ci_jars()
        for mc, path, branch in lines:
            code = fingerprint(head, path)
            if not build_all and code == released.get(mc):
                why = "skip - this code is already released"
            elif not build_all and code == (built.get(ci_jar_name(mc)) or {}).get("fingerprint"):
                why = "skip - already built (jar waiting on ci-builds)"
            else:
                why = "build"
                selected.append(dict(mc=mc, path=path or ".", code=code))
            rows.append(f"| {mc} | `{path or '.'}` | {why} |")
    summary("### Version lines\n\n| Minecraft | Folder | This run |\n|---|---|---|\n" + "\n".join(rows))
    for row in rows:
        log(row)
    output("matrix", json.dumps(dict(include=selected), separators=(",", ":")))
    output("any", "true" if selected else "false")


# ---------------------------------------------------------------------------------------------------------------- sync

def git_env(index):
    return {"GIT_INDEX_FILE": str(index)}


def mirror_tree(released_commit, path, mirror_head, main_head, index):
    """Tree of a mirror branch: the line's released folder, main's shared license files, the mirror's own .github."""
    env = git_env(index)
    run("git", "read-tree", f"{released_commit}:{path}" if path else f"{released_commit}:", env=env)
    if not path:
        run("git", "rm", "-r", "-q", "--cached", "--ignore-unmatch", VERSIONS_DIR.rstrip("/"), env=env)
    run("git", "rm", "-r", "-q", "--cached", "--ignore-unmatch", ".github", env=env)
    if mirror_head and run("git", "cat-file", "-e", f"{mirror_head}:.github", check=False).returncode == 0:
        # kept as it is: the workflow token may not change workflow files, and mirrors never run CI on their own
        run("git", "read-tree", "--prefix=.github/", f"{mirror_head}:.github", env=env)
    for name in SHARED_FILES:
        blob = run("git", "rev-parse", "--verify", "--quiet", f"{main_head}:{name}", check=False).stdout.strip()
        if blob:
            run("git", "update-index", "--add", "--cacheinfo", f"100644,{blob},{name}", env=env)
    return run("git", "write-tree", env=env).stdout.strip()


def sync():
    """Point every mirror branch at its line's newest released code (as the release manifest records it)."""
    WORK.mkdir(parents=True, exist_ok=True)
    main_head = commit_of(ref(MAIN))
    if main_head is None:
        sys.exit(f"{ref(MAIN)} not found")
    manifest, _ = latest_manifest()
    if manifest is None:
        log("No release yet - nothing to sync.")
        return
    rows = []
    for mc, path, branch in read_versions(main_head):
        if branch == MAIN:
            continue  # main is that line's own branch
        entry = manifest.get("lines", {}).get(mc)
        if entry is None:
            rows.append(f"| {mc} | `{branch}` | not released yet |")
            continue
        released_commit, released_path = entry.get("commit"), entry.get("path", "")
        if not released_commit or commit_of(released_commit) is None:
            rows.append(f"| {mc} | `{branch}` | released commit {released_commit} not found - skipped |")
            continue
        mirror_head = commit_of(ref(branch))
        index = WORK / f"index-{mc}"
        index.unlink(missing_ok=True)
        tree = mirror_tree(released_commit, released_path, mirror_head, main_head, index)
        if mirror_head and run("git", "rev-parse", f"{mirror_head}^{{tree}}").stdout.strip() == tree:
            rows.append(f"| {mc} | `{branch}` | up to date |")
            continue
        commit = make_mirror_commit(mc, branch, entry, released_commit, released_path, mirror_head, tree)
        if DRY_RUN:
            rows.append(f"| {mc} | `{branch}` | would move to {commit[:7]} (dry run) |")
            continue
        run("git", "push", REMOTE or ".", f"{commit}:refs/heads/{branch}")
        rows.append(f"| {mc} | `{branch}` | updated to {commit[:7]} (Janky Paper {entry.get('release')}) |")
    summary("### Mirror branches\n\n| Minecraft | Branch | Sync |\n|---|---|---|\n" + "\n".join(rows))
    for row in rows:
        log(row)


def make_mirror_commit(mc, branch, entry, released_commit, path, mirror_head, tree):
    """A commit on the mirror branch, authored like the main commit it comes from."""
    who = run("git", "log", "-1", "--format=%an%x00%ae%x00%aI", released_commit).stdout.rstrip("\n").split("\x00")
    subjects = []
    previous = (entry_from_mirror(mirror_head) if mirror_head else None)
    if previous and commit_of(previous):
        log_range = run("git", "log", "--format=%h %s", f"{previous}..{released_commit}", "--", path or ".", check=False)
        subjects = [line for line in log_range.stdout.splitlines() if line.strip()][:30]
    if not subjects:
        subjects = [run("git", "log", "-1", "--format=%h %s", released_commit).stdout.strip()]
    message = (f"Minecraft {mc}: Janky Paper {entry.get('release')}\n\n"
               f"Code of the {mc} line as released in Janky Paper {entry.get('release')}, from "
               f"{MAIN}@{released_commit[:7]}{' (' + path + '/)' if path else ''}.\n\n"
               + "\n".join(f"- {s}" for s in subjects)
               + f"\n\nJanky-Main-Commit: {released_commit}\n")
    env = {
        "GIT_AUTHOR_NAME": who[0], "GIT_AUTHOR_EMAIL": who[1], "GIT_AUTHOR_DATE": who[2],
        "GIT_COMMITTER_NAME": who[0], "GIT_COMMITTER_EMAIL": who[1],
    }
    parents = ["-p", mirror_head] if mirror_head else []
    return subprocess.run(["git", "commit-tree", tree, *parents], cwd=ROOT, input=message, text=True,
                          capture_output=True, check=True, env={**os.environ, **env}).stdout.strip()


def entry_from_mirror(mirror_head):
    """The main commit a mirror branch was last synced from (its Janky-Main-Commit trailer), if any."""
    body = run("git", "log", "-1", "--format=%B", mirror_head, check=False).stdout
    match = re.search(r"^Janky-Main-Commit: ([0-9a-f]{40})$", body, re.M)
    return match.group(1) if match else None


# ---------------------------------------------------------------------------------------------------------------- main

if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else ""
    if command == "plan":
        plan()
    elif command == "sync":
        sync()
    elif command == "fingerprint":
        commit = sys.argv[2] if len(sys.argv) > 2 else "HEAD"
        wanted = set(sys.argv[3:])
        for mc, path, branch in read_versions():
            if not wanted or mc in wanted:
                print(f"{mc:<8} {path or '.':<18} {fingerprint(commit, path)}")
    else:
        sys.exit(__doc__)
