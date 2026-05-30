# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build
./mvnw clean package

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=EasementsApplicationTests

# Run the application
./mvnw spring-boot:run

# Check formatting (runs Spotless)
./mvnw verify -DskipTests

# Apply formatting fixes
./mvnw spotless:apply
```

## Architecture

Spring Boot 4.0.6 web service (Java 25) that uses RavenDB as its document database and Thymeleaf for server-side rendering.

- **Port 8085** — application HTTP
- **Port 8090** — Spring Boot Actuator management (all endpoints exposed)
- Registered as a Spring Boot Admin client

### RavenDB

`RavenDBConfig` creates two beans:

- `IDocumentStore` — singleton; connects to the `DocSearch` database. URLs come from the `ravendb.urls` property, which is expected to be injected via the `RAVENDB_URLS` Jenkins global property (not set in `application.yaml`).
- `IDocumentSession` — request-scoped; opened per HTTP request and closed on request teardown via `destroyMethod = "close"`.

### Encrypted properties

Jasypt (`jasypt-spring-boot-starter`) is on the classpath. Sensitive config values can be wrapped as `ENC(...)` in `application.yaml` and require the Jasypt master password at startup (typically via the `JASYPT_ENCRYPTOR_PASSWORD` environment variable).

## Java Code Style

Mechanical rules (indentation, line length, brace placement, blank lines,
import ordering) are enforced by `config/eclipse-formatter.xml` via
`./mvnw spotless:apply`. The rules below are not mechanically enforced:

- **Wrapping** — only wrap when the line would exceed 85 characters; do
  not break lines that fit on one line. Indent the continuation one level
  (2 spaces) relative to the opening statement. Applies to every call
  form: multi-argument calls and string-literal arguments. Count the full
  indented line; if it fits, keep it on one line.
- **Method-declaration parameters** — when a method or constructor
  signature exceeds 85 characters, each parameter goes on its own line
  at one continuation indent (enforced by Spotless). A blank line
  separates the opening `{` from the first statement in every method
  body (enforced by `blank_lines_at_beginning_of_method_body = 1`).
  Short signatures that fit on one line are never wrapped.
- **Conditionals** — prefer `if/else` over the ternary operator (`?:`).
  Use ternary only for truly trivial inline expressions where the intent
  is immediately obvious and both branches are very short.
- **Imports** — no wildcard imports. Group order: `java.*`, `jakarta.*`,
  `org.*`, `com.*`; alphabetical within each group.
- **Javadoc** — required on every `public` and package-private class,
  constructor, and method. Use `<p>` for paragraph breaks, `<ul>/<li>` for
  lists, `{@link}` for type/method references, `{@code}` for inline code.
  Include `@param` and `@return` tags where applicable.
- **Inline comments** — `//` style; explain the *why* when non-obvious.
  Place above the relevant line (not at end-of-line) unless very short.
- **Javadoc on modified classes** — add or update Javadoc and inline
  comments on any new or modified Java class.
  Run `./mvnw spotless:apply` to auto-fix formatting before committing.
