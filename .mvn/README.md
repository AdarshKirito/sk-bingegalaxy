# `.mvn/jvm.config` — keep it portable

`jvm.config` is applied to the Maven launcher JVM on **every** machine that builds
this repo: your laptop, the Jenkins Linux agent, and any Maven-in-Docker run.
Anything OS-specific in here breaks the others.

## The bug this file exists to prevent

`jvm.config` used to contain:

```
-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT
```

That is correct **on a Windows host behind a TLS-intercepting antivirus** (this repo
ships `avg-root-ca.crt` for exactly that reason) — the JVM has to read the Windows
certificate store to trust the intercepted chain.

It is *fatal* anywhere else. `WINDOWS-ROOT` does not exist on Linux, so the JVM
cannot construct a default SSL context at all and **every artifact download fails**:

```
java.security.NoSuchAlgorithmException: Error constructing implementation
  (algorithm: Default, provider: SunJSSE, class: sun.security.ssl.SSLContextImpl$DefaultSSLContext)
```

This hid for a long time because it only bites when Maven actually needs the
network. With a warm `~/.m2` every build looks green; add one new dependency and
the Linux CI agent (`Jenkinsfile` runs `sh 'mvn ...'`) fails on an error that
points at TLS rather than at this file.

## If you need a custom truststore

Set it in **your own environment**, not in this file:

```powershell
# Windows host behind a TLS-intercepting proxy / antivirus
$env:MAVEN_OPTS = "-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"
mvn test
```

`MAVEN_OPTS` is applied after `jvm.config`, so it also works as an override when
you cannot edit the file — which is how the Docker build below forces a sane
truststore back on.

## Building in Docker (no local JDK required)

```powershell
docker run --rm `
  -v "d:\sk-binge-galaxy\sk-binge-galaxy:/app" -v skbg-m2:/root/.m2 `
  -w /app/backend maven:3.9-eclipse-temurin-17 `
  mvn -B test
```

Use the **Debian-based** image. The `-alpine` variant has a truststore layout that
fails the same way even with a correct `trustStoreType`.

## Rule

**Nothing in `jvm.config` may be OS-, host- or network-specific.** Memory and
metaspace limits are portable and belong here. Truststores, proxies, credentials
and file paths are not, and belong in `MAVEN_OPTS`, `settings.xml`, or CI secrets.
