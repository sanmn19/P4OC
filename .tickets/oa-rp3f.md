---
id: oa-rp3f
status: closed
deps: []
links: []
created: 2026-09-20T12:25:09Z
type: feature
priority: 2
assignee: Jasmin Le Roux
external-ref: gh-69
---
# Make phone attachments discoverable and reviewable

Problem: #69 reports missing direct phone uploads despite existing OFISH upload support hidden in the remote picker.
Evidence: ChatScreen opens FilePickerDialog; [Upload] launches OpenMultipleDocuments and immediately uploads.
UX constraints: Preserve compact themed chat, square controls, exact tab/server workspace ownership, existing transport and safe image loader.
Expected behavior: Existing + opens Photos, Files from phone, Workspace files. Phone selection is reviewed with metadata and destination before Upload & attach; native photo/document pickers, compact image previews, explicit readiness and recoverable upload failures; preserve draft and uploaded files, never silently overwrite or send incomplete selection.
Design: https://app.paper.design/file/01M2Z6MZQZRV2KN1JVJHXYE6R6/p-1-0
Verification: Remote Crabbox build/unit validation and explicit Samsung device UI journey, including image-only send and mixed upload failure.

## Acceptance Criteria

Phone source chooser, upload review and folder selection; exact workspace-bound destination; bounded image previews; selected-model image capability error; incomplete upload send refusal; actionable retry/removal; current flow before/after screenshots and Android verification.


## Implementation Notes (2026-09-20)

Implemented the reviewable phone-attachment flow:

- `AttachmentSourceSheet` (new, `ui/components/chat/PhoneAttachmentSheet.kt`): attach button opens Photos / Files from phone / Workspace files chooser.
- `PhoneAttachmentSheet` (new, same file): pre-upload review with per-file name/mime/size, oversized-file error, workspace + folder destination, Change folder (directory-only picker mode), Remove, Upload & attach.
- `ComposerAttachmentRail` (new, `ui/components/chat/ComposerAttachmentRail.kt`): compact draft rail with image thumbnails (compact `ChatAttachment` mode) and per-file details dialog.
- `FilePickerManager`: `phoneReview` state, `reviewPhoneFiles`/`removePhoneFile`/`cancelPhoneReview`/`confirmPhoneUpload`, `hasUnresolvedUploads`, picker job cancellation, dedup of delivered uploads.
- `FilePickerDialog`: `choosingDirectory` mode, `workspaceLabel`, `SelectedFile.sizeBytes`, shared `toOpenCodeFileUrl`.
- `UploadCoordinator`: `isActive` mirroring, cancellation propagation, completed-items delivery on cancel, final-state ordering.
- `UploadOrchestrator`: `createOnly` no-overwrite uploads, user-facing CANCELLED/CONFLICT/FAILURE messages, unknown-size progress.
- `UploadProgressSheet`: preparing state (no terminal summary while preparing), per-item failure messages, Remove failed action, context label.
- `ChatScreen`: pending-upload review entry point, owner-keyed picker result guard, upload sheet visibility state.
- `ChatViewModel`: `attachmentError` gate (unresolved uploads, missing workspace, image-incapable model) on both send paths.

Device proof on Samsung R58X70XHB9P (debug build `dev.blazelight.p4oc.debug.attachmentsproof`, OpenCode 1.18.31, isolated project `/workspace/p4oc-smoke/project`): cancelled review left empty file/message state; reviewed upload to `uploads/` attached without sending; image rejected on image-incapable model with draft preserved; image-only and mixed text+txt+jpg sends confirmed via server receipts; partial name collision preserved existing file (SHA256 6d814509…); retry after moving the conflict succeeded without resurrecting a removed item. Screenshots under `/tmp/p4oc-proof-*.png`. Full unit suite passed on the pre-cleanup build; detekt not clean (pre-existing). Pending final validation before close.

## Notes

**2026-09-20T15:09:27Z**

Final verification: normal debug APK assembled; full suite 1125 tests, 0 failures/errors, 1 skipped. Detekt has only existing ui/tabs/TabBar.kt:236 LongMethod (tracked oa-0p94); no attachment findings and baseline unchanged. Portable create-only shell regressions pass, including strict POSIX ln, file/directory races and foreign nested-file preservation; not run on an actual macOS host. Final isolated Samsung build rechecked source chooser, native document review/destination, existing-file collision, themed pending-review banner, and Remove failed clearing the queue. Existing upload SHA256 remained f6f3246e69a74ea0bb2c2711462972ccc9f60b47ebb2ca1b0ce1473a0fcc9c59. Earlier real device image-only/mixed sends, compact draft with keyboard, retry/cancel and safe viewer proofs retained. Deliverable APK/screenshots/unit summary in .crabbox/captures/attachments-final/. Temporary proof app, device forwarding and synthetic phone fixtures removed; normal installations untouched. No commit or publication.

**2026-09-21T10:30:14Z**

User requested visual finish after screenshot review: restore compact icon-led source chooser, group destination review with primary action, and make attachment names/type/removal clear while retaining compact keyboard footprint. Visual-only scope; no upload/protocol/state redesign. Verify actual Samsung light/dark screenshots before completion.

**2026-09-21T11:50:56Z**

Attachment UI visual polish completed. Source chooser is now a compact icon-led TUI sheet; upload review groups exact destination and file identity with a clear primary action; draft cards use uniform previews/icons, middle ellipsis, type/size metadata, accessible removal, and a themed details dialog. Verified on physical Samsung R58X70XHB9P against a real OpenCode 1.18.31 server in light and dark themes, including phone upload, workspace selection, mixed PNG/TXT filename differentiation, keyboard footprint, and details dialog. :app:assembleDebug passed. Detekt reports no attachment-file findings; the existing unrelated TabBar.kt:236 LongMethod remains tracked by oa-0p94. Evidence: .crabbox/captures/attachments-polish/.

**2026-09-21T13:29:25Z**

Post-completion release-readiness audit found and fixed two defects introduced by this work: (1) ChatScreen.kt used a raw M3 AlertDialog for the attachment-owner-changed error, failing scripts/check_theme_violations.sh; replaced with TuiAlertDialog + TuiButton. (2) Attachment UI added ~33 hardcoded display strings; all now live in strings.xml (including a plural for the review file count). Also fixed a detekt Wrapping finding in the same region. Verified on Crabbox (agent-sandbox-ssh): :app:compileDebugKotlin exit 0, :app:testDebugUnitTest exit 0, :app:lintDebug exit 0, theme check exit 0. :app:detekt reports only the pre-existing TabBar.kt:236 LongMethod, now tracked as oa-udel (reproduces on clean HEAD).

**2026-09-23T10:14:12Z**

Release-readiness follow-up: corrected upload-result Dismiss queue state so a completed sheet does not reopen or block Send, stabilized attachment owner across asynchronous session loading, localized picker/probe errors and clarified disabled Send accessibility. OFISH shell paths beginning with a dash and upload finish destination-directory collision were fixed; 3 behavior regressions added. Integrated isolated Crabbox build assembled; :app:testDebugUnitTest, :app:detekt and :app:lintDebug all passed (run_ec5c64777f5102f4a18e866286625e03). On physical Samsung R58X70XHB9P, isolated .reviewproof app connected to OpenCode 1.18.31 and attachment source sheet rendered; .crabbox/captures/release-review/19-integrated-chat.png and 20-attachment-source.png. The new Dismiss behavior itself has not been re-exercised on device in this follow-up.
