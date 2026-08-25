# Publishing XmaxSDK

The public Maven coordinate is:

```text
ai.xmax:xmax-sdk:<version>
```

The project uses the Vanniktech Maven Publish plugin to generate the AAR, POM,
sources artifact, documentation artifact, checksums, and signatures required by
Maven Central.

## Before the first release

1. Create an account at the Central Publisher Portal.
2. Verify the `ai.xmax` namespace using the `xmax.ai` DNS zone.
3. Create a company release GPG key and publish its public key.
4. Generate a Central Portal user token.
5. Configure the POM repository URLs if the final GitHub repository differs from
   `https://github.com/XingMai/XmaxSDK-Android`.

Keep Central credentials and the GPG private key outside the repository. For CI,
store them in GitHub Actions secrets and map them to the Gradle properties expected
by the publishing plugin.

## Local verification

The regular build does not require publishing credentials:

```bash
./gradlew clean build
```

Inspect the generated POM before publishing:

```bash
./gradlew :xmax-sdk:generatePomFileForMavenPublication
```

## Release

Change `VERSION_NAME` in `gradle.properties` to an immutable release version, for
example `0.1.0-alpha01`, commit and tag that exact source revision, then upload:

```bash
./gradlew :xmax-sdk:publishToMavenCentral
```

For the first release, review validation in the Central Publisher Portal and publish
manually. Never reuse or overwrite a version that has already been published.

