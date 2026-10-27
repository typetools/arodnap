# Releasing

One group and version for everything, set in the root `pom.xml`: the group is
`io.github.iamsanjaymalakar` (a Maven Central namespace verified through GitHub), so the Maven
plugin is `io.github.iamsanjaymalakar:arodnap-maven-plugin` and the Gradle plugin
`io.github.iamsanjaymalakar.arodnap`. The Gradle plugin's build reads both from the POM, and the
engine's `tools.properties` gets its tools' coordinates from it.

## What a release publishes

[`.github/workflows/release.yml`](../.github/workflows/release.yml), on a tag `v<version>`:

1. **Maven Central**: `mvn -P release deploy` uploads every module (the model, the engine, the
   command line, the Maven plugin, each tool and the distribution zip) with sources, javadoc and
   signatures, and waits until Central has published them.
2. **Gradle Plugin Portal**: `publishPlugins` publishes the Gradle plugin, which depends on the
   engine just published. A new plugin's first version is reviewed by the Gradle team before it
   appears, which can take a few days.
3. **GitHub**: a draft release with the distribution (`arodnap-<version>-bin.zip` and `.tar.gz`)
   attached, for you to edit and publish.

## That it works from what is published

The plugins and the command line download nothing from this repository: the Maven and Gradle
plugins fetch Arodnap's tools and the Checker Framework from Maven repositories, and the
distribution bundles them. [`scripts/published_artifacts.py`](../scripts/published_artifacts.py)
checks that the published set is complete. `stage` publishes everything into a local folder
standing in for Maven Central; `check` repairs a test project with the Maven plugin, the Gradle
plugin and the command line from the zip, each from empty caches, with only that folder, Maven
Central and the Gradle Plugin Portal to download from. CI runs both on every pull request
(`Works from published artifacts`); after a release, the workflow's `check` mode runs the same
against the real Maven Central and Plugin Portal.

## Accounts and secrets (once)

1. **Maven Central**: sign in at central.sonatype.com with GitHub; the namespace
   `io.github.iamsanjaymalakar` is verified. Generate a user token (account page).
2. **Signing key**: `gpg --full-generate-key` (RSA 4096), publish the public key with
   `gpg --keyserver keyserver.ubuntu.com --send-keys KEYID`, and export the private key with
   `gpg --armor --export-secret-keys KEYID`. Keep a backup.
3. **Gradle Plugin Portal**: sign in at plugins.gradle.org with GitHub and create an API key.
4. **GitHub**: in the repository's Settings, Environments, create `release`:
   - required reviewers: yourself, so nothing publishes without your approval;
   - deployment branches and tags: the tag `v*` (releases) and the branch `master` (dry runs);
   - secrets:

     | Secret | What |
     |---|---|
     | `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | the Central user token |
     | `MAVEN_GPG_KEY`, `MAVEN_GPG_PASSPHRASE` | the ASCII-armored private key and its passphrase |
     | `GRADLE_PUBLISH_KEY`, `GRADLE_PUBLISH_SECRET` | the Plugin Portal API key |

## Steps

1. **Dry run.** In the repository's Actions, run **Release** with mode `dry-run` and the version
   to release (for example `0.1.0`), and approve it. It builds that version, uploads it to Central
   for validation without publishing, and validates the Gradle plugin. In the Central portal
   (Publish, Deployments), check the deployment `Arodnap <version>`, then **Drop** it.
2. **Release.** Set the version and tag it:

   ```bash
   mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false
   ```

   Commit, push a tag `v0.1.0` on that commit, and approve the run. The workflow checks that the
   tag and the build's version agree.
3. **After it.** Edit the draft GitHub release and publish it. Once the Gradle plugin is approved
   and Central has the release (usually within half an hour), run **Release** with mode `check`
   and the version. Then set the next snapshot version (`0.2.0-SNAPSHOT`) and commit.

Nothing on Central can be deleted or changed once published, so always dry-run a new version.
To check the release build locally without publishing: `mvn -P release package -Dgpg.skip`.
