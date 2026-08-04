import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("gg.grounds.base-conventions") version "0.8.0"
    id("io.quarkus") version "3.37.3"
}

kotlin { jvmToolchain(25) }

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

tasks.withType<KotlinCompile>().configureEach { compilerOptions.jvmTarget.set(JvmTarget.JVM_25) }

// The OpenAPI snapshot published to groundsgg/api-reference. Quarkus only writes
// `build/generated/openapi/openapi.json` on a production build, and it appends to
// whatever is already there — so a stale file from an earlier build would ship
// endpoints that no longer exist. Clearing first is what makes the snapshot a
// statement about this commit.
val cleanProductionOpenApi =
    tasks.register<Delete>("cleanProductionOpenApi") {
        delete(layout.buildDirectory.dir("generated/openapi"))
        delete(layout.buildDirectory.dir("quarkus"))
        delete(layout.buildDirectory.dir("quarkus-app"))
        delete(layout.buildDirectory.dir("quarkus-build"))
    }

// The ordering covers every `quarkus*` task, not just `quarkusBuild`. The clean
// removes `build/quarkus`, which is where `quarkusGenerateAppModel` writes the
// application model that `quarkusBuild` then reads — constraining only the
// latter lets Gradle run the generator first and delete its output underneath
// it, which fails as "input file does not exist" in any single invocation that
// asks for both `test` and this snapshot.
tasks
    .matching { it.name.startsWith("quarkus") }
    .configureEach { mustRunAfter(cleanProductionOpenApi) }

val quarkusBuild = tasks.named("quarkusBuild")

tasks.register<Copy>("generateOpenApiSnapshot") {
    group = "documentation"
    dependsOn(cleanProductionOpenApi, quarkusBuild)
    from(layout.buildDirectory.file("generated/openapi/openapi.json"))
    into(layout.buildDirectory.dir("api-reference"))
    rename { "openapi.json" }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/*")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
}

// The contract is a moving SNAPSHOT, and Gradle caches changing modules for 24
// hours by default. On a warm cache that means a contract merged and published
// an hour ago is simply not seen: the build compiles against yesterday's proto
// and a new RPC just silently doesn't exist. See service-match for the same fix.
configurations.all { resolutionStrategy.cacheChangingModulesFor(0, "seconds") }

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.37.3"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    // Retained only until plugin-player and plugin-match speak HTTP; the gRPC
    // service is a thin adapter over the same code the REST resources call.
    implementation("io.quarkus:quarkus-grpc")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.nats:jnats:2.26.0")
    implementation("io.quarkus:quarkus-opentelemetry")
    // Prometheus metrics on /q/metrics — JVM, HTTP and the Agroal pool.
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("gg.grounds:library-grpc-contracts-player:0.7.0")
    // Access classes for reads against the EU-resident database. Pinned to a
    // release rather than a SNAPSHOT: what may be served stale is a decision,
    // and a decision should not change because someone merged to main.
    implementation("gg.grounds:library-data-access:0.2.0")

    // JWT validation for incoming calls (v2.2 Service Architecture).
    implementation("com.nimbusds:nimbus-jose-jwt:10.9.1")

    compileOnly("com.google.protobuf:protobuf-kotlin")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.3.0")
}

sourceSets { main { java { srcDirs("build/classes/java/quarkus-generated-sources/grpc") } } }

tasks
    .matching { it.name == "kaptGenerateStubsKotlin" }
    .configureEach {
        dependsOn("quarkusGenerateCode")
        dependsOn("quarkusGenerateCodeDev")
    }
