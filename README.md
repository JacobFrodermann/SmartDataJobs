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

Build the WAR first:

```bash
mvn clean package
```

Then start Payara with the WAR deployed:

```bash
docker compose up -d
```

Payara automatically deploys every application found in
`/opt/payara/deployments`. The compose file bind-mounts
`target/SmartDataJobs.war` into that directory, so no manual deployment step
is required.

Useful URLs:

- Application: <http://localhost:8080/SmartDataJobs/>
- REST API: <http://localhost:8080/SmartDataJobs/smartdatajobs/system/info>
- Payara admin console: <http://localhost:4848> (`admin` / `admin`)

After rebuilding the WAR, restart the container to redeploy the new version:

```bash
mvn clean package
docker compose restart
```

Stop the container:

```bash
docker compose down
```

The exposed ports can be changed with `SMARTDATAJOBS_HTTP_PORT` and
`SMARTDATAJOBS_ADMIN_PORT`.

## Project layout

- `src/main/java` – Java sources
- `src/main/resources` – application resources
- `src/main/webapp` – static web content and `WEB-INF`
- `libs/maven-repo` – project-local Maven repository for the Smart* helper
  libraries (`NGI_Logger`, `NGI_RestUtils`, `NGI_AdvancedReflection`,
  `SmartUserAuth`)

For more documentation see the project wiki.
