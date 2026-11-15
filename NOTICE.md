# Janky Paper - Copyright and License

```
Janky Paper - a Paper fork for recording servers and huge fake-player crowds
Created and owned by Pepe-tf (https://github.com/Pepe-tf)
Copyright (C) 2026 Pepe-tf, CamerG1011 (https://github.com/CamerG1011) and the Janky Paper contributors

This program is free software: you can redistribute it and/or modify it under the terms of
the GNU General Public License as published by the Free Software Foundation, version 3 of
the License, with the additional terms below (section 7 of that License).

This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GNU General Public License for more details.

You should have received a copy of the GNU General Public License along with this program
(the LICENSE file). If not, see <https://www.gnu.org/licenses/>.
```

Janky Paper is built on [Paper](https://github.com/PaperMC/Paper) by the PaperMC team and Paper's
contributors. Almost all of the server is their work, and the copyright of Paper's code stays with its
authors. Paper is licensed under the GNU General Public License version 3, inherited from Spigot, CraftBukkit
and Bukkit. A fork of it has to stay under that license, so Janky Paper is GPL-3.0 as well. The full text is
in [LICENSE](LICENSE). Full credits are in [CREDITS.md](CREDITS.md).

## In plain words

This summary is not a substitute for the license text, which is what applies.

**You may:**

- run Janky Paper on any server, including commercial and paid ones
- fork it, change it, and build your own version
- share or publish your version, for free or for money

**If you share a jar or the source of your own version with anyone, you must:**

- keep this notice, [CREDITS.md](CREDITS.md), the [LICENSE](LICENSE) file, and every copyright line in the code
- keep crediting **Pepe-tf** as the original creator and owner and **CamerG1011** as a coder and contributor
  (term 3 below)
- license your version under GPL-3.0 with these same terms, with no extra restrictions of your own
- give the people you share the jar with the complete source code of your version (a public Git repository is
  the simplest way)
- say that you changed it and roughly when (your Git history does this)
- give your version its own name (see the additional terms below)

Running a modified version only on your own server, without giving the jar to anyone, does not require
publishing anything.

## Additional terms (GNU GPL version 3, section 7)

These terms apply to the material the Janky Paper authors added (the `dev.jankypaper` sources, the patches in
`janky-paper-*/`, the build scripts, workflows and documentation of this repository). They are permitted by
section 7 of the license and stay in force for every copy and modified version.

1. **Name and branding (section 7(e)).** The license does not grant any right to use the name "Janky Paper",
   its `[ᴊᴀɴᴋʏ]` console tag or its logo for another project, a modified version, a service or a product, except
   to describe truthfully where your work comes from (for example "based on Janky Paper").
2. **Marking modified versions (section 7(c)).** A modified version you give to others must not be presented as
   Janky Paper itself or as coming from its authors. It must use its own name where users see it (the jar
   name, the server brand / startup banner, release titles), and its source must state that it is a modified
   version of Janky Paper.
3. **Credit to the original authors (section 7(b)).** Every copy, fork, clone and modified version of Janky
   Paper, as source code or as a jar, must keep crediting **Pepe-tf** (https://github.com/Pepe-tf) as the
   original creator and owner of Janky Paper, and **CamerG1011** (https://github.com/CamerG1011) as a coder and
   contributor of Janky Paper. To do so, it must keep all of the following:
   - this notice and [CREDITS.md](CREDITS.md);
   - the credit header at the top of each Janky Paper source file (`dev.jankypaper` packages);
   - the Credits section of `README.md` (a rewritten README must keep that credit in it);
   - the credit line the server prints at every start (`ServerBanners.CREDITS`), which is part of the
     "Appropriate Legal Notices" the program displays;
   - the existing copyright and author lines;
   - the credit to **Paper** (the PaperMC team and contributors) and the projects it builds on, in
     [CREDITS.md](CREDITS.md), the README and the startup banner ("built on PaperMC/Paper").

   You may add your own names next to these credits. You may not remove, hide, shorten or reword them, or
   present anyone else as the creator or owner of Janky Paper.

## Unofficial builds, forks and redistributions

The only official Janky Paper builds are the jars published on the
[Janky Paper GitHub releases](https://github.com/Janky-Development/Janky-Paper/releases) and the jars you build
yourself from this repository's source.

Pepe-tf, CamerG1011 and every other developer or contributor of Janky Paper are **not responsible** for any
unofficial build, fork, modified version or redistributed copy of Janky Paper, or for anything that happens
because you chose to download, install or run one. This includes, among other things:

- jars or source shared on other websites, Discord servers, mirrors, mod or plugin sites, or by other people;
- forks and modified versions of Janky Paper, and forks of those forks;
- re-uploaded or re-hosted copies of an official jar, which may have been changed;
- any malware, data loss, server damage, crashes, security issues, lost money or other harm they cause.

Downloading or running an unofficial build or a redistributed version is your own choice and at your own risk.
Whoever publishes a fork or redistributes a copy is alone responsible for it, its support, and what it does.
The Janky Paper developers do not check, approve, support or endorse unofficial builds, and do not give support
for them. If you are not sure a jar is official, do not run it: download it from the releases page above or
build it from source.

This section adds to, and does not replace, sections 15 ("Disclaimer of Warranty") and 16 ("Limitation of
Liability") of the [LICENSE](LICENSE), which apply to every copy of Janky Paper, official or not. Those sections
apply to the fullest extent the law where you live allows.

## Upstream projects

| Project | License | Source |
|---|---|---|
| Paper | GPL-3.0 (some contributions also MIT, listed in Paper's `LICENSE.md`) | https://github.com/PaperMC/Paper |
| Spigot / CraftBukkit / Bukkit | GPL-3.0 | https://hub.spigotmc.org/stash/projects/SPIGOT |

Their notices are kept in the sources `applyAllPatches` checks out (`paper-api/`, `paper-server/`), and the
copyright of their code stays with its authors.

## Minecraft

Janky Paper does not contain or distribute Minecraft. On first start, the server jar downloads the official
server from Mojang and patches it on your machine, like Paper's jar does. Running it means you accept the
[Minecraft EULA](https://www.minecraft.net/eula). Janky Paper is not an official Minecraft product and is not
approved by or associated with Mojang Studios, Microsoft or PaperMC. "Minecraft" is a trademark of Mojang
Synergies AB.

## Contributions

Contributions are accepted under the same license and terms (GPL-3.0 plus the additional terms above). Authors
keep the copyright of their contributions. See [CONTRIBUTING.md](CONTRIBUTING.md).
