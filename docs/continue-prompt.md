# Prompt to continue the port

Paste everything below the line into a new Claude Code session opened in `C:\Users\itexp\AndroidStudioProjects\Attendance`.

---

We are porting the web app `C:\Users\itexp\attendance-pwa` (Svelte + Dexie) to a native Android app in this folder
(Kotlin, Jetpack Compose, Material 3 Expressive, Room 3; package `com.itexpert120.attendanceManager`; not a git repo).
Read first, in this order: `docs/phone-design.md` (mandatory: the web UI is the spec for data and rules only, every
screen must be redesigned for a phone), then the memory notes for this project, then `docs/continue-prompt.md`.

## State (checked at the end of the last session, 2026-10-07)
Green: `:app:compileDebugKotlin`, 179 JVM unit tests, 171 instrumented tests on `emulator-5554` (whole suite except
the `tools` seeder, run with `am instrument -e notPackage com.itexpert120.attendanceManager.tools`, which does not
uninstall the app; `connectedDebugAndroidTest` would uninstall it and wipe the demo data).

Done: every screen (Registers home, register Attendance/Fees/Summary/Reports, Students, Tests, Test detail +
reports, Homework tab + editor, Settings + School settings, Setup); PDF engine `export/` (register landscape A4,
attendance report, absentee list, test sheet, periodic test report, homework) with Print/Share/Save sheet;
native zip backup/restore + "Import from web app" + Auto Backup rules; class rename/delete; adaptive app icon.
A read-only parity audit against the PWA (~225 features) found ~33 gaps; all were fixed (PWA behaviour wins:
totals in days, holiday reason required, absentee export daily-only, etc.).
UX changes after on-device review: sheets settle without overshoot (`SheetMotion`), slide (not fade) screen
transitions with per-tab bottom bars (`ui/navigation/Transitions.kt`, `TabFrame`), compact Today card, no
full-width buttons (`BottomActionBar`), edge-to-edge bottoms (`AppScreen` + `navigationBarBottom()`),
`DelayedLoading`, tap-tab-again scrolls to top. Rules are in `docs/phone-design.md` ("Buttons, sheets and motion").

## Next
1. On the emulator, review every screen again in light/dark/large font (restore the emulator settings afterwards;
   the user watches it), especially the edge-to-edge bottoms and the redesigned Setup flow (reach it by clearing
   app data, then re-seed with `tools.SeedDemoData`).
2. Motion polish pass (the user asked for it after parity): animate content switches (segments, Day/Month, periods)
   with slides, not fades (the user dislikes fades); `animateItem` in lists; colour animation on attendance
   marks; extended FABs collapsing on scroll. Nothing decorative.
3. Optional native extras from `docs/phone-design.md`.

## Rules that save time
- Windows. Before EVERY Gradle call in PowerShell run `$env:PATH = $env:PATH -replace '"',''` (the user's PATH has a
  stray double quote that breaks the unit-test JVM). If a daemon started with the bad PATH, run `gradlew --stop`.
- Parallel subagents are welcome but keep to ~4 at once (about 7 GB RAM free; each build costs 2-3 GB). Give each one its
  own files, tell them to compile only, and let only one process use the emulator. The user was close to usage limits
  last time: keep agents few and their scopes small, and ask for concise reports.
- If stale generated classes appear (e.g. `AttendanceDatabase_Impl` not found on device) run
  `gradlew :app:kspDebugKotlin --rerun :app:compileDebugKotlin --rerun`.
- Android's regex engine rejects a bare `}`: escape braces, and cover regex code with an instrumented test.
- Material 3 is pinned to 1.5.0-alpha29 for Expressive APIs (opt in with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`).
  If it causes trouble, fall back to the BOM's stable 1.4.0 and tell the user.
- Known kit gaps: `AppTextField` has no IME action/capitalisation options (use `SetupTextField`), `DropdownField` has
  no error slot, `AppSheet` uses a deprecated sheet-state call, no repository exposes enrollments of all classes
  (`StudentRepository.observeAllEnrollmentRows()` would be cleaner than the direct DAO use in `RegistersTabScreen`).
