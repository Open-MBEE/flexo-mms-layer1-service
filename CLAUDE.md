# Flexo MMS Layer 1 Service

## Project Overview

Flexo MMS Layer 1 is the core REST API of the Open-MBEE Flexo MMS ecosystem. It mediates between high-level engineering data management concepts (branching, committing, access control) and an underlying SPARQL 1.1 compliant RDF quad-store (Apache Jena Fuseki). Written in Kotlin, built on the Ktor framework.

## Tech Stack

- **Language:** Kotlin (JVM 21)
- **Framework:** Ktor 3.x (Netty engine)
- **Build:** Gradle (Kotlin DSL) — `build.gradle.kts`
- **Serialization:** kotlinx-serialization-json
- **RDF/SPARQL:** Apache Jena ARQ
- **Auth:** JWT (ktor-server-auth-jwt)
- **Testing:** Kotest 6.x + JUnit 5 + ktor-server-test-host
- **CI:** CircleCI (`.circleci/config.yml`)
- **Docker:** eclipse-temurin:21 base image

## Build & Run

```bash
# Build
./gradlew build

# Run (requires env vars, see below)
./gradlew run

# Build distribution
./gradlew installDist

# Docker build
docker build -t flexo-mms-layer1-service .
```

## Testing

Tests are integration tests that require a running quad-store (Fuseki). The test infrastructure uses Docker Compose:

```bash
# Start test dependencies (Fuseki quad-store, MinIO, store-service)
docker-compose -f src/test/resources/docker-compose.yml up -d

# Generate cluster initialization data (required before first test run)
cd deploy && npx ts-node src/main.ts http://layer1-service > ../src/test/resources/cluster.trig

# Run tests (requires env vars)
FLEXO_MMS_ROOT_CONTEXT=http://layer1-service \
FLEXO_MMS_QUERY_URL=http://localhost:3030/ds/sparql \
FLEXO_MMS_UPDATE_URL=http://localhost:3030/ds/update \
FLEXO_MMS_GRAPH_STORE_PROTOCOL_URL=http://localhost:3030/ds/data \
./gradlew test
```

Tests use Kotest with JUnit 5 platform. JaCoCo coverage reports are generated automatically after tests.

In CI, tests run inside a Docker container (`Dockerfile-Test`) on a shared Docker network with the quad-store, using `application.conf.test`.

## Required Environment Variables

| Variable | Purpose | Example |
|---|---|---|
| `FLEXO_MMS_ROOT_CONTEXT` | Base URI for the MMS instance | `http://layer1-service` |
| `FLEXO_MMS_QUERY_URL` | SPARQL query endpoint | `http://localhost:3030/ds/sparql` |
| `FLEXO_MMS_UPDATE_URL` | SPARQL update endpoint | `http://localhost:3030/ds/update` |
| `FLEXO_MMS_GRAPH_STORE_PROTOCOL_URL` | Graph Store Protocol endpoint | `http://localhost:3030/ds/data` |
| `FLEXO_MMS_STORE_SERVICE_URL` | (Optional) External store service URL | `http://store-service:8080/store` |
| `JWT_SECRET` | JWT signing secret | `test1234` (default for dev) |

Additional optional variables: `FLEXO_MMS_GLOMAR_RESPONSE`, `FLEXO_MMS_MAXIMUM_LITERAL_SIZE_KIB`, `FLEXO_MMS_GZIP_LITERALS_LARGER_THAN_KIB`, `FLEXO_MMS_SPARQL_REQUEST_TIMEOUT`. See `src/main/resources/application.conf.example` for all options.

## Project Structure

```
src/main/kotlin/org/openmbee/flexo/mms/
├── Application.kt              # Ktor application entry point & module configuration
├── Layer1Context.kt            # Per-request context (auth, SPARQL preconditions, request/response)
├── Conditions.kt               # SPARQL-based validation/precondition engine
├── SparqlBuilder.kt            # SPARQL query construction utilities
├── SparqlParameterizer.kt      # SPARQL parameterization/templating
├── AccessControl.kt            # Permission enforcement logic
├── Store.kt                    # Quad-store interaction layer
├── routes/
│   ├── Orgs.kt, Repos.kt, Branches.kt, Locks.kt, Commits.kt, ...  # Route definitions per resource
│   ├── ldp/                    # Linked Data Platform protocol handlers
│   ├── gsp/                    # Graph Store Protocol handlers
│   ├── sparql/                 # SPARQL Protocol handlers
│   └── store/                  # External store service handlers
├── server/
│   ├── Routing.kt              # Top-level route registration (start here when exploring)
│   ├── Authentication.kt       # JWT auth configuration
│   ├── LinkedDataPlatform.kt   # LDP protocol implementation
│   ├── GraphStore.kt           # GSP protocol implementation
│   ├── SparqlQuery.kt          # SPARQL query protocol
│   ├── SparqlUpdate.kt         # SPARQL update protocol
│   └── Protocol.kt, HTTP.kt   # Shared protocol/HTTP abstractions
└── (other utility files: Content.kt, Errors.kt, Namespaces.kt, etc.)

src/main/resources/
├── application.conf.example    # Full configuration template
├── application.conf.test       # Test configuration

src/test/
├── kotlin/                     # Test source files (Kotest specs)
├── resources/
│   ├── docker-compose.yml      # Test infrastructure (Fuseki, MinIO, store-service)
│   └── cluster.trig            # Generated quad-store initialization data

deploy/                         # TypeScript utility to generate cluster.trig initialization files
```

## Architecture Notes

- **Entry point:** `Application.kt` defines the Ktor module. Main class is `io.ktor.server.netty.EngineMain`.
- **Request lifecycle:** Every request is wrapped in a `Layer1Context` which manages auth (JWT), SPARQL precondition generation, and response building.
- **Routing pattern:** `server/Routing.kt` dispatches to resource-specific routers in `routes/`. Each resource router delegates to a protocol handler (`linkedDataPlatformDirectContainer()`, `graphStoreProtocol()`, `sparqlQuery()`, or `sparqlUpdate()`).
- **Protocol handlers** in `server/` create typed request/response context objects (e.g., `LdpDirectContainerRequest`, `SparqlQueryRequest`) that downstream handlers in `routes/ldp/`, `routes/gsp/`, `routes/sparql/` operate on.
- **Resources:** Organizations → Repositories → Branches/Locks/Commits → Model graphs. Access control via Groups and Policies.
- **All state** (including configuration, access control, and user data) is stored in the quad-store as RDF.

## Key Conventions

- Kotlin source follows `kotlin.code.style=official`.
- The Gradle daemon is disabled (`org.gradle.daemon=false`).
- Configuration uses HOCON format (`application.conf`) with environment variable overrides via `${?ENV_VAR}` syntax.
- The project uses the `-Xdebug` Kotlin compiler flag for coroutine debugging.
- Docker images are published to DockerHub under `openmbee/flexo-mms-layer1-service`.
- Branch naming: `develop`, `release/*`, `hotfix/*`, `support/*`. Tags: `v{semver}`.
