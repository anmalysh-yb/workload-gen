plugins {
	java
	id("org.springframework.boot") version "3.5.6"
	id("io.spring.dependency-management") version "1.1.7"
	id("io.freefair.lombok") version "8.11"
}

group = "com.amalyshev"
version = "0.0.1-SNAPSHOT"
description = "SQL workload generator"

// Target Java 17 bytecode and APIs, but build with whatever JDK 17+ is on the machine. Pinning an
// exact 17 toolchain fails on a box that only has a newer JDK installed.
tasks.withType<JavaCompile> {
	options.release = 17
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.postgresql:postgresql:42.7.4")
	implementation("com.yugabyte:jdbc-yugabytedb:42.7.3-yb-1")
	implementation("org.apache.commons:commons-lang3:3.0")
	implementation("org.apache.commons:commons-collections4:4.4")
	implementation("com.google.guava:guava:32.1.3-jre")
	implementation("info.picocli:picocli:4.7.6")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Stable jar name so workload-gen.sh and any wrapper script can reference it directly.
tasks.bootJar {
	archiveFileName = "workload-gen.jar"
}
