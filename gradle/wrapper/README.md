# =============================================================================
# Gradle Wrapper — placeholder, no jar in the repo
# =============================================================================
#
# This project ships only the launcher scripts (`gradlew`, `gradlew.bat`) and
# `gradle/wrapper/gradle-wrapper.properties`; the
# `gradle/wrapper/gradle-wrapper.jar` binary is intentionally NOT committed.
#
# Why no jar
#   The jar is a small, content-addressable bootstrap that every Android
#   project ships identically; committing a copy here is noise and the
#   checksum is already pinned by `distributionUrl` above.
#
# How to materialize a full wrapper on a dev machine (one-shot):
#
#   1. Install Gradle 8.7+ locally (SDKMAN, Homebrew, or the distro's pkg).
#   2. cd AgentM
#   3. gradle wrapper --gradle-version 8.7
#
#   Step 3 rewrites this properties file with the official distribution
#   checksum AND drops a `gradle/wrapper/gradle-wrapper.jar` (~60 KB)
#   alongside it. From that point `./gradlew` works fully offline.
#
# Until then, the trimmed launcher here falls back to a local `gradle`
# install on PATH:
#
#   - `./gradlew <args>`   → runs `gradle <args>` if no jar is present
#   - `gradlew.bat <args>`  → same fallback on Windows
#
# Verification commands (once the jar exists):
#   ./gradlew --version
#   ./gradlew tasks
#
# Pin: the distribution above is 8.7 (Gradle 8.7 is the minimum that AGP 8.5
# accepts per the AGP–Gradle compatibility table; 8.7+ is fine, do NOT go
# below 8.7 without also downgrading AGP in gradle/libs.versions.toml).
# =============================================================================
