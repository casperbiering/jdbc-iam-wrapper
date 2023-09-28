plugins {
    java
    `maven-publish`
    signing
    id("com.diffplug.spotless") version "6.20.0"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("de.marcphilipp.nexus-publish") version "0.4.0"
    id("io.codearte.nexus-staging") version "0.30.0"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("software.amazon.awssdk:sso:2.20.135")
    implementation("software.amazon.awssdk:ssooidc:2.20.135")
    implementation("software.amazon.awssdk:sts:2.20.135")
    implementation("org.apache.logging.log4j:log4j-slf4j-impl:2.20.0")

    testImplementation("junit:junit:4.12")

    // implementation("org.mariadb.jdbc:mariadb-java-client:2.7.0")
    implementation("mysql:mysql-connector-java:8.0.33")
    // implementation("mysql:mysql-connector-java:5.1.49")
    implementation("org.postgresql:postgresql:42.6.0")
}

group = "dk.biering"

val release: String? by project
val baseVersion = "0.1.5"

version = if (release != null && release!!.toBoolean()) {
    baseVersion
} else {
    "$baseVersion-SNAPSHOT"
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withJavadocJar()
    withSourcesJar()
}

spotless {
    java {
        googleJavaFormat().aosp()
        removeUnusedImports()
    }

    kotlinGradle {
        ktlint()
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set(project.name)
                description.set("Connect to JDBC Drivers with local AWS Profile via IAM RDS token")
                url.set("https://github.com/casperbiering/jdbc-iam-wrapper")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("casperbiering")
                        name.set("Casper Biering")
                        email.set("casper@biering.dk")
                    }
                }
                scm {
                    url.set(pom.url)
                }
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype()
    }
}

val mavenUploadUser: String? by project
val mavenUploadPassword: String? by project
nexusStaging {
    username = mavenUploadUser
    password = mavenUploadPassword
    // Try for 2 minutes
    numberOfRetries = 30
    delayBetweenRetriesInMillis = 4000
}

tasks.closeRepository.configure {
    mustRunAfter(tasks.publish)
}

signing {
    val signingKeyId: String? by project
    val signingKey: String? by project
    val signingPassword: String? by project
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}

tasks.javadoc {
    if (JavaVersion.current().isJava9Compatible) {
        (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
    }
}
