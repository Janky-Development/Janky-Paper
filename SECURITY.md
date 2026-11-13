# Security Policy

## Supported versions

Security fixes go into the newest release of each supported Minecraft version (see the
[releases](https://github.com/Janky-Development/Janky-Paper/releases) and `versions.txt`). Older Janky Paper releases are not patched; update to the latest
one for your Minecraft version.

## Reporting a vulnerability

**Do not report security problems in public issues, pull requests or discussions.**

Report them privately through GitHub: **Security** tab → **Report a vulnerability**
([direct link](https://github.com/Janky-Development/Janky-Paper/security/advisories/new)). Please include:

- what the problem is and what an attacker could do with it (crash the server, run code, read data, ...)
- the Janky Paper version (`/version`) and Minecraft version
- steps or a proof of concept to reproduce it, and any plugins needed for it

You'll get an answer as soon as possible. Please give us reasonable time to release a fix before you publish
details. Reporters are credited in the release notes unless they'd rather not be.

## Not ours to fix

- Problems that also happen on plain [Paper](https://github.com/PaperMC/Paper): report them to PaperMC as
  described in [their security policy](https://github.com/PaperMC/Paper/security/policy). We pick up their
  fixes when we update.
- Problems in Minecraft itself: report them to Mojang.
- Problems in a plugin, including fake-player plugins: report them to that plugin's authors.
