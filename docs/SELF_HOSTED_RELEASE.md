# Self-Hosted Packaging and Releases

## Workflow

`.github/workflows/build.yml` runs host-free checks on GitHub's Windows runner. Only successful trusted main pushes or manual main runs can schedule the repository-scoped `salt-extend-windows` runner with `self-hosted`, `Windows`, `X64` and `salt-1.19.3` labels.

The `salt-build` environment restricts deployment branches to main. `SALT_PACKAGE_ENABLED=true` enables packaging. Environment variables provide `SALT_HOME`, `SALT_JDK_HOME` and a persistent `SALT_KOTLIN_HOME`. The build reads the existing Salt installation, without installing a mod, restarting Salt or reading saved accounts.

The local runner has read-only workflow permissions. Only the separate GitHub-hosted Linux release job receives `contents: write`; checkout does not persist credentials. Actions are pinned to commit hashes. External PRs run only host-free checks, never local packaging.

Public-repository self-hosting is not a security sandbox. Any trusted main workflow/code can execute as the runner user. Keep repository write access restricted; this setup does not claim Windows account isolation or protection from compromised maintainer code.

## Version Policy

Every eligible main run builds and uploads a validated `.spmod` and SHA-256 checksum. Publishing verifies the checksum, manifest/configuration and absence of bundled host/test classes again.

A new `plugin.properties` version creates `v<version>` at the exact build commit. Assets are first uploaded to a draft, checked for completeness, then published as a development prerelease. A version already published is not overwritten; bump the version for new distributable changes. A conflicting existing tag stops publication. Interrupted unpublished drafts can be completed by rerunning the same build.

Published prereleases are not marked as the latest stable release. Actions artifacts remain available for 14 days, independently of permanent release assets.

## Local Runtime

Runner files reside outside the checkout at `E:/MTool/Actions/salt-extend-runner`. This machine uses a current-user logon entry named `SaltExtendActionsRunner` to launch `start-runner.ps1` with a hidden window. It is not an administrator-level Windows service and does not require a Windows password. A named mutex prevents duplicate listeners; the official runner helper retains automatic updates.

The runner needs this user logged in, the computer awake, the configured local network proxy available, and the existing JDK/Salt paths intact. Logging out or shutting down takes it offline; eligible jobs wait for it to return.

The persistent Kotlin compiler cache is outside the disposable checkout. A final workflow step removes only checked, absolute paths beneath this job's workspace: `plugin/build`, `core/build`, `scripts/__pycache__` and `.tools`. It never deletes the existing Salt/JDK installation or unrelated development builds.

Disabling `SALT_PACKAGE_ENABLED` stops new local packaging schedules. To stop autostart, remove only the named current-user logon value, then stop only this runner's owned supervisor/listener processes. Remove the repository runner registration separately when retiring the machine.
