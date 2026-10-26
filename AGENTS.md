# AGENTS.md

Architecture and maintenance notes for **SmartDataJobs** and its sibling
projects **SmartUser**, **SmartData** and **SWAC**.

This file records the decisions made while converting the Java projects from
NetBeans/Ant to Maven and wiring all of them into a single Docker Compose
deployment. Read it before changing the build, Docker setup, deployment
configuration, or the Smart* helper libraries.

---

## 1. Repositories and scope

Five separate Git repositories are involved:

| Repository | Path | Purpose |
| --- | --- | --- |
| `SmartDataJobs` | this repository | Jobs REST application |
| `SmartUser` | `../SmartUser` | User management REST application and database-backed user store |
| `SmartData` | `../SmartData` | TreeQL database access REST API (tables, records, storages) |
| `SWAC` | `../SWAC` | SmartWebApplicatinComponents, the static JavaScript UI library used by the web front ends |
| `SmartDataDeployment` | `..` | Docker Compose stack, master Payara image, JDBC/config/bootstrap, operator scripts |

They are **not** Maven modules of one reactor. They are built separately.
Each application repository builds only its own WAR artifact image. The Docker
Compose deployment and everything that ties the four applications together
live in `SmartDataDeployment`, see §5.

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
PostgreSQL 16 (database smartuser)
    |
    | jdbc/SmartUser  -> schema smartuser   (Hibernate/JPA, SmartUser tables)
    | jdbc/SmartData  -> schemas public, smartmonitoring (JDBC, TreeQL tables)
    v
Payara Server 6.2025.11 (Java 17, Jakarta EE 10)
    |
    +-- /SmartDataJobs   -> SmartDataJobs.war
    |       REST base:   /SmartDataJobs/smartdatajobs
    |
    +-- /SmartUser       -> SmartUser.war
    |       REST base:   /SmartUser/smartuser
    |
    +-- /SmartData       -> SmartData.war
    |       REST base:   /SmartData/smartdata
    |
    +-- /SWAC           -> SWAC.war
            static JavaScript UI library (no server-side code)
```

Important URLs of the Compose deployment described in §5:

- SmartDataJobs UI: `http://localhost:8080/SmartDataJobs/`
- SmartDataJobs REST info: `http://localhost:8080/SmartDataJobs/smartdatajobs/system/info`
- SmartUser UI: `http://localhost:8080/SmartUser/`
- SmartUser REST info: `http://localhost:8080/SmartUser/smartuser/system/info`
- SmartData UI: `http://localhost:8080/SmartData/`
- SmartData REST info: `http://localhost:8080/SmartData/smartdata/system/info`
- SWAC: `http://localhost:8080/SWAC/` (library entry point `…/SWAC/swac/swac.js`)
- Payara admin: `http://localhost:4848` with default `admin` / `admin`

The context roots come from the WAR file names (`SmartDataJobs`,
`SmartUser`, `SmartData`). The second path segment comes from the JAX-RS
`@ApplicationPath` values in the `ApplicationConfig` classes.

---

## 3. Build architecture

### 3.1 SmartDataJobs

Maven WAR project that keeps the NetBeans/Ant directory layout:

```text
pom.xml
src/java                Java sources and their resource files
web                     document root
libs/maven-repo
```

The layout is wired into the POM instead of moving files:

```xml
<sourceDirectory>src/java</sourceDirectory>
<resources>                     <!-- hibernate.properties, no *.java -->
<warSourceDirectory>web</warSourceDirectory>
```

`src/java` is both the source directory and a resource directory excluding
`**/*.java`, which reproduces what the Ant build did: non-Java files next to
the sources (`hibernate.properties`) end up in `WEB-INF/classes`.

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
`/opt/wars/SmartDataJobs.war`. It does not run Payara. The master Payara image
and the Compose deployment that consume this image live in
`..` (§5).

### 3.2 SmartUser

Also a Maven WAR project with the NetBeans/Ant directory layout:

```text
pom.xml
src/java                Java sources and their resource files
src/conf                configuration (persistence.xml)
web                     document root
libs/maven-repo
```

Same wiring as SmartDataJobs, plus one extra resource mapping because JPA
requires `META-INF/persistence.xml` on the classpath while NetBeans kept the
file in `src/conf`:

```xml
<resource>
    <directory>src/conf</directory>
    <targetPath>META-INF</targetPath>
    <includes><include>persistence.xml</include></includes>
</resource>
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
mvn clean package             # -> target/SmartUser.war
docker build -t smartuser-war:local .
```

### 3.3 SmartData

SmartData was also converted from NetBeans/Ant to Maven. Unlike SmartUser it
uses plain JDBC, not JPA.

```text
pom.xml
src/java                Java sources (NetBeans layout kept)
src/conf                old Ant manifest, unused
web                     document root (NetBeans layout kept)
libs/                   original third party jars (kept for reference)
libs/maven-repo         Smart* helper jars
Dockerfile              Maven build + WAR artifact image
```

Key properties:

- Java release: 17
- Packaging: `war`, final name `SmartData`
- Jakarta EE API: `jakarta.platform:jakarta.jakartaee-api:10.0.0`, scope
  `provided`
- `<sourceDirectory>src/java</sourceDirectory>` and
  `<warSourceDirectory>web</warSourceDirectory>` keep the NetBeans layout, so
  the Ant `build.xml`/`nbproject` files are no longer needed for the build.
- Third party dependencies taken over from the former `libs/*.jar`:

```text
org.eclipse.microprofile.openapi:microprofile-openapi-api:3.1
com.sun.xml.bind:jaxb-impl:4.0.2
org.mongodb:mongodb-driver-sync:4.1.0
org.json:json:20240205
```

- The old Ant build had a `-post-dist` target that removed the Jakarta EE API
  jar from `WEB-INF/lib`. That hack is gone: the API is `provided`, so it never
  ends up in the WAR.
- MongoDB is only used when `mongo.url` is configured. Otherwise all storage
  access goes through the JNDI data source `jdbc/SmartData` (PostgreSQL).

Build:

```bash
cd ../SmartData
mvn clean package          # -> target/SmartData.war
docker build -t smartdata-war:local .
```

### 3.4 SWAC

SWAC is **not** a Java or Maven project. It is a NetBeans *HTML5 client
project*: plain HTML, CSS and JavaScript with no build step.

```text
index.html              document root entry page
swac/                   library core, components, libs, langs, offline
nbproject/              NetBeans project metadata (context root /SWAC)
docker/WEB-INF/         web.xml and glassfish-web.xml added for deployment
Dockerfile              static WAR artifact image
```

Key facts:

- Declared context root (NetBeans): `nbproject/project.properties`,
  `web.context.root=/SWAC`.
- Size: about 30 MB / 1350 files, mostly `swac/libs/*` (uikit, leaflet,
  mathlive, tinymce, turf, localforage). It is shipped uncompressed except for
  the ZIP compression of the WAR.
- The directory serves as the document root; there is no `pom.xml` and no
  `target/` output.
- `swac/api/php/proxy.php` and `swac/api/php/sendmail.php` are not referenced
  anywhere. They are leftovers and never execute on Payara.

Build:

```bash
cd ../SWAC
docker build -t swac-war:local .
```

### 3.5 Project-local Maven repository

The Smart* helper libraries are not published to Maven Central. All Java
projects contain a small file-based Maven repository:

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

`SmartData` uses the same four helper jars as `SmartDataJobs`; the repository
file set is byte-identical, so the directories can be copied when the jars are
updated.

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

## 4. Application-specific decisions

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

`src/conf/persistence.xml` (mapped to `WEB-INF/classes/META-INF/persistence.xml`
by the POM) defines:

- Persistence unit: `SmartUserPU`
- Transaction type: JTA
- Provider: Hibernate
- Data source: `jdbc/SmartUser`
- `hibernate.hbm2ddl.auto=update`

The JPA entities map to the PostgreSQL schema `smartuser`:

```java
@Table(name = "users", schema = "smartuser")
```

The schema must exist before Hibernate can create the tables. The Compose
setup initializes it with:

```sql
CREATE SCHEMA IF NOT EXISTS smartuser AUTHORIZATION CURRENT_USER;
```

File: `../docker/postgres/init-smartuser.sql`.

PostgreSQL init scripts only run when the data volume is first initialized. If
an old volume exists without the schema, reset it from the deployment
repository:

```bash
cd ..
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

### 4.5 SmartData-specific decisions

- **Ant to Maven without moving sources.** `src/java` and `web` are configured
  via `<sourceDirectory>`/`<warSourceDirectory>` instead of the Maven default
  layout. Renaming them would have touched every NetBeans file for no gain.
- **SmartData helper jars.** JAXB, the MongoDB driver, `org.json` and the
  MicroProfile OpenAPI API used to be loose jars in `libs/`. They are now
  normal Maven dependencies; `libs/*.jar` stays in the repository as the
  original reference copies but is no longer used by the build.
- **`WEB-INF/lib` is smaller than in the Ant build.** ActiveJAXB and the
  Jakarta API are `provided`, so `jakarta.jakartaee-api` is not packaged. The
  Ant `-post-dist` target that removed it afterwards is obsolete.
- **Two `Configuration` classes collide again.** `NGI_RestUtils` happens to
  contain `de.fhbielefeld.smartdata.config.Configuration` (and
  `de.smart.Configuration`). SmartData's own class lives in `WEB-INF/classes`
  and therefore wins. Same situation as §4.4; do not remove the jar entry.
- **`jdbc/SmartData` instead of a JPA unit.** Data access uses plain JDBC over
  a container data source. The JNDI name comes from the property
  `postgres.jndi`, defaulting to `jdbc/SmartData`. Setting `mongo.url` switches
  the whole storage layer to MongoDB – the deployment does not configure it.
- **Storages are PostgreSQL schemas.** `storage=public` is the default,
  `storage=smartmonitoring` is what the monitoring front end uses. A storage is
  created with `POST /SmartData/smartdata/storage/create?name=<name>`; the
  schema name is used as is, so the database user must be allowed to create
  schemas.

### 4.6 CDI is enabled in SmartUser (required)

`SmartUser/web/WEB-INF/beans.xml` uses
`bean-discovery-mode="all"`. This is **required**, not cosmetic.

The JAX-RS resources inject `@PersistenceContext EntityManager` and
`@Resource UserTransaction` (for example `UserResource` and
`SystemResource`). With `bean-discovery-mode="none"` CDI is off, Jersey
instantiates the resources itself and performs no EE resource injection, so
both fields stayed `null`. Consequences were:

- `/SmartUser/smartuser/system/info` reported `databaseConnectivity:false`.
- `user/performLogin`, `user` (create/list) and everything else that builds a
  `Usermanager` returned HTTP 500, because `UsermanagerLocal` refuses to be
  constructed without an EntityManager, leaving `UsermanagerBuilder` with an
  empty manager list (`Index 0 out of bounds for length 0`).

With CDI enabled, resource creation is delegated to CDI, both fields are
injected, and all endpoints work. Verified after the change:

```text
system/info            databaseConnectivity: true
performLogin           200 + authtoken cookie
user (list, token)     200
user (list, no token)  401
user (create)          201
user/count             200
```

`beans.xml` carries a comment explaining this. Do not switch it back without
re-running the checks in §8.

### 4.7 SmartUser bootstrap (`system/setup`)

A fresh database has **no users and no user rights**. After the tables are
created by Hibernate, `GET /SmartUser/smartuser/system/setup` creates:

```text
*        anonymous user, CREATE right on de.fhbielefeld.scl.smartuser.user
admin    user with "*" on "*"            (password: admin)
AppUser  application user, GRANT right   (password: SmartPassword1298!)
```

Without it, even anonymous user creation fails with HTTP 500
`User >*< not found.` because the check for the `*` CREATE right cannot find
the anonymous user.

The endpoint is one-shot; a second call violates unique constraints and
returns HTTP 500. Use `../scripts/init-smartuser.sh`, which
only calls it when `smartuser.users` is empty.

---

## 5. Docker and deployment

All deployment tooling lives in the separate repository
`..`:

```text
../compose.yaml             # PostgreSQL + Payara service
../Dockerfile.payara        # master Payara image
../docker/payara/init_0_configure_databases.sh
../docker/payara/*_config.properties
../docker/postgres/init-smartuser.sql
../scripts/up.sh
../scripts/init-smartuser.sh
```

This repository keeps exactly one Docker file — `Dockerfile`, the SmartDataJobs
WAR artifact image — because it builds from these sources. All four
applications follow the same artifact image pattern: the application builds its
WAR, an `alpine` stage stores it at `/opt/wars/<Name>.war`, and the image never
runs Payara.

Contracts this repository must keep for the deployment repository:

- Artifact image `smartdatajobs-war:local` containing exactly
  `/opt/wars/SmartDataJobs.war`. `Dockerfile.payara` copies the WAR from there
  and fails at build time if the image is missing.
- The WAR file name and the POM `finalName` stay `SmartDataJobs`. The file name
  becomes the Payara application name and the context root `/SmartDataJobs`,
  and the Compose healthcheck greps `asadmin list-applications` for that name.
- The configuration file stays `SmartDataJobs_config.properties`; Payara loads
  `<module-name>_config.properties` from its working directory `/opt/payara`.
- The REST base path stays `/SmartDataJobs/smartdatajobs`
  (`ApplicationConfig`), because the front end and the documentation reference
  these paths absolutely.
- `/SWAC/swac/swac.js` stays an absolute path in the front end. It is served by
  SWAC below `/SWAC`; never reference it relatively.

Everything else about the deployment — services, ports, environment variables,
the master image, JDBC resources, the database bootstrap, known pitfalls and
the full verification checklist — is documented in
`../AGENTS.md`. Change it there, not here.

---

## 6. Known warnings and pre-existing behavior

### 6.1 EntityManager / CDI (resolved)

This used to be the biggest functional defect of the stack:

```text
/SmartUser/smartuser/system/info  -> "databaseConnectivity": false
user/performLogin                 -> 500 Index 0 out of bounds for length 0
```

Cause: `@PersistenceContext`/`@Resource` fields were never injected because
SmartUser's `beans.xml` disabled CDI (`bean-discovery-mode="none"`), so
`UsermanagerLocal` could not be built and the usermanager list stayed empty.

Fix: `bean-discovery-mode="all"` in
`../SmartUser/web/WEB-INF/beans.xml` (see §4.6) plus the
`system/setup` bootstrap (§4.7) and `confirm_mail=false` in the SmartUser
configuration. Login, user creation, user listing and `/user/count` now work.

The persistence unit itself was never broken: Hibernate always created the
`smartuser` tables.

### 6.2 Configuration properties

Each application looks for a configuration file in the Payara working
directory, named `<module-name>_config.properties`:

```text
/opt/payara/SmartDataJobs_config.properties
/opt/payara/SmartUser_config.properties
/opt/payara/SmartData_config.properties
```

All three are shipped by `../Dockerfile.payara` from
`../docker/payara/*_config.properties`. They exist because
without them every application logs a load error and several endpoints fail:

- SmartUser builds no Usermanager without `passwordsalt` and
  `authtokensalt` (HTTP 412 on `user/list`, `user/performLogin`, ...), throws a
  `NullPointerException` without `serviceorder` (HTTP 500), and dereferences
  `confirm_mail` without a null check in `UsermanagerLocal.createUser()`
  (HTTP 500 on user creation). The shipped file therefore sets
  `serviceorder=local` and `confirm_mail=false`. Set `confirm_mail=true` only
  together with the `email_smtp_*` settings.
- SmartData falls back to `jdbc/SmartData` and PostgreSQL when `postgres.jndi`
  is absent. Setting `mongo.url` switches it to MongoDB, and
  `smartuser.url` + `smartuser.apptoken` (or `smartuser.db_uri`,
  `smartuser.db_user`, `smartuser.db_pwd`) activates authentication against
  SmartUser for `@SmartUserAuth` endpoints. Authentication is **not**
  activated in the current setup, so SmartData endpoints are publicly
  accessible.

Verify with:

```bash
curl -s http://localhost:8080/SmartUser/smartuser/system/config
curl -s http://localhost:8080/SmartData/smartdata/system/config
curl -s http://localhost:8080/SmartDataJobs/smartdatajobs/system/config
```

All three must report `"propsloaded":true`.

The salts in `SmartUser_config.properties` are development defaults. Changing
them invalidates all stored password hashes and auth tokens.

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
file. The deployment in `..` therefore uses the image
defaults (`admin` / `admin`).
If the admin password is changed, update the password file or add a proper
startup configuration; otherwise `asadmin` healthchecks and administration
commands will fail.

### 6.6 `restart` and `compose --build` do not rebuild artifact images

`docker compose restart` only restarts the existing container, and
`docker compose up --build` rebuilds the master Payara image but reuses the
existing `*-war:local` artifact images. A code change without its artifact
image rebuild is silently ignored.

After SmartDataJobs changes:

```bash
docker build -t smartdatajobs-war:local .
cd ..
docker compose up --build -d
```

The rebuild matrix for SmartUser, SmartData and SWAC is in
`../AGENTS.md` §10.2, together with
`../scripts/up.sh`, which rebuilds all four artifact images
and the master image.

### 6.7 SWAC licensing

Most of SWAC is LGPL, but 13 directories below `swac/components/` contain
their own `LICENSE.md` marking them as proprietary ("not for redistribution or
modification without permission"), for example `Booking`, `Communicator`,
`Mediaplayer`, `Texteditor` and `Questions`. Building them into an image that
is pushed to a registry counts as redistribution. The stack image
(`../Dockerfile.payara`) contains the SWAC artifact image as
is. If that becomes a problem, exclude those directories in
`../SWAC/Dockerfile`; do not patch the files.

---

## 7. Common maintenance tasks

Build this application:

```bash
mvn clean package                                  # -> target/SmartDataJobs.war
docker build -t smartdatajobs-war:local .
```

Deployment, startup, database bootstrap, log inspection and stack resets are
documented in `../AGENTS.md`. After a code change the
artifact image must be rebuilt before the stack is restarted:

```bash
docker build -t smartdatajobs-war:local .
cd ..
docker compose up --build -d
```

Compose does not rebuild artifact images. `docker compose up --build` alone
would silently redeploy the previous WAR.

### Check the deployed application

```bash
curl -i http://localhost:8080/SmartDataJobs/
curl -i http://localhost:8080/SmartDataJobs/smartdatajobs/system/info
curl -s http://localhost:8080/SmartDataJobs/smartdatajobs/system/config
```

### Check the components it depends on

```bash
# SmartUser: login and user rights
curl -s http://localhost:8080/SmartUser/smartuser/system/info

# SmartData: data access (lists the storages of the public storage)
curl -s "http://localhost:8080/SmartData/smartdata/storage/getCollections?name=public"

# SWAC: the JavaScript library loaded by the front end
curl -sI http://localhost:8080/SWAC/swac/swac.js
```

The only compiler warning of this module is the deprecated
`SmartDataAccessor.saveDataSpecial(...)` (§6.3); it does not fail the build.

---

## 8. Verification checklist for future changes

Application level:

- [ ] `mvn clean package` succeeds and produces `target/SmartDataJobs.war`.
- [ ] `docker build -t smartdatajobs-war:local .` succeeds and the image
      contains `/opt/wars/SmartDataJobs.war`.
- [ ] The WAR still deploys as application `SmartDataJobs` (context root
      `/SmartDataJobs`, REST base `/smartdatajobs`).
- [ ] `/SmartDataJobs/` and `/SmartDataJobs/smartdatajobs/system/info` return
      200, and the page loads `/SWAC/swac/swac.js` without 404.
- [ ] `/SmartDataJobs/smartdatajobs/system/config` reports
      `"propsloaded":true`.

Stack level:

- [ ] `docker compose up --build -d` in `..` yields two
      healthy services, and `asadmin list-applications` lists all four
      applications.
- [ ] `asadmin list-jdbc-resources` lists `jdbc/SmartUser` and `jdbc/SmartData`.

The complete checklist, including SmartUser, SmartData and SWAC behaviour, is
`../AGENTS.md` §12.

---

## 9. Key files

SmartDataJobs:

```text
pom.xml
Dockerfile              # minimal SmartDataJobs WAR artifact image
.dockerignore
libs/maven-repo/
```

SmartDataDeployment (`..`):

```text
compose.yaml
Dockerfile.payara                       # master Payara image with all WARs
docker/payara/init_0_configure_databases.sh
docker/payara/SmartDataJobs_config.properties
docker/payara/SmartUser_config.properties
docker/payara/SmartData_config.properties
docker/postgres/init-smartuser.sql
scripts/up.sh
scripts/init-smartuser.sh               # one-shot GET system/setup, DB must be empty
```

SmartUser:

```text
pom.xml
Dockerfile
.dockerignore
libs/maven-repo/
src/conf/persistence.xml
src/java/de/fhbielefeld/smartuser/rest/UserRightsResource.java
```

SmartData:

```text
pom.xml                 # replaces build.xml / nbproject for the build
Dockerfile
.dockerignore
libs/maven-repo/        # same helper jars as SmartDataJobs
src/java/               # NetBeans source layout (kept)
web/                    # NetBeans document root (kept)
libs/*.jar              # original third party jars, reference only
```

SWAC:

```text
Dockerfile                      # static WAR artifact image
.dockerignore
docker/WEB-INF/web.xml          # welcome file + MIME mappings
docker/WEB-INF/glassfish-web.xml# pinned context root /SWAC
index.html
swac/
nbproject/project.properties    # declares web.context.root=/SWAC
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
project-local artifact repositories, and `provided` scope for the Jakarta EE
API. The NetBeans directory layout (`src/java`, `src/conf`, `web`) was kept and
wired into the POMs (`<sourceDirectory>`, `<resources>`,
`<warSourceDirectory>`) instead of moving every file into the Maven standard
layout. That keeps the history readable: the migration commit shows the build
files and dependencies, not a wholesale file move.

`SmartData` was converted later than `SmartDataJobs` and `SmartUser`, but with
the same approach: its Ant `build.xml`, `nbproject/` and the sibling project
references are no longer used, and its former `libs/*.jar` files became Maven
dependencies. The NetBeans directory layout (`src/java`, `web`) was kept and
wired into the POM instead of moving files.

`SWAC` is the exception: it was always a NetBeans HTML5 client project and
stays one. It has no build step, and only its document root is packaged, into a
static WAR, for deployment below `/SWAC`.

The Docker setup grew inside `SmartDataJobs` at first: the WAR artifact
`Dockerfile`, the master `Dockerfile.payara`, `compose.yaml`, the Payara init
script, the `*_config.properties`, the PostgreSQL init SQL and the operator
scripts all lived next to the application sources. That mixed a single
application's release cycle with the deployment of four applications plus
infrastructure, so the deployment concern was extracted into the separate
`SmartDataDeployment` repository. Each application repository keeps exactly its
own artifact `Dockerfile`; every cross-component fact (context roots, JNDI
names, config file names, schema ownership, build order, startup order,
healthcheck) lives in `../AGENTS.md`.
