# SmartDataJobs

REST API for controlling and executing recurring jobs.

## Build

The project is built with Maven and no longer requires NetBeans or Ant.

Requirements:

- JDK 17 or newer
- Maven 3.9 or newer

Build the deployable WAR:

```bash
mvn clean package
```

The resulting artifact is `target/SmartDataJobs.war`.

The WAR is built for a Jakarta EE 10 application server such as Payara 6/7.
The Jakarta EE API itself is `provided` and is therefore not bundled into the
WAR.

## Run with Docker Compose

The compose setup starts Payara with both SmartDataJobs and SmartUser, plus a
PostgreSQL database for SmartUser.

### Prerequisites

- Docker
- Maven 3.9 or newer only for local WAR builds or IDE work

Both applications are packaged as minimal artifact images containing only
their WAR files:

```bash
docker build -t smartuser-war:local ../SmartUser
docker build -t smartdatajobs-war:local .
```

Then build the master Payara image and start everything:

```bash
docker compose up --build -d
```

Or use the helper script:

```bash
./scripts/up.sh
```

What happens:

- `../SmartUser` is built into the `smartuser-war:local` artifact image.
- SmartDataJobs is built into the `smartdatajobs-war:local` artifact image.
- The master `Dockerfile.payara` builds a Payara image and copies both WARs
  from those artifact images.
- Payara auto-deploys both WARs from `/opt/payara/deployments`.
- PostgreSQL is started and the `smartuser` schema is initialized.
- A Payara JDBC connection pool and the `jdbc/SmartUser` resource are
  configured automatically before deployment.

`Dockerfile` builds the minimal SmartDataJobs WAR artifact image.
`Dockerfile.payara` builds the master Payara container that runs both
applications.

Useful URLs:

- SmartDataJobs: <http://localhost:8080/SmartDataJobs/>
- SmartDataJobs REST API: <http://localhost:8080/SmartDataJobs/smartdatajobs/system/info>
- SmartUser: <http://localhost:8080/SmartUser/>
- SmartUser REST API: <http://localhost:8080/SmartUser/smartuser/system/info>
- Payara admin console: <http://localhost:4848> (`admin` / `admin`)

After changing SmartUser, rebuild its artifact image and the master Payara
image:

```bash
docker build -t smartuser-war:local ../SmartUser
docker compose up --build -d
```

After changing SmartDataJobs, rebuild its artifact image and the master Payara
image:

```bash
docker build -t smartdatajobs-war:local .
docker compose up --build -d
```

Stop the services:

```bash
docker compose down
```

Reset the database as well:

```bash
docker compose down -v
```

The exposed ports can be changed with `SMARTDATAJOBS_HTTP_PORT` and
`SMARTDATAJOBS_ADMIN_PORT`. Database credentials can be changed with
`SMARTUSER_DB_NAME`, `SMARTUSER_DB_USER`, and `SMARTUSER_DB_PASSWORD`.

## Project layout

- `src/main/java` – Java sources
- `src/main/resources` – application resources
- `src/main/webapp` – static web content and `WEB-INF`
- `libs/maven-repo` – project-local Maven repository for the Smart* helper
  libraries (`NGI_Logger`, `NGI_RestUtils`, `NGI_AdvancedReflection`,
  `SmartUserAuth`)
- `Dockerfile` – minimal SmartDataJobs WAR artifact image
- `Dockerfile.payara` – master Payara image containing both WARs
- `docker/payara` – Payara startup configuration for the SmartUser database
- `docker/postgres` – PostgreSQL initialization
- `scripts/up.sh` – builds both artifact images and starts the compose setup

For more documentation see the project wiki.
