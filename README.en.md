# MobileGH

MobileGH is a third-party GitHub client for Android built with Kotlin and Jetpack Compose. It delivers native repository and code reading, complete Issue / PR / Discussions workflows, Actions management and log diagnostics, mirror-accelerated downloads, and optional on-device offline translation.

[简体中文](README.md) · English · [Download the latest release](https://github.com/sheep-programmer/mobilegh/releases/latest) · [Report an issue](https://github.com/sheep-programmer/mobilegh/issues)

## Core Highlights

- **Native Reading & Offline Translation**: Native Compose rendering for READMEs (table of contents, image/table zoom, dynamic badge racing fallback, video and math support) plus an optional standalone translation plugin for on-device Chinese–English and multi-language translation.
- **Cache-First Instant Startup**: Disk and in-memory snapshots for Home, Starred repositories, repository feeds, and list details—showing cached content immediately on cold start with a non-blocking background refresh indicator.
- **Full Code & Repository Workflows**: Syntax-highlighted source viewing, line-by-line Blame, unified/split diffs, multi-file/folder commits with Git LFS (up to 2 GiB), and repository import.
- **End-to-End Actions Management**: Inspect jobs and steps, jump to failures, search and export logs, trigger/rerun workflows (with debug logging), approve deployments, and manage Actions settings, device-encrypted Secrets, Variables, Runners, and Caches.
- **Deep Issue & PR Collaboration**: Issue templates and YAML forms, Issue Types, parent/sub-issues, Saved Replies, inline PR reviews and code suggestions, PR viewed-file sync, and creating issues directly from Android system share.
- **Mirror Acceleration, Downloads & Auto-Update**: Independent mirror settings for files/images vs. downloads/clones, Release asset filtering, pause/resume, SHA-256 verification, APK extraction from Actions artifact ZIPs, and a dedicated update UI with automatic installer launch.
- **Security & Adaptive Layout**: Multi-account switching, app lock, on-device TOTP authenticator, background notifications with working hours, and adaptive navigation rail on tablets and foldables.

## Screenshots

Captured from the latest emulator build using fictional accounts and local test fixtures.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/en/01-home.png" alt="Home workspace and shortcuts" width="240"><br>Home</td>
    <td align="center"><img src="docs/screenshots/en/03-repo.png" alt="Repository details and quick actions" width="240"><br>Repository</td>
    <td align="center"><img src="docs/screenshots/en/19-file-readme.png" alt="Native Markdown and README rendering" width="240"><br>Native README</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/en/04-issue.png" alt="Issue description, comments, and actions" width="240"><br>Issue discussion</td>
    <td align="center"><img src="docs/screenshots/en/05-diff-split.png" alt="Side-by-side diff for a commit" width="240"><br>Split diff</td>
    <td align="center"><img src="docs/screenshots/en/21-job-log-dark.png" alt="Actions job log in dark mode" width="240"><br>Actions logs</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/en/07-notifications.png" alt="Notifications grouped by repository" width="240"><br>Notifications</td>
    <td align="center"><img src="docs/screenshots/en/12-star-overview.png" alt="Star lists overview with search and sort" width="240"><br>Star lists</td>
    <td align="center"><img src="docs/screenshots/en/08-insights.png" alt="Contributor stats and commit trends" width="240"><br>Insights</td>
  </tr>
</table>

<details>
<summary>More screenshots (Repositories, Code, Branches, Search, Achievements, Settings & Tablet layout)</summary>

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/en/02-repos.png" alt="Repository list" width="240"><br>Repositories</td>
    <td align="center"><img src="docs/screenshots/en/18-file-code.png" alt="Syntax-highlighted source code" width="240"><br>Source code</td>
    <td align="center"><img src="docs/screenshots/en/06-compare.png" alt="Compare branches and commits" width="240"><br>Compare</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/en/03b-branch-picker-sheet.png" alt="Branch and tag picker" width="240"><br>Branch picker</td>
    <td align="center"><img src="docs/screenshots/en/03c-readme-toc-sheet.png" alt="README table of contents" width="240"><br>README TOC</td>
    <td align="center"><img src="docs/screenshots/en/09-star-list.png" alt="Star list details" width="240"><br>Star list detail</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/en/16-search-results.png" alt="Global search results" width="240"><br>Search results</td>
    <td align="center"><img src="docs/screenshots/en/22-issues.png" alt="Issue list and filters" width="240"><br>Issue filters</td>
    <td align="center"><img src="docs/screenshots/en/23-pushes.png" alt="Recent pushes" width="240"><br>Recent pushes</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/en/25-achievements.png" alt="GitHub achievements" width="240"><br>Achievements</td>
    <td align="center"><img src="docs/screenshots/en/10-settings.png" alt="App settings" width="240"><br>Settings</td>
    <td align="center"><img src="docs/screenshots/en/11-login.png" alt="Sign in and authorization" width="240"><br>Sign in</td>
  </tr>
</table>

<img src="docs/screenshots/en/26-tablet-settings.png" alt="Landscape tablet with left navigation and settings filling the remaining width" width="800">

</details>

## Download and sign in

Requires **Android 8.0 (API 26) or later**. See the latest Release for the v0.40.13 ARM64 APK and per-ABI checksums. [Release notes](docs/RELEASE_v0.40.13.md)

| Device | APK |
| --- | --- |
| Most Android phones and tablets | `app-arm64-v8a-release.apk` |
| Older 32-bit ARM devices | `app-armeabi-v7a-release.apk` |
| x86-64 devices or emulators | `app-x86_64-release.apk` |
| Unsure about your architecture | `app-universal-release.apk` |

Each release includes `SHA256SUMS.txt`. Sign in with GitHub authorization or a personal access token (PAT). Repository actions depend on your account permissions, granted scopes, and organization policies. [Downloads](https://github.com/sheep-programmer/mobilegh/releases/latest) · [Authentication and permissions](docs/AUTH_CAPABILITIES.md)

## Features

| Area | What you can do |
| --- | --- |
| Browse repositories | Read natively rendered READMEs (with TOC navigation, `data-path` relative link resolution, image/table zoom, and dynamic badge racing fallback), find source files, view Blame, and sort forks by time, stars, watchers, or name. |
| Collaborate and review | Work with Issues, pull requests, and Discussions, including templates/YAML forms, Issue Types, sub-issues, Saved Replies, Markdown drafts, inline review comments, code suggestions, unified/split diffs, and PR viewed-file sync. |
| Actions and import | Inspect jobs and steps, jump to failures, search and export logs, dispatch or rerun workflows (with debug logging), approve deployments, configure Actions settings, Secrets (device-encrypted), Variables, Runners, and Caches, and import repositories. |
| Upload and maintain | Edit files, create branches, manage releases, and upload multiple files or folders in one commit. Git LFS handles files over GitHub’s regular limit (up to 2 GiB). |
| Organize your work | Customize Home shortcuts and favorites; use GitHub and local star lists with cold-start caching and background silent refresh; group notifications by repository with bulk actions; view your contribution heatmap and achievements. |
| Accounts and security | Switch between accounts, lock the app, generate TOTP codes on the device, configure background notifications with working hours, and export `.log` diagnostic files and crash reports. |
| Reading, mirrors & updates | Chinese/English UI, dark mode, optional floating bottom bar, offline translation, and adaptive tablet rail; independent mirror settings for files/images vs. downloads/clones, Release filtering, pause/resume, SHA-256 verification, and dedicated auto-installing app updates. |

### Offline translation

Translation is off by default. Enable it under **Settings → Appearance & language → Reading**. The app prompts you to download and install the optional component, then downloads the Chinese–English model. The switch turns on after the model passes integrity checks and a translation test in both directions. Other languages can be downloaded on demand.

Use the page’s **⋯** menu to switch between the translation and the original. Translation runs on the device, with results cached in memory for the current session. The component and downloaded language models are stored separately from the main APK.

### Downloads and updates

Open download history from the top-right corner of Home. The dash in the top-right corner of a download panel minimizes it. Closing the panel leaves tasks and files accessible from download history. When GitHub supplies a SHA-256 digest, the app verifies the file before saving it.

In-app updates use a dedicated update UI distinct from regular file downloads and automatically launch the system package installer once the update APK finishes downloading. Network settings allow configuring acceleration mirrors independently for files/images and downloads/clones.

## Build and contribute

Use JDK 17 or later and Android SDK 36. The repository contains `app` (Kotlin, Compose UI, and GitHub APIs) and `translation-plugin` (the separately installable offline translation component).

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease :translation-plugin:assembleRelease
```

When preparing a release, attach `translation-plugin/build/outputs/apk/release/translation-plugin-release.apk` to the same GitHub Release as `mobilegh-offline-translation.apk`. Sign both APKs with the same certificate. The component asset must have a SHA-256 digest in GitHub Release metadata. Release signing reads local `keystore.properties` or the `MOBILEGH_*` signing environment variables. With no release key configured, the build uses debug signing.

Use `tools/readme_screenshots.sh` on a dedicated emulator to regenerate the Chinese and English screenshots. Include the app version, Android version, reproduction steps, and screenshots when reporting a problem. [Feature list](docs/FEATURES.md) · [Release regression checklist](docs/RELEASE_REGRESSION.md) · [Open an issue](https://github.com/sheep-programmer/mobilegh/issues/new/choose)

## License

MobileGH’s original code is licensed under the **GNU Lesser General Public License v3.0 or later (`LGPL-3.0-or-later`)**. Redistribution of a modified version carries the license’s source and notice obligations. The LGPL’s additional permissions and conditions apply when linking with other code. [LGPL text](LICENSE) · [Underlying GPL v3 terms](COPYING)

Third-party code, dependencies, and assets retain their own licenses or terms, including Google ML Kit in the optional translation component. [Bundled web assets and third-party notices](app/src/main/assets/web/THIRD_PARTY_LICENSES.txt)
