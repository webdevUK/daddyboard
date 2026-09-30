# ROADMAP.md

## Phase 1: Setup and Security (Completed)
- Create `daddyboard-main` and `daddyboard-gif` projects.
- Setup `shared_debug.keystore` for shared signature permissions.
- Implement Headless IPC ContentProvider Bridge and UI overlays.

## Phase 2: CI Optimization (In Progress)
- Optimize `.github/workflows/build.yml` to reduce GitHub Actions build times from 15 minutes.
- Skip lint/test, restrict NDK compilation strictly to `arm64-v8a`, and cache C++ object files.

## Phase 3: Native UI Porting
- Integrate native UI porting in the main app to support the new features.

## Phase 4: Polish and Release
- E2E testing of the IPC bridge.
- Release candidate build.
