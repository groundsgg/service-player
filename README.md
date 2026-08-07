# service-player

Quarkus service for network-wide player presence: who is online, on which proxy
and backend server, under what name, and in which language they want to be
spoken to.

## API

REST under `/v1/players`, documented by an OpenAPI snapshot published to
[groundsgg/api-reference](https://github.com/groundsgg/api-reference) on every
release. Rendering stays central — this service serves no Swagger UI of its own.

Generate the snapshot locally:

```bash
./gradlew generateOpenApiSnapshot   # -> build/api-reference/openapi.json
```

Callers authenticate with the projected workload token from
`/var/run/secrets/grounds/token`, sent as a bearer. It is verified against the
cluster's JWKS with the `grounds-services` audience.

HTTP is the only transport. The `PlayerPresenceService` gRPC adapter was
removed once `plugin-player` (1.0.0) and `plugin-match` (0.6.0) had moved to
REST and the proxies had been rolled onto those jars; the rules it wrapped
always lived in `PresenceService`, which the REST resources call directly.

## Development

Run in dev mode with live reload:

```bash
./gradlew --console=plain quarkusDev
```

Run in dev mode with live reload using DevSpace in a Kubernetes cluster (Initial build may take some time):

```bash
devspace use namespace api
devspace dev
```

## License

Licensed under the GNU Affero General Public License v3.0
