## What and why

<!-- What does this change, and what problem does it solve? Link the issue if there is one. -->

## How it was tested

<!-- ./gradlew build, dev server, bot count and TPS/MSPT before/after, spark profile... -->

## Minecraft versions

<!-- Pull requests go to main (newest version); the change is then ported to the ver/* branches. -->
- [ ] Applies to every version as it is
- [ ] Needs a different port on some versions, or doesn't apply to them (say which)

## Checklist

- [ ] Changes to generated sources are rebuilt into patches (`fixup...` then `rebuild...`)
- [ ] Every patched change is marked `// Janky Paper - <why>`
- [ ] README updated if behaviour, config keys or commands changed
- [ ] `releaseVersion` in `gradle.properties` is unchanged
- [ ] Credits are untouched, and new `dev.jankypaper` files have the credit header
