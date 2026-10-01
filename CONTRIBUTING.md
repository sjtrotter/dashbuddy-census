# Contributing

Use JDK 21, the Gradle wrapper, and a sibling app checkout at the agreed contract SHA. Bootstrap the missing wrapper JAR as described in the README. Run `./gradlew build`; Docker enables PostgreSQL migration tests, otherwise their skip reason is visible.

Use Gradle Kotlin DSL, the version catalog, four-space indentation, trailing commas, and KDoc on public types naming the issue (`#1157 S1` for this slice). Warnings fail compilation (#841). Escape literal closing braces in regexes (#909), route wall-clock reads through Clock-named files, log with SLF4J, and keep writes under `java.io.tmpdir`.

Keep migrations forward-only after release. Do not copy the app's contract, weaken exact version pins, add cryptography libraries, or log user data. Include relevant tests and describe behavior and validation in each pull request. This repository is AGPL-3.0-only; use the private process in [security](docs/SECURITY.md) for vulnerabilities.
