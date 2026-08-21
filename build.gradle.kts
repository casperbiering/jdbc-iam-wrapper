import java.time.Duration

plugins {
    java
    `maven-publish`
    signing
    alias(libs.plugins.spotless)
    alias(libs.plugins.shadow)
    alias(libs.plugins.nexus.publish)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.awssdk.sso)
    implementation(libs.awssdk.ssooidc)
    implementation(libs.awssdk.sts)
    implementation(libs.log4j.slf4j.impl)

    testImplementation(libs.junit)

    // implementation(libs.mariadb.client)
    implementation(libs.mysql.client)
    // implementation("mysql:mysql-connector-java:5.1.49")
    implementation(libs.postgresql.client)
}

group = "dk.biering"

val release = project.findProperty("release") as String?
val baseVersion = "0.2.0" // REMEMBER TO UPDATE IN IamWrapper.java

version =
    if (release != null && release.toBoolean()) {
        baseVersion
    } else {
        "$baseVersion-SNAPSHOT"
    }

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
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

val mavenUploadUser = project.findProperty("mavenUploadUser") as String?
val mavenUploadPassword = project.findProperty("mavenUploadPassword") as String?

nexusPublishing {
    repositories {
        sonatype {
            username.set(mavenUploadUser)
            password.set(mavenUploadPassword)
        }
    }
    // Try for 2 minutes
    clientTimeout.set(Duration.ofMinutes(2))
}

signing {
    val signingKeyId = project.findProperty("signingKeyId") as String?
    val signingKey = project.findProperty("signingKey") as String?
    val signingPassword = project.findProperty("signingPassword") as String?
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(publishing.publications["mavenJava"])
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("html5", true)
}
