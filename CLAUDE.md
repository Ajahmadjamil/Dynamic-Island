# Pixel Island – notes for Claude

**Read the project vault first:** `C:\Users\princ\OneDrive\obsidian vault\pixel island\Claude Start Here.md`

That Obsidian vault holds everything about this project: architecture, one note per Kotlin file (with line numbers), features, platform quirks, history, decisions, known issues, and the full rules list (`07 Decisions & Rules/Rules for Claude.md`). If the code and the vault disagree, trust the code and fix the note.

## Must-follow rules

- The island stays **at the camera, above the status bar**. Never move it below the status bar.
- **One pop-up, never two:** never show an island card when Android shows its own banner.
- No Compose in the island (main) process. No allocations in `onDraw`. No polling.
- Toolchain pins: AGP **9.2.1**, Gradle wrapper **9.8.0**, compileSdk 37, targetSdk 36. Don't upgrade AGP, even if lint suggests it.
- Use `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"`. Don't build from the CLI while Android Studio is building.
- Don't commit unless asked. Don't change security settings or appops on the phone yourself. Never handle keystore passwords or put secrets anywhere.
- Say clearly what was and wasn't tested on the device (Pixel 8, Android 17).

## Common commands

```bash
./gradlew assembleRelease
```

```bash
./gradlew --console=plain --continue :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

## At the end of a session

Add a note from `Templates/Session Log Template.md` to `06 History/Sessions/` in the vault, and update `Changelog`, `Work in Progress`, `Known Issues & Tech Debt`, and the code notes of any files you changed.
