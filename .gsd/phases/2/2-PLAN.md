---
phase: 2
plan: 1
wave: 1
---

# Plan 2.1: CI Optimization for NDK Builds

## Objective
Drastically reduce GitHub Actions build times from ~15 minutes by bypassing unnecessary linting/testing steps, restricting HeliBoard's NDK C++ cross-compilation strictly to the `arm64-v8a` ABI, and persisting native C++ object files between runs using GitHub Actions caching.

## Context
- .gsd/SPEC.md
- .gsd/ROADMAP.md
- .github/workflows/build.yml

## Tasks

<task type="auto">
  <name>Optimize GitHub Actions Build Workflow</name>
  <files>.github/workflows/build.yml</files>
  <action>
    1. Update the "Build DaddyBoard" and "Build DaddyBoard GIF" steps.
    2. Change the gradle run command to bypass lint and tests, run in parallel, and restrict the ABI:
       `./gradlew assembleDebug -x lint -x test --parallel --build-cache -Pandroid.injected.build.abi=arm64-v8a`
    3. Add a dedicated `actions/cache@v4` step before the build steps to cache the NDK `.cxx` directories and `app/build` directories.
       - Use a cache key based on the runner OS and a hash of the C++ source files (e.g. `hashFiles('**/*.cpp', '**/*.h', '**/CMakeLists.txt')`).
       - Path should include `daddyboard-main/app/.cxx`, `daddyboard-main/app/build`, `daddyboard-gif/app/.cxx` (if applicable), and `daddyboard-gif/app/build`.
  </action>
  <verify>cat .github/workflows/build.yml | grep "actions/cache@v4" && cat .github/workflows/build.yml | grep "\-Pandroid.injected.build.abi=arm64-v8a"</verify>
  <done>The workflow file uses the optimized gradle command with abi injection and includes the cache step for .cxx and build directories.</done>
</task>

## Success Criteria
- [ ] GitHub Actions caching step is properly integrated for `.cxx` and `build` folders.
- [ ] Gradle build command restricts compilation to `arm64-v8a` and skips lint/test.
