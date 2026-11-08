## ADDED Requirements

### Requirement: Validate application changes with Maven
The application repository SHALL run `mvn -B verify` with Java 21 for changes targeting the `develop` and `main` branches. Verification SHALL complete successfully before images can be published.

#### Scenario: Change is integrated into develop
- **WHEN** a change is pushed to or merged into `develop`
- **THEN** the workflow runs the complete Maven verify lifecycle, including the project's integration tests
- **AND** the workflow does not publish container images

#### Scenario: Change is integrated into main
- **WHEN** a change is pushed to or merged into `main`
- **THEN** the workflow runs the complete Maven verify lifecycle before starting image publication

### Requirement: Publish application images to private GHCR
The application repository SHALL publish images for `pedido-service`, `estoque-service`, and `api-gateway` to GitHub Container Registry only for successful changes integrated into `main`. Each image SHALL have an immutable commit-SHA tag and the moving `main` tag. Packages SHALL be private, and workflow permissions SHALL be limited to those needed to read the repository and publish packages.

#### Scenario: Main verification succeeds
- **WHEN** Maven verification succeeds for a change integrated into `main`
- **THEN** the workflow builds and publishes one image for each of the three services to GHCR
- **AND** each image is tagged with the commit SHA and `main`

#### Scenario: Main verification fails
- **WHEN** Maven verification fails for a change integrated into `main`
- **THEN** the workflow does not publish or update any GHCR image tags

#### Scenario: Change is integrated into develop
- **WHEN** a change is pushed to or merged into `develop`
- **THEN** the workflow does not publish preview or release images to GHCR

### Requirement: Keep image publication separate from deployment
The workflow SHALL NOT deploy application images to a runtime environment.

#### Scenario: Images are published successfully
- **WHEN** all three images are published to GHCR
- **THEN** the workflow completes without initiating a deployment
