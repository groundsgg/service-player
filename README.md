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

### Retiring gRPC

The service still implements the `PlayerPresenceService` gRPC contract on the
same port, because `plugin-player` and `plugin-match` speak it. That adapter
holds no rules of its own — both transports call the same `PresenceService` —
so it can be deleted once no caller needs it. The order matters:

1. Release this service. It now answers on both transports.
2. Move `plugin-player` and `plugin-match` to HTTP, and roll the proxies.
3. Delete `gg.grounds.api`, the `quarkus-grpc` dependency, and the
   `library-grpc-contracts-player` dependency.

Doing 3 before 2 takes the network's logins down, since a proxy that cannot
reach the presence service cannot let anyone in.

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
