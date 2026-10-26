# AGENTS.md

Architecture and maintenance notes for **SmartDataJobs** and its sibling
project **SmartUser**.

This file records the decisions made while converting both projects from
NetBeans/Ant to Maven and wiring them into a single Docker Compose deployment.
Read it before changing the build, Docker setup, deployment configuration, or
the Smart* helper libraries.

---

## 1. Repositories and scope

Two separate Git repositories are involved:

| Repository | Path | Purpose |
| --- | --- | --- |
| `SmartDataJobs` | this repository | Jobs REST application |
| `SmartUser` | `../SmartUser` | User management REST application and database-backed user store |

They are **not** Maven modules of one reactor. They are built separately.
The Docker setup in `SmartDataJobs` consumes the built image from `SmartUser`.

The old NetBeans build used sibling Ant project references such as:

```properties
project.NGI_Logger=../NGI_Logger
reference.NGI_Logger.jar=${project.NGI_Logger}/dist/NGI_Logger.jar
```

Those sibling checkouts were not part of either repository and were not
present in a clean checkout. The Maven conversion removed that dependency by
vendoring the built Smart* jars into project-local Maven repositories.

---

## 2. Runtime architecture

```text
PostgreSQL 16
    |
    | jdbc/SmartUser
    v
Payara Server 6.2025.11 (Java 17, Jakarta EE 10)
    |
    +-- /SmartDataJobs   -> SmartDataJobs.war
    |       REST base:   /SmartDataJobs/smartdatajobs
    |
    +-- /SmartUser       -> SmartUser.war
            REST base:   /SmartUser/smartuser
```

Important URLs in the Compose setup:

- SmartDataJobs UI: `http://localhost:8080/SmartDataJobs/`
- SmartDataJobs REST info: `http://localhost:8080/SmartDataJobs/smartdatajobs/system/info`
- SmartUser UI: `http://localhost:8080/SmartUser/`
- SmartUser REST info: `http://localhost:8080/SmartUser/smartuser/system/info`
- Payara admin: `http://localhost:4848` with default `admin` / `admin`

The context roots come from the WAR file names (`SmartDataJobs`,
`SmartUser`). The second path segment comes from the JAX-RS
`@ApplicationPath` values in the `ApplicationConfig` classes.

---

## 3. Build architecture

### 3.1 SmartDataJobs

Standard Maven WAR project:

```text
pom.xml
src/main/java
src/main/resources
src/main/webapp
libs/maven-repo
```

Key properties:

- Java release: 17
- Packaging: `war`
- Final name: `SmartDataJobs`
- Jakarta EE API: `jakarta.platform:jakarta.jakartaee-api:10.0.0`, scope
  `provided`
- WAR output: `target/SmartDataJobs.war`

Build:

```bash
cd SmartDataJobs
mvn clean package
```

Docker artifact image:

```bash
cd SmartDataJobs
docker build -t smartdatajobs-war:local .
```

This produces a minimal image containing only
`/opt/wars/SmartDataJobs.war`. It does not run Payara.

### 3.2 SmartUser

Also a standard Maven WAR project:

```text
pom.xml
src/main/java
src/main/resources
src/main/webapp
libs/maven-repo
```

Key properties:

- Java release: 17
- Packaging: `war`
- Final name: `SmartUser`
- Jakarta EE API: `jakarta.platform:jakarta.jakartaee-api:10.0.0`, scope
  `provided`
- Hibernate ORM: `org.hibernate.orm:hibernate-core:6.1.7.Final`
- Jackson XML: `com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.14.2`
- WAR output: `target/SmartUser.war`

Build:

```bash
cd ../SmartUser
mvn clean package
```

### 3.3 Project-local Maven repository

The Smart* helper libraries are not published to Maven Central. Both projects
contain a small file-based Maven repository:

```text
libs/maven-repo
```

It is referenced from the POM as:

```xml
<repository>
    <id>smartdata-local</id>
    <url>file://${project.basedir}/libs/maven-repo</url>
    ...
</repository>
```

Artifacts currently used:

```text
de.fhbielefeld.scl:NGI_Logger:1.0.0
de.fhbielefeld.scl:NGI_RestUtils:1.0.0
de.fhbielefeld.scl:NGI_AdvancedReflection:1.0.0
de.fhbielefeld.smartuser:SmartUserAuth:1.0.0
de.fhbielefeld.smartuser:sun-jndi-ldap:1.0.0   (SmartUser only)
```

Each artifact has a `.jar`, a `.pom`, and `.sha1` / `.md5` checksum files.
The local repository is authoritative. The original sibling project sources
were under `/home/jef/git/webanwendungen/backend/smartData/`, but they may not
exist on another machine.

If a helper jar is updated:

1. Replace the jar in the correct `libs/maven-repo/...` coordinates.
2. Regenerate `.sha1` and `.md5`.
3. Prefer bumping the version and updating the POM instead of silently
   replacing a release artifact.

---

## 4. SmartUser-specific decisions

### 4.1 `DataFlowInterceptor`

The old NetBeans project referenced an undefined global library
`${libs.restapi.classpath}`. The only missing class from that library was
`de.fhbielefeld.scl.rest.dataflow.DataFlowInterceptor`.

It was used only for debug logging in
`de.fhbielefeld.smartuser.rest.UserRightsResource.check(...)`:

```java
DataFlowInterceptor.log();
DataFlowInterceptor.log("Start search for user...");
```

Decision: remove the dependency and use the existing
`de.ngi.logging.Logger` from `NGI_RestUtils`:

```java
de.ngi.logging.Logger.log();
de.ngi.logging.Logger.log("Start search for user...");
```

Do not reintroduce `DataFlowInterceptor` unless a real implementation is
available.

### 4.2 `sun-jndi-ldap`

`UsermanagerLDAP` imports `com.sun.jndi.ldap.LdapCtxFactory`. The original
project bundled a patched `libs/sun-jndi-ldap.jar`.

Decision: keep the jar, but move it into the local Maven repository as
`de.fhbielefeld.smartuser:sun-jndi-ldap:1.0.0`.

Do not remove this dependency without rewriting the LDAP code to use the
supported `javax.naming.ldap` API.

### 4.3 Persistence and database schema

`src/main/resources/META-INF/persistence.xml` defines:

- Persistence unit: `SmartUserPU`
- Transaction type: JTA
- Provider: Hibernate
- Data source: `jdbc/SmartUser`
- `hibernate.hbm2ddl.auto=update`

The JPA entities map to the PostgreSQL schema `smartuser`:

```java
@Table(name = "users", schema = "smartuser")
```

The schema must exist before Hibernate can create the tables. The Compose setup
initializes it with:

```sql
CREATE SCHEMA IF NOT EXISTS smartuser AUTHORIZATION CURRENT_USER;
```

File: `docker/postgres/init-smartuser.sql`.

PostgreSQL init scripts only run when the data volume is first initialized. If
an old volume exists without the schema, reset it:

```bash
docker compose down -v
```

### 4.4 Duplicate `Configuration` class

SmartUser's own source contains
`de.fhbielefeld.smartuser.config.Configuration`. The `SmartUserAuth` jar also
contains a class with the same name.

The web application class loader prefers `WEB-INF/classes` over
`WEB-INF/lib`, so SmartUser's own class wins. This is existing behavior. Do not
move or delete one of the classes without checking all injection and
configuration paths.

---

## 5. Docker architecture

### 5.1 SmartUser artifact image

`../SmartUser/Dockerfile` is a multi-stage build:

1. Maven stage builds `target/SmartUser.war`.
2. Alpine artifact stage copies it to:

```text
/opt/wars/SmartUser.war
```

Build it with:

```bash
cd ../SmartUser
docker build -t smartuser-war:local .
```

This image is intentionally only an artifact carrier. It is not a runnable
Payara image.

### 5.2 SmartDataJobs artifact image

`SmartDataJobs/Dockerfile` is the SmartDataJobs counterpart to the SmartUser
artifact image:

1. Maven stage builds `target/SmartDataJobs.war` from `pom.xml`, `libs`, and
   `src`.
2. Alpine artifact stage copies it to:

```text
/opt/wars/SmartDataJobs.war
```

Build it with:

```bash
cd SmartDataJobs
docker build -t smartdatajobs-war:local .
```

This image is only an artifact carrier. It is not a runnable Payara image.

### 5.3 Master Payara image

`SmartDataJobs/Dockerfile.payara` builds the runnable master container:

1. `FROM ${SMARTDATAJOBS_IMAGE} AS smartdatajobs-war` (default
   `smartdatajobs-war:local`).
2. `FROM ${SMARTUSER_IMAGE} AS smartuser-war` (default
   `smartuser-war:local`).
3. Maven stage downloads the PostgreSQL JDBC driver
   (`org.postgresql:postgresql:42.7.4`).
4. `FROM payara/server-full:6.2025.11-jdk17`.
5. Copies the PostgreSQL driver into
   `/opt/payara/appserver/glassfish/domains/domain1/lib/`.
6. Copies `docker/payara/init_0_configure_smartuser_db.sh`.
7. Copies `SmartDataJobs.war` from its artifact image.
8. Copies `SmartUser.war` from its artifact image.
9. Switches back to the `payara` runtime user.

Important details:

- The Payara base image runs as user `payara`. The Dockerfile uses `USER root`
  for the `chmod` of the init script, then switches back with `USER payara`.
- The build uses the classic Docker builder on the current machine, so avoid
  BuildKit-only features such as `COPY --chmod`.
- `target/` is ignored by `.dockerignore`; the SmartDataJobs WAR is built
  inside the Docker build, not copied from the host.
- Both `SMARTDATAJOBS_IMAGE` and `SMARTUSER_IMAGE` are build args, so Compose
  can point at other tags.

### 5.4 Payara startup and JDBC resource

The Payara image automatically deploys every WAR in
`/opt/payara/deployments`.

`init_0_configure_smartuser_db.sh` runs before
`init_1_generate_deploy_commands.sh` and appends:

```text
create-jdbc-connection-pool ... SmartUserPool
create-jdbc-resource --connectionpoolid SmartUserPool jdbc/SmartUser
```

to `$POSTBOOT_COMMANDS`. Payara then appends its automatic deploy commands.
The JDBC resource therefore exists before SmartUser is deployed.

Implementation notes:

- The script is **sourced** by the Payara entrypoint. It uses `return 0`, not
  `exit 0`, when skipping configuration.
- It checks `domain.xml` for `jdbc/SmartUser` to avoid adding duplicate
  resources on restart.
- JDBC URLs contain colons, which must be escaped in the asadmin
  `--property` string:

```text
url=jdbc\:postgresql\://host\:5432/database
```

- Database connection values come from:

```text
SMARTUSER_DB_HOST
SMARTUSER_DB_PORT
SMARTUSER_DB_NAME
SMARTUSER_DB_USER
SMARTUSER_DB_PASSWORD
```

### 5.5 Compose deployment

`SmartDataJobs/compose.yaml` contains:

- `smartuser-db`: `postgres:16-alpine`, persistent volume, init SQL,
  `pg_isready` healthcheck.
- `smartdatajobs`: master Payara image, depends on a healthy database,
  exposes ports `8080` and `4848`.

Notable settings:

```yaml
DEPLOY_PROPS: "--force=true"
```

Without `--force=true`, a Payara container restart re-runs the generated deploy
commands against the persisted domain and logs:

```text
Application with name SmartUser is already registered
```

`--force=true` makes the automatic redeployment idempotent.

Environment variables:

```text
SMARTDATAJOBS_HTTP_PORT   default 8080
SMARTDATAJOBS_ADMIN_PORT  default 4848
SMARTUSER_DB_NAME         default smartuser
SMARTUSER_DB_USER         default smartuser
SMARTUSER_DB_PASSWORD     default smartuser
```

The database volume is `smartdatajobs_smartuser-db-data`.

### 5.6 One-command build and start

`scripts/up.sh`:

1. Builds `smartuser-war:local` from `../SmartUser`.
2. Builds `smartdatajobs-war:local` from this repository.
3. Runs `docker compose up --build -d` to build the master Payara image.

Use it for the normal full deployment:

```bash
cd SmartDataJobs
./scripts/up.sh
```

If only SmartUser changed:

```bash
docker build -t smartuser-war:local ../SmartUser
docker compose up --build -d
```

If only SmartDataJobs changed:

```bash
docker build -t smartdatajobs-war:local .
docker compose up --build -d
```

Stop:

```bash
docker compose down
```

Reset the database too:

```bash
docker compose down -v
```

---

## 6. Known warnings and pre-existing behavior

### 6.1 SmartUser `databaseConnectivity:false`

`/SmartUser/smartuser/system/info` can report:

```json
"databaseConnectivity": false
```

The endpoint only checks whether the injected `EntityManager` field is
non-null:

```java
if (this.em == null) {
    rob.add("databaseConnectivity", false);
}
```

The `@PersistenceContext` field in `SystemResource` is null in the current
container. The persistence unit itself is working: Hibernate creates the
`smartuser` tables and the application deploys.

Likely cause: `src/main/webapp/WEB-INF/beans.xml` uses
`bean-discovery-mode="none"`, so JAX-RS resources are not fully CDI-managed.
This is pre-existing behavior. Do not enable CDI or change `beans.xml` without
testing all endpoints.

### 6.2 Missing configuration properties

Both applications look for configuration files in the Payara working
directory:

```text
/opt/payara/SmartDataJobs_config.properties
/opt/payara/SmartUser_config.properties
```

If missing, the applications log errors but still deploy. SmartUser endpoints
that create/list users require at least:

```properties
passwordsalt=...
authtokensalt=...
```

Without them, endpoints such as `/SmartUser/smartuser/user/list` return HTTP
412 with a configuration error. Add the files through Compose bind mounts if
the full application behavior is needed.

### 6.3 Compiler warning in SmartDataJobs

`SmartDataAccessor.saveDataSpecial(...)` is deprecated but not annotated with
`@Deprecated`. This is a pre-existing warning and does not fail the build.

### 6.4 Jakarta API jars inside SmartUser

Hibernate brings some Jakarta API jars into `WEB-INF/lib`. The application
deployed successfully with them. Do not remove them casually; if a class
loading conflict appears, evaluate explicit exclusions and retest deployment.

### 6.5 Payara admin password

The Payara image ships with:

```text
/opt/payara/passwordFile  ->  AS_ADMIN_PASSWORD=admin
```

Setting only the `ADMIN_PASSWORD` environment variable does not rewrite that
file. The Compose setup therefore uses the image defaults (`admin` / `admin`).
If the admin password is changed, update the password file or add a proper
startup configuration; otherwise `asadmin` healthchecks and administration
commands will fail.

### 6.6 `restart` and `compose --build` do not rebuild artifact images

`docker compose restart` only restarts the existing container.
`docker compose up --build` rebuilds the master Payara image, but it reuses the
existing `smartdatajobs-war:local` and `smartuser-war:local` artifact images.

After SmartDataJobs changes:

```bash
docker build -t smartdatajobs-war:local .
docker compose up --build -d
```

After SmartUser changes:

```bash
docker build -t smartuser-war:local ../SmartUser
docker compose up --build -d
```

`./scripts/up.sh` rebuilds both artifact images and the master image.

---

## 7. Common maintenance tasks

### Rebuild everything

```bash
cd SmartDataJobs
./scripts/up.sh
```

### Check deployment

```bash
docker compose ps
docker exec smartdatajobs asadmin --user=admin \
  --passwordfile=/opt/payara/passwordFile list-applications
```

Expected applications:

```text
SmartDataJobs
SmartUser
```

### Check database tables

```bash
docker exec smartuser-db psql -U smartuser -d smartuser -c '\dt smartuser.*'
```

Expected tables:

```text
accessfail
groups
groupusers
resources
userrights
users
```

### Test endpoints

```bash
curl -i http://localhost:8080/SmartDataJobs/
curl -i http://localhost:8080/SmartDataJobs/smartdatajobs/system/info
curl -i http://localhost:8080/SmartUser/
curl -i http://localhost:8080/SmartUser/smartuser/system/info
```

All four returned HTTP 200 during verification.

### Inspect logs

```bash
docker compose logs -f smartdatajobs
docker compose logs -f smartuser-db
```

### Force a clean Docker rebuild

```bash
docker build --no-cache -t smartuser-war:local ../SmartUser
docker compose build --no-cache
docker compose up -d
```

### Reset the database

```bash
docker compose down -v
docker compose up -d
```

---

## 8. Verification checklist for future changes

Before considering the architecture healthy, verify:

- [ ] `mvn clean package` succeeds in `SmartDataJobs`.
- [ ] `mvn clean package` succeeds in `SmartUser`.
- [ ] `docker build -t smartdatajobs-war:local .` succeeds.
- [ ] `docker build -t smartuser-war:local ../SmartUser` succeeds.
- [ ] `docker compose build` succeeds.
- [ ] `docker compose up -d` starts both services.
- [ ] `docker compose ps` reports both services healthy.
- [ ] `asadmin list-applications` lists both applications.
- [ ] All four HTTP endpoints return 200.
- [ ] `smartuser` schema contains the six expected tables.
- [ ] `docker compose restart smartdatajobs` becomes healthy again and does
      not log `Application with name ... is already registered`.

---

## 9. Key files

SmartDataJobs:

```text
pom.xml
Dockerfile              # minimal SmartDataJobs WAR artifact image
Dockerfile.payara       # master Payara image containing both WARs
compose.yaml
.dockerignore
docker/payara/init_0_configure_smartuser_db.sh
docker/postgres/init-smartuser.sql
scripts/up.sh
libs/maven-repo/
```

SmartUser:

```text
pom.xml
Dockerfile
.dockerignore
libs/maven-repo/
src/main/resources/META-INF/persistence.xml
src/main/java/de/fhbielefeld/smartuser/rest/UserRightsResource.java
```

---

## 10. Historical context

The original projects were NetBeans Ant web projects. The old build:

- Used `nbproject/project.properties` and `nbproject/project.xml`.
- Expected sibling projects such as `../NGI_Logger` and
  `../NGI_RestUtils` to exist next to the application.
- Used NetBeans global libraries such as
  `${libs.jakartaee-api-10.0.classpath}` and, for SmartUser, an undefined
  `${libs.restapi.classpath}`.
- Packaged the Jakarta EE API into the WAR and removed it again in a custom
  Ant `-post-dist` target.

The Maven conversion replaced all of this with explicit Maven dependencies,
project-local artifact repositories, standard source layout, and `provided`
scope for the Jakarta EE API.
