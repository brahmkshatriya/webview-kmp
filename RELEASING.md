# Releasing

Releases are built by `.github/workflows/publish.yml` from a tag or a manual workflow dispatch.

The project uses the Vanniktech Maven Publish plugin to generate Maven publications, POM metadata,
source jars, and javadoc jars. Because the library contains Linux, Windows, macOS, and iOS native
targets, one machine cannot build every publication. Each target is therefore published into an
isolated local Maven repository on a matching GitHub runner. A final job merges those repositories,
signs every artifact, and uploads one Maven Central Portal deployment.

## GitHub Actions secrets

Two repository secrets are required.

`GRADLE_PROPERTIES_CONTENT` is written verbatim to `~/.gradle/gradle.properties` only in the final
publish job and must contain:

```properties
mavenCentralUsername=...
mavenCentralPassword=...
signing.keyId=...
signing.password=...
```

`GPG_SECRET_KEY_RING_BASE64` contains the base64-encoded binary secret GPG key ring corresponding to
`signing.keyId`. The publish job decodes it, appends `signing.secretKeyRingFile` to the temporary
Gradle properties file, and uses it to sign the merged Maven repository.

Platform build jobs receive neither secret.

## Version

For a tag build, the tag name is used as the Maven version. A leading `v` is stripped, so both
`<version>` and `v<version>` publish `<version>`.

For a manual release, run the `Publish` workflow and provide the version input.
