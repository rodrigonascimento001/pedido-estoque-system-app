## Context

The application repository is a Maven multi-module project with a single Dockerfile that builds `pedido-service`, `estoque-service`, or `api-gateway` through the `MODULE` build argument. Its current Compose file combines those applications with Kafka, Zookeeper, Redis, and two PostgreSQL instances. Integration tests use Testcontainers and therefore require Docker. The infrastructure repository is separate and will own its resource Compose file. Application production configuration and deployment target are not yet defined.

## Goals / Non-Goals

**Goals:**
- Validate changes with `mvn -B verify` using Java 21 on GitHub-hosted Linux runners.
- Publish the three application images to private GHCR on changes integrated into `main`.
- Keep CI on `develop` without publishing images from that branch.
- Separate application and infrastructure Compose definitions while preserving a documented local full-stack flow.

**Non-Goals:**
- Deploy automatically to any environment.
- Declare the current Compose configuration production-ready or implement production hardening.
- Choose production hosting, database operations, secret management, or release promotion policy.
- Publish preview images from `develop`.

## Decisions

1. **Run Maven verification on `develop` and `main`.** Use the repository's Maven root and Java 21. `mvn verify` runs the complete reactor lifecycle and the existing integration tests; GitHub-hosted Linux runners provide Docker for Testcontainers. Pull requests targeting either branch may run the same validation so failures are visible before merge. A push to `main` (including a merge) is the image-publishing event.

2. **Publish three images to private GHCR packages on `main`.** Build the existing Dockerfile once per service using `MODULE`, authenticate with the workflow's `GITHUB_TOKEN`, and grant only `contents: read` and `packages: write`. Tag each image with an immutable commit SHA tag and the moving `main` tag. Do not publish from `develop`; do not deploy after publishing. Package visibility must be configured as private in GitHub if it is not already inherited from package settings.

3. **Keep infrastructure Compose in the infrastructure repository and application Compose in this repository.** Adapt the existing Compose definition by moving Kafka, Zookeeper, Redis, PostgreSQL, and their persistent volumes to the infrastructure repository; keep the three application services and their local Docker builds here. Do not duplicate infrastructure service definitions in the application Compose.

4. **Coordinate the two Compose projects through one named external Docker network.** The infrastructure Compose owns/creates the network; application containers join it and address dependencies by stable Compose service DNS names. Document the startup order (infrastructure first, wait for health, then applications) and shutdown commands. Cross-project `depends_on` is unavailable, so application startup coordination cannot rely on it.

5. **Keep CI publication separate from local Compose builds.** The local application Compose remains able to build from the checked-out source and run the principal flow when the infrastructure Compose is running. GHCR publication produces artifacts for later manual use; it does not turn the local Compose into a production deployment definition.

## Risks / Trade-offs

- [Docker network name or service DNS drifts between repositories] → Define the shared network name and dependency aliases as an explicit contract and document them in both repositories.
- [Application starts before infrastructure is ready] → Start infrastructure first, retain resource health checks, and document readiness checks; no cross-project `depends_on` can enforce ordering.
- [Private GHCR images cannot be pulled by a future host] → Publishing uses `GITHUB_TOKEN`; a manual deployment host will need separately configured package-read credentials. Host credentials are outside this change.
- [A multi-module `verify` run takes longer than module-specific tests] → Prefer one root reactor invocation for consistent coverage; optimize only if measured CI time becomes problematic.
- [Production requirements differ from local Compose assumptions] → Keep production hardening explicitly out of scope and treat this split as local orchestration and artifact preparation only.
- [The infrastructure repository is outside this workspace's writable root] → Implementation touching that repository must be performed from its own workspace or with appropriate access; this proposal records the required coordinated change.

## Migration Plan

1. Add and validate the two Compose definitions and shared-network contract across both repositories.
2. Update local instructions to start infrastructure and wait for health before starting application services; verify the complete principal flow.
3. Add the workflow for Maven verification and GHCR image publication, ensuring publication only runs on `main` after verification passes.
4. Merge the infrastructure and application repository changes in a compatible order and confirm private GHCR package permissions.

Rollback: revert the workflow to stop automated publication and restore the prior application Compose from version control. Infrastructure data volumes must not be removed as part of rollback.

## Open Questions

- Confirm the exact infrastructure services and aliases already intended in the infrastructure repository when implementation begins.
- Configure private package visibility and confirm manual pull credentials when the eventual deployment host is selected.
- Decide whether the moving image tag should remain `main` or be renamed before implementation; this design uses `main` as the explicit branch tag.
