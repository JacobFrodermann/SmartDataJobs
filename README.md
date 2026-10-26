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

## Project layout

- `src/main/java` – Java sources
- `src/main/resources` – application resources
- `src/main/webapp` – static web content and `WEB-INF`
- `libs/maven-repo` – project-local Maven repository for the Smart* helper
  libraries (`NGI_Logger`, `NGI_RestUtils`, `NGI_AdvancedReflection`,
  `SmartUserAuth`)

For more documentation see the project wiki.
