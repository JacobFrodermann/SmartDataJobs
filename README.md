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

## Docker artifact image

`Dockerfile` builds the SmartDataJobs WAR artifact image:

```bash
docker build -t smartdatajobs-war:local .
```

The image only contains `/opt/wars/SmartDataJobs.war`. It does not run Payara.

## Full stack

SmartDataJobs runs together with SmartUser, SmartData and SWAC on Payara 6
with PostgreSQL. Everything needed to run that stack — Docker Compose, the
master Payara image, the JDBC resource configuration, the configuration
properties, the database bootstrap and the operator scripts — lives in the
separate repository **`SmartDataDeployment`** (`..`).

```bash
cd ..
./scripts/up.sh             # build all artifact images and start the stack
./scripts/init-smartuser.sh # once, on a fresh database
```

After changing SmartDataJobs, rebuild its artifact image and restart the stack:

```bash
docker build -t smartdatajobs-war:local .
cd ..
docker compose up --build -d
```

Deployed URLs:

- SmartDataJobs: <http://localhost:8080/SmartDataJobs/>
- SmartDataJobs REST API: <http://localhost:8080/SmartDataJobs/smartdatajobs/system/info>
- SmartUser: <http://localhost:8080/SmartUser/>
- SmartData: <http://localhost:8080/SmartData/>
- SWAC: <http://localhost:8080/SWAC/>
- Payara admin console: <http://localhost:4848> (`admin` / `admin`)

See `../AGENTS.md` for the architecture, the integration
contracts between the applications and the verification checklist.

## Project layout

- `src/java` – Java sources and their resource files (`hibernate.properties`)
- `web` – static web content, `WEB-INF` and `configuration.js`
- `libs/maven-repo` – project-local Maven repository for the Smart* helper
  libraries (`NGI_Logger`, `NGI_RestUtils`, `NGI_AdvancedReflection`,
  `SmartUserAuth`)
- `Dockerfile` – minimal SmartDataJobs WAR artifact image
- `AGENTS.md` – architecture and maintenance notes

For more documentation see the project wiki.
