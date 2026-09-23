---
id: oa-udel
status: closed
deps: []
links: []
created: 2026-09-21T13:29:19Z
type: chore
priority: 2
assignee: Jasmin Le Roux
---
# TabBar tabIndicatorRow exceeds detekt LongMethod limit

Problem:
`./gradlew :app:detekt` fails on unmodified code at app/src/main/java/dev/blazelight/p4oc/ui/tabs/TabBar.kt:236.

Evidence:
Running `./gradlew :app:detekt` on clean HEAD (git stash of all working-tree changes) still reports exactly one finding:

  TabBar.kt:236:13: The function tabIndicatorRow is too long (63). The maximum length is 60. [LongMethod]
  Analysis failed with 1 weighted issues.

This is unrelated to the attachment work; it is pre-existing on HEAD. Note that CI (.github/workflows/build.yml) runs :app:lintDebug, :app:compileDebugKotlin, :app:testDebugUnitTest, and :app:assembleDebug but does NOT run :app:detekt, so CI is green while the documented local detekt gate fails.

ux constraint: None (static analysis only). Do not change tab indicator rendering behavior.

expected behavior: `./gradlew :app:detekt` passes with no LongMethod finding at TabBar.kt.

Acceptance criteria:
- TabBar.kt tabIndicatorRow is at or under the 60-line limit, or a narrowly justified baseline/@Suppress entry is added.
- `./gradlew :app:detekt` exits 0.
- Tab indicator rendering is unchanged.

Verification:
Run `export JAVA_HOME=/usr/lib/jvm/java-17-openjdk && ./gradlew :app:detekt` and confirm exit 0 with no TabBar findings.


## Notes

**2026-09-23T10:13:55Z**

Refactored the tab close target into a small composable; integrated :app:assembleDebug, :app:testDebugUnitTest, :app:detekt and :app:lintDebug passed in Crabbox (run_ec5c64777f5102f4a18e866286625e03 for tests/detekt/lint). On Samsung R58X70XHB9P isolated .reviewproof build, close target was rendered and tapped; Home returned while the saved session remained. Screenshot .crabbox/captures/release-review/21-tab-close.png.
