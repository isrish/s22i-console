# Contributing to S22i Console

Contributions can be code, documentation, accessibility improvements, bug reports or feature ideas. You do not need a bike to improve the docs or run the local tests.

| I want to… | Start here |
| --- | --- |
| Suggest a feature | [Open a feature request](https://github.com/isrish/s22i-console/issues/new?template=feature_request.yml) |
| Report a problem | [Open a bug report](https://github.com/isrish/s22i-console/issues/new?template=bug_report.yml) |
| Change the code or docs | Follow the pull request steps below |
| Understand the app | Read [AGENTS.md](AGENTS.md) and [protocol constraints](docs/PROTOCOL.md) |

## Feature requests

Search [existing issues](https://github.com/isrish/s22i-console/issues) first. If an idea already exists, add your use case to that discussion.

Use the feature request form to explain the problem, who it affects, the desired behavior and how someone could tell the feature works. Include alternatives you considered. Describe whether it changes controller behavior, saved data, networking or permissions. A sketch using fictional data is welcome; a large implementation is not required to propose an idea.

For a major feature, new dependency, controller change or architecture change, discuss the approach in an issue before investing in a large PR. Small fixes and documentation corrections can go straight to a PR. An open request is a discussion, not a commitment to implement it.

## Bug reports

Include the app version and build type, Android version if known, reproducible steps, expected behavior and what actually happened. For controller problems, distinguish a displayed value from a physical change at the pedals. Say whether the app was connected or a ride was active, without sharing an actual ride record.

Use a short sanitized error excerpt when it helps. Do not attach complete logs, device inventories, authentication packets or saved rides. Reproduce UI problems with empty state or synthetic sample data before sharing screenshots. Do not repeat a risky hardware action solely to improve a report.

## Set up a contribution

1. Fork the repository on GitHub, then clone your fork. Replace `YOUR-USERNAME` below with your GitHub handle.
2. Create a focused branch from the current upstream `main`.
3. Read the relevant architecture and protocol guidance before editing.

```sh
git clone https://github.com/YOUR-USERNAME/s22i-console.git
cd s22i-console
git remote add upstream https://github.com/isrish/s22i-console.git
git fetch upstream
git switch -c feature/short-description upstream/main
```

Use your GitHub-provided noreply email for public commits. Do not include a personal email address in files or PR descriptions.

For development, install Python 3.9+ and JDK 17. Building the Android app also requires SDK platform 34 and build-tools 34.0.0; see [build setup](README.md#build). The project uses native Java and Python scripts, with no Gradle or downloaded app libraries.

## Make and verify the change

Keep a PR focused on one problem. Preserve existing style, third-party notices and the offline design. Put explanatory rationale in the relevant documentation; source files intentionally have no explanatory comments. Use synthetic inputs for regression tests. Add a test when it protects meaningful behavior, especially protocol validation, ride recovery, statistics or privacy.

For app behavior changes, run:

```sh
python3 scripts/test_protocol.py
python3 -m unittest discover -s tests -v
python3 scripts/build.py --debug
```

A local debug build uses your own signing identity. It cannot directly replace a published APK signed by the maintainer. Building does not install the app; do not uninstall a working installation or erase ride data just to test a contribution. Production signing keys are private and are not needed to submit a PR.

For documentation-only changes, check links, formatting and publication rules; no APK build or hardware test is required. When templates or publication checks change, also validate their syntax and run the Python tests.

Stage only the intended files, inspect the staged diff, then run:

```sh
git diff --cached
git diff --cached --check
python3 scripts/check_publication.py
```

The publication check scans the Git index, not unstaged edits. Its allowlist is deliberate: a legitimate new source or documentation path may need an explicit addition to `scripts/check_publication.py`. Do not weaken it to admit logs, keys or private data. README images require a separate visual and metadata review before their approved hash is updated.

## Open a pull request

Commit your focused change and push the branch to your fork:

```sh
git commit -m "Describe the problem solved"
git push -u origin feature/short-description
```

Open a PR against **isrish/s22i-console → main**. Use a draft PR when you want early feedback or still have unfinished work.

Fill in the PR template with the problem, resulting behavior, related issue and validation. Include the commands you ran and their results; state clearly when hardware behavior is untested. For UI changes, show empty-state or synthetic-data screenshots. Link a related issue with `Refs #123`; use `Closes #123` only if the PR fully resolves it.

Reviewers should be able to assess the change without reading a chat transcript. Keep unrelated formatting or refactoring out of the PR. Respond to feedback with follow-up commits and keep the title and description aligned with the final change. Resolve CI failures before requesting final review; passing tests does not itself establish hardware compatibility. Maintainers review and merge contributions.

## Controller and privacy boundaries

- On-screen resistance writes are currently restricted to levels 1 and 2. Displayed levels and readback patterns must not silently expand that limit.
- Keep USB ownership, fresh-state checks, explicit user actions and ride recovery intact. Never automatically retry actuator writes or introduce reset, calibration or firmware commands as an incidental change.
- Hardware checks require a person at the bike and an agreed test scope. Do not interrupt an active ride or present simulated results as hardware verification.
- Keep personal paths, network addresses, serial numbers, credentials, real ride records, raw logs, downloaded APKs and signing keys out of commits, issues and PRs. The automated scanner cannot recognize every identifier.
- Preserve the production/debug boundary and existing privacy controls. Do not add networking, telemetry collection or permissions without an explicit design discussion.

Contribute only work you have permission to share under the project's [Apache-2.0 license](LICENSE), and retain compatible upstream attribution. Be respectful and specific in reviews; explain the behavior or tradeoff you want to improve.
