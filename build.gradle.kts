plugins {
    java
    id("io.quarkus") version "3.30.4"
}

group = "gg.grounds"
version = "1.0.0-SNAPSHOT"

repositories {
    maven {
        url = uri("https://maven.pkg.github.com/groundsgg/grpc-contracts")
        credentials {
            username = providers.gradleProperty("github.user").get()
            password = providers.gradleProperty("github.token").get()
        }
    }
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.30.5"))
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-grpc")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("gg.grounds:grpc-contracts-player:0.0.2")
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

sourceSets {
    main {
        java {
            srcDirs("build/classes/java/quarkus-generated-sources/grpc")
        }
    }
}

tasks.test {
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    testLogging {
        // Show assertion diffs in test output
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.quarkusDev {
    jvmArgs = listOf("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}
