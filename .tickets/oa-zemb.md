---
id: oa-zemb
status: closed
deps: []
links: []
created: 2026-07-09T00:00:00Z
type: task
priority: 1
assignee: Jasmin Le Roux
---
# Make saved server removal safe

## Problem

`ServerScreen` renders destructive saved-server removal as inline raw text: `remove` or `warn remove`. Tapping it immediately removes the saved server. When open tabs reference that server, `warn remove` looks like a status label rather than a destructive action, and it does not explain what happens to those tabs.

This violates the project UI rule that row-specific actions belong in long-press or overflow menus and creates data-loss/footgun risk.

## Evidence

- `app/src/main/java/dev/blazelight/p4oc/ui/screens/server/ServerScreen.kt` currently renders `text = if (server.endpointKey in openTabEndpointKeys) "warn remove" else "remove"` in the saved server row.
- `local-adb-screenshots/oa-5sro-visual-qa/17_server_removal_open_tabs_warning.png` shows `warn remove` inline next to `Remote Server`.
- AGENTS.md Agent-Space UI Rule prefers row-specific actions in long-press or overflow menus, not persistent inline destructive text.
- The same screen already has open-tab endpoint awareness; use that count to explain consequences before removal.

## UX Constraint

Row tap should select/connect to a server. Destructive actions must be secondary, explicit, localized, and confirmed when open tabs are affected.

## Expected Behavior

Saved server rows expose an overflow action affordance with accessible semantics. The destructive item is named `Forget server`, appears last, and opens a confirmation dialog when open tabs reference that server.
- Keep server row tap reserved for connect/select. Do not overload it with destructive management.
- Follow Agent-Space UI Rule: row-specific actions should be contextual/overflow, not always-visible inline chrome.

Open-tab confirmation copy should explain the consequence, for example:

`1 open tab is using this server. Existing tabs will stay open until closed or reconnected, but this server will be removed from saved targets.`

## Acceptance Criteria

- Inline `remove` / `warn remove` text is gone from saved server rows.
- Saved server rows have an overflow/menu action with a content description and test tag.
- The overflow menu includes `Forget server` as a destructive action.
- Forgetting a saved server with open tabs shows a confirmation dialog with the open-tab count and consequence.
- Forgetting a saved server without open tabs is still explicit and not triggered by tapping the row body.
- Visible strings are localized in `strings.xml`.
- Key interactions have content descriptions/test tags.
- Compile and detekt pass.

## Failure States To Avoid

- Do not silently delete a saved server from a warning-colored inline label.
- Do not imply open tabs will be closed if they will remain open, or imply they remain safe if removal will break reconnect.
- Do not leave `warn remove` or `remove` as raw visible strings in code.
- Do not move the destructive action into the row body tap target.

## Verification

- Capture or inspect the saved server row: no inline `warn remove` should appear.
- With an open tab for a server, choose Forget server and verify confirmation appears before removal.
- Run `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:compileDebugKotlin && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:detekt`.

## Verification Notes

- 2026-07-09: Implementation changed saved server rows from inline `remove` / `warn remove` text to an overflow menu with localized `Forget server` and a destructive `TuiConfirmDialog` that includes open-tab count when applicable.
- 2026-07-09: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:compileDebugKotlin && JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:detekt` passed.
- 2026-07-09: Device verification is still required before closing. `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew :app:installDebug` failed with `No connected devices`, and `adb devices` returned no connected device. Do not close until the overflow -> Forget server -> confirmation dialog flow is screenshotted with an open tab referencing the saved server.

## Notes

**2026-07-10T07:39:56Z**

Implementation and integrated compile/detekt/tests pass. Current-device server inventory screenshots verify the inline destructive label is gone, but the required open-tab overflow -> Forget server -> confirmation-dialog screenshot was not captured. Ticket remains in progress per its explicit verification gate.

**2026-09-23T09:39:40Z**

2026-09-23: On Samsung R58X70XHB9P in isolated build dev.blazelight.p4oc.reviewproof connected to OpenCode 1.18.31, opened a session tab for saved server 127.0.0.1:14097, then captured saved-server overflow (.crabbox/captures/release-review/13-overflow-open-tab.png) and Forget server confirmation with 1 open tab and explicit Review tabs / Close tabs and forget choices (.crabbox/captures/release-review/14-forget-open-tab-confirmation.png). Back dismissed without removal; saved server remains (.crabbox/captures/release-review/15-server-preserved-open-tab.png). The required screenshot gate is satisfied. No destructive action was taken.
