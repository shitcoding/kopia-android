# Fix All 10 Failing E2E Maestro Tests

**Goal:** Fix all 10 failing Maestro E2E tests so the full suite (38/38) passes on sharded AVD execution.

**Architecture:** Two root causes affect all 10 failures: (1) wrong cleanup order in flow preambles causes KopiaKt to launch behind DocumentsUI when a prior restore flow left the SAF picker open; (2) missing test data cleanup for repo creation paths. Fix is mechanical: reorder 3 YAML lines in all 38 flows + add 2 cleanup lines to setup script.

**Tech Stack:** Maestro E2E framework, YAML flow files, shell scripts, Android emulator (AVD)

---

## Background

### Current state: 28/38 pass, 10 fail

All 38 Maestro flows start with this cleanup preamble:
```yaml
- stopApp                                    # Kill KopiaKt
- launchApp                                  # Launch KopiaKt
- stopApp: "com.google.android.documentsui"  # Kill SAF picker
```

### Root Cause 1: Wrong cleanup order (affects 8-10 tests)

When a restore flow opens the Android SAF file picker (`com.google.android.documentsui`) and the flow ends, DocumentsUI remains in the foreground. The next flow's `launchApp` executes BEFORE DocumentsUI is killed, so KopiaKt launches behind DocumentsUI. When DocumentsUI is finally killed (step 3), KopiaKt is in the background and its WebView never renders. The `extendedWaitUntil: visible: "KopiaKt"` assertion then times out.

**Fix:** Reorder to kill DocumentsUI BEFORE launching KopiaKt:
```yaml
- stopApp                                    # Kill KopiaKt
- stopApp: "com.google.android.documentsui"  # Kill SAF picker FIRST
- launchApp                                  # Launch KopiaKt (foreground clear)
```

### Root Cause 2: Missing test data cleanup (affects 2 tests)

`setup_test_repo.sh` cleans `/sdcard/testrepo` and `/sdcard/v1repo` but NOT:
- `/sdcard/KopiaTestRepo` (used by `backup_create_repo_local.yaml`)
- `/sdcard/KopiaNegativeTestRepo` (used by `backup_create_repo_local_negative.yaml`)

If these directories contain a Kopia repository from a prior run, the create-repo wizard's "Test Connection" or "Create Repository" steps behave differently (detecting existing repo).

### All 10 failing tests mapped to root causes

| Flow | Root Cause |
|------|-----------|
| `full_e2e_flow` | Cleanup order |
| `browse_files` | Cleanup order |
| `connect_v1_repo` | Cleanup order |
| `exitdoor_disconnect_source_snapshots` | Cleanup order |
| `settings_disconnect` | Cleanup order |
| `filebrowser_batch_select_all_restore` | Cleanup order |
| `filebrowser_batch_select_restore_files` | Cleanup order |
| `filebrowser_restore_directory_preservation` | Cleanup order |
| `backup_create_repo_local` | Cleanup order + test data cleanup |
| `backup_create_repo_local_negative` | Test data cleanup |

---

## Task 1: Reorder cleanup preamble in all 38 flows

**Files:** All 38 YAML files in `e2e/maestro/*.yaml`

This is a mechanical find-and-replace across all 38 files. Every flow has the exact same 3-line pattern.

**Step 1: Run sed to reorder the cleanup lines**

The pattern in every file is exactly:
```yaml
- stopApp
- launchApp
- stopApp: "com.google.android.documentsui"
```

Replace with:
```yaml
- stopApp
- stopApp: "com.google.android.documentsui"
- launchApp
```

Run this command from the project root:
```bash
cd e2e/maestro
for f in *.yaml; do
  sed -i '' '/^- stopApp$/{
    N
    /\n- launchApp$/{
      N
      /\n- stopApp: "com\.google\.android\.documentsui"$/{
        s/- stopApp\n- launchApp\n- stopApp: "com\.google\.android\.documentsui"/- stopApp\n- stopApp: "com.google.android.documentsui"\n- launchApp/
      }
    }
  }' "$f"
done
```

**Step 2: Verify the replacement worked**

Run:
```bash
cd e2e/maestro
grep -A2 '^- stopApp$' *.yaml | head -60
```

Expected: Every occurrence should now show:
```
- stopApp
- stopApp: "com.google.android.documentsui"
- launchApp
```

No file should still have the old order (`stopApp` → `launchApp` → `stopApp: documentsui`).

**Step 3: Fix internal cleanup sequences in backup_create_repo_local_negative.yaml**

This file has 2 additional internal `stopApp`/`launchApp`/`stopApp: documentsui` sequences (Test 2 at ~line 69 and Test 3 at ~line 134). The sed should have caught these too, but verify:

```bash
grep -n -A2 'stopApp$' e2e/maestro/backup_create_repo_local_negative.yaml
```

Expected: All 3 sequences (preamble + Test 2 + Test 3) should show the corrected order.

**Step 4: Commit**

```bash
git add e2e/maestro/*.yaml
git commit -m "fix(e2e): reorder cleanup to kill DocumentsUI before launching KopiaKt

stopApp for DocumentsUI was running AFTER launchApp, causing
KopiaKt to launch behind the SAF file picker when a prior
restore flow left it in the foreground. The WebView never
rendered, causing cascading timeouts on all subsequent flows
in the same shard."
```

---

## Task 2: Add test data cleanup to setup_test_repo.sh

**Files:**
- Modify: `e2e/maestro/scripts/setup_test_repo.sh:30`

**Step 1: Add cleanup lines for create-repo test paths**

In `setup_test_repo.sh`, line 30 currently has:
```bash
$ADB shell "rm -rf /sdcard/testrepo /sdcard/v1repo /sdcard/Download/restore_dest"
```

Change to:
```bash
$ADB shell "rm -rf /sdcard/testrepo /sdcard/v1repo /sdcard/Download/restore_dest /sdcard/KopiaTestRepo /sdcard/KopiaNegativeTestRepo"
```

**Step 2: Verify the change**

```bash
grep 'rm -rf' e2e/maestro/scripts/setup_test_repo.sh
```

Expected: The line should now include all 5 paths.

**Step 3: Commit**

```bash
git add e2e/maestro/scripts/setup_test_repo.sh
git commit -m "fix(e2e): clean up create-repo test paths in setup script

backup_create_repo_local and backup_create_repo_local_negative
create repositories at /sdcard/KopiaTestRepo and
/sdcard/KopiaNegativeTestRepo. These must be cleaned between
test runs to prevent 'repo already exists' failures."
```

---

## Task 3: Run full E2E suite and verify 38/38 pass

**Prerequisites:**
- 2 AVDs running (emulator-5554, emulator-5556)
- App installed: `./gradlew :app-android:installDebug`
- Test repos set up: `e2e/maestro/scripts/setup_test_repo.sh`
- Restore dir set up: `e2e/maestro/scripts/setup_restore_dir.sh`

**Step 1: Set up both AVDs**

```bash
cd e2e/maestro/scripts
for serial in emulator-5554 emulator-5556; do
  ./setup_test_repo.sh $serial
  ./setup_restore_dir.sh $serial
done
```

**Step 2: Install debug APK on both**

```bash
./gradlew :app-android:installDebug
```

**Step 3: Run the full sharded test suite**

```bash
cd e2e/maestro
maestro --device emulator-5554,emulator-5556 test --shard-split 2 .
```

**Step 4: Verify results**

Expected: `38 PASSED, 0 FAILED`

If any tests still fail, investigate individually:
```bash
maestro --device emulator-5554 test <failing_flow>.yaml
```

**Step 5: If all pass, final commit (squash or leave as-is)**

No additional commit needed if Tasks 1-2 are clean.

---

## Task 4: Document the fix

Record a short summary of the fix:
- Root cause: cleanup order (stopApp/launchApp/stopApp:documentsui → stopApp/stopApp:documentsui/launchApp)
- Secondary: missing test data cleanup paths
- Files changed: 38 YAML flows + 1 shell script
- Verification: 38/38 tests passing
