# Releasing

One version for everything: the root `pom.xml`'s. The Gradle plugin's build reads it from
there.

## What a release publishes

Pushing a tag `v<version>` runs [`.github/workflows/release.yml`](../.github/workflows/release.yml):

1. **Maven Central**: `mvn -P release deploy` uploads every module (the engine, the model,
   the command line, the Maven plugin, each tool, and the distribution zip) with sources,
   javadoc and signatures, and waits until Central has published them.
2. **Gradle Plugin Portal**: `publishPlugins` publishes the Gradle plugin, which depends on
   the engine just published.
3. **GitHub**: a draft release with the distribution (`arodnap-<version>-bin.zip` and
   `.tar.gz`) attached, for you to edit and publish.

## Before the first release

- **A namespace.** Maven Central and the Gradle Plugin Portal only accept a group you have
  verified. `org.arodnap` needs the `arodnap.org` domain (a DNS record); without it, pick
  another group (for example one under a verified `io.github.<account>` namespace) and change
  `org.arodnap` in the POMs' `groupId`s, `tools.properties`, the Gradle plugin's `group` and
  plugin id, and the docs.
- **Repository secrets** (in a `release` environment):

  | Secret | What |
  |---|---|
  | `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | a user token from central.sonatype.com |
  | `MAVEN_GPG_KEY`, `MAVEN_GPG_PASSPHRASE` | an ASCII-armored private key whose public key is on a key server, and its passphrase |
  | `GRADLE_PUBLISH_KEY`, `GRADLE_PUBLISH_SECRET` | an API key from plugins.gradle.org |

- The developer entry in the parent POM (`<developers>`) names who publishes.

## Steps

1. Set the release version everywhere: `mvn versions:set -DnewVersion=<version>
   -DgenerateBackupPoms=false`.
2. Commit, and push a tag `v<version>` on that commit. The workflow checks that the tag and
   the build's version agree.
3. When it finishes, edit the draft GitHub release and publish it.
4. Set the next snapshot version (`<next>-SNAPSHOT`) and commit.

To check the release build without publishing, run it unsigned:

```bash
mvn -P release package -Dgpg.skip
```
