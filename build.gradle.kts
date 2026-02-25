plugins {
    id("gg.grounds.root") version "0.1.1"
    id("io.quarkus") version "3.30.6"
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

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.32.1"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-grpc")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-kotlin")
    implementation("gg.grounds:library-grpc-contracts-player:0.1.0")

    compileOnly("com.google.protobuf:protobuf-kotlin")

    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.2.2")
}

sourceSets { main { java { srcDirs("build/classes/java/quarkus-generated-sources/grpc") } } }

tasks
    .matching { it.name == "kaptGenerateStubsKotlin" }
    .configureEach {
        dependsOn("quarkusGenerateCode")
        dependsOn("quarkusGenerateCodeDev")
    }
