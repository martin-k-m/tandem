# Installing

Tandem is published to **GitHub Packages**, not Maven Central. That has one
consequence worth knowing before you start: **GitHub Packages requires
authentication even for public artifacts.** An anonymous `mvn install` cannot
resolve it. This is a GitHub policy, not a choice Tandem made.

## Maven

Add the repository and the dependency:

```xml
<repositories>
  <repository>
    <id>github-tandem</id>
    <url>https://maven.pkg.github.com/martin-k-m/tandem</url>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>io.github.martinkm</groupId>
    <artifactId>tandem</artifactId>
    <version>1.1.0</version>
  </dependency>
</dependencies>
```

Then put credentials in `~/.m2/settings.xml`. The id must match the repository
id above:

```xml
<settings>
  <servers>
    <server>
      <id>github-tandem</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_TOKEN</password>
    </server>
  </servers>
</settings>
```

The token needs only the `read:packages` scope. Do not commit it.

## Gradle

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/martin-k-m/tandem")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.token").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("io.github.martinkm:tandem:1.1.0")
}
```

## In GitHub Actions

The token is already there, so nothing extra is needed:

```yaml
- uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: '17'
    server-id: github-tandem
    server-username: GITHUB_ACTOR
    server-password: GITHUB_TOKEN

- run: mvn --batch-mode verify
  env:
    GITHUB_ACTOR: ${{ github.actor }}
    GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

## Building from source

No token needed:

```sh
git clone https://github.com/martin-k-m/tandem
cd tandem
mvn install
```

Java 17 or newer. There is nothing else to fetch: the only dependency in the
build is JUnit, and it is test-scoped.

## Requirements

| | |
| :-- | :-- |
| Java | 17 or newer, tested on 17 and 21 |
| Runtime dependencies | None |
| Build dependencies | JUnit 5, test scope only |
