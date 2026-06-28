# Publishing to Maven Central

The build is wired with [`com.vanniktech.maven.publish`](https://github.com/vanniktech/gradle-maven-publish-plugin)
and a `Publish` GitHub Actions workflow. Once the one-time setup below is done, cutting a
GitHub Release publishes `tv.seekr:seekr-core`, `tv.seekr:seekr-android` and
`tv.seekr:seekr-compose` to Maven Central automatically.

> These steps need credentials and DNS access that only the project owner has — they
> can't be automated for you.

## One-time setup

### 1. Claim the `tv.seekr` namespace on the Central Portal

1. Sign in at <https://central.sonatype.com> (GitHub login works).
2. **Add Namespace → `tv.seekr`.**
3. Verify ownership of `seekr.tv` by adding the **TXT DNS record** the portal shows you to
   the `seekr.tv` zone. Once it resolves, the namespace flips to *Verified*.
4. **Generate a user token** (Account → Generate User Token). You get a username + password
   pair — these are the `MAVEN_CENTRAL_*` secrets below, **not** your login.

### 2. Create a GPG signing key

Central requires every artifact to be signed.

```bash
# Generate (pick RSA 4096, no expiry is fine for a project key):
gpg --full-generate-key

# Find the key id (the long hex after "sec rsa4096/"):
gpg --list-secret-keys --keyid-format=long

# Publish the public half so Central can verify signatures:
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>

# Export the PRIVATE key in ASCII-armored form for CI (this whole block is the secret):
gpg --armor --export-secret-keys <KEY_ID>
```

Keep the passphrase you set — it's `SIGNING_KEY_PASSWORD`.

### 3. Add the four GitHub repository secrets

`Settings → Secrets and variables → Actions → New repository secret`:

| Secret | Value |
|--------|-------|
| `MAVEN_CENTRAL_USERNAME` | user-token username from step 1.4 |
| `MAVEN_CENTRAL_PASSWORD` | user-token password from step 1.4 |
| `SIGNING_KEY` | the full `-----BEGIN PGP PRIVATE KEY BLOCK-----…` from `gpg --armor --export-secret-keys` |
| `SIGNING_KEY_PASSWORD` | the GPG key passphrase |

The workflow maps these to the `ORG_GRADLE_PROJECT_*` properties the plugin expects, so no
secret is ever written to a file.

## Cutting a release

1. Bump `VERSION_NAME` in `gradle.properties` (e.g. `0.1.1` → `0.1.2`) and commit.
2. Create a GitHub Release with a matching tag (e.g. `v0.1.1`).
3. The **Publish** workflow runs `publishAndReleaseToMavenCentral` and, because
   `SONATYPE_AUTOMATIC_RELEASE=true`, the deployment validates and releases on its own.
4. Artifacts are usually resolvable within ~15–30 min and indexed in search within a few
   hours.

## Testing the wiring without publishing

```bash
# Validates packaging/POM/coordinates locally — no Central account needed.
# Disable signing for a quick local check, then re-enable:
./gradlew publishToMavenLocal -PRELEASE_SIGNING_ENABLED=false
# → check ~/.m2/repository/tv/seekr/seekr-android/<version>/
```

## Consuming once published

```kotlin
repositories { mavenCentral() }
dependencies {
    implementation("tv.seekr:seekr-android:0.1.1")   // or seekr-compose / seekr-core
}
```
